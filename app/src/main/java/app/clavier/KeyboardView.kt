package app.clavier

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Shift { OFF, ONCE, LOCK }

enum class EnterAction { NEWLINE, GO, SEARCH, SEND, NEXT, DONE }

/** Ce que l'interface demande au service ; l'aperçu des réglages en passe une version muette. */
interface KeyActions {
    fun text(s: String)
    fun key(code: Code)
    fun longPress(code: Code)
    fun moveCursor(steps: Int)
    fun deleteWord()
    fun pick(suggestion: String)
    fun feedback()
}

/** État affiché par le clavier, tenu par le service. */
class KeyboardState {
    var page by mutableStateOf(Page.LETTERS)
    var shift by mutableStateOf(Shift.OFF)
    var enter by mutableStateOf(EnterAction.NEWLINE)
    var suggestions by mutableStateOf(emptyList<String>())
}

data class KeyColors(val background: Color, val key: Color, val special: Color, val text: Color, val accent: Color, val onAccent: Color)

@Composable
fun keyColors(theme: KeyTheme): KeyColors {
    if (theme == KeyTheme.BLACK) return KeyColors(Color.Black, Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color.White, Color(0xFF8AB4F8), Color.Black)
    val dark = when (theme) { KeyTheme.LIGHT -> false; KeyTheme.DARK -> true; else -> isSystemInDarkTheme() }
    val s = if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    return KeyColors(s.surfaceContainer, s.surfaceBright, s.secondaryContainer, s.onSurface, s.primary, s.onPrimary)
}

/** Bulle de l'appui long : alignée sur la touche, repoussée pour ne pas sortir du clavier ; le doigt choisit en glissant. */
class PopupState(private val altWidth: Float) {
    var keyBounds by mutableStateOf(Rect.Zero); private set
    var alts by mutableStateOf(emptyList<String>()); private set
    var index by mutableStateOf(0); private set
    var rootWidth = 0f
    val left get() = keyBounds.left.coerceAtMost(rootWidth - altWidth * alts.size).coerceAtLeast(0f)

    fun open(bounds: Rect, alts: List<String>) { keyBounds = bounds; this.alts = alts; index = 0 }
    fun move(bounds: Rect, x: Float) { keyBounds = bounds; index = ((x - left) / altWidth).toInt().coerceIn(0, alts.lastIndex) }
    fun end(): String? = alts.getOrNull(index).also { alts = emptyList() }
}

@Composable
fun Keyboard(state: KeyboardState, prefs: KeyboardPrefs, actions: KeyActions, modifier: Modifier = Modifier) {
    val colors = keyColors(prefs.theme)
    val font = when (prefs.font) { KeyFont.SANS -> FontFamily.SansSerif; KeyFont.SERIF -> FontFamily.Serif; KeyFont.MONO -> FontFamily.Monospace }
    val rows = remember(prefs.layout, prefs.numberRow, state.page) { Layouts.rows(prefs.layout, state.page, prefs.numberRow) }
    val altWidth = with(LocalDensity.current) { 40.dp.toPx() }
    val popup = remember(altWidth) { PopupState(altWidth) }

    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(null, prefs.background) {
        value = if (prefs.background == 0L) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(backgroundFile(context).path)?.asImageBitmap()
        }
    }

    Box(modifier.background(colors.background).onGloballyPositioned { popup.rootWidth = it.size.width.toFloat() }) {
        photo?.let {
            Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            if (prefs.photoDim > 0) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = prefs.photoDim / 100f)))
        }
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 3.dp, vertical = 2.dp)) {
            SuggestionBar(state.suggestions.takeIf { prefs.suggestions }.orEmpty(), colors, font, actions)
            for (row in rows) {
                Row(Modifier.fillMaxWidth().height(prefs.keyHeight.dp), horizontalArrangement = Arrangement.Center) {
                    // Rangée plus courte (QWERTY milieu) : demi-touches vides de chaque côté, comme sur un vrai clavier
                    val missing = (rows.maxOf { r -> r.sumOf { it.weight.toDouble() } } - row.sumOf { it.weight.toDouble() }).toFloat()
                    if (missing > 0) Box(Modifier.weight(missing / 2))
                    for (key in row) {
                        KeyCell(key, state, prefs, colors, font, actions, Modifier.weight(key.weight), popup)
                    }
                    if (missing > 0) Box(Modifier.weight(missing / 2))
                }
            }
        }
        popup.takeIf { it.alts.isNotEmpty() }?.let { p ->
            Row(
                Modifier.offset { IntOffset(p.left.roundToInt(), (p.keyBounds.top - p.keyBounds.height * 1.1f).roundToInt().coerceAtLeast(0)) }
                    .height(with(LocalDensity.current) { p.keyBounds.height.toDp() })
                    .clip(RoundedCornerShape(prefs.keyRadius.dp)).background(colors.special),
            ) {
                p.alts.forEachIndexed { i, alt ->
                    Box(
                        Modifier.width(40.dp).fillMaxHeight().background(if (i == p.index) colors.accent else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Text(alt, color = if (i == p.index) colors.onAccent else colors.text, fontSize = prefs.fontSize.sp, fontFamily = font) }
                }
            }
        }
    }
}

private fun shifted(s: String, shift: Shift) = if (shift == Shift.OFF) s else s.uppercase()

