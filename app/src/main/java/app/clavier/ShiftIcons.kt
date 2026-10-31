package app.clavier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Flèche de Maj : contour (minuscules), pleine (une majuscule), pleine et soulignée (verrouillée). */
object ShiftIcons {
    private fun PathBuilder.arrow() {
        moveTo(12f, 3f); lineTo(3.5f, 12f); horizontalLineTo(8f); verticalLineTo(17f)
        horizontalLineTo(16f); verticalLineTo(12f); horizontalLineTo(20.5f); close()
    }

    private fun icon(name: String, filled: Boolean, bar: Boolean) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        // Couleur remplacée par la teinte de l'Icon ; seule la forme compte
        if (filled) path(fill = SolidColor(Color.Black)) { arrow() }
        else path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) { arrow() }
        if (bar) path(fill = SolidColor(Color.Black)) { moveTo(8f, 19f); horizontalLineTo(16f); verticalLineTo(21f); horizontalLineTo(8f); close() }
    }.build()

    val off by lazy { icon("ShiftOff", filled = false, bar = false) }
    val once by lazy { icon("ShiftOnce", filled = true, bar = false) }
    val lock by lazy { icon("ShiftLock", filled = true, bar = true) }

    fun of(shift: Shift) = when (shift) { Shift.OFF -> off; Shift.ONCE -> once; Shift.LOCK -> lock }
}
