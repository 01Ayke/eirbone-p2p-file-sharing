#ifndef TRACKER_PROTOCOL_H
#define TRACKER_PROTOCOL_H

#include <netinet/in.h>
#include "tracker_state.h"

void parse_seed_list_and_register(Peer *peer, const char *ip, const char *seed_content);
void parse_key_list(Peer *peer, const char *list_content, int is_seed);

void handle_announce(int client_fd, const char *line, const char *client_ip);
void handle_look(int client_fd, const char *line);
void handle_getfile(int client_fd, const char *line);
void handle_update(int client_fd, const char *line, const char *client_ip);
void handle_client(int client_fd, struct sockaddr_in *client_addr);

#endif