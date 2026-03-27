#ifndef TRACKER_PROTOCOL_H
#define TRACKER_PROTOCOL_H

#include <netinet/in.h>
#include "tracker_state.h"

/*
 * Contexte par connexion client : conserve l'IP, le port d'écoute P2P
 * et le pointeur vers le peer enregistré après announce.
 */
typedef struct {
    char client_ip[INET_ADDRSTRLEN];
    int  listen_port;   /* port d'écoute P2P du peer (défini à l'announce) */
    Peer *peer;         /* NULL tant que le peer n'a pas fait announce      */
} ClientContext;

void parse_seed_list_and_register(Peer *peer, const char *ip, const char *seed_content);
void parse_key_list(Peer *peer, const char *list_content, int is_seed);

void handle_announce(int client_fd, const char *line, ClientContext *ctx);
void handle_look(int client_fd, const char *line);
void handle_getfile(int client_fd, const char *line);
void handle_update(int client_fd, const char *line, ClientContext *ctx);
void handle_bye(int client_fd, ClientContext *ctx);
void handle_client(int client_fd, struct sockaddr_in *client_addr);

#endif
