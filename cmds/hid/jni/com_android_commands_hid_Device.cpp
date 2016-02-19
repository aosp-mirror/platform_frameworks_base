/*
 * Copyright (C) 2015 The Android Open Source Project
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

#define LOG_TAG "HidCommandDevice"

#include "com_android_commands_hid_Device.h"

#include <linux/uhid.h>

#include <fcntl.h>
#include <cstdio>
#include <cstring>
#include <memory>
#include <unistd.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <android_os_MessageQueue.h>
#include <core_jni_helpers.h>
#include <jni.h>
#include <JNIHelp.h>
#include <ScopedPrimitiveArray.h>
#include <ScopedUtfChars.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/StrongPointer.h>

namespace android {
namespace uhid {

static const char* UHID_PATH = "/dev/uhid";
static const size_t UHID_MAX_NAME_LENGTH = 128;

static struct {
    jmethodID onDeviceOpen;
    jmethodID onDeviceError;
} gDeviceCallbackClassInfo;

static int handleLooperEvents(int /* fd */, int events, void* data) {
    Device* d = reinterpret_cast<Device*>(data);
    return d->handleEvents(events);
}

static void checkAndClearException(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

DeviceCallback::DeviceCallback(JNIEnv* env, jobject callback) :
    mCallbackObject(env->NewGlobalRef(callback)) { }

DeviceCallback::~DeviceCallback() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mCallbackObject);
}

void DeviceCallback::onDeviceError() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbackObject, gDeviceCallbackClassInfo.onDeviceError);
    checkAndClearException(env, "onDeviceError");
}

void DeviceCallback::onDeviceOpen() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbackObject, gDeviceCallbackClassInfo.onDeviceOpen);
    checkAndClearException(env, "onDeviceOpen");
}

Device* Device::open(int32_t id, const char* name, int32_t vid, int32_t pid,
        std::unique_ptr<uint8_t[]> descriptor, size_t descriptorSize,
        std::unique_ptr<DeviceCallback> callback, sp<Looper> looper) {

    int fd = ::open(UHID_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        ALOGE("Failed to open uhid: %s", strerror(errno));
        return nullptr;
    }

    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_CREATE;
    strncpy((char*)ev.u.create.name, name, UHID_MAX_NAME_LENGTH);
    ev.u.create.rd_data = descriptor.get();
    ev.u.create.rd_size = descriptorSize;
    ev.u.create.bus = BUS_BLUETOOTH;
    ev.u.create.vendor = vid;
    ev.u.create.product = pid;
    ev.u.create.version = 0;
    ev.u.create.country = 0;

    errno = 0;
    ssize_t ret = TEMP_FAILURE_RETRY(::write(fd, &ev, sizeof(ev)));
    if (ret < 0 || ret != sizeof(ev)) {
        ::close(fd);
        ALOGE("Failed to create uhid node: %s", strerror(errno));
        return nullptr;
    }

    // Wait for the device to actually be created.
    ret = TEMP_FAILURE_RETRY(::read(fd, &ev, sizeof(ev)));
    if (ret < 0 || ev.type != UHID_START) {
        ::close(fd);
        ALOGE("uhid node failed to start: %s", strerror(errno));
        return nullptr;
    }

    return new Device(id, fd, std::move(callback), looper);
}

Device::Device(int32_t id, int fd, std::unique_ptr<DeviceCallback> callback, sp<Looper> looper) :
            mId(id), mFd(fd), mDeviceCallback(std::move(callback)), mLooper(looper) {
    looper->addFd(fd, 0, Looper::EVENT_INPUT, handleLooperEvents, reinterpret_cast<void*>(this));
}

Device::~Device() {
    mLooper->removeFd(mFd);
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_DESTROY;
    TEMP_FAILURE_RETRY(::write(mFd, &ev, sizeof(ev)));
    ::close(mFd);
    mFd = -1;
}

void Device::sendReport(uint8_t* report, size_t reportSize) {
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_INPUT;
    ev.u.input.size = reportSize;
    memcpy(&ev.u.input.data, report, reportSize);
    ssize_t ret = TEMP_FAILURE_RETRY(::write(mFd, &ev, sizeof(ev)));
    if (ret < 0 || ret != sizeof(ev)) {
        ALOGE("Failed to send hid event: %s", strerror(errno));
    }
}

