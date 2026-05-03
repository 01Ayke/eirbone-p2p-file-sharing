#include "../src/tracker_protocol.h"
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

void test_parse_key_list() {
    
    peers = (Peer *)malloc(10 * sizeof(Peer));
    peer_count = 0;
    peers_capacity = 10;
    
    Peer *p = find_or_create_peer("192.168.1.100", 6000);
    
    parse_key_list(p, "key1 key2 key3", 1);
    assert(p->seed_count == 3);
    assert(strcmp(p->seeded_keys[0], "key1") == 0);
    assert(strcmp(p->seeded_keys[1], "key2") == 0);
    assert(strcmp(p->seeded_keys[2], "key3") == 0);
    
    parse_key_list(p, "lkey1 lkey2", 0);
    assert(p->leech_count == 2);
    assert(strcmp(p->leeched_keys[0], "lkey1") == 0);
    assert(strcmp(p->leeched_keys[1], "lkey2") == 0);
    
    Peer *p2 = find_or_create_peer("192.168.1.101", 6001);
    parse_key_list(p2, "", 1);
    assert(p2->seed_count == 0);
    
    free(peers);
    peers = NULL;
    peer_count = 0;
    peers_capacity = 0;
    
    printf("parse_key_list() %spassed%s\n", green, reset);
}

void test_parse_seed_list_and_register() {    
    peers = (Peer *)malloc(10 * sizeof(Peer));
    peer_count = 0;
    peers_capacity = 10;
    
    files = (FileEntry *)malloc(10 * sizeof(FileEntry));
    file_count = 0;
    files_capacity = 10;
    
    Peer *p = find_or_create_peer("192.168.1.50", 7000);
    
    const char *seed_data = "file1.dat 1000 100 hash1 file2.iso 2000 200 hash2";
    parse_seed_list_and_register(p, "192.168.1.50", seed_data);
    
    assert(file_count == 2);
    assert(p->seed_count == 2);
    assert(strcmp(files[0].filename, "file1.dat") == 0);
    assert(strcmp(files[1].filename, "file2.iso") == 0);
    assert(files[0].length == 1000);
    assert(files[1].length == 2000);
    
    assert(file_has_owner(&files[0], "192.168.1.50:7000") == 1);
    assert(file_has_owner(&files[1], "192.168.1.50:7000") == 1);
    
    Peer *p2 = find_or_create_peer("192.168.1.51", 7001);
    parse_seed_list_and_register(p2, "192.168.1.51", "");
    assert(p2->seed_count == 0);
    
    free(peers);
    peers = NULL;
    peer_count = 0;
    peers_capacity = 0;
    
    free(files);
    files = NULL;
    file_count = 0;
    files_capacity = 0;
    
    printf("parse_seed_list_and_register() %spassed%s\n", green, reset);
}

int main() {
    printf("\n%s-> tracker_protocol Tests%s\n", purple, reset);
    
    test_parse_key_list();
    test_parse_seed_list_and_register();
    
    printf("%sAll tracker_protocol tests passed!%s\n\n", green, reset);
    return 0;
}
