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

#include <JNIHelp.h>
#include <ScopedPrimitiveArray.h>

#include <cstring>

#include <android_os_MessageQueue.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <hardware/hdmi_cec.h>
#include <sys/param.h>
#include <utils/Looper.h>
#include <utils/RefBase.h>

namespace android {

static struct {
    jmethodID handleIncomingCecCommand;
    jmethodID handleHotplug;
} gHdmiCecControllerClassInfo;

class HdmiCecController {
public:
    HdmiCecController(hdmi_cec_device_t* device, jobject callbacksObj,
            const sp<Looper>& looper);

    void init();

    // Send message to other device. Note that it runs in IO thread.
    int sendMessage(const cec_message_t& message);
    // Add a logical address to device.
    int addLogicalAddress(cec_logical_address_t address);
    // Clear all logical address registered to the device.
    void clearLogicaladdress();
    // Get physical address of device.
    int getPhysicalAddress();
    // Get CEC version from driver.
    int getVersion();
    // Get vendor id used for vendor command.
    uint32_t getVendorId();
    // Get Port information on all the HDMI ports.
    jobjectArray getPortInfos();
    // Set a flag and its value.
    void setOption(int flag, int value);
    // Set audio return channel status.
    void setAudioReturnChannel(int port, bool flag);
    // Whether to hdmi device is connected to the given port.
    bool isConnected(int port);

    jobject getCallbacksObj() const {
        return mCallbacksObj;
    }

private:
    static const int INVALID_PHYSICAL_ADDRESS = 0xFFFF;
    static void onReceived(const hdmi_event_t* event, void* arg);

    hdmi_cec_device_t* mDevice;
    jobject mCallbacksObj;
    sp<Looper> mLooper;
};

// RefBase wrapper for hdmi_event_t. As hdmi_event_t coming from HAL
// may keep its own lifetime, we need to copy it in order to delegate
// it to service thread.
class CecEventWrapper : public LightRefBase<CecEventWrapper> {
public:
    CecEventWrapper(const hdmi_event_t& event) {
        // Copy message.
        switch (event.type) {
        case HDMI_EVENT_CEC_MESSAGE:
            mEvent.cec.initiator = event.cec.initiator;
            mEvent.cec.destination = event.cec.destination;
            mEvent.cec.length = event.cec.length;
            std::memcpy(mEvent.cec.body, event.cec.body, event.cec.length);
            break;
        case HDMI_EVENT_HOT_PLUG:
            mEvent.hotplug.connected = event.hotplug.connected;
            mEvent.hotplug.port_id = event.hotplug.port_id;
            break;
        default:
            // TODO: add more type whenever new type is introduced.
            break;
        }
    }

    const cec_message_t& cec() const {
        return mEvent.cec;
    }

    const hotplug_event_t& hotplug() const {
        return mEvent.hotplug;
    }

    virtual ~CecEventWrapper() {}

private:
    hdmi_event_t mEvent;
};

// Handler class to delegate incoming message to service thread.
class HdmiCecEventHandler : public MessageHandler {
public:
    HdmiCecEventHandler(HdmiCecController* controller, const sp<CecEventWrapper>& event)
        : mController(controller),
          mEventWrapper(event) {
    }

    virtual ~HdmiCecEventHandler() {}

    void handleMessage(const Message& message) {
        switch (message.what) {
        case HDMI_EVENT_CEC_MESSAGE:
            propagateCecCommand(mEventWrapper->cec());
            break;
        case HDMI_EVENT_HOT_PLUG:
            propagateHotplugEvent(mEventWrapper->hotplug());
            break;
        default:
            // TODO: add more type whenever new type is introduced.
            break;
        }
    }

private:
    // Propagate the message up to Java layer.
    void propagateCecCommand(const cec_message_t& message) {
        jint srcAddr = message.initiator;
        jint dstAddr = message.destination;
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        jbyteArray body = env->NewByteArray(message.length);
        const jbyte* bodyPtr = reinterpret_cast<const jbyte *>(message.body);
        env->SetByteArrayRegion(body, 0, message.length, bodyPtr);

        env->CallVoidMethod(mController->getCallbacksObj(),
                gHdmiCecControllerClassInfo.handleIncomingCecCommand, srcAddr,
                dstAddr, body);
        env->DeleteLocalRef(body);

        checkAndClearExceptionFromCallback(env, __FUNCTION__);
    }

