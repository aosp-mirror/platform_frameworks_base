/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <nativehelper/ScopedUtfChars.h>
#include "jni.h"

#include "core_jni_helpers.h"

namespace android {

static jint com_android_internal_os_AtomicDirectory_getDirectoryFd(JNIEnv* env,
        jobject /*clazz*/, jstring path) {
    ScopedUtfChars path8(env, path);
    if (path8.c_str() == NULL) {
        ALOGE("Invalid path: %s", path8.c_str());
        return -1;
    }
    int fd;
    if ((fd = TEMP_FAILURE_RETRY(open(path8.c_str(), O_DIRECTORY | O_RDONLY))) == -1) {
        ALOGE("Cannot open directory %s, error: %s\n", path8.c_str(), strerror(errno));
        return -1;
    }
    return fd;
}

static void com_android_internal_os_AtomicDirectory_fsyncDirectoryFd(JNIEnv* env,
        jobject /*clazz*/, jint fd) {
    if (TEMP_FAILURE_RETRY(fsync(fd)) == -1) {
        ALOGE("Cannot fsync directory %d, error: %s\n", fd, strerror(errno));
    }
}

/*
 * JNI registration.
 */
static const JNINativeMethod gRegisterMethods[] = {
    /* name, signature, funcPtr */
    { "fsyncDirectoryFd",
      "(I)V",
       (void*) com_android_internal_os_AtomicDirectory_fsyncDirectoryFd
    },
    { "getDirectoryFd",
      "(Ljava/lang/String;)I",
       (void*) com_android_internal_os_AtomicDirectory_getDirectoryFd
    },
};

int register_com_android_internal_os_AtomicDirectory(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/AtomicDirectory",
            gRegisterMethods, NELEM(gRegisterMethods));
}

}; // namespace android
