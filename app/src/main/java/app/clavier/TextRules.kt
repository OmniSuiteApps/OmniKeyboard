package app.clavier

/** Règles d'édition pures (testées sans téléphone). */
object TextRules {
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '\''

    /** Mot en cours de frappe juste avant le curseur ; apostrophe et trait d'union le coupent (« l'arbre » → « arbre »). */
    fun currentWord(before: CharSequence): String = before.takeLastWhile { it.isLetterOrDigit() }.toString()

    /** Mot d'avant le mot en cours, s'il n'en est séparé que par des espaces (pas de point entre les deux). */
    fun previousWord(before: CharSequence): String? {
        val rest = before.dropLast(currentWord(before).length)
        val trimmed = rest.trimEnd(' ')
        if (trimmed.length == rest.length) return null
        return currentWord(trimmed).takeIf { it.isNotEmpty() }
    }

    /** Deux espaces après un mot : on remplace le premier par un point. */
    fun doubleSpacePeriod(before: CharSequence): Boolean =
        before.length >= 2 && before[before.length - 1] == ' ' && before[before.length - 2].isLetterOrDigit()

    /** Nombre de caractères à effacer pour supprimer le mot précédent (et les espaces qui le suivent). */
    fun wordDeleteLength(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0 && !isWordChar(before[i - 1])) return before.length - i + 1
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.length - i
    }

    /** Reprend la casse du mot tapé : « Bonj » → « Bonjour », « BONJ » → « BONJOUR ». */
    fun matchCase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    /** Mot digne d'être appris : au moins deux lettres, pas un nombre ni un lien. */
    fun learnable(word: String) = word.length in 2..32 && word.any { it.isLetter() } && word.none { it == '@' || it == '/' }
}
