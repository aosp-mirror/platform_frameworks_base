#include "aidl_language.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#ifdef HAVE_MS_C_RUNTIME
int isatty(int  fd)
{
    return (fd == 0);
}
#endif

#if 0
ParserCallbacks k_parserCallbacks = {
    NULL
};
#endif

ParserCallbacks* g_callbacks = NULL; // &k_parserCallbacks;

