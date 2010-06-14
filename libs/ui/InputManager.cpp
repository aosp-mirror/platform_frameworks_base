//
// Copyright 2010 The Android Open Source Project
//
// The input manager.
//
#define LOG_TAG "InputManager"

//#define LOG_NDEBUG 0

#include <cutils/log.h>
#include <ui/InputManager.h>
#include <ui/InputReader.h>
#include <ui/InputDispatcher.h>

namespace android {

InputManager::InputManager(const sp<EventHubInterface>& eventHub,
        const sp<InputDispatchPolicyInterface>& policy) :
        mEventHub(eventHub), mPolicy(policy) {
    mDispatcher = new InputDispatcher(policy);
    mReader = new InputReader(eventHub, policy, mDispatcher);

    mDispatcherThread = new InputDispatcherThread(mDispatcher);
    mReaderThread = new InputReaderThread(mReader);

    configureExcludedDevices();
}

InputManager::~InputManager() {
    stop();
}

void InputManager::configureExcludedDevices() {
    Vector<String8> excludedDeviceNames;
    mPolicy->getExcludedDeviceNames(excludedDeviceNames);

    for (size_t i = 0; i < excludedDeviceNames.size(); i++) {
        mEventHub->addExcludedDevice(excludedDeviceNames[i]);
    }
}

status_t InputManager::start() {
    status_t result = mDispatcherThread->run("InputDispatcher", PRIORITY_URGENT_DISPLAY);
    if (result) {
        LOGE("Could not start InputDispatcher thread due to error %d.", result);
        return result;
    }

    result = mReaderThread->run("InputReader", PRIORITY_URGENT_DISPLAY);
    if (result) {
        LOGE("Could not start InputReader thread due to error %d.", result);

        mDispatcherThread->requestExit();
        return result;
    }

    return OK;
}

status_t InputManager::stop() {
    status_t result = mReaderThread->requestExitAndWait();
    if (result) {
        LOGW("Could not stop InputReader thread due to error %d.", result);
    }

    result = mDispatcherThread->requestExitAndWait();
    if (result) {
        LOGW("Could not stop InputDispatcher thread due to error %d.", result);
    }

    return OK;
}

status_t InputManager::registerInputChannel(const sp<InputChannel>& inputChannel) {
    return mDispatcher->registerInputChannel(inputChannel);
}

status_t InputManager::unregisterInputChannel(const sp<InputChannel>& inputChannel) {
    return mDispatcher->unregisterInputChannel(inputChannel);
}

int32_t InputManager::getScanCodeState(int32_t deviceId, int32_t deviceClasses, int32_t scanCode)
    const {
    int32_t vkKeyCode, vkScanCode;
    if (mReader->getCurrentVirtualKey(& vkKeyCode, & vkScanCode)) {
        if (vkScanCode == scanCode) {
            return KEY_STATE_VIRTUAL;
        }
    }

    return mEventHub->getScanCodeState(deviceId, deviceClasses, scanCode);
}

int32_t InputManager::getKeyCodeState(int32_t deviceId, int32_t deviceClasses, int32_t keyCode)
    const {
    int32_t vkKeyCode, vkScanCode;
    if (mReader->getCurrentVirtualKey(& vkKeyCode, & vkScanCode)) {
        if (vkKeyCode == keyCode) {
            return KEY_STATE_VIRTUAL;
        }
    }

    return mEventHub->getKeyCodeState(deviceId, deviceClasses, keyCode);
}

int32_t InputManager::getSwitchState(int32_t deviceId, int32_t deviceClasses, int32_t sw) const {
    return mEventHub->getSwitchState(deviceId, deviceClasses, sw);
}

bool InputManager::hasKeys(size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) const {
    return mEventHub->hasKeys(numCodes, keyCodes, outFlags);
}

} // namespace android
