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

#include "core_jni_helpers.h"
#include "jni.h"
#include <nativehelper/JNIPlatformHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "private/android_filesystem_config.h"

#include "MtpServer.h"
#include "MtpStorage.h"

using namespace android;

static Mutex sMutex;

// MtpServer fields
static jfieldID field_MtpServer_nativeContext;

// MtpStorage fields
static jfieldID field_MtpStorage_storageId;
static jfieldID field_MtpStorage_path;
static jfieldID field_MtpStorage_description;
static jfieldID field_MtpStorage_removable;
static jfieldID field_MtpStorage_maxFileSize;

// Initializer for the jfieldIDs above. This method must be invoked before accessing MtpServer and
// MtpStorage fields.
static void initializeJavaIDs(JNIEnv* env) {
    static std::once_flag sJniInitialized;

    std::call_once(sJniInitialized, [](JNIEnv *env) {
        const jclass storage_clazz = FindClassOrDie(env, "android/mtp/MtpStorage");
        field_MtpStorage_storageId = GetFieldIDOrDie(env, storage_clazz, "mStorageId", "I");
        field_MtpStorage_path =
                GetFieldIDOrDie(env, storage_clazz, "mPath", "Ljava/lang/String;");
        field_MtpStorage_description =
                GetFieldIDOrDie(env, storage_clazz, "mDescription", "Ljava/lang/String;");
        field_MtpStorage_removable = GetFieldIDOrDie(env, storage_clazz, "mRemovable", "Z");
        field_MtpStorage_maxFileSize = GetFieldIDOrDie(env, storage_clazz, "mMaxFileSize", "J");

        const jclass server_clazz = FindClassOrDie(env, "android/mtp/MtpServer");
        field_MtpServer_nativeContext = GetFieldIDOrDie(env, server_clazz, "mNativeContext", "J");
    }, env);
}

// ----------------------------------------------------------------------------

// in android_mtp_MtpDatabase.cpp
extern IMtpDatabase* getMtpDatabase(JNIEnv *env, jobject database);

static inline MtpServer* getMtpServer(JNIEnv *env, jobject thiz) {
    initializeJavaIDs(env);
    return (MtpServer*)env->GetLongField(thiz, field_MtpServer_nativeContext);
}

static void
android_mtp_MtpServer_setup(JNIEnv *env, jobject thiz, jobject javaDatabase, jobject jControlFd,
        jboolean usePtp, jstring deviceInfoManufacturer, jstring deviceInfoModel,
        jstring deviceInfoDeviceVersion, jstring deviceInfoSerialNumber)
{
    initializeJavaIDs(env);

    const char *deviceInfoManufacturerStr = env->GetStringUTFChars(deviceInfoManufacturer, NULL);
    const char *deviceInfoModelStr = env->GetStringUTFChars(deviceInfoModel, NULL);
    const char *deviceInfoDeviceVersionStr = env->GetStringUTFChars(deviceInfoDeviceVersion, NULL);
    const char *deviceInfoSerialNumberStr = env->GetStringUTFChars(deviceInfoSerialNumber, NULL);
    int controlFd = dup(jniGetFDFromFileDescriptor(env, jControlFd));
    MtpServer* server = new MtpServer(getMtpDatabase(env, javaDatabase), controlFd,
            usePtp,
            (deviceInfoManufacturerStr != NULL) ? deviceInfoManufacturerStr : "",
            (deviceInfoModelStr != NULL) ? deviceInfoModelStr : "",
            (deviceInfoDeviceVersionStr != NULL) ? deviceInfoDeviceVersionStr : "",
            (deviceInfoSerialNumberStr != NULL) ? deviceInfoSerialNumberStr : "");
    if (deviceInfoManufacturerStr != NULL) {
        env->ReleaseStringUTFChars(deviceInfoManufacturer, deviceInfoManufacturerStr);
    }
    if (deviceInfoModelStr != NULL) {
        env->ReleaseStringUTFChars(deviceInfoModel, deviceInfoModelStr);
    }
    if (deviceInfoDeviceVersionStr != NULL) {
        env->ReleaseStringUTFChars(deviceInfoDeviceVersion, deviceInfoDeviceVersionStr);
    }
    if (deviceInfoSerialNumberStr != NULL) {
        env->ReleaseStringUTFChars(deviceInfoSerialNumber, deviceInfoSerialNumberStr);
    }
    env->SetLongField(thiz, field_MtpServer_nativeContext, (jlong)server);
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
android_mtp_MtpServer_send_object_info_changed(JNIEnv *env, jobject thiz, jint handle)
{
    Mutex::Autolock autoLock(sMutex);

    MtpServer* server = getMtpServer(env, thiz);
    if (server)
        server->sendObjectInfoChanged(handle);
    else
        ALOGE("server is null in send_object_info_changed");
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
        jboolean removable = env->GetBooleanField(jstorage, field_MtpStorage_removable);
        jlong maxFileSize = env->GetLongField(jstorage, field_MtpStorage_maxFileSize);

        const char *pathStr = env->GetStringUTFChars(path, NULL);
        if (pathStr != NULL) {
            const char *descriptionStr = env->GetStringUTFChars(description, NULL);
            if (descriptionStr != NULL) {
                MtpStorage* storage = new MtpStorage(storageID, pathStr, descriptionStr,
                        removable, maxFileSize);
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
    {"native_setup",                "(Landroid/mtp/MtpDatabase;Ljava/io/FileDescriptor;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                                            (void *)android_mtp_MtpServer_setup},
    {"native_run",                  "()V",  (void *)android_mtp_MtpServer_run},
    {"native_cleanup",              "()V",  (void *)android_mtp_MtpServer_cleanup},
    {"native_send_object_added",    "(I)V", (void *)android_mtp_MtpServer_send_object_added},
    {"native_send_object_removed",  "(I)V", (void *)android_mtp_MtpServer_send_object_removed},
    {"native_send_object_info_changed",  "(I)V", (void *)android_mtp_MtpServer_send_object_info_changed},
    {"native_send_device_property_changed",  "(I)V",
                                    (void *)android_mtp_MtpServer_send_device_property_changed},
    {"native_add_storage",          "(Landroid/mtp/MtpStorage;)V",
                                            (void *)android_mtp_MtpServer_add_storage},
    {"native_remove_storage",       "(I)V", (void *)android_mtp_MtpServer_remove_storage},
};

int register_android_mtp_MtpServer(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env,
                "android/mtp/MtpServer", gMethods, NELEM(gMethods));
}
