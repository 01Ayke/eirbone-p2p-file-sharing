# Eirbone — Application P2P d'échange de gros fichiers

## Table des matières

1. [Architecture générale](#1-architecture-générale)
2. [Protocole Tracker–Pair](#2-protocole-trackerPair)
3. [Protocole Pair–Pair](#3-protocole-pairPair)
4. [Tracker (C)](#4-tracker-c)
5. [Peer (Java)](#5-peer-java)
6. [Configuration (`config.ini`)](#6-configuration-configini)
7. [Gestion multi-threadée](#7-gestion-multi-threadée)
8. [Fichiers de log](#8-fichiers-de-log)
9. [Interface web embarquée](#9-interface-web-embarquée)
10. [Compilation et lancement](#10-compilation-et-lancement)
11. [Tests](#11-tests)

---

## 1. Architecture générale

Le réseau Eirbone est centralisé autour d'un **Tracker** unique connu de tous les pairs. Chaque **Pair** est à la fois serveur (il partage des pièces) et client (il télécharge des pièces). Il existe deux types de pairs :

- **Seeder** : possède la totalité d'un fichier et le partage.
- **Leecher** : est en cours de téléchargement d'un fichier ; partage au fur et à mesure les pièces déjà reçues.

```
          ┌─────────┐
          │ Tracker │  (C, TCP, port configurable)
          └────┬────┘
         ┌─────┴─────┐
    ┌────┴───┐   ┌───┴────┐
    │  Pair  │◄──►  Pair  │  (Java, TCP, échange de pièces)
    └────────┘   └────────┘
```

Les communications se font **exclusivement en TCP**. Les messages de contrôle sont du texte ASCII terminés par `\n`. Les données binaires (pièces de fichiers) suivent l'en-tête texte de la réponse `data`.

---

## 2. Protocole Tracker–Pair

Toutes les commandes se terminent par `\n` (ou `\r\n`). Le symbole `<` indique ce que le pair envoie, `>` ce que le tracker répond.

### 2.1 `announce` — annonce initiale

Envoyé une seule fois à la connexion. Le pair déclare son port d'écoute P2P, la liste des fichiers qu'il possède entièrement (seed) et les fichiers en cours de téléchargement (leech).

```
< announce listen <port> seed [<nom> <taille> <taille_pièce> <md5> ...] leech [<md5> ...]
> ok
```

**Exemple :**
```
< announce listen 2222 seed [file_a.dat 2097152 1024 8905e92afeb80fc7722ec89eb0bf0966] leech []
> ok
```

**Implémentation :**
- Tracker (C) : `handle_announce()` dans `tracker_protocol.c`
- Peer (Java) : `TrackerClient.announce()` — appelé au démarrage dans `Main.java`
- La connexion TCP vers le tracker est **keep-alive** : elle reste ouverte pour toutes les commandes suivantes.

### 2.2 `look` — recherche de fichiers

Le pair interroge le tracker pour obtenir la liste des fichiers disponibles sur le réseau.

```
< look [<critère1> <critère2> ...]
> list [<nom> <taille> <taille_pièce> <md5> ...]
```

**Exemples :**
```
< look [filename=file_a.dat filesize>1048576]
> list [file_a.dat 2097152 1024 8905e92afeb80fc7722ec89eb0bf0966]

< look []
> list [file_a.dat 2097152 1024 8905e92a... file_b.dat 3145728 1024 330a5722...]
```

**Critères supportés :**
- `filename=<nom>` : filtrage exact sur le nom (obligatoire selon l'énoncé)
- `filesize><N>` : filtrage sur la taille (implémenté en option)
- Sans critère (`look []`) : retourne tous les fichiers connus du tracker

**Implémentation :**
- Tracker : `handle_look()` dans `tracker_protocol.c`
- Peer : `TrackerClient.look()` / `TrackerClient.lookFiles()` — renvoie une liste de `NetworkFile`

### 2.3 `getfile` — liste des pairs possédant un fichier

```
< getfile <md5>
> peers <md5> [<IP1>:<port1> <IP2>:<port2> ...]
```

**Exemple :**
```
< getfile 8905e92afeb80fc7722ec89eb0bf0966
> peers 8905e92afeb80fc7722ec89eb0bf0966 [1.1.1.2:2222 1.1.1.3:3333]
```

**Implémentation :**
- Tracker : `handle_getfile()` dans `tracker_protocol.c` — cherche les `owners` du fichier dans `tracker_storage`
- Peer : `TrackerClient.getFile()` — retourne une liste de `PeerAddress`

### 2.4 `update` — mise à jour périodique

Envoyé périodiquement (interval configurable) pour mettre à jour les listes seed/leech sans rouvrir de connexion.

```
< update seed [<md5> ...] leech [<md5> ...]
> ok
```

**Exemple :**
```
< update seed [] leech [8905e92afeb80fc7722ec89eb0bf0966]
> ok
```

**Implémentation :**
- Tracker : `handle_update()` dans `tracker_protocol.c` — met à jour les listes et recalcule les `owners`
- Peer : `TrackerClient.sendUpdate()` — appelé par le thread `tracker-updater` dans `Main.java` toutes les `update-interval` secondes

> **Note :** La mise à jour périodique utilise `update` (clés MD5 uniquement), pas `announce` (qui inclut noms et tailles et est réservé à la connexion initiale).

### 2.5 `bye` — déconnexion propre

```
< bye
> bye
```

**Implémentation :**
- Tracker : `handle_bye()` — retire le pair de toutes les listes d'owners et le marque déconnecté
- Peer : `TrackerClient.bye()` — appelé dans `Main.java` à l'arrêt du programme

### 2.6 Fermeture après 3 messages malformés

Conformément à l'énoncé, si le tracker reçoit **3 messages malformés consécutifs** d'un même client, il ferme la connexion. Le compteur est remis à zéro à chaque commande valide.

**Implémentation :** compteur `malformed_count` dans `handle_client()` (`tracker_protocol.c`)

---

## 3. Protocole Pair–Pair

### 3.1 `interested` / `have` — découverte du buffermap

Avant de demander des pièces, un pair envoie `interested` pour connaître ce que son voisin possède.

```
< interested <md5>
> have <md5> <buffermap_base64>
```

**Buffermap :** vecteur de bits où le bit `i` indique la présence de la pièce `i`. Encodé en **Base64** pour la transmission texte.

**Exemple** (fichier de 2 Mo, 2048 pièces, buffermap de 256 octets) :
```
< interested 8905e92afeb80fc7722ec89eb0bf0966
> have 8905e92afeb80fc7722ec89eb0bf0966 AAAA...==
```

**Implémentation :**
- PeerServer (Java) : `handleInterested()` — calcule le buffermap à partir des pièces possédées et répond
- PeerClient (Java) : envoie `interested`, lit la réponse `have`, décode le buffermap pour établir la liste des pièces à demander

### 3.2 Échange périodique de `have`

Périodiquement, un pair informe ses voisins de son avancement. La réponse est le buffermap du voisin.

```
< have <md5> <buffermap_base64>
> have <md5> <buffermap_base64>
```

**Implémentation :**
- PeerServer : `handleHave()` — reçoit le buffermap du pair, **répond avec son propre buffermap** pour le même fichier
- PeerClient : `broadcastHave()` — envoie `have` à tous les pairs du pool de connexions persistantes et **lit leur réponse** pour maintenir le flux TCP synchronisé

### 3.3 `getpieces` / `data` — transfert de pièces

```
< getpieces <md5> [<idx1> <idx2> <idx3> ...]
> data <md5> [<idx1> <taille1> <idx2> <taille2> ...]\n
  <payload binaire>
```

Le message `data` est composé d'un **en-tête texte terminé par `\n`** suivi d'un **payload binaire**. Le récepteur lit d'abord l'en-tête pour connaître le nombre et la taille des pièces attendues, puis lit exactement `N × 1024` octets binaires.

**Taille des pièces fixée à 1024 octets.** La dernière pièce est complétée par des zéros (padding) à l'envoi et tronquée à la taille réelle à la réception.

**Exemple :**
```
< getpieces 8905e92afeb80fc7722ec89eb0bf0966 [3 5 7]
> data 8905e92afeb80fc7722ec89eb0bf0966 [3 1024 5 1024 7 1024]\n
  <3072 octets de données binaires>
```

**Implémentation :**
- PeerServer : `handleGetPieces()` — construit l'en-tête, lit chaque pièce depuis le disque avec `FileManager.readPiece()`, les envoie en binaire
- PeerClient : `fetchPiecesFrom()` — sépare la lecture de l'en-tête (octet par octet jusqu'à `\n`) et la lecture binaire (`DataInputStream.readFully()`) pour éviter tout sur-buffering

### 3.4 Message Framing

TCP étant un protocole orienté flux, **un appel à `recv()` ne correspond pas forcément à un message complet**. Deux mécanismes sont utilisés :

- **Messages de contrôle** : lus caractère par caractère jusqu'au `\n` (tracker C : `read_line()` ; peer Java : lecture manuelle depuis `DataInputStream`)
- **Payload binaire** : lu avec `DataInputStream.readFully()` — garantit la lecture d'exactement N octets

---

## 4. Tracker (C)

### Structure du code

| Fichier | Rôle |
|---|---|
| `main.c` | Point d'entrée, lecture config, socket TCP, thread pool |
| `tracker_protocol.c/h` | Handlers des commandes (`announce`, `look`, `getfile`, `update`, `bye`) |
| `tracker_storage.c/h` | Gestion en mémoire des pairs et fichiers, verrou mutex |
| `tracker_state.h` | Structures `Peer` et `FileEntry` |
| `tracker_utils.c/h` | Thread pool, logging, `read_line()`, `send_response()` |
| `config.c/h` | Lecture de `config.ini`, valeurs par défaut |

### Structures de données

```c
typedef struct {
    char ip[INET_ADDRSTRLEN];
    int  listen_port;
    int  connected;
    char seeded_keys[100][MAX_KEY];   // clés MD5 des fichiers seedés
    int  seed_count;
    char leeched_keys[100][MAX_KEY];  // clés MD5 des fichiers en cours
    int  leech_count;
    time_t last_seen;
} Peer;

typedef struct {
    char filename[MAX_FILENAME];
    long length;
    int  piece_size;
    char key[MAX_KEY];                // MD5 du fichier
    char owners[MAX_PEERS_PER_FILE][64]; // "ip:port" des seeders/leechers
    int  owner_count;
} FileEntry;
```

### Thread pool

Le tracker utilise un **pool de threads** (évite la création d'un thread par connexion) :

- Taille et capacité de la file configurables dans `config.ini` (`thread_pool_size`, `thread_pool_queue`)
- Chaque thread du pool attend une connexion entrante via `pthread_cond_wait`
- `thread_pool_add_task()` dépose le `client_fd` dans une file circulaire protégée par mutex

### Protection des données partagées

`peers[]` et `files[]` sont protégés par un **mutex global** (`storage_lock` / `storage_unlock`) utilisé dans tous les handlers de commandes.

### Nettoyage automatique

`purge_orphan_files()` est appelée après chaque `announce`, `update` ou `bye` : supprime du tableau `files[]` toute entrée dont `owner_count == 0`.

### Port en ligne de commande

```bash
./tracker <port>          # port prioritaire sur config.ini
./tracker                 # utilise config.ini ou la valeur par défaut (9000)
```

---

## 5. Peer (Java)

### Structure du code

| Classe | Rôle |
|---|---|
| `Main` | Orchestration : config, tracker, PeerServer, thread de mise à jour, CLI, WebServer |
| `TrackerClient` | Protocole Tracker–Pair (announce, look, getfile, update, bye) |
| `PeerServer` | Serveur TCP pair-à-pair (interested, have, getpieces) |
| `PeerClient` | Client TCP pair-à-pair (fetchPiecesFrom, broadcastHave) |
| `DownloadManager` | Téléchargement multi-peers, gestion des tâches |
| `FileManager` | I/O disque : lecture/écriture pièces, calcul MD5, manifestes |
| `Config` | Lecture de `config.ini` |
| `WebServer` | Serveur HTTP embarqué (API REST + interface web) |

### Connexions TCP keep-alive

**Vers le tracker :** une seule connexion TCP ouverte au démarrage, réutilisée pour toutes les commandes (`announce`, `look`, `getfile`, `update`, `bye`).

**Entre pairs :** `PeerClient` maintient un **pool de connexions persistantes** (`Map<String, PeerConn>`) — une connexion TCP par pair distant, réutilisée pour les échanges `interested`/`getpieces`/`have`.

### Calcul MD5

Le hash MD5 est calculé sur **la totalité du contenu du fichier** (`FileManager.computeMD5()`) et sert de clé unique dans tous les échanges. Conforme à la spécification (`md5sum`-compatible).

### Manifeste de téléchargement

Conformément à la mise à jour du protocole, pour chaque fichier **en cours de téléchargement**, un fichier manifeste (`<md5>.manifest`) est créé dans `shared/` :

```
md5=8905e92afeb80fc7722ec89eb0bf0966
filename=file_a.dat
size=2097152
pieces=0,1,2,5,7
```

- Créé dès le début du téléchargement (liste de pièces vide)
- Mis à jour après chaque batch de pièces reçues
- Supprimé après finalisation du fichier

Cela permet de reprendre un téléchargement interrompu et d'annoncer les pièces partielles aux autres pairs.

### Téléchargement multi-peers

`DownloadManager.doMultiPeerDownload()` :

1. Interroge le tracker (`look` puis `getfile`) pour obtenir la liste des pairs
2. Crée le manifeste initial
3. Pour chaque pair disponible, télécharge les pièces manquantes via `PeerClient.fetchPiecesFrom()`
4. Après chaque batch, diffuse le buffermap mis à jour à tous les pairs connectés (`broadcastHave`)
5. Quand toutes les pièces sont reçues : tronque le padding, renomme le fichier temporaire (`<md5>` → `<filename>`), supprime le manifeste

### Nombre maximum de pairs

Configurable via `max-peers` dans `config.ini`. Correspond au nombre maximum de pairs auxquels le pair se connecte simultanément pour un téléchargement.

### Tolérance aux pannes

- Si un pair distant se déconnecte inopinément pendant un `getpieces`, l'exception est capturée, la connexion est retirée du pool, et le téléchargement continue avec les autres pairs disponibles.
- Si le tracker est indisponible au démarrage, le pair démarre quand même (PeerServer et interface web restent accessibles).

### Interface utilisateur CLI

```
Commandes :
  download <fichier>   recherche le fichier sur le tracker et le télécharge depuis tous les pairs disponibles
  list [<fichier>]     liste les fichiers disponibles sur le réseau (filtrage optionnel par nom)
  quit                 arrêt propre (bye au tracker, fermeture des connexions)
```

---

## 6. Configuration (`config.ini`)

### Tracker (`tracker/src/config.ini`)

```ini
[tracker]
port         = 9000     # port TCP d'écoute
max_peers    = 100      # capacité maximale du tableau peers[]
max_files    = 500      # capacité maximale du tableau files[]

[network]
timeout          = 30   # timeout socket (secondes)
backlog          = 10   # taille de la file d'attente listen()
thread_pool_size = 4    # nombre de threads dans le pool
thread_pool_queue= 50   # capacité de la file de tâches du pool

[log]
level = info
file  = tracker.log
```

### Peer (`peer/config.ini`)

```ini
tracker-address  = 127.0.0.1   # adresse IP du tracker
tracker-port     = 9000         # port TCP du tracker
listen-port      = 2222         # port d'écoute P2P du pair
shared-dir       = ./shared     # répertoire des fichiers partagés
max-peers        = 5            # nombre max de pairs pour un téléchargement
update-interval  = 30           # intervalle en secondes entre les update au tracker
```

Les paramètres peuvent être surchargés en **ligne de commande** :
```bash
java -jar peer.jar <port-peer> [<ip-tracker>] [<port-tracker>]
```

---

## 7. Gestion multi-threadée

### Tracker (C)

Utilise un **pool de threads** (`select`-compatible) plutôt qu'un thread par connexion (évite la dégradation de performances lors de nombreuses connexions simultanées) :

- Un ensemble fixe de threads (`thread_pool_size`) attend du travail
- Chaque connexion entrante est déposée dans une file FIFO protégée par mutex
- Les threads récupèrent les tâches via une variable de condition (`pthread_cond_wait`)

### Peer (Java)

- `PeerServer` utilise un `ExecutorService` (pool de 10 threads) pour gérer les connexions entrantes
- `DownloadManager.download()` lance un thread dédié par téléchargement (`download-<filename>`)
- Un thread `tracker-updater` envoie périodiquement les mises à jour au tracker
- `WebServer` utilise un `CachedThreadPool` pour les requêtes HTTP

---

## 8. Fichiers de log

Conformément à l'énoncé, les deux programmes contrôlent la verbosité via un système de log.

### Tracker

- Sortie sur **stdout** et dans `tracker.log` (chemin configurable dans `config.ini`)
- Niveaux : `INFO`, `DEBUG`, `WARN`
- Format : `[YYYY-MM-DD HH:MM:SS] [NIVEAU] message`
- Thread-safe : protégé par un mutex dédié au log

### Peer

- Sortie sur **stdout** (et stderr pour les erreurs)
- Préfixes : `[INFO]`, `[DEBUG]`, `[WARN]`, `[ERROR]`
- Affichage des commandes envoyées (`[DEBUG] >> ...`) et reçues (`[DEBUG] << ...`)

---

## 9. Interface web embarquée

Le pair embarque un **serveur HTTP** (port configurable via `web-port` dans `config.ini`, défaut : 8080) permettant une visualisation graphique de l'activité.

### Endpoints REST

| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/` | Interface HTML |
| `GET` | `/api/files?search=<nom>` | Fichiers disponibles sur le réseau |
| `GET` | `/api/peers?md5=<md5>` | Pairs possédant un fichier donné |
| `POST` | `/api/download` | Lance un téléchargement depuis un pair choisi |
| `GET` | `/api/downloads` | Liste et progression des téléchargements en cours |
| `GET` | `/api/shared` | Fichiers locaux dans `shared/` |
| `GET` | `/api/status` | État de la connexion tracker et configuration du pair |
| `POST` | `/api/tracker/connect` | Reconnecte le pair à un nouveau tracker |

L'interface web expose :
- La progression des téléchargements (pièces reçues / total, pourcentage)
- La liste des pairs connectés et leurs adresses
- L'état de la connexion au tracker
- Les fichiers locaux partagés avec leur MD5

---

## 10. Compilation et lancement

### Tracker

```bash
cd tracker
make build              # compile le binaire dans src/install/tracker
make test               # exécute les tests unitaires et d'intégration

# Lancement
./src/install/tracker           # lit config.ini (port 9000 par défaut)
./src/install/tracker 12345     # force le port 12345
```

### Peer

```bash
cd peer
mvn package -DskipTests         # compile et empaquète en JAR

# Lancement
java -jar target/eirbone-peer.jar                    # utilise config.ini
java -jar target/eirbone-peer.jar 2222               # force le port P2P
java -jar target/eirbone-peer.jar 2222 192.168.1.5   # tracker sur 192.168.1.5
java -jar target/eirbone-peer.jar 2222 192.168.1.5 9000
```

### Exemple de session complète

**Terminal 1 — Tracker :**
```bash
./src/install/tracker 9000
```

**Terminal 2 — Pair A (seeder) :**
```bash
# Copier file_a.dat dans peer/shared/
java -jar target/eirbone-peer.jar 2222 127.0.0.1 9000
```

**Terminal 3 — Pair B (leecher) :**
```bash
java -jar target/eirbone-peer.jar 3333 127.0.0.1 9000
> download file_a.dat
```

---

## 11. Tests

### Tracker (C)

Les tests se trouvent dans `tracker/test/` :

| Binaire | Couverture |
|---|---|
| `test_tracker_config` | Lecture et parsing de `config.ini` |
| `test_tracker_storage` | `find_or_create_peer`, `add_owner_to_file`, `purge_orphan_files` |
| `test_tracker_protocol` | Parsing des commandes `announce`, `look`, `getfile`, `update` |
| `test_tracker_utils` | `read_line`, `starts_with`, `trim_newline` |
| `test_integration` | Session TCP complète simulée |

```bash
cd tracker && make test
```

### Peer (Java)

Les tests se trouvent dans `peer/src/test/` :

| Classe | Couverture |
|---|---|
| `ConfigTest` | Lecture de `config.ini` |
| `BuffermapTest` | Encodage/décodage Base64 du buffermap |
| `PeerServerTest` | Handlers `interested`, `getpieces`, `have` |
| `PeerClientTest` | `fetchPiecesFrom`, `broadcastHave` |
| `FileManagerTest` | `readPiece`, `writePiece`, `computeMD5`, manifestes |
| `TrackerClientTest` | Parsing des réponses `list`, `peers` |
| `DownloadManagerParserTest` | Parsing des réponses du tracker |
| `PeerServerClientIT` | Test d'intégration serveur/client pair |
| `MultiPeerDownloadIT` | Test d'intégration téléchargement multi-pairs |

```bash
cd peer && mvn test
```
