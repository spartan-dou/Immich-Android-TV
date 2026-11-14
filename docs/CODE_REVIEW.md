# Revue de code — Immich Android TV (fork `spartan`)

- **Périmètre** : branche `spartan` du fork (commit `a20b686`), comparée à `upstream/main` (commit `51ea302`, version 7.0.0).
- **Méthode** : lecture du code (réseau, authentification, stockage des clés, chargement d'images et vidéo, build/CI/signature), `git diff upstream/main...origin/spartan`, et état des workflows du fork (`gh run list`). Rien n'a été compilé ni exécuté : les constats viennent de la lecture du code, et ce qui reste une hypothèse est signalé comme tel.
- **Convention** : chaque constat cite `chemin:ligne`, une sévérité (critique / élevée / moyenne / faible) et une recommandation. Les chemins `…/immich/` abrègent `app/src/main/java/nl/giejay/android/tv/immich/`.

## Résumé — les 5 points les plus importants

1. **Élevée — sécurité : la clé d'API circule dans des URLs.** Elle est ajoutée en paramètre `apiKey=` aux URLs vidéo, aux URLs passées au lecteur externe et aux vignettes des chaînes de l'écran d'accueil (`…/immich/api/util/ApiUtil.kt:22,34,41`). Elle se retrouve donc dans les journaux du serveur et du reverse proxy, dans la base TvProvider et chez toute application choisie comme lecteur externe. Le risque est aggravé par le repli sur une clé à permission `all` lors de la connexion par téléphone (`…/immich/auth/ImmichLoginClient.kt:79-83`).
2. **Élevée — CI : le build nocturne du fork est cassé depuis le 13/11/2026 et exécute du code upstream avec les secrets de signature.** Le rebase automatique échoue sur `AuthFragmentStep1.kt` (commit `703ab00`), donc la dernière release reste basée sur upstream 6.2.0 alors qu'upstream est en 7.0.0. Par ailleurs, le workflow compile sans aucune revue le code upstream fraîchement récupéré, avec le keystore et ses mots de passe dans l'environnement (`.github/workflows/spartan-build.yml:70-82`).
3. **Élevée — performance et mémoire : abonnements aux préférences jamais libérés et scopes de coroutines jamais annulés.** `PreferenceManager.subscribe*` repose sur `observeForever` sans désabonnement (`…/immich/shared/prefs/LiveSharedPreferences.kt:27,57`), et `VerticalCardGridFragment` crée deux `CoroutineScope` qui ne sont jamais annulés (`…/immich/shared/fragment/VerticalCardGridFragment.kt:62-63`). Chaque grille ouverte reste en mémoire et relance des requêtes réseau à chaque changement de tri.
4. **Moyenne — performance : « charger tout l'album avant d'ouvrir la visionneuse » est quadratique.** Introduit par le commit `a20b686`, ce chargement se fait par pages de 50 en série, avec une déduplication en O(n²) sur le thread principal (`…/immich/assets/GenericAssetFragment.kt:144-152`, `…/immich/shared/fragment/VerticalCardGridFragment.kt:312-313,379`).
5. **Moyenne — sécurité du transport et du stockage.** HTTP en clair et autorités de certification utilisateur sont autorisés partout (`app/src/main/res/xml/network_security_config.xml:3-7`), la désactivation de la vérification TLS est globale à tous les comptes, la connexion par téléphone envoie le mot de passe en HTTP clair sur le réseau local, et `allowBackup="true"` sauvegarde les préférences (`app/src/main/AndroidManifest.xml:42-43`).

---

## 1. Performance

### P1 — Abonnements `observeForever` jamais retirés (élevée)
- `…/immich/shared/prefs/LiveSharedPreferences.kt:27` et `:57` : `subscribeTyped` et `subscribeMultiple` appellent `observeForever` et ne renvoient rien qui permette de se désabonner.
- Appelants liés à un cycle de vie de fragment : `…/immich/assets/GenericAssetFragment.kt:67` (dans `onCreate`) et `…/immich/album/AlbumFragment.kt:56` (dans `onViewCreated`, donc un abonnement de plus à chaque recréation de vue).
- Conséquences : chaque fragment de grille déjà ouvert reste référencé, avec sa liste d'assets et son adaptateur. À chaque changement de tri ou de filtre, tous ces fragments, même détruits, exécutent `clearState()` puis `fetchInitialItems()`, donc des requêtes réseau inutiles. Le fork rend le changement de tri plus fréquent (`SortChooser`), ce qui amplifie l'effet.
- **Recommandation** : faire renvoyer un handle de désabonnement (ou exposer un `Flow`/`LiveData` observé avec `viewLifecycleOwner`), et se désabonner dans `onDestroy`/`onDestroyView`.

### P2 — Scopes de coroutines non annulés dans les grilles (élevée)
- `…/immich/shared/fragment/VerticalCardGridFragment.kt:62-63` : `ioScope` et `mainScope` sont créés avec un `Job()` indépendant ; aucune annulation n'existe dans le fichier (seuls `ScreenSaverService.kt:50` et `ScreenSaverPreviewFragment.kt:99` annulent leur scope).
- Conséquences : les chargements continuent après la sortie de l'écran (récursion `loadMoreAssets` ligne 248 comprise), puis appellent `setDataOnMain` sur un fragment détruit.
- **Recommandation** : utiliser `viewLifecycleOwner.lifecycleScope` ou `lifecycleScope`, ou annuler les deux scopes dans `onDestroy`.

### P3 — Chargement complet d'un album avant la visionneuse : pages de 50 et O(n²) (moyenne)
- `…/immich/assets/GenericAssetFragment.kt:144-152` : boucle `while (!allPagesLoaded)` qui enchaîne des pages de `FETCH_PAGE_COUNT = 50` (`VerticalCardGridFragment.kt:393-394`). Un album de 5 000 éléments demande 100 requêtes en série avant l'ouverture.
- À chaque page, `setData` (`VerticalCardGridFragment.kt:312-313`) filtre avec `assets.contains(it)` (égalité structurelle de la data class `Asset`) puis recopie toute la liste avec `+=`. `addAssetsPaginated` fait un `removeAll` (ligne 379). Le tout s'exécute sur le thread principal.
- La boucle tourne sur `Dispatchers.Default` pendant que `fetchNextItems` (ligne 224) peut tourner sur `ioScope` : `currentPage` (ligne 237) est modifié sans synchronisation, d'où des pages demandées en double.
- **Recommandation** : pour ce cas, appeler `listAssetsFromAlbum` (pages de 1 000, 3 en parallèle) comme le fait déjà le tri non chronologique (`…/immich/album/AlbumDetailsFragment.kt:42`), dédupliquer avec un `HashSet` d'identifiants, et protéger la pagination par un `Mutex`.

### P4 — Chaque requête en échec est rejouée sans clé d'API (moyenne)
- `…/immich/api/ApiClientFactory.kt:27-36` : le `try/catch` englobe `chain.proceed(newRequest)`. Toute `IOException` réseau (timeout, serveur injoignable) est interceptée, journalisée comme « invalid format », puis la requête est relancée sans l'en-tête `x-api-key`.
- Conséquences : le délai d'échec est doublé sur un serveur injoignable, et le message de log est trompeur.
- **Recommandation** : ne protéger que la construction de l'en-tête (`addHeader` peut lever une `IllegalArgumentException`) et laisser `proceed` hors du `try`.

### P5 — Le mode « vérification TLS désactivée » ne partage pas le pool de connexions (faible)
- `…/immich/api/util/UnsafeOkHttpClient.kt:44` crée un `OkHttpClient.Builder()` neuf, alors que le commit `750bad6` a introduit un `baseClient` partagé pour le seul chemin normal (`ApiClientFactory.kt:13,17-19`). Chaque `ApiClient` et chaque rechargement du module Glide (`…/immich/shared/CustomGlideModule.kt:37-46`) ouvre alors son propre pool et son propre dispatcher.
- **Recommandation** : dériver le client « unsafe » de `baseClient.newBuilder()`.

### P6 — Démarrage : réflexion Kotlin et Keystore sur le thread principal (moyenne ; coût non mesuré)
- `…/immich/shared/prefs/PreferenceManager.kt:54` parcourt `Pref::class.sealedSubclasses` via `kotlin-reflect`, puis crée un `LivePreference` observé en permanence par préférence (ligne 56). `migrateApiKeyIfNeeded` (ligne 53) et le déchiffrement de la clé passent par l'AndroidKeyStore. Tout cela s'exécute dans `ImmichApplication.onCreate` (`…/immich/ImmichApplication.kt:43`).
- `AccountManager.accounts()` déchiffre toutes les clés à chaque appel (`…/immich/auth/AccountManager.kt:119`). Il est appelé trois fois par affichage de `ProfilesFragment` (`…/immich/auth/ProfilesFragment.kt:26,42,61`) et au démarrage (`…/immich/MainActivity.kt:188`).
- **Recommandation** : remplacer la réflexion par une liste statique des préférences, et mettre en cache en mémoire la liste des comptes déchiffrés.

### P7 — Autres points (faible)
- `…/immich/shared/prefs/PreferenceManager.kt:77` : `"\\s".toRegex()` est recompilé à chaque lecture de `hostName`, donc pour chaque URL de vignette (`ApiUtil.kt:19`). À précompiler ou normaliser une seule fois à l'enregistrement.
- `…/immich/shared/prefs/PreferenceManager.kt:44` : `liveContext` est une `MutableMap` non synchronisée, lue depuis des threads IO et écrite depuis le thread principal. Utiliser une `ConcurrentHashMap`.
- `…/immich/shared/util/AssetUtil.kt:28,67` : `queue.removeAt(0)` sur une `ArrayList` et, lignes 34-48, deux `filter` complets par portrait. Le regroupement des portraits est en O(n²) ; utiliser un `ArrayDeque` et un index par personne.
- `…/immich/api/ApiClient.kt:147` : `getAlbum(albumId)` n'est appelé que pour lire `assetCount`. *Hypothèse* : selon la version du serveur, cette route renvoie aussi tous les assets de l'album si `withoutAssets=true` n'est pas passé (`…/immich/api/service/ApiService.kt:31-32`), ce qui ferait télécharger une réponse volumineuse pour rien.
- `…/immich/api/ApiClient.kt:191-199` : `similarAssets` interroge les années l'une après l'autre ; un `pmap` borné irait plus vite.
- `…/immich/timeline/TimelineViewModel.kt:197` : les buckets chargés ne sont jamais évincés, et `recomputeLayout()` (ligne 198) recalcule toute la mise en page à chaque nouveau mois. Le coût croît avec la profondeur de défilement.
- `…/immich/timeline/TimelineMosaicAdapter.kt:196-200` : chaque `bind` d'une rangée détruit puis ré-« inflate » toutes ses cellules. Un pool de vues de cellules éviterait ces allocations pendant le défilement.
- `…/immich/shared/util/AssetUtil.kt:89` : par défaut, la visionneuse charge la variante `fullsize` (l'original pour JPEG/PNG/WebP). `ViewPager` précharge les pages voisines, donc plusieurs originaux sont téléchargés en parallèle, ce qui va à l'encontre de l'objectif du commit `750bad6`. Proposer `preview` par défaut sur les écrans 1080p et `fullsize` seulement en 4K ou au zoom.
- `…/immich/shared/CustomGlideModule.kt:56-57` : `applyOptions` est vide (aucune taille de cache mémoire ou disque, aucun format par défaut), alors que le manifeste demande `largeHeap` (`AndroidManifest.xml:47`). Sur les appareils à faible RAM, fixer `PREFER_RGB_565` par défaut et borner le cache mémoire.

---

## 2. Sécurité

### S1 — Clé d'API dans les URLs (élevée)
- `…/immich/api/util/ApiUtil.kt:41` : toutes les URLs vidéo portent `?apiKey=…`, y compris pour le lecteur interne, qui envoie déjà l'en-tête `x-api-key` (`…/immich/slider/ImmichMediaSlider.kt:46-48`).
- `ApiUtil.kt:34` : même chose pour l'original forcé.
- `mediaslider/mediaslider/src/main/kotlin/nl/giejay/mediaslider/plugin/ExternalPlayerButtonControllerPlugin.kt:29-32` : cette URL est transmise par `Intent.ACTION_VIEW` à n'importe quelle application vidéo.
- `…/immich/homescreenchannels/HomeScreenChannelManager.kt:321` : la clé est écrite dans la base TvProvider via l'URL des vignettes, lisible par le lanceur.
- Conséquences : la clé apparaît dans les journaux d'accès du serveur et du reverse proxy, et dans l'historique d'applications tierces. Avec HTTP en clair (S3), elle est lisible sur le réseau.
- **Recommandation** : ne plus mettre la clé dans l'URL pour le lecteur interne (l'en-tête suffit). Pour le lecteur externe et les chaînes, utiliser une clé dédiée à permissions minimales (`asset.view`), un lien de partage Immich, ou un `ContentProvider` local qui sert l'image.

### S2 — Repli sur une clé à permission `all` (moyenne)
- `…/immich/auth/ImmichLoginClient.kt:95-97` : n'importe quelle réponse 400 à la création de clé est interprétée comme « permission inconnue ». Les lignes 79-83 créent alors une clé `all`, qui donne l'accès complet au compte, administration comprise si l'utilisateur est administrateur. Cette clé est ensuite stockée sur le téléviseur et exposée par S1.
- **Recommandation** : ne replier que si le message d'erreur désigne bien une permission inconnue, retirer une à une les permissions refusées plutôt que de passer à `all`, et afficher sur la page du téléphone qu'une clé à accès complet a été créée.

### S3 — Transport : HTTP en clair, autorités utilisateur, TLS désactivable globalement (moyenne)
- `app/src/main/res/xml/network_security_config.xml:3-7` et `app/src/main/AndroidManifest.xml:46` : HTTP en clair autorisé pour tous les domaines et autorités de certification utilisateur acceptées. Le choix se comprend pour de l'auto-hébergement, mais rien n'avertit l'utilisateur quand l'URL saisie est en `http://`.
- `…/immich/api/util/UnsafeOkHttpClient.kt:16-46` : gestionnaire de confiance qui accepte tout, `hostnameVerifier` toujours vrai, et `SSLContext.getInstance("SSL")` (ligne 39).
- `…/immich/auth/AuthFragmentStep2.kt:92` : l'option est une préférence globale, absente du modèle `Account` (`…/immich/auth/AccountManager.kt:15-25`). L'activer pour un serveur auto-signé désactive donc la vérification TLS pour tous les comptes, y compris ceux hébergés sur Internet.
- **Recommandation** : stocker l'option par compte ; préférer l'épinglage du certificat du serveur (confiance au premier usage) à « tout accepter » ; utiliser `TLS` ; afficher un avertissement pour une URL `http://` non locale.

### S4 — Connexion par téléphone sur le réseau local (moyenne)
Points positifs vérifiés : jeton de chemin de 128 bits tiré de `SecureRandom` (`…/immich/auth/LocalLoginServer.kt:237-238`), PKCE S256 et `state` (`ImmichLoginClient.kt:144-149`), limite de tentatives (ligne 132), échappement HTML (ligne 221), tailles bornées (lignes 229-230), session temporaire fermée après création de la clé (`ImmichLoginClient.kt:86-89`), serveur arrêté à la fermeture de l'écran (`…/immich/auth/AuthByLocalPhoneFragment.kt:143-146`).

Points à corriger :
- `LocalLoginServer.kt:66` : le formulaire est servi en HTTP clair. L'e-mail et le mot de passe Immich (ligne 108) transitent donc en clair sur le Wi-Fi, et le jeton secret figure dans l'URL. C'est le risque principal ; il est inhérent à l'approche, mais devrait être réduit par une mise en avant du SSO, où seul un code protégé par PKCE transite.
- `LocalLoginServer.kt:63` : l'écoute se fait sur toutes les interfaces (`InetSocketAddress(PORT)`) et non sur la seule adresse affichée.
- `LocalLoginServer.kt:68-70` : boucle `accept` sur un seul thread. Un client qui ouvre une connexion sans rien envoyer bloque le serveur pendant 10 s (ligne 228), et chaque connexion pendant jusqu'à 15 s par appel réseau.
- `LocalLoginServer.kt:184` : l'en-tête `Location` est construit avec l'URL renvoyée par le serveur saisi, sans filtrer `\r\n`. Un serveur malveillant peut injecter des en-têtes dans la réponse envoyée au téléphone (faible).
- `ImmichLoginClient.kt:26-29` : le client HTTP de connexion ignore la préférence de désactivation TLS. Sur un serveur auto-signé, la connexion par téléphone échoue alors que le reste de l'application fonctionne.
- `ImmichLoginClient.kt:171-175` : la liste de permissions ne contient pas `asset.update`, alors que l'application appelle `PUT assets/{id}` pour les favoris (`…/immich/api/service/ApiService.kt:40-41`). Avec une clé créée par ce parcours, le bouton favori échouera sur un serveur qui applique les permissions par clé.
- `AuthByLocalPhoneFragment.kt:107` : si la vue a disparu au moment où la clé est créée, `view?.post` ne fait rien. La clé existe côté serveur mais n'est jamais enregistrée (clé orpheline).
- **Recommandation** : lier le socket à l'adresse locale choisie, traiter chaque connexion dans un petit pool de threads, valider l'URL de redirection avec `toHttpUrlOrNull()`, ajouter `asset.update`, réutiliser la configuration TLS de l'application, et supprimer la clé créée si elle ne peut pas être enregistrée.

### S5 — Sauvegarde Android et stockage (moyenne)
- `app/src/main/AndroidManifest.xml:42-43` : `allowBackup="true"` et `fullBackupContent="true"`, sans `dataExtractionRules`. Les préférences partent en sauvegarde : URL des serveurs, e-mails des comptes (en clair, `AccountManager.kt:138`) et clés chiffrées. Après restauration, les clés sont inutilisables puisque la clé Keystore n'est pas sauvegardée, et le compte est alors supprimé silencieusement (`AccountManager.kt:118-119`).
- `…/immich/security/ApiKeyCipher.kt:37-39` : si le Keystore est défaillant, la clé est stockée en clair avec le préfixe `plain:`. Le commentaire lignes 19-21 reconnaît qu'elle est alors incluse dans la sauvegarde.
- Le chiffrement lui-même est correct : AES-256-GCM, clé non exportable, IV aléatoire fourni par le chiffreur (`ApiKeyCipher.kt:34-36,91-95`).
- **Recommandation** : exclure le fichier de préférences des sauvegardes avec `dataExtractionRules` et `fullBackupContent`, ou passer `allowBackup` à `false`.

### S6 — Clé d'API affichée en clair et comptes sans protection (faible)
- `…/immich/auth/AuthFragmentStep2.kt:55-61` : la clé courante est préremplie dans un champ `TYPE_CLASS_TEXT`. Toute personne ayant la télécommande peut la lire. Utiliser `TYPE_TEXT_VARIATION_PASSWORD` et ne pas préremplir.
- `…/immich/auth/ProfilesFragment.kt:58-65` : le changement de compte est libre, ce qui est cohérent avec un écran « qui regarde ? ». Voir l'idée de verrouillage par code en section 5.
- `…/immich/auth/AccountManager.kt:74-82` : supprimer un compte ne révoque pas la clé côté serveur. Le commentaire le dit, mais les clés « Immich TV (modèle) » s'accumulent à chaque reconnexion.

### S7 — Liens profonds et composants exportés (faible)
- `app/src/main/AndroidManifest.xml:100-105` : le schéma `immichtv://` est `BROWSABLE`. N'importe quelle application peut ouvrir un album ou un asset par identifiant, et le titre de l'album vient du paramètre `name` sans vérification (`…/immich/homescreenchannels/ChannelDeepLinks.kt:32-34`). L'impact se limite à de l'affichage ; retirer `BROWSABLE` suffit, puisque seul le lanceur a besoin de ces liens.
- `AndroidManifest.xml:93-94,129-130` : filtres d'intention Cast et métadonnée pointant vers `castconnect.CastReceiverOptionsProvider`, une classe absente du dépôt. Configuration morte à supprimer.

### S8 — Dépendances et build (faible)
- `app/build.gradle:122` : `play-services-auth` est déclaré, mais aucun import `com.google.android.gms` n'existe dans `app/src/main`. La dépendance est inutile depuis le retrait de Firebase et de l'ancienne connexion par téléphone.
- `app/build.gradle:88` : `lifecycle-extensions:2.2.0`, artefact abandonné. `gradle/libs.versions.toml:69` : `android.arch.core:core-testing:1.1.1`, antérieur à AndroidX, ce qui oblige à garder `android.enableJetifier=true` (`gradle.properties:35`).
- `build.gradle:14` : le dépôt JitPack est ouvert à tous les modules, ce qui crée un risque de substitution de dépendance. Le restreindre avec `content { includeGroup(...) }` à la seule bibliothèque de QR code.
- `app/build.gradle:25-28` : la signature release se replie sur un fichier `keystore` et le mot de passe `"dummy"`. Ce n'est pas un secret réel, mais un build release local échoue de façon peu claire ; mieux vaut ne configurer la signature que si les variables d'environnement sont présentes.
- Les versions des bibliothèques principales (OkHttp via Retrofit 3.0.0, Glide 5.0.9, Media3 1.10.1, Gson 2.14.0) sont récentes. Je n'ai pas croisé ces versions avec une base de vulnérabilités, faute d'outil d'audit dans le dépôt ; ajouter Dependabot ou Renovate sur le fork couvrirait ce point.

### S9 — Journalisation (faible)
- `…/immich/api/interceptor/ResponseLoggingInterceptor.kt:38` : en mode debug, l'URL, les en-têtes de réponse et 500 caractères du corps sont écrits dans logcat. Ces données sont personnelles (noms de fichiers, lieux), mais la clé n'y figure pas puisque les appels Retrofit ne la mettent pas dans l'URL.
- `…/immich/api/util/ApiUtil.kt:66-78` : le corps d'erreur brut du serveur est affiché tel quel dans un toast.

---

## 3. Design et architecture

### D1 — État global par singletons et préférences « miroir » (moyenne)
- `PreferenceManager`, `AccountManager`, `ApiClient.getClient` et `ImmichApplication.appContext` sont des singletons globaux. `ApiClientConfig(...)` est reconstruit à la main à quatre endroits au moins (`VerticalCardGridFragment.kt:92-100`, `FavoriteService.kt:19-26`, `ScreenSaverAssetLoader.kt:59-65`, `ApiClient.kt:52-57`), alors que `ApiClientConfig.fromPrefs()` existe.
- `…/immich/api/ApiClient.kt:105-111` : le cache du client n'est pas sûr entre threads (`apiClient!!` après une affectation concurrente).
- `…/immich/shared/prefs/Preferences.kt:29,35` et beaucoup d'autres lignes : `ImmichApplication.appContext!!` est évalué à l'initialisation d'objets statiques, ce qui rend l'ordre d'initialisation fragile (d'où le `ContentProvider` `AppContextInitializer`) et les tests plus difficiles.
- **Recommandation** : introduire un petit conteneur de dépendances (`AppContainer` créé dans `Application`) qui fournit `ApiClient`, le compte actif et les préférences.

