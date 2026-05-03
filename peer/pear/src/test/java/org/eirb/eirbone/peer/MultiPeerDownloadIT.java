package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'intégration multi-peers.
 *
 * Scénario :
 *   Fichier test : 6 pièces × 1024 octets, contenu déterministe
 *                  pièce 0 = 0x00×1024 | pièce 1 = 0x11×1024 | ... | pièce 5 = 0x55×1024
 *   Peer1  : possède uniquement les pièces 0, 1, 2  → buffermap "4A==" (0xE0)
 *   Peer2  : possède uniquement les pièces 3, 4, 5  → buffermap "HA==" (0x1C)
 *   Peer3  : downloader — ne possède rien au départ → buffermap "AA==" (0x00)
 *
 * Organisation des tests (ordre d'exécution) :
 *   Groupe 1 [ordre 1-4]  : Propriétés des buffermaps  → tous PASSENT
 *   Groupe 2 [ordre 5-6]  : Échange protocole direct   → tous PASSENT
 *   Groupe 3 [ordre 7]    : Multi-peers manuel         → PASSE (montre que le protocole fonctionne)
 *   Groupe 4 [ordre 8]    : BUG-1 révélé               → ÉCHOUE (PeerClient ignore le buffermap)
 *   Groupe 5 [ordre 9]    : BUG-2 documenté            → @Disabled (méthode inexistante)
 *
 * Bugs révélés :
 *   BUG-1 : PeerClient.downloadFrom() — ligne ~50 — ignore "have <md5> <buffermap>"
 *           → demande toutes les pièces [0..N-1] même si le peer n'en a qu'une partie
 *   BUG-2 : DownloadManager / PeerClient — aucun support du téléchargement multi-peers
 *   BUG-3 : PeerServer.handleGetPieces() — ligne ~108 — toujours "1024" dans le header data
 *           y compris pour la dernière pièce (padding non annoncé)
 *   BUG-4 : PeerClient.downloadFrom() — ligne ~64 — lit toujours 1024 octets par pièce,
 *           n'utilise pas les longueurs annoncées dans l'en-tête "data"
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiPeerDownloadIT {

    static final int NB_PIECES  = 6;
    static final int PIECE_SIZE = 1024;
    static final int TOTAL_SIZE = NB_PIECES * PIECE_SIZE; // 6144 octets

    // Répertoire de travail de Peer3 (downloader) — isolé par test grâce à @TempDir instance
    @TempDir Path peer3Dir;

    // Fichier de référence et son MD5 — créés une seule fois pour toute la classe
    static byte[] fullFileContent;
    static String fileMd5;

    @BeforeAll
    static void creerFichierTest() throws Exception {
        // Contenu déterministe : pièce i = octet (0x11 * i) répété 1024 fois
        fullFileContent = new byte[TOTAL_SIZE];
        for (int i = 0; i < NB_PIECES; i++) {
            Arrays.fill(fullFileContent, i * PIECE_SIZE, (i + 1) * PIECE_SIZE, (byte) (0x11 * i));
        }
        // piece 0 : 0x00 × 1024
        // piece 1 : 0x11 × 1024
        // piece 2 : 0x22 × 1024
        // piece 3 : 0x33 × 1024
        // piece 4 : 0x44 × 1024
        // piece 5 : 0x55 × 1024

        MessageDigest md5Digest = MessageDigest.getInstance("MD5");
        md5Digest.update(fullFileContent);
        StringBuilder sb = new StringBuilder();
        for (byte b : md5Digest.digest()) sb.append(String.format("%02x", b));
        fileMd5 = sb.toString();

        System.out.println("[TEST-SETUP] ─────────────────────────────────────────");
        System.out.println("[TEST-SETUP] Fichier de test : " + TOTAL_SIZE + " octets ("
            + NB_PIECES + " pièces × " + PIECE_SIZE + " octets)");
        System.out.println("[TEST-SETUP] MD5 original   : " + fileMd5);
        System.out.println("[TEST-SETUP] Buffermap Peer1 (pièces 0,1,2) : 4A== (0xE0)");
        System.out.println("[TEST-SETUP] Buffermap Peer2 (pièces 3,4,5) : HA== (0x1C)");
        System.out.println("[TEST-SETUP] ─────────────────────────────────────────");
    }

    static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) { return ss.getLocalPort(); }
    }

    // =========================================================================
    // Fake peer server — sert uniquement les pièces dans [fromPiece, toPiece[
    //
    // Comportement :
    //   1. Accepte "interested <md5>" → répond "have <md5> <buffermap_partiel>"
    //   2. Accepte "getpieces <md5> [...]" → répond avec data header + données binaires
    //      NB : sert uniquement ses propres pièces ; si le client demande une pièce
    //           hors de sa plage, envoie des zéros et logue l'erreur.
    //   3. Stocke la requête getpieces dans requestedPieces pour les assertions.
    // =========================================================================

    int startFakePartialPeer(int fromPiece, int toPiece,
                              List<String> requestedPieces) throws Exception {
        int[] myPieces = new int[toPiece - fromPiece];
        for (int i = fromPiece; i < toPiece; i++) myPieces[i - fromPiece] = i;
        byte[] bm      = BuffermapTest.encode(NB_PIECES, myPieces);
        String bmB64   = Base64.getEncoder().encodeToString(bm);
        String peerTag = "[PEER" + fromPiece + "-" + (toPiece - 1) + "]";

        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        CountDownLatch ready = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            ready.countDown();
            try (Socket sock = ss.accept()) {
                BufferedReader reader  = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                PrintWriter    writer  = new PrintWriter(sock.getOutputStream(), true);
                OutputStream   rawOut  = sock.getOutputStream();

                // --- 1. interested ---
                String interested = reader.readLine();
                System.out.println(peerTag + " << " + interested);
                assertTrue(interested != null && interested.startsWith("interested "),
                    "Message attendu : 'interested <md5>', reçu : '" + interested + "'");
                assertEquals(fileMd5, interested.substring("interested ".length()).trim(),
                    "Le MD5 dans 'interested' doit correspondre au fichier de test");

                // --- 2. have ---
                String haveMsg = "have " + fileMd5 + " " + bmB64;
                writer.println(haveMsg);
                System.out.println(peerTag + " >> " + haveMsg);

                // --- 3. getpieces ---
                String gp = reader.readLine();
                System.out.println(peerTag + " << " + gp);
                if (gp == null) { System.err.println(peerTag + " ERREUR : getpieces null"); return; }
                requestedPieces.add(gp);

                int lbr = gp.indexOf('['), rbr = gp.indexOf(']');
                if (lbr < 0 || rbr < 0) { System.err.println(peerTag + " Format getpieces invalide"); return; }
                String[] askedIndices = gp.substring(lbr + 1, rbr).trim().split("\\s+");

                // --- 4. data header ---
                StringBuilder header = new StringBuilder("data " + fileMd5 + " [");
                for (int k = 0; k < askedIndices.length; k++) {
                    if (k > 0) header.append(" ");
                    header.append(askedIndices[k]).append(" " + PIECE_SIZE);
                }
                header.append("]");
                writer.println(header);
                System.out.println(peerTag + " >> " + header);
                Thread.sleep(20); // garantit que l'en-tête arrive avant les données binaires

                // --- 5. données binaires pièce par pièce ---
                for (String idxStr : askedIndices) {
                    int idx = Integer.parseInt(idxStr.trim());
                    byte[] data;
                    if (idx >= fromPiece && idx < toPiece) {
                        data = Arrays.copyOfRange(
                            fullFileContent, idx * PIECE_SIZE, (idx + 1) * PIECE_SIZE);
                    } else {
                        // Le client a demandé une pièce que ce peer ne possède pas — BUG-1
                        data = new byte[PIECE_SIZE];
                        System.err.println(peerTag + " ERREUR BUG-1 : pièce " + idx
                            + " demandée mais non disponible (plage [" + fromPiece + "," + toPiece + "[)");
                    }
                    rawOut.write(data);
                    System.out.println(peerTag + " >> pièce " + idx + " envoyée ("
                        + data.length + " octets)");
                }
                rawOut.flush();

            } catch (AssertionError | Exception e) {
                System.err.println(peerTag + " EXCEPTION : " + e.getMessage());
            } finally {
                try { ss.close(); } catch (IOException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
        ready.await();
        return port;
    }

    // =========================================================================
    // Groupe 1 — Propriétés des buffermaps (tests purement logiques)
    // =========================================================================

    @Test @Order(1)
    void buffermap_peer1_contient_exactement_pieces_0_1_2() {
        List<Integer> pieces = BuffermapTest.decode("4A==", NB_PIECES);
        assertEquals(List.of(0, 1, 2), pieces,
            "Buffermap '4A==' doit décoder en [0, 1, 2]");
    }

    @Test @Order(2)
    void buffermap_peer2_contient_exactement_pieces_3_4_5() {
        List<Integer> pieces = BuffermapTest.decode("HA==", NB_PIECES);
        assertEquals(List.of(3, 4, 5), pieces,
            "Buffermap 'HA==' doit décoder en [3, 4, 5]");
    }

    @Test @Order(3)
    void aucun_overlap_entre_peer1_et_peer2() {
        Set<Integer> p1 = new HashSet<>(BuffermapTest.decode("4A==", NB_PIECES));
        Set<Integer> p2 = new HashSet<>(BuffermapTest.decode("HA==", NB_PIECES));
        Set<Integer> intersection = new HashSet<>(p1);
        intersection.retainAll(p2);
        assertTrue(intersection.isEmpty(),
            "Les buffermaps ne doivent partager aucune pièce : " + intersection);
    }

    @Test @Order(4)
    void union_peer1_peer2_couvre_toutes_les_pieces() {
        Set<Integer> all = new HashSet<>(BuffermapTest.decode("4A==", NB_PIECES));
        all.addAll(BuffermapTest.decode("HA==", NB_PIECES));
        assertEquals(Set.of(0, 1, 2, 3, 4, 5), all,
            "L'union des deux buffermaps doit couvrir les 6 pièces");
    }

    // =========================================================================
    // Groupe 2 — Échange protocole direct (sockets bruts, sans PeerClient)
    //            Vérifie que les messages réseau sont corrects
    // =========================================================================

    @Test @Order(5)
    void echangeProtocole_avecPeer1_interested_have_getpieces_data() throws Exception {
        List<String> received = new ArrayList<>();
        int peer1Port = startFakePartialPeer(0, 3, received);

        try (Socket sock = new Socket("127.0.0.1", peer1Port)) {
            PrintWriter    out    = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader in     = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            InputStream    rawIn  = sock.getInputStream();

            // ── Étape 1 : "interested <md5>" ─────────────────────────────────
            out.println("interested " + fileMd5);
            String haveResp = in.readLine();
            System.out.println("[TEST-P5] << " + haveResp);

            assertTrue(haveResp.startsWith("have " + fileMd5 + " "),
                "La réponse doit commencer par 'have <md5> '");
            String bmReceived = haveResp.split(" ")[2];
            assertEquals("4A==", bmReceived,
                "Peer1 doit retourner le buffermap '4A==' (pièces 0,1,2)");

            List<Integer> piecesDispos = BuffermapTest.decode(bmReceived, NB_PIECES);
            assertEquals(List.of(0, 1, 2), piecesDispos,
                "Le buffermap décodé doit lister les pièces 0, 1, 2");

            // ── Étape 2 : "getpieces <md5> [0 1 2]" ──────────────────────────
            out.println("getpieces " + fileMd5 + " [0 1 2]");
            String dataHeader = in.readLine();
            System.out.println("[TEST-P5] << " + dataHeader);

            assertTrue(dataHeader.startsWith("data " + fileMd5 + " ["),
                "L'en-tête data doit commencer par 'data <md5> ['");
            assertTrue(dataHeader.contains("0 1024"), "Doit annoncer la pièce 0 (1024 octets)");
            assertTrue(dataHeader.contains("1 1024"), "Doit annoncer la pièce 1 (1024 octets)");
            assertTrue(dataHeader.contains("2 1024"), "Doit annoncer la pièce 2 (1024 octets)");
            assertFalse(dataHeader.contains("3 "),    "Ne doit pas annoncer la pièce 3");

            // ── Étape 3 : données binaires 3 × 1024 octets ────────────────────
            for (int i = 0; i < 3; i++) {
                byte[] buf   = new byte[PIECE_SIZE];
                int    totalRead = 0;
                while (totalRead < PIECE_SIZE) {
                    int n = rawIn.read(buf, totalRead, PIECE_SIZE - totalRead);
                    if (n < 0) fail("Connexion fermée avant la fin de la pièce " + i);
                    totalRead += n;
                }
                byte[] expected = Arrays.copyOfRange(
                    fullFileContent, i * PIECE_SIZE, (i + 1) * PIECE_SIZE);
                assertArrayEquals(expected, buf,
                    "Contenu de la pièce " + i + " incorrect");
                System.out.println("[TEST-P5] Pièce " + i + " reçue et vérifiée OK");
            }
        }
    }

    @Test @Order(6)
    void echangeProtocole_avecPeer2_interested_have_getpieces_data() throws Exception {
        List<String> received = new ArrayList<>();
        int peer2Port = startFakePartialPeer(3, 6, received);

        try (Socket sock = new Socket("127.0.0.1", peer2Port)) {
            PrintWriter    out   = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader in    = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            InputStream    rawIn = sock.getInputStream();

            out.println("interested " + fileMd5);
            String haveResp = in.readLine();
            System.out.println("[TEST-P6] << " + haveResp);

            String bmReceived = haveResp.split(" ")[2];
            assertEquals("HA==", bmReceived,
                "Peer2 doit retourner le buffermap 'HA==' (pièces 3,4,5)");
            assertEquals(List.of(3, 4, 5), BuffermapTest.decode(bmReceived, NB_PIECES));

            out.println("getpieces " + fileMd5 + " [3 4 5]");
            String dataHeader = in.readLine();
            System.out.println("[TEST-P6] << " + dataHeader);

            assertTrue(dataHeader.contains("3 1024") && dataHeader.contains("4 1024")
                && dataHeader.contains("5 1024"),
                "L'en-tête doit annoncer les pièces 3, 4, 5");
            assertFalse(dataHeader.contains("0 ") || dataHeader.contains("1 ")
                || dataHeader.contains("2 "),
                "L'en-tête ne doit pas contenir les pièces 0, 1, 2");

            for (int i = 3; i < 6; i++) {
                byte[] buf = new byte[PIECE_SIZE];
                int totalRead = 0;
                while (totalRead < PIECE_SIZE) {
                    int n = rawIn.read(buf, totalRead, PIECE_SIZE - totalRead);
                    if (n < 0) fail("EOF inattendu à la pièce " + i);
                    totalRead += n;
                }
                assertArrayEquals(
                    Arrays.copyOfRange(fullFileContent, i * PIECE_SIZE, (i + 1) * PIECE_SIZE),
                    buf, "Contenu de la pièce " + i + " incorrect");
                System.out.println("[TEST-P6] Pièce " + i + " reçue et vérifiée OK");
            }
        }
    }

    // =========================================================================
    // Groupe 3 — Téléchargement multi-peers manuel (coordination dans le test)
    //
    //   Ce test réalise le téléchargement complet depuis deux peers partiels
    //   en utilisant directement les sockets (sans PeerClient).
    //   Il prouve que LE PROTOCOLE FONCTIONNE — c'est PeerClient qui est défaillant.
    // =========================================================================

    @Test @Order(7)
    void multiPeerManuel_reassemblage_complet_md5_identique() throws Exception {
        List<String> req1 = new ArrayList<>(), req2 = new ArrayList<>();
        int peer1Port = startFakePartialPeer(0, 3, req1);
        int peer2Port = startFakePartialPeer(3, 6, req2);

        FileManager fm = new FileManager(peer3Dir.toString());

        // ── Téléchargement depuis Peer1 : pièces 0, 1, 2 ────────────────────
        System.out.println("[PEER3] Connexion à Peer1 (port " + peer1Port + ")");
        try (Socket sock = new Socket("127.0.0.1", peer1Port)) {
            PrintWriter    w   = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader r   = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            InputStream    raw = sock.getInputStream();

            w.println("interested " + fileMd5);
            String have = r.readLine();
            System.out.println("[PEER3] << " + have);

            List<Integer> peer1Pieces = BuffermapTest.decode(have.split(" ")[2], NB_PIECES);
            assertEquals(List.of(0, 1, 2), peer1Pieces,
                "Peer1 doit annoncer les pièces 0, 1, 2");

            // Demande uniquement les pièces annoncées par le buffermap
            w.println("getpieces " + fileMd5 + " [0 1 2]");
            System.out.println("[PEER3] >> getpieces " + fileMd5 + " [0 1 2]");
            r.readLine(); // en-tête data

            for (int idx : peer1Pieces) {
                byte[] buf = new byte[PIECE_SIZE];
                int totalRead = 0;
                while (totalRead < PIECE_SIZE) {
                    int n = raw.read(buf, totalRead, PIECE_SIZE - totalRead);
                    if (n < 0) fail("EOF inattendu lors de la pièce " + idx + " depuis Peer1");
                    totalRead += n;
                }
                fm.writePiece(fileMd5, idx, buf); // fichier temp nommé par le md5
                System.out.println("[PEER3] Pièce " + idx + " écrite (depuis Peer1)");
            }
        }

        // ── Téléchargement depuis Peer2 : pièces 3, 4, 5 ────────────────────
        System.out.println("[PEER3] Connexion à Peer2 (port " + peer2Port + ")");
        try (Socket sock = new Socket("127.0.0.1", peer2Port)) {
            PrintWriter    w   = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader r   = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            InputStream    raw = sock.getInputStream();

            w.println("interested " + fileMd5);
            String have = r.readLine();
            System.out.println("[PEER3] << " + have);

            List<Integer> peer2Pieces = BuffermapTest.decode(have.split(" ")[2], NB_PIECES);
            assertEquals(List.of(3, 4, 5), peer2Pieces,
                "Peer2 doit annoncer les pièces 3, 4, 5");

            w.println("getpieces " + fileMd5 + " [3 4 5]");
            System.out.println("[PEER3] >> getpieces " + fileMd5 + " [3 4 5]");
            r.readLine(); // en-tête data

            for (int idx : peer2Pieces) {
                byte[] buf = new byte[PIECE_SIZE];
                int totalRead = 0;
                while (totalRead < PIECE_SIZE) {
                    int n = raw.read(buf, totalRead, PIECE_SIZE - totalRead);
                    if (n < 0) fail("EOF inattendu lors de la pièce " + idx + " depuis Peer2");
                    totalRead += n;
                }
                fm.writePiece(fileMd5, idx, buf);
                System.out.println("[PEER3] Pièce " + idx + " écrite (depuis Peer2)");
            }
        }

        // ── Finalisation ─────────────────────────────────────────────────────
        fm.truncate(fileMd5, TOTAL_SIZE);                           // retire le padding si besoin
        fm.renameAfterDownload(fileMd5, "test_multiPeer.bin");      // renomme le fichier temp

        Path downloaded = peer3Dir.resolve("test_multiPeer.bin");

        // ── Assertions ───────────────────────────────────────────────────────
        assertTrue(Files.exists(downloaded), "Le fichier final doit exister");

        assertEquals(TOTAL_SIZE, Files.size(downloaded),
            "Taille attendue : " + TOTAL_SIZE + " octets");

        assertArrayEquals(fullFileContent, Files.readAllBytes(downloaded),
            "Le contenu octet-à-octet doit être identique à l'original");

        String downloadedMd5 = FileManager.computeMD5(downloaded.toFile());
        assertEquals(fileMd5, downloadedMd5,
            "MD5 final attendu : " + fileMd5 + " | obtenu : " + downloadedMd5);

        System.out.println("[PEER3] ─────────────────────────────────────────────");
        System.out.println("[PEER3] Fichier reconstruit : " + downloaded);
        System.out.println("[PEER3] Taille              : " + Files.size(downloaded) + " octets");
        System.out.println("[PEER3] MD5 vérifié         : " + downloadedMd5 + " ✓");
        System.out.println("[PEER3] Peer1 a reçu        : " + req1.get(0));
        System.out.println("[PEER3] Peer2 a reçu        : " + req2.get(0));

        // Vérification qu'aucune pièce n'a été demandée deux fois (aucun overlap dans les requêtes)
        int[] peer1Requested = extractIndices(req1.get(0));
        int[] peer2Requested = extractIndices(req2.get(0));
        for (int p1 : peer1Requested) {
            for (int p2 : peer2Requested) {
                assertNotEquals(p1, p2,
                    "La pièce " + p1 + " a été demandée aux deux peers — overlap détecté !");
            }
        }

        assertEquals(NB_PIECES, peer1Requested.length + peer2Requested.length,
            "Le nombre total de pièces demandées doit être " + NB_PIECES);
    }

    // =========================================================================
    // Groupe 4 — BUG-1 : PeerClient ignore le buffermap
    //
    //   Ce test ÉCHOUE avec le code actuel.
    //   Message d'erreur attendu : "BUG-1 CONFIRMÉ : PeerClient a envoyé
    //     'getpieces <md5> [0 1 2 3 4 5]' au lieu de 'getpieces <md5> [0 1 2]'"
    // =========================================================================

    @Test @Order(8)
    void bug1_peerClient_ignore_le_buffermap_et_demande_toutes_les_pieces() throws Exception {
        AtomicReference<String> getpiecesRequest = new AtomicReference<>();

        // Peer1 partiel : possède uniquement les pièces 0, 1, 2
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try (Socket sock = ss.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                PrintWriter    writer = new PrintWriter(sock.getOutputStream(), true);
                OutputStream   rawOut = sock.getOutputStream();

                reader.readLine(); // "interested <md5>"

                // Buffermap : seulement pièces 0, 1, 2
                byte[] bm = BuffermapTest.encode(NB_PIECES, 0, 1, 2);
                writer.println("have " + fileMd5 + " " + Base64.getEncoder().encodeToString(bm));

                String gp = reader.readLine();
                getpiecesRequest.set(gp);
                System.out.println("[BUG-1] PeerClient a envoyé : " + gp);

                // Sert toutes les pièces demandées (les pièces hors plage reçoivent des zéros)
                int lbr = gp.indexOf('['), rbr = gp.indexOf(']');
                String[] indices = gp.substring(lbr + 1, rbr).trim().split("\\s+");

                StringBuilder header = new StringBuilder("data " + fileMd5 + " [");
                for (int k = 0; k < indices.length; k++) {
                    if (k > 0) header.append(" ");
                    header.append(indices[k]).append(" " + PIECE_SIZE);
                }
                header.append("]");
                writer.println(header);
                Thread.sleep(20);

                for (String idx : indices) {
                    int p = Integer.parseInt(idx.trim());
                    byte[] data = (p < 3)
                        ? Arrays.copyOfRange(fullFileContent, p * PIECE_SIZE, (p + 1) * PIECE_SIZE)
                        : new byte[PIECE_SIZE]; // pièce non disponible → zéros
                    if (p >= 3) {
                        System.err.println("[BUG-1] ⚠ Pièce " + p
                            + " demandée mais non disponible chez Peer1 !");
                    }
                    rawOut.write(data);
                }
                rawOut.flush();
            } catch (Exception e) {
                System.err.println("[BUG-1 SERVER] " + e.getMessage());
            } finally {
                try { ss.close(); } catch (IOException ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(100);

        FileManager fm = new FileManager(peer3Dir.toString());
        PeerClient client = new PeerClient(new Config("nonexistent.ini"), fm);

        // PeerClient contacte un peer qui n'a que les pièces 0,1,2
        // Il devrait n'envoyer que "getpieces [0 1 2]" après avoir lu le buffermap
        try {
            client.downloadFrom("127.0.0.1", port, "bug1test.bin", fileMd5, TOTAL_SIZE);
        } catch (Exception e) {
            System.out.println("[BUG-1] Exception lors du download : " + e.getMessage());
        }

        String gp = getpiecesRequest.get();
        assertNotNull(gp, "La requête getpieces n'a pas été reçue par le serveur");

        System.out.println("[BUG-1] ─────────────────────────────────────────────");
        System.out.println("[BUG-1] Requête reçue     : " + gp);
        System.out.println("[BUG-1] Requête attendue  : getpieces " + fileMd5 + " [0 1 2]");

        // ASSERTION QUI ÉCHOUE AVEC LE CODE ACTUEL :
        // PeerClient doit n'envoyer que [0 1 2] car c'est ce que le buffermap "4A==" annonce.
        // Avec le code actuel, il envoie [0 1 2 3 4 5] — ce qui prouve que le buffermap est ignoré.
        boolean demandeCorrectement = gp.contains("[0 1 2]")
            && !gp.contains(" 3") && !gp.contains(" 4") && !gp.contains(" 5");

        assertTrue(demandeCorrectement,
            "BUG-1 CONFIRMÉ ─ PeerClient a envoyé :\n  '" + gp + "'\n"
            + "Attendu :\n  'getpieces " + fileMd5 + " [0 1 2]'\n"
            + "Cause : PeerClient.downloadFrom() (ligne ~46-55) lit la réponse 'have' "
            + "mais ne décode JAMAIS le buffermap Base64.\n"
            + "Fix : décoder le buffermap reçu et n'inclure dans getpieces que les "
            + "indices dont le bit est à 1.");
    }

    // =========================================================================
    // Groupe 5 — BUG-2 documenté : pas de support multi-peers dans PeerClient
    //
    //   @Disabled car la méthode downloadFromMultiple() n'existe pas encore.
    //   Ce test sert de spécification pour l'implémentation future.
    // =========================================================================

    @Test @Order(9)
    @Disabled("BUG-2 : PeerClient ne supporte pas le téléchargement multi-peers. "
            + "Implémenter PeerClient.downloadFromMultiple(List<PeerAddress>, String, String, long)")
    void bug2_peerClient_ne_supporte_pas_le_multi_peers() throws Exception {
        List<String> req1 = new ArrayList<>(), req2 = new ArrayList<>();
        int peer1Port = startFakePartialPeer(0, 3, req1);
        int peer2Port = startFakePartialPeer(3, 6, req2);

        FileManager fm = new FileManager(peer3Dir.toString());
        PeerClient client = new PeerClient(new Config("nonexistent.ini"), fm);

        // ⚠ Cette méthode doit être créée dans PeerClient :
        //
        // client.downloadFromMultiple(
        //     List.of(new PeerClient.PeerAddress("127.0.0.1", peer1Port),
        //             new PeerClient.PeerAddress("127.0.0.1", peer2Port)),
        //     "test_multi.bin", fileMd5, (long) TOTAL_SIZE
        // );
        //
        // Comportement attendu de downloadFromMultiple() :
        //   1. Pour chaque peer : envoie "interested <md5>", reçoit "have <md5> <buffermap>"
        //   2. Décode chaque buffermap → liste des pièces disponibles par peer
        //   3. Distribue les pièces sans overlap (Set<Integer> already_assigned)
        //   4. Pour chaque peer : envoie "getpieces <md5> [pièces_assignées]", lit les données
        //   5. Chaque pièce est écrite au bon offset : pieceIndex * PIECE_SIZE
        //   6. Truncate + rename après réception de toutes les pièces

        // Assertions sur les requêtes envoyées :
        assertEquals("getpieces " + fileMd5 + " [0 1 2]", req1.get(0),
            "Peer1 doit recevoir uniquement ses pièces");
        assertEquals("getpieces " + fileMd5 + " [3 4 5]", req2.get(0),
            "Peer2 doit recevoir uniquement ses pièces");

        // Assertions sur le fichier reconstruit :
        Path downloaded = peer3Dir.resolve("test_multi.bin");
        assertEquals((long) TOTAL_SIZE, Files.size(downloaded));
        assertArrayEquals(fullFileContent, Files.readAllBytes(downloaded));
        assertEquals(fileMd5, FileManager.computeMD5(downloaded.toFile()));
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    /** Extrait les indices de pièces depuis "getpieces <md5> [0 1 2]" → {0, 1, 2} */
    private int[] extractIndices(String getpiecesLine) {
        int lbr = getpiecesLine.indexOf('['), rbr = getpiecesLine.indexOf(']');
        String[] parts = getpiecesLine.substring(lbr + 1, rbr).trim().split("\\s+");
        int[] indices = new int[parts.length];
        for (int i = 0; i < parts.length; i++) indices[i] = Integer.parseInt(parts[i].trim());
        return indices;
    }
}
