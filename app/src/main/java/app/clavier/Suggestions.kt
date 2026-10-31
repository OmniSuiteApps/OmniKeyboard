package app.clavier

import android.content.Context
import android.provider.UserDictionary
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.io.File
import java.text.Normalizer

object Suggestions {
    /** Clé de comparaison sans accents ni casse : « ecole » trouve « école ». */
    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filter { it.code !in 0x300..0x36F }

    /**
     * Barre de suggestions : le mot tapé, puis les corrections du correcteur du téléphone et les complétions locales,
     * sans doublons, dans la casse du mot tapé.
     */
    fun merge(typed: String, spell: List<String>, local: List<String>, slots: Int = 3): List<String> {
        if (typed.isEmpty()) return emptyList()
        val seen = mutableSetOf(typed.lowercase())
        val others = (spell.take(2) + local + spell.drop(2)).map { TextRules.matchCase(typed, it) }.filter { seen.add(it.lowercase()) }
        return listOf(typed) + others.take(slots - 1)
    }

    /** Distance d'édition entre deux mots ; deux lettres inversées (« bonjuor ») ne comptent que pour une faute. */
    fun editDistance(a: String, b: String): Int {
        var older = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) cur[j] = minOf(cur[j], older[j - 2] + 1)
            }
            older = prev
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * Correction automatique à l'espace : seulement si le correcteur trouve le mot faux et que sa première proposition
     * est proche (ou qu'il la recommande lui-même). Les mots connus localement ne sont jamais corrigés.
     */
    fun autoCorrection(typed: String, result: SpellResult?, known: (String) -> Boolean): String? {
        if (result == null || result.word != typed || typed.length < 2 || result.inDictionary || known(typed)) return null
        val top = result.suggestions.firstOrNull() ?: return null
        val close = editDistance(fold(typed), fold(top)) <= maxOf(1, typed.length / 4)
        if (!(result.recommended || result.typo && close)) return null
        return TextRules.matchCase(typed, top).takeIf { it != typed }
    }
}

/** Mots connus triés par clé sans accents : la complétion d'un préfixe est une recherche dichotomique. */
class WordIndex(words: Map<String, Int>) {
    private val keys: Array<String>
    private val entries: Array<Pair<String, Int>>
    private val lower = HashSet<String>()

    init {
        val sorted = words.entries.map { Triple(Suggestions.fold(it.key), it.key, it.value) }.sortedBy { it.first }
        keys = Array(sorted.size) { sorted[it].first }
        entries = Array(sorted.size) { sorted[it].second to sorted[it].third }
        words.keys.mapTo(lower) { it.lowercase() }
    }

    operator fun contains(word: String) = word.lowercase() in lower

    fun completions(typed: String, max: Int = 5): List<String> {
        val key = Suggestions.fold(typed)
        if (key.isEmpty()) return emptyList()
        var i = keys.binarySearch(key).let { if (it < 0) -it - 1 else it }
        val found = ArrayList<Pair<String, Int>>()
        while (i < keys.size && keys[i].startsWith(key)) {
            if (entries[i].first.length > typed.length) found += entries[i]
            i++
        }
        return found.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** Mots qui commencent par cette lettre, accents compris (pour la saisie gestuelle). */
    fun startingWith(first: Char): List<Pair<String, Int>> {
        val key = Suggestions.fold(first.toString())
        var i = keys.binarySearch(key).let { if (it < 0) -it - 1 else it }
        val found = ArrayList<Pair<String, Int>>()
        while (i < keys.size && keys[i].startsWith(key)) found += entries[i++]
        return found
    }
}

/** Mots tapés souvent et mots qui se suivent, gardés dans des fichiers privés de l'appli (jamais envoyés). */
class LearnedWords internal constructor(private val file: File, private val pairsFile: File) {
    private val counts = HashMap<String, Int>()
    private val pairs = HashMap<String, HashMap<String, Int>>()
    private var dirty = false

