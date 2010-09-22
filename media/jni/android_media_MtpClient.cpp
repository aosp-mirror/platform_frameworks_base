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

#define LOG_TAG "MtpClientJNI"
#include "utils/Log.h"

#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include "MtpClient.h"
#include "MtpDevice.h"
#include "MtpObjectInfo.h"

using namespace android;

// ----------------------------------------------------------------------------

static jmethodID method_deviceAdded;
static jmethodID method_deviceRemoved;
static jfieldID field_context;

static struct file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
    jfieldID mDescriptor;
} gFileDescriptorOffsets;

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

#ifdef HAVE_ANDROID_OS

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        LOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

class MyClient : public MtpClient {
private:
    virtual void    deviceAdded(MtpDevice *device);
    virtual void    deviceRemoved(MtpDevice *device);

    jobject         mClient;
    MtpDevice*      mEventDevice;

public:
                    MyClient(JNIEnv *env, jobject client);
    void            cleanup(JNIEnv *env);
};

MtpClient* get_client_from_object(JNIEnv* env, jobject javaClient)
{
    return (MtpClient*)env->GetIntField(javaClient, field_context);
}


MyClient::MyClient(JNIEnv *env, jobject client)
    :   mClient(env->NewGlobalRef(client))
{
}

void MyClient::cleanup(JNIEnv *env) {
    env->DeleteGlobalRef(mClient);
}

void MyClient::deviceAdded(MtpDevice *device) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    const char* name = device->getDeviceName();
    LOGD("MyClient::deviceAdded %s\n", name);

    env->CallVoidMethod(mClient, method_deviceAdded, device->getID());

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void MyClient::deviceRemoved(MtpDevice *device) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    const char* name = device->getDeviceName();
    LOGD("MyClient::deviceRemoved %s\n", name);

    env->CallVoidMethod(mClient, method_deviceRemoved, device->getID());

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

#endif // HAVE_ANDROID_OS

// ----------------------------------------------------------------------------

static void
android_media_MtpClient_setup(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    LOGD("setup\n");
    MyClient* client = new MyClient(env, thiz);
    client->start();
    env->SetIntField(thiz, field_context, (int)client);
#endif
}

static void
android_media_MtpClient_finalize(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    LOGD("finalize\n");
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    client->cleanup(env);
    delete client;
    env->SetIntField(thiz, field_context, 0);
#endif
}

static jboolean
android_media_MtpClient_start(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    LOGD("start\n");
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    return client->start();
#else
    return false;
#endif
}

static void
android_media_MtpClient_stop(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    LOGD("stop\n");
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    client->stop();
#endif
}

static jboolean
android_media_MtpClient_delete_object(JNIEnv *env, jobject thiz,
        jint device_id, jint object_id)
{
#ifdef HAVE_ANDROID_OS
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    MtpDevice* device = client->getDevice(device_id);
    if (device)
        return device->deleteObject(object_id);
    else
 #endif
        return NULL;
}

static jint
android_media_MtpClient_get_parent(JNIEnv *env, jobject thiz,
        jint device_id, jint object_id)
{
#ifdef HAVE_ANDROID_OS
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    MtpDevice* device = client->getDevice(device_id);
    if (device)
        return device->getParent(object_id);
    else
#endif
        return -1;
}

static jint
android_media_MtpClient_get_storage_id(JNIEnv *env, jobject thiz,
        jint device_id, jint object_id)
{
 #ifdef HAVE_ANDROID_OS
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    MtpDevice* device = client->getDevice(device_id);
    if (device)
        return device->getStorageID(object_id);
    else
#endif
        return -1;
}

static jobject
android_media_MtpClient_open_file(JNIEnv *env, jobject thiz,
        jint device_id, jint object_id)
{
#ifdef HAVE_ANDROID_OS
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    MtpDevice* device = client->getDevice(device_id);
    if (!device)
        return NULL;

    MtpObjectInfo* info = device->getObjectInfo(object_id);
    if (!info)
        return NULL;
    int object_size = info->mCompressedSize;
    delete info;
    int fd = device->readObject(object_id, object_size);
    if (fd < 0)
        return NULL;

    jobject fileDescriptor = env->NewObject(gFileDescriptorOffsets.mClass,
        gFileDescriptorOffsets.mConstructor);
    if (fileDescriptor != NULL) {
        env->SetIntField(fileDescriptor, gFileDescriptorOffsets.mDescriptor, fd);
    } else {
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
#endif
    return NULL;
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"native_setup",            "()V",  (void *)android_media_MtpClient_setup},
    {"native_finalize",         "()V",  (void *)android_media_MtpClient_finalize},
    {"native_start",            "()Z",  (void *)android_media_MtpClient_start},
    {"native_stop",             "()V",  (void *)android_media_MtpClient_stop},
    {"native_delete_object",   "(II)Z", (void *)android_media_MtpClient_delete_object},
    {"native_get_parent",      "(II)I", (void *)android_media_MtpClient_get_parent},
    {"native_get_storage_id",  "(II)I", (void *)android_media_MtpClient_get_storage_id},
    {"native_open_file",       "(II)Landroid/os/ParcelFileDescriptor;",
                                        (void *)android_media_MtpClient_open_file},
};

static const char* const kClassPathName = "android/media/MtpClient";

int register_android_media_MtpClient(JNIEnv *env)
{
    jclass clazz;

    LOGD("register_android_media_MtpClient\n");

    clazz = env->FindClass("android/media/MtpClient");
    if (clazz == NULL) {
        LOGE("Can't find android/media/MtpClient");
        return -1;
    }
    method_deviceAdded = env->GetMethodID(clazz, "deviceAdded", "(I)V");
    if (method_deviceAdded == NULL) {
        LOGE("Can't find deviceAdded");
        return -1;
    }
    method_deviceRemoved = env->GetMethodID(clazz, "deviceRemoved", "(I)V");
    if (method_deviceRemoved == NULL) {
        LOGE("Can't find deviceRemoved");
        return -1;
    }
    field_context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (field_context == NULL) {
        LOGE("Can't find MtpClient.mNativeContext");
        return -1;
    }

   clazz = env->FindClass("java/io/FileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.io.FileDescriptor");
    gFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "()V");
    gFileDescriptorOffsets.mDescriptor = env->GetFieldID(clazz, "descriptor", "I");
    LOG_FATAL_IF(gFileDescriptorOffsets.mDescriptor == NULL,
                 "Unable to find descriptor field in java.io.FileDescriptor");

   clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "(Ljava/io/FileDescriptor;)V");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mConstructor == NULL,
                 "Unable to find constructor for android.os.ParcelFileDescriptor");

    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MtpClient", gMethods, NELEM(gMethods));
}
