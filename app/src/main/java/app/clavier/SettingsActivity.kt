package app.clavier

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.textservice.TextServicesManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { SettingsScreen() }
            }
        }
    }
}

/** Aperçu dans les réglages : les touches réagissent à l'œil mais n'écrivent rien. */
private object PreviewActions : KeyActions {
    override fun text(s: String) = Unit
    override fun key(code: Code) = Unit
    override fun longPress(code: Code) = Unit
    override fun moveCursor(steps: Int) = Unit
    override fun deleteWord() = Unit
    override fun pick(suggestion: String) = Unit
    override fun feedback() = Unit
}

/** Catégories du menu ; `preview` : l'aperçu du clavier s'affiche en bas de la page. */
private enum class Category(val icon: ImageVector, val label: String, val preview: Boolean) {
    APPEARANCE(Icons.Outlined.Palette, "Apparence", true),
    LAYOUT(Icons.Outlined.Keyboard, "Disposition", true),
    LANGUAGE(Icons.Outlined.Language, "Langue d'écriture", false),
    TYPING(Icons.Outlined.TouchApp, "Saisie", false),
    GESTURES(Icons.Outlined.Swipe, "Gestes", false),
    SUGGESTIONS(Icons.Outlined.AutoAwesome, "Suggestions", true),
}

private val CARD = RoundedCornerShape(20.dp)

/** État du téléphone vu par les réglages, relu à chaque retour dans l'appli. */
private class Status(val enabled: Boolean, val current: Boolean, val spellChecker: Boolean, val learned: Int)

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by remember { context.keyboardPrefs() }.collectAsState(KeyboardPrefs())
    val set: (KeyboardPrefs) -> Unit = { scope.launch { context.saveKeyboardPrefs(it) } }
    val imm = context.getSystemService(InputMethodManager::class.java)

    var status by remember { mutableStateOf(Status(enabled = true, current = true, spellChecker = true, learned = 0)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val default = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        status = Status(
            enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName },
            current = default.startsWith(context.packageName + "/"),
            spellChecker = context.getSystemService(TextServicesManager::class.java)?.isSpellCheckerEnabled == true,
            learned = LearnedWords.get(context).size,
        )
    }

    var page by remember { mutableStateOf<Category?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val preview = remember { KeyboardState().apply { suggestions = listOf("Bonj", "Bonjour", "Bonjours") } }

    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            cropping = withContext(Dispatchers.IO) { decodePhoto(context, uri) }
            if (cropping == null) Toast.makeText(context, "Photo illisible", Toast.LENGTH_SHORT).show()
        }
    }
    cropping?.let { photo ->
        CropScreen(photo, prefs, onCancel = { cropping = null }) { rect ->
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { saveCrop(context, photo, rect) }.isSuccess }
                if (saved) context.saveKeyboardPrefs(prefs.copy(background = System.currentTimeMillis()))
                else Toast.makeText(context, "Photo non enregistrée", Toast.LENGTH_SHORT).show()
                cropping = null
            }
        }
        return
    }

    Column(Modifier.safeDrawingPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = page
            if (current == null) {
                Text("Clavier", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp))
                SetupCard(status, imm)
                Category.entries.forEach { c -> MenuRow(c.icon, c.label, summary(c, prefs, status)) { page = c } }
                Spacer(Modifier.height(4.dp))
                MenuRow(Icons.Outlined.Keyboard, "Claviers du téléphone", null, external = true) {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
                TryField()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                    Text(current.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
                }
                Card {
                    when (current) {
                        Category.APPEARANCE -> AppearancePage(prefs, set) { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        Category.LAYOUT -> LayoutPage(prefs, set)
                        Category.TYPING -> TypingPage(prefs, set)
                        Category.GESTURES -> GesturesPage(prefs, set)
                        Category.LANGUAGE -> LanguagePage(prefs, set, status)
                        Category.SUGGESTIONS -> SuggestionsPage(prefs, set, status) { status = Status(status.enabled, status.current, status.spellChecker, 0) }
                    }
                }
            }
        }
        if (page?.preview == true) Keyboard(preview, prefs, PreviewActions, Modifier.fillMaxWidth())
    }
}

