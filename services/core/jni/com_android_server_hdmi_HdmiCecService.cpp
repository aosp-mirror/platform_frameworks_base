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

#define LOG_TAG "HdmiCecJni"

#define LOG_NDEBUG 1

#include "ScopedPrimitiveArray.h"

#include <cstring>
#include <deque>
#include <map>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <hardware/hdmi_cec.h>

namespace android {

static struct {
    jmethodID handleMessage;
    jmethodID handleHotplug;
    jmethodID getActiveSource;
    jmethodID getOsdName;
    jmethodID getLanguage;
} gHdmiCecServiceClassInfo;

#ifndef min
#define min(a, b) ((a) > (b) ? (b) : (a))
#endif

class HdmiCecHandler {
public:
    enum HdmiCecError {
        SUCCESS = 0,
        FAILED = -1
    };

    // Data type to hold a CEC message or internal event data.
    typedef union {
        cec_message_t cec;
        hotplug_event_t hotplug;
    } queue_item_t;

    // Entry used for message queue.
    typedef std::pair<int, const queue_item_t> MessageEntry;

    HdmiCecHandler(hdmi_cec_device_t* device, jobject callbacksObj);

    void initialize();

    // initialize individual logical device.
    int initLogicalDevice(int type);
    void releaseLogicalDevice(int type);

    cec_logical_address_t getLogicalAddress(int deviceType);
    int getDeviceType(cec_logical_address_t addr);
    void queueMessage(const MessageEntry& message);
    void queueOutgoingMessage(const cec_message_t& message);
    void sendReportPhysicalAddress();
    void sendActiveSource(cec_logical_address_t srcAddr);
    void sendInactiveSource(cec_logical_address_t srcAddr);
    void sendImageViewOn(cec_logical_address_t srcAddr);
    void sendTextViewOn(cec_logical_address_t srcAddr);
    void sendGiveDevicePowerStatus(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr);
    void sendFeatureAbort(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr,
            int opcode, int reason);
    void sendCecVersion(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr,
            int version);
    void sendDeviceVendorID(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr);
    void sendGiveDeviceVendorID(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr);
    void sendSetOsdName(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr,
            const char* name, size_t len);
    void sendSetMenuLanguage(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr);

    void sendCecMessage(const cec_message_t& message);

private:
    enum {
        EVENT_TYPE_RX,
        EVENT_TYPE_TX,
        EVENT_TYPE_HOTPLUG,
        EVENT_TYPE_STANDBY
    };

    static const unsigned int MAX_BUFFER_SIZE = 256;
    static const uint16_t INVALID_PHYSICAL_ADDRESS = 0xFFFF;
    static const int INACTIVE_DEVICE_TYPE = -1;

    static void onReceived(const hdmi_event_t* event, void* arg);
    static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);

    void updatePhysicalAddress();
    void dispatchMessage(const MessageEntry& message);
    void processIncomingMessage(const cec_message_t& msg);

    // Check the message before we pass it up to framework. If true, we proceed.
    // otherwise do not propagate it.
    bool precheckMessage(const cec_message_t& msg);

    // Propagate the message up to Java layer.
    void propagateMessage(const cec_message_t& msg);
    void propagateHotplug(bool connected);

    // Handles incoming <Request Active Source> message. If one of logical
    // devices is active, it should reply with <Active Source> message.
    void handleRequestActiveSource();
    void handleGetOsdName(const cec_message_t& msg);
    void handleGiveDeviceVendorID(const cec_message_t& msg);
    void handleGetCECVersion(const cec_message_t& msg);
    void handleGetMenuLanguage(const cec_message_t& msg);

