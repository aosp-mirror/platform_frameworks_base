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

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include <android/hardware/tv/cec/1.0/IHdmiCec.h>
#include <android/hardware/tv/cec/1.0/IHdmiCecCallback.h>
#include <android/hardware/tv/cec/1.0/types.h>
#include <android_os_MessageQueue.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <sys/param.h>
#include <utils/Errors.h>
#include <utils/Looper.h>
#include <utils/RefBase.h>

using ::android::hardware::tv::cec::V1_0::CecLogicalAddress;
using ::android::hardware::tv::cec::V1_0::CecMessage;
using ::android::hardware::tv::cec::V1_0::HdmiPortInfo;
using ::android::hardware::tv::cec::V1_0::HotplugEvent;
using ::android::hardware::tv::cec::V1_0::IHdmiCec;
using ::android::hardware::tv::cec::V1_0::IHdmiCecCallback;
using ::android::hardware::tv::cec::V1_0::MaxLength;
using ::android::hardware::tv::cec::V1_0::OptionKey;
using ::android::hardware::tv::cec::V1_0::Result;
using ::android::hardware::tv::cec::V1_0::SendMessageResult;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_vec;
using ::android::hardware::hidl_string;

namespace android {

static struct {
    jmethodID handleIncomingCecCommand;
    jmethodID handleHotplug;
} gHdmiCecControllerClassInfo;

class HdmiCecController {
public:
    HdmiCecController(sp<IHdmiCec> hdmiCec, jobject callbacksObj, const sp<Looper>& looper);
    ~HdmiCecController();

    // Send message to other device. Note that it runs in IO thread.
    int sendMessage(const CecMessage& message);
    // Add a logical address to device.
    int addLogicalAddress(CecLogicalAddress address);
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
    // Set an option to CEC HAL.
    void setOption(OptionKey key, bool enabled);
    // Informs CEC HAL about the current system language.
    void setLanguage(hidl_string language);
    // Enable audio return channel.
    void enableAudioReturnChannel(int port, bool flag);
    // Whether to hdmi device is connected to the given port.
    bool isConnected(int port);

    jobject getCallbacksObj() const {
        return mCallbacksObj;
    }

private:
    class HdmiCecCallback : public IHdmiCecCallback {
    public:
        HdmiCecCallback(HdmiCecController* controller) : mController(controller) {};
        Return<void> onCecMessage(const CecMessage& event)  override;
        Return<void> onHotplugEvent(const HotplugEvent& event)  override;
    private:
        HdmiCecController* mController;
    };

    static const int INVALID_PHYSICAL_ADDRESS = 0xFFFF;

    sp<IHdmiCec> mHdmiCec;
    jobject mCallbacksObj;
    sp<IHdmiCecCallback> mHdmiCecCallback;
    sp<Looper> mLooper;
};

// Handler class to delegate incoming message to service thread.
class HdmiCecEventHandler : public MessageHandler {
public:
    enum EventType {
        CEC_MESSAGE,
        HOT_PLUG
    };

    HdmiCecEventHandler(HdmiCecController* controller, const CecMessage& cecMessage)
            : mController(controller),
              mCecMessage(cecMessage) {}

    HdmiCecEventHandler(HdmiCecController* controller, const HotplugEvent& hotplugEvent)
            : mController(controller),
              mHotplugEvent(hotplugEvent) {}

    virtual ~HdmiCecEventHandler() {}

    void handleMessage(const Message& message) {
        switch (message.what) {
        case EventType::CEC_MESSAGE:
            propagateCecCommand(mCecMessage);
            break;
        case EventType::HOT_PLUG:
            propagateHotplugEvent(mHotplugEvent);
            break;
        default:
            // TODO: add more type whenever new type is introduced.
            break;
        }
    }

private:
    // Propagate the message up to Java layer.
    void propagateCecCommand(const CecMessage& message) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        jint srcAddr = static_cast<jint>(message.initiator);
        jint dstAddr = static_cast<jint>(message.destination);
        jbyteArray body = env->NewByteArray(message.body.size());
        const jbyte* bodyPtr = reinterpret_cast<const jbyte *>(message.body.data());
        env->SetByteArrayRegion(body, 0, message.body.size(), bodyPtr);
        env->CallVoidMethod(mController->getCallbacksObj(),
                gHdmiCecControllerClassInfo.handleIncomingCecCommand, srcAddr,
                dstAddr, body);
        env->DeleteLocalRef(body);

        checkAndClearExceptionFromCallback(env, __FUNCTION__);
    }

