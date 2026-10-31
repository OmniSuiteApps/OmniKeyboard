# Clavier

Clavier Android personnel pour Pixel 9, ultra personnalisable, avec complétion par le dictionnaire du téléphone.

## Complétion

Trois sources, fusionnées dans la barre de suggestions (mot tapé à gauche, meilleure suggestion au centre) :

- **Correcteur orthographique du téléphone** (`TextServicesManager`, celui de *Paramètres > Système > Langues > Correcteur*).
  Le dictionnaire interne de Gboard n'est pas accessible aux autres claviers.
- **Dictionnaire personnel** d'Android (`UserDictionary`), lisible seulement quand Clavier est le clavier actif.
- **Mots appris** : fichier privé `mots_appris.tsv`, jamais envoyé. Rien n'est appris dans les mots de passe ni en
  navigation privée (`IME_FLAG_NO_PERSONALIZED_LEARNING`).

## Compiler et installer

Même clé de signature que Lanceur (`keystore.properties`, hors git).

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleRelease
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

Puis ouvrir l'appli Clavier : *Activer Clavier*, puis *Choisir le clavier*.

## Tests

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest
```

## Gestes

| Geste | Action |
|---|---|
| Appui long sur une lettre | Accents (et chiffres sur la 1re rangée) ; glisser pour choisir |
| Glisser sur espace | Déplace le curseur |
| Glisser à gauche sur ⌫ | Efface mot par mot |
| Appui long sur ⌫ | Efface en continu |
| Appui long sur ⇧ | Verrouille les majuscules |
| Appui long sur espace | Change de clavier |
| Appui long sur ?123 | Ouvre les réglages |
