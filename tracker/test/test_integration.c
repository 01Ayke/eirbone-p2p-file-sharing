#include "../src/config.h"
#include "../src/tracker_storage.h"
#include "../src/tracker_protocol.h"
#include "../src/tracker_utils.h"
#include "../src/tracker_state.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

static int failed = 0;

#define CHECK(cond) do { \
    if (!(cond)) { fprintf(stderr, "FAIL: %s (line %d)\n", #cond, __LINE__); failed++; } \
} while(0)

// remette à zéro l'état du tracker avant chaque test
static void reset(int n) {
    free(peers); free(files);
    peers = calloc(n, sizeof(Peer));
    files = calloc(n, sizeof(FileEntry));
    peer_count = file_count = 0;
    peers_capacity = files_capacity = n;
}

typedef struct { int fd; struct sockaddr_in addr; } SrvArg;

// thread qui exécute handle_client() pour un client donné
static void *srv(void *a) {
    SrvArg *s = a;
    handle_client(s->fd, &s->addr);
    free(s);
    return NULL;
}

// ouvre une connexion client-serveur et lance le thread serveur
static int client_open(pthread_t *tid) {
    int sv[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    SrvArg *a = malloc(sizeof *a);
    a->fd = sv[0];
    memset(&a->addr, 0, sizeof a->addr);
    a->addr.sin_family = AF_INET;
    inet_pton(AF_INET, "127.0.0.1", &a->addr.sin_addr);
    pthread_create(tid, NULL, srv, a);
    return sv[1];
}

// envoie une commande au serveur et lit la réponse (jusqu'à '\n' ou n-1 chars)
static void cmd(int fd, const char *c, char *resp, size_t n) {
    write(fd, c, strlen(c));
    if (c[strlen(c)-1] != '\n') write(fd, "\n", 1);
    memset(resp, 0, n);
    size_t i = 0;
    char ch;
    while (i < n-1 && read(fd, &ch, 1) == 1) { resp[i++] = ch; if (ch == '\n') break; }
}

/* tests */

// test d'une séquence d'annonces et de recherches basiques
static void test_announce_ok(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "announce listen 5000 seed [f.iso 1000 512 k1] leech []", r, sizeof r);
    CHECK(strncmp(r, "ok", 2) == 0);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
    CHECK(file_count == 0);
}

// test d'une annonce mal formée (manque "seed" ou "leech")
static void test_announce_bad(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "announce listen 5000 noseeds", r, sizeof r);
    CHECK(strncmp(r, "error", 5) == 0);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
}

// test d'une recherche "look" sur un tracker vide
static void test_look_empty(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "look []", r, sizeof r);
    CHECK(strncmp(r, "list [", 6) == 0);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
}

// test d'une recherche "look" qui doit voir un fichier annoncé par un autre client
static void test_look_sees_file(void) {
    reset(10);
    pthread_t t1; int fd1 = client_open(&t1);
    pthread_t t2; int fd2 = client_open(&t2);
    char r[512];
    cmd(fd1, "announce listen 5001 seed [x.mp3 5000 512 xkey] leech []", r, sizeof r);
    cmd(fd2, "look []", r, sizeof r);
    CHECK(strstr(r, "x.mp3") != NULL);
    CHECK(strstr(r, "xkey")  != NULL);
    cmd(fd2, "bye", r, sizeof r); close(fd2); pthread_join(t2, NULL);
    cmd(fd1, "bye", r, sizeof r); close(fd1); pthread_join(t1, NULL);
}

//  test d'une recherche "look" avec un filtre sur le nom de fichier
static void test_look_by_name(void) {
    reset(10);
    pthread_t t1; int fd1 = client_open(&t1);
    pthread_t t2; int fd2 = client_open(&t2);
    char r[512];
    cmd(fd1, "announce listen 5002 seed [a.dat 100 10 akey] leech []", r, sizeof r);
    cmd(fd2, "look [filename=a.dat]", r, sizeof r);
    CHECK(strstr(r, "a.dat") != NULL);
    cmd(fd2, "look [filename=nope.dat]", r, sizeof r);
    CHECK(strstr(r, "nope") == NULL);
    cmd(fd2, "bye", r, sizeof r); close(fd2); pthread_join(t2, NULL);
    cmd(fd1, "bye", r, sizeof r); close(fd1); pthread_join(t1, NULL);
}

