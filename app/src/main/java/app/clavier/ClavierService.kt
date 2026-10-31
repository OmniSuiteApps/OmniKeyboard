package app.clavier

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClavierService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, KeyActions {
    // Compose a besoin d'un cycle de vie ; un service de saisie n'en a pas, on le fournit
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = savedState.savedStateRegistry

    private val state = KeyboardState()
    private val prefs by lazy { keyboardPrefs().stateIn(lifecycleScope, SharingStarted.Eagerly, KeyboardPrefs()) }
    private val learned by lazy { LearnedWords.get(this) }
    private var userDictionary: Map<String, Int> = emptyMap()
    private var spell: SpellSuggester? = null
    private var glideSpell: SpellSuggester? = null
    private var view: View? = null

    // Index des mots connus, reconstruit en arrière-plan quand un mot est appris ou le dictionnaire relu
    private var index = WordIndex(emptyMap())
    private var dictStamp = 0
    private var indexStamp = -1
    private var indexing = false

    /** Dernière réponse du correcteur : gardée pour ne pas vider la barre en attendant la suivante. */
    private var lastSpell: SpellResult? = null
    /** Mot remplacé par la correction automatique, annulable par ⌫ juste après. */
    private var undo: Pair<String, String>? = null
    /** Mots dont l'utilisateur a annulé la correction : plus corrigés jusqu'à l'arrêt du clavier. */
    private val rejected = HashSet<String>()
    /** Mot posé par la saisie gestuelle : la barre garde ses variantes tant qu'il est sous le curseur. */
    private var glided: String? = null
    /** Tracé qui attend le correcteur : son ébauche et de quoi poser le mot. */
    private var pendingGlide: Pair<String, (List<String>) -> Unit>? = null
    private var clipAt = 0L

    private var suggest = false
    private var learn = false
    private var pickedLast = false

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboard.primaryClip ?: return@OnPrimaryClipChangedListener
        // Les applis marquent mots de passe et codes comme sensibles : on ne les garde pas
        if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true || clip.itemCount == 0) return@OnPrimaryClipChangedListener
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() } ?: return@OnPrimaryClipChangedListener
        state.clips = (listOf(text) + state.clips.filter { it != text }).take(10)
        state.recentClip = text
        clipAt = android.os.SystemClock.uptimeMillis()
    }

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // Nouvelle langue d'écriture : nouvelle session du correcteur
        lifecycleScope.launch {
            prefs.map { it.language }.distinctUntilChanged().collect { language ->
                spell?.close()
                glideSpell?.close()
                lastSpell = null
                spell = SpellSuggester(this@ClavierService, language.locale) { r ->
                    lastSpell = r
                    if (r.word == currentWord()) showSuggestions(r.word)
                }
                // Une réponse pour un autre tracé que celui qui attend est ignorée
                glideSpell = SpellSuggester(this@ClavierService, language.locale) { r ->
                    pendingGlide?.takeIf { it.first == r.word }?.let { pendingGlide = null; it.second(r.suggestions) }
                }
            }
        }
        clipboard.addPrimaryClipChangedListener(clipListener)
        prefs // démarre la lecture des réglages tout de suite
    }

    override fun onCreateInputView(): View {
        window.window!!.decorView.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setContent {
                val p by prefs.collectAsState()
                Keyboard(state, p, this@ClavierService)
            }
        }.also { view = it }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        // Jamais de suggestions ni d'apprentissage dans un mot de passe ou en navigation privée
        suggest = prefs.value.suggestions && cls == InputType.TYPE_CLASS_TEXT && !password
        learn = suggest && prefs.value.learn && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        state.page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) Page.SYMBOLS else Page.LETTERS
        state.enter = enterAction(info)
        state.shift = Shift.OFF
        pickedLast = false
        undo = null
        glided = null
        if (suggest) lifecycleScope.launch {
            userDictionary = withContext(Dispatchers.IO) { readUserDictionary(this@ClavierService, prefs.value.language.locale) }
            dictStamp++
            index()
        }
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        state.suggestions = emptyList()
        lifecycleScope.launch(Dispatchers.IO) { learned.save() }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refresh()
    }

    override fun onDestroy() {
        learned.save()
        spell?.close()
        glideSpell?.close()
        clipboard.removePrimaryClipChangedListener(clipListener)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun enterAction(info: EditorInfo): EnterAction {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.NEWLINE
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            else -> EnterAction.NEWLINE
        }
    }

    private fun before(n: Int = 64): CharSequence = currentInputConnection?.getTextBeforeCursor(n, 0) ?: ""

    private fun currentWord() = TextRules.currentWord(before())

    /** L'index à jour s'il est prêt, sinon l'ancien pendant qu'on reconstruit le nouveau. */
    private fun index(): WordIndex {
        val stamp = learned.version + dictStamp * 1_000_000
        if (stamp != indexStamp && !indexing) {
            indexing = true
            val dict = userDictionary
            lifecycleScope.launch {
                // Mots appris pondérés plus fort : ils viennent de l'utilisateur
                index = withContext(Dispatchers.Default) {
                    WordIndex(HashMap(dict).apply { learned.snapshot().forEach { (w, n) -> merge(w, n * 20, Int::plus) } })
                }
                indexStamp = stamp
                indexing = false
            }
        }
        return index
    }

    /** Majuscule automatique et suggestions, recalculées après chaque changement du texte ou du curseur. */
    private fun refresh() {
        val ic = currentInputConnection ?: return
        if (state.shift != Shift.LOCK) {
            val caps = prefs.value.autoCap && ic.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) != 0
            state.shift = if (caps) Shift.ONCE else Shift.OFF
        }
        // Le dernier texte copié ne reste proposé que 5 minutes (il peut s'agir d'un mot de passe non signalé)
        if (state.recentClip != null && android.os.SystemClock.uptimeMillis() - clipAt > 5 * 60_000) state.recentClip = null
        if (!suggest) { state.suggestions = emptyList(); return }
        val before = before()
        val word = TextRules.currentWord(before)
        if (glided != null && word == glided) return
        glided = null
        if (word.isEmpty()) {
            // Mot suivant : d'après les enchaînements appris
            state.suggestions = TextRules.previousWord(before)?.takeIf { prefs.value.predict }?.let { learned.next(it) }.orEmpty()
            return
        }
        showSuggestions(word)
        spell?.request(word, before)
    }

    private fun showSuggestions(word: String) {
        // En attendant la réponse pour ce mot, on garde celles du mot d'avant qui collent encore : la barre ne clignote pas
        val spellWords = lastSpell?.let { r ->
            if (r.word == word) r.suggestions
            else if (word.startsWith(r.word)) r.suggestions.filter { Suggestions.fold(it).startsWith(Suggestions.fold(word)) }
            else null
        }.orEmpty()
        state.suggestions = Suggestions.merge(word, spellWords, index().completions(word))
    }

    private fun learnCurrent() {
        if (!learn) return
        val before = before()
        learned.learn(TextRules.currentWord(before), TextRules.previousWord(before))
    }

    /** Remplace le mot en cours par la correction du correcteur, juste avant un espace ou une ponctuation. */
    private fun autoCorrect() {
        if (!suggest || !prefs.value.autoCorrect) return
        val word = currentWord()
        // Un mot appris ne compte qu'après 3 fois : une faute tapée trop vite pour être corrigée ne devient pas « connue »
        val known = { w: String -> w in userDictionary || w.lowercase() in userDictionary || learned.count(w) >= 3 || w.lowercase() in rejected }
        val fixed = Suggestions.autoCorrection(word, lastSpell, known) ?: return
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fixed, 1)
        undo = word to fixed
    }

    /** ⌫ juste après une correction : on remet le mot tapé (sans l'espace) et on ne le corrige plus. */
    private fun undoCorrection(): Boolean {
        val (word, fixed) = undo ?: return false
        undo = null
        val before = before(fixed.length + 2)
        val sep = before.length - before.trimEnd(' ', ',', '.', '!', '?', ';', ':').length
        if (sep != 1 || !before.dropLast(1).endsWith(fixed)) return false
        val ic = currentInputConnection ?: return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(fixed.length + 1, 0)
        ic.commitText(word, 1)
        ic.endBatchEdit()
        rejected += word.lowercase()
        if (learn) learned.learn(word)
        return true
    }

    // KeyActions

    override fun text(s: String) {
        val ic = currentInputConnection ?: return
        // Après une suggestion choisie, « . » et « , » se collent au mot
        if (pickedLast && (s == "." || s == ",") && before(1) == " ") ic.deleteSurroundingText(1, 0)
        undo = null
        glided = null
        if (!s[0].isLetterOrDigit() && s[0] !in "'’-") {
            if (s.length == 1) autoCorrect()
            learnCurrent()
        }
        ic.commitText(if (pickedLast && (s == "." || s == ",")) "$s " else s, 1)
        pickedLast = false
        if (state.shift == Shift.ONCE) state.shift = Shift.OFF
    }

    override fun key(code: Code) {
        val ic = currentInputConnection ?: return
        if (code == Code.DELETE && undoCorrection()) return
        if (code != Code.SHIFT) { undo = null; glided = null }
        when (code) {
            Code.SHIFT -> state.shift = if (state.shift == Shift.OFF) Shift.ONCE else Shift.OFF
            Code.DELETE -> {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            Code.SPACE -> {
                autoCorrect()
                learnCurrent()
                if (prefs.value.doubleSpacePeriod && TextRules.doubleSpacePeriod(before(2))) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                } else ic.commitText(" ", 1)
            }
            Code.ENTER -> {
                learnCurrent()
                val info = currentInputEditorInfo
                val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                if (state.enter != EnterAction.NEWLINE && action != EditorInfo.IME_ACTION_NONE) ic.performEditorAction(action)
                else sendKeyChar('\n')
            }
            Code.SYMBOLS -> state.page = Page.SYMBOLS
            Code.LETTERS -> state.page = Page.LETTERS
            Code.EMOJI -> state.page = Page.EMOJI
            Code.CLIPBOARD -> state.page = if (state.page == Page.CLIPBOARD) Page.LETTERS else Page.CLIPBOARD
            Code.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Code.TEXT -> Unit
        }
        pickedLast = false
    }

    override fun longPress(code: Code) {
        when (code) {
            Code.SHIFT -> state.shift = Shift.LOCK
            Code.SPACE -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            Code.SYMBOLS, Code.LETTERS -> key(Code.SETTINGS)
            else -> key(code)
        }
    }

    override fun moveCursor(steps: Int) {
        undo = null
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun deleteWord() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(TextRules.wordDeleteLength(before()), 0)
    }

    override fun pick(suggestion: String) {
        val ic = currentInputConnection ?: return
        val before = before()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(TextRules.currentWord(before).length, 0)
        ic.commitText("$suggestion ", 1)
        ic.endBatchEdit()
        if (learn) learned.learn(suggestion, TextRules.previousWord(before))
        pickedLast = true
        undo = null
        glided = null
    }

    override fun glide(path: List<Pt>, keys: Map<Char, Pt>, keyWidth: Float) {
        undo = null
        val sketch = Glide.sketch(path, keys)
        val first = sketch.firstOrNull() ?: return
        // Candidats : mes mots qui commencent par la bonne lettre, plus ce que le correcteur tire de l'ébauche du tracé
        val local = index().startingWith(first).toMap()
        val commit = { spellWords: List<String> ->
            val candidates = HashMap(local)
            spellWords.forEachIndexed { i, w -> candidates.merge(w, 30 - 5 * i, ::maxOf) }
            if (candidates.isEmpty()) candidates[sketch] = 0
            val ranked = Glide.rank(path, keys, keyWidth, candidates).ifEmpty { listOf(sketch) }
            commitGlide(ranked)
        }
        // Jamais de correcteur dans un mot de passe : il recevrait le texte du champ
        val g = glideSpell?.takeIf { suggest && it.available }
        // Un tracé encore en attente est posé tel quel avant le nouveau
        pendingGlide?.second?.invoke(emptyList())
        pendingGlide = null
        if (g == null) commit(emptyList()) else {
            pendingGlide = sketch to commit
            val before = before()
            g.request(sketch, if (before.isEmpty() || before.last() == ' ') "$before$sketch" else "$before $sketch")
        }
    }

    private fun commitGlide(ranked: List<String>) {
        val ic = currentInputConnection ?: return
        val shift = state.shift
        val cased = ranked.map { if (shift == Shift.LOCK) it.uppercase() else if (shift == Shift.ONCE) it.replaceFirstChar(Char::uppercase) else it }
        val last = before(1).lastOrNull()
        // Deux mots tracés à la suite : l'espace se met tout seul
        if (last != null && last.isLetterOrDigit()) learnCurrent()
        ic.commitText(if (last != null && !last.isWhitespace() && last !in "'’-(") " ${cased[0]}" else cased[0], 1)
        glided = cased[0]
        // Le mot choisi au centre, les autres possibilités autour
        if (suggest) state.suggestions = listOfNotNull(cased.getOrNull(1), cased[0], cased.getOrNull(2))
        if (shift == Shift.ONCE) state.shift = Shift.OFF
        pickedLast = false
    }

    override fun feedback() {
        val p = prefs.value
        if (p.vibrate) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (p.sound) getSystemService(AudioManager::class.java).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }
}