    // Internal thread for message queue handler
    class HdmiThread : public Thread {
    public:
        HdmiThread(HdmiCecHandler* hdmiCecHandler, bool canCallJava) :
            Thread(canCallJava),
            mHdmiCecHandler(hdmiCecHandler) {
        }
    private:
        virtual bool threadLoop() {
            ALOGV("HdmiThread started");
            AutoMutex _l(mHdmiCecHandler->mMessageQueueLock);
            mHdmiCecHandler->mMessageQueueCondition.wait(mHdmiCecHandler->mMessageQueueLock);
            /* Process all messages in the queue */
            while (mHdmiCecHandler->mMessageQueue.size() > 0) {
                MessageEntry entry = mHdmiCecHandler->mMessageQueue.front();
                mHdmiCecHandler->dispatchMessage(entry);
            }
            return true;
        }

        HdmiCecHandler* mHdmiCecHandler;
    };

    // device type -> logical address mapping
    std::map<int, cec_logical_address_t> mLogicalDevices;

    hdmi_cec_device_t* mDevice;
    jobject mCallbacksObj;
    Mutex mLock;
    Mutex mMessageQueueLock;
    Condition mMessageQueueCondition;
    sp<HdmiThread> mMessageQueueHandler;

    std::deque<MessageEntry> mMessageQueue;
    uint16_t mPhysicalAddress;
};


HdmiCecHandler::HdmiCecHandler(hdmi_cec_device_t* device, jobject callbacksObj) :
    mDevice(device),
    mCallbacksObj(callbacksObj) {
}

void HdmiCecHandler::initialize() {
    mDevice->register_event_callback(mDevice, HdmiCecHandler::onReceived, this);
    mMessageQueueHandler = new HdmiThread(this, true /* canCallJava */);
    mMessageQueueHandler->run("MessageHandler");
    updatePhysicalAddress();
}

void HdmiCecHandler::updatePhysicalAddress() {
    uint16_t addr;
    if (!mDevice->get_physical_address(mDevice, &addr)) {
        mPhysicalAddress = addr;
    } else {
        mPhysicalAddress = INVALID_PHYSICAL_ADDRESS;
    }
}

int HdmiCecHandler::initLogicalDevice(int type) {
    cec_logical_address_t addr;
    int res = mDevice->allocate_logical_address(mDevice, type, &addr);

    if (res != 0) {
        ALOGE("Logical Address Allocation failed: %d", res);
    } else {
        ALOGV("Logical Address Allocation success: %d", addr);
        mLogicalDevices.insert(std::pair<int, cec_logical_address_t>(type, addr));
    }
    return addr;
}

void HdmiCecHandler::releaseLogicalDevice(int type) {
    std::map<int, cec_logical_address_t>::iterator it = mLogicalDevices.find(type);
    if (it != mLogicalDevices.end()) {
        mLogicalDevices.erase(it);
    }
    // TODO: remove the address monitored in HAL as well.
}

cec_logical_address_t HdmiCecHandler::getLogicalAddress(int mDevicetype) {
    std::map<int, cec_logical_address_t>::iterator it = mLogicalDevices.find(mDevicetype);
    if (it != mLogicalDevices.end()) {
        return it->second;
    }
    return CEC_ADDR_UNREGISTERED;
}

int HdmiCecHandler::getDeviceType(cec_logical_address_t addr) {
    std::map<int, cec_logical_address_t>::iterator it = mLogicalDevices.begin();
    for (; it != mLogicalDevices.end(); ++it) {
        if (it->second == addr) {
            return it->first;
        }
    }
    return INACTIVE_DEVICE_TYPE;
}

void HdmiCecHandler::queueMessage(const MessageEntry& entry) {
    AutoMutex _l(mMessageQueueLock);
    if (mMessageQueue.size() <=  MAX_BUFFER_SIZE) {
        mMessageQueue.push_back(entry);
        mMessageQueueCondition.signal();
    } else {
        ALOGW("Queue is full! Message dropped.");
    }
}

void HdmiCecHandler::queueOutgoingMessage(const cec_message_t& message) {
    queue_item_t item;
    item.cec = message;
    MessageEntry entry = std::make_pair(EVENT_TYPE_TX, item);
    queueMessage(entry);
}

