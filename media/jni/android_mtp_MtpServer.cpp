/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "MtpServerJNI"
#include "utils/Log.h"

#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <utils/threads.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "private/android_filesystem_config.h"

#include "MtpServer.h"
#include "MtpStorage.h"

using namespace android;

// MtpServer fields
static jfieldID field_MtpServer_nativeContext;

// MtpStorage fields
static jfieldID field_MtpStorage_storageId;
static jfieldID field_MtpStorage_path;
static jfieldID field_MtpStorage_description;
static jfieldID field_MtpStorage_reserveSpace;
static jfieldID field_MtpStorage_removable;
static jfieldID field_MtpStorage_maxFileSize;

static Mutex sMutex;

// ----------------------------------------------------------------------------

// in android_mtp_MtpDatabase.cpp
extern MtpDatabase* getMtpDatabase(JNIEnv *env, jobject database);

static inline MtpServer* getMtpServer(JNIEnv *env, jobject thiz) {
    return (MtpServer*)env->GetLongField(thiz, field_MtpServer_nativeContext);
}

static void
android_mtp_MtpServer_setup(JNIEnv *env, jobject thiz, jobject javaDatabase, jboolean usePtp)
{
    int fd = open("/dev/mtp_usb", O_RDWR);
    if (fd >= 0) {
        MtpServer* server = new MtpServer(fd, getMtpDatabase(env, javaDatabase),
                usePtp, AID_MEDIA_RW, 0664, 0775);
        env->SetLongField(thiz, field_MtpServer_nativeContext, (jlong)server);
    } else {
        ALOGE("could not open MTP driver, errno: %d", errno);
    }
}

static void
android_mtp_MtpServer_run(JNIEnv *env, jobject thiz)
{
    MtpServer* server = getMtpServer(env, thiz);
    if (server)
        server->run();
    else
        ALOGE("server is null in run");
}

static void
android_mtp_MtpServer_cleanup(JNIEnv *env, jobject thiz)
{
    Mutex::Autolock autoLock(sMutex);

    MtpServer* server = getMtpServer(env, thiz);
    if (server) {
        delete server;
        env->SetLongField(thiz, field_MtpServer_nativeContext, 0);
    } else {
        ALOGE("server is null in cleanup");
    }
}

static void
android_mtp_MtpServer_send_object_added(JNIEnv *env, jobject thiz, jint handle)
{
    Mutex::Autolock autoLock(sMutex);

    MtpServer* server = getMtpServer(env, thiz);
    if (server)
        server->sendObjectAdded(handle);
    else
        ALOGE("server is null in send_object_added");
}

static void
android_mtp_MtpServer_send_object_removed(JNIEnv *env, jobject thiz, jint handle)
{
    Mutex::Autolock autoLock(sMutex);

    MtpServer* server = getMtpServer(env, thiz);
    if (server)
        server->sendObjectRemoved(handle);
    else
        ALOGE("server is null in send_object_removed");
}

static void
android_mtp_MtpServer_send_device_property_changed(JNIEnv *env, jobject thiz, jint property)
{
    Mutex::Autolock autoLock(sMutex);

    MtpServer* server = getMtpServer(env, thiz);
    if (server)
        server->sendDevicePropertyChanged(property);
    else
        ALOGE("server is null in send_object_removed");
}

static void
android_mtp_MtpServer_add_storage(JNIEnv *env, jobject thiz, jobject jstorage)
{
    Mutex::Autolock autoLock(sMutex);

    MtpServer* server = getMtpServer(env, thiz);
    if (server) {
        jint storageID = env->GetIntField(jstorage, field_MtpStorage_storageId);
        jstring path = (jstring)env->GetObjectField(jstorage, field_MtpStorage_path);
        jstring description = (jstring)env->GetObjectField(jstorage, field_MtpStorage_description);
        jlong reserveSpace = env->GetLongField(jstorage, field_MtpStorage_reserveSpace);
        jboolean removable = env->GetBooleanField(jstorage, field_MtpStorage_removable);
        jlong maxFileSize = env->GetLongField(jstorage, field_MtpStorage_maxFileSize);

        const char *pathStr = env->GetStringUTFChars(path, NULL);
        if (pathStr != NULL) {
            const char *descriptionStr = env->GetStringUTFChars(description, NULL);
            if (descriptionStr != NULL) {
                MtpStorage* storage = new MtpStorage(storageID, pathStr, descriptionStr,
                        reserveSpace, removable, maxFileSize);
                server->addStorage(storage);
                env->ReleaseStringUTFChars(path, pathStr);
                env->ReleaseStringUTFChars(description, descriptionStr);
            } else {
                env->ReleaseStringUTFChars(path, pathStr);
            }
        }
    } else {
        ALOGE("server is null in add_storage");
    }
}