/** Ce que chaque catégorie contient en ce moment, en quelques mots. */
private fun summary(c: Category, p: KeyboardPrefs, s: Status): String = when (c) {
    Category.APPEARANCE -> listOfNotNull(p.theme.label, p.font.label.takeIf { p.font != KeyFont.SANS }, "${p.keyHeight} dp").joinToString(" · ")
    Category.LAYOUT -> listOfNotNull(p.layout.label, "rangée de chiffres".takeIf { p.numberRow }).joinToString(" · ")
    Category.TYPING -> listOfNotNull("Vibration".takeIf { p.vibrate }, "Son".takeIf { p.sound }, "Majuscule auto".takeIf { p.autoCap })
        .joinToString(" · ").ifEmpty { "Tout désactivé" }
    Category.GESTURES -> listOfNotNull("Curseur".takeIf { p.spaceCursor }, "Effacer des mots".takeIf { p.deleteSwipe })
        .joinToString(" · ").ifEmpty { "Appui long seulement" }
    Category.LANGUAGE -> p.language.label
    Category.SUGGESTIONS -> if (!p.suggestions) "Désactivées" else listOfNotNull(
        "Activées",
        "correcteur éteint".takeUnless { s.spellChecker },
        "${s.learned} mots appris".takeIf { p.learn },
    ).joinToString(" · ")
}

@Composable
private fun SetupCard(status: Status, imm: InputMethodManager) {
    if (status.enabled && status.current) return
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(CARD).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (!status.enabled) "Clavier n'est pas activé" else "Clavier n'est pas le clavier utilisé",
            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
        )
        if (!status.enabled) Button({ context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("Activer") }
        else Button({ imm.showInputMethodPicker() }) { Text("Choisir") }
    }
}

@Composable
private fun AppearancePage(p: KeyboardPrefs, set: (KeyboardPrefs) -> Unit, onPickPhoto: () -> Unit) {
    Choices("Thème", KeyTheme.entries, p.theme, { it.label }) { set(p.copy(theme = it)) }
    Choices("Police", KeyFont.entries, p.font, { it.label }) { set(p.copy(font = it)) }
    SectionTitle("Touches")
    Slide("Hauteur", p.keyHeight, 36..72, "dp") { set(p.copy(keyHeight = it)) }
    Slide("Taille du texte", p.fontSize, 14..32, "sp") { set(p.copy(fontSize = it)) }
    Slide("Arrondi", p.keyRadius, 0..24, "dp") { set(p.copy(keyRadius = it)) }
    Slide("Espacement", p.keyGap, 0..8, "dp") { set(p.copy(keyGap = it)) }
    Toggle("Bordures", "Un contour fin autour de chaque touche", p.borders) { set(p.copy(borders = it)) }
    SectionTitle("Photo de fond")
    if (p.background == 0L) OutlinedButton(onPickPhoto) { Text("Choisir une photo") }
    else {
        Slide("Assombrir la photo", p.photoDim, 0..90, "%") { set(p.copy(photoDim = it)) }
        Slide("Opacité des touches", p.keyAlpha, 0..100, "%") { set(p.copy(keyAlpha = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onPickPhoto) { Text("Changer") }
            TextButton({ set(p.copy(background = 0)) }) { Text("Retirer") }
        }
    }
    val d = KeyboardPrefs()
    TextButton({
        set(p.copy(theme = d.theme, font = d.font, keyHeight = d.keyHeight, fontSize = d.fontSize, keyRadius = d.keyRadius, keyGap = d.keyGap, borders = d.borders, keyAlpha = d.keyAlpha, photoDim = d.photoDim))
    }) { Text("Rétablir l'apparence par défaut") }
}

@Composable
private fun LayoutPage(p: KeyboardPrefs, set: (KeyboardPrefs) -> Unit) {
    Choices("Disposition", LayoutKind.entries, p.layout, { it.label }) { set(p.copy(layout = it)) }
    Toggle("Rangée de chiffres", "Sinon, appui long sur la première rangée", p.numberRow) { set(p.copy(numberRow = it)) }
}

@Composable
private fun LanguagePage(p: KeyboardPrefs, set: (KeyboardPrefs) -> Unit, status: Status) {
    val context = LocalContext.current
    Choices("Langue", WritingLanguage.entries, p.language, { it.label }) { set(p.copy(language = it)) }
    Info(
        "Ce qu'elle change",
        "Les suggestions du correcteur du téléphone et les mots du dictionnaire perso. Les mots appris servent dans toutes les langues.",
    )
    if (p.language != WritingLanguage.SYSTEM) Info(
        "Langue absente du correcteur ?",
        "Ajoute-la aux langues du téléphone, sinon seuls le dictionnaire perso et les mots appris servent.",
    )
    if (!status.spellChecker) Info("Correcteur du téléphone", "Désactivé : aucune suggestion du dictionnaire du téléphone")
    OutlinedButton({ context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) }) { Text("Langues du téléphone") }
}

