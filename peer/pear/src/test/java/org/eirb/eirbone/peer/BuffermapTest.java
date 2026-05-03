package org.eirb.eirbone.peer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires du buffermap Eirbone.
 *
 * Convention de codage (cf. PeerServer.handleInterested) :
 *   octet j  = pièces j*8 … j*8+7
 *   bit (7 - i%8) de l'octet i/8 = pièce i
 *
 * Exemple pour 6 pièces (1 octet) :
 *   bit 7 = pièce 0 | bit 6 = pièce 1 | bit 5 = pièce 2
 *   bit 4 = pièce 3 | bit 3 = pièce 4 | bit 2 = pièce 5
 *   bits 1 et 0 = inutilisés
 *
 * Valeurs attendues :
 *   Peer1 (pièces 0,1,2) → 11100000 = 0xE0 → Base64 "4A=="
 *   Peer2 (pièces 3,4,5) → 00011100 = 0x1C → Base64 "HA=="
 *   Vide                  → 00000000 = 0x00 → Base64 "AA=="
 *   Complet (6 pièces)   → 11111100 = 0xFC → Base64 "/A=="
 *
 * Méthodes utilitaires intentionnellement publiques pour être réutilisées
 * dans MultiPeerDownloadIT sans duplication.
 */
class BuffermapTest {

    // -------------------------------------------------------------------------
    // Utilitaires publics (absents du code de production — à intégrer si besoin)
    // -------------------------------------------------------------------------

    /**
     * Encode une liste de pièces présentes en buffermap brut.
     * Algorithme identique à PeerServer.handleInterested.
     */
    public static byte[] encode(int totalPieces, int... presentPieces) {
        byte[] bm = new byte[(totalPieces + 7) / 8];
        for (int p : presentPieces) {
            bm[p / 8] |= (byte) (1 << (7 - (p % 8)));
        }
        return bm;
    }

