#ifndef TRACKER_STORAGE_H
#define TRACKER_STORAGE_H

#include "tracker_state.h"

Peer *find_or_create_peer(const char *ip, int listen_port);
int file_has_owner(FileEntry *f, const char *owner);
FileEntry *find_file_by_key(const char *key);
FileEntry *find_or_create_file(const char *filename, long length, int piece_size, const char *key);
void add_owner_to_file(FileEntry *f, const char *owner);
void clear_peer_keys(Peer *p);
void add_seed_key(Peer *p, const char *key);
void add_leech_key(Peer *p, const char *key);

#endif