#ifndef TRACKER_STORAGE_H
#define TRACKER_STORAGE_H

#include "tracker_state.h"

/* Verrou global sur les données partagées (peers[], files[]) */
void storage_lock(void);
void storage_unlock(void);

Peer *find_or_create_peer(const char *ip, int listen_port);
Peer *find_peer(const char *ip, int listen_port);
int file_has_owner(FileEntry *f, const char *owner);
FileEntry *find_file_by_key(const char *key);
FileEntry *find_or_create_file(const char *filename, long length, int piece_size, const char *key);
void add_owner_to_file(FileEntry *f, const char *owner);
void remove_owner_from_file(FileEntry *f, const char *owner);
void clear_peer_keys(Peer *p);
void add_seed_key(Peer *p, const char *key);
void add_leech_key(Peer *p, const char *key);

/* Supprime de files[] toute entrée dont owner_count == 0 */
void purge_orphan_files(void);

#endif
