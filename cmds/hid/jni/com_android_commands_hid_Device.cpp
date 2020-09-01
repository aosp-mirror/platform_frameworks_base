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
#include <inttypes.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <memory>

#include <android/log.h>
#include <android/looper.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>

#include <android-base/stringprintf.h>

#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#define  LOGW(...)  __android_log_print(ANDROID_LOG_WARN,LOG_TAG,__VA_ARGS__)
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG,LOG_TAG,__VA_ARGS__)
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)

namespace android {
namespace uhid {

static const char* UHID_PATH = "/dev/uhid";

static struct {
    jmethodID onDeviceOpen;
    jmethodID onDeviceGetReport;
    jmethodID onDeviceOutput;
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

static ScopedLocalRef<jbyteArray> toJbyteArray(JNIEnv* env, const std::vector<uint8_t>& vector) {
    ScopedLocalRef<jbyteArray> array(env, env->NewByteArray(vector.size()));
    if (array.get() == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return array;
    }
    static_assert(sizeof(char) == sizeof(uint8_t));
    env->SetByteArrayRegion(array.get(), 0, vector.size(),
                            reinterpret_cast<const signed char*>(vector.data()));
    return array;
}

static std::string toString(const std::vector<uint8_t>& data) {
    std::string s = "";
    for (uint8_t b : data) {
        s += android::base::StringPrintf("%x ", b);
    }
    return s;
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

void DeviceCallback::onDeviceGetReport(uint32_t requestId, uint8_t reportId) {
    JNIEnv* env = getJNIEnv();
    env->CallVoidMethod(mCallbackObject, gDeviceCallbackClassInfo.onDeviceGetReport,
            requestId, reportId);
    checkAndClearException(env, "onDeviceGetReport");
}

void DeviceCallback::onDeviceOutput(const std::vector<uint8_t>& data) {
    JNIEnv* env = getJNIEnv();
    env->CallVoidMethod(mCallbackObject, gDeviceCallbackClassInfo.onDeviceOutput,
                        toJbyteArray(env, data).get());
    checkAndClearException(env, "onDeviceOutput");
}

JNIEnv* DeviceCallback::getJNIEnv() {
    JNIEnv* env;
    mJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    return env;
}

std::unique_ptr<Device> Device::open(int32_t id, const char* name, int32_t vid, int32_t pid,
                                     uint16_t bus, const std::vector<uint8_t>& descriptor,
                                     std::unique_ptr<DeviceCallback> callback) {
    size_t size = descriptor.size();
    if (size > HID_MAX_DESCRIPTOR_SIZE) {
        LOGE("Received invalid hid report with descriptor size %zu, skipping", size);
        return nullptr;
    }

    android::base::unique_fd fd(::open(UHID_PATH, O_RDWR | O_CLOEXEC));
    if (!fd.ok()) {
        LOGE("Failed to open uhid: %s", strerror(errno));
        return nullptr;
    }

    struct uhid_event ev = {};
    ev.type = UHID_CREATE2;
    strlcpy(reinterpret_cast<char*>(ev.u.create2.name), name, sizeof(ev.u.create2.name));
    std::string uniq = android::base::StringPrintf("Id: %d", id);
    strlcpy(reinterpret_cast<char*>(ev.u.create2.uniq), uniq.c_str(), sizeof(ev.u.create2.uniq));
    memcpy(&ev.u.create2.rd_data, descriptor.data(), size * sizeof(ev.u.create2.rd_data[0]));
    ev.u.create2.rd_size = size;
    ev.u.create2.bus = bus;
    ev.u.create2.vendor = vid;
    ev.u.create2.product = pid;
    ev.u.create2.version = 0;
    ev.u.create2.country = 0;

    errno = 0;
    ssize_t ret = TEMP_FAILURE_RETRY(::write(fd, &ev, sizeof(ev)));
    if (ret < 0 || ret != sizeof(ev)) {
        LOGE("Failed to create uhid node: %s", strerror(errno));
        return nullptr;
    }

    // Wait for the device to actually be created.
    ret = TEMP_FAILURE_RETRY(::read(fd, &ev, sizeof(ev)));
    if (ret < 0 || ev.type != UHID_START) {
        LOGE("uhid node failed to start: %s", strerror(errno));
        return nullptr;
    }
    // using 'new' to access non-public constructor
    return std::unique_ptr<Device>(new Device(id, std::move(fd), std::move(callback)));
}

Device::Device(int32_t id, android::base::unique_fd fd, std::unique_ptr<DeviceCallback> callback)
      : mId(id), mFd(std::move(fd)), mDeviceCallback(std::move(callback)) {
    ALooper* aLooper = ALooper_forThread();
    if (aLooper == NULL) {
        LOGE("Could not get ALooper, ALooper_forThread returned NULL");
        aLooper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    }
    ALooper_addFd(aLooper, mFd, 0, ALOOPER_EVENT_INPUT, handleLooperEvents,
                  reinterpret_cast<void*>(this));
}

Device::~Device() {
    ALooper* looper = ALooper_forThread();
    if (looper != NULL) {
        ALooper_removeFd(looper, mFd);
    } else {
        LOGE("Could not remove fd, ALooper_forThread() returned NULL!");
    }
    struct uhid_event ev = {};
    ev.type = UHID_DESTROY;
    TEMP_FAILURE_RETRY(::write(mFd, &ev, sizeof(ev)));
}

// Send event over the fd.
static void writeEvent(int fd, struct uhid_event& ev, const char* messageType) {
    ssize_t ret = TEMP_FAILURE_RETRY(::write(fd, &ev, sizeof(ev)));
    if (ret < 0 || ret != sizeof(ev)) {
        LOGE("Failed to send uhid_event %s: %s", messageType, strerror(errno));
    }
}

void Device::sendReport(const std::vector<uint8_t>& report) const {
    if (report.size() > UHID_DATA_MAX) {
        LOGE("Received invalid report of size %zu, skipping", report.size());
        return;
    }

    struct uhid_event ev = {};
    ev.type = UHID_INPUT2;
    ev.u.input2.size = report.size();
    memcpy(&ev.u.input2.data, report.data(), report.size() * sizeof(ev.u.input2.data[0]));
    writeEvent(mFd, ev, "UHID_INPUT2");
}

void Device::sendGetFeatureReportReply(uint32_t id, const std::vector<uint8_t>& report) const {
    struct uhid_event ev = {};
    ev.type = UHID_GET_REPORT_REPLY;
    ev.u.get_report_reply.id = id;
    ev.u.get_report_reply.err = report.size() == 0 ? EIO : 0;
    ev.u.get_report_reply.size = report.size();
    memcpy(&ev.u.get_report_reply.data, report.data(),
            report.size() * sizeof(ev.u.get_report_reply.data[0]));
    writeEvent(mFd, ev, "UHID_GET_REPORT_REPLY");
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

    switch (ev.type) {
        case UHID_OPEN: {
            mDeviceCallback->onDeviceOpen();
            break;
        }
        case UHID_GET_REPORT: {
            mDeviceCallback->onDeviceGetReport(ev.u.get_report.id, ev.u.get_report.rnum);
            break;
        }
        case UHID_SET_REPORT: {
            const struct uhid_set_report_req& set_report = ev.u.set_report;
            if (set_report.size > UHID_DATA_MAX) {
                LOGE("SET_REPORT contains too much data: size = %" PRIu16, set_report.size);
                return 0;
            }

            std::vector<uint8_t> data(set_report.data, set_report.data + set_report.size);
            LOGI("Received SET_REPORT: id=%" PRIu32 " rnum=%" PRIu8 " data=%s", set_report.id,
                 set_report.rnum, toString(data).c_str());
            break;
        }
        case UHID_OUTPUT: {
            struct uhid_output_req& output = ev.u.output;
            std::vector<uint8_t> data(output.data, output.data + output.size);
            mDeviceCallback->onDeviceOutput(data);
            break;
        }
        default: {
            LOGI("Unhandled event type: %" PRIu32, ev.type);
            break;
        }
    }

    return 1;
}

} // namespace uhid

std::vector<uint8_t> getData(JNIEnv* env, jbyteArray javaArray) {
    std::vector<uint8_t> data;
    if (javaArray == nullptr) {
        return data;
    }

    ScopedByteArrayRO scopedArray(env, javaArray);
    size_t size = scopedArray.size();
    data.reserve(size);
    for (size_t i = 0; i < size; i++) {
        data.push_back(static_cast<uint8_t>(scopedArray[i]));
    }
    return data;
}

static jlong openDevice(JNIEnv* env, jclass /* clazz */, jstring rawName, jint id, jint vid,
                        jint pid, jint bus, jbyteArray rawDescriptor, jobject callback) {
    ScopedUtfChars name(env, rawName);
    if (name.c_str() == nullptr) {
        return 0;
    }

    std::vector<uint8_t> desc = getData(env, rawDescriptor);

    std::unique_ptr<uhid::DeviceCallback> cb(new uhid::DeviceCallback(env, callback));

    std::unique_ptr<uhid::Device> d =
            uhid::Device::open(id, reinterpret_cast<const char*>(name.c_str()), vid, pid, bus, desc,
                               std::move(cb));
    return reinterpret_cast<jlong>(d.release());
}

static void sendReport(JNIEnv* env, jclass /* clazz */, jlong ptr, jbyteArray rawReport) {
    std::vector<uint8_t> report = getData(env, rawReport);
    uhid::Device* d = reinterpret_cast<uhid::Device*>(ptr);
    if (d) {
        d->sendReport(report);
    } else {
        LOGE("Could not send report, Device* is null!");
    }
}

static void sendGetFeatureReportReply(JNIEnv* env, jclass /* clazz */, jlong ptr, jint id,
        jbyteArray rawReport) {
    uhid::Device* d = reinterpret_cast<uhid::Device*>(ptr);
    if (d) {
        std::vector<uint8_t> report = getData(env, rawReport);
        d->sendGetFeatureReportReply(id, report);
    } else {
        LOGE("Could not send get feature report reply, Device* is null!");
    }
}

static void closeDevice(JNIEnv* /* env */, jclass /* clazz */, jlong ptr) {
    uhid::Device* d = reinterpret_cast<uhid::Device*>(ptr);
    if (d) {
        delete d;
    }
}

static JNINativeMethod sMethods[] = {
        {"nativeOpenDevice",
         "(Ljava/lang/String;IIII[B"
         "Lcom/android/commands/hid/Device$DeviceCallback;)J",
         reinterpret_cast<void*>(openDevice)},
        {"nativeSendReport", "(J[B)V", reinterpret_cast<void*>(sendReport)},
        {"nativeSendGetFeatureReportReply", "(JI[B)V",
         reinterpret_cast<void*>(sendGetFeatureReportReply)},
        {"nativeCloseDevice", "(J)V", reinterpret_cast<void*>(closeDevice)},
};

int register_com_android_commands_hid_Device(JNIEnv* env) {
    jclass clazz = env->FindClass("com/android/commands/hid/Device$DeviceCallback");
    if (clazz == NULL) {
        LOGE("Unable to find class 'DeviceCallback'");
        return JNI_ERR;
    }
    uhid::gDeviceCallbackClassInfo.onDeviceOpen =
            env->GetMethodID(clazz, "onDeviceOpen", "()V");
    uhid::gDeviceCallbackClassInfo.onDeviceGetReport =
            env->GetMethodID(clazz, "onDeviceGetReport", "(II)V");
    uhid::gDeviceCallbackClassInfo.onDeviceOutput =
            env->GetMethodID(clazz, "onDeviceOutput", "([B)V");
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