    /** Change à chaque mot appris : le clavier sait quand reconstruire son index. */
    @Volatile var version = 0
        private set

    init {
        runCatching {
            file.forEachLine { line -> line.split('\t').takeIf { it.size == 2 }?.let { (w, n) -> n.toIntOrNull()?.let { counts[w] = it } } }
        }
        runCatching {
            pairsFile.forEachLine { line ->
                line.split('\t').takeIf { it.size == 3 }?.let { (a, b, n) -> n.toIntOrNull()?.let { pairs.getOrPut(a) { HashMap() }[b] = it } }
            }
        }
    }

    val size get() = synchronized(this) { counts.size }

    fun snapshot(): Map<String, Int> = synchronized(this) { HashMap(counts) }

    /** Nombre de fois où le mot a été tapé, sans tenir compte de la casse. */
    fun count(word: String): Int = synchronized(this) { counts[word] ?: counts[word.lowercase()] ?: 0 }

    /** `previous` : le mot d'avant dans la même phrase, pour apprendre l'enchaînement. */
    fun learn(word: String, previous: String? = null): Unit = synchronized(this) {
        if (!TextRules.learnable(word)) return
        // Sans mot avant (début de phrase, après une ponctuation), la majuscule n'en dit rien : on apprend en minuscules
        val key = if (previous == null || word.lowercase() in counts) word.lowercase() else word
        counts[key] = (counts[key] ?: 0) + 1
        if (previous != null && TextRules.learnable(previous)) {
            val next = pairs.getOrPut(previous.lowercase()) { HashMap() }
            next[key] = (next[key] ?: 0) + 1
        }
        dirty = true
        version++
        Unit
    }

    /** Mots qui suivent le plus souvent `previous`. */
    fun next(previous: String, max: Int = 3): List<String> = synchronized(this) {
        pairs[previous.lowercase()]?.entries?.sortedByDescending { it.value }?.take(max)?.map { it.key }.orEmpty()
    }

    fun save() {
        // Le texte est préparé sous verrou, écrit hors verrou : la frappe n'attend pas le disque
        val (words, next) = synchronized(this) {
            if (!dirty) return
            if (counts.size > MAX_WORDS) prune()
            dirty = false
            counts.entries.joinToString("") { "${it.key}\t${it.value}\n" } to
                buildString { pairs.forEach { (a, next) -> next.forEach { (b, n) -> append("$a\t$b\t$n\n") } } }
        }
        // Stockage plein : on garde l'ancien fichier et on réessaiera à la prochaine fermeture, sans planter le clavier
        runCatching { write(file, words); write(pairsFile, next) }.onFailure { synchronized(this) { dirty = true } }
    }

    /** Au-delà de MAX_WORDS, on oublie les mots et enchaînements vus une seule fois (fautes, noms de passage). */
    private fun prune() {
        counts.values.removeIf { it <= 1 }
        pairs.values.forEach { it.values.removeIf { n -> n <= 1 } }
        pairs.values.removeIf { it.isEmpty() }
        version++
    }

    private fun write(target: File, text: String) {
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }

    fun clear() = synchronized(this) {
        counts.clear()
        pairs.clear()
        file.delete()
        pairsFile.delete()
        dirty = false
        version++
        Unit
    }

    companion object {
        private const val MAX_WORDS = 20_000
        @Volatile private var instance: LearnedWords? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            val dir = context.applicationContext.filesDir
            instance ?: LearnedWords(File(dir, "mots_appris.tsv"), File(dir, "mots_suivants.tsv")).also { instance = it }
        }
    }
}

/**
 * Dictionnaire personnel d'Android : seul le clavier actif a le droit de le lire. Avec une langue, on garde ses mots
 * et ceux ajoutés « pour toutes les langues ».
 */