void HdmiCecHandler::sendReportPhysicalAddress() {
    if (mPhysicalAddress == INVALID_PHYSICAL_ADDRESS) {
        ALOGE("Invalid physical address.");
        return;
    }

    // Report physical address for each logical one hosted in it.
    std::map<int, cec_logical_address_t>::iterator it = mLogicalDevices.begin();
    while (it != mLogicalDevices.end()) {
        cec_message_t msg;
        msg.initiator = it->second;  // logical address
        msg.destination = CEC_ADDR_BROADCAST;
        msg.length = 4;
        msg.body[0] = CEC_MESSAGE_REPORT_PHYSICAL_ADDRESS;
        std::memcpy(msg.body + 1, &mPhysicalAddress, 2);
        msg.body[3] = it->first;  // device type
        queueOutgoingMessage(msg);
        ++it;
    }
}

void HdmiCecHandler::sendActiveSource(cec_logical_address_t srcAddr) {
    if (mPhysicalAddress == INVALID_PHYSICAL_ADDRESS) {
        ALOGE("Error getting physical address.");
        return;
    }
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = CEC_ADDR_BROADCAST;
    msg.length = 3;
    msg.body[0] = CEC_MESSAGE_ACTIVE_SOURCE;
    std::memcpy(msg.body + 1, &mPhysicalAddress, 2);
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendInactiveSource(cec_logical_address_t srcAddr) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = CEC_ADDR_TV;
    msg.length = 3;
    msg.body[0] = CEC_MESSAGE_INACTIVE_SOURCE;
    if (mPhysicalAddress != INVALID_PHYSICAL_ADDRESS) {
        std::memcpy(msg.body + 1, &mPhysicalAddress, 2);
        queueOutgoingMessage(msg);
    }
}

void HdmiCecHandler::sendImageViewOn(cec_logical_address_t srcAddr) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = CEC_ADDR_TV;
    msg.length = 1;
    msg.body[0] = CEC_MESSAGE_IMAGE_VIEW_ON;
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendTextViewOn(cec_logical_address_t srcAddr) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = CEC_ADDR_TV;
    msg.length = 1;
    msg.body[0] = CEC_MESSAGE_TEXT_VIEW_ON;
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendGiveDevicePowerStatus(cec_logical_address_t srcAddr,
        cec_logical_address_t dstAddr) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = dstAddr;
    msg.length = 1;
    msg.body[0] = CEC_MESSAGE_GIVE_DEVICE_POWER_STATUS;
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendFeatureAbort(cec_logical_address_t srcAddr,
        cec_logical_address_t dstAddr, int opcode, int reason) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = dstAddr;
    msg.length = 3;
    msg.body[0] = CEC_MESSAGE_FEATURE_ABORT;
    msg.body[1] = opcode;
    msg.body[2] = reason;
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendCecVersion(cec_logical_address_t srcAddr,
        cec_logical_address_t dstAddr, int version) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = dstAddr;
    msg.length = 2;
    msg.body[0] = CEC_MESSAGE_CEC_VERSION;
    msg.body[1] = version;
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendGiveDeviceVendorID(cec_logical_address_t srcAddr,
        cec_logical_address_t dstAddr) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = dstAddr;
    msg.length = 1;
    msg.body[0] = CEC_MESSAGE_GIVE_DEVICE_VENDOR_ID;
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendDeviceVendorID(cec_logical_address_t srcAddr,
        cec_logical_address_t dstAddr) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = dstAddr;
    msg.length = 4;
    msg.body[0] = CEC_MESSAGE_DEVICE_VENDOR_ID;
    uint32_t vendor_id;
    mDevice->get_vendor_id(mDevice, &vendor_id);
    std::memcpy(msg.body + 1, &vendor_id, 3);
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendSetOsdName(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr,
        const char* name, size_t len) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = dstAddr;
    msg.body[0] = CEC_MESSAGE_SET_OSD_NAME;
    msg.length = min(len + 1, CEC_MESSAGE_BODY_MAX_LENGTH);
    std::memcpy(msg.body + 1, name, msg.length - 1);
    queueOutgoingMessage(msg);
}