### D2 — Gestion d'erreurs par chaînes de caractères (moyenne)
- Partout `Either<String, T>`. `…/immich/api/ApiClient.kt:312` décide d'un repli avec `error.contains("(400)")`, et `ApiUtil.kt:67` teste `contains("required permission: all")`.
- `VerticalCardGridFragment.kt:237-241` : `currentPage` est incrémenté avant l'appel. En cas d'erreur, la page est perdue et n'est jamais redemandée.
- **Recommandation** : un type d'erreur scellé (`Network`, `Http(code, message)`, `Auth`, `Parse`) et une pagination qui n'avance qu'en cas de succès.

### D3 — Comparateur de tri non conforme au contrat (moyenne)
- `…/immich/shared/prefs/PhotosOrder.kt:11-15,23-27` avec `…/immich/shared/util/Utils.kt` (`compareToNullSafe`) : quand les deux dates sont nulles, `compare(a, b)` et `compare(b, a)` renvoient tous deux `1`. `TimSort` peut alors lever « Comparison method violates its general contract ». `AlbumFragment.kt:68-73` attrape déjà cette exception pour les albums, mais `GenericAssetFragment.kt:113` ne le fait pas. Le code vient d'upstream, mais le fork le réutilise dans `FAVORITES_FIRST` (`PhotosOrder.kt:44`).
- **Recommandation** : `compareBy(nullsLast()) { it.exifInfo?.dateTimeOriginal ?: it.fileModifiedAt }`, inversé pour l'ordre décroissant.

