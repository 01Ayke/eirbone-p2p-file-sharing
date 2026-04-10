#!/bin/bash

# Couleurs pour l'affichage
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo "========================================="
echo "      TEST DU TRACKER EIRBONE P2P"
echo "========================================="
echo ""

# Libérer le port 9000 si occupé
fuser -k 9000/tcp 2>/dev/null || true
sleep 1

# Lancer le tracker en arrière-plan
echo "[*] Démarrage du tracker..."
./tracker/install/tracker > >(tee tracker_test.log) 2>&1 &
TRACKER_PID=$!
sleep 3

# Vérifier que le tracker est bien lancé
if ! ps -p $TRACKER_PID > /dev/null; then
    echo -e "${RED}[ERREUR] Le tracker n'a pas pu démarrer${NC}"
    cat tracker_test.log
    exit 1
fi

echo -e "${GREEN}[OK] Tracker lancé (PID: $TRACKER_PID)${NC}"
echo ""

# Test 1: announce
echo "Test 1: announce"
echo "---"
(echo 'announce listen 12345 seed [file1 1000 100 hash1] leech [hash2]'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
RESULT=$?
if [ $RESULT -eq 0 ]; then
    echo -e "${GREEN}[OK] announce envoyé${NC}"
else
    echo -e "${RED}[ERREUR] announce échoué${NC}"
fi
echo ""

# Test 2: look
echo "Test 2: look"
echo "---"
(echo 'look'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
RESULT=$?
if [ $RESULT -eq 0 ]; then
    echo -e "${GREEN}[OK] look envoyé${NC}"
else
    echo -e "${RED}[ERREUR] look échoué${NC}"
fi
echo ""

# Test 3: getfile
echo "Test 3: getfile"
echo "---"
(echo 'getfile hash1'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
RESULT=$?
if [ $RESULT -eq 0 ]; then
    echo -e "${GREEN}[OK] getfile envoyé${NC}"
else
    echo -e "${RED}[ERREUR] getfile échoué${NC}"
fi
echo ""

# Test 4: update
echo "Test 4: update"
echo "---"
(echo 'update seed [hash1] leech [hash2]'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
RESULT=$?
if [ $RESULT -eq 0 ]; then
    echo -e "${GREEN}[OK] update envoyé${NC}"
else
    echo -e "${RED}[ERREUR] update échoué${NC}"
fi
echo ""

# Test 5: bye
echo "Test 5: bye"
echo "---"
(echo 'bye'; sleep 1) | nc -q 1 localhost 9000 2>/dev/null
RESULT=$?
if [ $RESULT -eq 0 ]; then
    echo -e "${GREEN}[OK] bye envoyé${NC}"
else
    echo -e "${RED}[ERREUR] bye échoué${NC}"
fi
echo ""

# Arrêter le tracker
echo "[*] Arrêt du tracker..."
kill $TRACKER_PID 2>/dev/null || pkill -f tracker/install/tracker
sleep 1

echo "========================================="
echo "      TESTS TERMINÉS"
echo "========================================="
echo ""
echo "Logs du tracker:"
echo "---"
cat tracker_test.log
echo ""
