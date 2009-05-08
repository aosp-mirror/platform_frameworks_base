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

#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>

#include <utils/backup_helpers.h>

namespace android
{

static jfieldID s_descriptorField;

static int
performBackup_native(JNIEnv* env, jstring basePath,
            jobject oldSnapshot, jobject newSnapshot,
            jobject data, jobjectArray files)
{
    int err;

    // all parameters have already been checked against null

    int oldSnapshotFD = env->GetIntField(oldSnapshot, s_descriptorField);
    int newSnapshotFD = env->GetIntField(newSnapshot, s_descriptorField);
    int dataFD = env->GetIntField(data, s_descriptorField);

    char const* basePathUTF = env->GetStringUTFChars(basePath, NULL);
    const int fileCount = env->GetArrayLength(files);
    char const** filesUTF = (char const**)malloc(sizeof(char*)*fileCount);
    for (int i=0; i<fileCount; i++) {
        filesUTF[i] = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(files, i), NULL);
    }

    err = back_up_files(oldSnapshotFD, newSnapshotFD, dataFD, basePathUTF, filesUTF, fileCount);

    for (int i=0; i<fileCount; i++) {
        env->ReleaseStringUTFChars((jstring)env->GetObjectArrayElement(files, i), filesUTF[i]);
    }
    free(filesUTF);
    env->ReleaseStringUTFChars(basePath, basePathUTF);

    return err;
}

static const JNINativeMethod g_methods[] = {
    { "performBackup_native",
        "(Ljava/lang/String;Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;"
        "Ljava/io/FileDescriptor;[Ljava/lang/String;)I",
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