### D4 — Duplication (faible)
- Le filtre `exclude_immich_tv` est écrit trois fois (`ApiClient.kt:242-244`, `GenericAssetFragment.kt:84-86`, `ScreenSaverAssetLoader.kt:54-56`). Depuis le fork, les trois copies ne traitent plus les RAW de la même façon.
- La construction de `MediaSliderConfiguration`, soit une vingtaine de paramètres, est copiée dans `GenericAssetFragment.kt:170-191`, `MainActivity.kt:148-168`, `FolderFragment.kt:94-115` et `ScreenSaverAssetLoader.kt:78-101`. Cette dernière contient d'ailleurs une coquille : `maxCutOffWidth` y reçoit `SLIDER_MAX_CUT_OFF_HEIGHT` (ligne 87).
- **Recommandation** : une fabrique unique `sliderConfigurationFromPrefs(items, loadMore, screen)`.

### D5 — Fichiers volumineux et code mort (faible)
- `…/immich/timeline/TimelineFragment.kt` (1 142 lignes), `mediaslider/.../view/MediaSliderController.kt` (775 lignes), `…/immich/shared/prefs/Preferences.kt` (690 lignes).
- `…/immich/shared/CustomGlideModuleOld.kt` est entièrement commenté. `CardPresenter.kt:63-84` contient un bloc commenté. `AuthFragmentStep1.kt:18-21` nomme des champs d'instance comme des constantes (`SELECTED_OPTION` est mutable).
- `.gitmodules` déclare `mediaslider` comme sous-module alors que le dossier est versionné directement dans l'arbre (`git ls-tree HEAD mediaslider` renvoie un `tree`, pas un `commit`). Les workflows font `submodules: 'recursive'` pour rien, et le README demande `git clone --recurse`.
- `build.gradle:20-24` : ktlint 0.37.2 avec `enforceCheck false`. Le formatage n'est donc pas vérifié.

