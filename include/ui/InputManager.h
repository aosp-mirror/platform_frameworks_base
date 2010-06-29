/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef _UI_INPUT_MANAGER_H
#define _UI_INPUT_MANAGER_H

/**
 * Native input manager.
 */

#include <ui/EventHub.h>
#include <ui/Input.h>
#include <utils/Errors.h>
#include <utils/Vector.h>
#include <utils/Timers.h>
#include <utils/RefBase.h>
#include <utils/String8.h>

namespace android {

class InputChannel;

class InputReaderInterface;
class InputReaderPolicyInterface;
class InputReaderThread;

class InputDispatcherInterface;
class InputDispatcherPolicyInterface;
class InputDispatcherThread;

/*
 * The input manager is the core of the system event processing.
 *
 * The input manager uses two threads.
 *
 * 1. The InputReaderThread (called "InputReader") reads and preprocesses raw input events,
 *    applies policy, and posts messages to a queue managed by the DispatcherThread.
 * 2. The InputDispatcherThread (called "InputDispatcher") thread waits for new events on the
 *    queue and asynchronously dispatches them to applications.
 *
 * By design, the InputReaderThread class and InputDispatcherThread class do not share any
 * internal state.  Moreover, all communication is done one way from the InputReaderThread
 * into the InputDispatcherThread and never the reverse.  Both classes may interact with the
 * InputDispatchPolicy, however.
 *
 * The InputManager class never makes any calls into Java itself.  Instead, the
 * InputDispatchPolicy is responsible for performing all external interactions with the
 * system, including calling DVM services.
 */
class InputManagerInterface : public virtual RefBase {
protected:
    InputManagerInterface() { }
    virtual ~InputManagerInterface() { }

public:
    /* Starts the input manager threads. */
    virtual status_t start() = 0;

    /* Stops the input manager threads and waits for them to exit. */
    virtual status_t stop() = 0;

    /* Registers an input channel prior to using it as the target of an event. */
    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel) = 0;

    /* Unregisters an input channel. */
    virtual status_t unregisterInputChannel(const sp<InputChannel>& inputChannel) = 0;

    /* Injects an input event and optionally waits for sync.
     * This method may block even if sync is false because it must wait for previous events
     * to be dispatched before it can determine whether input event injection will be
     * permitted based on the current input focus.
     * Returns one of the INPUT_EVENT_INJECTION_XXX constants.
     */
    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, bool sync, int32_t timeoutMillis) = 0;

    /* Preempts input dispatch in progress by making pending synchronous
     * dispatches asynchronous instead.  This method is generally called during a focus
     * transition from one application to the next so as to enable the new application
     * to start receiving input as soon as possible without having to wait for the
     * old application to finish up.
     */
    virtual void preemptInputDispatch() = 0;

    /* Gets input device configuration. */
    virtual void getInputConfiguration(InputConfiguration* outConfiguration) const = 0;

    /*
     * Queries current input state.
     *   deviceId may be -1 to search for the device automatically, filtered by class.
     *   deviceClasses may be -1 to ignore device class while searching.
     */
    virtual int32_t getScanCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t scanCode) const = 0;
    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t keyCode) const = 0;
    virtual int32_t getSwitchState(int32_t deviceId, int32_t deviceClasses,
            int32_t sw) const = 0;

    /* Determines whether physical keys exist for the given framework-domain key codes. */
    virtual bool hasKeys(size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) const = 0;
};

class InputManager : public InputManagerInterface {
protected:
    virtual ~InputManager();

public:
    InputManager(
            const sp<EventHubInterface>& eventHub,
            const sp<InputReaderPolicyInterface>& readerPolicy,
            const sp<InputDispatcherPolicyInterface>& dispatcherPolicy);

    // (used for testing purposes)
    InputManager(
            const sp<InputReaderInterface>& reader,
            const sp<InputDispatcherInterface>& dispatcher);

    virtual status_t start();
    virtual status_t stop();

    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel);
    virtual status_t unregisterInputChannel(const sp<InputChannel>& inputChannel);

    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, bool sync, int32_t timeoutMillis);

    virtual void preemptInputDispatch();

    virtual void getInputConfiguration(InputConfiguration* outConfiguration) const;
    virtual int32_t getScanCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t scanCode) const;
    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t keyCode) const;
    virtual int32_t getSwitchState(int32_t deviceId, int32_t deviceClasses,
            int32_t sw) const;
    virtual bool hasKeys(size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) const;

private:
    sp<InputReaderInterface> mReader;
    sp<InputReaderThread> mReaderThread;

    sp<InputDispatcherInterface> mDispatcher;
    sp<InputDispatcherThread> mDispatcherThread;

    void initialize();
};

} // namespace android

#endif // _UI_INPUT_MANAGER_H