    /**
     * Décode un buffermap Base64 et retourne la liste triée des indices présents.
     * ABSENT du code de production : PeerClient n'appelle jamais cette logique.
     * → BUG-1 : le buffermap reçu dans "have" n'est jamais utilisé.
     */
    public static List<Integer> decode(String base64Buffermap, int totalPieces) {
        byte[] bm = Base64.getDecoder().decode(base64Buffermap);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < totalPieces; i++) {
            if ((bm[i / 8] & (1 << (7 - (i % 8)))) != 0) {
                result.add(i);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 1. Encodage (côté serveur) — tests ces valeurs avec le code de PeerServer
    // -------------------------------------------------------------------------

    @Test
    void peer1_encodage_pieces_0_1_2_sur_6() {
        byte[] bm = encode(6, 0, 1, 2);
        assertEquals(1, bm.length, "6 pièces → 1 octet");
        // 1<<7 + 1<<6 + 1<<5 = 128 + 64 + 32 = 224 = 0xE0
        assertEquals((byte) 0xE0, bm[0],
            "bit7=pièce0 bit6=pièce1 bit5=pièce2 → 11100000 = 0xE0");
        assertEquals("4A==", Base64.getEncoder().encodeToString(bm),
            "0xE0 en Base64 doit donner '4A=='");
    }

    @Test
    void peer2_encodage_pieces_3_4_5_sur_6() {
        byte[] bm = encode(6, 3, 4, 5);
        assertEquals(1, bm.length);
        // 1<<4 + 1<<3 + 1<<2 = 16 + 8 + 4 = 28 = 0x1C
        assertEquals((byte) 0x1C, bm[0],
            "bit4=pièce3 bit3=pièce4 bit2=pièce5 → 00011100 = 0x1C");
        assertEquals("HA==", Base64.getEncoder().encodeToString(bm),
            "0x1C en Base64 doit donner 'HA=='");
    }

    @Test
    void buffermap_vide() {
        byte[] bm = encode(6);
        assertEquals((byte) 0x00, bm[0]);
        assertEquals("AA==", Base64.getEncoder().encodeToString(bm));
    }

    @Test
    void buffermap_complet_6_pieces() {
        byte[] bm = encode(6, 0, 1, 2, 3, 4, 5);
        // bits 7→2 mis à 1, bits 1→0 inutilisés → 11111100 = 0xFC
        assertEquals((byte) 0xFC, bm[0],
            "Buffermap complet 6 pièces = 0xFC (bits 1 et 0 inutilisés)");
        assertEquals("/A==", Base64.getEncoder().encodeToString(bm));
    }

    @Test
    void peerServer_algorithme_identique_a_encode() {
        // Reproduit exactement l'algorithme de PeerServer.handleInterested pour 6 pièces
        int nbPieces = 6;
        byte[] buffermap = new byte[(nbPieces + 7) / 8];
        for (int i = 0; i < nbPieces; i++) {
            buffermap[i / 8] |= (byte) (1 << (7 - (i % 8)));
        }
        assertArrayEquals(encode(6, 0, 1, 2, 3, 4, 5), buffermap,
            "L'algorithme de PeerServer et encode() doivent produire le même résultat");
        assertEquals("/A==", Base64.getEncoder().encodeToString(buffermap));
    }

    // -------------------------------------------------------------------------
    // 2. Décodage (côté client) — logique manquante dans PeerClient (BUG-1)
    // -------------------------------------------------------------------------

    @Test
    void decodage_buffermap_peer1_donne_0_1_2() {
        List<Integer> pieces = decode("4A==", 6);
        assertEquals(List.of(0, 1, 2), pieces,
            "Buffermap '4A==' (Peer1) doit décoder en pièces [0, 1, 2]");
    }

    @Test
    void decodage_buffermap_peer2_donne_3_4_5() {
        List<Integer> pieces = decode("HA==", 6);
        assertEquals(List.of(3, 4, 5), pieces,
            "Buffermap 'HA==' (Peer2) doit décoder en pièces [3, 4, 5]");
    }

    @Test
    void decodage_buffermap_vide() {
        assertTrue(decode("AA==", 6).isEmpty(),
            "Buffermap vide 'AA==' doit décoder en liste vide");
    }

    @Test
    void decodage_buffermap_complet() {
        assertEquals(List.of(0, 1, 2, 3, 4, 5), decode("/A==", 6));
    }

    // -------------------------------------------------------------------------
    // 3. Round-trip encodage → décodage
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_peer1() {
        String encoded = Base64.getEncoder().encodeToString(encode(6, 0, 1, 2));
        assertEquals(List.of(0, 1, 2), decode(encoded, 6));
    }

    @Test
    void roundTrip_peer2() {
        String encoded = Base64.getEncoder().encodeToString(encode(6, 3, 4, 5));
        assertEquals(List.of(3, 4, 5), decode(encoded, 6));
    }

    @Test
    void roundTrip_coherence_index_bit_pour_chaque_piece() {
        // Pour chaque pièce i ∈ [0,8[, encoder uniquement i puis décoder doit redonner exactement {i}
        for (int i = 0; i < 8; i++) {
            String encoded = Base64.getEncoder().encodeToString(encode(8, i));
            List<Integer> decoded = decode(encoded, 8);
            assertEquals(1, decoded.size(),
                "Exactement 1 pièce décodée pour l'index " + i);
            assertEquals(i, decoded.get(0),
                "L'index décodé doit être " + i + ", pas " + decoded.get(0));
        }
    }

    // -------------------------------------------------------------------------
    // 4. Propriétés de partition Peer1 ∪ Peer2
    // -------------------------------------------------------------------------

    @Test
    void peer1_ne_contient_pas_les_pieces_de_peer2() {
        List<Integer> peer1 = decode("4A==", 6);
        assertFalse(peer1.contains(3), "Peer1 ne doit pas avoir la pièce 3");
        assertFalse(peer1.contains(4), "Peer1 ne doit pas avoir la pièce 4");
        assertFalse(peer1.contains(5), "Peer1 ne doit pas avoir la pièce 5");
    }

    @Test
    void peer2_ne_contient_pas_les_pieces_de_peer1() {
        List<Integer> peer2 = decode("HA==", 6);
        assertFalse(peer2.contains(0), "Peer2 ne doit pas avoir la pièce 0");
        assertFalse(peer2.contains(1), "Peer2 ne doit pas avoir la pièce 1");
        assertFalse(peer2.contains(2), "Peer2 ne doit pas avoir la pièce 2");
    }

    @Test
    void aucun_overlap_entre_peer1_et_peer2() {
        List<Integer> intersection = new ArrayList<>(decode("4A==", 6));
        intersection.retainAll(decode("HA==", 6));
        assertTrue(intersection.isEmpty(),
            "Les buffermaps Peer1 et Peer2 ne doivent partager aucune pièce : " + intersection);
    }

    @Test
    void union_peer1_peer2_est_complete() {
        List<Integer> all = new ArrayList<>(decode("4A==", 6));
        all.addAll(decode("HA==", 6));
        assertEquals(6, all.size());
        for (int i = 0; i < 6; i++) {
            assertTrue(all.contains(i),
                "La pièce " + i + " doit être couverte par l'union des deux peers");
        }
    }

    // -------------------------------------------------------------------------
    // 5. Taille du buffermap selon le nombre de pièces
    // -------------------------------------------------------------------------

    @Test
    void taille_buffermap_1_piece() {
        assertEquals(1, encode(1, 0).length);
    }

    @Test
    void taille_buffermap_8_pieces() {
        assertEquals(1, encode(8, 0).length, "8 pièces tiennent dans 1 octet");
    }

    @Test
    void taille_buffermap_9_pieces() {
        assertEquals(2, encode(9, 0).length, "9 pièces nécessitent 2 octets");
    }

    @Test
    void taille_buffermap_16_pieces() {
        assertEquals(2, encode(16, 0).length);
    }

    @Test
    void taille_buffermap_17_pieces() {
        assertEquals(3, encode(17, 0).length);
    }
}