### D6 — Tests (moyenne)
- Il y a 32 fichiers de tests JVM/Robolectric sous `app/src/test`, bien ciblés sur la logique pure (timeline, DTO, PKCE, chiffrement, tri), aucun test instrumenté, et aucun test dans le module `mediaslider`.
- Ne sont pas couverts : `LocalLoginServer` (analyse HTTP, limite de tentatives, parcours SSO), `AccountManager` (bascule, adoption de l'ancien compte, compte dont la clé n'est plus déchiffrable), `ApiClientFactory` et `VerticalCardGridFragment` (pagination). Ce sont justement les zones à risque de cette revue.
- **Recommandation** : tests JVM du serveur local sur un port éphémère, de `AccountManager` avec des `SharedPreferences` Robolectric, et de l'intercepteur avec `MockWebServer`.

---

## 4. Section dédiée au fork (`spartan` par rapport à `upstream/main`)

État au moment de la revue : 18 commits propres au fork (77 fichiers, +1 901 / −462 lignes), 19 commits de retard sur upstream (point de divergence `037cb7c`). La branche `origin/main` du fork est elle aussi 19 commits derrière `upstream/main`.

### Ce que le fork apporte
| Thème | Commits | Appréciation |
|---|---|---|
| Suppression de Firebase, du mode démo et de la connexion par le serveur du développeur | `da31b95`, `703ab00` | Bon pour la vie privée : plus aucun tiers sur le chemin des identifiants. Les journaux restent locaux (`ImmichApplication.kt:69-76`). |
| Connexion par téléphone sur le réseau local, avec SSO | `2a1f4ee`, `02061eb` | Conception soignée (PKCE, jeton aléatoire, limites). Réserves en S2 et S4. |
| Comptes multiples | `6052e7d` | Simple et efficace (préférences miroir). Réserves F3 et F4 ci-dessous. |
| Mémoire et réseau | `750bad6`, `37e4df4` | Limitation à 3 pages en parallèle, RGB_565, fond réduit. Réserve : `fullsize` par défaut (P7). |
| Interface (grilles, menu, tri rapide, flou, badges vidéo, interrupteurs dates et lieux) | `8d749ad` à `8d8fdb5` | `SoftBlurTransformation` est correcte (clé de cache disque, `equals`/`hashCode`) et testée. |
| Chargement de tout l'album avant la visionneuse | `a20b686` | Voir P3. |
| Identifiant d'application dédié et workflow nocturne | `d719ea0`, `570cd86`, `6235f09` | Voir F1 et F2. |

