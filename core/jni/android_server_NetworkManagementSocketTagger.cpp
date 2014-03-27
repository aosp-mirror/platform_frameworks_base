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
#include <utils/Log.h>

#include "JNIHelp.h"

#include "jni.h"
#include <utils/misc.h>
#include <cutils/qtaguid.h>

#include <errno.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/socket.h>

namespace android {

static jint QTagUid_tagSocketFd(JNIEnv* env, jclass,
                                jobject fileDescriptor,
                                jint tagNum, jint uid) {
  int userFd = jniGetFDFromFileDescriptor(env, fileDescriptor);

  if (env->ExceptionOccurred() != NULL) {
    ALOGE("Can't get FileDescriptor num");
    return (jint)-1;
  }

  int res = qtaguid_tagSocket(userFd, tagNum, uid);
  if (res < 0) {
    return (jint)-errno;
  }
  return (jint)res;
}

static jint QTagUid_untagSocketFd(JNIEnv* env, jclass,
                                  jobject fileDescriptor) {
  int userFd = jniGetFDFromFileDescriptor(env, fileDescriptor);

  if (env->ExceptionOccurred() != NULL) {
    ALOGE("Can't get FileDescriptor num");
    return (jint)-1;
  }

  int res = qtaguid_untagSocket(userFd);
  if (res < 0) {
    return (jint)-errno;
  }
  return (jint)res;
}

static jint QTagUid_setCounterSet(JNIEnv* env, jclass,
                                  jint setNum, jint uid) {

  int res = qtaguid_setCounterSet(setNum, uid);
  if (res < 0) {
    return (jint)-errno;
  }
  return (jint)res;
}

static jint QTagUid_deleteTagData(JNIEnv* env, jclass,
                                  jint tagNum, jint uid) {

  int res = qtaguid_deleteTagData(tagNum, uid);
  if (res < 0) {
    return (jint)-errno;
  }
  return (jint)res;
}

static JNINativeMethod gQTagUidMethods[] = {
  { "native_tagSocketFd", "(Ljava/io/FileDescriptor;II)I", (void*)QTagUid_tagSocketFd},
  { "native_untagSocketFd", "(Ljava/io/FileDescriptor;)I", (void*)QTagUid_untagSocketFd},
  { "native_setCounterSet", "(II)I", (void*)QTagUid_setCounterSet},
  { "native_deleteTagData", "(II)I", (void*)QTagUid_deleteTagData},
};

int register_android_server_NetworkManagementSocketTagger(JNIEnv* env) {
  return jniRegisterNativeMethods(env, "com/android/server/NetworkManagementSocketTagger", gQTagUidMethods, NELEM(gQTagUidMethods));
}

};
