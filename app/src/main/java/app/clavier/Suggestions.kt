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

    /** Mots connus commençant par `typed`, les plus fréquents d'abord. */
    fun completions(typed: String, words: Map<String, Int>, max: Int = 5): List<String> {
        val key = fold(typed)
        return words.entries.asSequence()
            .filter { it.key.length > typed.length && fold(it.key).startsWith(key) }
            .sortedByDescending { it.value }
            .take(max).map { it.key }.toList()
    }

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
}

/** Mots tapés souvent, gardés dans un fichier privé de l'appli (jamais envoyés). */
class LearnedWords private constructor(private val file: File) {
    private val counts = HashMap<String, Int>()
    private var dirty = false

    init {
        runCatching {
            file.forEachLine { line -> line.split('\t').takeIf { it.size == 2 }?.let { (w, n) -> n.toIntOrNull()?.let { counts[w] = it } } }
        }
    }

    val size get() = synchronized(this) { counts.size }

    fun snapshot(): Map<String, Int> = synchronized(this) { HashMap(counts) }

    fun learn(word: String) = synchronized(this) {
        if (!TextRules.learnable(word)) return
        // Un mot en début de phrase n'est pas un nom propre : on le garde en minuscules s'il existe déjà ainsi
        val key = word.lowercase().takeIf { it in counts } ?: word
        counts[key] = (counts[key] ?: 0) + 1
        dirty = true
    }

    fun save() = synchronized(this) {
        if (!dirty) return
        val tmp = File(file.path + ".tmp")
        tmp.writeText(counts.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        tmp.renameTo(file)
        dirty = false
    }

    fun clear() = synchronized(this) {
        counts.clear()
        file.delete()
        dirty = false
    }

    companion object {
        @Volatile private var instance: LearnedWords? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: LearnedWords(File(context.applicationContext.filesDir, "mots_appris.tsv")).also { instance = it }
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

/** Correcteur orthographique du téléphone (celui choisi dans Paramètres > Langues > Correcteur). */
class SpellSuggester(
    context: Context,
    locale: java.util.Locale?,
    private val onResult: (word: String, suggestions: List<String>) -> Unit,
) : SpellCheckerSession.SpellCheckerSessionListener {

    // Sans langue choisie : celle des réglages du correcteur du téléphone
    private val session: SpellCheckerSession? = context.getSystemService(TextServicesManager::class.java)
        ?.newSpellCheckerSession(null, locale, this, locale == null)

    // Une réponse par demande, dans l'ordre : la file relie chaque réponse au mot demandé
    private val pending = ArrayDeque<String>()

    fun request(word: String) {
        if (session == null || pending.size > 50) return // correcteur absent ou muet
        pending.addLast(word)
        session?.getSentenceSuggestions(arrayOf(TextInfo(word)), 5)
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val word = pending.removeFirstOrNull() ?: return
        val sentence = results?.firstOrNull() ?: return onResult(word, emptyList())
        onResult(word, (0 until sentence.suggestionsCount).flatMap { suggestions(sentence.getSuggestionsInfoAt(it)) })
    }

    private fun suggestions(info: SuggestionsInfo?): List<String> =
        if (info == null) emptyList() else (0 until info.suggestionsCount).map { info.getSuggestionAt(it) }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit

    fun close() = session?.close()
}
