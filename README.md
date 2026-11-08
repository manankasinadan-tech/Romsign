# ROMsign 🛡️

**ROMsign** est une application Android d'ingénierie et d'audit cryptographique spécialement conçue pour les créateurs de ROMs personnalisées et les utilisateurs de kitchen d'extraction (notamment compatible avec les répertoires d'**UKA unpacker**).

Elle permet d'assurer l'audit unitaire et global de la chaîne de confiance, l'exclusion absolue des liens symboliques (symlinks) pour préserver l'intégrité de l'arborescence, ainsi que la résignature chirurgicale prévenant les bootloops par la purge et l'actualisation cohérente des caches d'optimisation ART (`.odex`, `.vdex`, `.art`, `.oat`, `.fsvmeta`).

---

## 🚀 Fonctionnalités Clés

1. **App Info (Diagnostic Unitaire d'un APK)** :
   - Sélection directe ou via sélecteur natif Android.
   - Streaming temps réel des logs d'analyse en masquant dynamiquement les contrôles.
   - Détection des schémas de signature **v1 (JAR)**, **v2 (APK Signing Block)**, **v3 (Key Rotation)** et **v4 (fs-verity)**.
   - Extraction des certificats X.509 (Sujet, Émetteur, Validité, SHA-256, SHA-1).
   - Analyse critique de `android:sharedUserId` (ex. `android.uid.system`).

2. **Audit Project (Analyse Globale & Dépendances)** :
   - Scan récursif de répertoires unpackés (ex. `/data/local/UnpackerSystem/` ou stockage externe).
   - **Exclusion stricte des symlinks** (prévention des boucles infinies et préservation des liens symboliques de la ROM).
   - Détection automatique des dépendances et caches ART invalidés (`.odex`, `.vdex`, `.art`, `.oat`, `.fsvmeta`).
   - Génération des livrables dans `sdcard/SIGNER/Data/` :
     - `signatures_audit_report.txt` & `.json`
     - `permissions_signatures_map.txt`
     - `invalidated_dependents_list.txt`
     - Sous-dossier `backup/` avec les fichiers de permissions originaux.
   - Visualiseur intégré dans l'application pour inspecter les rapports.

3. **Smart Resign (Résignature Chirurgicale & Régénération)** :
   - Popup de sélection entre clés de test AOSP intégrées (`platform`, `shared`) et création instantanée d'une clé personnalisée (RSA 2048-bit, certificat X.509 valide 25 ans).
   - Résignature sélective limitée aux APKs à impact systémique (`sharedUserId="android.uid.system"`, packages privilégiés).
   - Sauvegarde dans `sdcard/SIGNER/Data/backup/`.
   - Purge des caches ART obsolètes pour forcer `dex2oat` au premier boot et éviter tout bootloop.
   - Mise à jour automatique des balises `<signer-cert>` dans les XML de permissions.

---

## 🛠️ Compilation & Intégration Continue (CI / CD)

Le projet est entièrement configuré pour GitHub et GitHub Actions.

### Pipeline GitHub Actions (`.github/workflows/android.yml`)

À chaque `push` ou `pull request` sur les branches `main`, `master` ou `develop` (ainsi que via déclenchement manuel `workflow_dispatch`), le workflow :
1. Configure l'environnement **JDK 17 (Temurin)** et les caches Gradle.
2. Initialise automatiquement le fichier `.env` depuis `.env.example`.
3. Décode la clé de signature de debug `debug.keystore` (ou génère un keystore de secours).
4. Exécute la suite de tests unitaires JVM (`./gradlew testDebugUnitTest`).
5. Compile l'APK Debug (`./gradlew assembleDebug`).
6. Publie l'APK compilé sous forme d'artefact téléchargeable dans l'onglet **Actions** de GitHub (`romsign-debug-apk`).

### Compilation en Ligne de Commande Locale

```bash
# Rendre le wrapper exécutable (si nécessaire)
chmod +x gradlew

# Exécuter les tests unitaires
./gradlew testDebugUnitTest

# Compiler l'APK Debug
./gradlew assembleDebug

# L'APK généré se trouvera dans :
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📂 Structure du Projet

```text
├── .github/workflows/
│   └── android.yml               # Workflow GitHub Actions CI/CD
├── app/
│   ├── build.gradle.kts          # Configuration de build et dépendances Android
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/
│       │   │   ├── audit/        # Moteur d'audit récursif sans symlinks
│       │   │   ├── crypto/       # Parseur binaire AXML, blocs v1-v4 & clés
│       │   │   ├── model/        # Modèles de données cryptographiques
│       │   │   ├── shell/        # Exécuteur shell avec support su root
│       │   │   ├── signer/       # Moteur de résignature chirurgicale
│       │   │   ├── ui/           # Jetpack Compose UI (M3 ton sur ton)
│       │   │   └── MainActivity.kt
│       │   └── res/              # Ressources & icône adaptative
│       └── test/                 # Tests unitaires JVM
├── gradle/
│   └── wrapper/                  # Gradle Wrapper (v9.3.1)
├── gradlew & gradlew.bat         # Scripts de lancement Gradle
├── build.gradle.kts              # Configuration Gradle racine
└── settings.gradle.kts           # Paramètres du projet
```

---

## 🔒 Confidentialité & Sécurité

- Aucune donnée ou signature n'est transmise à des serveurs tiers. Tout le traitement cryptographique et l'audit d'arborescence sont exécutés localement sur le terminal ou la machine hôte.
