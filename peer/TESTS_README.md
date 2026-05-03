# Eirbone Peer — Test Suite

Tests unitaires et d'intégration pour le module **peer** (`org.eirb.eirbone`).  
Framework : **JUnit 5** (junit-jupiter 5.10.2), lancé par **Maven Surefire 3.2.5**.

---

## Lancer les tests

```bash
# Tous les tests (unit + intégration)
./run_tests.sh

# Tests unitaires uniquement
./run_tests.sh unit

# Tests d'intégration uniquement
./run_tests.sh integration

# Tous les tests + affichage des rapports texte
./run_tests.sh report

# Directement avec Maven
mvn clean test
```

Les rapports XML/TXT sont générés dans `target/surefire-reports/`.

---

## Structure des tests

```
src/test/java/org/eirb/eirbone/
├── config/
│   └── ConfigTest.java                  (5 tests)
├── storage/
│   └── FileManagerTest.java             (12 tests)
├── download/
│   ├── DownloadTaskTest.java            (11 tests)
│   └── DownloadManagerParserTest.java   (8 tests)
├── tracker/
│   └── TrackerClientTest.java           (7 tests)
└── peer/
    ├── PeerServerTest.java              (5 tests)  — unit
    ├── PeerClientTest.java              (3 tests)  — unit
    └── PeerServerClientIT.java          (3 tests)  — intégration
```

**Convention de nommage** : `*Test.java` = tests unitaires, `*IT.java` = tests d'intégration.

---

## Description de chaque fichier

### `ConfigTest` — `config/Config.java`

| Test | Ce qui est vérifié |
|------|--------------------|
| `defaultsWhenFileMissing` | Toutes les valeurs par défaut quand `config.ini` est absent |
| `loadsAllFields` | Lecture complète : adresse tracker, ports, max-peers, interval, shared-dir, web-port |
| `supportsListenPortKey` | La clé `listen-port` est acceptée comme alias de `peerPort` |
| `supportsPeerPortKey` | La clé `peer-port` est aussi acceptée |
| `partialFileKeepsDefaults` | Un fichier partiel n'écrase que les champs présents |

---

### `FileManagerTest` — `storage/FileManager.java`

| Test | Ce qui est vérifié |
|------|--------------------|
| `listSharedFilesEmpty` | Dossier vide → liste vide |
| `listSharedFilesReturnsOneFile` | Fichier présent → `filename`, `length`, `md5` (32 hex), `pieceSize` |
| `listSharedFilesIgnoresSubdirectories` | Les sous-dossiers sont ignorés |
| `computeMD5EmptyFile` | MD5 de fichier vide = `d41d8cd98f00b204e9800998ecf8427e` |
| `computeMD5KnownContent` | MD5 de `"hello world"` = `5eb63bbbe01eeed093cb22bb8f5acdc3` |
| `computeMD5IsDeterministic` | Deux appels successifs retournent le même hash |
| `readWritePieceRoundTrip` | Écriture puis lecture d'une pièce → contenu identique |
| `readPieceZeroPadsLastPiece` | Dernière pièce < 1024 octets → zéros en rembourrage |
| `writeSecondPieceDoesNotCorruptFirst` | Écriture pièce 1 ne corrompt pas la pièce 0 |
| `truncateShortenFile` | `truncate("file", 500)` → fichier fait exactement 500 octets |
| `renameAfterDownloadMovesFile` | Fichier temporaire (md5) renommé en nom final, source supprimée |
| `getFileReturnsCorrectAbsolutePath` | `getFile("x")` retourne `sharedDir/x` |
| `fileInfoToStringFormat` | Format `"filename length 1024 md5"` respecté |

---

### `DownloadTaskTest` — `download/DownloadManager.DownloadTask`

| Test | Ce qui est vérifié |
|------|--------------------|
| `initialStatusIsQueued` | Statut initial = `"queued"` |
| `initialDownloadedPiecesIsZero` | `downloadedPieces` démarre à 0 |
| `totalPiecesExactMultiple` | 2048 octets → 2 pièces |
| `totalPiecesRoundsUp` | 1025 octets → 2 pièces (ceil) |
| `totalPiecesExactlyOne` | 1024 octets → 1 pièce |
| `totalPiecesOneByte` | 1 octet → 1 pièce |
| `progressZeroInitially` | `progressPercent()` = 0.0 au départ |
| `progressHalfway` | 1/2 pièces téléchargées → 50 % |
| `progressComplete` | 2/2 pièces → 100 % |
| `progressHundredWhenDoneAndSizeZero` | taille 0 + status `"done"` → 100 % |
| `progressZeroWhenQueuedAndSizeZero` | taille 0 + status `"queued"` → 0 % |

---

### `DownloadManagerParserTest` — parsing privé de `DownloadManager`

Teste via réflexion les méthodes privées `extractTokens`, `parseMd5`, `parseLength`
qui analysent la réponse `list [filename size pieceSize md5]` du tracker.

