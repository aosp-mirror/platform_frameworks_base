/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "HdmiCecControllerJni"

#define LOG_NDEBUG 1

#include "JNIHelp.h"
#include "ScopedPrimitiveArray.h"

#include <string>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <hardware/hdmi_cec.h>
#include <sys/param.h>

namespace android {

static struct {
    jmethodID handleIncomingCecCommand;
    jmethodID handleHotplug;
} gHdmiCecControllerClassInfo;

class HdmiCecController {
public:
    HdmiCecController(hdmi_cec_device_t* device, jobject callbacksObj);

    void init();

    // Send message to other device. Note that it runs in IO thread.
    int sendMessage(const cec_message_t& message);

private:
    // Propagate the message up to Java layer.
    void propagateCecCommand(const cec_message_t& message);
    void propagateHotplugEvent(const hotplug_event_t& event);

    static void onReceived(const hdmi_event_t* event, void* arg);
    static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);

    hdmi_cec_device_t* mDevice;
    jobject mCallbacksObj;
};

HdmiCecController::HdmiCecController(hdmi_cec_device_t* device, jobject callbacksObj) :
    mDevice(device),
    mCallbacksObj(callbacksObj) {
}

void HdmiCecController::init() {
    mDevice->register_event_callback(mDevice, HdmiCecController::onReceived, this);
}

void HdmiCecController::propagateCecCommand(const cec_message_t& message) {
    jint srcAddr = message.initiator;
    jint dstAddr = message.destination;
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jbyteArray body = env->NewByteArray(message.length);
    const jbyte* bodyPtr = reinterpret_cast<const jbyte *>(message.body);
    env->SetByteArrayRegion(body, 0, message.length, bodyPtr);

    env->CallVoidMethod(mCallbacksObj,
            gHdmiCecControllerClassInfo.handleIncomingCecCommand,
            srcAddr, dstAddr, body);
    env->DeleteLocalRef(body);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void HdmiCecController::propagateHotplugEvent(const hotplug_event_t& event) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj,
            gHdmiCecControllerClassInfo.handleHotplug, event.connected);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

int HdmiCecController::sendMessage(const cec_message_t& message) {
    // TODO: propagate send_message's return value.
    return mDevice->send_message(mDevice, &message);
}

// static
void HdmiCecController::checkAndClearExceptionFromCallback(JNIEnv* env,
        const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

// static
void HdmiCecController::onReceived(const hdmi_event_t* event, void* arg) {
    HdmiCecController* controller = static_cast<HdmiCecController*>(arg);
    if (controller == NULL) {
        return;
    }

    switch (event->type) {
    case HDMI_EVENT_CEC_MESSAGE:
        controller->propagateCecCommand(event->cec);
        break;
    case HDMI_EVENT_HOT_PLUG:
        controller->propagateHotplugEvent(event->hotplug);
        break;
    default:
        ALOGE("Unsupported event type: %d", event->type);
        break;
    }
}

//------------------------------------------------------------------------------
#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

static jlong nativeInit(JNIEnv* env, jclass clazz, jobject callbacksObj) {
    int err;
    // If use same hardware module id between HdmiCecService and
    // HdmiControlSservice it may conflict and cause abnormal state of HAL.
    // TODO: use HDMI_CEC_HARDWARE_MODULE_ID of hdmi_cec.h for module id
    //       once migration to HdmiControlService is done.
    hw_module_t* module;
    err = hw_get_module("hdmi_cec_module",
            const_cast<const hw_module_t **>(&module));
    if (err != 0) {
        ALOGE("Error acquiring hardware module: %d", err);
        return 0;
    }
    hw_device_t* device;
    // TODO: use HDMI_CEC_HARDWARE_INTERFACE of hdmi_cec.h for interface name
    //       once migration to HdmiControlService is done.
    err = module->methods->open(module, "hdmi_cec_module_hw_if", &device);
    if (err != 0) {
        ALOGE("Error opening hardware module: %d", err);
        return 0;
    }

    HdmiCecController* controller = new HdmiCecController(
            reinterpret_cast<hdmi_cec_device*>(device),
            env->NewGlobalRef(callbacksObj));
    controller->init();

    GET_METHOD_ID(gHdmiCecControllerClassInfo.handleIncomingCecCommand, clazz,
            "handleIncomingCecCommand", "(II[B)V");
    GET_METHOD_ID(gHdmiCecControllerClassInfo.handleHotplug, clazz,
            "handleHotplug", "(Z)V");

    return reinterpret_cast<jlong>(controller);
}

static jint nativeSendCecCommand(JNIEnv* env, jclass clazz, jlong controllerPtr,
        jint srcAddr, jint dstAddr, jbyteArray body) {
    cec_message_t message;
    message.initiator = static_cast<cec_logical_address_t>(srcAddr);
    message.destination = static_cast<cec_logical_address_t>(dstAddr);

    jsize len = env->GetArrayLength(body);
    message.length = MIN(len, CEC_MESSAGE_BODY_MAX_LENGTH);
    ScopedByteArrayRO bodyPtr(env, body);
    std::memcpy(message.body, bodyPtr.get(), len);

    HdmiCecController* controller =
            reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->sendMessage(message);
}

static JNINativeMethod sMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "(Lcom/android/server/hdmi/HdmiCecController;)J",
            (void *) nativeInit },
    { "nativeSendCommand", "(JII[B)I",
            (void *) nativeSendCecCommand },
};

#define CLASS_PATH "com/android/server/hdmi/HdmiCecController"

int register_android_server_hdmi_HdmiCecController(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, CLASS_PATH, sMethods, NELEM(sMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

}  /* namespace android */