    void propagateHotplugEvent(const HotplugEvent& event) {
        // Note that this method should be called in service thread.
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        jint port = static_cast<jint>(event.portId);
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
    CecMessage mCecMessage;
    HotplugEvent mHotplugEvent;
};

HdmiCecController::HdmiCecController(sp<IHdmiCec> hdmiCec,
        jobject callbacksObj, const sp<Looper>& looper)
        : mHdmiCec(hdmiCec),
          mCallbacksObj(callbacksObj),
          mLooper(looper) {
    mHdmiCecCallback = new HdmiCecCallback(this);
    Return<void> ret = mHdmiCec->setCallback(mHdmiCecCallback);
    if (!ret.isOk()) {
        ALOGE("Failed to set a cec callback.");
    }
}

HdmiCecController::~HdmiCecController() {
    Return<void> ret = mHdmiCec->setCallback(nullptr);
    if (!ret.isOk()) {
        ALOGE("Failed to set a cec callback.");
    }
}

int HdmiCecController::sendMessage(const CecMessage& message) {
    // TODO: propagate send_message's return value.
    Return<SendMessageResult> ret = mHdmiCec->sendMessage(message);
    if (!ret.isOk()) {
        ALOGE("Failed to send CEC message.");
        return static_cast<int>(SendMessageResult::FAIL);
    }
    return static_cast<int>((SendMessageResult) ret);
}

int HdmiCecController::addLogicalAddress(CecLogicalAddress address) {
    Return<Result> ret = mHdmiCec->addLogicalAddress(address);
    if (!ret.isOk()) {
        ALOGE("Failed to add a logical address.");
        return static_cast<int>(Result::FAILURE_UNKNOWN);
    }
    return static_cast<int>((Result) ret);
}

void HdmiCecController::clearLogicaladdress() {
    Return<void> ret = mHdmiCec->clearLogicalAddress();
    if (!ret.isOk()) {
        ALOGE("Failed to clear logical address.");
    }
}

int HdmiCecController::getPhysicalAddress() {
    Result result;
    uint16_t addr;
    Return<void> ret = mHdmiCec->getPhysicalAddress([&result, &addr](Result res, uint16_t paddr) {
            result = res;
            addr = paddr;
        });
    if (!ret.isOk()) {
        ALOGE("Failed to get physical address.");
        return INVALID_PHYSICAL_ADDRESS;
    }
    return result == Result::SUCCESS ? addr : INVALID_PHYSICAL_ADDRESS;
}

int HdmiCecController::getVersion() {
    Return<int32_t> ret = mHdmiCec->getCecVersion();
    if (!ret.isOk()) {
        ALOGE("Failed to get cec version.");
    }
    return ret;
}

uint32_t HdmiCecController::getVendorId() {
    Return<uint32_t> ret = mHdmiCec->getVendorId();
    if (!ret.isOk()) {
        ALOGE("Failed to get vendor id.");
    }
    return ret;
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
    hidl_vec<HdmiPortInfo> ports;
    Return<void> ret = mHdmiCec->getPortInfo([&ports](hidl_vec<HdmiPortInfo> list) {
            ports = list;
        });
    if (!ret.isOk()) {
        ALOGE("Failed to get port information.");
        return NULL;
    }
    jobjectArray res = env->NewObjectArray(ports.size(), hdmiPortInfo, NULL);

    // MHL support field will be obtained from MHL HAL. Leave it to false.
    jboolean mhlSupported = (jboolean) 0;
    for (size_t i = 0; i < ports.size(); ++i) {
        jboolean cecSupported = (jboolean) ports[i].cecSupported;
        jboolean arcSupported = (jboolean) ports[i].arcSupported;
        jobject infoObj = env->NewObject(hdmiPortInfo, ctor, ports[i].portId, ports[i].type,
                ports[i].physicalAddress, cecSupported, mhlSupported, arcSupported);
        env->SetObjectArrayElement(res, i, infoObj);
    }
    return res;
}

void HdmiCecController::setOption(OptionKey key, bool enabled) {
    Return<void> ret = mHdmiCec->setOption(key, enabled);
    if (!ret.isOk()) {
        ALOGE("Failed to set option.");
    }
}

void HdmiCecController::setLanguage(hidl_string language) {
    Return<void> ret = mHdmiCec->setLanguage(language);
    if (!ret.isOk()) {
        ALOGE("Failed to set language.");
    }
}

// Enable audio return channel.
void HdmiCecController::enableAudioReturnChannel(int port, bool enabled) {
    Return<void> ret = mHdmiCec->enableAudioReturnChannel(port, enabled);
    if (!ret.isOk()) {
        ALOGE("Failed to enable/disable ARC.");
    }
}

// Whether to hdmi device is connected to the given port.
bool HdmiCecController::isConnected(int port) {
    Return<bool> ret = mHdmiCec->isConnected(port);
    if (!ret.isOk()) {
        ALOGE("Failed to get connection info.");
    }
    return ret;
}

Return<void> HdmiCecController::HdmiCecCallback::onCecMessage(const CecMessage& message) {
    sp<HdmiCecEventHandler> handler(new HdmiCecEventHandler(mController, message));
    mController->mLooper->sendMessage(handler, HdmiCecEventHandler::EventType::CEC_MESSAGE);
    return Void();
}

Return<void> HdmiCecController::HdmiCecCallback::onHotplugEvent(const HotplugEvent& event) {
    sp<HdmiCecEventHandler> handler(new HdmiCecEventHandler(mController, event));
    mController->mLooper->sendMessage(handler, HdmiCecEventHandler::EventType::HOT_PLUG);
    return Void();
}

//------------------------------------------------------------------------------
#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find method " methodName);

static jlong nativeInit(JNIEnv* env, jclass clazz, jobject callbacksObj,
        jobject messageQueueObj) {
    // TODO(b/31632518)
    sp<IHdmiCec> hdmiCec = IHdmiCec::getService();
    if (hdmiCec == nullptr) {
        ALOGE("Couldn't get tv.cec service.");
        return 0;
    }
    sp<MessageQueue> messageQueue =
            android_os_MessageQueue_getMessageQueue(env, messageQueueObj);

    HdmiCecController* controller = new HdmiCecController(
            hdmiCec,
            env->NewGlobalRef(callbacksObj),
            messageQueue->getLooper());

    GET_METHOD_ID(gHdmiCecControllerClassInfo.handleIncomingCecCommand, clazz,
            "handleIncomingCecCommand", "(II[B)V");
    GET_METHOD_ID(gHdmiCecControllerClassInfo.handleHotplug, clazz,
            "handleHotplug", "(IZ)V");

    return reinterpret_cast<jlong>(controller);
}

static jint nativeSendCecCommand(JNIEnv* env, jclass clazz, jlong controllerPtr,
        jint srcAddr, jint dstAddr, jbyteArray body) {
    CecMessage message;
    message.initiator = static_cast<CecLogicalAddress>(srcAddr);
    message.destination = static_cast<CecLogicalAddress>(dstAddr);

    jsize len = env->GetArrayLength(body);
    ScopedByteArrayRO bodyPtr(env, body);
    size_t bodyLength = MIN(static_cast<size_t>(len),
            static_cast<size_t>(MaxLength::MESSAGE_BODY));
    message.body.resize(bodyLength);
    for (size_t i = 0; i < bodyLength; ++i) {
        message.body[i] = static_cast<uint8_t>(bodyPtr[i]);
    }

    HdmiCecController* controller =
            reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->sendMessage(message);
}

static jint nativeAddLogicalAddress(JNIEnv* env, jclass clazz, jlong controllerPtr,
        jint logicalAddress) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->addLogicalAddress(static_cast<CecLogicalAddress>(logicalAddress));
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
    controller->setOption(static_cast<OptionKey>(flag), value > 0 ? true : false);
}