@Composable
private fun SuggestionBar(words: List<String>, colors: KeyColors, font: FontFamily, actions: KeyActions) {
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        // Mot tapé à gauche, suggestion la plus probable au centre
        words.forEachIndexed { i, w ->
            Box(Modifier.weight(1f).fillMaxHeight().clickable { actions.pick(w) }, contentAlignment = Alignment.Center) {
                Text(
                    w, color = colors.text, fontFamily = font, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (i == 1) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                )
            }
        }
    }
}

@Composable
private fun KeyCell(
    key: Key, state: KeyboardState, prefs: KeyboardPrefs, colors: KeyColors, font: FontFamily, actions: KeyActions,
    modifier: Modifier, popup: PopupState,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val current by rememberUpdatedState(prefs)
    val shift by rememberUpdatedState(state.shift)
    val viewConfig = LocalViewConfiguration.current
    val density = LocalDensity.current
    val accent = key.code == Code.ENTER || key.code == Code.SHIFT && state.shift != Shift.OFF
    val bgBase = when { accent -> colors.accent; key.code == Code.TEXT || key.code == Code.SPACE -> colors.key; else -> colors.special }
    val fg = if (accent) colors.onAccent else colors.text
    val bg = if (prefs.background == 0L) bgBase else bgBase.copy(alpha = bgBase.alpha * prefs.keyAlpha / 100f)
    val shape = RoundedCornerShape(prefs.keyRadius.dp)

    Box(
        modifier.fillMaxHeight().padding(horizontal = (prefs.keyGap / 2f).dp, vertical = prefs.keyGap.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(shape).background(bg)
            .then(if (prefs.borders) Modifier.border(1.dp, colors.text.copy(alpha = 0.25f), shape) else Modifier)
            .pointerInput(key) {
                val cursorStep = with(density) { 12.dp.toPx() }
                val wordStep = with(density) { 36.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    actions.feedback()
                    val downAt = SystemClock.uptimeMillis()
                    var mode = 0 // 0 appui, 1 bulle d'accents, 2 glissé, 3 répétition, 4 appui long traité
                    var anchor = down.position.x
                    var nextRepeat = downAt + viewConfig.longPressTimeoutMillis
                    while (true) {
                        val event = withTimeoutOrNull(40) { awaitPointerEvent() }
                        val change = event?.changes?.firstOrNull { it.id == down.id }
                        val now = SystemClock.uptimeMillis()
                        if (change != null && !change.pressed) {
                            change.consume()
                            when (mode) {
                                0 -> if (key.code == Code.TEXT) actions.text(shifted(key.label, shift)) else actions.key(key.code)
                                1 -> popup.end()?.let(actions::text)
                            }
                            break
                        }
                        if (change != null) {
                            change.consume()
                            val x = change.position.x
                            val dx = x - down.position.x
                            when {
                                mode == 1 -> popup.move(bounds, bounds.left + x)
                                key.code == Code.SPACE && current.spaceCursor && (mode == 2 || mode == 0 && abs(dx) > viewConfig.touchSlop) -> {
                                    mode = 2
                                    val steps = ((x - anchor) / cursorStep).toInt()
                                    if (steps != 0) { actions.moveCursor(steps); anchor += steps * cursorStep }
                                }
                                key.code == Code.DELETE && current.deleteSwipe && (mode == 2 || mode == 0 && dx < -viewConfig.touchSlop) -> {
                                    mode = 2
                                    while (x < anchor - wordStep) { actions.deleteWord(); anchor -= wordStep }
                                }
                            }
                        }
                        if (mode == 0 && now - downAt >= viewConfig.longPressTimeoutMillis) {
                            mode = when {
                                key.code == Code.TEXT && key.alts.isNotEmpty() -> { popup.open(bounds, key.alts.map { shifted(it, shift) }); actions.feedback(); 1 }
                                key.code == Code.DELETE -> 3
                                else -> { actions.longPress(key.code); 4 }
                            }
                        }
                        if (mode == 3 && now >= nextRepeat) { actions.key(Code.DELETE); nextRepeat = now + 50 }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val icon = keyIcon(key.code, state.enter, state.shift)
        when {
            icon != null -> Icon(icon, null, tint = fg, modifier = Modifier.size((prefs.fontSize + 2).dp))
            key.code == Code.SPACE -> Text(prefs.layout.label, color = fg.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = font)
            else -> Box(Modifier.fillMaxSize()) {
                Text(shifted(key.label, state.shift), color = fg, fontSize = (if (key.label.length > 1) prefs.fontSize * 0.7f else prefs.fontSize.toFloat()).sp, fontFamily = font, modifier = Modifier.align(Alignment.Center))
                // Indice discret de l'appui long (chiffre ou premier accent)
                key.alts.firstOrNull()?.let {
                    Text(it, color = fg.copy(alpha = 0.45f), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 1.dp))
                }
            }
        }
    }
}

private fun keyIcon(code: Code, enter: EnterAction, shift: Shift): ImageVector? = when (code) {
    Code.SHIFT -> ShiftIcons.of(shift)
    Code.DELETE -> Icons.AutoMirrored.Outlined.Backspace
    Code.ENTER -> when (enter) {
        EnterAction.NEWLINE -> Icons.AutoMirrored.Filled.KeyboardReturn
        EnterAction.SEARCH -> Icons.Filled.Search
        EnterAction.SEND -> Icons.AutoMirrored.Filled.Send
        EnterAction.GO, EnterAction.NEXT -> Icons.AutoMirrored.Filled.ArrowForward
        EnterAction.DONE -> Icons.Filled.Check
    }
    else -> null
}