// test d'une recherche "getfile" pour un fichier connu et un fichier inconnu
static void test_getfile_found(void) {
    reset(10);
    pthread_t t1; int fd1 = client_open(&t1);
    pthread_t t2; int fd2 = client_open(&t2);
    char r[512];
    cmd(fd1, "announce listen 5003 seed [b.dat 200 20 bkey] leech []", r, sizeof r);
    cmd(fd2, "getfile bkey", r, sizeof r);
    CHECK(strstr(r, "127.0.0.1:5003") != NULL);
    cmd(fd2, "bye", r, sizeof r); close(fd2); pthread_join(t2, NULL);
    cmd(fd1, "bye", r, sizeof r); close(fd1); pthread_join(t1, NULL);
}

// test d'une recherche "getfile" pour une clé inconnue
static void test_getfile_unknown(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "getfile nokey", r, sizeof r);
    CHECK(strncmp(r, "peers nokey [", 13) == 0);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
}

// test d'une recherche "getfile" avec une syntaxe incorrecte
static void test_getfile_bad(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "getfile", r, sizeof r);
    CHECK(strncmp(r, "error", 5) == 0);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
}

// test d'une commande "update" sans annonce préalable
static void test_update_no_announce(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "update seed [k] leech []", r, sizeof r);
    CHECK(strncmp(r, "error not announced", 19) == 0);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
}

// test d'une commande "update" qui change un peer de seeder à leecher pour un fichier donné
static void test_update_seed_to_leech(void) {
    reset(10);
    pthread_t t1; int fd1 = client_open(&t1);
    pthread_t t2; int fd2 = client_open(&t2);
    char r[256];
    // seeder annonce, leecher update en seed → devient owner
    cmd(fd1, "announce listen 5004 seed [c.dat 300 30 ckey] leech []", r, sizeof r);
    cmd(fd2, "announce listen 5008 seed [] leech [ckey]", r, sizeof r);
    cmd(fd2, "update seed [ckey] leech []", r, sizeof r);
    CHECK(strncmp(r, "ok", 2) == 0);
    storage_lock();
    FileEntry *f = find_file_by_key("ckey");
    int oc = f ? f->owner_count : -1;
    storage_unlock();
    CHECK(oc == 2);
    cmd(fd2, "bye", r, sizeof r); close(fd2); pthread_join(t2, NULL);
    cmd(fd1, "bye", r, sizeof r); close(fd1); pthread_join(t1, NULL);
}

// test d'une commande "bye" qui doit purger les fichiers annoncés par le client
static void test_bye_purges_file(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "announce listen 5005 seed [d.dat 400 40 dkey] leech []", r, sizeof r);
    storage_lock(); int fc = file_count; storage_unlock();
    CHECK(fc == 1);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
    CHECK(file_count == 0);
}

// test d'une déconnexion abrupte (sans "bye") qui doit marquer le peer comme déconnecté et purger ses fichiers
static void test_abrupt_disconnect(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "announce listen 5006 seed [e.dat 500 50 ekey] leech []", r, sizeof r);
    close(fd); pthread_join(t, NULL);
    storage_lock();
    Peer *p = find_peer("127.0.0.1", 5006);
    storage_unlock();
    CHECK(p && p->connected == 0);
    CHECK(file_count == 0);
}

// test d'une double annonce qui doit réinitialiser les clés du peer
static void test_double_announce_resets_keys(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "announce listen 5007 seed [f1.dat 100 10 fkey1] leech []", r, sizeof r);
    cmd(fd, "announce listen 5007 seed [f2.dat 200 20 fkey2] leech []", r, sizeof r);
    storage_lock();
    FileEntry *f1 = find_file_by_key("fkey1");
    FileEntry *f2 = find_file_by_key("fkey2");
    storage_unlock();
    CHECK(f1 == NULL);
    CHECK(f2 != NULL);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
}

// test d'une commande inconnue
static void test_unknown_command(void) {
    reset(10);
    pthread_t t; int fd = client_open(&t);
    char r[256];
    cmd(fd, "foobar", r, sizeof r);
    CHECK(strncmp(r, "error unknown command", 21) == 0);
    cmd(fd, "bye", r, sizeof r);
    close(fd); pthread_join(t, NULL);
}

// test de la capacité maximale de peers
static void test_capacity_peers(void) {
    reset(2);
    Peer *p1 = find_or_create_peer("1.1.1.1", 1);
    Peer *p2 = find_or_create_peer("1.1.1.2", 2);
    Peer *p3 = find_or_create_peer("1.1.1.3", 3);
    CHECK(p1 && p2);
    CHECK(p3 == NULL);
    CHECK(peer_count == 2);
}

