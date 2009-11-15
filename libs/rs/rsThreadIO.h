/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_THREAD_IO_H
#define ANDROID_RS_THREAD_IO_H

#include "rsUtils.h"
#include "rsLocklessFifo.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class Context;

class ThreadIO {
public:
    ThreadIO();
    ~ThreadIO();

    void shutdown();

    // Plays back commands from the client.
    // Returns true if any commands were processed.
    bool playCoreCommands(Context *con, bool waitForCommand);


    LocklessCommandFifo mToCore;
    LocklessCommandFifo mToClient;

    intptr_t mToCoreRet;

};


}
}
#endif

