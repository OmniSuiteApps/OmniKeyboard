package app.clavier

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
    /** Tracé de saisie gestuelle et centres des lettres, en pixels du clavier. */
    fun glide(path: List<Pt>, keys: Map<Char, Pt>, keyWidth: Float)
    fun feedback()
}

/** État affiché par le clavier, tenu par le service. */
class KeyboardState {
    var page by mutableStateOf(Page.LETTERS)
    var shift by mutableStateOf(Shift.OFF)
    var enter by mutableStateOf(EnterAction.NEWLINE)
    var suggestions by mutableStateOf(emptyList<String>())
    /** Textes copiés pendant que le clavier tourne, le plus récent d'abord (en mémoire seulement). */
    var clips by mutableStateOf(emptyList<String>())
    /** Dernier texte copié, proposé dans la barre tant qu'il n'est pas collé. */
    var recentClip by mutableStateOf<String?>(null)
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

    // Bulle de la touche appuyée
    var preview by mutableStateOf<String?>(null)
    var previewBounds by mutableStateOf(Rect.Zero)

    // Saisie gestuelle : centre de chaque lettre et tracé en cours
    val centers = HashMap<Char, Pt>()
    var keyWidth = 0f
    val trail = mutableStateListOf<Offset>()
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
            SuggestionBar(state, state.suggestions.takeIf { prefs.suggestions }.orEmpty(), colors, font, actions)
            // Les panneaux prennent la hauteur des touches de lettres, pour que le clavier ne saute pas
            val panelHeight = (Layouts.rows(prefs.layout, Page.LETTERS, prefs.numberRow).size * prefs.keyHeight).dp
            when (state.page) {
                Page.EMOJI -> EmojiPanel(state, prefs, colors, font, actions, popup, Modifier.height(panelHeight))
                Page.CLIPBOARD -> ClipboardPanel(state, prefs, colors, font, actions, popup, Modifier.height(panelHeight))
                else -> Unit
            }
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
        if (popup.trail.size > 1) Canvas(Modifier.matchParentSize()) {
            val path = Path().apply { moveTo(popup.trail[0].x, popup.trail[0].y); popup.trail.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, colors.accent.copy(alpha = 0.7f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        popup.preview?.let { label ->
            val b = popup.previewBounds
            Box(
                Modifier.offset { IntOffset(b.left.roundToInt(), (b.top - b.height * 1.15f).roundToInt().coerceAtLeast(0)) }
                    .size(with(LocalDensity.current) { b.width.toDp() }, with(LocalDensity.current) { b.height.toDp() })
                    .clip(RoundedCornerShape(prefs.keyRadius.dp)).background(colors.special),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = colors.text, fontSize = (prefs.fontSize * 1.3f).sp, fontFamily = font) }
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

@Composable
private fun ToolButton(icon: ImageVector, colors: KeyColors, onClick: () -> Unit) {
    Box(Modifier.size(48.dp, 42.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = colors.text.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PanelBottom(state: KeyboardState, prefs: KeyboardPrefs, colors: KeyColors, font: FontFamily, actions: KeyActions, popup: PopupState) {
    Row(Modifier.fillMaxWidth().height(prefs.keyHeight.dp)) {
        for (key in Layouts.panelBottom) KeyCell(key, state, prefs, colors, font, actions, Modifier.weight(key.weight), popup)
    }
}

@Composable
private fun EmojiPanel(state: KeyboardState, prefs: KeyboardPrefs, colors: KeyColors, font: FontFamily, actions: KeyActions, popup: PopupState, modifier: Modifier) {
    val context = LocalContext.current
    var recents by remember { mutableStateOf(Emojis.recent(context)) }
    // -1 : récents ; sans récents, on ouvre sur la première catégorie
    var tab by remember { mutableStateOf(if (recents.isEmpty()) 0 else -1) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(36.dp)) {
            val tabs = listOf(-1) + Emojis.categories.indices
            for (t in tabs) Box(
                Modifier.weight(1f).fillMaxHeight().clickable { tab = t }
                    .background(if (t == tab) colors.special else Color.Transparent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (t == -1) Icon(Icons.Outlined.History, null, tint = colors.text, modifier = Modifier.size(18.dp))
                else Text(Emojis.categories[t][0], fontSize = 16.sp)
            }
        }
        val list = if (tab == -1) recents else Emojis.categories[tab]
        LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.weight(1f).fillMaxWidth()) {
            items(list) { e ->
                Box(Modifier.height(44.dp).clickable {
                    actions.text(e)
                    Emojis.remember(context, e)
                    recents = Emojis.recent(context)
                }, contentAlignment = Alignment.Center) { Text(e, fontSize = 26.sp) }
            }
        }
        PanelBottom(state, prefs, colors, font, actions, popup)
    }
}

@Composable
private fun ClipboardPanel(state: KeyboardState, prefs: KeyboardPrefs, colors: KeyColors, font: FontFamily, actions: KeyActions, popup: PopupState, modifier: Modifier) {
    Column(modifier) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp)) {
            if (state.clips.isEmpty()) Text(
                "Les textes que vous copiez apparaîtront ici (pas les mots de passe). Rien n'est gardé après l'arrêt du clavier.",
                color = colors.text.copy(alpha = 0.7f), fontFamily = font, fontSize = 14.sp, modifier = Modifier.padding(12.dp),
            )
            for (clip in state.clips) Box(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(prefs.keyRadius.dp)).background(colors.key)
                    .clickable { actions.text(clip) }.padding(12.dp),
            ) { Text(clip, color = colors.text, fontFamily = font, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        PanelBottom(state, prefs, colors, font, actions, popup)
    }
}

private fun shifted(s: String, shift: Shift) = if (shift == Shift.OFF) s else s.uppercase()

@Composable
private fun SuggestionBar(state: KeyboardState, words: List<String>, colors: KeyColors, font: FontFamily, actions: KeyActions) {
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        // Sans mot en cours : outils (presse-papiers, dernier texte copié, réglages)
        if (words.isEmpty()) {
            ToolButton(Icons.Outlined.ContentPaste, colors) { actions.key(Code.CLIPBOARD) }
            state.recentClip?.let { clip ->
                Box(
                    Modifier.weight(1f).padding(horizontal = 6.dp).clip(RoundedCornerShape(16.dp)).background(colors.special)
                        .clickable { actions.text(clip); state.recentClip = null }.padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(clip, color = colors.text, fontFamily = font, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } ?: Box(Modifier.weight(1f))
            ToolButton(Icons.Outlined.Settings, colors) { actions.key(Code.SETTINGS) }
        }
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
            .onGloballyPositioned { c ->
                bounds = c.boundsInRoot()
                if (key.code == Code.TEXT && key.label.length == 1 && key.label[0].isLetter()) {
                    popup.centers[key.label[0]] = Pt(bounds.center.x, bounds.center.y)
                    popup.keyWidth = bounds.width
                }
            }
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
                    var mode = 0 // 0 appui, 1 bulle d'accents, 2 glissé, 3 répétition, 4 appui long traité, 5 saisie gestuelle
                    // Comme Gboard : ⌫ efface dès l'appui, la bulle montre la lettre sous le doigt
                    if (key.code == Code.DELETE) actions.key(Code.DELETE)
                    val letter = key.code == Code.TEXT && key.label.length == 1
                    if (letter && current.keyPreview) { popup.previewBounds = bounds; popup.preview = shifted(key.label, shift) }
                    val glides = letter && key.label[0].isLetter() && current.glide && state.page == Page.LETTERS
                    val path = ArrayList<Pt>()
                    var anchor = down.position.x
                    var nextRepeat = downAt + viewConfig.longPressTimeoutMillis
                    while (true) {
                        val event = withTimeoutOrNull(40) { awaitPointerEvent() }
                        val change = event?.changes?.firstOrNull { it.id == down.id }
                        val now = SystemClock.uptimeMillis()
                        if (change != null && !change.pressed) {
                            change.consume()
                            popup.preview = null
                            when (mode) {
                                0 -> when (key.code) {
                                    Code.TEXT -> actions.text(shifted(key.label, shift))
                                    Code.DELETE -> Unit
                                    else -> actions.key(key.code)
                                }
                                1 -> popup.end()?.let(actions::text)
                                5 -> { popup.trail.clear(); actions.glide(path, HashMap(popup.centers), popup.keyWidth) }
                            }
                            break
                        }
                        if (change != null) {
                            change.consume()
                            val x = change.position.x
                            val dx = x - down.position.x
                            val root = Offset(bounds.left + x, bounds.top + change.position.y)
                            when {
                                mode == 5 -> { path += Pt(root.x, root.y); popup.trail += root }
                                glides && mode == 0 && (change.position - down.position).getDistance() > bounds.width * 0.7f -> {
                                    mode = 5
                                    popup.preview = null
                                    val start = Offset(bounds.left + down.position.x, bounds.top + down.position.y)
                                    path += Pt(start.x, start.y); path += Pt(root.x, root.y)
                                    popup.trail.clear(); popup.trail += start; popup.trail += root
                                }
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
                                key.code == Code.TEXT && key.alts.isNotEmpty() -> { popup.preview = null; popup.open(bounds, key.alts.map { shifted(it, shift) }); actions.feedback(); 1 }
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
            key.code == Code.SPACE -> Text(listOf(prefs.language.short, prefs.layout.label).filter { it.isNotEmpty() }.joinToString(" · "), color = fg.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = font)
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
    Code.EMOJI -> Icons.Outlined.EmojiEmotions
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
