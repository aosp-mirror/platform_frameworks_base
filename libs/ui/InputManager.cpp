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

InputManager::InputManager(
        const sp<EventHubInterface>& eventHub,
        const sp<InputReaderPolicyInterface>& readerPolicy,
        const sp<InputDispatcherPolicyInterface>& dispatcherPolicy) {
    mDispatcher = new InputDispatcher(dispatcherPolicy);
    mReader = new InputReader(eventHub, readerPolicy, mDispatcher);
    initialize();
}

InputManager::InputManager(
        const sp<InputReaderInterface>& reader,
        const sp<InputDispatcherInterface>& dispatcher) :
        mReader(reader),
        mDispatcher(dispatcher) {
    initialize();
}

InputManager::~InputManager() {
    stop();
}

void InputManager::initialize() {
    mReaderThread = new InputReaderThread(mReader);
    mDispatcherThread = new InputDispatcherThread(mDispatcher);
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

int32_t InputManager::injectInputEvent(const InputEvent* event,
        int32_t injectorPid, int32_t injectorUid, bool sync, int32_t timeoutMillis) {
    return mDispatcher->injectInputEvent(event, injectorPid, injectorUid, sync, timeoutMillis);
}

void InputManager::preemptInputDispatch() {
    mDispatcher->preemptInputDispatch();
}

void InputManager::getInputConfiguration(InputConfiguration* outConfiguration) {
    mReader->getInputConfiguration(outConfiguration);
}

status_t InputManager::getInputDeviceInfo(int32_t deviceId, InputDeviceInfo* outDeviceInfo) {
    return mReader->getInputDeviceInfo(deviceId, outDeviceInfo);
}

void InputManager::getInputDeviceIds(Vector<int32_t>& outDeviceIds) {
    mReader->getInputDeviceIds(outDeviceIds);
}

int32_t InputManager::getScanCodeState(int32_t deviceId, uint32_t sourceMask,
        int32_t scanCode) {
    return mReader->getScanCodeState(deviceId, sourceMask, scanCode);
}

int32_t InputManager::getKeyCodeState(int32_t deviceId, uint32_t sourceMask,
        int32_t keyCode) {
    return mReader->getKeyCodeState(deviceId, sourceMask, keyCode);
}

int32_t InputManager::getSwitchState(int32_t deviceId, uint32_t sourceMask, int32_t sw) {
    return mReader->getSwitchState(deviceId, sourceMask, sw);
}

bool InputManager::hasKeys(int32_t deviceId, uint32_t sourceMask,
        size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) {
    return mReader->hasKeys(deviceId, sourceMask, numCodes, keyCodes, outFlags);
}

} // namespace android
