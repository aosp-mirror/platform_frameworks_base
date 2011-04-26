#ifndef SPEC_H
#define SPEC_H

#include <string.h>
#include <stdlib.h>

#if __cplusplus
extern "C" {
#endif

extern int num_lines;

typedef struct {
  int isConst;
  int type;
  int bits;
  int ptrLevel;
  char name[256];
  char typeName[256];
} VarType;

extern VarType *currType;

typedef struct {
  char name[256];
  int sync;
  int handcodeApi;
  int direct;
  int nocontext;
  int paramCount;
  VarType ret;
  VarType params[16];
} ApiEntry;

extern ApiEntry apis[128];
extern int apiCount;

extern int typeNextState;

#if __cplusplus
} // extern "C"
#endif

#endif // SPEC_H
