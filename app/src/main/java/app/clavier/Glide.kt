package app.clavier

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.PI

/** Point du tracé, en pixels du clavier. */
data class Pt(val x: Float, val y: Float)

/**
 * Saisie gestuelle : on compare la forme du tracé du doigt au tracé idéal de chaque mot candidat (de touche en touche).
 * Pur calcul, testé sans téléphone.
 */
object Glide {
    private const val SAMPLES = 32

    /** Le tracé ramené à `n` points régulièrement espacés. */
    fun resample(path: List<Pt>, n: Int = SAMPLES): List<Pt> {
        if (path.size < 2) return List(n) { path.firstOrNull() ?: Pt(0f, 0f) }
        val lengths = path.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
        val total = lengths.sum()
        if (total == 0f) return List(n) { path[0] }
        val out = ArrayList<Pt>(n)
        var seg = 0
        var acc = 0f
        for (i in 0 until n) {
            val target = total * i / (n - 1)
            while (seg < lengths.size - 1 && acc + lengths[seg] < target) { acc += lengths[seg]; seg++ }
            val t = if (lengths[seg] == 0f) 0f else ((target - acc) / lengths[seg]).coerceIn(0f, 1f)
            val a = path[seg]; val b = path[seg + 1]
            out += Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        return out
    }

    /** Tracé idéal d'un mot : le centre de chaque lettre, sans répéter une lettre doublée. Null si une lettre manque. */
    fun idealPath(word: String, keys: Map<Char, Pt>): List<Pt>? {
        val letters = Suggestions.fold(word).filter { it.isLetter() }
        if (letters.isEmpty()) return null
        val pts = ArrayList<Pt>()
        var last: Char? = null
        for (c in letters) {
            if (c == last) continue
            pts += keys[c] ?: return null
            last = c
        }
        return pts
    }

    /** Écart moyen entre deux tracés rééchantillonnés, en pixels. */
    fun distance(a: List<Pt>, b: List<Pt>): Float = a.zip(b) { p, q -> hypot(p.x - q.x, p.y - q.y) }.average().toFloat()

    /** Touche la plus proche d'un point. */
    fun nearest(p: Pt, keys: Map<Char, Pt>): Char? = keys.minByOrNull { (_, c) -> hypot(c.x - p.x, c.y - p.y) }?.key

    /**
     * Lettres où le doigt change franchement de direction, plus la première et la dernière : une ébauche du mot
     * (« bonjour » donne souvent « bonjour » ou « bonjur »), que le correcteur du téléphone sait compléter.
     */
    fun sketch(path: List<Pt>, keys: Map<Char, Pt>): String {
        val pts = resample(path, 48)
        val out = StringBuilder()
        fun add(p: Pt) { nearest(p, keys)?.let { if (out.lastOrNull() != it) out.append(it) } }
        add(pts.first())
        for (i in 2 until pts.size - 2) {
            val a1 = atan2(pts[i].y - pts[i - 2].y, pts[i].x - pts[i - 2].x)
            val a2 = atan2(pts[i + 2].y - pts[i].y, pts[i + 2].x - pts[i].x)
            var turn = abs(a2 - a1)
            if (turn > PI) turn = (2 * PI - turn).toFloat()
            if (turn > PI / 3) add(pts[i])
        }
        add(pts.last())
        return out.toString()
    }

    /**
     * Les meilleurs mots pour ce tracé. `candidates` : mot → fréquence. Le mot doit partir près du début du tracé et
     * finir près de la fin ; ensuite la ressemblance des formes compte, et un peu la fréquence.
     */
    fun rank(path: List<Pt>, keys: Map<Char, Pt>, keyWidth: Float, candidates: Map<String, Int>, max: Int = 3): List<String> {
        if (path.size < 2 || keys.isEmpty()) return emptyList()
        val user = resample(path)
        val start = path.first(); val end = path.last()
        return candidates.mapNotNull { (word, freq) ->
            val ideal = idealPath(word, keys) ?: return@mapNotNull null
            if (hypot(ideal.first().x - start.x, ideal.first().y - start.y) > keyWidth * 1.5f) return@mapNotNull null
            if (hypot(ideal.last().x - end.x, ideal.last().y - end.y) > keyWidth * 1.5f) return@mapNotNull null
            val shape = distance(user, resample(ideal)) / keyWidth
            word to shape - 0.08f * ln(1f + freq)
        }.sortedBy { it.second }.take(max).map { it.first }
    }
}
