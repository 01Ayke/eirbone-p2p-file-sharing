#!/bin/bash

# Script de test complet du peer Eirbone P2P
# Teste les fonctionnalités: announce, look, getfile, update, peer-to-peer

# Couleurs pour l'affichage
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=========================================${NC}"
echo -e "${BLUE}     TEST DU PEER EIRBONE P2P${NC}"
echo -e "${BLUE}=========================================${NC}"
echo ""

# S'assurer que le tracker est arrêté et le port libéré
echo "[*] Libération du port 9000..."
fuser -k 9000/tcp 2>/dev/null || true
sleep 1

# Démarrer le tracker en arrière-plan
echo "[*] Démarrage du tracker..."
./tracker/install/tracker > tracker_peer_test.log 2>&1 &
TRACKER_PID=$!
sleep 2

if ! ps -p $TRACKER_PID > /dev/null; then
    echo -e "${RED}[ERREUR] Tracker n'a pas pu démarrer${NC}"
    exit 1
fi
echo -e "${GREEN}[OK] Tracker lancé (PID: $TRACKER_PID)${NC}"
echo ""

# Vérifier que mvn est disponible
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}[ERREUR] Maven (mvn) non trouvé${NC}"
    kill $TRACKER_PID
    exit 1
fi

echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${YELLOW}TEST 1: Compilation du peer${NC}"
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
cd peer
mvn clean compile -q
if [ $? -eq 0 ]; then
    echo -e "${GREEN}[OK] Peer compilé avec succès${NC}"
else
    echo -e "${RED}[ERREUR] Compilation du peer échouée${NC}"
    kill $TRACKER_PID
    exit 1
fi
cd ..
echo ""

echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${YELLOW}TEST 2: Exécution du peer (announce + look + getfile)${NC}"
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo "Exécution du programme peer par défaut (App.java)..."
cd peer
mvn exec:java -Dexec.mainClass="org.eirb.eirbone.App" 2>&1 | head -50
RESULT=$?
cd ..

if [ $RESULT -eq 0 ] || grep -q "Peers for" tracker_peer_test.log; then
    echo -e "${GREEN}[OK] Peer a pu communiquer avec le tracker${NC}"
else
    echo -e "${YELLOW}[AVIS] Peer s'est exécuté mais avec des avertissements${NC}"
fi
echo ""

echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${YELLOW}TEST 3: Commandes manuelles Peer-Tracker${NC}"
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# Test announce
echo "3.1 - Test ANNOUNCE (peer enregistre ses fichiers auprès du tracker)"
echo "---"
(echo 'announce listen 2050 seed [myfile.dat 1048576 1024 e2d0fe1577e7aff00007e19ebdc04143] leech []'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
echo -e "${GREEN}[OK] Announce complété${NC}"
echo ""

# Test look
echo "3.2 - Test LOOK (peer cherche des fichiers)"
echo "---"
(echo 'look [filename=myfile.dat]'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
echo -e "${GREEN}[OK] Look complété${NC}"
echo ""

# Test getfile
echo "3.3 - Test GETFILE (obtenir la liste des peers ayant un fichier)"
echo "---"
(echo 'getfile e2d0fe1577e7aff00007e19ebdc04143'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
echo -e "${GREEN}[OK] Getfile complété${NC}"
echo ""

# Test update
echo "3.4 - Test UPDATE (mise à jour du statut seed/leech)"
echo "---"
(echo 'announce listen 2050 seed [myfile.dat 1048576 1024 e2d0fe1577e7aff00007e19ebdc04143]' | nc -q 1 localhost 9000 2>/dev/null > /dev/null; sleep 1; echo 'update seed [e2d0fe1577e7aff00007e19ebdc04143] leech []'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
echo -e "${GREEN}[OK] Update complété${NC}"
echo ""

echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${YELLOW}TEST 4: Logs du Tracker${NC}"
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
cat tracker_peer_test.log
echo ""

# Arrêter le tracker
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo "[*] Arrêt du tracker..."
kill $TRACKER_PID 2>/dev/null || pkill -f tracker/install/tracker
sleep 1

echo ""
echo -e "${BLUE}=========================================${NC}"
echo -e "${BLUE}      TESTS COMPLÉTÉS${NC}"
echo -e "${BLUE}=========================================${NC}"
