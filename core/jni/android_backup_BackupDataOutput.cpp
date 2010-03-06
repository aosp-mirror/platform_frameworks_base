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

static jfieldID s_descriptorField = 0;

static int
ctor_native(JNIEnv* env, jobject clazz, jobject fileDescriptor)
{
    int err;

    int fd = env->GetIntField(fileDescriptor, s_descriptorField);
    if (fd == -1) {
        return NULL;
    }

    return (int)new BackupDataWriter(fd);
}

static void
dtor_native(JNIEnv* env, jobject clazz, int w)
{
    delete (BackupDataWriter*)w;
}

static jint
writeEntityHeader_native(JNIEnv* env, jobject clazz, int w, jstring key, int dataSize)
{
    int err;
    BackupDataWriter* writer = (BackupDataWriter*)w;

    const char* keyUTF = env->GetStringUTFChars(key, NULL);
    if (keyUTF == NULL) {
        return -1;
    }

    err = writer->WriteEntityHeader(String8(keyUTF), dataSize);

    env->ReleaseStringUTFChars(key, keyUTF);

    return err;
}

static jint
writeEntityData_native(JNIEnv* env, jobject clazz, int w, jbyteArray data, int size)
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

    return err;
}

static void
setKeyPrefix_native(JNIEnv* env, jobject clazz, int w, jstring keyPrefixObj)
{
    int err;
    BackupDataWriter* writer = (BackupDataWriter*)w;

    const char* keyPrefixUTF = env->GetStringUTFChars(keyPrefixObj, NULL);
    String8 keyPrefix(keyPrefixUTF ? keyPrefixUTF : "");

    writer->SetKeyPrefix(keyPrefix);

    env->ReleaseStringUTFChars(keyPrefixObj, keyPrefixUTF);
}

static const JNINativeMethod g_methods[] = {
    { "ctor", "(Ljava/io/FileDescriptor;)I", (void*)ctor_native },
    { "dtor", "(I)V", (void*)dtor_native },
    { "writeEntityHeader_native", "(ILjava/lang/String;I)I", (void*)writeEntityHeader_native },
    { "writeEntityData_native", "(I[BI)I", (void*)writeEntityData_native },
    { "setKeyPrefix_native", "(ILjava/lang/String;)V", (void*)setKeyPrefix_native },
};

int register_android_backup_BackupDataOutput(JNIEnv* env)
{
    //LOGD("register_android_backup_BackupDataOutput");

    jclass clazz;

    clazz = env->FindClass("java/io/FileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.io.FileDescriptor");
    s_descriptorField = env->GetFieldID(clazz, "descriptor", "I");
    LOG_FATAL_IF(s_descriptorField == NULL,
            "Unable to find descriptor field in java.io.FileDescriptor");

    return AndroidRuntime::registerNativeMethods(env, "android/app/backup/BackupDataOutput",
            g_methods, NELEM(g_methods));
}

}
