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
// Standard functions ported to the current platform.  Note these are NOT
// in the "android" namespace.
//
#ifndef _LIBS_UTILS_PORTED_H
#define _LIBS_UTILS_PORTED_H

#include <sys/time.h>       // for timeval

#ifdef __cplusplus
extern "C" {
#endif

/* library replacement functions */
#if defined(NEED_GETTIMEOFDAY)
int gettimeofday(struct timeval* tv, struct timezone* tz);
#endif
#if defined(NEED_USLEEP)
void usleep(unsigned long usec);
#endif
#if defined(NEED_PIPE)
int pipe(int filedes[2]);
#endif
#if defined(NEED_SETENV)
int setenv(const char* name, const char* value, int overwrite);
void unsetenv(const char* name);
char* getenv(const char* name);
#endif

#ifdef __cplusplus
}
#endif

#endif // _LIBS_UTILS_PORTED_H