| Test | Ce qui est vérifié |
|------|--------------------|
| `extractTokensValidResponse` | Tokens extraits correctement depuis une réponse valide |
| `extractTokensNullResponseReturnsNull` | `null` → retourne `null` |
| `extractTokensWrongFilenameReturnsNull` | Nom de fichier différent → `null` |
| `extractTokensInvalidPrefixReturnsNull` | Pas de préfixe `"list ["` → `null` |
| `extractTokensEmptyListReturnsNull` | `"list []"` → `null` |
| `parseMd5ExtractsCorrectValue` | Hash MD5 correctement isolé |
| `parseMd5ReturnsNullWhenMissing` | Retours `null` sur réponse invalide |
| `parseLengthExtractsCorrectValue` | Taille du fichier correctement parsée en `long` |
| `parseLengthReturnsZeroWhenMissing` | 0 sur réponse invalide |

---

### `TrackerClientTest` — `tracker/TrackerClient.java`

Utilise un faux serveur tracker en-process (ServerSocket + thread) pour éviter
toute dépendance réseau externe.

| Test | Ce qui est vérifié |
|------|--------------------|
| `isConnectedAfterConnect` | `isConnected()` = true après `connect()`, false après `disconnect()` |
| `lookFilesEmptyListResponse` | Réponse `"list []"` → liste vide |
| `lookFilesOneFile` | Parsing complet : filename, size, pieceSize, md5 |
| `lookFilesDeduplicatesByMd5` | Deux entrées avec le même md5 → une seule dans la liste |
| `getFilePeerAddresses` | Parsing de `"[ip:port ip2:port2]"` → liste de `PeerAddress` |
| `getFileEmptyListResponse` | Réponse `"[]"` → liste vide |
| `peerAddressToString` | `PeerAddress.toString()` = `"ip:port"` |

---

### `PeerServerTest` — `peer/PeerServer.java`

Démarre un vrai `PeerServer` sur un port aléatoire et s'y connecte avec un
client de test basique. Utilise une lecture octet par octet pour les lignes
texte afin de ne pas consommer les données binaires.

| Test | Ce qui est vérifié |
|------|--------------------|
| `interestedUnknownMd5_returnsHaveWithDecodableBuffermap` | MD5 inconnu → réponse `"have ..."` avec buffermap Base64 valide |
| `interestedKnownMd5_returnsHaveWithAllBitsSet` | 1 fichier (1 pièce) → bit 7 du buffermap = 1 (0x80) |
| `interestedTwoPieces_buffermapHasTwoBitsSet` | Fichier de 2 pièces → bits 7 et 6 du buffermap (0xC0) |
| `getPieces_unknownMd5_returnsErrorLine` | MD5 inconnu → ligne `"error ..."` |
| `getPieces_returnsCorrectHeader_andBinaryData` | Header `"data md5 [0 1024 1 1024]"` + 2048 octets binaires identiques |

---

### `PeerClientTest` — `peer/PeerClient.java`

Crée un faux serveur peer qui envoie header et données binaires dans des flush
séparés (+ pause de 20 ms), garantissant que le `BufferedReader` de PeerClient
ne pre-lit pas les octets binaires.

| Test | Ce qui est vérifié |
|------|--------------------|
| `downloadSmallFile` | Fichier < 1024 octets téléchargé et contenu identique |
| `downloadExactlyOnePiece` | Fichier de exactement 1024 octets |
| `downloadCallsProgressListenerForEachPiece` | Listener appelé 3 fois pour 3 pièces, `total=3`, `lastDone=3` |

---

### `PeerServerClientIT` — intégration PeerServer ↔ PeerClient

Tests bout-en-bout : vrai `PeerServer` + vrai `PeerClient`, sans aucun mock.

| Test | Ce qui est vérifié |
|------|--------------------|
| `fullDownloadSmallFile` | Fichier texte téléchargé, contenu identique à l'original |
| `fullDownloadMultiplePieces` | 5 pièces exactes (5120 octets), progression reportée correctement |
| `fullDownloadNonAlignedSize` | 2 pièces + pièce partielle → fichier tronqué à la bonne taille |

---

## Notes techniques

### Protocole P2P testé

```
Client → Serveur :  interested <md5>
Serveur → Client :  have <md5> <buffermap_base64>
Client → Serveur :  getpieces <md5> [0 1 2 ...]
Serveur → Client :  data <md5> [0 1024 1 1024 ...]
Serveur → Client :  <données binaires> (1024 octets × nb_pièces)
```

### Buffermap

- 1 bit par pièce (bit 7 = pièce 0, bit 6 = pièce 1…)
- 1 octet pour 8 pièces → `ceil(nbPieces / 8)` octets
- Tous les bits à 1 si le fichier est complet (seeder)
- Encodé en Base64

### Ports réseau dans les tests

Chaque test qui ouvre un `ServerSocket` utilise `new ServerSocket(0)` pour
laisser l'OS choisir un port libre, évitant ainsi les conflits entre tests.