    void propagateHotplugEvent(const hotplug_event_t& event) {
        // Note that this method should be called in service thread.
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        jint port = event.port_id;
        jboolean connected = (jboolean) event.connected;
        env->CallVoidMethod(mController->getCallbacksObj(),
                gHdmiCecControllerClassInfo.handleHotplug, port, connected);

        checkAndClearExceptionFromCallback(env, __FUNCTION__);
    }

    // static
    static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
        if (env->ExceptionCheck()) {
            ALOGE("An exception was thrown by callback '%s'.", methodName);
            LOGE_EX(env);
            env->ExceptionClear();
        }
    }

    HdmiCecController* mController;
    sp<CecEventWrapper> mEventWrapper;
};

HdmiCecController::HdmiCecController(hdmi_cec_device_t* device,
        jobject callbacksObj, const sp<Looper>& looper) :
    mDevice(device),
    mCallbacksObj(callbacksObj),
    mLooper(looper) {
}

void HdmiCecController::init() {
    mDevice->register_event_callback(mDevice, HdmiCecController::onReceived, this);
}

int HdmiCecController::sendMessage(const cec_message_t& message) {
    // TODO: propagate send_message's return value.
    return mDevice->send_message(mDevice, &message);
}

int HdmiCecController::addLogicalAddress(cec_logical_address_t address) {
    return mDevice->add_logical_address(mDevice, address);
}

void HdmiCecController::clearLogicaladdress() {
    mDevice->clear_logical_address(mDevice);
}

int HdmiCecController::getPhysicalAddress() {
    uint16_t addr;
    if (!mDevice->get_physical_address(mDevice, &addr)) {
        return addr;
    }
    return INVALID_PHYSICAL_ADDRESS;
}

int HdmiCecController::getVersion() {
    int version = 0;
    mDevice->get_version(mDevice, &version);
    return version;
}

uint32_t HdmiCecController::getVendorId() {
    uint32_t vendorId = 0;
    mDevice->get_vendor_id(mDevice, &vendorId);
    return vendorId;
}

jobjectArray HdmiCecController::getPortInfos() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jclass hdmiPortInfo = env->FindClass("android/hardware/hdmi/HdmiPortInfo");
    if (hdmiPortInfo == NULL) {
        return NULL;
    }
    jmethodID ctor = env->GetMethodID(hdmiPortInfo, "<init>", "(IIIZZZ)V");
    if (ctor == NULL) {
        return NULL;
    }
    hdmi_port_info* ports;
    int numPorts;
    mDevice->get_port_info(mDevice, &ports, &numPorts);
    jobjectArray res = env->NewObjectArray(numPorts, hdmiPortInfo, NULL);

    // MHL support field will be obtained from MHL HAL. Leave it to false.
    jboolean mhlSupported = (jboolean) 0;
    for (int i = 0; i < numPorts; ++i) {
        hdmi_port_info* info = &ports[i];
        jboolean cecSupported = (jboolean) info->cec_supported;
        jboolean arcSupported = (jboolean) info->arc_supported;
        jobject infoObj = env->NewObject(hdmiPortInfo, ctor, info->port_id, info->type,
                info->physical_address, cecSupported, mhlSupported, arcSupported);
        env->SetObjectArrayElement(res, i, infoObj);
    }
    return res;
}

void HdmiCecController::setOption(int flag, int value) {
    mDevice->set_option(mDevice, flag, value);
}

// Set audio return channel status.
  void HdmiCecController::setAudioReturnChannel(int port, bool enabled) {
    mDevice->set_audio_return_channel(mDevice, port, enabled ? 1 : 0);
}

// Whether to hdmi device is connected to the given port.
bool HdmiCecController::isConnected(int port) {
    return mDevice->is_connected(mDevice, port) == HDMI_CONNECTED;
}

// static
void HdmiCecController::onReceived(const hdmi_event_t* event, void* arg) {
    HdmiCecController* controller = static_cast<HdmiCecController*>(arg);
    if (controller == NULL) {
        return;
    }

    sp<CecEventWrapper> spEvent(new CecEventWrapper(*event));
    sp<HdmiCecEventHandler> handler(new HdmiCecEventHandler(controller, spEvent));
    controller->mLooper->sendMessage(handler, event->type);
}

