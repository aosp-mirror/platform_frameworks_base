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

#include <string>
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
    cec_logical_address_t initLogicalDevice(cec_device_type_t type);
    void releaseLogicalDevice(cec_device_type_t type);

    cec_logical_address_t getLogicalAddress(cec_device_type_t deviceType);
    uint16_t getPhysicalAddress();
    cec_device_type_t getDeviceType(cec_logical_address_t addr);
    void queueMessage(const MessageEntry& message);
    void queueOutgoingMessage(const cec_message_t& message);
    void sendReportPhysicalAddress(cec_logical_address_t srcAddr);
    void sendActiveSource(cec_logical_address_t srcAddr);
    void sendFeatureAbort(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr,
            int opcode, int reason);
    void sendCecVersion(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr,
            int version);
    void sendDeviceVendorId(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr);
    void sendGiveDeviceVendorID(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr);
    void sendSetOsdName(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr,
            const char* name, size_t len);
    void sendSetMenuLanguage(cec_logical_address_t srcAddr, cec_logical_address_t dstAddr);

    void sendCecMessage(const cec_message_t& message);
    void setOsdName(const char* name, size_t len);

private:
    enum {
        EVENT_TYPE_RX,
        EVENT_TYPE_TX,
        EVENT_TYPE_HOTPLUG,
        EVENT_TYPE_STANDBY
    };

    /*
     * logical address pool for each device type.
     */
    static const cec_logical_address_t TV_ADDR_POOL[];
    static const cec_logical_address_t PLAYBACK_ADDR_POOL[];
    static const cec_logical_address_t RECORDER_ADDR_POOL[];
    static const cec_logical_address_t TUNER_ADDR_POOL[];

    static const unsigned int MAX_BUFFER_SIZE = 256;
    static const uint16_t INVALID_PHYSICAL_ADDRESS = 0xFFFF;

    static void onReceived(const hdmi_event_t* event, void* arg);
    static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);

    void updatePhysicalAddress();
    void updateLogicalAddress();

    // Allocate logical address. The CEC standard recommends that we try to use the address
    // we have ever used before, in case this is to allocate an address afte the cable is
    // connected again. If preferredAddr is given a valid one (not CEC_ADDR_UNREGISTERED), then
    // this method checks if the address is available first. If not, it tries other addresses
    // int the address pool available for the given type.
    cec_logical_address_t allocateLogicalAddress(cec_device_type_t type,
            cec_logical_address_t preferredAddr);

    // Send a CEC ping message. Returns true if successful.
    bool sendPing(cec_logical_address_t addr);

    // Return the pool of logical addresses that are used for a given device type.
    // One of the addresses in the pool will be chosen in the allocation logic.
    bool getLogicalAddressPool(cec_device_type_t type, const cec_logical_address_t** addrPool,
            size_t* poolSize);

    // Handles the message retrieved from internal message queue. The message can be
    // for either rx or tx.
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
    void handleGiveOsdName(const cec_message_t& msg);
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
    std::map<cec_device_type_t, cec_logical_address_t> mLogicalDevices;

    hdmi_cec_device_t* mDevice;
    jobject mCallbacksObj;
    Mutex mLock;
    Mutex mMessageQueueLock;
    Condition mMessageQueueCondition;
    sp<HdmiThread> mMessageQueueHandler;

    std::deque<MessageEntry> mMessageQueue;
    uint16_t mPhysicalAddress;
    std::string mOsdName;
};

    const cec_logical_address_t HdmiCecHandler::TV_ADDR_POOL[] = {
        CEC_ADDR_TV,
        CEC_ADDR_FREE_USE,
    };

    const cec_logical_address_t HdmiCecHandler::PLAYBACK_ADDR_POOL[] = {
        CEC_ADDR_PLAYBACK_1,
        CEC_ADDR_PLAYBACK_2,
        CEC_ADDR_PLAYBACK_3
    };

    const cec_logical_address_t HdmiCecHandler::RECORDER_ADDR_POOL[] = {
        CEC_ADDR_RECORDER_1,
        CEC_ADDR_RECORDER_2,
        CEC_ADDR_RECORDER_3
    };

    const cec_logical_address_t HdmiCecHandler::TUNER_ADDR_POOL[] = {
        CEC_ADDR_TUNER_1,
        CEC_ADDR_TUNER_2,
        CEC_ADDR_TUNER_3,
        CEC_ADDR_TUNER_4
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

uint16_t HdmiCecHandler::getPhysicalAddress() {
    return mPhysicalAddress;
}

cec_logical_address_t HdmiCecHandler::initLogicalDevice(cec_device_type_t type) {
    cec_logical_address addr = allocateLogicalAddress(type, CEC_ADDR_UNREGISTERED);
    if (addr != CEC_ADDR_UNREGISTERED && !mDevice->add_logical_address(mDevice, addr)) {
        mLogicalDevices.insert(std::pair<cec_device_type_t, cec_logical_address_t>(type, addr));

        // Broadcast <Report Physical Address> when a new logical address was allocated to let
        // other devices discover the new logical device and its logical - physical address
        // association.
        sendReportPhysicalAddress(addr);
    }
    return addr;
}

void HdmiCecHandler::releaseLogicalDevice(cec_device_type_t type) {
    std::map<cec_device_type_t, cec_logical_address_t>::iterator it = mLogicalDevices.find(type);
    if (it != mLogicalDevices.end()) {
        mLogicalDevices.erase(it);
    }
    // TODO: remove the address monitored in HAL as well.
}

cec_logical_address_t HdmiCecHandler::getLogicalAddress(cec_device_type_t type) {
    std::map<cec_device_type_t, cec_logical_address_t>::iterator it = mLogicalDevices.find(type);
    if (it != mLogicalDevices.end()) {
        return it->second;
    }
    return CEC_ADDR_UNREGISTERED;
}

cec_device_type_t HdmiCecHandler::getDeviceType(cec_logical_address_t addr) {
    std::map<cec_device_type_t, cec_logical_address_t>::iterator it = mLogicalDevices.begin();
    for (; it != mLogicalDevices.end(); ++it) {
        if (it->second == addr) {
            return it->first;
        }
    }
    return CEC_DEVICE_INACTIVE;
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

void HdmiCecHandler::sendReportPhysicalAddress(cec_logical_address_t addr) {
    if (mPhysicalAddress == INVALID_PHYSICAL_ADDRESS) {
        ALOGE("Invalid physical address.");
        return;
    }
    cec_device_type_t deviceType = getDeviceType(addr);
    if (deviceType == CEC_DEVICE_INACTIVE) {
        ALOGE("Invalid logical address: %d", addr);
        return;
    }

    cec_message_t msg;
    msg.initiator = addr;
    msg.destination = CEC_ADDR_BROADCAST;
    msg.length = 4;
    msg.body[0] = CEC_MESSAGE_REPORT_PHYSICAL_ADDRESS;
    msg.body[1] = (mPhysicalAddress >> 8) & 0xff;
    msg.body[2] = mPhysicalAddress & 0xff;
    msg.body[3] = deviceType;
    queueOutgoingMessage(msg);
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
    msg.body[1] = (mPhysicalAddress >> 8) & 0xff;
    msg.body[2] = mPhysicalAddress & 0xff;
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

void HdmiCecHandler::sendDeviceVendorId(cec_logical_address_t srcAddr,
        cec_logical_address_t dstAddr) {
    cec_message_t msg;
    msg.initiator = srcAddr;
    msg.destination = dstAddr;
    msg.length = 4;
    msg.body[0] = CEC_MESSAGE_DEVICE_VENDOR_ID;
    uint32_t vendor_id;
    mDevice->get_vendor_id(mDevice, &vendor_id);
    msg.body[1] = (vendor_id >> 16) & 0xff;
    msg.body[2] = (vendor_id >> 8) & 0xff;
    msg.body[3] = vendor_id & 0xff;
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

void HdmiCecHandler::setOsdName(const char* name, size_t len) {
    mOsdName.assign(name, min(len, CEC_MESSAGE_BODY_MAX_LENGTH - 1));
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

void HdmiCecHandler::updatePhysicalAddress() {
    uint16_t addr;
    if (!mDevice->get_physical_address(mDevice, &addr)) {
        mPhysicalAddress = addr;
    } else {
        mPhysicalAddress = INVALID_PHYSICAL_ADDRESS;
    }
}

void HdmiCecHandler::updateLogicalAddress() {
    mDevice->clear_logical_address(mDevice);
    std::map<cec_device_type_t, cec_logical_address_t>::iterator it = mLogicalDevices.begin();
    for (; it != mLogicalDevices.end(); ++it) {
        cec_logical_address_t addr;
        cec_logical_address_t preferredAddr = it->second;
        cec_device_type_t deviceType = it->first;
        addr = allocateLogicalAddress(deviceType, preferredAddr);
        if (!mDevice->add_logical_address(mDevice, addr)) {
            it->second = addr;
        } else {
            it->second = CEC_ADDR_UNREGISTERED;
        }
    }
}

cec_logical_address_t HdmiCecHandler::allocateLogicalAddress(cec_device_type_t type,
        cec_logical_address_t preferredAddr) {
    const cec_logical_address_t* addrPool;
    size_t poolSize;
    if (getLogicalAddressPool(type, &addrPool, &poolSize) < 0) {
        return CEC_ADDR_UNREGISTERED;
    }
    unsigned start = 0;

    // Find the index of preferred address in the pool. If not found, the start
    // position will be 0. This happens when the passed preferredAddr is set to
    // CEC_ADDR_UNREGISTERED, meaning that no preferred address is given.
    for (unsigned i = 0; i < poolSize; i++) {
        if (addrPool[i] == preferredAddr) {
            start = i;
            break;
        }
    }
    for (unsigned i = 0; i < poolSize; i++) {
        cec_logical_address_t addr = addrPool[(start + i) % poolSize];
        if (!sendPing(addr)) {
            // Failure in pinging means the address is available, not taken by any device.
            ALOGV("Logical Address Allocation success: %d", addr);
            return addr;
        }
    }
    ALOGE("Logical Address Allocation failed");
    return CEC_ADDR_UNREGISTERED;
}

bool HdmiCecHandler::sendPing(cec_logical_address addr) {
    cec_message_t msg;
    msg.initiator = msg.destination = addr;
    msg.length = 0;
    return !mDevice->send_message(mDevice, &msg);

}

#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))

bool HdmiCecHandler::getLogicalAddressPool(cec_device_type_t deviceType,
        const cec_logical_address_t** addrPool, size_t* poolSize) {
    switch (deviceType) {
    case CEC_DEVICE_TV:
        *addrPool = TV_ADDR_POOL;
        *poolSize = ARRAY_SIZE(TV_ADDR_POOL);
        break;
    case CEC_DEVICE_RECORDER:
        *addrPool = RECORDER_ADDR_POOL;
        *poolSize = ARRAY_SIZE(RECORDER_ADDR_POOL);
        break;
    case CEC_DEVICE_TUNER:
        *addrPool = TUNER_ADDR_POOL;
        *poolSize = ARRAY_SIZE(TUNER_ADDR_POOL);
        break;
    case CEC_DEVICE_PLAYBACK:
        *addrPool = PLAYBACK_ADDR_POOL;
        *poolSize = ARRAY_SIZE(PLAYBACK_ADDR_POOL);
        break;
    default:
        ALOGE("Unsupported device type: %d", deviceType);
        return false;
    }
    return true;
}

#undef ARRAY_SIZE

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
            updatePhysicalAddress();
            updateLogicalAddress();
        }
        propagateHotplug(connected);
    }
    mMessageQueueLock.lock();
}

void HdmiCecHandler::processIncomingMessage(const cec_message_t& msg) {
    int opcode = msg.body[0];
    if (opcode == CEC_MESSAGE_GIVE_PHYSICAL_ADDRESS) {
        sendReportPhysicalAddress(msg.destination);
    } else if (opcode == CEC_MESSAGE_REQUEST_ACTIVE_SOURCE) {
        handleRequestActiveSource();
    } else if (opcode == CEC_MESSAGE_GIVE_OSD_NAME) {
        handleGiveOsdName(msg);
    } else if (opcode == CEC_MESSAGE_GIVE_DEVICE_VENDOR_ID) {
        handleGiveDeviceVendorID(msg);
    } else if (opcode == CEC_MESSAGE_GET_CEC_VERSION) {
        handleGetCECVersion(msg);
    } else if (opcode == CEC_MESSAGE_GET_MENU_LANGUAGE) {
        handleGetMenuLanguage(msg);
    } else if (opcode == CEC_MESSAGE_ABORT) {
        // Compliance testing requires that abort message be responded with feature abort.
        sendFeatureAbort(msg.destination, msg.initiator, msg.body[0], ABORT_REFUSED);
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
        uint16_t senderAddr = (msg.body[1] << 8) + msg.body[2];
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
    if (activeDeviceType != CEC_DEVICE_INACTIVE) {
        sendActiveSource(getLogicalAddress(static_cast<cec_device_type_t>(activeDeviceType)));
    }
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void HdmiCecHandler::handleGiveOsdName(const cec_message_t& msg) {
    if (!mOsdName.empty()) {
        sendSetOsdName(msg.destination, msg.initiator, mOsdName.c_str(), mOsdName.length());
    }
}

void HdmiCecHandler::handleGiveDeviceVendorID(const cec_message_t& msg) {
    sendDeviceVendorId(msg.destination, msg.initiator);
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
    GET_METHOD_ID(gHdmiCecServiceClassInfo.getLanguage, clazz,
            "getLanguage", "(I)Ljava/lang/String;");

    return reinterpret_cast<jlong>(handler);
}

static void nativeSendMessage(JNIEnv* env, jclass clazz, jlong handlerPtr, jint deviceType,
        jint dstAddr, jint opcode, jbyteArray params) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    cec_logical_address_t srcAddr = handler->getLogicalAddress(
            static_cast<cec_device_type_t>(deviceType));
    jsize len = env->GetArrayLength(params);
    ScopedByteArrayRO paramsPtr(env, params);
    cec_message_t message;
    message.initiator = srcAddr;
    message.destination = static_cast<cec_logical_address_t>(dstAddr);
    message.length = min(len + 1, CEC_MESSAGE_BODY_MAX_LENGTH);
    message.body[0] = opcode;
    std::memcpy(message.body + 1, paramsPtr.get(), message.length - 1);
    handler->sendCecMessage(message);
}

static jint nativeAllocateLogicalAddress(JNIEnv* env, jclass clazz, jlong handlerPtr,
        jint deviceType) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    return handler->initLogicalDevice(static_cast<cec_device_type_t>(deviceType));
}

static void nativeRemoveLogicalAddress(JNIEnv* env, jclass clazz, jlong handlerPtr,
       jint deviceType) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    return handler->releaseLogicalDevice(static_cast<cec_device_type_t>(deviceType));
}

static jint nativeGetPhysicalAddress(JNIEnv* env, jclass clazz, jlong handlerPtr) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    return handler->getPhysicalAddress();
}

static void nativeSetOsdName(JNIEnv* env, jclass clazz, jlong handlerPtr, jbyteArray name) {
    HdmiCecHandler *handler = reinterpret_cast<HdmiCecHandler *>(handlerPtr);
    jsize len = env->GetArrayLength(name);
    if (len > 0) {
        ScopedByteArrayRO namePtr(env, name);
        handler->setOsdName(reinterpret_cast<const char *>(namePtr.get()), len);
    }
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
    { "nativeGetPhysicalAddress", "(J)I",
            (void *)nativeGetPhysicalAddress },
    { "nativeSetOsdName", "(J[B)V",
            (void *)nativeSetOsdName },
};

#define CLASS_PATH "com/android/server/hdmi/HdmiCecService"

int register_android_server_hdmi_HdmiCecService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, CLASS_PATH, sMethods, NELEM(sMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

}  /* namespace android */
