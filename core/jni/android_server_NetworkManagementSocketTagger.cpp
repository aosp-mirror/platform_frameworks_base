/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "NMST_QTagUidNative"

#include <android/multinetwork.h>
#include <cutils/qtaguid.h>
#include <errno.h>
#include <fcntl.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include "jni.h"

namespace android {

static jint tagSocketFd(JNIEnv* env, jclass, jobject fileDescriptor,
                        jint tagNum, jint uid) {
  int userFd = jniGetFDFromFileDescriptor(env, fileDescriptor);

  if (env->ExceptionCheck()) {
    ALOGE("Can't get FileDescriptor num");
    return (jint)-1;
  }

  int res = android_tag_socket_with_uid(userFd, tagNum, uid);
  if (res < 0) {
    return (jint)-errno;
  }
  return (jint)res;
}

static jint untagSocketFd(JNIEnv* env, jclass, jobject fileDescriptor) {
  int userFd = jniGetFDFromFileDescriptor(env, fileDescriptor);

  if (env->ExceptionCheck()) {
    ALOGE("Can't get FileDescriptor num");
    return (jint)-1;
  }

  int res = android_untag_socket(userFd);
  if (res < 0) {
    return (jint)-errno;
  }
  return (jint)res;
}

static jint setCounterSet(JNIEnv* env, jclass, jint setNum, jint uid) {
  int res = qtaguid_setCounterSet(setNum, uid);
  if (res < 0) {
    return (jint)-errno;
  }
  return (jint)res;
}

static jint deleteTagData(JNIEnv* env, jclass, jint tagNum, jint uid) {
  int res = qtaguid_deleteTagData(tagNum, uid);
  if (res < 0) {
    return (jint)-errno;
  }
  return (jint)res;
}

static const JNINativeMethod gQTagUidMethods[] = {
  { "native_tagSocketFd", "(Ljava/io/FileDescriptor;II)I", (void*)tagSocketFd},
  { "native_untagSocketFd", "(Ljava/io/FileDescriptor;)I", (void*)untagSocketFd},
  { "native_setCounterSet", "(II)I", (void*)setCounterSet},
  { "native_deleteTagData", "(II)I", (void*)deleteTagData},
};

int register_android_server_NetworkManagementSocketTagger(JNIEnv* env) {
  return jniRegisterNativeMethods(env, "com/android/server/NetworkManagementSocketTagger", gQTagUidMethods, NELEM(gQTagUidMethods));
}

};