void HdmiCecHandler::sendSetMenuLanguage(cec_logical_address_t srcAddr,
        cec_logical_address_t dstAddr) {
    char lang[4];   // buffer for 3-letter language code
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jstring res = (jstring) env->CallObjectMethod(mCallbacksObj,
            gHdmiCecServiceClassInfo.getLanguage,
            getDeviceType(srcAddr));
    const char *clang = env->GetStringUTFChars(res, NULL);
    strlcpy(lang, clang, sizeof(lang));
    env->ReleaseStringUTFChars(res, clang);

    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = dstAddr;
    msg.length = 4;  // opcode (1) + language code (3)
    msg.body[0] = CEC_MESSAGE_SET_MENU_LANGUAGE;
    std::memcpy(msg.body + 1, lang, 3);
    queueOutgoingMessage(msg);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void HdmiCecHandler::sendCecMessage(const cec_message_t& message) {
    AutoMutex _l(mLock);
    ALOGV("sendCecMessage");
    mDevice->send_message(mDevice, &message);
}

// static
void HdmiCecHandler::onReceived(const hdmi_event_t* event, void* arg) {
    HdmiCecHandler* handler = static_cast<HdmiCecHandler*>(arg);
    if (handler == NULL) {
        return;
    }
    queue_item_t item;
    if (event->type == HDMI_EVENT_CEC_MESSAGE) {
        item.cec = event->cec;
        MessageEntry entry = std::make_pair<int, const queue_item_t>(EVENT_TYPE_RX, item);
        handler->queueMessage(entry);
    } else if (event->type == HDMI_EVENT_HOT_PLUG) {
        item.hotplug = event->hotplug;
        MessageEntry entry = std::make_pair<int, const queue_item_t>(EVENT_TYPE_HOTPLUG, item);
        handler->queueMessage(entry);
    }
}

// static
void HdmiCecHandler::checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

void HdmiCecHandler::dispatchMessage(const MessageEntry& entry) {
    int type = entry.first;
    mMessageQueueLock.unlock();
    if (type == EVENT_TYPE_RX) {
        mMessageQueue.pop_front();
        processIncomingMessage(entry.second.cec);
    } else if (type == EVENT_TYPE_TX) {
        sendCecMessage(entry.second.cec);
        mMessageQueue.pop_front();
    } else if (type == EVENT_TYPE_HOTPLUG) {
        mMessageQueue.pop_front();
        bool connected = entry.second.hotplug.connected;
        if (connected) {
            // TODO: Update logical addresses as well, since they also could have
            // changed while the cable was disconnected.
            updatePhysicalAddress();
        }
        propagateHotplug(connected);
    }
    mMessageQueueLock.lock();
}

void HdmiCecHandler::processIncomingMessage(const cec_message_t& msg) {
    int opcode = msg.body[0];
    if (opcode == CEC_MESSAGE_GIVE_PHYSICAL_ADDRESS) {
        sendReportPhysicalAddress();
    } else if (opcode == CEC_MESSAGE_REQUEST_ACTIVE_SOURCE) {
        handleRequestActiveSource();
    } else if (opcode == CEC_MESSAGE_GET_OSD_NAME) {
        handleGetOsdName(msg);
    } else if (opcode == CEC_MESSAGE_GIVE_DEVICE_VENDOR_ID) {
        handleGiveDeviceVendorID(msg);
    } else if (opcode == CEC_MESSAGE_GET_CEC_VERSION) {
        handleGetCECVersion(msg);
    } else if (opcode == CEC_MESSAGE_GET_MENU_LANGUAGE) {
        handleGetMenuLanguage(msg);
    } else {
        if (precheckMessage(msg)) {
            propagateMessage(msg);
        }
    }
}

bool HdmiCecHandler::precheckMessage(const cec_message_t& msg) {
    // Check if this is the broadcast message coming to itself, which need not be passed
    // back to framework. This happens because CEC spec specifies that a physical device
    // may host multiple logical devices. A broadcast message sent by one of them therefore
    // should be able to reach the others by the loopback mechanism.
    //
    // Currently we don't deal with multiple logical devices, so this is not necessary.
    // It should be revisited once we support hosting multiple logical devices.
    int opcode = msg.body[0];
    if (msg.destination == CEC_ADDR_BROADCAST &&
            (opcode == CEC_MESSAGE_ACTIVE_SOURCE ||
             opcode == CEC_MESSAGE_SET_STREAM_PATH ||
             opcode == CEC_MESSAGE_INACTIVE_SOURCE)) {
        uint16_t senderAddr;
        std::memcpy(&senderAddr, &msg.body[1], 2);
        if (senderAddr == mPhysicalAddress) {
            return false;
        }
    }
    return true;
}

void HdmiCecHandler::propagateMessage(const cec_message_t& msg) {
    int paramLen = msg.length - 1;
    jint srcAddr = msg.initiator;
    jint dstAddr = msg.destination;
    jint opcode = msg.body[0];
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jbyteArray params = env->NewByteArray(paramLen);
    const jbyte* body = reinterpret_cast<const jbyte *>(msg.body + 1);
    if (paramLen > 0) {
        env->SetByteArrayRegion(params, 0, paramLen, body);
    }
    env->CallVoidMethod(mCallbacksObj,
            gHdmiCecServiceClassInfo.handleMessage,
            srcAddr, dstAddr, opcode, params);
    env->DeleteLocalRef(params);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void HdmiCecHandler::propagateHotplug(bool connected) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mCallbacksObj,
            gHdmiCecServiceClassInfo.handleHotplug,
            connected);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}


