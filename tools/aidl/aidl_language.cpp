#include "aidl_language.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#ifdef _WIN32
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

