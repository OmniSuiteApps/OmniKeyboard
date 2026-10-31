package app.clavier

enum class Code { TEXT, SHIFT, DELETE, ENTER, SPACE, SYMBOLS, LETTERS }

/** Une touche : `alts` est la liste de l'appui long (accents, chiffres, symboles). */
data class Key(val label: String, val code: Code = Code.TEXT, val alts: List<String> = emptyList(), val weight: Float = 1f)

enum class Page { LETTERS, SYMBOLS }

enum class LayoutKind(val label: String, val letterRows: List<String>) {
    AZERTY("AZERTY", listOf("azertyuiop", "qsdfghjklm", "wxcvbn'")),
    QWERTY("QWERTY", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
    BEPO("BÉPO", listOf("bépoèvdljz", "auiectsrnm", "àyxkqghfw")),
}

object Layouts {
    private val accents = mapOf(
        "a" to "àâæäáã", "e" to "éèêë€", "c" to "ç©", "u" to "ùûüú", "i" to "îïí", "o" to "ôœöóõ",
        "y" to "ÿ", "n" to "ñ", "s" to "ß§", "'" to "\"«»’",
        "," to ";:!?", "." to "…!?-:;",
        "-" to "–—_", "€" to "$£¥¢", "(" to "[{<", ")" to "]}>", "%" to "‰", "/" to "\\|",
        "*" to "★†•", "!" to "¡", "?" to "¿", "=" to "≠≈±", "+" to "±×÷", "\"" to "«»“”",
        "&" to "§", "#" to "№", "@" to "~^`", "_" to "°",
    )
    private const val DIGITS = "1234567890"

    fun alts(label: String, digit: Char? = null): List<String> =
        listOfNotNull(digit?.toString()) + (accents[label] ?: "").map { it.toString() }

    fun rows(kind: LayoutKind, page: Page, numberRow: Boolean): List<List<Key>> {
        val bottom = listOf(
            Key(if (page == Page.LETTERS) "?123" else "ABC", if (page == Page.LETTERS) Code.SYMBOLS else Code.LETTERS, weight = 1.5f),
            Key(",", alts = alts(",")),
            Key(" ", Code.SPACE, weight = 5f),
            Key(".", alts = alts(".")),
            Key("", Code.ENTER, weight = 1.5f),
        )
        if (page == Page.SYMBOLS) return listOf(
            textRow(DIGITS),
            textRow("@#€&_-()=%"),
            textRow("'\"*:/!?+") + Key("", Code.DELETE, weight = 1.5f),
            bottom,
        )
        val (top, middle, last) = kind.letterRows
        // Sans rangée de chiffres, l'appui long sur la première rangée les donne
        val topRow = top.mapIndexed { i, c -> Key(c.toString(), alts = alts(c.toString(), DIGITS.getOrNull(i).takeUnless { numberRow })) }
        // BÉPO a 9 lettres sur la dernière rangée : Maj et effacer y rétrécissent
        val side = if (last.length >= 9) 1f else 1.5f
        val lastRow = listOf(Key("", Code.SHIFT, weight = side)) + textRow(last) + Key("", Code.DELETE, weight = side)
        return listOfNotNull(textRow(DIGITS).takeIf { numberRow }, topRow, textRow(middle), lastRow, bottom)
    }

    private fun textRow(chars: String) = chars.map { Key(it.toString(), alts = alts(it.toString())) }
}
