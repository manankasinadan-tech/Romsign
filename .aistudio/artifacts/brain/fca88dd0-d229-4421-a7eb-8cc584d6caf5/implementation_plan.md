# ROMsign - Audit Cryptographique & Résignature Chirurgicale de ROMs Android

ROMsign est un outil d'ingénierie et d'audit cryptographique sur Android destiné aux développeurs et créateurs de ROMs personnalisées. L'application inspecte la chaîne de confiance des applications système extraites (UKA unpacker, Android Kitchen), analyse les schémas de signature (V1 à V4), audite les interdépendances critiques (`sharedUserId`, autorisations privilégiées) et réalise une résignature chirurgicale prévenant les bootloops par la purge et l'ajustement cohérent des caches d'optimisation ART (`.odex`, `.vdex`, `.art`, `.oat`, `.fsvmeta`).

---

### Décisions Validées & Paramètres Critiques

> [!IMPORTANT]
> Suite aux clarifications, l'architecture s'aligne sur les choix suivants :
> - **Gestion des clés de résignature** : Boîte de dialogue interactive lors de la résignature offrant le choix immédiat entre les clés de test AOSP standard intégrées (`platform`, `shared`, `media`, `releasekey`) et la génération/sélection d'une clé cryptographique personnalisée (RSA 2048/4096-bit, X.509 v3 auto-signé ou importée).
> - **Accès aux répertoires de travail** : Mode combiné prenant en charge l'exécution directe en shell root (`su`) pour les chemins privilégiés comme `/data/local/UnpackerSystem/`, tout en permettant la sélection de répertoires standard via le sélecteur Android.
> - **Traitement des caches d'optimisation & intégrité** : Sauvegarde automatique des fichiers originaux dans `sdcard/SIGNER/Data/backup/`, purge ciblée des caches invalidés (`.odex`, `.vdex`, `.art`, `.oat`, `.fsvmeta`) et mise à jour des définitions de permissions dans `etc/permissions/`.

---

### 1. Vue d'Ensemble & Fonctionnalités Clés

