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

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <cutils/log.h>
#include <utils/Timers.h>
#include <../../../libcore/include/StaticAssert.h>

#define EGL_TRACE 1
#include "hooks.h"

#define GL_ENTRY(_r, _api, ...) _r Debug_##_api ( __VA_ARGS__ );
#include "../include/glesv2_dbg.h"

#include "debugger_message.pb.h"

using namespace android;
using namespace com::android;

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
struct FunctionCall {
    virtual const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) = 0;
    virtual ~FunctionCall() {}
};

extern bool capture;
extern int timeMode; // SYSTEM_TIME_

extern int clientSock, serverSock;

unsigned GetBytesPerPixel(const GLenum format, const GLenum type);

int * MessageLoop(FunctionCall & functionCall, glesv2debugger::Message & msg,
                  const bool expectResponse, const glesv2debugger::Message_Function function);
void Receive(glesv2debugger::Message & cmd);
float Send(const glesv2debugger::Message & msg, glesv2debugger::Message & cmd);
void SetProp(const glesv2debugger::Message & cmd);
}; // namespace android {
