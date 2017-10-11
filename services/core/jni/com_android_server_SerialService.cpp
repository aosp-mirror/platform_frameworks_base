/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "SerialServiceJNI"
#include "utils/Log.h"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

namespace android
{

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

static jobject android_server_SerialService_open(JNIEnv *env, jobject /* thiz */, jstring path)
{
    const char *pathStr = env->GetStringUTFChars(path, NULL);

    int fd = open(pathStr, O_RDWR | O_NOCTTY);
    if (fd < 0) {
        ALOGE("could not open %s", pathStr);
        env->ReleaseStringUTFChars(path, pathStr);
        return NULL;
    }
    env->ReleaseStringUTFChars(path, pathStr);

    jobject fileDescriptor = jniCreateFileDescriptor(env, fd);
    if (fileDescriptor == NULL) {
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
}


static const JNINativeMethod method_table[] = {
    { "native_open",                "(Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;",
                                    (void*)android_server_SerialService_open },
};

int register_android_server_SerialService(JNIEnv *env)
{
    jclass clazz = env->FindClass("com/android/server/SerialService");
    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/SerialService");
        return -1;
    }

    clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "(Ljava/io/FileDescriptor;)V");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mConstructor == NULL,
                 "Unable to find constructor for android.os.ParcelFileDescriptor");

    return jniRegisterNativeMethods(env, "com/android/server/SerialService",
            method_table, NELEM(method_table));
}

};