int Device::handleEvents(int events) {
    if (events & (Looper::EVENT_ERROR | Looper::EVENT_HANGUP)) {
        ALOGE("uhid node was closed or an error occurred. events=0x%x", events);
        mDeviceCallback->onDeviceError();
        return 0;
    }
    struct uhid_event ev;
    ssize_t ret = TEMP_FAILURE_RETRY(::read(mFd, &ev, sizeof(ev)));
    if (ret < 0) {
        ALOGE("Failed to read from uhid node: %s", strerror(errno));
        mDeviceCallback->onDeviceError();
        return 0;
    }

    if (ev.type == UHID_OPEN) {
        mDeviceCallback->onDeviceOpen();
    }

    return 1;
}

} // namespace uhid

std::unique_ptr<uint8_t[]> getData(JNIEnv* env, jbyteArray javaArray, size_t& outSize) {
    ScopedByteArrayRO scopedArray(env, javaArray);
    outSize = scopedArray.size();
    std::unique_ptr<uint8_t[]> data(new uint8_t[outSize]);
    for (size_t i = 0; i < outSize; i++) {
        data[i] = static_cast<uint8_t>(scopedArray[i]);
    }
    return data;
}

static jlong openDevice(JNIEnv* env, jclass /* clazz */, jstring rawName, jint id, jint vid, jint pid,
        jbyteArray rawDescriptor, jobject queue, jobject callback) {
    ScopedUtfChars name(env, rawName);
    if (name.c_str() == nullptr) {
        return 0;
    }

    size_t size;
    std::unique_ptr<uint8_t[]> desc = getData(env, rawDescriptor, size);

    std::unique_ptr<uhid::DeviceCallback> cb(new uhid::DeviceCallback(env, callback));
    sp<Looper> looper = android_os_MessageQueue_getMessageQueue(env, queue)->getLooper();

    uhid::Device* d = uhid::Device::open(
            id, reinterpret_cast<const char*>(name.c_str()), vid, pid,
            std::move(desc), size, std::move(cb), std::move(looper));
    return reinterpret_cast<jlong>(d);
}

static void sendReport(JNIEnv* env, jclass /* clazz */, jlong ptr,jbyteArray rawReport) {
    size_t size;
    std::unique_ptr<uint8_t[]> report = getData(env, rawReport, size);
    uhid::Device* d = reinterpret_cast<uhid::Device*>(ptr);
    if (d) {
        d->sendReport(report.get(), size);
    }
}

static void closeDevice(JNIEnv* /* env */, jclass /* clazz */, jlong ptr) {
    uhid::Device* d = reinterpret_cast<uhid::Device*>(ptr);
    if (d) {
        delete d;
    }
}

static JNINativeMethod sMethods[] = {
    { "nativeOpenDevice",
            "(Ljava/lang/String;III[BLandroid/os/MessageQueue;"
            "Lcom/android/commands/hid/Device$DeviceCallback;)J",
            reinterpret_cast<void*>(openDevice) },
    { "nativeSendReport", "(J[B)V", reinterpret_cast<void*>(sendReport) },
    { "nativeCloseDevice", "(J)V", reinterpret_cast<void*>(closeDevice) },
};

int register_com_android_commands_hid_Device(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "com/android/commands/hid/Device$DeviceCallback");
    uhid::gDeviceCallbackClassInfo.onDeviceOpen =
            GetMethodIDOrDie(env, clazz, "onDeviceOpen", "()V");
    uhid::gDeviceCallbackClassInfo.onDeviceError=
            GetMethodIDOrDie(env, clazz, "onDeviceError", "()V");
    return jniRegisterNativeMethods(env, "com/android/commands/hid/Device",
            sMethods, NELEM(sMethods));
}

} // namespace android

jint JNI_OnLoad(JavaVM* jvm, void*) {
    JNIEnv *env = NULL;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    if (android::register_com_android_commands_hid_Device(env) < 0 ){
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