void HdmiCecHandler::handleRequestActiveSource() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jint activeDeviceType = env->CallIntMethod(mCallbacksObj,
            gHdmiCecServiceClassInfo.getActiveSource);
    if (activeDeviceType != INACTIVE_DEVICE_TYPE) {
        sendActiveSource(getLogicalAddress(activeDeviceType));
    }
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void HdmiCecHandler::handleGetOsdName(const cec_message_t& msg) {
    cec_logical_address_t addr = msg.destination;
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jbyteArray res = (jbyteArray) env->CallObjectMethod(mCallbacksObj,
            gHdmiCecServiceClassInfo.getOsdName,
            getDeviceType(addr));
    jbyte *name = env->GetByteArrayElements(res, NULL);
    if (name != NULL) {
        sendSetOsdName(addr, msg.initiator, reinterpret_cast<const char *>(name),
                env->GetArrayLength(res));
        env->ReleaseByteArrayElements(res, name, JNI_ABORT);
    }
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void HdmiCecHandler::handleGiveDeviceVendorID(const cec_message_t& msg) {
    sendDeviceVendorID(msg.destination, msg.initiator);
}

void HdmiCecHandler::handleGetCECVersion(const cec_message_t& msg) {
    int version;
    mDevice->get_version(mDevice, &version);
    sendCecVersion(msg.destination, msg.initiator, version);
}

void HdmiCecHandler::handleGetMenuLanguage(const cec_message_t& msg) {
    sendSetMenuLanguage(msg.destination, msg.initiator);
}

//------------------------------------------------------------------------------

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

static jlong nativeInit(JNIEnv* env, jclass clazz, jobject callbacksObj) {
    int err;
    hw_module_t* module;
    err = hw_get_module(HDMI_CEC_HARDWARE_MODULE_ID, const_cast<const hw_module_t **>(&module));
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
    HdmiCecHandler *handler = new HdmiCecHandler(reinterpret_cast<hdmi_cec_device *>(device),
            env->NewGlobalRef(callbacksObj));
    handler->initialize();

    GET_METHOD_ID(gHdmiCecServiceClassInfo.handleMessage, clazz,
            "handleMessage", "(III[B)V");
    GET_METHOD_ID(gHdmiCecServiceClassInfo.handleHotplug, clazz,
            "handleHotplug", "(Z)V");
    GET_METHOD_ID(gHdmiCecServiceClassInfo.getActiveSource, clazz,
            "getActiveSource", "()I");
    GET_METHOD_ID(gHdmiCecServiceClassInfo.getOsdName, clazz,
            "getOsdName", "(I)[B");
    GET_METHOD_ID(gHdmiCecServiceClassInfo.getLanguage, clazz,
            "getLanguage", "(I)Ljava/lang/String;");

    return reinterpret_cast<jlong>(handler);
}

static void nativeSendMessage(JNIEnv* env, jclass clazz, jlong handlerPtr, jint deviceType,
        jint dstAddr, jint opcode, jbyteArray params) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    cec_logical_address_t srcAddr = handler->getLogicalAddress(deviceType);
    jsize len = env->GetArrayLength(params);
    ScopedByteArrayRO paramsPtr(env, params);
    cec_message_t message;
    message.initiator = srcAddr;
    message.destination = static_cast<cec_logical_address_t>(dstAddr);
    message.length = len + 1;
    message.body[0] = opcode;
    std::memcpy(message.body + 1, paramsPtr.get(), len);
    handler->sendCecMessage(message);
}

