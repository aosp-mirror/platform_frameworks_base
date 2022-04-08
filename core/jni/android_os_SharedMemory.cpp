/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "SharedMemory"

#include "core_jni_helpers.h"

#include <cutils/ashmem.h>
#include <utils/Log.h>

#include <nativehelper/jni_macros.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include <algorithm>
#include <errno.h>
#include <limits>
#include <unistd.h>

namespace {

jclass errnoExceptionClass;
jmethodID errnoExceptionCtor;  // MethodID for ErrnoException.<init>(String,I)

void throwErrnoException(JNIEnv* env, const char* functionName, int error) {
    ScopedLocalRef<jstring> detailMessage(env, env->NewStringUTF(functionName));
    if (detailMessage.get() == NULL) {
        // Not really much we can do here. We're probably dead in the water,
        // but let's try to stumble on...
        env->ExceptionClear();
    }

    jobject exception = env->NewObject(errnoExceptionClass,
                                       errnoExceptionCtor,
                                       detailMessage.get(),
                                       error);
    env->Throw(reinterpret_cast<jthrowable>(exception));
}

jobject SharedMemory_nCreate(JNIEnv* env, jobject, jstring jname, jint size) {

    // Name is optional so we can't use ScopedUtfChars for this as it throws NPE on null
    const char* name = jname ? env->GetStringUTFChars(jname, nullptr) : nullptr;

    int fd = ashmem_create_region(name, size);

    // Capture the error, if there is one, before calling ReleaseStringUTFChars
    int err = fd < 0 ? errno : 0;

    if (name) {
        env->ReleaseStringUTFChars(jname, name);
    }

    if (fd < 0) {
        throwErrnoException(env, "SharedMemory_create", err);
        return nullptr;
    }

    return jniCreateFileDescriptor(env, fd);
}

jint SharedMemory_nGetSize(JNIEnv* env, jobject, jobject fileDescriptor) {
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (!ashmem_valid(fd)) {
        return -1;
    }
    size_t size = ashmem_get_size_region(fd);
    return static_cast<jint>(std::min(size, static_cast<size_t>(std::numeric_limits<jint>::max())));
}

jint SharedMemory_nSetProt(JNIEnv* env, jobject, jobject fileDescriptor, jint prot) {
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    int err = 0;
    if (ashmem_set_prot_region(fd, prot)) {
        err = errno;
    }
    return err;
}

const JNINativeMethod methods[] = {
  NATIVE_METHOD(SharedMemory, nCreate, "(Ljava/lang/String;I)Ljava/io/FileDescriptor;"),
  NATIVE_METHOD(SharedMemory, nGetSize, "(Ljava/io/FileDescriptor;)I"),
  NATIVE_METHOD(SharedMemory, nSetProt, "(Ljava/io/FileDescriptor;I)I")
};

} // anonymous namespace

namespace android {

int register_android_os_SharedMemory(JNIEnv* env) {
    errnoExceptionClass =
        MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/system/ErrnoException"));
    errnoExceptionCtor =
        GetMethodIDOrDie(env, errnoExceptionClass, "<init>", "(Ljava/lang/String;I)V");
    return RegisterMethodsOrDie(env, "android/os/SharedMemory", methods, NELEM(methods));
}

} // namespace android