1. **App Info (Diagnostic Unitaire d'un APK)** :
   - Sélection d'un fichier APK via le sélecteur natif.
   - Transition fluide : masque temporairement les champs d'entrée pour afficher une console de logs en streaming temps réel.
   - Détection des schémas de signature : **JAR V1**, **APK Signature Scheme v2**, **v3** (avec rotation de clés), et **v4** (fs-verity).
   - Extraction cryptographique exhaustive : Empreintes SHA-256 / SHA-1, DN du sujet / émetteur, validité, algorithme de clé publique.
   - Analyse de la chaîne de confiance et du `sharedUserId` extrait du manifest binaire Android (`android:sharedUserId="android.uid.system"`, etc.).

2. **Audit Project (Analyse Globale & Dépendances)** :
   - Ciblage récursif d'un dossier unpacké (ex. `/data/local/UnpackerSystem/`, `/sdcard/...`).
   - **Exclusion stricte des liens symboliques (symlinks)** via vérification des métadonnées POSIX (`Files.isSymbolicLink`) pour empêcher les boucles et corruptions.
   - Détection des dépendances croisées : regroupement des APKs partageant le même `sharedUserId`, association avec les fichiers XML de permissions (`privapp-permissions-*.xml`, `platform.xml`).
   - Recensement des artefacts d'optimisation dépendants (`.odex`, `.vdex`, `.art`, `.oat`, `.fsvmeta`).
   - Génération des rapports structurés dans `sdcard/SIGNER/Data/` :
     - `signatures_audit_report.json` / `.txt`
     - `permissions_signatures_map.txt`
     - `invalidated_dependents_list.txt`
     - Dossier de sauvegarde `backup/` contenant les copies conformes des XML de permissions.

3. **Smart Resign (Résignature Chirurgicale & Régénération)** :
   - Sélection du projet audité avec validation des contraintes d'audit.
   - Résignature sélective ciblant exclusivement les APKs à impact systémique direct sur le boot (ex. packages système partageant `android.uid.system`, packages système déclarés dans la matrice de confiance).
   - Popup de sélection de clé (AOSP Platform Test Key vs Clé personnalisée).
   - Post-traitement automatisé : purge sécurisée des caches ART obsolètes et ajustement des balises `<signer-cert>` dans les XML de permissions.

---

### 2. Expérience Utilisateur & Ligne Directrice UI / UX

- **Aesthetic & Palette** :
  - Design épuré, ton sur ton et minimaliste.
  - Cartes fonctionnelles utilisant la même couleur de fond que la surface de l'application (`surface`), rehaussées par une bordure fine discrète (`outlineVariant`, 1.dp) et un rayon de courbure doux (16.dp).
  - Accents doux : Teintes ardoise/indigo profond (`#1E222B`), fond doux neutre sombre ou clair selon le thème système, accents menthe/émeraude douce pour les signatures valides et ambre discret pour les avertissements d'audit.
- **Organisation Visuelle** :
  - Entête élégante présentant l'état du système (Statut Root détecté, Espace de travail actif).
  - Trois onglets de navigation claire ou organisation verticale paginée :
    1. **App Info** : Sélecteur d'APK + Terminal de logs en direct + Rapport cryptographique dépliable.
    2. **Audit Project** : Saisie du chemin cible + Déclencheur « LANCER » + Visualisation de l'arborescence et métriques d'impact.
    3. **Smart Resign** : Configuration du périmètre de résignature + Choix des clés cryptographiques + Rapport de régénération.
- **Feedback & Logs** :
  - Console de journalisation animée façon terminal moderne (police monospace, coloration syntaxique des statuts INFO, WARN, SUCCESS, ERROR).

---

### 3. Architecture Technique & Moteur Cryptographique

```
┌────────────────────────────────────────────────────────┐
│                      UI Layer                          │
│  MainActivity  /  ROMsignApp  /  Theme (M3 Ton-sur-Ton)│
│  ├─ AppInfoScreen (Live Terminal & Crypto Report)      │
│  ├─ AuditProjectScreen (Recursive Scanner & Metrics)   │
│  └─ SmartResignScreen (Key Selector & Resign Workflow) │
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│                    ViewModel Layer                     │
│  ROMsignViewModel (StateFlow, Async Coroutines, Logs)  │
└────────────┬─────────────────────────────┬─────────────┘
             │                             │
┌────────────▼────────────────┐ ┌──────────▼─────────────┐
│   ApkCryptoEngine           │ │   RomAuditEngine       │
│  - V1 Zip Manifest parser   │ │  - Symlink-free walk   │
│  - V2/V3 Signing Block      │ │  - sharedUserId parser │
│  - X.509 Cert & SHA256      │ │  - Permissions XML map │
│  - RSA Signer & ZipAlign    │ │  - ART cache detector  │
└────────────┬────────────────┘ └──────────┬─────────────┘
             │                             │
┌────────────▼─────────────────────────────▼─────────────┐
│                 System & Shell Gateway                 │
│  - RootShellExecutor (su commands for /data/local)     │
│  - FileSystemStorage (/sdcard/SIGNER/Data output)      │
│  - KeyStoreManager (AOSP test keys & custom generator) │
└────────────────────────────────────────────────────────┘
```

#### Modules Clés :
1. **`ApkSignatureParser`** :
   - Lit directement le format ZIP/APK en binaire pour extraire le bloc de signature APK (Magic `APK Sig Block 42`, ID `0x7109871a` pour V2, `0xf05368c0` pour V3, et entrées `META-INF/*.RSA` pour V1).
   - Extrait les certificats X.509 encodés en DER et calcule les empreintes SHA-256/SHA-1.
   - Décode le `AndroidManifest.xml` binaire pour extraire `package`, `versionCode`, et `sharedUserId`.

2. **`RomAuditEngine`** :
   - Parcours récursif avec filtrage des symlinks via `File.canonicalPath` vs `File.absolutePath` et `Files.isSymbolicLink`.
   - Analyse des répertoires `system/app`, `system/priv-app`, `vendor/app`, `product/app`, etc.
   - Détection des fichiers `.odex`, `.vdex`, `.art`, `.oat` situés dans les sous-dossiers `oat/arm/`, `oat/arm64/` et des fichiers de métadonnées `.fsvmeta`.
   - Génération des rapports dans `/sdcard/SIGNER/Data/`.

3. **`SmartResignEngine` & `KeyStoreManager`** :
   - Embarque les paires de clés AOSP standard (`platform.pk8` / `platform.x509.pem`).
   - Fournit un générateur de clé personnalisée (clé RSA 2048 avec certificat auto-signé).
   - Résigne chirurgicalement les APKs système critiques (mise à jour du `META-INF` et du bloc V2/V3).
   - Purge les fichiers d'optimisation ART associés aux APKs résignés pour forcer `dex2oat` à la première initialisation de la ROM.

---

### 4. Plan de Test & Vérification

- **Vérification Unitaire & Cryptographique** :
  - Test de parsing sur APK réel/synthétique pour valider l'extraction de `sharedUserId` et la détection des blocs V1/V2/V3.
  - Test du filtre de détection et d'exclusion des symlinks.
- **Vérification Compilation & Compatibilité Android** :
  - Compilation sans erreurs (`compile_applet`) avec minSdk 24 et targetSdk 36.
  - Conformité Material Design 3, permissions de stockage (`MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`), et intégration du cycle de vie Compose.
