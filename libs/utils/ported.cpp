/*
 * Copyright (C) 2005 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//
// Ports of standard functions that don't exist on a specific platform.
//
// Note these are NOT in the "android" namespace.
//
#include <utils/ported.h>

#if defined(NEED_GETTIMEOFDAY) || defined(NEED_USLEEP)
# include <sys/time.h>
# include <windows.h>
#endif


#if defined(NEED_GETTIMEOFDAY)
/*
 * Replacement gettimeofday() for Windows environments (primarily MinGW).
 *
 * Ignores "tz".
 */
int gettimeofday(struct timeval* ptv, struct timezone* tz)
{
    long long nsTime;   // time in 100ns units since Jan 1 1601
    FILETIME ft;

    if (tz != NULL) {
        // oh well
    }

    ::GetSystemTimeAsFileTime(&ft);
    nsTime = (long long) ft.dwHighDateTime << 32 |
             (long long) ft.dwLowDateTime;
    // convert to time in usec since Jan 1 1970
    ptv->tv_usec = (long) ((nsTime / 10LL) % 1000000LL);
    ptv->tv_sec = (long) ((nsTime - 116444736000000000LL) / 10000000LL);

    return 0;
}
#endif

#if defined(NEED_USLEEP)
//
// Replacement usleep for Windows environments (primarily MinGW).
//
void usleep(unsigned long usec)
{
    // Win32 API function Sleep() takes milliseconds
    ::Sleep((usec + 500) / 1000);
}
#endif

#if 0 //defined(NEED_PIPE)
//
// Replacement pipe() command for MinGW
//
// The _O_NOINHERIT flag sets bInheritHandle to FALSE in the
// SecurityAttributes argument to CreatePipe().  This means the handles
// aren't inherited when a new process is created.  The examples I've seen
// use it, possibly because there's a lot of junk going on behind the
// scenes.  (I'm assuming "process" and "thread" are different here, so
// we should be okay spinning up a thread.)  The recommended practice is
// to dup() the descriptor you want the child to have.
//
// It appears that unnamed pipes can't do non-blocking ("overlapped") I/O.
// You can't use select() either, since that only works on sockets.  The
// Windows API calls that are useful here all operate on a HANDLE, not
// an integer file descriptor, and I don't think you can get there from
// here.  The "named pipe" stuff is insane.
//
int pipe(int filedes[2])
{
    return _pipe(filedes, 0, _O_BINARY | _O_NOINHERIT);
}
#endif

#if defined(NEED_SETENV)
/*
 * MinGW lacks these.  For now, just stub them out so the code compiles.
 */
int setenv(const char* name, const char* value, int overwrite)
{
    return 0;
}
void unsetenv(const char* name)
{
}
char* getenv(const char* name)
{
    return NULL;
}
#endif
