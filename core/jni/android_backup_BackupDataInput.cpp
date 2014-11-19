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

#include "core_jni_helpers.h"

namespace android
{

// android.app.backup.BackupDataInput$EntityHeader
static jfieldID s_keyField = 0;
static jfieldID s_dataSizeField = 0;

static jlong
ctor_native(JNIEnv* env, jobject clazz, jobject fileDescriptor)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (fd == -1) {
        return (jlong)NULL;
    }

    return (jlong)new BackupDataReader(fd);
}

static void
dtor_native(JNIEnv* env, jobject clazz, jlong r)
{
    delete (BackupDataReader*)r;
}

static jint
readNextHeader_native(JNIEnv* env, jobject clazz, jlong r, jobject entity)
{
    int err;
    bool done;
    BackupDataReader* reader = (BackupDataReader*)r;

    int type = 0;

    err = reader->ReadNextHeader(&done, &type);
    if (done) {
        return 1;
    }

    if (err != 0) {
        return err < 0 ? err : -1;
    }

    switch (type) {
    case BACKUP_HEADER_ENTITY_V1:
    {
        String8 key;
        size_t dataSize;
        err = reader->ReadEntityHeader(&key, &dataSize);
        if (err != 0) {
            return err < 0 ? err : -1;
        }
        // TODO: Set the fields in the entity object
        jstring keyStr = env->NewStringUTF(key.string());
        env->SetObjectField(entity, s_keyField, keyStr);
        env->SetIntField(entity, s_dataSizeField, dataSize);
        return 0;
    }
    default:
        ALOGD("Unknown header type: 0x%08x\n", type);
        return -1;
    }

    // done
    return 1;
}

static jint
readEntityData_native(JNIEnv* env, jobject clazz, jlong r, jbyteArray data, jint offset, jint size)
{
    int err;
    BackupDataReader* reader = (BackupDataReader*)r;

    if (env->GetArrayLength(data) < (size+offset)) {
        // size mismatch
        return -1;
    }

    jbyte* dataBytes = env->GetByteArrayElements(data, NULL);
    if (dataBytes == NULL) {
        return -2;
    }

    err = reader->ReadEntityData(dataBytes+offset, size);

    env->ReleaseByteArrayElements(data, dataBytes, 0);

    return err;
}

static jint
skipEntityData_native(JNIEnv* env, jobject clazz, jlong r)
{
    int err;
    BackupDataReader* reader = (BackupDataReader*)r;

    err = reader->SkipEntityData();

    return err;
}

static const JNINativeMethod g_methods[] = {
    { "ctor", "(Ljava/io/FileDescriptor;)J", (void*)ctor_native },
    { "dtor", "(J)V", (void*)dtor_native },
    { "readNextHeader_native", "(JLandroid/app/backup/BackupDataInput$EntityHeader;)I",
            (void*)readNextHeader_native },
    { "readEntityData_native", "(J[BII)I", (void*)readEntityData_native },
    { "skipEntityData_native", "(J)I", (void*)skipEntityData_native },
};

int register_android_backup_BackupDataInput(JNIEnv* env)
{
    //ALOGD("register_android_backup_BackupDataInput");

    jclass clazz = FindClassOrDie(env, "android/app/backup/BackupDataInput$EntityHeader");
    s_keyField = GetFieldIDOrDie(env, clazz, "key", "Ljava/lang/String;");
    s_dataSizeField = GetFieldIDOrDie(env, clazz, "dataSize", "I");

    return RegisterMethodsOrDie(env, "android/app/backup/BackupDataInput", g_methods,
                                NELEM(g_methods));
}

}
