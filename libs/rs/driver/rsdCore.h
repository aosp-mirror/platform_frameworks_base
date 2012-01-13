/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

#ifndef RSD_CORE_H
#define RSD_CORE_H

#include <rs_hal.h>

#include "rsMutex.h"
#include "rsSignal.h"

#include "rsdGL.h"

typedef void (* InvokeFunc_t)(void);
typedef void (*WorkerCallback_t)(void *usr, uint32_t idx);

typedef struct RsdSymbolTableRec {
    const char * mName;
    void * mPtr;
    bool threadable;
} RsdSymbolTable;

typedef struct ScriptTLSStructRec {
    android::renderscript::Context * mContext;
    android::renderscript::Script * mScript;
} ScriptTLSStruct;

typedef struct RsdHalRec {
    uint32_t version_major;
    uint32_t version_minor;
    bool mHasGraphics;

    struct Workers {
        volatile int mRunningCount;
        volatile int mLaunchCount;
        uint32_t mCount;
        pthread_t *mThreadId;
        pid_t *mNativeThreadId;
        android::renderscript::Signal mCompleteSignal;

        android::renderscript::Signal *mLaunchSignals;
        WorkerCallback_t mLaunchCallback;
        void *mLaunchData;
    };
    Workers mWorkers;
    bool mExit;

    ScriptTLSStruct mTlsStruct;

    RsdGL gl;
} RsdHal;

extern pthread_key_t rsdgThreadTLSKey;
extern uint32_t rsdgThreadTLSKeyCount;
extern pthread_mutex_t rsdgInitMutex;


void rsdLaunchThreads(android::renderscript::Context *rsc, WorkerCallback_t cbk, void *data);

#endif

