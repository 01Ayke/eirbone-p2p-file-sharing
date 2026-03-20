#ifndef TRACKER_H
#define TRACKER_H
#include "peer.h"

#include <netinet/in.h>

struct config_t {
  struct sockaddr_in serv_addr;
  int port;
};

struct tracker_t {
  struct config_t config;
  struct peer_t *peers;
};

#endif // TRACKER_H
