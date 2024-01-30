/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "UinputCommandDevice"

#include "com_android_commands_uinput_Device.h"

#include <android-base/stringprintf.h>
#include <android/looper.h>
#include <android_os_Parcel.h>
#include <fcntl.h>
#include <input/InputEventLabels.h>
#include <inttypes.h>
#include <jni.h>
#include <linux/uinput.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <iterator>
#include <memory>
#include <vector>

namespace android {
namespace uinput {

using src::com::android::commands::uinput::InputAbsInfo;

static constexpr const char* UINPUT_PATH = "/dev/uinput";

static struct {
    jmethodID onDeviceConfigure;
    jmethodID onDeviceVibrating;
    jmethodID onDeviceError;
} gDeviceCallbackClassInfo;

static void checkAndClearException(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        env->ExceptionClear();
    }
}

DeviceCallback::DeviceCallback(JNIEnv* env, jobject callback)
      : mCallbackObject(env->NewGlobalRef(callback)) {
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

void DeviceCallback::onDeviceConfigure(int handle) {
    JNIEnv* env = getJNIEnv();
    env->CallVoidMethod(mCallbackObject, gDeviceCallbackClassInfo.onDeviceConfigure, handle);
    checkAndClearException(env, "onDeviceConfigure");
}

void DeviceCallback::onDeviceVibrating(int value) {
    JNIEnv* env = getJNIEnv();
    env->CallVoidMethod(mCallbackObject, gDeviceCallbackClassInfo.onDeviceVibrating, value);
    checkAndClearException(env, "onDeviceVibrating");
}

JNIEnv* DeviceCallback::getJNIEnv() {
    JNIEnv* env;
    mJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    return env;
}

std::unique_ptr<UinputDevice> UinputDevice::open(int32_t id, const char* name, int32_t vendorId,
                                                 int32_t productId, int32_t versionId, uint16_t bus,
                                                 uint32_t ffEffectsMax, const char* port,
                                                 std::unique_ptr<DeviceCallback> callback) {
    android::base::unique_fd fd(::open(UINPUT_PATH, O_RDWR | O_NONBLOCK | O_CLOEXEC));
    if (!fd.ok()) {
        ALOGE("Failed to open uinput: %s", strerror(errno));
        return nullptr;
    }

    int32_t version;
    ::ioctl(fd, UI_GET_VERSION, &version);
    if (version < 5) {
        ALOGE("Kernel version %d older than 5 is not supported", version);
        return nullptr;
    }

    struct uinput_setup setupDescriptor;
    memset(&setupDescriptor, 0, sizeof(setupDescriptor));
    strlcpy(setupDescriptor.name, name, UINPUT_MAX_NAME_SIZE);
    setupDescriptor.id.version = 1;
    setupDescriptor.id.bustype = bus;
    setupDescriptor.id.vendor = vendorId;
    setupDescriptor.id.product = productId;
    setupDescriptor.id.version = versionId;
    setupDescriptor.ff_effects_max = ffEffectsMax;

    // Request device configuration.
    callback->onDeviceConfigure(fd.get());

    // register the input device
    if (::ioctl(fd, UI_DEV_SETUP, &setupDescriptor)) {
        ALOGE("UI_DEV_SETUP ioctl failed on fd %d: %s.", fd.get(), strerror(errno));
        return nullptr;
    }

    // set the physical port.
    ::ioctl(fd, UI_SET_PHYS, port);

    if (::ioctl(fd, UI_DEV_CREATE) != 0) {
        ALOGE("Unable to create uinput device: %s.", strerror(errno));
        return nullptr;
    }

    // using 'new' to access non-public constructor
    return std::unique_ptr<UinputDevice>(new UinputDevice(id, std::move(fd), std::move(callback)));
}

UinputDevice::UinputDevice(int32_t id, android::base::unique_fd fd,
                           std::unique_ptr<DeviceCallback> callback)
      : mId(id), mFd(std::move(fd)), mDeviceCallback(std::move(callback)) {
    ALooper* aLooper = ALooper_forThread();
    if (aLooper == nullptr) {
        ALOGE("Could not get ALooper, ALooper_forThread returned NULL");
        aLooper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    }
    ALooper_addFd(
            aLooper, mFd, 0, ALOOPER_EVENT_INPUT,
            [](int, int events, void* data) {
                UinputDevice* d = reinterpret_cast<UinputDevice*>(data);
                return d->handleEvents(events);
            },
            reinterpret_cast<void*>(this));
    ALOGI("uinput device %d created: version = %d, fd = %d", mId, UINPUT_VERSION, mFd.get());
}

UinputDevice::~UinputDevice() {
    ::ioctl(mFd, UI_DEV_DESTROY);
}

void UinputDevice::injectEvent(uint16_t type, uint16_t code, int32_t value) {
    struct input_event event = {};
    event.type = type;
    event.code = code;
    event.value = value;
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    TIMESPEC_TO_TIMEVAL(&event.time, &ts);

    if (::write(mFd, &event, sizeof(input_event)) < 0) {
        ALOGE("Could not write event %" PRIu16 " %" PRIu16 " with value %" PRId32 " : %s", type,
              code, value, strerror(errno));
    }
}

int UinputDevice::handleEvents(int events) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        ALOGE("uinput node was closed or an error occurred. events=0x%x", events);
        mDeviceCallback->onDeviceError();
        return 0;
    }
    struct input_event ev;
    ssize_t ret = ::read(mFd, &ev, sizeof(ev));
    if (ret < 0) {
        ALOGE("Failed to read from uinput node: %s", strerror(errno));
        mDeviceCallback->onDeviceError();
        return 0;
    }

