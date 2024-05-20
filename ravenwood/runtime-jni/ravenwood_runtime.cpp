/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <fcntl.h>
#include <sys/stat.h>
#include <string.h>
#include <unistd.h>
#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

// Defined in ravenwood_os_constants.cpp
void register_android_system_OsConstants(JNIEnv* env);

// ---- Exception related ----

static void throwErrnoException(JNIEnv* env, const char* functionName) {
    int error = errno;
    jniThrowErrnoException(env, functionName, error);
}

template <typename rc_t>
static rc_t throwIfMinusOne(JNIEnv* env, const char* name, rc_t rc) {
    if (rc == rc_t(-1)) {
        throwErrnoException(env, name);
    }
    return rc;
}

// ---- JNI methods ----

typedef void (*FreeFunction)(void*);

static void nApplyFreeFunction(JNIEnv*, jclass, jlong freeFunction, jlong ptr) {
    void* nativePtr = reinterpret_cast<void*>(static_cast<uintptr_t>(ptr));
    FreeFunction nativeFreeFunction
        = reinterpret_cast<FreeFunction>(static_cast<uintptr_t>(freeFunction));
    nativeFreeFunction(nativePtr);
}

static jint nFcntlInt(JNIEnv* env, jclass, jint fd, jint cmd, jint arg) {
    return throwIfMinusOne(env, "fcntl", TEMP_FAILURE_RETRY(fcntl(fd, cmd, arg)));
}

static jlong nLseek(JNIEnv* env, jclass, jint fd, jlong offset, jint whence) {
    return throwIfMinusOne(env, "lseek", TEMP_FAILURE_RETRY(lseek(fd, offset, whence)));
}

static jintArray nPipe2(JNIEnv* env, jclass, jint flags) {
    int fds[2];
    throwIfMinusOne(env, "pipe2", TEMP_FAILURE_RETRY(pipe2(fds, flags)));

    jintArray result;
    result = env->NewIntArray(2);
    if (result == NULL) {
        return NULL; /* out of memory error thrown */
    }
    env->SetIntArrayRegion(result, 0, 2, fds);
    return result;
}

static jlong nDup(JNIEnv* env, jclass, jint fd) {
    return throwIfMinusOne(env, "fcntl", TEMP_FAILURE_RETRY(fcntl(fd, F_DUPFD_CLOEXEC, 0)));
}

// ---- Registration ----

static const JNINativeMethod sMethods[] =
{
    { "applyFreeFunction", "(JJ)V", (void*)nApplyFreeFunction },
    { "nFcntlInt", "(III)I", (void*)nFcntlInt },
    { "nLseek", "(IJI)J", (void*)nLseek },
    { "nPipe2", "(I)[I", (void*)nPipe2 },
    { "nDup", "(I)I", (void*)nDup },
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("GetEnv failed!");
        return result;
    }
    ALOG_ASSERT(env, "Could not retrieve the env!");

    ALOGI("%s: JNI_OnLoad", __FILE__);

    jint res = jniRegisterNativeMethods(env, "com/android/ravenwood/common/RavenwoodRuntimeNative",
            sMethods, NELEM(sMethods));
    if (res < 0) {
        return res;
    }

    register_android_system_OsConstants(env);

    return JNI_VERSION_1_4;
}
