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

#include <androidfw/BackupHelpers.h>

namespace android
{

static jlong
ctor_native(JNIEnv* env, jobject clazz, jobject fileDescriptor)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (fd == -1) {
        return (jlong)NULL;
    }

    return (jlong)new BackupDataWriter(fd);
}

static void
dtor_native(JNIEnv* env, jobject clazz, jlong w)
{
    delete (BackupDataWriter*)w;
}

static jint
writeEntityHeader_native(JNIEnv* env, jobject clazz, jlong w, jstring key, jint dataSize)
{
    int err;
    BackupDataWriter* writer = (BackupDataWriter*)w;

    const char* keyUTF = env->GetStringUTFChars(key, NULL);
    if (keyUTF == NULL) {
        return -1;
    }
    err = writer->WriteEntityHeader(String8(keyUTF), dataSize);

    env->ReleaseStringUTFChars(key, keyUTF);

    return (jint)err;
}

static jint
writeEntityData_native(JNIEnv* env, jobject clazz, jlong w, jbyteArray data, jint size)
{
    int err;
    BackupDataWriter* writer = (BackupDataWriter*)w;

    if (env->GetArrayLength(data) < size) {
        // size mismatch
        return -1;
    }

    jbyte* dataBytes = env->GetByteArrayElements(data, NULL);
    if (dataBytes == NULL) {
        return -1;
    }

    err = writer->WriteEntityData(dataBytes, size);

    env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);

    return (jint)err;
}

static void
setKeyPrefix_native(JNIEnv* env, jobject clazz, jlong w, jstring keyPrefixObj)
{
    int err;
    BackupDataWriter* writer = (BackupDataWriter*)w;

    const char* keyPrefixUTF = env->GetStringUTFChars(keyPrefixObj, NULL);
    String8 keyPrefix(keyPrefixUTF ? keyPrefixUTF : "");

    writer->SetKeyPrefix(keyPrefix);

    env->ReleaseStringUTFChars(keyPrefixObj, keyPrefixUTF);
}

static const JNINativeMethod g_methods[] = {
    { "ctor", "(Ljava/io/FileDescriptor;)J", (void*)ctor_native },
    { "dtor", "(J)V", (void*)dtor_native },
    { "writeEntityHeader_native", "(JLjava/lang/String;I)I", (void*)writeEntityHeader_native },
    { "writeEntityData_native", "(J[BI)I", (void*)writeEntityData_native },
    { "setKeyPrefix_native", "(JLjava/lang/String;)V", (void*)setKeyPrefix_native },
};

int register_android_backup_BackupDataOutput(JNIEnv* env)
{
    //ALOGD("register_android_backup_BackupDataOutput");
    return AndroidRuntime::registerNativeMethods(env, "android/app/backup/BackupDataOutput",
            g_methods, NELEM(g_methods));
}

}
