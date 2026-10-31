package app.clavier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Calculs du cadrage (testés sans téléphone). L'image remplit toujours tout le cadre. */
object CropMath {
    /** Échelle à laquelle l'image couvre juste le cadre (zoom 1). */
    fun coverScale(image: Size, frame: Size) = max(frame.width / image.width, frame.height / image.height)

    /** Décalage permis : l'image ne laisse jamais de bord vide dans le cadre. */
    fun clampPan(pan: Offset, image: Size, frame: Size, scale: Float): Offset {
        val maxX = max(0f, (image.width * scale - frame.width) / 2)
        val maxY = max(0f, (image.height * scale - frame.height) / 2)
        return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
    }

    /** Partie de l'image (en pixels de l'image) visible dans le cadre. */
    fun sourceRect(image: Size, frame: Size, scale: Float, pan: Offset): Rect {
        val left = ((image.width * scale - frame.width) / 2 - pan.x) / scale
        val top = ((image.height * scale - frame.height) / 2 - pan.y) / scale
        return Rect(left, top, left + frame.width / scale, top + frame.height / scale)
    }
}

/** Photo choisie, réduite pour tenir en mémoire ; l'orientation de l'appareil photo est appliquée. */
fun decodePhoto(context: Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val side = max(info.size.width, info.size.height)
        if (side > 3000) decoder.setTargetSampleSize(ceil(side / 3000.0).toInt())
    }
}.getOrNull()

/** Enregistre la partie cadrée (au plus 1440 px de large), en remplaçant l'ancienne photo d'un coup. */
fun saveCrop(context: Context, photo: Bitmap, rect: Rect) {
    val (at, size) = pixels(rect, photo)
    val (w, h) = size
    var crop = Bitmap.createBitmap(photo, at.x, at.y, w, h)
    if (w > 1440) crop = Bitmap.createScaledBitmap(crop, 1440, (h * 1440f / w).roundToInt().coerceAtLeast(1), true)
    val target = backgroundFile(context)
    val tmp = File(target.path + ".tmp")
    tmp.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    check(tmp.renameTo(target)) { "Photo non enregistrée" }
}

/** Écran de cadrage : glisser pour déplacer, pincer pour zoomer ; le cadre a la forme du clavier. */
@Composable
fun CropScreen(photo: Bitmap, prefs: KeyboardPrefs, onCancel: () -> Unit, onConfirm: (Rect) -> Unit) {
    BackHandler(onBack = onCancel)
    val image = remember(photo) { photo.asImageBitmap() }
    val imageSize = Size(photo.width.toFloat(), photo.height.toFloat())
    var frame by remember { mutableStateOf(Size.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val scale = if (frame == Size.Zero) 1f else CropMath.coverScale(imageSize, frame) * zoom

    // Même hauteur que le vrai clavier : barre de suggestions, rangées, marges et barre de navigation
    val density = LocalDensity.current
    val rows = Layouts.rows(prefs.layout, Page.LETTERS, prefs.numberRow).size
    val navBar = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val height = 42.dp + 4.dp + prefs.keyHeight.dp * rows + navBar
    val aspect = LocalConfiguration.current.screenWidthDp.dp / height

    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Cadrer la photo", color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))
        Text("Glisser pour déplacer, pincer pour zoomer", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 16.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(aspect).clipToBounds().border(2.dp, Color.White)
                    .onSizeChanged { frame = Size(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(photo) {
                        detectTransformGestures { _, panBy, zoomBy, _ ->
                            zoom = (zoom * zoomBy).coerceIn(1f, 6f)
                            pan = CropMath.clampPan(pan + panBy, imageSize, frame, CropMath.coverScale(imageSize, frame) * zoom)
                        }
                    },
            ) {
                val (offset, srcSize) = pixels(CropMath.sourceRect(imageSize, frame, scale, CropMath.clampPan(pan, imageSize, frame, scale)), photo)
                drawImage(image, srcOffset = offset, srcSize = srcSize, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            OutlinedButton(onCancel) { Text("Annuler", color = Color.White) }
            Button({ onConfirm(CropMath.sourceRect(imageSize, frame, scale, CropMath.clampPan(pan, imageSize, frame, scale))) }, enabled = frame != Size.Zero) {
                Text("Confirmer")
            }
        }
    }
}

/** Rectangle arrondi au pixel, sans jamais dépasser de la photo (sinon le dessin ou la découpe plante). */
private fun pixels(rect: Rect, photo: Bitmap): Pair<IntOffset, IntSize> {
    val w = rect.width.roundToInt().coerceIn(1, photo.width)
    val h = rect.height.roundToInt().coerceIn(1, photo.height)
    return IntOffset(rect.left.roundToInt().coerceIn(0, photo.width - w), rect.top.roundToInt().coerceIn(0, photo.height - h)) to IntSize(w, h)
}
