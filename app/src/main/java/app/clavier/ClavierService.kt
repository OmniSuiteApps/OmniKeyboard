package app.clavier

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
    private var view: View? = null

    private var suggest = false
    private var learn = false
    private var pickedLast = false

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        spell = SpellSuggester(this) { word, words -> if (word == currentWord()) showSuggestions(word, words) }
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
        if (suggest) lifecycleScope.launch {
            userDictionary = withContext(Dispatchers.IO) { readUserDictionary(this@ClavierService) }
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

    /** Majuscule automatique et suggestions, recalculées après chaque changement du texte ou du curseur. */
    private fun refresh() {
        val ic = currentInputConnection ?: return
        if (state.shift != Shift.LOCK) {
            val caps = prefs.value.autoCap && ic.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) != 0
            state.shift = if (caps) Shift.ONCE else Shift.OFF
        }
        val word = currentWord()
        if (!suggest || word.isEmpty()) { state.suggestions = emptyList(); return }
        showSuggestions(word, emptyList())
        spell?.request(word)
    }

    private fun showSuggestions(word: String, spellWords: List<String>) {
        val known = HashMap(userDictionary).apply { learned.snapshot().forEach { (w, n) -> merge(w, n * 20, Int::plus) } }
        state.suggestions = Suggestions.merge(word, spellWords, Suggestions.completions(word, known))
    }

    private fun learnCurrent() {
        if (learn) learned.learn(currentWord())
    }

    // KeyActions

    override fun text(s: String) {
        val ic = currentInputConnection ?: return
        // Après une suggestion choisie, « . » et « , » se collent au mot
        if (pickedLast && (s == "." || s == ",") && before(1) == " ") ic.deleteSurroundingText(1, 0)
        if (!s[0].isLetterOrDigit() && s[0] !in "'’-") learnCurrent()
        ic.commitText(if (pickedLast && (s == "." || s == ",")) "$s " else s, 1)
        pickedLast = false
        if (state.shift == Shift.ONCE) state.shift = Shift.OFF
    }

    override fun key(code: Code) {
        val ic = currentInputConnection ?: return
        when (code) {
            Code.SHIFT -> state.shift = if (state.shift == Shift.OFF) Shift.ONCE else Shift.OFF
            Code.DELETE -> {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            Code.SPACE -> {
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
            Code.TEXT -> Unit
        }
        pickedLast = false
    }

    override fun longPress(code: Code) {
        when (code) {
            Code.SHIFT -> state.shift = Shift.LOCK
            Code.SPACE -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            Code.SYMBOLS, Code.LETTERS -> startActivity(
                android.content.Intent(this, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> key(code)
        }
    }

    override fun moveCursor(steps: Int) {
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun deleteWord() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(TextRules.wordDeleteLength(before()), 0)
    }

    override fun pick(suggestion: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(currentWord().length, 0)
        ic.commitText("$suggestion ", 1)
        ic.endBatchEdit()
        if (learn) learned.learn(suggestion)
        pickedLast = true
    }

    override fun feedback() {
        val p = prefs.value
        if (p.vibrate) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (p.sound) getSystemService(AudioManager::class.java).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }
}
