/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#include <stdlib.h>
#include <ctype.h>
#include <string.h>
#include <errno.h>

#include <sys/ioctl.h>
#include <unistd.h>
#include <sys/socket.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <cutils/log.h>
#include <cutils/properties.h>
#include <../../../libcore/include/StaticAssert.h>

#define EGL_TRACE 1
#include "hooks.h"

#define GL_ENTRY(_r, _api, ...) _r Debug_##_api ( __VA_ARGS__ );
#include "../include/glesv2_dbg.h"

#include "DebuggerMessage.pb.h"

using namespace android;

#define API_ENTRY(_api) Debug_##_api

#ifndef __location__
#define __HIERALLOC_STRING_0__(s)   #s
#define __HIERALLOC_STRING_1__(s)   __HIERALLOC_STRING_0__(s)
#define __HIERALLOC_STRING_2__      __HIERALLOC_STRING_1__(__LINE__)
#define __location__                __FILE__ ":" __HIERALLOC_STRING_2__
#endif

#define ASSERT(expr) if (!(expr)) { LOGD("\n*\n*\n* ASSERT FAILED: %s at %s \n*\n*", #expr, __location__); exit(1); }
#undef assert
#define assert(expr) ASSERT(expr)
//#undef LOGD
//#define LOGD(...)

namespace android
{
extern int clientSock, serverSock;
#define BUFFSIZE 256
extern char sockBuff [BUFFSIZE];

void Send(const GLESv2Debugger::Message & msg, GLESv2Debugger::Message & cmd);
}; // namespace android {