### F1 — Le build nocturne échoue depuis le 13/11/2026 (élevée)
- `gh run list --repo spartan-dou/Immich-Android-TV` : les exécutions planifiées des 13/11 et 14/11 sont en échec ; celles du 01/11 au 12/11 ont réussi, mais sans rien construire puisque la release existait déjà. La dernière vraie construction date du 06/11 (`workflow_dispatch`, 4 min 56 s).
- Cause, lue dans les journaux : `CONFLICT (content)` dans `app/src/main/java/nl/giejay/android/tv/immich/auth/AuthFragmentStep1.kt` lors du rebase du commit `703ab00` (« Remove Firebase and the demo mode ») sur upstream.
- Conséquence : la dernière release (`spartan-037cb7c-a20b686`) reste basée sur upstream 6.2.0, alors qu'upstream a publié la 7.0.0.
- `.github/workflows/spartan-build.yml:55-56` : le titre de l'issue contient le SHA upstream. La déduplication ne fonctionne donc que pour un même commit upstream, et chaque nouveau commit upstream ouvre une nouvelle issue tant que le conflit dure. Deux issues sont déjà ouvertes sur le fork (numéros 1 et 2).
- **Recommandation** : résoudre le conflit à la main (`git fetch upstream && git rebase upstream/main` sur `spartan`, puis push forcé). Dans le workflow, utiliser un titre d'issue stable et commenter l'issue existante. Pour espacer les conflits, regrouper les suppressions du fork (Firebase, démo) dans un seul commit.

