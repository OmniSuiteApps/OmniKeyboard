package app.clavier

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class KeyTheme(val label: String) { SYSTEM("Material You"), LIGHT("Clair"), DARK("Sombre"), BLACK("Noir") }

/** Langue d'écriture : celle du correcteur et du dictionnaire perso ; `tag` nul suit les réglages du téléphone. */
enum class WritingLanguage(val label: String, val short: String, val tag: String?) {
    SYSTEM("Selon le téléphone", "", null),
    FR("Français", "FR", "fr-FR"),
    EN("English", "EN", "en-US"),
    ES("Español", "ES", "es-ES"),
    DE("Deutsch", "DE", "de-DE"),
    IT("Italiano", "IT", "it-IT"),
    PT("Português", "PT", "pt-PT"),
    ;

    val locale: java.util.Locale? get() = tag?.let(java.util.Locale::forLanguageTag)
}

enum class KeyFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }

data class KeyboardPrefs(
    val theme: KeyTheme = KeyTheme.SYSTEM,
    val font: KeyFont = KeyFont.SANS,
    val keyHeight: Int = 52,
    val keyRadius: Int = 8,
    val keyGap: Int = 3,
    val fontSize: Int = 22,
    val borders: Boolean = false,
    /** Date de la photo de fond (`backgroundFile`), 0 sans photo ; change à chaque nouvelle photo pour la recharger. */
    val background: Long = 0,
    /** Opacité des touches sur la photo, en %. */
    val keyAlpha: Int = 70,
    /** Assombrissement de la photo, en %. */
    val photoDim: Int = 0,
    val layout: LayoutKind = LayoutKind.AZERTY,
    val language: WritingLanguage = WritingLanguage.SYSTEM,
    val numberRow: Boolean = false,
    val vibrate: Boolean = true,
    val sound: Boolean = false,
    val autoCap: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val spaceCursor: Boolean = true,
    val deleteSwipe: Boolean = true,
    val suggestions: Boolean = true,
    val learn: Boolean = true,
    val autoCorrect: Boolean = true,
    val keyPreview: Boolean = true,
    val glide: Boolean = true,
    val predict: Boolean = true,
)

/** Photo de fond déjà cadrée, copiée dans l'appli (l'accès à la photo choisie ne dure pas). */
fun backgroundFile(context: Context) = java.io.File(context.filesDir, "fond.jpg")

private val Context.store by preferencesDataStore("reglages")

private object K {
    val theme = stringPreferencesKey("theme")
    val font = stringPreferencesKey("font")
    val keyHeight = intPreferencesKey("keyHeight")
    val keyRadius = intPreferencesKey("keyRadius")
    val keyGap = intPreferencesKey("keyGap")
    val fontSize = intPreferencesKey("fontSize")
    val borders = booleanPreferencesKey("borders")
    val background = longPreferencesKey("background")
    val keyAlpha = intPreferencesKey("keyAlpha")
    val photoDim = intPreferencesKey("photoDim")
    val layout = stringPreferencesKey("layout")
    val language = stringPreferencesKey("language")
    val numberRow = booleanPreferencesKey("numberRow")
    val vibrate = booleanPreferencesKey("vibrate")
    val sound = booleanPreferencesKey("sound")
    val autoCap = booleanPreferencesKey("autoCap")
    val doubleSpacePeriod = booleanPreferencesKey("doubleSpacePeriod")
    val spaceCursor = booleanPreferencesKey("spaceCursor")
    val deleteSwipe = booleanPreferencesKey("deleteSwipe")
    val suggestions = booleanPreferencesKey("suggestions")
    val learn = booleanPreferencesKey("learn")
    val autoCorrect = booleanPreferencesKey("autoCorrect")
    val keyPreview = booleanPreferencesKey("keyPreview")
    val glide = booleanPreferencesKey("glide")
    val predict = booleanPreferencesKey("predict")
}

private inline fun <reified T : Enum<T>> decode(name: String?, default: T): T = enumValues<T>().firstOrNull { it.name == name } ?: default

private fun decode(p: Preferences) = KeyboardPrefs().let { d ->
    KeyboardPrefs(
        theme = decode(p[K.theme], d.theme),
        font = decode(p[K.font], d.font),
        keyHeight = p[K.keyHeight] ?: d.keyHeight,
        keyRadius = p[K.keyRadius] ?: d.keyRadius,
        keyGap = p[K.keyGap] ?: d.keyGap,
        fontSize = p[K.fontSize] ?: d.fontSize,
        borders = p[K.borders] ?: d.borders,
        background = p[K.background] ?: d.background,
        keyAlpha = p[K.keyAlpha] ?: d.keyAlpha,
        photoDim = p[K.photoDim] ?: d.photoDim,
        layout = decode(p[K.layout], d.layout),
        language = decode(p[K.language], d.language),
        numberRow = p[K.numberRow] ?: d.numberRow,
        vibrate = p[K.vibrate] ?: d.vibrate,
        sound = p[K.sound] ?: d.sound,
        autoCap = p[K.autoCap] ?: d.autoCap,
        doubleSpacePeriod = p[K.doubleSpacePeriod] ?: d.doubleSpacePeriod,
        spaceCursor = p[K.spaceCursor] ?: d.spaceCursor,
        deleteSwipe = p[K.deleteSwipe] ?: d.deleteSwipe,
        suggestions = p[K.suggestions] ?: d.suggestions,
        learn = p[K.learn] ?: d.learn,
        autoCorrect = p[K.autoCorrect] ?: d.autoCorrect,
        keyPreview = p[K.keyPreview] ?: d.keyPreview,
        glide = p[K.glide] ?: d.glide,
        predict = p[K.predict] ?: d.predict,
    )
}

fun Context.keyboardPrefs(): Flow<KeyboardPrefs> = store.data.map(::decode)

/** Enregistre tous les réglages d'un coup (l'écran de réglages passe toujours la version complète). */
suspend fun Context.saveKeyboardPrefs(v: KeyboardPrefs) {
    store.edit { p ->
        p[K.theme] = v.theme.name
        p[K.font] = v.font.name
        p[K.keyHeight] = v.keyHeight
        p[K.keyRadius] = v.keyRadius
        p[K.keyGap] = v.keyGap
        p[K.fontSize] = v.fontSize
        p[K.borders] = v.borders
        p[K.background] = v.background
        p[K.keyAlpha] = v.keyAlpha
        p[K.photoDim] = v.photoDim
        p[K.layout] = v.layout.name
        p[K.language] = v.language.name
        p[K.numberRow] = v.numberRow
        p[K.vibrate] = v.vibrate
        p[K.sound] = v.sound
        p[K.autoCap] = v.autoCap
        p[K.doubleSpacePeriod] = v.doubleSpacePeriod
        p[K.spaceCursor] = v.spaceCursor
        p[K.deleteSwipe] = v.deleteSwipe
        p[K.suggestions] = v.suggestions
        p[K.learn] = v.learn
        p[K.autoCorrect] = v.autoCorrect
        p[K.keyPreview] = v.keyPreview
        p[K.glide] = v.glide
        p[K.predict] = v.predict
    }
}