static void
android_mtp_MtpServer_remove_storage(JNIEnv *env, jobject thiz, jint storageId)
{
    Mutex::Autolock autoLock(sMutex);

    MtpServer* server = getMtpServer(env, thiz);
    if (server) {
        MtpStorage* storage = server->getStorage(storageId);
        if (storage) {
            server->removeStorage(storage);
            delete storage;
        }
    } else
        ALOGE("server is null in remove_storage");
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMethods[] = {
    {"native_setup",                "(Landroid/mtp/MtpDatabase;Z)V",
                                            (void *)android_mtp_MtpServer_setup},
    {"native_run",                  "()V",  (void *)android_mtp_MtpServer_run},
    {"native_cleanup",              "()V",  (void *)android_mtp_MtpServer_cleanup},
    {"native_send_object_added",    "(I)V", (void *)android_mtp_MtpServer_send_object_added},
    {"native_send_object_removed",  "(I)V", (void *)android_mtp_MtpServer_send_object_removed},
    {"native_send_device_property_changed",  "(I)V",
                                    (void *)android_mtp_MtpServer_send_device_property_changed},
    {"native_add_storage",          "(Landroid/mtp/MtpStorage;)V",
                                            (void *)android_mtp_MtpServer_add_storage},
    {"native_remove_storage",       "(I)V", (void *)android_mtp_MtpServer_remove_storage},
};

int register_android_mtp_MtpServer(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/mtp/MtpStorage");
    if (clazz == NULL) {
        ALOGE("Can't find android/mtp/MtpStorage");
        return -1;
    }
    field_MtpStorage_storageId = env->GetFieldID(clazz, "mStorageId", "I");
    if (field_MtpStorage_storageId == NULL) {
        ALOGE("Can't find MtpStorage.mStorageId");
        return -1;
    }
    field_MtpStorage_path = env->GetFieldID(clazz, "mPath", "Ljava/lang/String;");
    if (field_MtpStorage_path == NULL) {
        ALOGE("Can't find MtpStorage.mPath");
        return -1;
    }
    field_MtpStorage_description = env->GetFieldID(clazz, "mDescription", "Ljava/lang/String;");
    if (field_MtpStorage_description == NULL) {
        ALOGE("Can't find MtpStorage.mDescription");
        return -1;
    }
    field_MtpStorage_reserveSpace = env->GetFieldID(clazz, "mReserveSpace", "J");
    if (field_MtpStorage_reserveSpace == NULL) {
        ALOGE("Can't find MtpStorage.mReserveSpace");
        return -1;
    }
    field_MtpStorage_removable = env->GetFieldID(clazz, "mRemovable", "Z");
    if (field_MtpStorage_removable == NULL) {
        ALOGE("Can't find MtpStorage.mRemovable");
        return -1;
    }
    field_MtpStorage_maxFileSize = env->GetFieldID(clazz, "mMaxFileSize", "J");
    if (field_MtpStorage_maxFileSize == NULL) {
        ALOGE("Can't find MtpStorage.mMaxFileSize");
        return -1;
    }

    clazz = env->FindClass("android/mtp/MtpServer");
    if (clazz == NULL) {
        ALOGE("Can't find android/mtp/MtpServer");
        return -1;
    }
    field_MtpServer_nativeContext = env->GetFieldID(clazz, "mNativeContext", "J");
    if (field_MtpServer_nativeContext == NULL) {
        ALOGE("Can't find MtpServer.mNativeContext");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
                "android/mtp/MtpServer", gMethods, NELEM(gMethods));
}