### F2 — Code upstream compilé automatiquement avec les secrets de signature (élevée)
- `.github/workflows/spartan-build.yml:34-35,52` : le workflow récupère `upstream/main` et rebase dessus. Les lignes 70-82 lancent ensuite Gradle (scripts de build, wrapper et plugins venus d'upstream) avec `KEYSTORE_BASE64`, les mots de passe du keystore et `GH_TOKEN` en écriture (`contents: write`, lignes 11-13 et 22) dans l'environnement.
- Conséquence : une modification malveillante ou compromise côté upstream (script Gradle, plugin, wrapper) s'exécuterait la nuit suivante, sans revue, avec accès à la clé de signature du fork et au droit de publier des releases.
- **Recommandation** : (1) séparer un job « test », sans secrets, d'un job « signature » ; (2) n'injecter `GH_TOKEN` que dans les étapes qui appellent `gh` ; (3) protéger le job de signature par un environnement GitHub avec approbation manuelle quand `upstream_sha` change, ou ne signer que si le diff upstream ne touche ni `*.gradle`, ni `gradle/`, ni `gradlew` ; (4) épingler les actions par SHA (`actions/checkout@v4` et `actions/setup-java@v4`, lignes 26 et 63) et ajouter une validation du wrapper Gradle ; (5) ajouter `timeout-minutes`.
- Points secondaires : `rm -f` du keystore (ligne 82) n'est pas exécuté si Gradle échoue, ce qui a peu d'impact sur un runner éphémère ; les anciennes releases ne sont jamais purgées.

### F3 — Comptes multiples : réglages qui ne suivent pas le compte (moyenne)
- `…/immich/auth/AccountManager.kt:15-25,90-103` : seuls l'hôte, la clé et trois sélections d'albums sont propres à un compte. Restent partagés entre comptes : `DISABLE_SSL_VERIFICATION` (S3), les éléments exclus des chaînes d'accueil (`HomeScreenChannelManager.kt:309`) et les préférences de tri par album.
- `…/immich/homescreenchannels/HomeScreenChannelManager.kt:71` : la synchronisation des chaînes n'est déclenchée que par `CHANNEL_ALBUMS` et `ENABLE_HOME_SCREEN_CHANNELS`. *Hypothèse fondée sur le comportement documenté de `SharedPreferences`* : si deux comptes ont la même sélection d'albums (vide, par exemple), le changement de compte ne notifie rien, et la chaîne « Photos récentes » continue d'afficher les photos du compte précédent, avec sa clé dans les URLs, jusqu'au prochain passage de l'application au premier plan (`ImmichApplication.kt:58-62`).
- **Recommandation** : appeler `HomeScreenChannelManager.scheduleRefresh("account_changed")` dans `AccountManager.load()` et `clearActive()`, et déplacer l'option TLS dans `Account`.

### F4 — Divers points propres au fork (faible)
- `README.md:26,28,80-85` : le README décrit encore la connexion par le serveur du développeur, le mode démo et `google-services.json`, tous supprimés par le fork. Il ne mentionne ni la connexion locale, ni le SSO (URI de redirection `http://<ip-tv>:47913/oauth-callback` à autoriser), ni les comptes multiples.
- `app/build.gradle:160` : l'identifiant d'application dédié est une bonne décision. En revanche, `versionCode` et `versionName` restent ceux d'upstream (lignes 16-17), si bien que toutes les releases du fork portent « 6.2.0 (148) ». Ajouter un `versionNameSuffix "-spartan"` et un `versionCode` dérivé de la date ou du nombre de commits.
- `app/src/main/AndroidManifest.xml:23` et `app/build.gradle:115` : la permission `BILLING` et la bibliothèque de facturation sont conservées. *Hypothèse* : avec un autre identifiant d'application et hors Play Store, les achats de don ne peuvent pas aboutir ; masquer l'entrée « don » ou la remplacer par un lien.
- `.github/workflows/release.yml:70` et `android.yml` : ces workflows d'upstream restent actifs sur le fork (déclenchés sur `main` et sur les tags `v*`) et visent le paquet Play Store d'upstream. Sans secrets, ils échoueraient ; mieux vaut les désactiver sur le fork.
- `…/immich/auth/AuthFragmentStep2.kt:96` : un compte ajouté par clé d'API n'a pas d'e-mail, donc `AccountManager.add` (ligne 58) ne détecte jamais le doublon. Ressaisir la même clé crée un second profil identique.

---

## 5. Idées de fonctionnalités

1. **Verrouillage des profils par code PIN.** L'écran « qui regarde ? » (`ProfilesFragment`) laisse passer librement d'un compte à l'autre. Un code à 4 chiffres optionnel par compte, saisi à la télécommande, protégerait les bibliothèques privées sur un téléviseur partagé.
2. **Révocation de la clé à la suppression d'un compte.** Mémoriser l'identifiant de la clé créée par `ImmichLoginClient` et appeler la suppression de clé côté serveur depuis `AccountManager.remove`, pour ne plus laisser de clés « Immich TV (modèle) » orphelines.
3. **Recherche.** Recherche intelligente (CLIP) et par métadonnées, avec saisie vocale Android TV. Le bouton en forme d'orbe de Leanback est déjà détourné pour le tri (`VerticalCardGridFragment.kt:214-220`). L'API de recherche par métadonnées est déjà utilisée, la recherche sémantique s'y ajouterait.
4. **Vue carte et lieux.** Les données EXIF de ville et de pays sont déjà exploitées (`AssetMetaDataMapping`). Une grille « Lieux » regroupée par pays et ville prolongerait naturellement l'interrupteur « Afficher le lieu ».
5. **Écran de veille « Souvenirs » et favoris.** `getMemories()` existe déjà pour la timeline (`ApiClient.kt:308-321`). Proposer « Ce jour-là » et « Favoris » comme types d'écran de veille (`ScreenSaverType`).
6. **Qualité adaptative des images.** Choisir `preview` ou `fullsize` selon la résolution de l'écran et la mémoire disponible (`ActivityManager.isLowRamDevice`), et ne charger l'original qu'au zoom. Complète le commit `750bad6`.
7. **Diagnostic de connexion.** Un écran « Tester la connexion » qui vérifie l'URL, la version du serveur, la validité de la clé et les permissions manquantes (par exemple `asset.update` pour les favoris), à la place des toasts d'erreur bruts.
8. **Albums partagés et liens de partage.** Ouvrir un lien de partage Immich en lecture seule (QR code scanné ou saisie), sans compte. Ce mécanisme pourrait aussi servir les vignettes des chaînes d'accueil sans exposer la clé d'API (S1).
9. **Épinglage de certificat au premier usage.** Remplace « désactiver la vérification TLS » par « faire confiance à ce certificat » pour les serveurs auto-signés (S3).
10. **Mise à jour intégrée du fork.** Vérifier la dernière release GitHub du fork et proposer l'installation de l'APK, puisque l'application n'est pas distribuée par le Play Store.
