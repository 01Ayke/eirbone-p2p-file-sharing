#ifndef FILE_H
#define FILE_H

#include <netinet/in.h>
#define MAX_FILENAME 256
#define MD5_HASH_SIZE 256

struct file_t {
  char filename[MAX_FILENAME];
  char hash[MD5_HASH_SIZE];
  int chunk_size;
};

#endif // FILE_H
