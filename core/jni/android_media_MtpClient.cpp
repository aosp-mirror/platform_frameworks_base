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
#include <utils/threads.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include "MtpClient.h"
#include "MtpDevice.h"

namespace android {

// ----------------------------------------------------------------------------

static jmethodID method_deviceAdded;
static jmethodID method_deviceRemoved;
static jfieldID field_context;

class MyClient : public MtpClient {
private:

    enum {
        kDeviceAdded = 1,
        kDeviceRemoved,
    };

    virtual void    deviceAdded(MtpDevice *device);
    virtual void    deviceRemoved(MtpDevice *device);

    bool            reportDeviceAdded(JNIEnv *env, MtpDevice *device);
    bool            reportDeviceRemoved(JNIEnv *env, MtpDevice *device);

    JNIEnv*         mEnv;
    jobject         mClient;
    Mutex           mEventLock;
    Condition       mEventCondition;
    Mutex           mAckLock;
    Condition       mAckCondition;
    int             mEvent;
    MtpDevice*      mEventDevice;

public:
                    MyClient(JNIEnv *env, jobject client);
    virtual         ~MyClient();
    void            waitForEvent(JNIEnv *env);

};

MtpClient* get_client_from_object(JNIEnv* env, jobject javaClient)
{
    return (MtpClient*)env->GetIntField(javaClient, field_context);
}


MyClient::MyClient(JNIEnv *env, jobject client)
    :   mEnv(env),
        mClient(env->NewGlobalRef(client)),
        mEvent(0),
        mEventDevice(NULL)
{
}

MyClient::~MyClient() {
    mEnv->DeleteGlobalRef(mClient);
}


void MyClient::deviceAdded(MtpDevice *device) {
    LOGD("MyClient::deviceAdded\n");
    mAckLock.lock();
    mEventLock.lock();
    mEvent = kDeviceAdded;
    mEventDevice = device;
    mEventCondition.signal();
    mEventLock.unlock();
    mAckCondition.wait(mAckLock);
    mAckLock.unlock();
}

void MyClient::deviceRemoved(MtpDevice *device) {
    LOGD("MyClient::deviceRemoved\n");
    mAckLock.lock();
    mEventLock.lock();
    mEvent = kDeviceRemoved;
    mEventDevice = device;
    mEventCondition.signal();
    mEventLock.unlock();
    mAckCondition.wait(mAckLock);
    mAckLock.unlock();
}

bool MyClient::reportDeviceAdded(JNIEnv *env, MtpDevice *device) {
    const char* name = device->getDeviceName();
    LOGD("MyClient::reportDeviceAdded %s\n", name);

    env->CallVoidMethod(mClient, method_deviceAdded, device->getID());

    return (!env->ExceptionCheck());
}

bool MyClient::reportDeviceRemoved(JNIEnv *env, MtpDevice *device) {
   const char* name = device->getDeviceName();
    LOGD("MyClient::reportDeviceRemoved %s\n", name);

    env->CallVoidMethod(mClient, method_deviceRemoved, device->getID());

    return (!env->ExceptionCheck());
}

void MyClient::waitForEvent(JNIEnv *env) {
    mEventLock.lock();
    mEventCondition.wait(mEventLock);
    mEventLock.unlock();

    switch (mEvent) {
        case kDeviceAdded:
            reportDeviceAdded(env, mEventDevice);
            break;
        case kDeviceRemoved:
            reportDeviceRemoved(env, mEventDevice);
            break;
    }

    mAckLock.lock();
    mAckCondition.signal();
    mAckLock.unlock();
}


// ----------------------------------------------------------------------------

static bool ExceptionCheck(void* env)
{
    return ((JNIEnv *)env)->ExceptionCheck();
}

static void
android_media_MtpClient_setup(JNIEnv *env, jobject thiz)
{
    LOGD("setup\n");
    MyClient* client = new MyClient(env, thiz);
    client->start();
    env->SetIntField(thiz, field_context, (int)client);
}

static void
android_media_MtpClient_finalize(JNIEnv *env, jobject thiz)
{
    LOGD("finalize\n");
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    delete client;
}

static void
android_media_MtpClient_wait_for_event(JNIEnv *env, jobject thiz)
{
    LOGD("wait_for_event\n");
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    client->waitForEvent(env);
}

static jboolean
android_media_MtpClient_delete_object(JNIEnv *env, jobject thiz,
        jint device_id, jint object_id)
{
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    MtpDevice* device = client->getDevice(device_id);
    if (device)
        return device->deleteObject(object_id);
    else
        return NULL;
}

static jint
android_media_MtpClient_get_parent(JNIEnv *env, jobject thiz,
        jint device_id, jint object_id)
{
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    MtpDevice* device = client->getDevice(device_id);
    if (device)
        return device->getParent(object_id);
    else
        return -1;
}

static jint
android_media_MtpClient_get_storage_id(JNIEnv *env, jobject thiz,
        jint device_id, jint object_id)
{
    MyClient *client = (MyClient *)env->GetIntField(thiz, field_context);
    MtpDevice* device = client->getDevice(device_id);
    if (device)
        return device->getStorageID(object_id);
    else
        return -1;
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"native_setup",            "()V",  (void *)android_media_MtpClient_setup},
    {"native_finalize",         "()V",  (void *)android_media_MtpClient_finalize},
    {"native_wait_for_event",   "()V",  (void *)android_media_MtpClient_wait_for_event},
    {"native_delete_object",   "(II)Z", (void *)android_media_MtpClient_delete_object},
    {"native_get_parent",      "(II)I", (void *)android_media_MtpClient_get_parent},
    {"native_get_storage_id",  "(II)I", (void *)android_media_MtpClient_get_storage_id},
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

    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MtpClient", gMethods, NELEM(gMethods));
}

} // namespace android
