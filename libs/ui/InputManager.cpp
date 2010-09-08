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

sp<InputReaderInterface> InputManager::getReader() {
    return mReader;
}

sp<InputDispatcherInterface> InputManager::getDispatcher() {
    return mDispatcher;
}

} // namespace android
