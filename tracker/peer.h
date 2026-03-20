#ifndef PEER_H
#define PEER_H
#include "file.h"

#include <netinet/in.h>

struct peer_t {
  struct sockaddr_in cli_addr;
  int port;
  struct file_t *files;
  unsigned int **bitmaps;
};

#endif // PEER_H
