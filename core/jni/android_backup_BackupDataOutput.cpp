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

#define LOG_TAG "FileBackupHelper_native"
#include <utils/Log.h>

#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>

#include <utils/backup_helpers.h>

namespace android
{

static jfieldID s_descriptorField = 0;

static int
ctor_native(JNIEnv* env, jobject This, jobject fileDescriptor)
{
    int err;

    int fd = env->GetIntField(fileDescriptor, s_descriptorField);
    if (fd == -1) {
        return NULL;
    }

    return (int)new BackupDataWriter(fd);
}

static void
dtor_native(JNIEnv* env, jobject This, int fd)
{
    delete (BackupDataWriter*)fd;
}

static const JNINativeMethod g_methods[] = {
    { "ctor", "(Ljava/io/FileDescriptor;)I", (void*)ctor_native },
    { "dtor", "(I)V", (void*)dtor_native },
};

int register_android_backup_BackupDataOutput(JNIEnv* env)
{
    LOGD("register_android_backup_BackupDataOutput");

    jclass clazz;

    clazz = env->FindClass("java/io/FileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.io.FileDescriptor");
    s_descriptorField = env->GetFieldID(clazz, "descriptor", "I");
    LOG_FATAL_IF(s_descriptorField == NULL,
            "Unable to find descriptor field in java.io.FileDescriptor");

    return AndroidRuntime::registerNativeMethods(env, "android/backup/BackupDataOutput",
            g_methods, NELEM(g_methods));
}

}
