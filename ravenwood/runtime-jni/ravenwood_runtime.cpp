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
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <utils/misc.h>
#include <unicode/utypes.h>

#include <string>

#include "jni_helper.h"

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

// ---- Helper functions ---

static jclass g_StructStat;
static jclass g_StructTimespecClass;

static jobject makeStructTimespec(JNIEnv* env, const struct timespec& ts) {
    static jmethodID ctor = env->GetMethodID(g_StructTimespecClass, "<init>",
            "(JJ)V");
    if (ctor == NULL) {
        return NULL;
    }
    return env->NewObject(g_StructTimespecClass, ctor,
            static_cast<jlong>(ts.tv_sec), static_cast<jlong>(ts.tv_nsec));
}

static jobject makeStructStat(JNIEnv* env, const struct stat64& sb) {
    static jmethodID ctor = env->GetMethodID(g_StructStat, "<init>",
            "(JJIJIIJJLandroid/system/StructTimespec;Landroid/system/StructTimespec;Landroid/system/StructTimespec;JJ)V");
    if (ctor == NULL) {
        return NULL;
    }

    jobject atim_timespec = makeStructTimespec(env, sb.st_atim);
    if (atim_timespec == NULL) {
        return NULL;
    }
    jobject mtim_timespec = makeStructTimespec(env, sb.st_mtim);
    if (mtim_timespec == NULL) {
        return NULL;
    }
    jobject ctim_timespec = makeStructTimespec(env, sb.st_ctim);
    if (ctim_timespec == NULL) {
        return NULL;
    }

    return env->NewObject(g_StructStat, ctor,
            static_cast<jlong>(sb.st_dev), static_cast<jlong>(sb.st_ino),
            static_cast<jint>(sb.st_mode), static_cast<jlong>(sb.st_nlink),
            static_cast<jint>(sb.st_uid), static_cast<jint>(sb.st_gid),
            static_cast<jlong>(sb.st_rdev), static_cast<jlong>(sb.st_size),
            atim_timespec, mtim_timespec, ctim_timespec,
            static_cast<jlong>(sb.st_blksize), static_cast<jlong>(sb.st_blocks));
}

static jobject doStat(JNIEnv* env, jstring javaPath, bool isLstat) {
    ScopedRealUtf8Chars path(env, javaPath);
    if (path.c_str() == NULL) {
        return NULL;
    }
    struct stat64 sb;
    int rc = isLstat ? TEMP_FAILURE_RETRY(lstat64(path.c_str(), &sb))
                     : TEMP_FAILURE_RETRY(stat64(path.c_str(), &sb));
    if (rc == -1) {
        throwErrnoException(env, isLstat ? "lstat" : "stat");
        return NULL;
    }
    return makeStructStat(env, sb);
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

static jobject nFstat(JNIEnv* env, jobject, jint fd) {
    struct stat64 sb;
    int rc = TEMP_FAILURE_RETRY(fstat64(fd, &sb));
    if (rc == -1) {
        throwErrnoException(env, "fstat");
        return NULL;
    }
    return makeStructStat(env, sb);
}

static jobject Linux_lstat(JNIEnv* env, jobject, jstring javaPath) {
    return doStat(env, javaPath, true);
}

static jobject Linux_stat(JNIEnv* env, jobject, jstring javaPath) {
    return doStat(env, javaPath, false);
}

static jint Linux_open(JNIEnv* env, jobject, jstring javaPath, jint flags, jint mode) {
    ScopedRealUtf8Chars path(env, javaPath);
    if (path.c_str() == NULL) {
        return -1;
    }
    return throwIfMinusOne(env, "open", TEMP_FAILURE_RETRY(open(path.c_str(), flags, mode)));
}

static void Linux_setenv(JNIEnv* env, jobject, jstring javaName, jstring javaValue,
        jboolean overwrite) {
    ScopedRealUtf8Chars name(env, javaName);
    if (name.c_str() == NULL) {
        jniThrowNullPointerException(env);
    }
    ScopedRealUtf8Chars value(env, javaValue);
    if (value.c_str() == NULL) {
        jniThrowNullPointerException(env);
    }
    throwIfMinusOne(env, "setenv", setenv(name.c_str(), value.c_str(), overwrite ? 1 : 0));
}

static jint Linux_getpid(JNIEnv* env, jobject) {
    return getpid();
}

static jint Linux_gettid(JNIEnv* env, jobject) {
    // gettid(2() was added in glibc 2.30 but Android uses an older version in prebuilt.
    return syscall(__NR_gettid);
}

static jstring getIcuDataName(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF(U_ICUDATA_NAME);
}

// ---- Registration ----

extern void register_android_system_OsConstants(JNIEnv* env);

static const JNINativeMethod sMethods[] =
{
    { "applyFreeFunction", "(JJ)V", (void*)nApplyFreeFunction },
    { "nFcntlInt", "(III)I", (void*)nFcntlInt },
    { "nLseek", "(IJI)J", (void*)nLseek },
    { "nPipe2", "(I)[I", (void*)nPipe2 },
    { "nDup", "(I)I", (void*)nDup },
    { "nFstat", "(I)Landroid/system/StructStat;", (void*)nFstat },
    { "lstat", "(Ljava/lang/String;)Landroid/system/StructStat;", (void*)Linux_lstat },
    { "stat", "(Ljava/lang/String;)Landroid/system/StructStat;", (void*)Linux_stat },
    { "nOpen", "(Ljava/lang/String;II)I", (void*)Linux_open },
    { "setenv", "(Ljava/lang/String;Ljava/lang/String;Z)V", (void*)Linux_setenv },
    { "getpid", "()I", (void*)Linux_getpid },
    { "gettid", "()I", (void*)Linux_gettid },
    { "getIcuDataName", "()Ljava/lang/String;", (void*)getIcuDataName },
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    ALOGV("%s: JNI_OnLoad", __FILE__);

    JNIEnv* env = GetJNIEnvOrDie(vm);
    g_StructStat = FindGlobalClassOrDie(env, "android/system/StructStat");
    g_StructTimespecClass = FindGlobalClassOrDie(env, "android/system/StructTimespec");

    jint res = jniRegisterNativeMethods(env, kRuntimeNative, sMethods, NELEM(sMethods));
    if (res < 0) {
        return res;
    }

    register_android_system_OsConstants(env);

    return JNI_VERSION_1_4;
}
