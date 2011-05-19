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

#ifndef _GLESV2_DBG_H_
#define _GLESV2_DBG_H_

#include <pthread.h>

namespace android
{
struct DbgContext;

DbgContext* CreateDbgContext(const unsigned version, const gl_hooks_t * const hooks);

void dbgReleaseThread();

// create and bind socket if haven't already, if failed to create socket or
//  forceUseFile, then open /data/local/tmp/dump.gles2dbg, exit when size reached
void StartDebugServer(const unsigned short port, const bool forceUseFile,
                      const unsigned int maxFileSize, const char * const filePath);
void StopDebugServer(); // close socket if open

}; // namespace android

#endif // #ifndef _GLESV2_DBG_H_
