package app.clavier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test fun currentWord() {
        assertEquals("arbre", TextRules.currentWord("l'arbre"))
        assertEquals("", TextRules.currentWord("bonjour "))
        assertEquals("être", TextRules.currentWord("peut-être"))
    }

    @Test fun doubleSpace() {
        assertTrue(TextRules.doubleSpacePeriod("salut "))
        assertFalse(TextRules.doubleSpacePeriod("salut. "))
        assertFalse(TextRules.doubleSpacePeriod(" "))
    }

    @Test fun deleteWord() {
        assertEquals(6, TextRules.wordDeleteLength("dis bonjour ".substring(0, 10)))
        assertEquals(8, TextRules.wordDeleteLength("dis bonjour "))
        assertEquals(2, TextRules.wordDeleteLength("oui! "))
    }

    @Test fun matchCase() {
        assertEquals("Bonjour", TextRules.matchCase("Bon", "bonjour"))
        assertEquals("BONJOUR", TextRules.matchCase("BON", "bonjour"))
        assertEquals("bonjour", TextRules.matchCase("bon", "bonjour"))
    }

    @Test fun completionsIgnoreAccents() {
        val words = mapOf("école" to 5, "écoute" to 9, "eco" to 50)
        assertEquals(listOf("écoute", "école"), Suggestions.completions("eco", words))
    }

    @Test fun mergeKeepsTypedFirstWithoutDuplicates() {
        assertEquals(listOf("Bonj", "Bonjour", "Bonne"), Suggestions.merge("Bonj", listOf("bonjour", "bonne"), listOf("bonjour")))
        assertEquals(emptyList<String>(), Suggestions.merge("", listOf("a"), emptyList()))
    }

    @Test fun layoutsHaveSameWidthEverywhere() {
        for (kind in LayoutKind.entries) for (page in Page.entries) for (numbers in listOf(true, false)) {
            val rows = Layouts.rows(kind, page, numbers)
            assertTrue(rows.all { r -> r.sumOf { it.weight.toDouble() } <= 11.0 })
        }
        assertEquals("1", Layouts.rows(LayoutKind.AZERTY, Page.LETTERS, false)[0][0].alts[0])
    }
}