//------------------------------------------------------------------------------
#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

static jlong nativeInit(JNIEnv* env, jclass clazz, jobject callbacksObj,
        jobject messageQueueObj) {
    int err;
    hw_module_t* module;
    err = hw_get_module(HDMI_CEC_HARDWARE_MODULE_ID,
            const_cast<const hw_module_t **>(&module));
    if (err != 0) {
        ALOGE("Error acquiring hardware module: %d", err);
        return 0;
    }

    hw_device_t* device;
    err = module->methods->open(module, HDMI_CEC_HARDWARE_INTERFACE, &device);
    if (err != 0) {
        ALOGE("Error opening hardware module: %d", err);
        return 0;
    }

    sp<MessageQueue> messageQueue =
            android_os_MessageQueue_getMessageQueue(env, messageQueueObj);

    HdmiCecController* controller = new HdmiCecController(
            reinterpret_cast<hdmi_cec_device*>(device),
            env->NewGlobalRef(callbacksObj),
            messageQueue->getLooper());
    controller->init();

    GET_METHOD_ID(gHdmiCecControllerClassInfo.handleIncomingCecCommand, clazz,
            "handleIncomingCecCommand", "(II[B)V");
    GET_METHOD_ID(gHdmiCecControllerClassInfo.handleHotplug, clazz,
            "handleHotplug", "(IZ)V");

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

static jint nativeAddLogicalAddress(JNIEnv* env, jclass clazz, jlong controllerPtr,
        jint logicalAddress) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->addLogicalAddress(static_cast<cec_logical_address_t>(logicalAddress));
}

static void nativeClearLogicalAddress(JNIEnv* env, jclass clazz, jlong controllerPtr) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    controller->clearLogicaladdress();
}

static jint nativeGetPhysicalAddress(JNIEnv* env, jclass clazz, jlong controllerPtr) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->getPhysicalAddress();
}

static jint nativeGetVersion(JNIEnv* env, jclass clazz, jlong controllerPtr) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->getVersion();
}

static jint nativeGetVendorId(JNIEnv* env, jclass clazz, jlong controllerPtr) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->getVendorId();
}

static jobjectArray nativeGetPortInfos(JNIEnv* env, jclass clazz, jlong controllerPtr) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->getPortInfos();
}

static void nativeSetOption(JNIEnv* env, jclass clazz, jlong controllerPtr, jint flag, jint value) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    controller->setOption(flag, value);
}

static void nativeSetAudioReturnChannel(JNIEnv* env, jclass clazz, jlong controllerPtr,
        jint port, jboolean enabled) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    controller->setAudioReturnChannel(port, enabled == JNI_TRUE);
}

static jboolean nativeIsConnected(JNIEnv* env, jclass clazz, jlong controllerPtr, jint port) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->isConnected(port) ? JNI_TRUE : JNI_FALSE ;
}

static JNINativeMethod sMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
      "(Lcom/android/server/hdmi/HdmiCecController;Landroid/os/MessageQueue;)J",
      (void *) nativeInit },
    { "nativeSendCecCommand", "(JII[B)I", (void *) nativeSendCecCommand },
    { "nativeAddLogicalAddress", "(JI)I", (void *) nativeAddLogicalAddress },
    { "nativeClearLogicalAddress", "(J)V", (void *) nativeClearLogicalAddress },
    { "nativeGetPhysicalAddress", "(J)I", (void *) nativeGetPhysicalAddress },
    { "nativeGetVersion", "(J)I", (void *) nativeGetVersion },
    { "nativeGetVendorId", "(J)I", (void *) nativeGetVendorId },
    { "nativeGetPortInfos",
      "(J)[Landroid/hardware/hdmi/HdmiPortInfo;",
      (void *) nativeGetPortInfos },
    { "nativeSetOption", "(JII)V", (void *) nativeSetOption },
    { "nativeSetAudioReturnChannel", "(JIZ)V", (void *) nativeSetAudioReturnChannel },
    { "nativeIsConnected", "(JI)Z", (void *) nativeIsConnected },
};

#define CLASS_PATH "com/android/server/hdmi/HdmiCecController"

int register_android_server_hdmi_HdmiCecController(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, CLASS_PATH, sMethods, NELEM(sMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

}  /* namespace android */
