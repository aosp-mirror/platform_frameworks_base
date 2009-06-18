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

#include <utils/BackupHelpers.h>

namespace android
{

// java.io.FileDescriptor
static jfieldID s_descriptorField = 0;

static int
ctor(JNIEnv* env, jobject clazz)
{
    return (int)new RestoreHelperBase();
}

static void
dtor(JNIEnv* env, jobject clazz, jint ptr)
{
    delete (RestoreHelperBase*)ptr;
}

static int
writeFile_native(JNIEnv* env, jobject clazz, jint ptr, jstring filenameObj, int backupReaderPtr)
{
    int err;
    RestoreHelperBase* restore = (RestoreHelperBase*)ptr;
    BackupDataReader* reader = (BackupDataReader*)backupReaderPtr;
    char const* filename;

    filename = env->GetStringUTFChars(filenameObj, NULL);

    err = restore->WriteFile(String8(filename), reader);

    env->ReleaseStringUTFChars(filenameObj, filename);

    return err;
}

static int
writeSnapshot_native(JNIEnv* env, jobject clazz, jint ptr, jobject fileDescriptor)
{
    int err;

    RestoreHelperBase* restore = (RestoreHelperBase*)ptr;
    int fd = env->GetIntField(fileDescriptor, s_descriptorField);

    err = restore->WriteSnapshot(fd);

    return err;
}

static const JNINativeMethod g_methods[] = {
    { "ctor", "()I", (void*)ctor },
    { "dtor", "(I)V", (void*)dtor },
    { "writeFile_native", "(ILjava/lang/String;I)I", (void*)writeFile_native },
    { "writeSnapshot_native", "(ILjava/io/FileDescriptor;)I", (void*)writeSnapshot_native },
};

int register_android_backup_RestoreHelperBase(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass("java/io/FileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.io.FileDescriptor");
    s_descriptorField = env->GetFieldID(clazz, "descriptor", "I");
    LOG_FATAL_IF(s_descriptorField == NULL,
            "Unable to find descriptor field in java.io.FileDescriptor");
    
    return AndroidRuntime::registerNativeMethods(env, "android/backup/RestoreHelperBase",
            g_methods, NELEM(g_methods));
}

}