// test de la capacité maximale de fichiers
static void test_capacity_files(void) {
    reset(2);
    FileEntry *f1 = find_or_create_file("a", 1, 1, "ka");
    FileEntry *f2 = find_or_create_file("b", 1, 1, "kb");
    FileEntry *f3 = find_or_create_file("c", 1, 1, "kc");
    CHECK(f1 && f2);
    CHECK(f3 == NULL);
    CHECK(file_count == 2);
}

// test de deux seeders pour le même fichier
static void test_two_seeders_same_file(void) {
    reset(10);
    pthread_t t1; int fd1 = client_open(&t1);
    pthread_t t2; int fd2 = client_open(&t2);
    pthread_t t3; int fd3 = client_open(&t3);
    char r[512];
    cmd(fd1, "announce listen 6001 seed [shared.dat 1000 100 skey] leech []", r, sizeof r);
    cmd(fd2, "announce listen 6002 seed [shared.dat 1000 100 skey] leech []", r, sizeof r);
    cmd(fd3, "getfile skey", r, sizeof r);
    CHECK(strstr(r, "127.0.0.1:6001") != NULL);
    CHECK(strstr(r, "127.0.0.1:6002") != NULL);
    cmd(fd3, "bye", r, sizeof r); close(fd3); pthread_join(t3, NULL);
    cmd(fd1, "bye", r, sizeof r); close(fd1); pthread_join(t1, NULL);
    cmd(fd2, "bye", r, sizeof r); close(fd2); pthread_join(t2, NULL);
}

#define N_CONCURRENT 8 // nombre de clients concurrents pour le test de concurrence

// worker qui simule un client qui annonce un fichier, fait une recherche, puis se déconnecte
static void *concurrent_worker(void *arg) {
    int port = 9000 + (int)(long)arg;
    char buf[256], r[256];
    snprintf(buf, sizeof buf,
             "announce listen %d seed [f%d.dat 1000 100 ck%d] leech []\n",
             port, port, port);
    pthread_t t; int fd = client_open(&t);
    write(fd, buf, strlen(buf));
    size_t i = 0; char ch;
    while (i < sizeof(r)-1 && read(fd, &ch, 1)==1) { r[i++]=ch; if(ch=='\n') break; }
    write(fd, "bye\n", 4);
    i = 0;
    while (i < sizeof(r)-1 && read(fd, &ch, 1)==1) { r[i++]=ch; if(ch=='\n') break; }
    close(fd); pthread_join(t, NULL);
    return NULL;
}

// test de plusieurs clients concurrents qui annoncent chacun un fichier différent, font une recherche, puis se déconnectent
static void test_concurrent(void) {
    reset(N_CONCURRENT + 2);
    pthread_t tids[N_CONCURRENT];
    for (int i = 0; i < N_CONCURRENT; i++)
        pthread_create(&tids[i], NULL, concurrent_worker, (void*)(long)i);
    for (int i = 0; i < N_CONCURRENT; i++)
        pthread_join(tids[i], NULL);
    CHECK(file_count == 0);
    CHECK(peer_count == N_CONCURRENT);
}

char *red = "\033[31m";
char *green = "\033[32m";
char *reset_ = "\033[0m";
char *purple = "\033[35m";

/*
rq :
ce test d'intégration lance plusieurs clients concurrents qui interagissent avec le tracker 
en envoyant des commandes d'annonce, de recherche, puis de déconnexion. 
Les client sont pour l'instant des threads qui simulent des peers.
*/

int main(void) {
    log_init("/dev/null"); // désactive la journalisation pour ne pas polluer la sortie des tests
    test_announce_ok();
    test_announce_bad();
    test_look_empty();
    test_look_sees_file();
    test_look_by_name();
    test_getfile_found();
    test_getfile_unknown();
    test_getfile_bad();
    test_update_no_announce();
    test_update_seed_to_leech();
    test_bye_purges_file();
    test_abrupt_disconnect();
    test_double_announce_resets_keys();
    test_unknown_command();
    test_capacity_peers();
    test_capacity_files();
    test_two_seeders_same_file();
    test_concurrent();

    if (failed)
        fprintf(stderr, "%d test(s) %sfailed%s\n", failed, red, reset_);
    else
        printf("%s All tests passed %s\n", green, reset_);

    free(peers); free(files);
    return failed ? 1 : 0;
}