@Composable
private fun TypingPage(p: KeyboardPrefs, set: (KeyboardPrefs) -> Unit) {
    SectionTitle("Retour au toucher")
    Toggle("Vibration", null, p.vibrate) { set(p.copy(vibrate = it)) }
    Toggle("Son", "Suit le volume des sons du système", p.sound) { set(p.copy(sound = it)) }
    SectionTitle("Correction de la frappe")
    Toggle("Majuscule automatique", "En début de phrase, selon le champ", p.autoCap) { set(p.copy(autoCap = it)) }
    Toggle("Double espace = point", "Deux espaces après un mot tapent « . »", p.doubleSpacePeriod) { set(p.copy(doubleSpacePeriod = it)) }
}

@Composable
private fun GesturesPage(p: KeyboardPrefs, set: (KeyboardPrefs) -> Unit) {
    SectionTitle("Glisser")
    Toggle("Sur la barre d'espace", "Déplace le curseur", p.spaceCursor) { set(p.copy(spaceCursor = it)) }
    Toggle("Vers la gauche sur ⌫", "Efface mot par mot", p.deleteSwipe) { set(p.copy(deleteSwipe = it)) }
    SectionTitle("Appui long")
    listOf(
        "Lettre" to "Accents, et chiffres sur la 1re rangée",
        "⌫" to "Efface en continu",
        "⇧" to "Verrouille les majuscules",
        "Espace" to "Change de clavier",
        "?123" to "Ouvre ces réglages",
    ).forEach { (key, what) -> Info(key, what) }
}

@Composable
private fun SuggestionsPage(p: KeyboardPrefs, set: (KeyboardPrefs) -> Unit, status: Status, onCleared: () -> Unit) {
    val context = LocalContext.current
    Toggle("Barre de suggestions", "Jamais dans les mots de passe", p.suggestions) { set(p.copy(suggestions = it)) }
    if (!p.suggestions) return
    SectionTitle("Sources")
    Info("Correcteur du téléphone", if (status.spellChecker) "Activé" else "Désactivé : seules les deux autres sources servent")
    if (!status.spellChecker) OutlinedButton({ context.startActivity(Intent(Settings.ACTION_SETTINGS)) }) { Text("Ouvrir les paramètres") }
    Info("Dictionnaire personnel", "Les mots ajoutés dans les paramètres d'Android")
    OutlinedButton({ context.startActivity(Intent(Settings.ACTION_USER_DICTIONARY_SETTINGS)) }) { Text("Modifier le dictionnaire") }
    Toggle("Apprendre mes mots", "${status.learned} mots retenus, jamais envoyés · rien en navigation privée", p.learn) { set(p.copy(learn = it)) }
    if (status.learned > 0) TextButton({ LearnedWords.get(context).clear(); onCleared() }) { Text("Oublier les mots appris") }
}

@Composable
private fun TryField() {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("Essayer ici") })
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(CARD).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 18.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, summary: String?, external: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(CARD).background(colors.surfaceContainerHigh).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(end = 16.dp).size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        }
        Icon(
            if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Info(title: String, subtitle: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(label: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, onChange)
    }
}

@Composable
private fun Slide(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text("$value $unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
}

@Composable
private fun <T> Choices(label: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    SectionTitle(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { FilterChip(it == selected, { onPick(it) }, { Text(name(it)) }) }
    }
}