static int nativeAllocateLogicalAddress(JNIEnv* env, jclass clazz, jlong handlerPtr,
        jint deviceType) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    return handler->initLogicalDevice(deviceType);
}

static void nativeRemoveLogicalAddress(JNIEnv* env, jclass clazz, jlong handlerPtr,
       jint deviceType) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    return handler->releaseLogicalDevice(deviceType);
}

static void nativeSendActiveSource(JNIEnv* env, jclass clazz, jlong handlerPtr,
        jint deviceType) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    cec_logical_address_t srcAddr = handler->getLogicalAddress(deviceType);
    handler->sendActiveSource(srcAddr);
}

static void nativeSendInactiveSource(JNIEnv* env, jclass clazz, jlong handlerPtr,
        jint deviceType) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    cec_logical_address_t srcAddr = handler->getLogicalAddress(deviceType);
    handler->sendInactiveSource(srcAddr);
}

static void nativeSendImageViewOn(JNIEnv* env, jclass clazz, jlong handlerPtr,
        jint deviceType) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    cec_logical_address_t srcAddr = handler->getLogicalAddress(deviceType);
    handler->sendImageViewOn(srcAddr);
}

static void nativeSendTextViewOn(JNIEnv* env, jclass clazz, jlong handlerPtr,
        jint deviceType) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    cec_logical_address_t srcAddr = handler->getLogicalAddress(deviceType);
    handler->sendTextViewOn(srcAddr);
}

static void nativeSendGiveDevicePowerStatus(JNIEnv* env, jclass clazz, jlong handlerPtr,
        jint deviceType, jint destination) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    cec_logical_address_t srcAddr = handler->getLogicalAddress(deviceType);
    cec_logical_address_t dstAddr = static_cast<cec_logical_address_t>(destination);
    handler->sendGiveDevicePowerStatus(srcAddr, dstAddr);
}

static JNINativeMethod sMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "(Lcom/android/server/hdmi/HdmiCecService;)J",
            (void *)nativeInit },
    { "nativeSendMessage", "(JIII[B)V",
            (void *)nativeSendMessage },
    { "nativeAllocateLogicalAddress", "(JI)I",
            (void *)nativeAllocateLogicalAddress },
    { "nativeRemoveLogicalAddress", "(JI)V",
            (void *)nativeRemoveLogicalAddress },
    { "nativeSendActiveSource", "(JI)V",
            (void *)nativeSendActiveSource },
    { "nativeSendInactiveSource", "(JI)V",
            (void *)nativeSendInactiveSource },
    { "nativeSendImageViewOn", "(JI)V",
            (void *)nativeSendImageViewOn },
    { "nativeSendTextViewOn", "(JI)V",
            (void *)nativeSendTextViewOn },
    { "nativeSendGiveDevicePowerStatus", "(JII)V",
            (void *)nativeSendGiveDevicePowerStatus }
};

#define CLASS_PATH "com/android/server/hdmi/HdmiCecService"

int register_android_server_hdmi_HdmiCecService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, CLASS_PATH, sMethods, NELEM(sMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

}  /* namespace android */
