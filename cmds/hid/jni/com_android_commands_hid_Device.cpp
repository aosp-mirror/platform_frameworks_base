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

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <android/looper.h>
#include <android/log.h>

#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#define  LOGW(...)  __android_log_print(ANDROID_LOG_WARN,LOG_TAG,__VA_ARGS__)
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG,LOG_TAG,__VA_ARGS__)
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)

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
        LOGE("An exception was thrown by callback '%s'.", methodName);
        env->ExceptionClear();
    }
}

DeviceCallback::DeviceCallback(JNIEnv* env, jobject callback) :
    mCallbackObject(env->NewGlobalRef(callback)) {
    env->GetJavaVM(&mJavaVM);
 }

DeviceCallback::~DeviceCallback() {
    JNIEnv* env = getJNIEnv();
    env->DeleteGlobalRef(mCallbackObject);
}

void DeviceCallback::onDeviceError() {
    JNIEnv* env = getJNIEnv();
    env->CallVoidMethod(mCallbackObject, gDeviceCallbackClassInfo.onDeviceError);
    checkAndClearException(env, "onDeviceError");
}

void DeviceCallback::onDeviceOpen() {
    JNIEnv* env = getJNIEnv();
    env->CallVoidMethod(mCallbackObject, gDeviceCallbackClassInfo.onDeviceOpen);
    checkAndClearException(env, "onDeviceOpen");
}

JNIEnv* DeviceCallback::getJNIEnv() {
    JNIEnv* env;
    mJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    return env;
}

Device* Device::open(int32_t id, const char* name, int32_t vid, int32_t pid,
        std::unique_ptr<uint8_t[]> descriptor, size_t descriptorSize,
        std::unique_ptr<DeviceCallback> callback) {

    int fd = ::open(UHID_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGE("Failed to open uhid: %s", strerror(errno));
        return nullptr;
    }

    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_CREATE2;
    strncpy((char*)ev.u.create2.name, name, UHID_MAX_NAME_LENGTH);
    memcpy(&ev.u.create2.rd_data, descriptor.get(),
            descriptorSize * sizeof(ev.u.create2.rd_data[0]));
    ev.u.create2.rd_size = descriptorSize;
    ev.u.create2.bus = BUS_BLUETOOTH;
    ev.u.create2.vendor = vid;
    ev.u.create2.product = pid;
    ev.u.create2.version = 0;
    ev.u.create2.country = 0;

    errno = 0;
    ssize_t ret = TEMP_FAILURE_RETRY(::write(fd, &ev, sizeof(ev)));
    if (ret < 0 || ret != sizeof(ev)) {
        ::close(fd);
        LOGE("Failed to create uhid node: %s", strerror(errno));
        return nullptr;
    }

    // Wait for the device to actually be created.
    ret = TEMP_FAILURE_RETRY(::read(fd, &ev, sizeof(ev)));
    if (ret < 0 || ev.type != UHID_START) {
        ::close(fd);
        LOGE("uhid node failed to start: %s", strerror(errno));
        return nullptr;
    }
    return new Device(id, fd, std::move(callback));
}

Device::Device(int32_t id, int fd, std::unique_ptr<DeviceCallback> callback) :
            mId(id), mFd(fd), mDeviceCallback(std::move(callback)) {
    ALooper* aLooper = ALooper_forThread();
    if (aLooper == NULL) {
        LOGE("Could not get ALooper, ALooper_forThread returned NULL");
        aLooper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    }
    ALooper_addFd(aLooper, fd, 0, ALOOPER_EVENT_INPUT, handleLooperEvents,
                  reinterpret_cast<void*>(this));
}

Device::~Device() {
    ALooper* looper = ALooper_forThread();
    if (looper != NULL) {
        ALooper_removeFd(looper, mFd);
    } else {
        LOGE("Could not remove fd, ALooper_forThread() returned NULL!");
    }
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
    ev.type = UHID_INPUT2;
    ev.u.input2.size = reportSize;
    memcpy(&ev.u.input2.data, report, reportSize);
    ssize_t ret = TEMP_FAILURE_RETRY(::write(mFd, &ev, sizeof(ev)));
    if (ret < 0 || ret != sizeof(ev)) {
        LOGE("Failed to send hid event: %s", strerror(errno));
    }
}

int Device::handleEvents(int events) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        LOGE("uhid node was closed or an error occurred. events=0x%x", events);
        mDeviceCallback->onDeviceError();
        return 0;
    }
    struct uhid_event ev;
    ssize_t ret = TEMP_FAILURE_RETRY(::read(mFd, &ev, sizeof(ev)));
    if (ret < 0) {
        LOGE("Failed to read from uhid node: %s", strerror(errno));
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
        jbyteArray rawDescriptor, jobject callback) {
    ScopedUtfChars name(env, rawName);
    if (name.c_str() == nullptr) {
        return 0;
    }

    size_t size;
    std::unique_ptr<uint8_t[]> desc = getData(env, rawDescriptor, size);

    std::unique_ptr<uhid::DeviceCallback> cb(new uhid::DeviceCallback(env, callback));

    uhid::Device* d = uhid::Device::open(
            id, reinterpret_cast<const char*>(name.c_str()), vid, pid,
            std::move(desc), size, std::move(cb));
    return reinterpret_cast<jlong>(d);
}

static void sendReport(JNIEnv* env, jclass /* clazz */, jlong ptr, jbyteArray rawReport) {
    size_t size;
    std::unique_ptr<uint8_t[]> report = getData(env, rawReport, size);
    uhid::Device* d = reinterpret_cast<uhid::Device*>(ptr);
    if (d) {
        d->sendReport(report.get(), size);
    } else {
        LOGE("Could not send report, Device* is null!");
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
            "(Ljava/lang/String;III[B"
            "Lcom/android/commands/hid/Device$DeviceCallback;)J",
            reinterpret_cast<void*>(openDevice) },
    { "nativeSendReport", "(J[B)V", reinterpret_cast<void*>(sendReport) },
    { "nativeCloseDevice", "(J)V", reinterpret_cast<void*>(closeDevice) },
};

int register_com_android_commands_hid_Device(JNIEnv* env) {
    jclass clazz = env->FindClass("com/android/commands/hid/Device$DeviceCallback");
    if (clazz == NULL) {
        LOGE("Unable to find class 'DeviceCallback'");
        return JNI_ERR;
    }
    uhid::gDeviceCallbackClassInfo.onDeviceOpen =
            env->GetMethodID(clazz, "onDeviceOpen", "()V");
    uhid::gDeviceCallbackClassInfo.onDeviceError =
            env->GetMethodID(clazz, "onDeviceError", "()V");
    if (uhid::gDeviceCallbackClassInfo.onDeviceOpen == NULL ||
            uhid::gDeviceCallbackClassInfo.onDeviceError == NULL) {
        LOGE("Unable to obtain onDeviceOpen or onDeviceError methods");
        return JNI_ERR;
    }

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
