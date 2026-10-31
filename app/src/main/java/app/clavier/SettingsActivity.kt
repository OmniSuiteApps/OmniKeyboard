package app.clavier

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.textservice.TextServicesManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by remember { context.keyboardPrefs() }.collectAsState(KeyboardPrefs())
    fun set(p: KeyboardPrefs) = scope.launch { context.saveKeyboardPrefs(p) }

    val imm = context.getSystemService(InputMethodManager::class.java)
    var enabled by remember { mutableStateOf(false) }
    var learnedCount by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
        learnedCount = LearnedWords.get(context).size
    }
    val spellOn = remember { context.getSystemService(TextServicesManager::class.java)?.isSpellCheckerEnabled == true }
    val preview = remember { KeyboardState().apply { suggestions = listOf("Bonj", "Bonjour", "Bonjours") } }

    Column(Modifier.safeDrawingPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Clavier", style = MaterialTheme.typography.headlineMedium)

            Section("Mise en route")
            if (!enabled) Button({ context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("1. Activer Clavier") }
            OutlinedButton({ imm.showInputMethodPicker() }) { Text(if (enabled) "Choisir le clavier" else "2. Le choisir") }
            TryField()

            Section("Apparence")
            Choices("Thème", KeyTheme.entries, prefs.theme, { it.label }) { set(prefs.copy(theme = it)) }
            Choices("Police", KeyFont.entries, prefs.font, { it.label }) { set(prefs.copy(font = it)) }
            Slide("Hauteur des touches", prefs.keyHeight, 36..72, "dp") { set(prefs.copy(keyHeight = it)) }
            Slide("Taille du texte", prefs.fontSize, 14..32, "sp") { set(prefs.copy(fontSize = it)) }
            Slide("Arrondi", prefs.keyRadius, 0..24, "dp") { set(prefs.copy(keyRadius = it)) }
            Slide("Espacement", prefs.keyGap, 0..8, "dp") { set(prefs.copy(keyGap = it)) }
            Toggle("Bordures", prefs.borders) { set(prefs.copy(borders = it)) }

            Section("Disposition")
            Choices("Disposition", LayoutKind.entries, prefs.layout, { it.label }) { set(prefs.copy(layout = it)) }
            Toggle("Rangée de chiffres", prefs.numberRow) { set(prefs.copy(numberRow = it)) }

            Section("Comportement")
            Toggle("Vibration", prefs.vibrate) { set(prefs.copy(vibrate = it)) }
            Toggle("Son", prefs.sound) { set(prefs.copy(sound = it)) }
            Toggle("Majuscule automatique", prefs.autoCap) { set(prefs.copy(autoCap = it)) }
            Toggle("Double espace = point", prefs.doubleSpacePeriod) { set(prefs.copy(doubleSpacePeriod = it)) }

            Section("Gestes")
            Toggle("Glisser sur espace déplace le curseur", prefs.spaceCursor) { set(prefs.copy(spaceCursor = it)) }
            Toggle("Glisser à gauche sur ⌫ efface des mots", prefs.deleteSwipe) { set(prefs.copy(deleteSwipe = it)) }
            Text("Appui long : espace change de clavier, ?123 ouvre ces réglages, ⇧ verrouille les majuscules.", style = MaterialTheme.typography.bodySmall)

            Section("Suggestions")
            Toggle("Suggestions", prefs.suggestions) { set(prefs.copy(suggestions = it)) }
            if (!spellOn) Text(
                "Le correcteur orthographique du téléphone est désactivé : seuls le dictionnaire perso et les mots appris servent.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton({ context.startActivity(Intent(Settings.ACTION_USER_DICTIONARY_SETTINGS)) }) { Text("Dictionnaire personnel") }
            Toggle("Apprendre mes mots ($learnedCount)", prefs.learn) { set(prefs.copy(learn = it)) }
            OutlinedButton({ LearnedWords.get(context).clear(); learnedCount = 0 }) { Text("Oublier les mots appris") }
            Spacer(Modifier.height(8.dp))
        }
        Keyboard(preview, prefs, PreviewActions, Modifier.fillMaxWidth())
    }
}

@Composable
private fun TryField() {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("Essayer ici") })
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun Slide(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Text("$label : $value $unit")
    Slider(value.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
}

@Composable
private fun <T> Choices(label: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    Text(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { FilterChip(it == selected, { onPick(it) }, { Text(name(it)) }) }
    }
}