    switch (ev.type) {
        case EV_UINPUT: {
            if (ev.code == UI_FF_UPLOAD) {
                struct uinput_ff_upload ff_upload;
                ff_upload.request_id = ev.value;
                ::ioctl(mFd, UI_BEGIN_FF_UPLOAD, &ff_upload);
                ff_upload.retval = 0;
                ::ioctl(mFd, UI_END_FF_UPLOAD, &ff_upload);
            } else if (ev.code == UI_FF_ERASE) {
                struct uinput_ff_erase ff_erase;
                ff_erase.request_id = ev.value;
                ::ioctl(mFd, UI_BEGIN_FF_ERASE, &ff_erase);
                ff_erase.retval = 0;
                ::ioctl(mFd, UI_END_FF_ERASE, &ff_erase);
            }
            break;
        }
        case EV_FF: {
            ALOGI("EV_FF effect = %d value = %d", ev.code, ev.value);
            mDeviceCallback->onDeviceVibrating(ev.value);
            break;
        }
        default: {
            ALOGI("Unhandled event type: %" PRIu32, ev.type);
            break;
        }
    }

    return 1;
}

} // namespace uinput

std::vector<int32_t> toVector(JNIEnv* env, jintArray javaArray) {
    std::vector<int32_t> data;
    if (javaArray == nullptr) {
        return data;
    }

    ScopedIntArrayRO scopedArray(env, javaArray);
    size_t size = scopedArray.size();
    data.reserve(size);
    for (size_t i = 0; i < size; i++) {
        data.push_back(static_cast<int32_t>(scopedArray[i]));
    }
    return data;
}

static jlong openUinputDevice(JNIEnv* env, jclass /* clazz */, jstring rawName, jint id,
                              jint vendorId, jint productId, jint versionId, jint bus,
                              jint ffEffectsMax, jstring rawPort, jobject callback) {
    ScopedUtfChars name(env, rawName);
    if (name.c_str() == nullptr) {
        return 0;
    }

    ScopedUtfChars port(env, rawPort);
    std::unique_ptr<uinput::DeviceCallback> cb =
            std::make_unique<uinput::DeviceCallback>(env, callback);

    std::unique_ptr<uinput::UinputDevice> d =
            uinput::UinputDevice::open(id, name.c_str(), vendorId, productId, versionId, bus,
                                       ffEffectsMax, port.c_str(), std::move(cb));
    return reinterpret_cast<jlong>(d.release());
}

static void closeUinputDevice(JNIEnv* /* env */, jclass /* clazz */, jlong ptr) {
    uinput::UinputDevice* d = reinterpret_cast<uinput::UinputDevice*>(ptr);
    if (d != nullptr) {
        delete d;
    }
}

static void injectEvent(JNIEnv* /* env */, jclass /* clazz */, jlong ptr, jint type, jint code,
                        jint value) {
    uinput::UinputDevice* d = reinterpret_cast<uinput::UinputDevice*>(ptr);
    if (d != nullptr) {
        d->injectEvent(static_cast<uint16_t>(type), static_cast<uint16_t>(code),
                       static_cast<int32_t>(value));
    } else {
        ALOGE("Could not inject event, Device* is null!");
    }
}

static void configure(JNIEnv* env, jclass /* clazz */, jint handle, jint code,
                      jintArray rawConfigs) {
    std::vector<int32_t> configs = toVector(env, rawConfigs);
    // Configure uinput device, with user specified code and value.
    for (auto& config : configs) {
        if (::ioctl(static_cast<int>(handle), _IOW(UINPUT_IOCTL_BASE, code, int), config) < 0) {
            ALOGE("Error configuring device (ioctl %d, value 0x%x): %s", code, config,
                  strerror(errno));
        }
    }
}

