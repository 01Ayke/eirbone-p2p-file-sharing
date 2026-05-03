#include "../src/tracker_storage.h"
#include "../src/tracker_state.h"
#include <assert.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

char *red = "\033[31m";
char *green = "\033[32m";
char *reset = "\033[0m";
char *purple = "\033[35m";

void test_find_or_create_peer() {
    
    peers = (Peer *)malloc(10 * sizeof(Peer));
    peer_count = 0;
    peers_capacity = 10;
    
    Peer *p1 = find_or_create_peer("192.168.1.1", 6000);
    assert(p1 != NULL);
    assert(peer_count == 1);
    assert(strcmp(p1->ip, "192.168.1.1") == 0);
    assert(p1->listen_port == 6000);
    assert(p1->connected == 1);
    
    Peer *p1_found = find_or_create_peer("192.168.1.1", 6000);
    assert(p1_found == p1);
    assert(peer_count == 1);
    
    Peer *p2 = find_or_create_peer("192.168.1.2", 6001);
    assert(p2 != NULL && p2 != p1);
    assert(peer_count == 2);
    
    free(peers);
    peers = NULL;
    peer_count = 0;
    peers_capacity = 0;
    
    printf("find_or_create_peer() %spassed%s\n", green, reset);
}

void test_find_peer() {    
    peers = (Peer *)malloc(10 * sizeof(Peer));
    peer_count = 0;
    peers_capacity = 10;
    
    Peer *p1 = find_or_create_peer("10.0.0.1", 5000);
    Peer *p_found = find_peer("10.0.0.1", 5000);
    assert(p_found == p1);
    
    Peer *p_notfound = find_peer("10.0.0.2", 5001);
    assert(p_notfound == NULL);
    
    free(peers);
    peers = NULL;
    peer_count = 0;
    peers_capacity = 0;
    
    printf("find_peer() %spassed%s\n", green, reset);
}

void test_find_or_create_file() {    
    files = (FileEntry *)malloc(10 * sizeof(FileEntry));
    file_count = 0;
    files_capacity = 10;
    
    FileEntry *f1 = find_or_create_file("ubuntu.iso", 1000000, 256000, "abc123");
    assert(f1 != NULL);
    assert(file_count == 1);
    assert(strcmp(f1->filename, "ubuntu.iso") == 0);
    assert(f1->length == 1000000);
    assert(f1->piece_size == 256000);
    assert(strcmp(f1->key, "abc123") == 0);
    
    FileEntry *f1_found = find_or_create_file("ubuntu.iso", 1000000, 256000, "abc123");
    assert(f1_found == f1);
    assert(file_count == 1);
    
    free(files);
    files = NULL;
    file_count = 0;
    files_capacity = 0;
    
    printf("find_or_create_file() %spassed%s\n", green, reset);
}

void test_find_file_by_key() {
    
    files = (FileEntry *)malloc(10 * sizeof(FileEntry));
    file_count = 0;
    files_capacity = 10;
    
    FileEntry *f = find_or_create_file("file.dat", 1000, 100, "key1");
    
    /* Test 1: Trouver par clé */
    FileEntry *f_found = find_file_by_key("key1");
    assert(f_found == f);
    
    /* Test 2: Clé inexistante */
    FileEntry *f_notfound = find_file_by_key("unknown");
    assert(f_notfound == NULL);
    
    free(files);
    files = NULL;
    file_count = 0;
    files_capacity = 0;
    
    printf("find_file_by_key() %spassed%s\n", green, reset);
}

void test_file_owner_management() {    
    files = (FileEntry *)malloc(10 * sizeof(FileEntry));
    file_count = 0;
    files_capacity = 10;
    FileEntry *f = find_or_create_file("file.dat", 1000, 100, "key1");
    assert(file_has_owner(f, "owner1") == 0);
    
    add_owner_to_file(f, "owner1");
    assert(f->owner_count == 1);
    assert(file_has_owner(f, "owner1") == 1);
    
    add_owner_to_file(f, "owner2");
    assert(f->owner_count == 2);
    assert(file_has_owner(f, "owner2") == 1);
    
    add_owner_to_file(f, "owner1");
    assert(f->owner_count == 2);
    
    remove_owner_from_file(f, "owner1");
    assert(f->owner_count == 1);
    assert(file_has_owner(f, "owner1") == 0);
    assert(file_has_owner(f, "owner2") == 1);
    
    remove_owner_from_file(f, "unknown");
    assert(f->owner_count == 1);
    
    free(files);
    files = NULL;
    file_count = 0;
    files_capacity = 0;
    
    printf("file_owner_management() %spassed%s\n", green, reset);
}

void test_peer_keys() {    
    peers = (Peer *)malloc(10 * sizeof(Peer));
    peer_count = 0;
    peers_capacity = 10;
    
    Peer *p = find_or_create_peer("10.0.0.1", 5000);
    
    assert(p->seed_count == 0);
    assert(p->leech_count == 0);
    
    add_seed_key(p, "seed1");
    assert(p->seed_count == 1);
    assert(strcmp(p->seeded_keys[0], "seed1") == 0);
    
    add_leech_key(p, "leech1");
    assert(p->leech_count == 1);
    assert(strcmp(p->leeched_keys[0], "leech1") == 0);
    
    add_seed_key(p, "seed2");
    add_leech_key(p, "leech2");
    assert(p->seed_count == 2);
    assert(p->leech_count == 2);
    
    clear_peer_keys(p);
    assert(p->seed_count == 0);
    assert(p->leech_count == 0);
    
    free(peers);
    peers = NULL;
    peer_count = 0;
    peers_capacity = 0;
    
    printf("peer keys management %spassed%s\n", green, reset);
}

void test_purge_orphan_files() {
    
    files = (FileEntry *)malloc(10 * sizeof(FileEntry));
    file_count = 0;
    files_capacity = 10;
    
    FileEntry *f1 = find_or_create_file("file1.dat", 100, 10, "key1");
    FileEntry *f2 = find_or_create_file("file2.dat", 200, 10, "key2");
    FileEntry *f3 = find_or_create_file("file3.dat", 300, 10, "key3");
    
    add_owner_to_file(f1, "owner1");
    add_owner_to_file(f2, "owner2");
    add_owner_to_file(f3, "owner3");
    
    assert(file_count == 3);
    
    remove_owner_from_file(f2, "owner2");
    assert(f2->owner_count == 0);
    purge_orphan_files();
    assert(file_count == 2);
    
    assert(strcmp(files[0].key, "key1") == 0);
    assert(strcmp(files[1].key, "key3") == 0);
    
    free(files);
    files = NULL;
    file_count = 0;
    files_capacity = 0;
    
    printf("purge_orphan_files() %spassed%s\n", green, reset);
}

int main() {
    printf("\n%s-> tracker_storage Tests%s\n", purple, reset);
    
    test_find_or_create_peer();
    test_find_peer();
    test_find_or_create_file();
    test_find_file_by_key();
    test_file_owner_management();
    test_peer_keys();
    test_purge_orphan_files();
    
    printf("%sAll tracker_storage tests passed!%s\n\n", green, reset);
    return 0;
}