fun readUserDictionary(context: Context, language: java.util.Locale?): Map<String, Int> = runCatching {
    val lang = language?.language
    context.contentResolver.query(
        UserDictionary.Words.CONTENT_URI,
        arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY),
        lang?.let { "${UserDictionary.Words.LOCALE} IS NULL OR ${UserDictionary.Words.LOCALE} LIKE ?" },
        lang?.let { arrayOf("$it%") }, null,
    )?.use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0) ?: continue, c.getInt(1)) }
    }
}.getOrNull().orEmpty()

/** Réponse du correcteur pour un mot : ses propositions et ce qu'il pense du mot. */
data class SpellResult(
    val word: String,
    val suggestions: List<String>,
    val inDictionary: Boolean = false,
    val typo: Boolean = false,
    val recommended: Boolean = false,
)

/** Correcteur orthographique du téléphone (celui choisi dans Paramètres > Langues > Correcteur). */
class SpellSuggester(
    context: Context,
    locale: java.util.Locale?,
    private val onResult: (SpellResult) -> Unit,
) : SpellCheckerSession.SpellCheckerSessionListener {

    // Sans langue choisie : celle des réglages du correcteur du téléphone
    private val session: SpellCheckerSession? = context.getSystemService(TextServicesManager::class.java)
        ?.let { it.newSpellCheckerSession(null, locale?.let { l -> supported(it, l) }, this, locale == null) }

    /**
     * Sans sa variante exacte, Android prend la dernière variante de la même langue : « fr-FR » donnait le fr_CA du
     * correcteur de Gboard (fr, fr_CA), qui proposait des mots anglais. On demande alors la langue seule.
     */
    private fun supported(tsm: TextServicesManager, locale: java.util.Locale): java.util.Locale {
        val info = tsm.currentSpellCheckerInfo ?: return locale
        val subtypes = (0 until info.subtypeCount).map { info.getSubtypeAt(it).locale }
        return if (locale.toString() in subtypes) locale else java.util.Locale.forLanguageTag(locale.language)
    }

    private class Request(val word: String, val text: String, var sentAt: Long = 0)

    // Une seule demande en cours ; pendant ce temps, seule la plus récente attend (les lettres tapées entre-temps sont
    // déjà dépassées). Le correcteur ne prend donc jamais de retard sur la frappe.
    private var inFlight: Request? = null
    private var waiting: Request? = null

    /**
     * `before` : le texte avant le curseur, qui finit par `word`. Seul, un mot passe pour un début de phrase et le
     * correcteur lui met une majuscule (« bonjuor » → « Bonjour ») ; avec la phrase, il ne la met qu'à bon escient.
     */
    fun request(word: String, before: CharSequence) {
        if (session == null) return
        val request = Request(word, before.takeLast(48).toString())
        val stuck = inFlight?.let { android.os.SystemClock.uptimeMillis() - it.sentAt > 1500 } == true
        if (inFlight == null || stuck) send(request) else waiting = request
    }

    private fun send(request: Request) {
        request.sentAt = android.os.SystemClock.uptimeMillis()
        inFlight = request
        session?.getSentenceSuggestions(arrayOf(TextInfo(request.text)), 5)
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val request = inFlight ?: return
        inFlight = null
        waiting?.let { waiting = null; send(it) }
        val sentence = results?.firstOrNull()
        // Seulement les corrections du dernier mot, celui en cours de frappe
        val last = sentence?.let { s -> (0 until s.suggestionsCount).firstOrNull { s.getOffsetAt(it) + s.getLengthAt(it) == request.text.length } }
        val info = last?.let { sentence.getSuggestionsInfoAt(it) }
        val attrs = info?.suggestionsAttributes ?: SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY
        onResult(
            SpellResult(
                request.word, suggestions(info),
                inDictionary = attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0,
                typo = attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0,
                recommended = attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0,
            ),
        )
    }

    private fun suggestions(info: SuggestionsInfo?): List<String> =
        if (info == null) emptyList() else (0 until info.suggestionsCount.coerceAtLeast(0)).map { info.getSuggestionAt(it) }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit

    val available get() = session != null

    fun close() = session?.close()
}