static void setAbsInfo(JNIEnv* env, jclass /* clazz */, jint handle, jint axisCode,
                       jobject infoObj) {
    Parcel* parcel = parcelForJavaObject(env, infoObj);
    uinput::InputAbsInfo info;

    info.readFromParcel(parcel);

    struct uinput_abs_setup absSetup;
    absSetup.code = axisCode;
    absSetup.absinfo.maximum = info.maximum;
    absSetup.absinfo.minimum = info.minimum;
    absSetup.absinfo.value = info.value;
    absSetup.absinfo.fuzz = info.fuzz;
    absSetup.absinfo.flat = info.flat;
    absSetup.absinfo.resolution = info.resolution;

    ::ioctl(static_cast<int>(handle), UI_ABS_SETUP, &absSetup);
}

static jint getEvdevEventTypeByLabel(JNIEnv* env, jclass /* clazz */, jstring rawLabel) {
    ScopedUtfChars label(env, rawLabel);
    return InputEventLookup::getLinuxEvdevEventTypeByLabel(label.c_str()).value_or(-1);
}

static jint getEvdevEventCodeByLabel(JNIEnv* env, jclass /* clazz */, jint type, jstring rawLabel) {
    ScopedUtfChars label(env, rawLabel);
    return InputEventLookup::getLinuxEvdevEventCodeByLabel(type, label.c_str()).value_or(-1);
}

static jint getEvdevInputPropByLabel(JNIEnv* env, jclass /* clazz */, jstring rawLabel) {
    ScopedUtfChars label(env, rawLabel);
    return InputEventLookup::getLinuxEvdevInputPropByLabel(label.c_str()).value_or(-1);
}

static JNINativeMethod sMethods[] = {
        {"nativeOpenUinputDevice",
         "(Ljava/lang/String;IIIIIILjava/lang/String;"
         "Lcom/android/commands/uinput/Device$DeviceCallback;)J",
         reinterpret_cast<void*>(openUinputDevice)},
        {"nativeInjectEvent", "(JIII)V", reinterpret_cast<void*>(injectEvent)},
        {"nativeConfigure", "(II[I)V", reinterpret_cast<void*>(configure)},
        {"nativeSetAbsInfo", "(IILandroid/os/Parcel;)V", reinterpret_cast<void*>(setAbsInfo)},
        {"nativeCloseUinputDevice", "(J)V", reinterpret_cast<void*>(closeUinputDevice)},
        {"nativeGetEvdevEventTypeByLabel", "(Ljava/lang/String;)I",
         reinterpret_cast<void*>(getEvdevEventTypeByLabel)},
        {"nativeGetEvdevEventCodeByLabel", "(ILjava/lang/String;)I",
         reinterpret_cast<void*>(getEvdevEventCodeByLabel)},
        {"nativeGetEvdevInputPropByLabel", "(Ljava/lang/String;)I",
         reinterpret_cast<void*>(getEvdevInputPropByLabel)},
};

int register_com_android_commands_uinput_Device(JNIEnv* env) {
    jclass clazz = env->FindClass("com/android/commands/uinput/Device$DeviceCallback");
    if (clazz == nullptr) {
        ALOGE("Unable to find class 'DeviceCallback'");
        return JNI_ERR;
    }

    uinput::gDeviceCallbackClassInfo.onDeviceConfigure =
            env->GetMethodID(clazz, "onDeviceConfigure", "(I)V");
    uinput::gDeviceCallbackClassInfo.onDeviceVibrating =
            env->GetMethodID(clazz, "onDeviceVibrating", "(I)V");
    uinput::gDeviceCallbackClassInfo.onDeviceError =
            env->GetMethodID(clazz, "onDeviceError", "()V");
    if (uinput::gDeviceCallbackClassInfo.onDeviceConfigure == nullptr ||
        uinput::gDeviceCallbackClassInfo.onDeviceError == nullptr ||
        uinput::gDeviceCallbackClassInfo.onDeviceVibrating == nullptr) {
        ALOGE("Unable to obtain onDeviceConfigure or onDeviceError or onDeviceVibrating methods");
        return JNI_ERR;
    }
    return jniRegisterNativeMethods(env, "com/android/commands/uinput/Device", sMethods,
                                    NELEM(sMethods));
}

} // namespace android

jint JNI_OnLoad(JavaVM* jvm, void*) {
    JNIEnv* env = nullptr;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    if (android::register_com_android_commands_uinput_Device(env) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
