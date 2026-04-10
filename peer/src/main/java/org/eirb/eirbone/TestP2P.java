package org.eirb.eirbone;

import java.util.ArrayList;
import java.util.List;

import org.eirb.eirbone.peer.PeerClient;
import org.eirb.eirbone.server.TCPServer;
import org.eirb.eirbone.tracker.TrackerClient;
import org.eirb.eirbone.tracker.TrackerClient.FileInfo;
import org.eirb.eirbone.utils.FileInfos;

public class TestP2P {

  public static void main(String[] args) throws Exception {

    System.out.println("=== Test P2P ===");
    
    try {
      // creer un fichier de test
      String fileKey = "test_p2p_key";
      FileInfos file = new FileInfos("test_p2p.dat", 1024, 256, fileKey);

      // demarrer le seeder sur le port 3000
      System.out.println("\n1. Démarrage du serveur peer sur port 3000...");
      TCPServer server = new TCPServer(3000);
      server.addFile(file);
      server.start();
      Thread.sleep(500);

      // connexion au tracker et annonce
      System.out.println("2. Connexion au tracker et annonce...");
      TrackerClient tracker = new TrackerClient("127.0.0.1", 9000);
      tracker.connect();
      
      // créer un FileInfos pour le tracker
      ArrayList<FileInfos> trackerFiles = new ArrayList<>();
      FileInfos tfInfo = new FileInfos("test_p2p.dat", 1024 * 256, 1024, fileKey);
      trackerFiles.add(tfInfo);
      tracker.announce(3000, trackerFiles, new ArrayList<>());
      System.out.println("   Annonce OK");

      // recherche du fichier auprès du tracker
      System.out.println("3. Recherche du fichier auprès du tracker...");
      // List<FileInfo> found = tracker.look("test_p2p.dat");
      // System.out.println("   Trouvé : " + found);

      // récupération de la liste des peers
      System.out.println("4. Récupération des sources...");
      List<String> peers = tracker.getFile(fileKey);
      System.out.println("   Peers : " + peers);

      // déconnexion du tracker
      tracker.disconnect();

      // connexion P2P à un peer
      if (!peers.isEmpty()) {
        String[] peerAddr = peers.get(0).split(":");
        String peerHost = peerAddr[0];
        int peerPort = Integer.parseInt(peerAddr[1]);
        System.out.println("\n5. Connexion P2P à " + peerHost + ":" + peerPort + "...");
        PeerClient client = new PeerClient(peerHost, peerPort);
        client.connect();

        // envoi de la commande interested
        System.out.println("6. Envoi de 'interested " + fileKey + "'...");
        String have = client.interested(fileKey);
        System.out.println("   Réponse : " + have);

        // demande des pièces
        System.out.println("7. Demande des pièces 0, 1...");
        List<Integer> indices = new ArrayList<>();
        indices.add(0);
        indices.add(1);
        byte[] payload = client.getpieces(fileKey, indices);
        if (payload != null) {
          System.out.println("   Payload reçu : " + payload.length + " octets");
        }
        client.disconnect();
      }

      // arrêt du serveur
      server.stop();
      System.out.println("\n8. Test P2P terminé OK");

    } catch (Exception e) {
      System.err.println("TEST P2P FAILED : " + e.getMessage());
      e.printStackTrace();
    }
  }
}
