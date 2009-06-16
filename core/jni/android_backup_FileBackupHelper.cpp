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
performBackup_native(JNIEnv* env, jobject clazz, jobject oldState, int data,
        jobject newState, jobjectArray files, jobjectArray keys)
{
    int err;

    // all parameters have already been checked against null
    int oldStateFD = oldState != NULL ? env->GetIntField(oldState, s_descriptorField) : -1;
    int newStateFD = env->GetIntField(newState, s_descriptorField);
    BackupDataWriter* dataStream = (BackupDataWriter*)data;

    const int fileCount = env->GetArrayLength(files);
    char const** filesUTF = (char const**)malloc(sizeof(char*)*fileCount);
    for (int i=0; i<fileCount; i++) {
        filesUTF[i] = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(files, i), NULL);
    }

    const int keyCount = env->GetArrayLength(keys);
    char const** keysUTF = (char const**)malloc(sizeof(char*)*keyCount);
    for (int i=0; i<keyCount; i++) {
        keysUTF[i] = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(keys, i), NULL);
    }

    err = back_up_files(oldStateFD, dataStream, newStateFD, filesUTF, keysUTF, fileCount);

    for (int i=0; i<fileCount; i++) {
        env->ReleaseStringUTFChars((jstring)env->GetObjectArrayElement(files, i), filesUTF[i]);
    }
    free(filesUTF);

    for (int i=0; i<keyCount; i++) {
        env->ReleaseStringUTFChars((jstring)env->GetObjectArrayElement(keys, i), keysUTF[i]);
    }
    free(keysUTF);

    return err;
}

static const JNINativeMethod g_methods[] = {
    { "performBackup_native",
       "(Ljava/io/FileDescriptor;ILjava/io/FileDescriptor;[Ljava/lang/String;[Ljava/lang/String;)I",
       (void*)performBackup_native },
};

int register_android_backup_FileBackupHelper(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass("java/io/FileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.io.FileDescriptor");
    s_descriptorField = env->GetFieldID(clazz, "descriptor", "I");
    LOG_FATAL_IF(s_descriptorField == NULL,
            "Unable to find descriptor field in java.io.FileDescriptor");
    
    return AndroidRuntime::registerNativeMethods(env, "android/backup/FileBackupHelper",
            g_methods, NELEM(g_methods));
}

}
