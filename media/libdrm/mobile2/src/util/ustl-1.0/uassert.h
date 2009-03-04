// uassert.h

#ifndef UASSERT_H
#define UASSERT_H

#if PLATFORM_ANDROID
#include <stdio.h>

#undef assert
#define assert(x) _uassert((x), #x, __FILE__, __LINE__)

static void _uassert(int x, const char *xstr, const char *file, int line) {
  if (!x) {
    printf("assert %s failed at %s:%d\n", xstr, file, line);
  }
}
#else
#include <assert.h>
#endif

#endif