static void nativeSetLanguage(JNIEnv* env, jclass clazz, jlong controllerPtr, jstring language) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    const char *languageStr = env->GetStringUTFChars(language, NULL);
    controller->setLanguage(languageStr);
    env->ReleaseStringUTFChars(language, languageStr);
}

static void nativeEnableAudioReturnChannel(JNIEnv* env, jclass clazz, jlong controllerPtr,
        jint port, jboolean enabled) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    controller->enableAudioReturnChannel(port, enabled == JNI_TRUE);
}

static jboolean nativeIsConnected(JNIEnv* env, jclass clazz, jlong controllerPtr, jint port) {
    HdmiCecController* controller = reinterpret_cast<HdmiCecController*>(controllerPtr);
    return controller->isConnected(port) ? JNI_TRUE : JNI_FALSE ;
}

static const JNINativeMethod sMethods[] = {
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
    { "nativeSetOption", "(JIZ)V", (void *) nativeSetOption },
    { "nativeSetLanguage", "(JLjava/lang/String;)V", (void *) nativeSetLanguage },
    { "nativeEnableAudioReturnChannel", "(JIZ)V", (void *) nativeEnableAudioReturnChannel },
    { "nativeIsConnected", "(JI)Z", (void *) nativeIsConnected },
};

#define CLASS_PATH "com/android/server/hdmi/HdmiCecController"

int register_android_server_hdmi_HdmiCecController(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, CLASS_PATH, sMethods, NELEM(sMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    (void)res; // Don't scream about unused variable in the LOG_NDEBUG case
    return 0;
}

}  /* namespace android */
