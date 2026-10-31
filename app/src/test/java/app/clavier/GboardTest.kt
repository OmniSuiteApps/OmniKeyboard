package app.clavier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardTest {
    @Test fun previousWord() {
        assertEquals("bonjour", TextRules.previousWord("bonjour "))
        assertEquals("bonjour", TextRules.previousWord("bonjour to"))
        assertNull(TextRules.previousWord("fin. "))
        assertNull(TextRules.previousWord("bonjour"))
    }

    @Test fun indexFindsPrefixAndKnownWords() {
        val index = WordIndex(mapOf("école" to 5, "écoute" to 9, "maison" to 3))
        assertEquals(listOf("écoute", "école"), index.completions("eco"))
        assertTrue("Maison" in index)
        assertEquals(setOf("école", "écoute"), index.startingWith('e').map { it.first }.toSet())
    }

    @Test fun autoCorrectionOnlyWhenSureAndClose() {
        val typo = SpellResult("bonjuor", listOf("bonjour"), typo = true)
        assertEquals("bonjour", Suggestions.autoCorrection("bonjuor", typo) { false })
        assertEquals("Bonjour", Suggestions.autoCorrection("Bonjuor", typo.copy(word = "Bonjuor")) { false })
        assertNull(Suggestions.autoCorrection("bonjuor", typo) { true }) // mot à moi : jamais corrigé
        assertNull(Suggestions.autoCorrection("bonjuor", typo.copy(word = "bonju")) { false }) // réponse d'un autre mot
        assertNull(Suggestions.autoCorrection("xq", SpellResult("xq", listOf("maison"), typo = true)) { false }) // trop loin
        assertEquals(3, Suggestions.editDistance("chat", "chien"))
        assertEquals(1, Suggestions.editDistance("bonjuor", "bonjour"))
    }

    // Centres d'une rangée AZERTY, touches de 100 px
    private val keys = "azertyuiop".mapIndexed { i, c -> c to Pt(50f + 100 * i, 50f) }.toMap() +
        "qsdfghjklm".mapIndexed { i, c -> c to Pt(50f + 100 * i, 150f) }.toMap() +
        "wxcvbn".mapIndexed { i, c -> c to Pt(150f + 100 * i, 250f) }.toMap()

    @Test fun glidePicksWordMatchingTheShape() {
        val path = Glide.resample(Glide.idealPath("rue", keys)!!, 40)
        assertEquals(listOf("rue"), Glide.rank(path, keys, 100f, mapOf("rue" to 1, "rte" to 50, "zut" to 50), max = 1))
        assertEquals(Glide.idealPath("elle", keys), Glide.idealPath("éle", keys)) // accents et lettres doublées
        assertTrue(Glide.sketch(path, keys).startsWith("r") && Glide.sketch(path, keys).endsWith("e"))
    }
}

class LearnedWordsTest {
    @Test fun learnsSavesAndPredicts() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val a = java.io.File(dir, "mots.tsv"); val b = java.io.File(dir, "suivants.tsv")
        val words = LearnedWords(a, b)
        words.learn("Merci") // début de phrase : appris en minuscules
        words.learn("Paris", "à") // au milieu d'une phrase : garde sa majuscule
        words.learn("beaucoup", "merci")
        words.learn("beaucoup", "merci")
        words.save()
        val reread = LearnedWords(a, b)
        assertEquals(1, reread.count("merci"))
        assertEquals(1, reread.count("Paris"))
        assertEquals(listOf("beaucoup"), reread.next("Merci"))
        dir.deleteRecursively()
    }
}
