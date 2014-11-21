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
#include "core_jni_helpers.h"

#include <androidfw/BackupHelpers.h>

namespace android
{

static jlong
ctor(JNIEnv* env, jobject clazz)
{
    return (jlong)new RestoreHelperBase();
}

static void
dtor(JNIEnv* env, jobject clazz, jlong ptr)
{
    delete (RestoreHelperBase*)ptr;
}

static jint
performBackup_native(JNIEnv* env, jobject clazz, jobject oldState, jlong data,
        jobject newState, jobjectArray files, jobjectArray keys)
{
    int err;

    // all parameters have already been checked against null
    int oldStateFD = oldState != NULL ? jniGetFDFromFileDescriptor(env, oldState) : -1;
    int newStateFD = jniGetFDFromFileDescriptor(env, newState);
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

    return (jint) err;
}


static jint
writeFile_native(JNIEnv* env, jobject clazz, jlong ptr, jstring filenameObj, jlong backupReaderPtr)
{
    int err;
    RestoreHelperBase* restore = (RestoreHelperBase*)ptr;
    BackupDataReader* reader = (BackupDataReader*)backupReaderPtr;
    char const* filename;

    filename = env->GetStringUTFChars(filenameObj, NULL);

    err = restore->WriteFile(String8(filename), reader);

    env->ReleaseStringUTFChars(filenameObj, filename);

    return (jint) err;
}

static jint
writeSnapshot_native(JNIEnv* env, jobject clazz, jlong ptr, jobject fileDescriptor)
{
    int err;

    RestoreHelperBase* restore = (RestoreHelperBase*)ptr;
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    err = restore->WriteSnapshot(fd);

    return (jint) err;
}

static const JNINativeMethod g_methods[] = {
    { "ctor", "()J", (void*)ctor },
    { "dtor", "(J)V", (void*)dtor },
    { "performBackup_native",
       "(Ljava/io/FileDescriptor;JLjava/io/FileDescriptor;[Ljava/lang/String;[Ljava/lang/String;)I",
       (void*)performBackup_native },
    { "writeFile_native", "(JLjava/lang/String;J)I", (void*)writeFile_native },
    { "writeSnapshot_native", "(JLjava/io/FileDescriptor;)I", (void*)writeSnapshot_native },
};

int register_android_backup_FileBackupHelperBase(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/app/backup/FileBackupHelperBase",
            g_methods, NELEM(g_methods));
}

}
