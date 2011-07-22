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

#define LOG_TAG "InputReader"

//#define LOG_NDEBUG 0

// Log debug messages for each raw event received from the EventHub.
#define DEBUG_RAW_EVENTS 0

// Log debug messages about touch screen filtering hacks.
#define DEBUG_HACKS 0

// Log debug messages about virtual key processing.
#define DEBUG_VIRTUAL_KEYS 0

// Log debug messages about pointers.
#define DEBUG_POINTERS 0

// Log debug messages about pointer assignment calculations.
#define DEBUG_POINTER_ASSIGNMENT 0

// Log debug messages about gesture detection.
#define DEBUG_GESTURES 0

#include "InputReader.h"

#include <cutils/log.h>
#include <ui/Keyboard.h>
#include <ui/VirtualKeyMap.h>

#include <stddef.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>
#include <math.h>

#define INDENT "  "
#define INDENT2 "    "
#define INDENT3 "      "
#define INDENT4 "        "
#define INDENT5 "          "

namespace android {

// --- Constants ---

// Maximum number of slots supported when using the slot-based Multitouch Protocol B.
static const size_t MAX_SLOTS = 32;

// --- Static Functions ---

template<typename T>
inline static T abs(const T& value) {
    return value < 0 ? - value : value;
}

template<typename T>
inline static T min(const T& a, const T& b) {
    return a < b ? a : b;
}

template<typename T>
inline static void swap(T& a, T& b) {
    T temp = a;
    a = b;
    b = temp;
}

inline static float avg(float x, float y) {
    return (x + y) / 2;
}

inline static float distance(float x1, float y1, float x2, float y2) {
    return hypotf(x1 - x2, y1 - y2);
}

inline static int32_t signExtendNybble(int32_t value) {
    return value >= 8 ? value - 16 : value;
}

static inline const char* toString(bool value) {
    return value ? "true" : "false";
}

static int32_t rotateValueUsingRotationMap(int32_t value, int32_t orientation,
        const int32_t map[][4], size_t mapSize) {
    if (orientation != DISPLAY_ORIENTATION_0) {
        for (size_t i = 0; i < mapSize; i++) {
            if (value == map[i][0]) {
                return map[i][orientation];
            }
        }
    }
    return value;
}

static const int32_t keyCodeRotationMap[][4] = {
        // key codes enumerated counter-clockwise with the original (unrotated) key first
        // no rotation,        90 degree rotation,  180 degree rotation, 270 degree rotation
        { AKEYCODE_DPAD_DOWN,   AKEYCODE_DPAD_RIGHT,  AKEYCODE_DPAD_UP,     AKEYCODE_DPAD_LEFT },
        { AKEYCODE_DPAD_RIGHT,  AKEYCODE_DPAD_UP,     AKEYCODE_DPAD_LEFT,   AKEYCODE_DPAD_DOWN },
        { AKEYCODE_DPAD_UP,     AKEYCODE_DPAD_LEFT,   AKEYCODE_DPAD_DOWN,   AKEYCODE_DPAD_RIGHT },
        { AKEYCODE_DPAD_LEFT,   AKEYCODE_DPAD_DOWN,   AKEYCODE_DPAD_RIGHT,  AKEYCODE_DPAD_UP },
};
static const size_t keyCodeRotationMapSize =
        sizeof(keyCodeRotationMap) / sizeof(keyCodeRotationMap[0]);

static int32_t rotateKeyCode(int32_t keyCode, int32_t orientation) {
    return rotateValueUsingRotationMap(keyCode, orientation,
            keyCodeRotationMap, keyCodeRotationMapSize);
}

static void rotateDelta(int32_t orientation, float* deltaX, float* deltaY) {
    float temp;
    switch (orientation) {
    case DISPLAY_ORIENTATION_90:
        temp = *deltaX;
        *deltaX = *deltaY;
        *deltaY = -temp;
        break;

    case DISPLAY_ORIENTATION_180:
        *deltaX = -*deltaX;
        *deltaY = -*deltaY;
        break;

    case DISPLAY_ORIENTATION_270:
        temp = *deltaX;
        *deltaX = -*deltaY;
        *deltaY = temp;
        break;
    }
}

static inline bool sourcesMatchMask(uint32_t sources, uint32_t sourceMask) {
    return (sources & sourceMask & ~ AINPUT_SOURCE_CLASS_MASK) != 0;
}

static uint32_t getButtonStateForScanCode(int32_t scanCode) {
    // Currently all buttons are mapped to the primary button.
    switch (scanCode) {
    case BTN_LEFT:
        return AMOTION_EVENT_BUTTON_PRIMARY;
    case BTN_RIGHT:
    case BTN_STYLUS:
        return AMOTION_EVENT_BUTTON_SECONDARY;
    case BTN_MIDDLE:
    case BTN_STYLUS2:
        return AMOTION_EVENT_BUTTON_TERTIARY;
    case BTN_SIDE:
        return AMOTION_EVENT_BUTTON_BACK;
    case BTN_FORWARD:
    case BTN_EXTRA:
        return AMOTION_EVENT_BUTTON_FORWARD;
    case BTN_BACK:
        return AMOTION_EVENT_BUTTON_BACK;
    case BTN_TASK:
    default:
        return 0;
    }
}

// Returns true if the pointer should be reported as being down given the specified
// button states.  This determines whether the event is reported as a touch event.
static bool isPointerDown(int32_t buttonState) {
    return buttonState &
            (AMOTION_EVENT_BUTTON_PRIMARY | AMOTION_EVENT_BUTTON_SECONDARY
                    | AMOTION_EVENT_BUTTON_TERTIARY);
}

static float calculateCommonVector(float a, float b) {
    if (a > 0 && b > 0) {
        return a < b ? a : b;
    } else if (a < 0 && b < 0) {
        return a > b ? a : b;
    } else {
        return 0;
    }
}

static void synthesizeButtonKey(InputReaderContext* context, int32_t action,
        nsecs_t when, int32_t deviceId, uint32_t source,
        uint32_t policyFlags, int32_t lastButtonState, int32_t currentButtonState,
        int32_t buttonState, int32_t keyCode) {
    if (
            (action == AKEY_EVENT_ACTION_DOWN
                    && !(lastButtonState & buttonState)
                    && (currentButtonState & buttonState))
            || (action == AKEY_EVENT_ACTION_UP
                    && (lastButtonState & buttonState)
                    && !(currentButtonState & buttonState))) {
        context->getDispatcher()->notifyKey(when, deviceId, source, policyFlags,
                action, 0, keyCode, 0, context->getGlobalMetaState(), when);
    }
}

static void synthesizeButtonKeys(InputReaderContext* context, int32_t action,
        nsecs_t when, int32_t deviceId, uint32_t source,
        uint32_t policyFlags, int32_t lastButtonState, int32_t currentButtonState) {
    synthesizeButtonKey(context, action, when, deviceId, source, policyFlags,
            lastButtonState, currentButtonState,
            AMOTION_EVENT_BUTTON_BACK, AKEYCODE_BACK);
    synthesizeButtonKey(context, action, when, deviceId, source, policyFlags,
            lastButtonState, currentButtonState,
            AMOTION_EVENT_BUTTON_FORWARD, AKEYCODE_FORWARD);
}


// --- InputReader ---

InputReader::InputReader(const sp<EventHubInterface>& eventHub,
        const sp<InputReaderPolicyInterface>& policy,
        const sp<InputDispatcherInterface>& dispatcher) :
        mEventHub(eventHub), mPolicy(policy), mDispatcher(dispatcher),
        mGlobalMetaState(0), mDisableVirtualKeysTimeout(LLONG_MIN), mNextTimeout(LLONG_MAX),
        mConfigurationChangesToRefresh(0) {
    refreshConfiguration(0);
    updateGlobalMetaState();
    updateInputConfiguration();
}

InputReader::~InputReader() {
    for (size_t i = 0; i < mDevices.size(); i++) {
        delete mDevices.valueAt(i);
    }
}

void InputReader::loopOnce() {
    uint32_t changes;
    { // acquire lock
        AutoMutex _l(mStateLock);

        changes = mConfigurationChangesToRefresh;
        mConfigurationChangesToRefresh = 0;
    } // release lock

    if (changes) {
        refreshConfiguration(changes);
    }

    int32_t timeoutMillis = -1;
    if (mNextTimeout != LLONG_MAX) {
        nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
        timeoutMillis = toMillisecondTimeoutDelay(now, mNextTimeout);
    }

    size_t count = mEventHub->getEvents(timeoutMillis, mEventBuffer, EVENT_BUFFER_SIZE);
    if (count) {
        processEvents(mEventBuffer, count);
    }
    if (!count || timeoutMillis == 0) {
        nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
#if DEBUG_RAW_EVENTS
        LOGD("Timeout expired, latency=%0.3fms", (now - mNextTimeout) * 0.000001f);
#endif
        mNextTimeout = LLONG_MAX;
        timeoutExpired(now);
    }
}

void InputReader::processEvents(const RawEvent* rawEvents, size_t count) {
    for (const RawEvent* rawEvent = rawEvents; count;) {
        int32_t type = rawEvent->type;
        size_t batchSize = 1;
        if (type < EventHubInterface::FIRST_SYNTHETIC_EVENT) {
            int32_t deviceId = rawEvent->deviceId;
            while (batchSize < count) {
                if (rawEvent[batchSize].type >= EventHubInterface::FIRST_SYNTHETIC_EVENT
                        || rawEvent[batchSize].deviceId != deviceId) {
                    break;
                }
                batchSize += 1;
            }
#if DEBUG_RAW_EVENTS
            LOGD("BatchSize: %d Count: %d", batchSize, count);
#endif
            processEventsForDevice(deviceId, rawEvent, batchSize);
        } else {
            switch (rawEvent->type) {
            case EventHubInterface::DEVICE_ADDED:
                addDevice(rawEvent->deviceId);
                break;
            case EventHubInterface::DEVICE_REMOVED:
                removeDevice(rawEvent->deviceId);
                break;
            case EventHubInterface::FINISHED_DEVICE_SCAN:
                handleConfigurationChanged(rawEvent->when);
                break;
            default:
                LOG_ASSERT(false); // can't happen
                break;
            }
        }
        count -= batchSize;
        rawEvent += batchSize;
    }
}

void InputReader::addDevice(int32_t deviceId) {
    String8 name = mEventHub->getDeviceName(deviceId);
    uint32_t classes = mEventHub->getDeviceClasses(deviceId);

    InputDevice* device = createDevice(deviceId, name, classes);
    device->configure(&mConfig, 0);

    if (device->isIgnored()) {
        LOGI("Device added: id=%d, name='%s' (ignored non-input device)", deviceId, name.string());
    } else {
        LOGI("Device added: id=%d, name='%s', sources=0x%08x", deviceId, name.string(),
                device->getSources());
    }

    bool added = false;
    { // acquire device registry writer lock
        RWLock::AutoWLock _wl(mDeviceRegistryLock);

        ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
        if (deviceIndex < 0) {
            mDevices.add(deviceId, device);
            added = true;
        }
    } // release device registry writer lock

    if (! added) {
        LOGW("Ignoring spurious device added event for deviceId %d.", deviceId);
        delete device;
        return;
    }
}

void InputReader::removeDevice(int32_t deviceId) {
    bool removed = false;
    InputDevice* device = NULL;
    { // acquire device registry writer lock
        RWLock::AutoWLock _wl(mDeviceRegistryLock);

        ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
        if (deviceIndex >= 0) {
            device = mDevices.valueAt(deviceIndex);
            mDevices.removeItemsAt(deviceIndex, 1);
            removed = true;
        }
    } // release device registry writer lock

    if (! removed) {
        LOGW("Ignoring spurious device removed event for deviceId %d.", deviceId);
        return;
    }

    if (device->isIgnored()) {
        LOGI("Device removed: id=%d, name='%s' (ignored non-input device)",
                device->getId(), device->getName().string());
    } else {
        LOGI("Device removed: id=%d, name='%s', sources=0x%08x",
                device->getId(), device->getName().string(), device->getSources());
    }

    device->reset();

    delete device;
}

InputDevice* InputReader::createDevice(int32_t deviceId, const String8& name, uint32_t classes) {
    InputDevice* device = new InputDevice(this, deviceId, name);

    // External devices.
    if (classes & INPUT_DEVICE_CLASS_EXTERNAL) {
        device->setExternal(true);
    }

    // Switch-like devices.
    if (classes & INPUT_DEVICE_CLASS_SWITCH) {
        device->addMapper(new SwitchInputMapper(device));
    }

    // Keyboard-like devices.
    uint32_t keyboardSource = 0;
    int32_t keyboardType = AINPUT_KEYBOARD_TYPE_NON_ALPHABETIC;
    if (classes & INPUT_DEVICE_CLASS_KEYBOARD) {
        keyboardSource |= AINPUT_SOURCE_KEYBOARD;
    }
    if (classes & INPUT_DEVICE_CLASS_ALPHAKEY) {
        keyboardType = AINPUT_KEYBOARD_TYPE_ALPHABETIC;
    }
    if (classes & INPUT_DEVICE_CLASS_DPAD) {
        keyboardSource |= AINPUT_SOURCE_DPAD;
    }
    if (classes & INPUT_DEVICE_CLASS_GAMEPAD) {
        keyboardSource |= AINPUT_SOURCE_GAMEPAD;
    }

    if (keyboardSource != 0) {
        device->addMapper(new KeyboardInputMapper(device, keyboardSource, keyboardType));
    }

    // Cursor-like devices.
    if (classes & INPUT_DEVICE_CLASS_CURSOR) {
        device->addMapper(new CursorInputMapper(device));
    }

    // Touchscreens and touchpad devices.
    if (classes & INPUT_DEVICE_CLASS_TOUCH_MT) {
        device->addMapper(new MultiTouchInputMapper(device));
    } else if (classes & INPUT_DEVICE_CLASS_TOUCH) {
        device->addMapper(new SingleTouchInputMapper(device));
    }

    // Joystick-like devices.
    if (classes & INPUT_DEVICE_CLASS_JOYSTICK) {
        device->addMapper(new JoystickInputMapper(device));
    }

    return device;
}

void InputReader::processEventsForDevice(int32_t deviceId,
        const RawEvent* rawEvents, size_t count) {
    { // acquire device registry reader lock
        RWLock::AutoRLock _rl(mDeviceRegistryLock);

        ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
        if (deviceIndex < 0) {
            LOGW("Discarding event for unknown deviceId %d.", deviceId);
            return;
        }

        InputDevice* device = mDevices.valueAt(deviceIndex);
        if (device->isIgnored()) {
            //LOGD("Discarding event for ignored deviceId %d.", deviceId);
            return;
        }

        device->process(rawEvents, count);
    } // release device registry reader lock
}

void InputReader::timeoutExpired(nsecs_t when) {
    { // acquire device registry reader lock
        RWLock::AutoRLock _rl(mDeviceRegistryLock);

        for (size_t i = 0; i < mDevices.size(); i++) {
            InputDevice* device = mDevices.valueAt(i);
            if (!device->isIgnored()) {
                device->timeoutExpired(when);
            }
        }
    } // release device registry reader lock
}

void InputReader::handleConfigurationChanged(nsecs_t when) {
    // Reset global meta state because it depends on the list of all configured devices.
    updateGlobalMetaState();

    // Update input configuration.
    updateInputConfiguration();

    // Enqueue configuration changed.
    mDispatcher->notifyConfigurationChanged(when);
}

void InputReader::refreshConfiguration(uint32_t changes) {
    mPolicy->getReaderConfiguration(&mConfig);
    mEventHub->setExcludedDevices(mConfig.excludedDeviceNames);

    if (changes) {
        LOGI("Reconfiguring input devices.  changes=0x%08x", changes);

        if (changes & InputReaderConfiguration::CHANGE_MUST_REOPEN) {
            mEventHub->requestReopenDevices();
        } else {
            { // acquire device registry reader lock
                RWLock::AutoRLock _rl(mDeviceRegistryLock);

                for (size_t i = 0; i < mDevices.size(); i++) {
                    InputDevice* device = mDevices.valueAt(i);
                    device->configure(&mConfig, changes);
                }
            } // release device registry reader lock
        }
    }
}

void InputReader::updateGlobalMetaState() {
    { // acquire state lock
        AutoMutex _l(mStateLock);

        mGlobalMetaState = 0;

        { // acquire device registry reader lock
            RWLock::AutoRLock _rl(mDeviceRegistryLock);

            for (size_t i = 0; i < mDevices.size(); i++) {
                InputDevice* device = mDevices.valueAt(i);
                mGlobalMetaState |= device->getMetaState();
            }
        } // release device registry reader lock
    } // release state lock
}

int32_t InputReader::getGlobalMetaState() {
    { // acquire state lock
        AutoMutex _l(mStateLock);

        return mGlobalMetaState;
    } // release state lock
}

void InputReader::updateInputConfiguration() {
    { // acquire state lock
        AutoMutex _l(mStateLock);

        int32_t touchScreenConfig = InputConfiguration::TOUCHSCREEN_NOTOUCH;
        int32_t keyboardConfig = InputConfiguration::KEYBOARD_NOKEYS;
        int32_t navigationConfig = InputConfiguration::NAVIGATION_NONAV;
        { // acquire device registry reader lock
            RWLock::AutoRLock _rl(mDeviceRegistryLock);

            InputDeviceInfo deviceInfo;
            for (size_t i = 0; i < mDevices.size(); i++) {
                InputDevice* device = mDevices.valueAt(i);
                device->getDeviceInfo(& deviceInfo);
                uint32_t sources = deviceInfo.getSources();

                if ((sources & AINPUT_SOURCE_TOUCHSCREEN) == AINPUT_SOURCE_TOUCHSCREEN) {
                    touchScreenConfig = InputConfiguration::TOUCHSCREEN_FINGER;
                }
                if ((sources & AINPUT_SOURCE_TRACKBALL) == AINPUT_SOURCE_TRACKBALL) {
                    navigationConfig = InputConfiguration::NAVIGATION_TRACKBALL;
                } else if ((sources & AINPUT_SOURCE_DPAD) == AINPUT_SOURCE_DPAD) {
                    navigationConfig = InputConfiguration::NAVIGATION_DPAD;
                }
                if (deviceInfo.getKeyboardType() == AINPUT_KEYBOARD_TYPE_ALPHABETIC) {
                    keyboardConfig = InputConfiguration::KEYBOARD_QWERTY;
                }
            }
        } // release device registry reader lock

        mInputConfiguration.touchScreen = touchScreenConfig;
        mInputConfiguration.keyboard = keyboardConfig;
        mInputConfiguration.navigation = navigationConfig;
    } // release state lock
}

void InputReader::disableVirtualKeysUntil(nsecs_t time) {
    mDisableVirtualKeysTimeout = time;
}

bool InputReader::shouldDropVirtualKey(nsecs_t now,
        InputDevice* device, int32_t keyCode, int32_t scanCode) {
    if (now < mDisableVirtualKeysTimeout) {
        LOGI("Dropping virtual key from device %s because virtual keys are "
                "temporarily disabled for the next %0.3fms.  keyCode=%d, scanCode=%d",
                device->getName().string(),
                (mDisableVirtualKeysTimeout - now) * 0.000001,
                keyCode, scanCode);
        return true;
    } else {
        return false;
    }
}

void InputReader::fadePointer() {
    { // acquire device registry reader lock
        RWLock::AutoRLock _rl(mDeviceRegistryLock);

        for (size_t i = 0; i < mDevices.size(); i++) {
            InputDevice* device = mDevices.valueAt(i);
            device->fadePointer();
        }
    } // release device registry reader lock
}

void InputReader::requestTimeoutAtTime(nsecs_t when) {
    if (when < mNextTimeout) {
        mNextTimeout = when;
    }
}

void InputReader::getInputConfiguration(InputConfiguration* outConfiguration) {
    { // acquire state lock
        AutoMutex _l(mStateLock);

        *outConfiguration = mInputConfiguration;
    } // release state lock
}

status_t InputReader::getInputDeviceInfo(int32_t deviceId, InputDeviceInfo* outDeviceInfo) {
    { // acquire device registry reader lock
        RWLock::AutoRLock _rl(mDeviceRegistryLock);

        ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
        if (deviceIndex < 0) {
            return NAME_NOT_FOUND;
        }

        InputDevice* device = mDevices.valueAt(deviceIndex);
        if (device->isIgnored()) {
            return NAME_NOT_FOUND;
        }

        device->getDeviceInfo(outDeviceInfo);
        return OK;
    } // release device registy reader lock
}

void InputReader::getInputDeviceIds(Vector<int32_t>& outDeviceIds) {
    outDeviceIds.clear();

    { // acquire device registry reader lock
        RWLock::AutoRLock _rl(mDeviceRegistryLock);

        size_t numDevices = mDevices.size();
        for (size_t i = 0; i < numDevices; i++) {
            InputDevice* device = mDevices.valueAt(i);
            if (! device->isIgnored()) {
                outDeviceIds.add(device->getId());
            }
        }
    } // release device registy reader lock
}

int32_t InputReader::getKeyCodeState(int32_t deviceId, uint32_t sourceMask,
        int32_t keyCode) {
    return getState(deviceId, sourceMask, keyCode, & InputDevice::getKeyCodeState);
}

int32_t InputReader::getScanCodeState(int32_t deviceId, uint32_t sourceMask,
        int32_t scanCode) {
    return getState(deviceId, sourceMask, scanCode, & InputDevice::getScanCodeState);
}

int32_t InputReader::getSwitchState(int32_t deviceId, uint32_t sourceMask, int32_t switchCode) {
    return getState(deviceId, sourceMask, switchCode, & InputDevice::getSwitchState);
}

int32_t InputReader::getState(int32_t deviceId, uint32_t sourceMask, int32_t code,
        GetStateFunc getStateFunc) {
    { // acquire device registry reader lock
        RWLock::AutoRLock _rl(mDeviceRegistryLock);

        int32_t result = AKEY_STATE_UNKNOWN;
        if (deviceId >= 0) {
            ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
            if (deviceIndex >= 0) {
                InputDevice* device = mDevices.valueAt(deviceIndex);
                if (! device->isIgnored() && sourcesMatchMask(device->getSources(), sourceMask)) {
                    result = (device->*getStateFunc)(sourceMask, code);
                }
            }
        } else {
            size_t numDevices = mDevices.size();
            for (size_t i = 0; i < numDevices; i++) {
                InputDevice* device = mDevices.valueAt(i);
                if (! device->isIgnored() && sourcesMatchMask(device->getSources(), sourceMask)) {
                    result = (device->*getStateFunc)(sourceMask, code);
                    if (result >= AKEY_STATE_DOWN) {
                        return result;
                    }
                }
            }
        }
        return result;
    } // release device registy reader lock
}

bool InputReader::hasKeys(int32_t deviceId, uint32_t sourceMask,
        size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) {
    memset(outFlags, 0, numCodes);
    return markSupportedKeyCodes(deviceId, sourceMask, numCodes, keyCodes, outFlags);
}

bool InputReader::markSupportedKeyCodes(int32_t deviceId, uint32_t sourceMask, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) {
    { // acquire device registry reader lock
        RWLock::AutoRLock _rl(mDeviceRegistryLock);
        bool result = false;
        if (deviceId >= 0) {
            ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
            if (deviceIndex >= 0) {
                InputDevice* device = mDevices.valueAt(deviceIndex);
                if (! device->isIgnored() && sourcesMatchMask(device->getSources(), sourceMask)) {
                    result = device->markSupportedKeyCodes(sourceMask,
                            numCodes, keyCodes, outFlags);
                }
            }
        } else {
            size_t numDevices = mDevices.size();
            for (size_t i = 0; i < numDevices; i++) {
                InputDevice* device = mDevices.valueAt(i);
                if (! device->isIgnored() && sourcesMatchMask(device->getSources(), sourceMask)) {
                    result |= device->markSupportedKeyCodes(sourceMask,
                            numCodes, keyCodes, outFlags);
                }
            }
        }
        return result;
    } // release device registy reader lock
}

void InputReader::requestRefreshConfiguration(uint32_t changes) {
    if (changes) {
        bool needWake;
        { // acquire lock
            AutoMutex _l(mStateLock);

            needWake = !mConfigurationChangesToRefresh;
            mConfigurationChangesToRefresh |= changes;
        } // release lock

        if (needWake) {
            mEventHub->wake();
        }
    }
}

void InputReader::dump(String8& dump) {
    mEventHub->dump(dump);
    dump.append("\n");

    dump.append("Input Reader State:\n");

    { // acquire device registry reader lock
        RWLock::AutoRLock _rl(mDeviceRegistryLock);

        for (size_t i = 0; i < mDevices.size(); i++) {
            mDevices.valueAt(i)->dump(dump);
        }
    } // release device registy reader lock

    dump.append(INDENT "Configuration:\n");
    dump.append(INDENT2 "ExcludedDeviceNames: [");
    for (size_t i = 0; i < mConfig.excludedDeviceNames.size(); i++) {
        if (i != 0) {
            dump.append(", ");
        }
        dump.append(mConfig.excludedDeviceNames.itemAt(i).string());
    }
    dump.append("]\n");
    dump.appendFormat(INDENT2 "VirtualKeyQuietTime: %0.1fms\n",
            mConfig.virtualKeyQuietTime * 0.000001f);

    dump.appendFormat(INDENT2 "PointerVelocityControlParameters: "
            "scale=%0.3f, lowThreshold=%0.3f, highThreshold=%0.3f, acceleration=%0.3f\n",
            mConfig.pointerVelocityControlParameters.scale,
            mConfig.pointerVelocityControlParameters.lowThreshold,
            mConfig.pointerVelocityControlParameters.highThreshold,
            mConfig.pointerVelocityControlParameters.acceleration);

    dump.appendFormat(INDENT2 "WheelVelocityControlParameters: "
            "scale=%0.3f, lowThreshold=%0.3f, highThreshold=%0.3f, acceleration=%0.3f\n",
            mConfig.wheelVelocityControlParameters.scale,
            mConfig.wheelVelocityControlParameters.lowThreshold,
            mConfig.wheelVelocityControlParameters.highThreshold,
            mConfig.wheelVelocityControlParameters.acceleration);

    dump.appendFormat(INDENT2 "PointerGesture:\n");
    dump.appendFormat(INDENT3 "Enabled: %s\n",
            toString(mConfig.pointerGesturesEnabled));
    dump.appendFormat(INDENT3 "QuietInterval: %0.1fms\n",
            mConfig.pointerGestureQuietInterval * 0.000001f);
    dump.appendFormat(INDENT3 "DragMinSwitchSpeed: %0.1fpx/s\n",
            mConfig.pointerGestureDragMinSwitchSpeed);
    dump.appendFormat(INDENT3 "TapInterval: %0.1fms\n",
            mConfig.pointerGestureTapInterval * 0.000001f);
    dump.appendFormat(INDENT3 "TapDragInterval: %0.1fms\n",
            mConfig.pointerGestureTapDragInterval * 0.000001f);
    dump.appendFormat(INDENT3 "TapSlop: %0.1fpx\n",
            mConfig.pointerGestureTapSlop);
    dump.appendFormat(INDENT3 "MultitouchSettleInterval: %0.1fms\n",
            mConfig.pointerGestureMultitouchSettleInterval * 0.000001f);
    dump.appendFormat(INDENT3 "MultitouchMinDistance: %0.1fpx\n",
            mConfig.pointerGestureMultitouchMinDistance);
    dump.appendFormat(INDENT3 "SwipeTransitionAngleCosine: %0.1f\n",
            mConfig.pointerGestureSwipeTransitionAngleCosine);
    dump.appendFormat(INDENT3 "SwipeMaxWidthRatio: %0.1f\n",
            mConfig.pointerGestureSwipeMaxWidthRatio);
    dump.appendFormat(INDENT3 "MovementSpeedRatio: %0.1f\n",
            mConfig.pointerGestureMovementSpeedRatio);
    dump.appendFormat(INDENT3 "ZoomSpeedRatio: %0.1f\n",
            mConfig.pointerGestureZoomSpeedRatio);
}


// --- InputReaderThread ---

InputReaderThread::InputReaderThread(const sp<InputReaderInterface>& reader) :
        Thread(/*canCallJava*/ true), mReader(reader) {
}

InputReaderThread::~InputReaderThread() {
}

bool InputReaderThread::threadLoop() {
    mReader->loopOnce();
    return true;
}


// --- InputDevice ---

InputDevice::InputDevice(InputReaderContext* context, int32_t id, const String8& name) :
        mContext(context), mId(id), mName(name), mSources(0),
        mIsExternal(false), mDropUntilNextSync(false) {
}

InputDevice::~InputDevice() {
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        delete mMappers[i];
    }
    mMappers.clear();
}

void InputDevice::dump(String8& dump) {
    InputDeviceInfo deviceInfo;
    getDeviceInfo(& deviceInfo);

    dump.appendFormat(INDENT "Device %d: %s\n", deviceInfo.getId(),
            deviceInfo.getName().string());
    dump.appendFormat(INDENT2 "IsExternal: %s\n", toString(mIsExternal));
    dump.appendFormat(INDENT2 "Sources: 0x%08x\n", deviceInfo.getSources());
    dump.appendFormat(INDENT2 "KeyboardType: %d\n", deviceInfo.getKeyboardType());

    const Vector<InputDeviceInfo::MotionRange>& ranges = deviceInfo.getMotionRanges();
    if (!ranges.isEmpty()) {
        dump.append(INDENT2 "Motion Ranges:\n");
        for (size_t i = 0; i < ranges.size(); i++) {
            const InputDeviceInfo::MotionRange& range = ranges.itemAt(i);
            const char* label = getAxisLabel(range.axis);
            char name[32];
            if (label) {
                strncpy(name, label, sizeof(name));
                name[sizeof(name) - 1] = '\0';
            } else {
                snprintf(name, sizeof(name), "%d", range.axis);
            }
            dump.appendFormat(INDENT3 "%s: source=0x%08x, "
                    "min=%0.3f, max=%0.3f, flat=%0.3f, fuzz=%0.3f\n",
                    name, range.source, range.min, range.max, range.flat, range.fuzz);
        }
    }

    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        mapper->dump(dump);
    }
}

void InputDevice::addMapper(InputMapper* mapper) {
    mMappers.add(mapper);
}

void InputDevice::configure(const InputReaderConfiguration* config, uint32_t changes) {
    mSources = 0;

    if (!isIgnored()) {
        if (!changes) { // first time only
            mContext->getEventHub()->getConfiguration(mId, &mConfiguration);
        }

        size_t numMappers = mMappers.size();
        for (size_t i = 0; i < numMappers; i++) {
            InputMapper* mapper = mMappers[i];
            mapper->configure(config, changes);
            mSources |= mapper->getSources();
        }
    }
}

void InputDevice::reset() {
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        mapper->reset();
    }
}

void InputDevice::process(const RawEvent* rawEvents, size_t count) {
    // Process all of the events in order for each mapper.
    // We cannot simply ask each mapper to process them in bulk because mappers may
    // have side-effects that must be interleaved.  For example, joystick movement events and
    // gamepad button presses are handled by different mappers but they should be dispatched
    // in the order received.
    size_t numMappers = mMappers.size();
    for (const RawEvent* rawEvent = rawEvents; count--; rawEvent++) {
#if DEBUG_RAW_EVENTS
        LOGD("Input event: device=%d type=0x%04x scancode=0x%04x "
                "keycode=0x%04x value=0x%08x flags=0x%08x",
                rawEvent->deviceId, rawEvent->type, rawEvent->scanCode, rawEvent->keyCode,
                rawEvent->value, rawEvent->flags);
#endif

        if (mDropUntilNextSync) {
            if (rawEvent->type == EV_SYN && rawEvent->scanCode == SYN_REPORT) {
                mDropUntilNextSync = false;
#if DEBUG_RAW_EVENTS
                LOGD("Recovered from input event buffer overrun.");
#endif
            } else {
#if DEBUG_RAW_EVENTS
                LOGD("Dropped input event while waiting for next input sync.");
#endif
            }
        } else if (rawEvent->type == EV_SYN && rawEvent->scanCode == SYN_DROPPED) {
            LOGI("Detected input event buffer overrun for device %s.", mName.string());
            mDropUntilNextSync = true;
            reset();
        } else {
            for (size_t i = 0; i < numMappers; i++) {
                InputMapper* mapper = mMappers[i];
                mapper->process(rawEvent);
            }
        }
    }
}

void InputDevice::timeoutExpired(nsecs_t when) {
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        mapper->timeoutExpired(when);
    }
}

void InputDevice::getDeviceInfo(InputDeviceInfo* outDeviceInfo) {
    outDeviceInfo->initialize(mId, mName);

    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        mapper->populateDeviceInfo(outDeviceInfo);
    }
}

int32_t InputDevice::getKeyCodeState(uint32_t sourceMask, int32_t keyCode) {
    return getState(sourceMask, keyCode, & InputMapper::getKeyCodeState);
}

int32_t InputDevice::getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
    return getState(sourceMask, scanCode, & InputMapper::getScanCodeState);
}

int32_t InputDevice::getSwitchState(uint32_t sourceMask, int32_t switchCode) {
    return getState(sourceMask, switchCode, & InputMapper::getSwitchState);
}

int32_t InputDevice::getState(uint32_t sourceMask, int32_t code, GetStateFunc getStateFunc) {
    int32_t result = AKEY_STATE_UNKNOWN;
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        if (sourcesMatchMask(mapper->getSources(), sourceMask)) {
            result = (mapper->*getStateFunc)(sourceMask, code);
            if (result >= AKEY_STATE_DOWN) {
                return result;
            }
        }
    }
    return result;
}

bool InputDevice::markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) {
    bool result = false;
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        if (sourcesMatchMask(mapper->getSources(), sourceMask)) {
            result |= mapper->markSupportedKeyCodes(sourceMask, numCodes, keyCodes, outFlags);
        }
    }
    return result;
}

int32_t InputDevice::getMetaState() {
    int32_t result = 0;
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        result |= mapper->getMetaState();
    }
    return result;
}

void InputDevice::fadePointer() {
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        mapper->fadePointer();
    }
}


// --- InputMapper ---

InputMapper::InputMapper(InputDevice* device) :
        mDevice(device), mContext(device->getContext()) {
}

InputMapper::~InputMapper() {
}

void InputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    info->addSource(getSources());
}

void InputMapper::dump(String8& dump) {
}

void InputMapper::configure(const InputReaderConfiguration* config, uint32_t changes) {
}

void InputMapper::reset() {
}

void InputMapper::timeoutExpired(nsecs_t when) {
}

int32_t InputMapper::getKeyCodeState(uint32_t sourceMask, int32_t keyCode) {
    return AKEY_STATE_UNKNOWN;
}

int32_t InputMapper::getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
    return AKEY_STATE_UNKNOWN;
}

int32_t InputMapper::getSwitchState(uint32_t sourceMask, int32_t switchCode) {
    return AKEY_STATE_UNKNOWN;
}

bool InputMapper::markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) {
    return false;
}

int32_t InputMapper::getMetaState() {
    return 0;
}

void InputMapper::fadePointer() {
}

void InputMapper::dumpRawAbsoluteAxisInfo(String8& dump,
        const RawAbsoluteAxisInfo& axis, const char* name) {
    if (axis.valid) {
        dump.appendFormat(INDENT4 "%s: min=%d, max=%d, flat=%d, fuzz=%d, resolution=%d\n",
                name, axis.minValue, axis.maxValue, axis.flat, axis.fuzz, axis.resolution);
    } else {
        dump.appendFormat(INDENT4 "%s: unknown range\n", name);
    }
}


// --- SwitchInputMapper ---

SwitchInputMapper::SwitchInputMapper(InputDevice* device) :
        InputMapper(device) {
}

SwitchInputMapper::~SwitchInputMapper() {
}

uint32_t SwitchInputMapper::getSources() {
    return AINPUT_SOURCE_SWITCH;
}

void SwitchInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_SW:
        processSwitch(rawEvent->when, rawEvent->scanCode, rawEvent->value);
        break;
    }
}

void SwitchInputMapper::processSwitch(nsecs_t when, int32_t switchCode, int32_t switchValue) {
    getDispatcher()->notifySwitch(when, switchCode, switchValue, 0);
}

int32_t SwitchInputMapper::getSwitchState(uint32_t sourceMask, int32_t switchCode) {
    return getEventHub()->getSwitchState(getDeviceId(), switchCode);
}


// --- KeyboardInputMapper ---

KeyboardInputMapper::KeyboardInputMapper(InputDevice* device,
        uint32_t source, int32_t keyboardType) :
        InputMapper(device), mSource(source),
        mKeyboardType(keyboardType) {
    initializeLocked();
}

KeyboardInputMapper::~KeyboardInputMapper() {
}

void KeyboardInputMapper::initializeLocked() {
    mLocked.metaState = AMETA_NONE;
    mLocked.downTime = 0;
}

uint32_t KeyboardInputMapper::getSources() {
    return mSource;
}

void KeyboardInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    info->setKeyboardType(mKeyboardType);
}

void KeyboardInputMapper::dump(String8& dump) {
    { // acquire lock
        AutoMutex _l(mLock);
        dump.append(INDENT2 "Keyboard Input Mapper:\n");
        dumpParameters(dump);
        dump.appendFormat(INDENT3 "KeyboardType: %d\n", mKeyboardType);
        dump.appendFormat(INDENT3 "KeyDowns: %d keys currently down\n", mLocked.keyDowns.size());
        dump.appendFormat(INDENT3 "MetaState: 0x%0x\n", mLocked.metaState);
        dump.appendFormat(INDENT3 "DownTime: %lld\n", mLocked.downTime);
    } // release lock
}


void KeyboardInputMapper::configure(const InputReaderConfiguration* config, uint32_t changes) {
    InputMapper::configure(config, changes);

    if (!changes) { // first time only
        // Configure basic parameters.
        configureParameters();

        // Reset LEDs.
        {
            AutoMutex _l(mLock);
            resetLedStateLocked();
        }
    }
}

void KeyboardInputMapper::configureParameters() {
    mParameters.orientationAware = false;
    getDevice()->getConfiguration().tryGetProperty(String8("keyboard.orientationAware"),
            mParameters.orientationAware);

    mParameters.associatedDisplayId = mParameters.orientationAware ? 0 : -1;
}

void KeyboardInputMapper::dumpParameters(String8& dump) {
    dump.append(INDENT3 "Parameters:\n");
    dump.appendFormat(INDENT4 "AssociatedDisplayId: %d\n",
            mParameters.associatedDisplayId);
    dump.appendFormat(INDENT4 "OrientationAware: %s\n",
            toString(mParameters.orientationAware));
}

void KeyboardInputMapper::reset() {
    for (;;) {
        int32_t keyCode, scanCode;
        { // acquire lock
            AutoMutex _l(mLock);

            // Synthesize key up event on reset if keys are currently down.
            if (mLocked.keyDowns.isEmpty()) {
                initializeLocked();
                resetLedStateLocked();
                break; // done
            }

            const KeyDown& keyDown = mLocked.keyDowns.top();
            keyCode = keyDown.keyCode;
            scanCode = keyDown.scanCode;
        } // release lock

        nsecs_t when = systemTime(SYSTEM_TIME_MONOTONIC);
        processKey(when, false, keyCode, scanCode, 0);
    }

    InputMapper::reset();
    getContext()->updateGlobalMetaState();
}

void KeyboardInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_KEY: {
        int32_t scanCode = rawEvent->scanCode;
        if (isKeyboardOrGamepadKey(scanCode)) {
            processKey(rawEvent->when, rawEvent->value != 0, rawEvent->keyCode, scanCode,
                    rawEvent->flags);
        }
        break;
    }
    }
}

bool KeyboardInputMapper::isKeyboardOrGamepadKey(int32_t scanCode) {
    return scanCode < BTN_MOUSE
        || scanCode >= KEY_OK
        || (scanCode >= BTN_MISC && scanCode < BTN_MOUSE)
        || (scanCode >= BTN_JOYSTICK && scanCode < BTN_DIGI);
}

void KeyboardInputMapper::processKey(nsecs_t when, bool down, int32_t keyCode,
        int32_t scanCode, uint32_t policyFlags) {
    int32_t newMetaState;
    nsecs_t downTime;
    bool metaStateChanged = false;

    { // acquire lock
        AutoMutex _l(mLock);

        if (down) {
            // Rotate key codes according to orientation if needed.
            // Note: getDisplayInfo is non-reentrant so we can continue holding the lock.
            if (mParameters.orientationAware && mParameters.associatedDisplayId >= 0) {
                int32_t orientation;
                if (!getPolicy()->getDisplayInfo(mParameters.associatedDisplayId,
                        NULL, NULL, & orientation)) {
                    orientation = DISPLAY_ORIENTATION_0;
                }

                keyCode = rotateKeyCode(keyCode, orientation);
            }

            // Add key down.
            ssize_t keyDownIndex = findKeyDownLocked(scanCode);
            if (keyDownIndex >= 0) {
                // key repeat, be sure to use same keycode as before in case of rotation
                keyCode = mLocked.keyDowns.itemAt(keyDownIndex).keyCode;
            } else {
                // key down
                if ((policyFlags & POLICY_FLAG_VIRTUAL)
                        && mContext->shouldDropVirtualKey(when,
                                getDevice(), keyCode, scanCode)) {
                    return;
                }

                mLocked.keyDowns.push();
                KeyDown& keyDown = mLocked.keyDowns.editTop();
                keyDown.keyCode = keyCode;
                keyDown.scanCode = scanCode;
            }

            mLocked.downTime = when;
        } else {
            // Remove key down.
            ssize_t keyDownIndex = findKeyDownLocked(scanCode);
            if (keyDownIndex >= 0) {
                // key up, be sure to use same keycode as before in case of rotation
                keyCode = mLocked.keyDowns.itemAt(keyDownIndex).keyCode;
                mLocked.keyDowns.removeAt(size_t(keyDownIndex));
            } else {
                // key was not actually down
                LOGI("Dropping key up from device %s because the key was not down.  "
                        "keyCode=%d, scanCode=%d",
                        getDeviceName().string(), keyCode, scanCode);
                return;
            }
        }

        int32_t oldMetaState = mLocked.metaState;
        newMetaState = updateMetaState(keyCode, down, oldMetaState);
        if (oldMetaState != newMetaState) {
            mLocked.metaState = newMetaState;
            metaStateChanged = true;
            updateLedStateLocked(false);
        }

        downTime = mLocked.downTime;
    } // release lock

    // Key down on external an keyboard should wake the device.
    // We don't do this for internal keyboards to prevent them from waking up in your pocket.
    // For internal keyboards, the key layout file should specify the policy flags for
    // each wake key individually.
    // TODO: Use the input device configuration to control this behavior more finely.
    if (down && getDevice()->isExternal()
            && !(policyFlags & (POLICY_FLAG_WAKE | POLICY_FLAG_WAKE_DROPPED))) {
        policyFlags |= POLICY_FLAG_WAKE_DROPPED;
    }

    if (metaStateChanged) {
        getContext()->updateGlobalMetaState();
    }

    if (down && !isMetaKey(keyCode)) {
        getContext()->fadePointer();
    }

    getDispatcher()->notifyKey(when, getDeviceId(), mSource, policyFlags,
            down ? AKEY_EVENT_ACTION_DOWN : AKEY_EVENT_ACTION_UP,
            AKEY_EVENT_FLAG_FROM_SYSTEM, keyCode, scanCode, newMetaState, downTime);
}

ssize_t KeyboardInputMapper::findKeyDownLocked(int32_t scanCode) {
    size_t n = mLocked.keyDowns.size();
    for (size_t i = 0; i < n; i++) {
        if (mLocked.keyDowns[i].scanCode == scanCode) {
            return i;
        }
    }
    return -1;
}

int32_t KeyboardInputMapper::getKeyCodeState(uint32_t sourceMask, int32_t keyCode) {
    return getEventHub()->getKeyCodeState(getDeviceId(), keyCode);
}

int32_t KeyboardInputMapper::getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
    return getEventHub()->getScanCodeState(getDeviceId(), scanCode);
}

bool KeyboardInputMapper::markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) {
    return getEventHub()->markSupportedKeyCodes(getDeviceId(), numCodes, keyCodes, outFlags);
}

int32_t KeyboardInputMapper::getMetaState() {
    { // acquire lock
        AutoMutex _l(mLock);
        return mLocked.metaState;
    } // release lock
}

void KeyboardInputMapper::resetLedStateLocked() {
    initializeLedStateLocked(mLocked.capsLockLedState, LED_CAPSL);
    initializeLedStateLocked(mLocked.numLockLedState, LED_NUML);
    initializeLedStateLocked(mLocked.scrollLockLedState, LED_SCROLLL);

    updateLedStateLocked(true);
}

void KeyboardInputMapper::initializeLedStateLocked(LockedState::LedState& ledState, int32_t led) {
    ledState.avail = getEventHub()->hasLed(getDeviceId(), led);
    ledState.on = false;
}

void KeyboardInputMapper::updateLedStateLocked(bool reset) {
    updateLedStateForModifierLocked(mLocked.capsLockLedState, LED_CAPSL,
            AMETA_CAPS_LOCK_ON, reset);
    updateLedStateForModifierLocked(mLocked.numLockLedState, LED_NUML,
            AMETA_NUM_LOCK_ON, reset);
    updateLedStateForModifierLocked(mLocked.scrollLockLedState, LED_SCROLLL,
            AMETA_SCROLL_LOCK_ON, reset);
}

void KeyboardInputMapper::updateLedStateForModifierLocked(LockedState::LedState& ledState,
        int32_t led, int32_t modifier, bool reset) {
    if (ledState.avail) {
        bool desiredState = (mLocked.metaState & modifier) != 0;
        if (reset || ledState.on != desiredState) {
            getEventHub()->setLedState(getDeviceId(), led, desiredState);
            ledState.on = desiredState;
        }
    }
}


// --- CursorInputMapper ---

CursorInputMapper::CursorInputMapper(InputDevice* device) :
        InputMapper(device) {
    initializeLocked();
}

CursorInputMapper::~CursorInputMapper() {
}

uint32_t CursorInputMapper::getSources() {
    return mSource;
}

void CursorInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    if (mParameters.mode == Parameters::MODE_POINTER) {
        float minX, minY, maxX, maxY;
        if (mPointerController->getBounds(&minX, &minY, &maxX, &maxY)) {
            info->addMotionRange(AMOTION_EVENT_AXIS_X, mSource, minX, maxX, 0.0f, 0.0f);
            info->addMotionRange(AMOTION_EVENT_AXIS_Y, mSource, minY, maxY, 0.0f, 0.0f);
        }
    } else {
        info->addMotionRange(AMOTION_EVENT_AXIS_X, mSource, -1.0f, 1.0f, 0.0f, mXScale);
        info->addMotionRange(AMOTION_EVENT_AXIS_Y, mSource, -1.0f, 1.0f, 0.0f, mYScale);
    }
    info->addMotionRange(AMOTION_EVENT_AXIS_PRESSURE, mSource, 0.0f, 1.0f, 0.0f, 0.0f);

    if (mHaveVWheel) {
        info->addMotionRange(AMOTION_EVENT_AXIS_VSCROLL, mSource, -1.0f, 1.0f, 0.0f, 0.0f);
    }
    if (mHaveHWheel) {
        info->addMotionRange(AMOTION_EVENT_AXIS_HSCROLL, mSource, -1.0f, 1.0f, 0.0f, 0.0f);
    }
}

void CursorInputMapper::dump(String8& dump) {
    { // acquire lock
        AutoMutex _l(mLock);
        dump.append(INDENT2 "Cursor Input Mapper:\n");
        dumpParameters(dump);
        dump.appendFormat(INDENT3 "XScale: %0.3f\n", mXScale);
        dump.appendFormat(INDENT3 "YScale: %0.3f\n", mYScale);
        dump.appendFormat(INDENT3 "XPrecision: %0.3f\n", mXPrecision);
        dump.appendFormat(INDENT3 "YPrecision: %0.3f\n", mYPrecision);
        dump.appendFormat(INDENT3 "HaveVWheel: %s\n", toString(mHaveVWheel));
        dump.appendFormat(INDENT3 "HaveHWheel: %s\n", toString(mHaveHWheel));
        dump.appendFormat(INDENT3 "VWheelScale: %0.3f\n", mVWheelScale);
        dump.appendFormat(INDENT3 "HWheelScale: %0.3f\n", mHWheelScale);
        dump.appendFormat(INDENT3 "ButtonState: 0x%08x\n", mLocked.buttonState);
        dump.appendFormat(INDENT3 "Down: %s\n", toString(isPointerDown(mLocked.buttonState)));
        dump.appendFormat(INDENT3 "DownTime: %lld\n", mLocked.downTime);
    } // release lock
}

void CursorInputMapper::configure(const InputReaderConfiguration* config, uint32_t changes) {
    InputMapper::configure(config, changes);

    if (!changes) { // first time only
        // Configure basic parameters.
        configureParameters();

        // Configure device mode.
        switch (mParameters.mode) {
        case Parameters::MODE_POINTER:
            mSource = AINPUT_SOURCE_MOUSE;
            mXPrecision = 1.0f;
            mYPrecision = 1.0f;
            mXScale = 1.0f;
            mYScale = 1.0f;
            mPointerController = getPolicy()->obtainPointerController(getDeviceId());
            break;
        case Parameters::MODE_NAVIGATION:
            mSource = AINPUT_SOURCE_TRACKBALL;
            mXPrecision = TRACKBALL_MOVEMENT_THRESHOLD;
            mYPrecision = TRACKBALL_MOVEMENT_THRESHOLD;
            mXScale = 1.0f / TRACKBALL_MOVEMENT_THRESHOLD;
            mYScale = 1.0f / TRACKBALL_MOVEMENT_THRESHOLD;
            break;
        }

        mVWheelScale = 1.0f;
        mHWheelScale = 1.0f;

        mHaveVWheel = getEventHub()->hasRelativeAxis(getDeviceId(), REL_WHEEL);
        mHaveHWheel = getEventHub()->hasRelativeAxis(getDeviceId(), REL_HWHEEL);
    }

    if (!changes || (changes & InputReaderConfiguration::CHANGE_POINTER_SPEED)) {
        mPointerVelocityControl.setParameters(config->pointerVelocityControlParameters);
        mWheelXVelocityControl.setParameters(config->wheelVelocityControlParameters);
        mWheelYVelocityControl.setParameters(config->wheelVelocityControlParameters);
    }
}

void CursorInputMapper::configureParameters() {
    mParameters.mode = Parameters::MODE_POINTER;
    String8 cursorModeString;
    if (getDevice()->getConfiguration().tryGetProperty(String8("cursor.mode"), cursorModeString)) {
        if (cursorModeString == "navigation") {
            mParameters.mode = Parameters::MODE_NAVIGATION;
        } else if (cursorModeString != "pointer" && cursorModeString != "default") {
            LOGW("Invalid value for cursor.mode: '%s'", cursorModeString.string());
        }
    }

    mParameters.orientationAware = false;
    getDevice()->getConfiguration().tryGetProperty(String8("cursor.orientationAware"),
            mParameters.orientationAware);

    mParameters.associatedDisplayId = mParameters.mode == Parameters::MODE_POINTER
            || mParameters.orientationAware ? 0 : -1;
}

void CursorInputMapper::dumpParameters(String8& dump) {
    dump.append(INDENT3 "Parameters:\n");
    dump.appendFormat(INDENT4 "AssociatedDisplayId: %d\n",
            mParameters.associatedDisplayId);

    switch (mParameters.mode) {
    case Parameters::MODE_POINTER:
        dump.append(INDENT4 "Mode: pointer\n");
        break;
    case Parameters::MODE_NAVIGATION:
        dump.append(INDENT4 "Mode: navigation\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    dump.appendFormat(INDENT4 "OrientationAware: %s\n",
            toString(mParameters.orientationAware));
}

void CursorInputMapper::initializeLocked() {
    mAccumulator.clear();

    mLocked.buttonState = 0;
    mLocked.downTime = 0;
}

void CursorInputMapper::reset() {
    for (;;) {
        int32_t buttonState;
        { // acquire lock
            AutoMutex _l(mLock);

            buttonState = mLocked.buttonState;
            if (!buttonState) {
                initializeLocked();
                break; // done
            }
        } // release lock

        // Reset velocity.
        mPointerVelocityControl.reset();
        mWheelXVelocityControl.reset();
        mWheelYVelocityControl.reset();

        // Synthesize button up event on reset.
        nsecs_t when = systemTime(SYSTEM_TIME_MONOTONIC);
        mAccumulator.clear();
        mAccumulator.buttonDown = 0;
        mAccumulator.buttonUp = buttonState;
        mAccumulator.fields = Accumulator::FIELD_BUTTONS;
        sync(when);
    }

    InputMapper::reset();
}

void CursorInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_KEY: {
        int32_t buttonState = getButtonStateForScanCode(rawEvent->scanCode);
        if (buttonState) {
            if (rawEvent->value) {
                mAccumulator.buttonDown = buttonState;
                mAccumulator.buttonUp = 0;
            } else {
                mAccumulator.buttonDown = 0;
                mAccumulator.buttonUp = buttonState;
            }
            mAccumulator.fields |= Accumulator::FIELD_BUTTONS;

            // Sync now since BTN_MOUSE is not necessarily followed by SYN_REPORT and
            // we need to ensure that we report the up/down promptly.
            sync(rawEvent->when);
            break;
        }
        break;
    }

    case EV_REL:
        switch (rawEvent->scanCode) {
        case REL_X:
            mAccumulator.fields |= Accumulator::FIELD_REL_X;
            mAccumulator.relX = rawEvent->value;
            break;
        case REL_Y:
            mAccumulator.fields |= Accumulator::FIELD_REL_Y;
            mAccumulator.relY = rawEvent->value;
            break;
        case REL_WHEEL:
            mAccumulator.fields |= Accumulator::FIELD_REL_WHEEL;
            mAccumulator.relWheel = rawEvent->value;
            break;
        case REL_HWHEEL:
            mAccumulator.fields |= Accumulator::FIELD_REL_HWHEEL;
            mAccumulator.relHWheel = rawEvent->value;
            break;
        }
        break;

    case EV_SYN:
        switch (rawEvent->scanCode) {
        case SYN_REPORT:
            sync(rawEvent->when);
            break;
        }
        break;
    }
}

void CursorInputMapper::sync(nsecs_t when) {
    uint32_t fields = mAccumulator.fields;
    if (fields == 0) {
        return; // no new state changes, so nothing to do
    }

    int32_t motionEventAction;
    int32_t lastButtonState, currentButtonState;
    PointerProperties pointerProperties;
    PointerCoords pointerCoords;
    nsecs_t downTime;
    float vscroll, hscroll;
    { // acquire lock
        AutoMutex _l(mLock);

        lastButtonState = mLocked.buttonState;

        bool down, downChanged;
        bool wasDown = isPointerDown(mLocked.buttonState);
        bool buttonsChanged = fields & Accumulator::FIELD_BUTTONS;
        if (buttonsChanged) {
            mLocked.buttonState = (mLocked.buttonState | mAccumulator.buttonDown)
                    & ~mAccumulator.buttonUp;

            down = isPointerDown(mLocked.buttonState);

            if (!wasDown && down) {
                mLocked.downTime = when;
                downChanged = true;
            } else if (wasDown && !down) {
                downChanged = true;
            } else {
                downChanged = false;
            }
        } else {
            down = wasDown;
            downChanged = false;
        }

        currentButtonState = mLocked.buttonState;

        downTime = mLocked.downTime;
        float deltaX = fields & Accumulator::FIELD_REL_X ? mAccumulator.relX * mXScale : 0.0f;
        float deltaY = fields & Accumulator::FIELD_REL_Y ? mAccumulator.relY * mYScale : 0.0f;

        if (downChanged) {
            motionEventAction = down ? AMOTION_EVENT_ACTION_DOWN : AMOTION_EVENT_ACTION_UP;
        } else if (down || mPointerController == NULL) {
            motionEventAction = AMOTION_EVENT_ACTION_MOVE;
        } else {
            motionEventAction = AMOTION_EVENT_ACTION_HOVER_MOVE;
        }

        if (mParameters.orientationAware && mParameters.associatedDisplayId >= 0
                && (deltaX != 0.0f || deltaY != 0.0f)) {
            // Rotate motion based on display orientation if needed.
            // Note: getDisplayInfo is non-reentrant so we can continue holding the lock.
            int32_t orientation;
            if (! getPolicy()->getDisplayInfo(mParameters.associatedDisplayId,
                    NULL, NULL, & orientation)) {
                orientation = DISPLAY_ORIENTATION_0;
            }

            rotateDelta(orientation, &deltaX, &deltaY);
        }

        pointerProperties.clear();
        pointerProperties.id = 0;
        pointerProperties.toolType = AMOTION_EVENT_TOOL_TYPE_MOUSE;

        pointerCoords.clear();

        if (mHaveVWheel && (fields & Accumulator::FIELD_REL_WHEEL)) {
            vscroll = mAccumulator.relWheel;
        } else {
            vscroll = 0;
        }
        mWheelYVelocityControl.move(when, NULL, &vscroll);

        if (mHaveHWheel && (fields & Accumulator::FIELD_REL_HWHEEL)) {
            hscroll = mAccumulator.relHWheel;
        } else {
            hscroll = 0;
        }
        mWheelXVelocityControl.move(when, &hscroll, NULL);

        mPointerVelocityControl.move(when, &deltaX, &deltaY);

        if (mPointerController != NULL) {
            if (deltaX != 0 || deltaY != 0 || vscroll != 0 || hscroll != 0
                    || buttonsChanged) {
                mPointerController->setPresentation(
                        PointerControllerInterface::PRESENTATION_POINTER);

                if (deltaX != 0 || deltaY != 0) {
                    mPointerController->move(deltaX, deltaY);
                }

                if (buttonsChanged) {
                    mPointerController->setButtonState(mLocked.buttonState);
                }

                mPointerController->unfade(PointerControllerInterface::TRANSITION_IMMEDIATE);
            }

            float x, y;
            mPointerController->getPosition(&x, &y);
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_X, x);
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        } else {
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_X, deltaX);
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, deltaY);
        }

        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, down ? 1.0f : 0.0f);
    } // release lock

    // Moving an external trackball or mouse should wake the device.
    // We don't do this for internal cursor devices to prevent them from waking up
    // the device in your pocket.
    // TODO: Use the input device configuration to control this behavior more finely.
    uint32_t policyFlags = 0;
    if (getDevice()->isExternal()) {
        policyFlags |= POLICY_FLAG_WAKE_DROPPED;
    }

    // Synthesize key down from buttons if needed.
    synthesizeButtonKeys(getContext(), AKEY_EVENT_ACTION_DOWN, when, getDeviceId(), mSource,
            policyFlags, lastButtonState, currentButtonState);

    // Send motion event.
    int32_t metaState = mContext->getGlobalMetaState();
    getDispatcher()->notifyMotion(when, getDeviceId(), mSource, policyFlags,
            motionEventAction, 0, metaState, currentButtonState, 0,
            1, &pointerProperties, &pointerCoords, mXPrecision, mYPrecision, downTime);

    // Send hover move after UP to tell the application that the mouse is hovering now.
    if (motionEventAction == AMOTION_EVENT_ACTION_UP
            && mPointerController != NULL) {
        getDispatcher()->notifyMotion(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_HOVER_MOVE, 0,
                metaState, currentButtonState, AMOTION_EVENT_EDGE_FLAG_NONE,
                1, &pointerProperties, &pointerCoords, mXPrecision, mYPrecision, downTime);
    }

    // Send scroll events.
    if (vscroll != 0 || hscroll != 0) {
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_VSCROLL, vscroll);
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_HSCROLL, hscroll);

        getDispatcher()->notifyMotion(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_SCROLL, 0, metaState, currentButtonState,
                AMOTION_EVENT_EDGE_FLAG_NONE,
                1, &pointerProperties, &pointerCoords, mXPrecision, mYPrecision, downTime);
    }

    // Synthesize key up from buttons if needed.
    synthesizeButtonKeys(getContext(), AKEY_EVENT_ACTION_UP, when, getDeviceId(), mSource,
            policyFlags, lastButtonState, currentButtonState);

    mAccumulator.clear();
}

int32_t CursorInputMapper::getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
    if (scanCode >= BTN_MOUSE && scanCode < BTN_JOYSTICK) {
        return getEventHub()->getScanCodeState(getDeviceId(), scanCode);
    } else {
        return AKEY_STATE_UNKNOWN;
    }
}

void CursorInputMapper::fadePointer() {
    { // acquire lock
        AutoMutex _l(mLock);
        if (mPointerController != NULL) {
            mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
        }
    } // release lock
}


// --- TouchInputMapper ---

TouchInputMapper::TouchInputMapper(InputDevice* device) :
        InputMapper(device) {
    mLocked.surfaceOrientation = -1;
    mLocked.surfaceWidth = -1;
    mLocked.surfaceHeight = -1;

    initializeLocked();
}

TouchInputMapper::~TouchInputMapper() {
}

uint32_t TouchInputMapper::getSources() {
    return mTouchSource | mPointerSource;
}

void TouchInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    { // acquire lock
        AutoMutex _l(mLock);

        // Ensure surface information is up to date so that orientation changes are
        // noticed immediately.
        if (!configureSurfaceLocked()) {
            return;
        }

        info->addMotionRange(mLocked.orientedRanges.x);
        info->addMotionRange(mLocked.orientedRanges.y);

        if (mLocked.orientedRanges.havePressure) {
            info->addMotionRange(mLocked.orientedRanges.pressure);
        }

        if (mLocked.orientedRanges.haveSize) {
            info->addMotionRange(mLocked.orientedRanges.size);
        }

        if (mLocked.orientedRanges.haveTouchSize) {
            info->addMotionRange(mLocked.orientedRanges.touchMajor);
            info->addMotionRange(mLocked.orientedRanges.touchMinor);
        }

        if (mLocked.orientedRanges.haveToolSize) {
            info->addMotionRange(mLocked.orientedRanges.toolMajor);
            info->addMotionRange(mLocked.orientedRanges.toolMinor);
        }

        if (mLocked.orientedRanges.haveOrientation) {
            info->addMotionRange(mLocked.orientedRanges.orientation);
        }

        if (mLocked.orientedRanges.haveDistance) {
            info->addMotionRange(mLocked.orientedRanges.distance);
        }

        if (mPointerController != NULL) {
            float minX, minY, maxX, maxY;
            if (mPointerController->getBounds(&minX, &minY, &maxX, &maxY)) {
                info->addMotionRange(AMOTION_EVENT_AXIS_X, mPointerSource,
                        minX, maxX, 0.0f, 0.0f);
                info->addMotionRange(AMOTION_EVENT_AXIS_Y, mPointerSource,
                        minY, maxY, 0.0f, 0.0f);
            }
            info->addMotionRange(AMOTION_EVENT_AXIS_PRESSURE, mPointerSource,
                    0.0f, 1.0f, 0.0f, 0.0f);
        }
    } // release lock
}

void TouchInputMapper::dump(String8& dump) {
    { // acquire lock
        AutoMutex _l(mLock);
        dump.append(INDENT2 "Touch Input Mapper:\n");
        dumpParameters(dump);
        dumpVirtualKeysLocked(dump);
        dumpRawAxes(dump);
        dumpCalibration(dump);
        dumpSurfaceLocked(dump);

        dump.appendFormat(INDENT3 "Translation and Scaling Factors:\n");
        dump.appendFormat(INDENT4 "XScale: %0.3f\n", mLocked.xScale);
        dump.appendFormat(INDENT4 "YScale: %0.3f\n", mLocked.yScale);
        dump.appendFormat(INDENT4 "XPrecision: %0.3f\n", mLocked.xPrecision);
        dump.appendFormat(INDENT4 "YPrecision: %0.3f\n", mLocked.yPrecision);
        dump.appendFormat(INDENT4 "GeometricScale: %0.3f\n", mLocked.geometricScale);
        dump.appendFormat(INDENT4 "ToolSizeLinearScale: %0.3f\n", mLocked.toolSizeLinearScale);
        dump.appendFormat(INDENT4 "ToolSizeLinearBias: %0.3f\n", mLocked.toolSizeLinearBias);
        dump.appendFormat(INDENT4 "ToolSizeAreaScale: %0.3f\n", mLocked.toolSizeAreaScale);
        dump.appendFormat(INDENT4 "ToolSizeAreaBias: %0.3f\n", mLocked.toolSizeAreaBias);
        dump.appendFormat(INDENT4 "PressureScale: %0.3f\n", mLocked.pressureScale);
        dump.appendFormat(INDENT4 "SizeScale: %0.3f\n", mLocked.sizeScale);
        dump.appendFormat(INDENT4 "OrientationScale: %0.3f\n", mLocked.orientationScale);
        dump.appendFormat(INDENT4 "DistanceScale: %0.3f\n", mLocked.distanceScale);

        dump.appendFormat(INDENT3 "Last Touch:\n");
        dump.appendFormat(INDENT4 "Button State: 0x%08x\n", mLastTouch.buttonState);
        dump.appendFormat(INDENT4 "Pointer Count: %d\n", mLastTouch.pointerCount);
        for (uint32_t i = 0; i < mLastTouch.pointerCount; i++) {
            const PointerData& pointer = mLastTouch.pointers[i];
            dump.appendFormat(INDENT5 "[%d]: id=%d, x=%d, y=%d, pressure=%d, "
                    "touchMajor=%d, touchMinor=%d, toolMajor=%d, toolMinor=%d, "
                    "orientation=%d, distance=%d, isStylus=%s\n", i,
                    pointer.id, pointer.x, pointer.y, pointer.pressure,
                    pointer.touchMajor, pointer.touchMinor, pointer.toolMajor, pointer.toolMinor,
                    pointer.orientation, pointer.distance, toString(pointer.isStylus));
        }

        if (mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER) {
            dump.appendFormat(INDENT3 "Pointer Gesture Detector:\n");
            dump.appendFormat(INDENT4 "XMovementScale: %0.3f\n",
                    mLocked.pointerGestureXMovementScale);
            dump.appendFormat(INDENT4 "YMovementScale: %0.3f\n",
                    mLocked.pointerGestureYMovementScale);
            dump.appendFormat(INDENT4 "XZoomScale: %0.3f\n",
                    mLocked.pointerGestureXZoomScale);
            dump.appendFormat(INDENT4 "YZoomScale: %0.3f\n",
                    mLocked.pointerGestureYZoomScale);
            dump.appendFormat(INDENT4 "MaxSwipeWidth: %f\n",
                    mLocked.pointerGestureMaxSwipeWidth);
        }
    } // release lock
}

void TouchInputMapper::initializeLocked() {
    mCurrentTouch.clear();
    mLastTouch.clear();
    mDownTime = 0;

    mLocked.currentVirtualKey.down = false;

    mLocked.orientedRanges.havePressure = false;
    mLocked.orientedRanges.haveSize = false;
    mLocked.orientedRanges.haveTouchSize = false;
    mLocked.orientedRanges.haveToolSize = false;
    mLocked.orientedRanges.haveOrientation = false;
    mLocked.orientedRanges.haveDistance = false;

    mPointerGesture.reset();
}

void TouchInputMapper::configure(const InputReaderConfiguration* config, uint32_t changes) {
    InputMapper::configure(config, changes);

    mConfig = *config;

    if (!changes) { // first time only
        // Configure basic parameters.
        configureParameters();

        // Configure sources.
        switch (mParameters.deviceType) {
        case Parameters::DEVICE_TYPE_TOUCH_SCREEN:
            mTouchSource = AINPUT_SOURCE_TOUCHSCREEN;
            mPointerSource = 0;
            break;
        case Parameters::DEVICE_TYPE_TOUCH_PAD:
            mTouchSource = AINPUT_SOURCE_TOUCHPAD;
            mPointerSource = 0;
            break;
        case Parameters::DEVICE_TYPE_POINTER:
            mTouchSource = AINPUT_SOURCE_TOUCHPAD;
            mPointerSource = AINPUT_SOURCE_MOUSE;
            break;
        default:
            LOG_ASSERT(false);
        }

        // Configure absolute axis information.
        configureRawAxes();

        // Prepare input device calibration.
        parseCalibration();
        resolveCalibration();

        { // acquire lock
            AutoMutex _l(mLock);

             // Configure surface dimensions and orientation.
            configureSurfaceLocked();
        } // release lock
    }

    if (!changes || (changes & InputReaderConfiguration::CHANGE_POINTER_SPEED)) {
        mPointerGesture.pointerVelocityControl.setParameters(
                mConfig.pointerVelocityControlParameters);
    }

    if (!changes || (changes & InputReaderConfiguration::CHANGE_POINTER_GESTURE_ENABLEMENT)) {
        // Reset the touch screen when pointer gesture enablement changes.
        reset();
    }
}

void TouchInputMapper::configureParameters() {
    // Use the pointer presentation mode for devices that do not support distinct
    // multitouch.  The spot-based presentation relies on being able to accurately
    // locate two or more fingers on the touch pad.
    mParameters.gestureMode = getEventHub()->hasInputProperty(getDeviceId(), INPUT_PROP_SEMI_MT)
            ? Parameters::GESTURE_MODE_POINTER : Parameters::GESTURE_MODE_SPOTS;

    String8 gestureModeString;
    if (getDevice()->getConfiguration().tryGetProperty(String8("touch.gestureMode"),
            gestureModeString)) {
        if (gestureModeString == "pointer") {
            mParameters.gestureMode = Parameters::GESTURE_MODE_POINTER;
        } else if (gestureModeString == "spots") {
            mParameters.gestureMode = Parameters::GESTURE_MODE_SPOTS;
        } else if (gestureModeString != "default") {
            LOGW("Invalid value for touch.gestureMode: '%s'", gestureModeString.string());
        }
    }

    if (getEventHub()->hasRelativeAxis(getDeviceId(), REL_X)
            || getEventHub()->hasRelativeAxis(getDeviceId(), REL_Y)) {
        // The device is a cursor device with a touch pad attached.
        // By default don't use the touch pad to move the pointer.
        mParameters.deviceType = Parameters::DEVICE_TYPE_TOUCH_PAD;
    } else if (getEventHub()->hasInputProperty(getDeviceId(), INPUT_PROP_POINTER)) {
        // The device is a pointing device like a track pad.
        mParameters.deviceType = Parameters::DEVICE_TYPE_POINTER;
    } else if (getEventHub()->hasInputProperty(getDeviceId(), INPUT_PROP_DIRECT)) {
        // The device is a touch screen.
        mParameters.deviceType = Parameters::DEVICE_TYPE_TOUCH_SCREEN;
    } else {
        // The device is a touch pad of unknown purpose.
        mParameters.deviceType = Parameters::DEVICE_TYPE_POINTER;
    }

    String8 deviceTypeString;
    if (getDevice()->getConfiguration().tryGetProperty(String8("touch.deviceType"),
            deviceTypeString)) {
        if (deviceTypeString == "touchScreen") {
            mParameters.deviceType = Parameters::DEVICE_TYPE_TOUCH_SCREEN;
        } else if (deviceTypeString == "touchPad") {
            mParameters.deviceType = Parameters::DEVICE_TYPE_TOUCH_PAD;
        } else if (deviceTypeString == "pointer") {
            mParameters.deviceType = Parameters::DEVICE_TYPE_POINTER;
        } else if (deviceTypeString != "default") {
            LOGW("Invalid value for touch.deviceType: '%s'", deviceTypeString.string());
        }
    }

    mParameters.orientationAware = mParameters.deviceType == Parameters::DEVICE_TYPE_TOUCH_SCREEN;
    getDevice()->getConfiguration().tryGetProperty(String8("touch.orientationAware"),
            mParameters.orientationAware);

    mParameters.associatedDisplayId = mParameters.orientationAware
            || mParameters.deviceType == Parameters::DEVICE_TYPE_TOUCH_SCREEN
            || mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER
            ? 0 : -1;
}

void TouchInputMapper::dumpParameters(String8& dump) {
    dump.append(INDENT3 "Parameters:\n");

    switch (mParameters.gestureMode) {
    case Parameters::GESTURE_MODE_POINTER:
        dump.append(INDENT4 "GestureMode: pointer\n");
        break;
    case Parameters::GESTURE_MODE_SPOTS:
        dump.append(INDENT4 "GestureMode: spots\n");
        break;
    default:
        assert(false);
    }

    switch (mParameters.deviceType) {
    case Parameters::DEVICE_TYPE_TOUCH_SCREEN:
        dump.append(INDENT4 "DeviceType: touchScreen\n");
        break;
    case Parameters::DEVICE_TYPE_TOUCH_PAD:
        dump.append(INDENT4 "DeviceType: touchPad\n");
        break;
    case Parameters::DEVICE_TYPE_POINTER:
        dump.append(INDENT4 "DeviceType: pointer\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    dump.appendFormat(INDENT4 "AssociatedDisplayId: %d\n",
            mParameters.associatedDisplayId);
    dump.appendFormat(INDENT4 "OrientationAware: %s\n",
            toString(mParameters.orientationAware));
}

void TouchInputMapper::configureRawAxes() {
    mRawAxes.x.clear();
    mRawAxes.y.clear();
    mRawAxes.pressure.clear();
    mRawAxes.touchMajor.clear();
    mRawAxes.touchMinor.clear();
    mRawAxes.toolMajor.clear();
    mRawAxes.toolMinor.clear();
    mRawAxes.orientation.clear();
    mRawAxes.distance.clear();
    mRawAxes.trackingId.clear();
    mRawAxes.slot.clear();
}

void TouchInputMapper::dumpRawAxes(String8& dump) {
    dump.append(INDENT3 "Raw Axes:\n");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.x, "X");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.y, "Y");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.pressure, "Pressure");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.touchMajor, "TouchMajor");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.touchMinor, "TouchMinor");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.toolMajor, "ToolMajor");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.toolMinor, "ToolMinor");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.orientation, "Orientation");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.distance, "Distance");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.trackingId, "TrackingId");
    dumpRawAbsoluteAxisInfo(dump, mRawAxes.slot, "Slot");
}

bool TouchInputMapper::configureSurfaceLocked() {
    // Ensure we have valid X and Y axes.
    if (!mRawAxes.x.valid || !mRawAxes.y.valid) {
        LOGW(INDENT "Touch device '%s' did not report support for X or Y axis!  "
                "The device will be inoperable.", getDeviceName().string());
        return false;
    }

    // Update orientation and dimensions if needed.
    int32_t orientation = DISPLAY_ORIENTATION_0;
    int32_t width = mRawAxes.x.maxValue - mRawAxes.x.minValue + 1;
    int32_t height = mRawAxes.y.maxValue - mRawAxes.y.minValue + 1;

    if (mParameters.associatedDisplayId >= 0) {
        // Note: getDisplayInfo is non-reentrant so we can continue holding the lock.
        if (! getPolicy()->getDisplayInfo(mParameters.associatedDisplayId,
                &mLocked.associatedDisplayWidth, &mLocked.associatedDisplayHeight,
                &mLocked.associatedDisplayOrientation)) {
            return false;
        }

        // A touch screen inherits the dimensions of the display.
        if (mParameters.deviceType == Parameters::DEVICE_TYPE_TOUCH_SCREEN) {
            width = mLocked.associatedDisplayWidth;
            height = mLocked.associatedDisplayHeight;
        }

        // The device inherits the orientation of the display if it is orientation aware.
        if (mParameters.orientationAware) {
            orientation = mLocked.associatedDisplayOrientation;
        }
    }

    if (mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER
            && mPointerController == NULL) {
        mPointerController = getPolicy()->obtainPointerController(getDeviceId());
    }

    bool orientationChanged = mLocked.surfaceOrientation != orientation;
    if (orientationChanged) {
        mLocked.surfaceOrientation = orientation;
    }

    bool sizeChanged = mLocked.surfaceWidth != width || mLocked.surfaceHeight != height;
    if (sizeChanged) {
        LOGI("Device reconfigured: id=%d, name='%s', surface size is now %dx%d",
                getDeviceId(), getDeviceName().string(), width, height);

        mLocked.surfaceWidth = width;
        mLocked.surfaceHeight = height;

        // Configure X and Y factors.
        mLocked.xScale = float(width) / (mRawAxes.x.maxValue - mRawAxes.x.minValue + 1);
        mLocked.yScale = float(height) / (mRawAxes.y.maxValue - mRawAxes.y.minValue + 1);
        mLocked.xPrecision = 1.0f / mLocked.xScale;
        mLocked.yPrecision = 1.0f / mLocked.yScale;

        mLocked.orientedRanges.x.axis = AMOTION_EVENT_AXIS_X;
        mLocked.orientedRanges.x.source = mTouchSource;
        mLocked.orientedRanges.y.axis = AMOTION_EVENT_AXIS_Y;
        mLocked.orientedRanges.y.source = mTouchSource;

        configureVirtualKeysLocked();

        // Scale factor for terms that are not oriented in a particular axis.
        // If the pixels are square then xScale == yScale otherwise we fake it
        // by choosing an average.
        mLocked.geometricScale = avg(mLocked.xScale, mLocked.yScale);

        // Size of diagonal axis.
        float diagonalSize = hypotf(width, height);

        // TouchMajor and TouchMinor factors.
        if (mCalibration.touchSizeCalibration != Calibration::TOUCH_SIZE_CALIBRATION_NONE) {
            mLocked.orientedRanges.haveTouchSize = true;

            mLocked.orientedRanges.touchMajor.axis = AMOTION_EVENT_AXIS_TOUCH_MAJOR;
            mLocked.orientedRanges.touchMajor.source = mTouchSource;
            mLocked.orientedRanges.touchMajor.min = 0;
            mLocked.orientedRanges.touchMajor.max = diagonalSize;
            mLocked.orientedRanges.touchMajor.flat = 0;
            mLocked.orientedRanges.touchMajor.fuzz = 0;

            mLocked.orientedRanges.touchMinor = mLocked.orientedRanges.touchMajor;
            mLocked.orientedRanges.touchMinor.axis = AMOTION_EVENT_AXIS_TOUCH_MINOR;
        }

        // ToolMajor and ToolMinor factors.
        mLocked.toolSizeLinearScale = 0;
        mLocked.toolSizeLinearBias = 0;
        mLocked.toolSizeAreaScale = 0;
        mLocked.toolSizeAreaBias = 0;
        if (mCalibration.toolSizeCalibration != Calibration::TOOL_SIZE_CALIBRATION_NONE) {
            if (mCalibration.toolSizeCalibration == Calibration::TOOL_SIZE_CALIBRATION_LINEAR) {
                if (mCalibration.haveToolSizeLinearScale) {
                    mLocked.toolSizeLinearScale = mCalibration.toolSizeLinearScale;
                } else if (mRawAxes.toolMajor.valid && mRawAxes.toolMajor.maxValue != 0) {
                    mLocked.toolSizeLinearScale = float(min(width, height))
                            / mRawAxes.toolMajor.maxValue;
                }

                if (mCalibration.haveToolSizeLinearBias) {
                    mLocked.toolSizeLinearBias = mCalibration.toolSizeLinearBias;
                }
            } else if (mCalibration.toolSizeCalibration ==
                    Calibration::TOOL_SIZE_CALIBRATION_AREA) {
                if (mCalibration.haveToolSizeLinearScale) {
                    mLocked.toolSizeLinearScale = mCalibration.toolSizeLinearScale;
                } else {
                    mLocked.toolSizeLinearScale = min(width, height);
                }

                if (mCalibration.haveToolSizeLinearBias) {
                    mLocked.toolSizeLinearBias = mCalibration.toolSizeLinearBias;
                }

                if (mCalibration.haveToolSizeAreaScale) {
                    mLocked.toolSizeAreaScale = mCalibration.toolSizeAreaScale;
                } else if (mRawAxes.toolMajor.valid && mRawAxes.toolMajor.maxValue != 0) {
                    mLocked.toolSizeAreaScale = 1.0f / mRawAxes.toolMajor.maxValue;
                }

                if (mCalibration.haveToolSizeAreaBias) {
                    mLocked.toolSizeAreaBias = mCalibration.toolSizeAreaBias;
                }
            }

            mLocked.orientedRanges.haveToolSize = true;

            mLocked.orientedRanges.toolMajor.axis = AMOTION_EVENT_AXIS_TOOL_MAJOR;
            mLocked.orientedRanges.toolMajor.source = mTouchSource;
            mLocked.orientedRanges.toolMajor.min = 0;
            mLocked.orientedRanges.toolMajor.max = diagonalSize;
            mLocked.orientedRanges.toolMajor.flat = 0;
            mLocked.orientedRanges.toolMajor.fuzz = 0;

            mLocked.orientedRanges.toolMinor = mLocked.orientedRanges.toolMajor;
            mLocked.orientedRanges.toolMinor.axis = AMOTION_EVENT_AXIS_TOOL_MINOR;
        }

        // Pressure factors.
        mLocked.pressureScale = 0;
        if (mCalibration.pressureCalibration != Calibration::PRESSURE_CALIBRATION_NONE) {
            RawAbsoluteAxisInfo rawPressureAxis;
            switch (mCalibration.pressureSource) {
            case Calibration::PRESSURE_SOURCE_PRESSURE:
                rawPressureAxis = mRawAxes.pressure;
                break;
            case Calibration::PRESSURE_SOURCE_TOUCH:
                rawPressureAxis = mRawAxes.touchMajor;
                break;
            default:
                rawPressureAxis.clear();
            }

            if (mCalibration.pressureCalibration == Calibration::PRESSURE_CALIBRATION_PHYSICAL
                    || mCalibration.pressureCalibration
                            == Calibration::PRESSURE_CALIBRATION_AMPLITUDE) {
                if (mCalibration.havePressureScale) {
                    mLocked.pressureScale = mCalibration.pressureScale;
                } else if (rawPressureAxis.valid && rawPressureAxis.maxValue != 0) {
                    mLocked.pressureScale = 1.0f / rawPressureAxis.maxValue;
                }
            }

            mLocked.orientedRanges.havePressure = true;

            mLocked.orientedRanges.pressure.axis = AMOTION_EVENT_AXIS_PRESSURE;
            mLocked.orientedRanges.pressure.source = mTouchSource;
            mLocked.orientedRanges.pressure.min = 0;
            mLocked.orientedRanges.pressure.max = 1.0;
            mLocked.orientedRanges.pressure.flat = 0;
            mLocked.orientedRanges.pressure.fuzz = 0;
        }

        // Size factors.
        mLocked.sizeScale = 0;
        if (mCalibration.sizeCalibration != Calibration::SIZE_CALIBRATION_NONE) {
            if (mCalibration.sizeCalibration == Calibration::SIZE_CALIBRATION_NORMALIZED) {
                if (mRawAxes.toolMajor.valid && mRawAxes.toolMajor.maxValue != 0) {
                    mLocked.sizeScale = 1.0f / mRawAxes.toolMajor.maxValue;
                }
            }

            mLocked.orientedRanges.haveSize = true;

            mLocked.orientedRanges.size.axis = AMOTION_EVENT_AXIS_SIZE;
            mLocked.orientedRanges.size.source = mTouchSource;
            mLocked.orientedRanges.size.min = 0;
            mLocked.orientedRanges.size.max = 1.0;
            mLocked.orientedRanges.size.flat = 0;
            mLocked.orientedRanges.size.fuzz = 0;
        }

        // Orientation
        mLocked.orientationScale = 0;
        if (mCalibration.orientationCalibration != Calibration::ORIENTATION_CALIBRATION_NONE) {
            if (mCalibration.orientationCalibration
                    == Calibration::ORIENTATION_CALIBRATION_INTERPOLATED) {
                if (mRawAxes.orientation.valid && mRawAxes.orientation.maxValue != 0) {
                    mLocked.orientationScale = float(M_PI_2) / mRawAxes.orientation.maxValue;
                }
            }

            mLocked.orientedRanges.haveOrientation = true;

            mLocked.orientedRanges.orientation.axis = AMOTION_EVENT_AXIS_ORIENTATION;
            mLocked.orientedRanges.orientation.source = mTouchSource;
            mLocked.orientedRanges.orientation.min = - M_PI_2;
            mLocked.orientedRanges.orientation.max = M_PI_2;
            mLocked.orientedRanges.orientation.flat = 0;
            mLocked.orientedRanges.orientation.fuzz = 0;
        }

        // Distance
        mLocked.distanceScale = 0;
        if (mCalibration.distanceCalibration != Calibration::DISTANCE_CALIBRATION_NONE) {
            if (mCalibration.distanceCalibration
                    == Calibration::DISTANCE_CALIBRATION_SCALED) {
                if (mCalibration.haveDistanceScale) {
                    mLocked.distanceScale = mCalibration.distanceScale;
                } else {
                    mLocked.distanceScale = 1.0f;
                }
            }

            mLocked.orientedRanges.haveDistance = true;

            mLocked.orientedRanges.distance.axis = AMOTION_EVENT_AXIS_DISTANCE;
            mLocked.orientedRanges.distance.source = mTouchSource;
            mLocked.orientedRanges.distance.min =
                    mRawAxes.distance.minValue * mLocked.distanceScale;
            mLocked.orientedRanges.distance.max =
                    mRawAxes.distance.minValue * mLocked.distanceScale;
            mLocked.orientedRanges.distance.flat = 0;
            mLocked.orientedRanges.distance.fuzz =
                    mRawAxes.distance.fuzz * mLocked.distanceScale;
        }
    }

    if (orientationChanged || sizeChanged) {
        // Compute oriented surface dimensions, precision, scales and ranges.
        // Note that the maximum value reported is an inclusive maximum value so it is one
        // unit less than the total width or height of surface.
        switch (mLocked.surfaceOrientation) {
        case DISPLAY_ORIENTATION_90:
        case DISPLAY_ORIENTATION_270:
            mLocked.orientedSurfaceWidth = mLocked.surfaceHeight;
            mLocked.orientedSurfaceHeight = mLocked.surfaceWidth;

            mLocked.orientedXPrecision = mLocked.yPrecision;
            mLocked.orientedYPrecision = mLocked.xPrecision;

            mLocked.orientedRanges.x.min = 0;
            mLocked.orientedRanges.x.max = (mRawAxes.y.maxValue - mRawAxes.y.minValue)
                    * mLocked.yScale;
            mLocked.orientedRanges.x.flat = 0;
            mLocked.orientedRanges.x.fuzz = mLocked.yScale;

            mLocked.orientedRanges.y.min = 0;
            mLocked.orientedRanges.y.max = (mRawAxes.x.maxValue - mRawAxes.x.minValue)
                    * mLocked.xScale;
            mLocked.orientedRanges.y.flat = 0;
            mLocked.orientedRanges.y.fuzz = mLocked.xScale;
            break;

        default:
            mLocked.orientedSurfaceWidth = mLocked.surfaceWidth;
            mLocked.orientedSurfaceHeight = mLocked.surfaceHeight;

            mLocked.orientedXPrecision = mLocked.xPrecision;
            mLocked.orientedYPrecision = mLocked.yPrecision;

            mLocked.orientedRanges.x.min = 0;
            mLocked.orientedRanges.x.max = (mRawAxes.x.maxValue - mRawAxes.x.minValue)
                    * mLocked.xScale;
            mLocked.orientedRanges.x.flat = 0;
            mLocked.orientedRanges.x.fuzz = mLocked.xScale;

            mLocked.orientedRanges.y.min = 0;
            mLocked.orientedRanges.y.max = (mRawAxes.y.maxValue - mRawAxes.y.minValue)
                    * mLocked.yScale;
            mLocked.orientedRanges.y.flat = 0;
            mLocked.orientedRanges.y.fuzz = mLocked.yScale;
            break;
        }

        // Compute pointer gesture detection parameters.
        // TODO: These factors should not be hardcoded.
        if (mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER) {
            int32_t rawWidth = mRawAxes.x.maxValue - mRawAxes.x.minValue + 1;
            int32_t rawHeight = mRawAxes.y.maxValue - mRawAxes.y.minValue + 1;
            float rawDiagonal = hypotf(rawWidth, rawHeight);
            float displayDiagonal = hypotf(mLocked.associatedDisplayWidth,
                    mLocked.associatedDisplayHeight);

            // Scale movements such that one whole swipe of the touch pad covers a
            // given area relative to the diagonal size of the display when no acceleration
            // is applied.
            // Assume that the touch pad has a square aspect ratio such that movements in
            // X and Y of the same number of raw units cover the same physical distance.
            mLocked.pointerGestureXMovementScale = mConfig.pointerGestureMovementSpeedRatio
                    * displayDiagonal / rawDiagonal;
            mLocked.pointerGestureYMovementScale = mLocked.pointerGestureXMovementScale;

            // Scale zooms to cover a smaller range of the display than movements do.
            // This value determines the area around the pointer that is affected by freeform
            // pointer gestures.
            mLocked.pointerGestureXZoomScale = mConfig.pointerGestureZoomSpeedRatio
                    * displayDiagonal / rawDiagonal;
            mLocked.pointerGestureYZoomScale = mLocked.pointerGestureXZoomScale;

            // Max width between pointers to detect a swipe gesture is more than some fraction
            // of the diagonal axis of the touch pad.  Touches that are wider than this are
            // translated into freeform gestures.
            mLocked.pointerGestureMaxSwipeWidth =
                    mConfig.pointerGestureSwipeMaxWidthRatio * rawDiagonal;

            // Reset the current pointer gesture.
            mPointerGesture.reset();

            // Remove any current spots.
            if (mParameters.gestureMode == Parameters::GESTURE_MODE_SPOTS) {
                mPointerController->clearSpots();
            }
        }
    }

    return true;
}

void TouchInputMapper::dumpSurfaceLocked(String8& dump) {
    dump.appendFormat(INDENT3 "SurfaceWidth: %dpx\n", mLocked.surfaceWidth);
    dump.appendFormat(INDENT3 "SurfaceHeight: %dpx\n", mLocked.surfaceHeight);
    dump.appendFormat(INDENT3 "SurfaceOrientation: %d\n", mLocked.surfaceOrientation);
}

void TouchInputMapper::configureVirtualKeysLocked() {
    Vector<VirtualKeyDefinition> virtualKeyDefinitions;
    getEventHub()->getVirtualKeyDefinitions(getDeviceId(), virtualKeyDefinitions);

    mLocked.virtualKeys.clear();

    if (virtualKeyDefinitions.size() == 0) {
        return;
    }

    mLocked.virtualKeys.setCapacity(virtualKeyDefinitions.size());

    int32_t touchScreenLeft = mRawAxes.x.minValue;
    int32_t touchScreenTop = mRawAxes.y.minValue;
    int32_t touchScreenWidth = mRawAxes.x.maxValue - mRawAxes.x.minValue + 1;
    int32_t touchScreenHeight = mRawAxes.y.maxValue - mRawAxes.y.minValue + 1;

    for (size_t i = 0; i < virtualKeyDefinitions.size(); i++) {
        const VirtualKeyDefinition& virtualKeyDefinition =
                virtualKeyDefinitions[i];

        mLocked.virtualKeys.add();
        VirtualKey& virtualKey = mLocked.virtualKeys.editTop();

        virtualKey.scanCode = virtualKeyDefinition.scanCode;
        int32_t keyCode;
        uint32_t flags;
        if (getEventHub()->mapKey(getDeviceId(), virtualKey.scanCode,
                & keyCode, & flags)) {
            LOGW(INDENT "VirtualKey %d: could not obtain key code, ignoring",
                    virtualKey.scanCode);
            mLocked.virtualKeys.pop(); // drop the key
            continue;
        }

        virtualKey.keyCode = keyCode;
        virtualKey.flags = flags;

        // convert the key definition's display coordinates into touch coordinates for a hit box
        int32_t halfWidth = virtualKeyDefinition.width / 2;
        int32_t halfHeight = virtualKeyDefinition.height / 2;

        virtualKey.hitLeft = (virtualKeyDefinition.centerX - halfWidth)
                * touchScreenWidth / mLocked.surfaceWidth + touchScreenLeft;
        virtualKey.hitRight= (virtualKeyDefinition.centerX + halfWidth)
                * touchScreenWidth / mLocked.surfaceWidth + touchScreenLeft;
        virtualKey.hitTop = (virtualKeyDefinition.centerY - halfHeight)
                * touchScreenHeight / mLocked.surfaceHeight + touchScreenTop;
        virtualKey.hitBottom = (virtualKeyDefinition.centerY + halfHeight)
                * touchScreenHeight / mLocked.surfaceHeight + touchScreenTop;
    }
}

void TouchInputMapper::dumpVirtualKeysLocked(String8& dump) {
    if (!mLocked.virtualKeys.isEmpty()) {
        dump.append(INDENT3 "Virtual Keys:\n");

        for (size_t i = 0; i < mLocked.virtualKeys.size(); i++) {
            const VirtualKey& virtualKey = mLocked.virtualKeys.itemAt(i);
            dump.appendFormat(INDENT4 "%d: scanCode=%d, keyCode=%d, "
                    "hitLeft=%d, hitRight=%d, hitTop=%d, hitBottom=%d\n",
                    i, virtualKey.scanCode, virtualKey.keyCode,
                    virtualKey.hitLeft, virtualKey.hitRight,
                    virtualKey.hitTop, virtualKey.hitBottom);
        }
    }
}

void TouchInputMapper::parseCalibration() {
    const PropertyMap& in = getDevice()->getConfiguration();
    Calibration& out = mCalibration;

    // Touch Size
    out.touchSizeCalibration = Calibration::TOUCH_SIZE_CALIBRATION_DEFAULT;
    String8 touchSizeCalibrationString;
    if (in.tryGetProperty(String8("touch.touchSize.calibration"), touchSizeCalibrationString)) {
        if (touchSizeCalibrationString == "none") {
            out.touchSizeCalibration = Calibration::TOUCH_SIZE_CALIBRATION_NONE;
        } else if (touchSizeCalibrationString == "geometric") {
            out.touchSizeCalibration = Calibration::TOUCH_SIZE_CALIBRATION_GEOMETRIC;
        } else if (touchSizeCalibrationString == "pressure") {
            out.touchSizeCalibration = Calibration::TOUCH_SIZE_CALIBRATION_PRESSURE;
        } else if (touchSizeCalibrationString != "default") {
            LOGW("Invalid value for touch.touchSize.calibration: '%s'",
                    touchSizeCalibrationString.string());
        }
    }

    // Tool Size
    out.toolSizeCalibration = Calibration::TOOL_SIZE_CALIBRATION_DEFAULT;
    String8 toolSizeCalibrationString;
    if (in.tryGetProperty(String8("touch.toolSize.calibration"), toolSizeCalibrationString)) {
        if (toolSizeCalibrationString == "none") {
            out.toolSizeCalibration = Calibration::TOOL_SIZE_CALIBRATION_NONE;
        } else if (toolSizeCalibrationString == "geometric") {
            out.toolSizeCalibration = Calibration::TOOL_SIZE_CALIBRATION_GEOMETRIC;
        } else if (toolSizeCalibrationString == "linear") {
            out.toolSizeCalibration = Calibration::TOOL_SIZE_CALIBRATION_LINEAR;
        } else if (toolSizeCalibrationString == "area") {
            out.toolSizeCalibration = Calibration::TOOL_SIZE_CALIBRATION_AREA;
        } else if (toolSizeCalibrationString != "default") {
            LOGW("Invalid value for touch.toolSize.calibration: '%s'",
                    toolSizeCalibrationString.string());
        }
    }

    out.haveToolSizeLinearScale = in.tryGetProperty(String8("touch.toolSize.linearScale"),
            out.toolSizeLinearScale);
    out.haveToolSizeLinearBias = in.tryGetProperty(String8("touch.toolSize.linearBias"),
            out.toolSizeLinearBias);
    out.haveToolSizeAreaScale = in.tryGetProperty(String8("touch.toolSize.areaScale"),
            out.toolSizeAreaScale);
    out.haveToolSizeAreaBias = in.tryGetProperty(String8("touch.toolSize.areaBias"),
            out.toolSizeAreaBias);
    out.haveToolSizeIsSummed = in.tryGetProperty(String8("touch.toolSize.isSummed"),
            out.toolSizeIsSummed);

    // Pressure
    out.pressureCalibration = Calibration::PRESSURE_CALIBRATION_DEFAULT;
    String8 pressureCalibrationString;
    if (in.tryGetProperty(String8("touch.pressure.calibration"), pressureCalibrationString)) {
        if (pressureCalibrationString == "none") {
            out.pressureCalibration = Calibration::PRESSURE_CALIBRATION_NONE;
        } else if (pressureCalibrationString == "physical") {
            out.pressureCalibration = Calibration::PRESSURE_CALIBRATION_PHYSICAL;
        } else if (pressureCalibrationString == "amplitude") {
            out.pressureCalibration = Calibration::PRESSURE_CALIBRATION_AMPLITUDE;
        } else if (pressureCalibrationString != "default") {
            LOGW("Invalid value for touch.pressure.calibration: '%s'",
                    pressureCalibrationString.string());
        }
    }

    out.pressureSource = Calibration::PRESSURE_SOURCE_DEFAULT;
    String8 pressureSourceString;
    if (in.tryGetProperty(String8("touch.pressure.source"), pressureSourceString)) {
        if (pressureSourceString == "pressure") {
            out.pressureSource = Calibration::PRESSURE_SOURCE_PRESSURE;
        } else if (pressureSourceString == "touch") {
            out.pressureSource = Calibration::PRESSURE_SOURCE_TOUCH;
        } else if (pressureSourceString != "default") {
            LOGW("Invalid value for touch.pressure.source: '%s'",
                    pressureSourceString.string());
        }
    }

    out.havePressureScale = in.tryGetProperty(String8("touch.pressure.scale"),
            out.pressureScale);

    // Size
    out.sizeCalibration = Calibration::SIZE_CALIBRATION_DEFAULT;
    String8 sizeCalibrationString;
    if (in.tryGetProperty(String8("touch.size.calibration"), sizeCalibrationString)) {
        if (sizeCalibrationString == "none") {
            out.sizeCalibration = Calibration::SIZE_CALIBRATION_NONE;
        } else if (sizeCalibrationString == "normalized") {
            out.sizeCalibration = Calibration::SIZE_CALIBRATION_NORMALIZED;
        } else if (sizeCalibrationString != "default") {
            LOGW("Invalid value for touch.size.calibration: '%s'",
                    sizeCalibrationString.string());
        }
    }

    // Orientation
    out.orientationCalibration = Calibration::ORIENTATION_CALIBRATION_DEFAULT;
    String8 orientationCalibrationString;
    if (in.tryGetProperty(String8("touch.orientation.calibration"), orientationCalibrationString)) {
        if (orientationCalibrationString == "none") {
            out.orientationCalibration = Calibration::ORIENTATION_CALIBRATION_NONE;
        } else if (orientationCalibrationString == "interpolated") {
            out.orientationCalibration = Calibration::ORIENTATION_CALIBRATION_INTERPOLATED;
        } else if (orientationCalibrationString == "vector") {
            out.orientationCalibration = Calibration::ORIENTATION_CALIBRATION_VECTOR;
        } else if (orientationCalibrationString != "default") {
            LOGW("Invalid value for touch.orientation.calibration: '%s'",
                    orientationCalibrationString.string());
        }
    }

    // Distance
    out.distanceCalibration = Calibration::DISTANCE_CALIBRATION_DEFAULT;
    String8 distanceCalibrationString;
    if (in.tryGetProperty(String8("touch.distance.calibration"), distanceCalibrationString)) {
        if (distanceCalibrationString == "none") {
            out.distanceCalibration = Calibration::DISTANCE_CALIBRATION_NONE;
        } else if (distanceCalibrationString == "scaled") {
            out.distanceCalibration = Calibration::DISTANCE_CALIBRATION_SCALED;
        } else if (distanceCalibrationString != "default") {
            LOGW("Invalid value for touch.distance.calibration: '%s'",
                    distanceCalibrationString.string());
        }
    }

    out.haveDistanceScale = in.tryGetProperty(String8("touch.distance.scale"),
            out.distanceScale);
}

void TouchInputMapper::resolveCalibration() {
    // Pressure
    switch (mCalibration.pressureSource) {
    case Calibration::PRESSURE_SOURCE_DEFAULT:
        if (mRawAxes.pressure.valid) {
            mCalibration.pressureSource = Calibration::PRESSURE_SOURCE_PRESSURE;
        } else if (mRawAxes.touchMajor.valid) {
            mCalibration.pressureSource = Calibration::PRESSURE_SOURCE_TOUCH;
        }
        break;

    case Calibration::PRESSURE_SOURCE_PRESSURE:
        if (! mRawAxes.pressure.valid) {
            LOGW("Calibration property touch.pressure.source is 'pressure' but "
                    "the pressure axis is not available.");
        }
        break;

    case Calibration::PRESSURE_SOURCE_TOUCH:
        if (! mRawAxes.touchMajor.valid) {
            LOGW("Calibration property touch.pressure.source is 'touch' but "
                    "the touchMajor axis is not available.");
        }
        break;

    default:
        break;
    }

    switch (mCalibration.pressureCalibration) {
    case Calibration::PRESSURE_CALIBRATION_DEFAULT:
        if (mCalibration.pressureSource != Calibration::PRESSURE_SOURCE_DEFAULT) {
            mCalibration.pressureCalibration = Calibration::PRESSURE_CALIBRATION_AMPLITUDE;
        } else {
            mCalibration.pressureCalibration = Calibration::PRESSURE_CALIBRATION_NONE;
        }
        break;

    default:
        break;
    }

    // Tool Size
    switch (mCalibration.toolSizeCalibration) {
    case Calibration::TOOL_SIZE_CALIBRATION_DEFAULT:
        if (mRawAxes.toolMajor.valid) {
            mCalibration.toolSizeCalibration = Calibration::TOOL_SIZE_CALIBRATION_LINEAR;
        } else {
            mCalibration.toolSizeCalibration = Calibration::TOOL_SIZE_CALIBRATION_NONE;
        }
        break;

    default:
        break;
    }

    // Touch Size
    switch (mCalibration.touchSizeCalibration) {
    case Calibration::TOUCH_SIZE_CALIBRATION_DEFAULT:
        if (mCalibration.pressureCalibration != Calibration::PRESSURE_CALIBRATION_NONE
                && mCalibration.toolSizeCalibration != Calibration::TOOL_SIZE_CALIBRATION_NONE) {
            mCalibration.touchSizeCalibration = Calibration::TOUCH_SIZE_CALIBRATION_PRESSURE;
        } else {
            mCalibration.touchSizeCalibration = Calibration::TOUCH_SIZE_CALIBRATION_NONE;
        }
        break;

    default:
        break;
    }

    // Size
    switch (mCalibration.sizeCalibration) {
    case Calibration::SIZE_CALIBRATION_DEFAULT:
        if (mRawAxes.toolMajor.valid) {
            mCalibration.sizeCalibration = Calibration::SIZE_CALIBRATION_NORMALIZED;
        } else {
            mCalibration.sizeCalibration = Calibration::SIZE_CALIBRATION_NONE;
        }
        break;

    default:
        break;
    }

    // Orientation
    switch (mCalibration.orientationCalibration) {
    case Calibration::ORIENTATION_CALIBRATION_DEFAULT:
        if (mRawAxes.orientation.valid) {
            mCalibration.orientationCalibration = Calibration::ORIENTATION_CALIBRATION_INTERPOLATED;
        } else {
            mCalibration.orientationCalibration = Calibration::ORIENTATION_CALIBRATION_NONE;
        }
        break;

    default:
        break;
    }

    // Distance
    switch (mCalibration.distanceCalibration) {
    case Calibration::DISTANCE_CALIBRATION_DEFAULT:
        if (mRawAxes.distance.valid) {
            mCalibration.distanceCalibration = Calibration::DISTANCE_CALIBRATION_SCALED;
        } else {
            mCalibration.distanceCalibration = Calibration::DISTANCE_CALIBRATION_NONE;
        }
        break;

    default:
        break;
    }
}

void TouchInputMapper::dumpCalibration(String8& dump) {
    dump.append(INDENT3 "Calibration:\n");

    // Touch Size
    switch (mCalibration.touchSizeCalibration) {
    case Calibration::TOUCH_SIZE_CALIBRATION_NONE:
        dump.append(INDENT4 "touch.touchSize.calibration: none\n");
        break;
    case Calibration::TOUCH_SIZE_CALIBRATION_GEOMETRIC:
        dump.append(INDENT4 "touch.touchSize.calibration: geometric\n");
        break;
    case Calibration::TOUCH_SIZE_CALIBRATION_PRESSURE:
        dump.append(INDENT4 "touch.touchSize.calibration: pressure\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    // Tool Size
    switch (mCalibration.toolSizeCalibration) {
    case Calibration::TOOL_SIZE_CALIBRATION_NONE:
        dump.append(INDENT4 "touch.toolSize.calibration: none\n");
        break;
    case Calibration::TOOL_SIZE_CALIBRATION_GEOMETRIC:
        dump.append(INDENT4 "touch.toolSize.calibration: geometric\n");
        break;
    case Calibration::TOOL_SIZE_CALIBRATION_LINEAR:
        dump.append(INDENT4 "touch.toolSize.calibration: linear\n");
        break;
    case Calibration::TOOL_SIZE_CALIBRATION_AREA:
        dump.append(INDENT4 "touch.toolSize.calibration: area\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    if (mCalibration.haveToolSizeLinearScale) {
        dump.appendFormat(INDENT4 "touch.toolSize.linearScale: %0.3f\n",
                mCalibration.toolSizeLinearScale);
    }

    if (mCalibration.haveToolSizeLinearBias) {
        dump.appendFormat(INDENT4 "touch.toolSize.linearBias: %0.3f\n",
                mCalibration.toolSizeLinearBias);
    }

    if (mCalibration.haveToolSizeAreaScale) {
        dump.appendFormat(INDENT4 "touch.toolSize.areaScale: %0.3f\n",
                mCalibration.toolSizeAreaScale);
    }

    if (mCalibration.haveToolSizeAreaBias) {
        dump.appendFormat(INDENT4 "touch.toolSize.areaBias: %0.3f\n",
                mCalibration.toolSizeAreaBias);
    }

    if (mCalibration.haveToolSizeIsSummed) {
        dump.appendFormat(INDENT4 "touch.toolSize.isSummed: %s\n",
                toString(mCalibration.toolSizeIsSummed));
    }

    // Pressure
    switch (mCalibration.pressureCalibration) {
    case Calibration::PRESSURE_CALIBRATION_NONE:
        dump.append(INDENT4 "touch.pressure.calibration: none\n");
        break;
    case Calibration::PRESSURE_CALIBRATION_PHYSICAL:
        dump.append(INDENT4 "touch.pressure.calibration: physical\n");
        break;
    case Calibration::PRESSURE_CALIBRATION_AMPLITUDE:
        dump.append(INDENT4 "touch.pressure.calibration: amplitude\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    switch (mCalibration.pressureSource) {
    case Calibration::PRESSURE_SOURCE_PRESSURE:
        dump.append(INDENT4 "touch.pressure.source: pressure\n");
        break;
    case Calibration::PRESSURE_SOURCE_TOUCH:
        dump.append(INDENT4 "touch.pressure.source: touch\n");
        break;
    case Calibration::PRESSURE_SOURCE_DEFAULT:
        break;
    default:
        LOG_ASSERT(false);
    }

    if (mCalibration.havePressureScale) {
        dump.appendFormat(INDENT4 "touch.pressure.scale: %0.3f\n",
                mCalibration.pressureScale);
    }

    // Size
    switch (mCalibration.sizeCalibration) {
    case Calibration::SIZE_CALIBRATION_NONE:
        dump.append(INDENT4 "touch.size.calibration: none\n");
        break;
    case Calibration::SIZE_CALIBRATION_NORMALIZED:
        dump.append(INDENT4 "touch.size.calibration: normalized\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    // Orientation
    switch (mCalibration.orientationCalibration) {
    case Calibration::ORIENTATION_CALIBRATION_NONE:
        dump.append(INDENT4 "touch.orientation.calibration: none\n");
        break;
    case Calibration::ORIENTATION_CALIBRATION_INTERPOLATED:
        dump.append(INDENT4 "touch.orientation.calibration: interpolated\n");
        break;
    case Calibration::ORIENTATION_CALIBRATION_VECTOR:
        dump.append(INDENT4 "touch.orientation.calibration: vector\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    // Distance
    switch (mCalibration.distanceCalibration) {
    case Calibration::DISTANCE_CALIBRATION_NONE:
        dump.append(INDENT4 "touch.distance.calibration: none\n");
        break;
    case Calibration::DISTANCE_CALIBRATION_SCALED:
        dump.append(INDENT4 "touch.distance.calibration: scaled\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    if (mCalibration.haveDistanceScale) {
        dump.appendFormat(INDENT4 "touch.distance.scale: %0.3f\n",
                mCalibration.distanceScale);
    }
}

void TouchInputMapper::reset() {
    // Synthesize touch up event if touch is currently down.
    // This will also take care of finishing virtual key processing if needed.
    if (mLastTouch.pointerCount != 0) {
        nsecs_t when = systemTime(SYSTEM_TIME_MONOTONIC);
        mCurrentTouch.clear();
        syncTouch(when, true);
    }

    { // acquire lock
        AutoMutex _l(mLock);
        initializeLocked();

        if (mPointerController != NULL
                && mParameters.gestureMode == Parameters::GESTURE_MODE_SPOTS) {
            mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
            mPointerController->clearSpots();
        }
    } // release lock

    InputMapper::reset();
}

void TouchInputMapper::syncTouch(nsecs_t when, bool havePointerIds) {
#if DEBUG_RAW_EVENTS
    if (!havePointerIds) {
        LOGD("syncTouch: pointerCount=%d, no pointer ids", mCurrentTouch.pointerCount);
    } else {
        LOGD("syncTouch: pointerCount=%d, up=0x%08x, down=0x%08x, move=0x%08x, "
                "last=0x%08x, current=0x%08x", mCurrentTouch.pointerCount,
                mLastTouch.idBits.value & ~mCurrentTouch.idBits.value,
                mCurrentTouch.idBits.value & ~mLastTouch.idBits.value,
                mLastTouch.idBits.value & mCurrentTouch.idBits.value,
                mLastTouch.idBits.value, mCurrentTouch.idBits.value);
    }
#endif

    // Preprocess pointer data.
    if (!havePointerIds) {
        calculatePointerIds();
    }

    uint32_t policyFlags = 0;
    if (mLastTouch.pointerCount == 0 && mCurrentTouch.pointerCount != 0) {
        if (mParameters.deviceType == Parameters::DEVICE_TYPE_TOUCH_SCREEN) {
            // If this is a touch screen, hide the pointer on an initial down.
            getContext()->fadePointer();
        }

        // Initial downs on external touch devices should wake the device.
        // We don't do this for internal touch screens to prevent them from waking
        // up in your pocket.
        // TODO: Use the input device configuration to control this behavior more finely.
        if (getDevice()->isExternal()) {
            policyFlags |= POLICY_FLAG_WAKE_DROPPED;
        }
    }

    // Synthesize key down from buttons if needed.
    synthesizeButtonKeys(getContext(), AKEY_EVENT_ACTION_DOWN, when, getDeviceId(), mTouchSource,
            policyFlags, mLastTouch.buttonState, mCurrentTouch.buttonState);

    // Send motion events.
    TouchResult touchResult;
    if (mLastTouch.pointerCount == 0 && mCurrentTouch.pointerCount == 0
            && mLastTouch.buttonState == mCurrentTouch.buttonState) {
        // Drop spurious syncs.
        touchResult = DROP_STROKE;
    } else {
        // Process touches and virtual keys.
        touchResult = consumeOffScreenTouches(when, policyFlags);
        if (touchResult == DISPATCH_TOUCH) {
            suppressSwipeOntoVirtualKeys(when);
            if (mPointerController != NULL && mConfig.pointerGesturesEnabled) {
                dispatchPointerGestures(when, policyFlags, false /*isTimeout*/);
            }
            dispatchTouches(when, policyFlags);
        }
    }

    // Synthesize key up from buttons if needed.
    synthesizeButtonKeys(getContext(), AKEY_EVENT_ACTION_UP, when, getDeviceId(), mTouchSource,
            policyFlags, mLastTouch.buttonState, mCurrentTouch.buttonState);

    // Copy current touch to last touch in preparation for the next cycle.
    // Keep the button state so we can track edge-triggered button state changes.
    if (touchResult == DROP_STROKE) {
        mLastTouch.clear();
        mLastTouch.buttonState = mCurrentTouch.buttonState;
    } else {
        mLastTouch.copyFrom(mCurrentTouch);
    }
}

void TouchInputMapper::timeoutExpired(nsecs_t when) {
    if (mPointerController != NULL) {
        dispatchPointerGestures(when, 0 /*policyFlags*/, true /*isTimeout*/);
    }
}

TouchInputMapper::TouchResult TouchInputMapper::consumeOffScreenTouches(
        nsecs_t when, uint32_t policyFlags) {
    int32_t keyEventAction, keyEventFlags;
    int32_t keyCode, scanCode, downTime;
    TouchResult touchResult;

    { // acquire lock
        AutoMutex _l(mLock);

        // Update surface size and orientation, including virtual key positions.
        if (! configureSurfaceLocked()) {
            return DROP_STROKE;
        }

        // Check for virtual key press.
        if (mLocked.currentVirtualKey.down) {
            if (mCurrentTouch.pointerCount == 0) {
                // Pointer went up while virtual key was down.
                mLocked.currentVirtualKey.down = false;
#if DEBUG_VIRTUAL_KEYS
                LOGD("VirtualKeys: Generating key up: keyCode=%d, scanCode=%d",
                        mLocked.currentVirtualKey.keyCode, mLocked.currentVirtualKey.scanCode);
#endif
                keyEventAction = AKEY_EVENT_ACTION_UP;
                keyEventFlags = AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY;
                touchResult = SKIP_TOUCH;
                goto DispatchVirtualKey;
            }

            if (mCurrentTouch.pointerCount == 1) {
                int32_t x = mCurrentTouch.pointers[0].x;
                int32_t y = mCurrentTouch.pointers[0].y;
                const VirtualKey* virtualKey = findVirtualKeyHitLocked(x, y);
                if (virtualKey && virtualKey->keyCode == mLocked.currentVirtualKey.keyCode) {
                    // Pointer is still within the space of the virtual key.
                    return SKIP_TOUCH;
                }
            }

            // Pointer left virtual key area or another pointer also went down.
            // Send key cancellation and drop the stroke so subsequent motions will be
            // considered fresh downs.  This is useful when the user swipes away from the
            // virtual key area into the main display surface.
            mLocked.currentVirtualKey.down = false;
#if DEBUG_VIRTUAL_KEYS
            LOGD("VirtualKeys: Canceling key: keyCode=%d, scanCode=%d",
                    mLocked.currentVirtualKey.keyCode, mLocked.currentVirtualKey.scanCode);
#endif
            keyEventAction = AKEY_EVENT_ACTION_UP;
            keyEventFlags = AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY
                    | AKEY_EVENT_FLAG_CANCELED;

            // Check whether the pointer moved inside the display area where we should
            // start a new stroke.
            int32_t x = mCurrentTouch.pointers[0].x;
            int32_t y = mCurrentTouch.pointers[0].y;
            if (isPointInsideSurfaceLocked(x, y)) {
                mLastTouch.clear();
                touchResult = DISPATCH_TOUCH;
            } else {
                touchResult = DROP_STROKE;
            }
        } else {
            if (mCurrentTouch.pointerCount >= 1 && mLastTouch.pointerCount == 0) {
                // Pointer just went down.  Handle off-screen touches, if needed.
                int32_t x = mCurrentTouch.pointers[0].x;
                int32_t y = mCurrentTouch.pointers[0].y;
                if (! isPointInsideSurfaceLocked(x, y)) {
                    // If exactly one pointer went down, check for virtual key hit.
                    // Otherwise we will drop the entire stroke.
                    if (mCurrentTouch.pointerCount == 1) {
                        const VirtualKey* virtualKey = findVirtualKeyHitLocked(x, y);
                        if (virtualKey) {
                            if (mContext->shouldDropVirtualKey(when, getDevice(),
                                    virtualKey->keyCode, virtualKey->scanCode)) {
                                return DROP_STROKE;
                            }

                            mLocked.currentVirtualKey.down = true;
                            mLocked.currentVirtualKey.downTime = when;
                            mLocked.currentVirtualKey.keyCode = virtualKey->keyCode;
                            mLocked.currentVirtualKey.scanCode = virtualKey->scanCode;
#if DEBUG_VIRTUAL_KEYS
                            LOGD("VirtualKeys: Generating key down: keyCode=%d, scanCode=%d",
                                    mLocked.currentVirtualKey.keyCode,
                                    mLocked.currentVirtualKey.scanCode);
#endif
                            keyEventAction = AKEY_EVENT_ACTION_DOWN;
                            keyEventFlags = AKEY_EVENT_FLAG_FROM_SYSTEM
                                    | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY;
                            touchResult = SKIP_TOUCH;
                            goto DispatchVirtualKey;
                        }
                    }
                    return DROP_STROKE;
                }
            }
            return DISPATCH_TOUCH;
        }

    DispatchVirtualKey:
        // Collect remaining state needed to dispatch virtual key.
        keyCode = mLocked.currentVirtualKey.keyCode;
        scanCode = mLocked.currentVirtualKey.scanCode;
        downTime = mLocked.currentVirtualKey.downTime;
    } // release lock

    // Dispatch virtual key.
    int32_t metaState = mContext->getGlobalMetaState();
    policyFlags |= POLICY_FLAG_VIRTUAL;
    getDispatcher()->notifyKey(when, getDeviceId(), AINPUT_SOURCE_KEYBOARD, policyFlags,
            keyEventAction, keyEventFlags, keyCode, scanCode, metaState, downTime);
    return touchResult;
}

void TouchInputMapper::suppressSwipeOntoVirtualKeys(nsecs_t when) {
    // Disable all virtual key touches that happen within a short time interval of the
    // most recent touch.  The idea is to filter out stray virtual key presses when
    // interacting with the touch screen.
    //
    // Problems we're trying to solve:
    //
    // 1. While scrolling a list or dragging the window shade, the user swipes down into a
    //    virtual key area that is implemented by a separate touch panel and accidentally
    //    triggers a virtual key.
    //
    // 2. While typing in the on screen keyboard, the user taps slightly outside the screen
    //    area and accidentally triggers a virtual key.  This often happens when virtual keys
    //    are layed out below the screen near to where the on screen keyboard's space bar
    //    is displayed.
    if (mConfig.virtualKeyQuietTime > 0 && mCurrentTouch.pointerCount != 0) {
        mContext->disableVirtualKeysUntil(when + mConfig.virtualKeyQuietTime);
    }
}

void TouchInputMapper::dispatchTouches(nsecs_t when, uint32_t policyFlags) {
    uint32_t currentPointerCount = mCurrentTouch.pointerCount;
    uint32_t lastPointerCount = mLastTouch.pointerCount;
    if (currentPointerCount == 0 && lastPointerCount == 0) {
        return; // nothing to do!
    }

    // Update current touch coordinates.
    float xPrecision, yPrecision;
    prepareTouches(&xPrecision, &yPrecision);

    // Dispatch motions.
    BitSet32 currentIdBits = mCurrentTouch.idBits;
    BitSet32 lastIdBits = mLastTouch.idBits;
    int32_t metaState = getContext()->getGlobalMetaState();
    int32_t buttonState = mCurrentTouch.buttonState;

    if (currentIdBits == lastIdBits) {
        // No pointer id changes so this is a move event.
        // The dispatcher takes care of batching moves so we don't have to deal with that here.
        dispatchMotion(when, policyFlags, mTouchSource,
                AMOTION_EVENT_ACTION_MOVE, 0, metaState, buttonState,
                AMOTION_EVENT_EDGE_FLAG_NONE,
                mCurrentTouchProperties, mCurrentTouchCoords,
                mCurrentTouch.idToIndex, currentIdBits, -1,
                xPrecision, yPrecision, mDownTime);
    } else {
        // There may be pointers going up and pointers going down and pointers moving
        // all at the same time.
        BitSet32 upIdBits(lastIdBits.value & ~currentIdBits.value);
        BitSet32 downIdBits(currentIdBits.value & ~lastIdBits.value);
        BitSet32 moveIdBits(lastIdBits.value & currentIdBits.value);
        BitSet32 dispatchedIdBits(lastIdBits.value);

        // Update last coordinates of pointers that have moved so that we observe the new
        // pointer positions at the same time as other pointers that have just gone up.
        bool moveNeeded = updateMovedPointers(
                mCurrentTouchProperties, mCurrentTouchCoords, mCurrentTouch.idToIndex,
                mLastTouchProperties, mLastTouchCoords, mLastTouch.idToIndex,
                moveIdBits);
        if (buttonState != mLastTouch.buttonState) {
            moveNeeded = true;
        }

        // Dispatch pointer up events.
        while (!upIdBits.isEmpty()) {
            uint32_t upId = upIdBits.firstMarkedBit();
            upIdBits.clearBit(upId);

            dispatchMotion(when, policyFlags, mTouchSource,
                    AMOTION_EVENT_ACTION_POINTER_UP, 0, metaState, buttonState, 0,
                    mLastTouchProperties, mLastTouchCoords,
                    mLastTouch.idToIndex, dispatchedIdBits, upId,
                    xPrecision, yPrecision, mDownTime);
            dispatchedIdBits.clearBit(upId);
        }

        // Dispatch move events if any of the remaining pointers moved from their old locations.
        // Although applications receive new locations as part of individual pointer up
        // events, they do not generally handle them except when presented in a move event.
        if (moveNeeded) {
            LOG_ASSERT(moveIdBits.value == dispatchedIdBits.value);
            dispatchMotion(when, policyFlags, mTouchSource,
                    AMOTION_EVENT_ACTION_MOVE, 0, metaState, buttonState, 0,
                    mCurrentTouchProperties, mCurrentTouchCoords,
                    mCurrentTouch.idToIndex, dispatchedIdBits, -1,
                    xPrecision, yPrecision, mDownTime);
        }

        // Dispatch pointer down events using the new pointer locations.
        while (!downIdBits.isEmpty()) {
            uint32_t downId = downIdBits.firstMarkedBit();
            downIdBits.clearBit(downId);
            dispatchedIdBits.markBit(downId);

            if (dispatchedIdBits.count() == 1) {
                // First pointer is going down.  Set down time.
                mDownTime = when;
            }

            dispatchMotion(when, policyFlags, mTouchSource,
                    AMOTION_EVENT_ACTION_POINTER_DOWN, 0, metaState, buttonState, 0,
                    mCurrentTouchProperties, mCurrentTouchCoords,
                    mCurrentTouch.idToIndex, dispatchedIdBits, downId,
                    xPrecision, yPrecision, mDownTime);
        }
    }

    // Update state for next time.
    for (uint32_t i = 0; i < currentPointerCount; i++) {
        mLastTouchProperties[i].copyFrom(mCurrentTouchProperties[i]);
        mLastTouchCoords[i].copyFrom(mCurrentTouchCoords[i]);
    }
}

void TouchInputMapper::prepareTouches(float* outXPrecision, float* outYPrecision) {
    uint32_t currentPointerCount = mCurrentTouch.pointerCount;
    uint32_t lastPointerCount = mLastTouch.pointerCount;

    AutoMutex _l(mLock);

    // Walk through the the active pointers and map touch screen coordinates (TouchData) into
    // display or surface coordinates (PointerCoords) and adjust for display orientation.
    for (uint32_t i = 0; i < currentPointerCount; i++) {
        const PointerData& in = mCurrentTouch.pointers[i];

        // ToolMajor and ToolMinor
        float toolMajor, toolMinor;
        switch (mCalibration.toolSizeCalibration) {
        case Calibration::TOOL_SIZE_CALIBRATION_GEOMETRIC:
            toolMajor = in.toolMajor * mLocked.geometricScale;
            if (mRawAxes.toolMinor.valid) {
                toolMinor = in.toolMinor * mLocked.geometricScale;
            } else {
                toolMinor = toolMajor;
            }
            break;
        case Calibration::TOOL_SIZE_CALIBRATION_LINEAR:
            toolMajor = in.toolMajor != 0
                    ? in.toolMajor * mLocked.toolSizeLinearScale + mLocked.toolSizeLinearBias
                    : 0;
            if (mRawAxes.toolMinor.valid) {
                toolMinor = in.toolMinor != 0
                        ? in.toolMinor * mLocked.toolSizeLinearScale
                                + mLocked.toolSizeLinearBias
                        : 0;
            } else {
                toolMinor = toolMajor;
            }
            break;
        case Calibration::TOOL_SIZE_CALIBRATION_AREA:
            if (in.toolMajor != 0) {
                float diameter = sqrtf(in.toolMajor
                        * mLocked.toolSizeAreaScale + mLocked.toolSizeAreaBias);
                toolMajor = diameter * mLocked.toolSizeLinearScale + mLocked.toolSizeLinearBias;
            } else {
                toolMajor = 0;
            }
            toolMinor = toolMajor;
            break;
        default:
            toolMajor = 0;
            toolMinor = 0;
            break;
        }

        if (mCalibration.haveToolSizeIsSummed && mCalibration.toolSizeIsSummed) {
            toolMajor /= currentPointerCount;
            toolMinor /= currentPointerCount;
        }

        // Pressure
        float rawPressure;
        switch (mCalibration.pressureSource) {
        case Calibration::PRESSURE_SOURCE_PRESSURE:
            rawPressure = in.pressure;
            break;
        case Calibration::PRESSURE_SOURCE_TOUCH:
            rawPressure = in.touchMajor;
            break;
        default:
            rawPressure = 0;
        }

        float pressure;
        switch (mCalibration.pressureCalibration) {
        case Calibration::PRESSURE_CALIBRATION_PHYSICAL:
        case Calibration::PRESSURE_CALIBRATION_AMPLITUDE:
            pressure = rawPressure * mLocked.pressureScale;
            break;
        default:
            pressure = 1;
            break;
        }

        // TouchMajor and TouchMinor
        float touchMajor, touchMinor;
        switch (mCalibration.touchSizeCalibration) {
        case Calibration::TOUCH_SIZE_CALIBRATION_GEOMETRIC:
            touchMajor = in.touchMajor * mLocked.geometricScale;
            if (mRawAxes.touchMinor.valid) {
                touchMinor = in.touchMinor * mLocked.geometricScale;
            } else {
                touchMinor = touchMajor;
            }
            break;
        case Calibration::TOUCH_SIZE_CALIBRATION_PRESSURE:
            touchMajor = toolMajor * pressure;
            touchMinor = toolMinor * pressure;
            break;
        default:
            touchMajor = 0;
            touchMinor = 0;
            break;
        }

        if (touchMajor > toolMajor) {
            touchMajor = toolMajor;
        }
        if (touchMinor > toolMinor) {
            touchMinor = toolMinor;
        }

        // Size
        float size;
        switch (mCalibration.sizeCalibration) {
        case Calibration::SIZE_CALIBRATION_NORMALIZED: {
            float rawSize = mRawAxes.toolMinor.valid
                    ? avg(in.toolMajor, in.toolMinor)
                    : in.toolMajor;
            size = rawSize * mLocked.sizeScale;
            break;
        }
        default:
            size = 0;
            break;
        }

        // Orientation
        float orientation;
        switch (mCalibration.orientationCalibration) {
        case Calibration::ORIENTATION_CALIBRATION_INTERPOLATED:
            orientation = in.orientation * mLocked.orientationScale;
            break;
        case Calibration::ORIENTATION_CALIBRATION_VECTOR: {
            int32_t c1 = signExtendNybble((in.orientation & 0xf0) >> 4);
            int32_t c2 = signExtendNybble(in.orientation & 0x0f);
            if (c1 != 0 || c2 != 0) {
                orientation = atan2f(c1, c2) * 0.5f;
                float scale = 1.0f + hypotf(c1, c2) / 16.0f;
                touchMajor *= scale;
                touchMinor /= scale;
                toolMajor *= scale;
                toolMinor /= scale;
            } else {
                orientation = 0;
            }
            break;
        }
        default:
            orientation = 0;
        }

        // Distance
        float distance;
        switch (mCalibration.distanceCalibration) {
        case Calibration::DISTANCE_CALIBRATION_SCALED:
            distance = in.distance * mLocked.distanceScale;
            break;
        default:
            distance = 0;
        }

        // X and Y
        // Adjust coords for surface orientation.
        float x, y;
        switch (mLocked.surfaceOrientation) {
        case DISPLAY_ORIENTATION_90:
            x = float(in.y - mRawAxes.y.minValue) * mLocked.yScale;
            y = float(mRawAxes.x.maxValue - in.x) * mLocked.xScale;
            orientation -= M_PI_2;
            if (orientation < - M_PI_2) {
                orientation += M_PI;
            }
            break;
        case DISPLAY_ORIENTATION_180:
            x = float(mRawAxes.x.maxValue - in.x) * mLocked.xScale;
            y = float(mRawAxes.y.maxValue - in.y) * mLocked.yScale;
            break;
        case DISPLAY_ORIENTATION_270:
            x = float(mRawAxes.y.maxValue - in.y) * mLocked.yScale;
            y = float(in.x - mRawAxes.x.minValue) * mLocked.xScale;
            orientation += M_PI_2;
            if (orientation > M_PI_2) {
                orientation -= M_PI;
            }
            break;
        default:
            x = float(in.x - mRawAxes.x.minValue) * mLocked.xScale;
            y = float(in.y - mRawAxes.y.minValue) * mLocked.yScale;
            break;
        }

        // Write output coords.
        PointerCoords& out = mCurrentTouchCoords[i];
        out.clear();
        out.setAxisValue(AMOTION_EVENT_AXIS_X, x);
        out.setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        out.setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, pressure);
        out.setAxisValue(AMOTION_EVENT_AXIS_SIZE, size);
        out.setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, touchMajor);
        out.setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, touchMinor);
        out.setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, toolMajor);
        out.setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR, toolMinor);
        out.setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, orientation);
        if (distance != 0) {
            out.setAxisValue(AMOTION_EVENT_AXIS_DISTANCE, distance);
        }

        // Write output properties.
        PointerProperties& properties = mCurrentTouchProperties[i];
        properties.clear();
        properties.id = mCurrentTouch.pointers[i].id;
        properties.toolType = getTouchToolType(mCurrentTouch.pointers[i].isStylus);
    }

    *outXPrecision = mLocked.orientedXPrecision;
    *outYPrecision = mLocked.orientedYPrecision;
}

void TouchInputMapper::dispatchPointerGestures(nsecs_t when, uint32_t policyFlags,
        bool isTimeout) {
    // Update current gesture coordinates.
    bool cancelPreviousGesture, finishPreviousGesture;
    bool sendEvents = preparePointerGestures(when,
            &cancelPreviousGesture, &finishPreviousGesture, isTimeout);
    if (!sendEvents) {
        return;
    }
    if (finishPreviousGesture) {
        cancelPreviousGesture = false;
    }

    // Update the pointer presentation and spots.
    if (mParameters.gestureMode == Parameters::GESTURE_MODE_SPOTS) {
        mPointerController->setPresentation(PointerControllerInterface::PRESENTATION_SPOT);
        if (finishPreviousGesture || cancelPreviousGesture) {
            mPointerController->clearSpots();
        }
        mPointerController->setSpots(mPointerGesture.currentGestureCoords,
                mPointerGesture.currentGestureIdToIndex,
                mPointerGesture.currentGestureIdBits);
    } else {
        mPointerController->setPresentation(PointerControllerInterface::PRESENTATION_POINTER);
    }

    // Show or hide the pointer if needed.
    switch (mPointerGesture.currentGestureMode) {
    case PointerGesture::NEUTRAL:
    case PointerGesture::QUIET:
        if (mParameters.gestureMode == Parameters::GESTURE_MODE_SPOTS
                && (mPointerGesture.lastGestureMode == PointerGesture::SWIPE
                        || mPointerGesture.lastGestureMode == PointerGesture::FREEFORM)) {
            // Remind the user of where the pointer is after finishing a gesture with spots.
            mPointerController->unfade(PointerControllerInterface::TRANSITION_GRADUAL);
        }
        break;
    case PointerGesture::TAP:
    case PointerGesture::TAP_DRAG:
    case PointerGesture::BUTTON_CLICK_OR_DRAG:
    case PointerGesture::HOVER:
    case PointerGesture::PRESS:
        // Unfade the pointer when the current gesture manipulates the
        // area directly under the pointer.
        mPointerController->unfade(PointerControllerInterface::TRANSITION_IMMEDIATE);
        break;
    case PointerGesture::SWIPE:
    case PointerGesture::FREEFORM:
        // Fade the pointer when the current gesture manipulates a different
        // area and there are spots to guide the user experience.
        if (mParameters.gestureMode == Parameters::GESTURE_MODE_SPOTS) {
            mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
        } else {
            mPointerController->unfade(PointerControllerInterface::TRANSITION_IMMEDIATE);
        }
        break;
    }

    // Send events!
    int32_t metaState = getContext()->getGlobalMetaState();
    int32_t buttonState = mCurrentTouch.buttonState;

    // Update last coordinates of pointers that have moved so that we observe the new
    // pointer positions at the same time as other pointers that have just gone up.
    bool down = mPointerGesture.currentGestureMode == PointerGesture::TAP
            || mPointerGesture.currentGestureMode == PointerGesture::TAP_DRAG
            || mPointerGesture.currentGestureMode == PointerGesture::BUTTON_CLICK_OR_DRAG
            || mPointerGesture.currentGestureMode == PointerGesture::PRESS
            || mPointerGesture.currentGestureMode == PointerGesture::SWIPE
            || mPointerGesture.currentGestureMode == PointerGesture::FREEFORM;
    bool moveNeeded = false;
    if (down && !cancelPreviousGesture && !finishPreviousGesture
            && !mPointerGesture.lastGestureIdBits.isEmpty()
            && !mPointerGesture.currentGestureIdBits.isEmpty()) {
        BitSet32 movedGestureIdBits(mPointerGesture.currentGestureIdBits.value
                & mPointerGesture.lastGestureIdBits.value);
        moveNeeded = updateMovedPointers(mPointerGesture.currentGestureProperties,
                mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                mPointerGesture.lastGestureProperties,
                mPointerGesture.lastGestureCoords, mPointerGesture.lastGestureIdToIndex,
                movedGestureIdBits);
        if (buttonState != mLastTouch.buttonState) {
            moveNeeded = true;
        }
    }

    // Send motion events for all pointers that went up or were canceled.
    BitSet32 dispatchedGestureIdBits(mPointerGesture.lastGestureIdBits);
    if (!dispatchedGestureIdBits.isEmpty()) {
        if (cancelPreviousGesture) {
            dispatchMotion(when, policyFlags, mPointerSource,
                    AMOTION_EVENT_ACTION_CANCEL, 0, metaState, buttonState,
                    AMOTION_EVENT_EDGE_FLAG_NONE,
                    mPointerGesture.lastGestureProperties,
                    mPointerGesture.lastGestureCoords, mPointerGesture.lastGestureIdToIndex,
                    dispatchedGestureIdBits, -1,
                    0, 0, mPointerGesture.downTime);

            dispatchedGestureIdBits.clear();
        } else {
            BitSet32 upGestureIdBits;
            if (finishPreviousGesture) {
                upGestureIdBits = dispatchedGestureIdBits;
            } else {
                upGestureIdBits.value = dispatchedGestureIdBits.value
                        & ~mPointerGesture.currentGestureIdBits.value;
            }
            while (!upGestureIdBits.isEmpty()) {
                uint32_t id = upGestureIdBits.firstMarkedBit();
                upGestureIdBits.clearBit(id);

                dispatchMotion(when, policyFlags, mPointerSource,
                        AMOTION_EVENT_ACTION_POINTER_UP, 0,
                        metaState, buttonState, AMOTION_EVENT_EDGE_FLAG_NONE,
                        mPointerGesture.lastGestureProperties,
                        mPointerGesture.lastGestureCoords, mPointerGesture.lastGestureIdToIndex,
                        dispatchedGestureIdBits, id,
                        0, 0, mPointerGesture.downTime);

                dispatchedGestureIdBits.clearBit(id);
            }
        }
    }

    // Send motion events for all pointers that moved.
    if (moveNeeded) {
        dispatchMotion(when, policyFlags, mPointerSource,
                AMOTION_EVENT_ACTION_MOVE, 0, metaState, buttonState, AMOTION_EVENT_EDGE_FLAG_NONE,
                mPointerGesture.currentGestureProperties,
                mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                dispatchedGestureIdBits, -1,
                0, 0, mPointerGesture.downTime);
    }

    // Send motion events for all pointers that went down.
    if (down) {
        BitSet32 downGestureIdBits(mPointerGesture.currentGestureIdBits.value
                & ~dispatchedGestureIdBits.value);
        while (!downGestureIdBits.isEmpty()) {
            uint32_t id = downGestureIdBits.firstMarkedBit();
            downGestureIdBits.clearBit(id);
            dispatchedGestureIdBits.markBit(id);

            if (dispatchedGestureIdBits.count() == 1) {
                mPointerGesture.downTime = when;
            }

            dispatchMotion(when, policyFlags, mPointerSource,
                    AMOTION_EVENT_ACTION_POINTER_DOWN, 0, metaState, buttonState, 0,
                    mPointerGesture.currentGestureProperties,
                    mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                    dispatchedGestureIdBits, id,
                    0, 0, mPointerGesture.downTime);
        }
    }

    // Send motion events for hover.
    if (mPointerGesture.currentGestureMode == PointerGesture::HOVER) {
        dispatchMotion(when, policyFlags, mPointerSource,
                AMOTION_EVENT_ACTION_HOVER_MOVE, 0,
                metaState, buttonState, AMOTION_EVENT_EDGE_FLAG_NONE,
                mPointerGesture.currentGestureProperties,
                mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                mPointerGesture.currentGestureIdBits, -1,
                0, 0, mPointerGesture.downTime);
    } else if (dispatchedGestureIdBits.isEmpty()
            && !mPointerGesture.lastGestureIdBits.isEmpty()) {
        // Synthesize a hover move event after all pointers go up to indicate that
        // the pointer is hovering again even if the user is not currently touching
        // the touch pad.  This ensures that a view will receive a fresh hover enter
        // event after a tap.
        float x, y;
        mPointerController->getPosition(&x, &y);

        PointerProperties pointerProperties;
        pointerProperties.clear();
        pointerProperties.id = 0;
        pointerProperties.toolType = AMOTION_EVENT_TOOL_TYPE_INDIRECT_FINGER;

        PointerCoords pointerCoords;
        pointerCoords.clear();
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_X, x);
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, y);

        getDispatcher()->notifyMotion(when, getDeviceId(), mPointerSource, policyFlags,
                AMOTION_EVENT_ACTION_HOVER_MOVE, 0,
                metaState, buttonState, AMOTION_EVENT_EDGE_FLAG_NONE,
                1, &pointerProperties, &pointerCoords, 0, 0, mPointerGesture.downTime);
    }

    // Update state.
    mPointerGesture.lastGestureMode = mPointerGesture.currentGestureMode;
    if (!down) {
        mPointerGesture.lastGestureIdBits.clear();
    } else {
        mPointerGesture.lastGestureIdBits = mPointerGesture.currentGestureIdBits;
        for (BitSet32 idBits(mPointerGesture.currentGestureIdBits); !idBits.isEmpty(); ) {
            uint32_t id = idBits.firstMarkedBit();
            idBits.clearBit(id);
            uint32_t index = mPointerGesture.currentGestureIdToIndex[id];
            mPointerGesture.lastGestureProperties[index].copyFrom(
                    mPointerGesture.currentGestureProperties[index]);
            mPointerGesture.lastGestureCoords[index].copyFrom(
                    mPointerGesture.currentGestureCoords[index]);
            mPointerGesture.lastGestureIdToIndex[id] = index;
        }
    }
}

bool TouchInputMapper::preparePointerGestures(nsecs_t when,
        bool* outCancelPreviousGesture, bool* outFinishPreviousGesture, bool isTimeout) {
    *outCancelPreviousGesture = false;
    *outFinishPreviousGesture = false;

    AutoMutex _l(mLock);

    // Handle TAP timeout.
    if (isTimeout) {
#if DEBUG_GESTURES
        LOGD("Gestures: Processing timeout");
#endif

        if (mPointerGesture.lastGestureMode == PointerGesture::TAP) {
            if (when <= mPointerGesture.tapUpTime + mConfig.pointerGestureTapDragInterval) {
                // The tap/drag timeout has not yet expired.
                getContext()->requestTimeoutAtTime(mPointerGesture.tapUpTime
                        + mConfig.pointerGestureTapDragInterval);
            } else {
                // The tap is finished.
#if DEBUG_GESTURES
                LOGD("Gestures: TAP finished");
#endif
                *outFinishPreviousGesture = true;

                mPointerGesture.activeGestureId = -1;
                mPointerGesture.currentGestureMode = PointerGesture::NEUTRAL;
                mPointerGesture.currentGestureIdBits.clear();

                mPointerGesture.pointerVelocityControl.reset();
                return true;
            }
        }

        // We did not handle this timeout.
        return false;
    }

    // Update the velocity tracker.
    {
        VelocityTracker::Position positions[MAX_POINTERS];
        uint32_t count = 0;
        for (BitSet32 idBits(mCurrentTouch.idBits); !idBits.isEmpty(); count++) {
            uint32_t id = idBits.firstMarkedBit();
            idBits.clearBit(id);
            uint32_t index = mCurrentTouch.idToIndex[id];
            positions[count].x = mCurrentTouch.pointers[index].x
                    * mLocked.pointerGestureXMovementScale;
            positions[count].y = mCurrentTouch.pointers[index].y
                    * mLocked.pointerGestureYMovementScale;
        }
        mPointerGesture.velocityTracker.addMovement(when, mCurrentTouch.idBits, positions);
    }

    // Pick a new active touch id if needed.
    // Choose an arbitrary pointer that just went down, if there is one.
    // Otherwise choose an arbitrary remaining pointer.
    // This guarantees we always have an active touch id when there is at least one pointer.
    // We keep the same active touch id for as long as possible.
    bool activeTouchChanged = false;
    int32_t lastActiveTouchId = mPointerGesture.activeTouchId;
    int32_t activeTouchId = lastActiveTouchId;
    if (activeTouchId < 0) {
        if (!mCurrentTouch.idBits.isEmpty()) {
            activeTouchChanged = true;
            activeTouchId = mPointerGesture.activeTouchId = mCurrentTouch.idBits.firstMarkedBit();
            mPointerGesture.firstTouchTime = when;
        }
    } else if (!mCurrentTouch.idBits.hasBit(activeTouchId)) {
        activeTouchChanged = true;
        if (!mCurrentTouch.idBits.isEmpty()) {
            activeTouchId = mPointerGesture.activeTouchId = mCurrentTouch.idBits.firstMarkedBit();
        } else {
            activeTouchId = mPointerGesture.activeTouchId = -1;
        }
    }

    // Determine whether we are in quiet time.
    bool isQuietTime = false;
    if (activeTouchId < 0) {
        mPointerGesture.resetQuietTime();
    } else {
        isQuietTime = when < mPointerGesture.quietTime + mConfig.pointerGestureQuietInterval;
        if (!isQuietTime) {
            if ((mPointerGesture.lastGestureMode == PointerGesture::PRESS
                    || mPointerGesture.lastGestureMode == PointerGesture::SWIPE
                    || mPointerGesture.lastGestureMode == PointerGesture::FREEFORM)
                    && mCurrentTouch.pointerCount < 2) {
                // Enter quiet time when exiting swipe or freeform state.
                // This is to prevent accidentally entering the hover state and flinging the
                // pointer when finishing a swipe and there is still one pointer left onscreen.
                isQuietTime = true;
            } else if (mPointerGesture.lastGestureMode == PointerGesture::BUTTON_CLICK_OR_DRAG
                    && mCurrentTouch.pointerCount >= 2
                    && !isPointerDown(mCurrentTouch.buttonState)) {
                // Enter quiet time when releasing the button and there are still two or more
                // fingers down.  This may indicate that one finger was used to press the button
                // but it has not gone up yet.
                isQuietTime = true;
            }
            if (isQuietTime) {
                mPointerGesture.quietTime = when;
            }
        }
    }

    // Switch states based on button and pointer state.
    if (isQuietTime) {
        // Case 1: Quiet time. (QUIET)
#if DEBUG_GESTURES
        LOGD("Gestures: QUIET for next %0.3fms", (mPointerGesture.quietTime
                + mConfig.pointerGestureQuietInterval - when) * 0.000001f);
#endif
        if (mPointerGesture.lastGestureMode != PointerGesture::QUIET) {
            *outFinishPreviousGesture = true;
        }

        mPointerGesture.activeGestureId = -1;
        mPointerGesture.currentGestureMode = PointerGesture::QUIET;
        mPointerGesture.currentGestureIdBits.clear();

        mPointerGesture.pointerVelocityControl.reset();
    } else if (isPointerDown(mCurrentTouch.buttonState)) {
        // Case 2: Button is pressed. (BUTTON_CLICK_OR_DRAG)
        // The pointer follows the active touch point.
        // Emit DOWN, MOVE, UP events at the pointer location.
        //
        // Only the active touch matters; other fingers are ignored.  This policy helps
        // to handle the case where the user places a second finger on the touch pad
        // to apply the necessary force to depress an integrated button below the surface.
        // We don't want the second finger to be delivered to applications.
        //
        // For this to work well, we need to make sure to track the pointer that is really
        // active.  If the user first puts one finger down to click then adds another
        // finger to drag then the active pointer should switch to the finger that is
        // being dragged.
#if DEBUG_GESTURES
        LOGD("Gestures: BUTTON_CLICK_OR_DRAG activeTouchId=%d, "
                "currentTouchPointerCount=%d", activeTouchId, mCurrentTouch.pointerCount);
#endif
        // Reset state when just starting.
        if (mPointerGesture.lastGestureMode != PointerGesture::BUTTON_CLICK_OR_DRAG) {
            *outFinishPreviousGesture = true;
            mPointerGesture.activeGestureId = 0;
        }

        // Switch pointers if needed.
        // Find the fastest pointer and follow it.
        if (activeTouchId >= 0 && mCurrentTouch.pointerCount > 1) {
            int32_t bestId = -1;
            float bestSpeed = mConfig.pointerGestureDragMinSwitchSpeed;
            for (uint32_t i = 0; i < mCurrentTouch.pointerCount; i++) {
                uint32_t id = mCurrentTouch.pointers[i].id;
                float vx, vy;
                if (mPointerGesture.velocityTracker.getVelocity(id, &vx, &vy)) {
                    float speed = hypotf(vx, vy);
                    if (speed > bestSpeed) {
                        bestId = id;
                        bestSpeed = speed;
                    }
                }
            }
            if (bestId >= 0 && bestId != activeTouchId) {
                mPointerGesture.activeTouchId = activeTouchId = bestId;
                activeTouchChanged = true;
#if DEBUG_GESTURES
                LOGD("Gestures: BUTTON_CLICK_OR_DRAG switched pointers, "
                        "bestId=%d, bestSpeed=%0.3f", bestId, bestSpeed);
#endif
            }
        }

        if (activeTouchId >= 0 && mLastTouch.idBits.hasBit(activeTouchId)) {
            const PointerData& currentPointer =
                    mCurrentTouch.pointers[mCurrentTouch.idToIndex[activeTouchId]];
            const PointerData& lastPointer =
                    mLastTouch.pointers[mLastTouch.idToIndex[activeTouchId]];
            float deltaX = (currentPointer.x - lastPointer.x)
                    * mLocked.pointerGestureXMovementScale;
            float deltaY = (currentPointer.y - lastPointer.y)
                    * mLocked.pointerGestureYMovementScale;

            rotateDelta(mLocked.surfaceOrientation, &deltaX, &deltaY);
            mPointerGesture.pointerVelocityControl.move(when, &deltaX, &deltaY);

            // Move the pointer using a relative motion.
            // When using spots, the click will occur at the position of the anchor
            // spot and all other spots will move there.
            mPointerController->move(deltaX, deltaY);
        } else {
            mPointerGesture.pointerVelocityControl.reset();
        }

        float x, y;
        mPointerController->getPosition(&x, &y);

        mPointerGesture.currentGestureMode = PointerGesture::BUTTON_CLICK_OR_DRAG;
        mPointerGesture.currentGestureIdBits.clear();
        mPointerGesture.currentGestureIdBits.markBit(mPointerGesture.activeGestureId);
        mPointerGesture.currentGestureIdToIndex[mPointerGesture.activeGestureId] = 0;
        mPointerGesture.currentGestureProperties[0].clear();
        mPointerGesture.currentGestureProperties[0].id = mPointerGesture.activeGestureId;
        mPointerGesture.currentGestureProperties[0].toolType =
                AMOTION_EVENT_TOOL_TYPE_INDIRECT_FINGER;
        mPointerGesture.currentGestureCoords[0].clear();
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, x);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 1.0f);
    } else if (mCurrentTouch.pointerCount == 0) {
        // Case 3. No fingers down and button is not pressed. (NEUTRAL)
        if (mPointerGesture.lastGestureMode != PointerGesture::NEUTRAL) {
            *outFinishPreviousGesture = true;
        }

        // Watch for taps coming out of HOVER or TAP_DRAG mode.
        // Checking for taps after TAP_DRAG allows us to detect double-taps.
        bool tapped = false;
        if ((mPointerGesture.lastGestureMode == PointerGesture::HOVER
                || mPointerGesture.lastGestureMode == PointerGesture::TAP_DRAG)
                && mLastTouch.pointerCount == 1) {
            if (when <= mPointerGesture.tapDownTime + mConfig.pointerGestureTapInterval) {
                float x, y;
                mPointerController->getPosition(&x, &y);
                if (fabs(x - mPointerGesture.tapX) <= mConfig.pointerGestureTapSlop
                        && fabs(y - mPointerGesture.tapY) <= mConfig.pointerGestureTapSlop) {
#if DEBUG_GESTURES
                    LOGD("Gestures: TAP");
#endif

                    mPointerGesture.tapUpTime = when;
                    getContext()->requestTimeoutAtTime(when
                            + mConfig.pointerGestureTapDragInterval);

                    mPointerGesture.activeGestureId = 0;
                    mPointerGesture.currentGestureMode = PointerGesture::TAP;
                    mPointerGesture.currentGestureIdBits.clear();
                    mPointerGesture.currentGestureIdBits.markBit(
                            mPointerGesture.activeGestureId);
                    mPointerGesture.currentGestureIdToIndex[
                            mPointerGesture.activeGestureId] = 0;
                    mPointerGesture.currentGestureProperties[0].clear();
                    mPointerGesture.currentGestureProperties[0].id =
                            mPointerGesture.activeGestureId;
                    mPointerGesture.currentGestureProperties[0].toolType =
                            AMOTION_EVENT_TOOL_TYPE_INDIRECT_FINGER;
                    mPointerGesture.currentGestureCoords[0].clear();
                    mPointerGesture.currentGestureCoords[0].setAxisValue(
                            AMOTION_EVENT_AXIS_X, mPointerGesture.tapX);
                    mPointerGesture.currentGestureCoords[0].setAxisValue(
                            AMOTION_EVENT_AXIS_Y, mPointerGesture.tapY);
                    mPointerGesture.currentGestureCoords[0].setAxisValue(
                            AMOTION_EVENT_AXIS_PRESSURE, 1.0f);

                    tapped = true;
                } else {
#if DEBUG_GESTURES
                    LOGD("Gestures: Not a TAP, deltaX=%f, deltaY=%f",
                            x - mPointerGesture.tapX,
                            y - mPointerGesture.tapY);
#endif
                }
            } else {
#if DEBUG_GESTURES
                LOGD("Gestures: Not a TAP, %0.3fms since down",
                        (when - mPointerGesture.tapDownTime) * 0.000001f);
#endif
            }
        }

        mPointerGesture.pointerVelocityControl.reset();

        if (!tapped) {
#if DEBUG_GESTURES
            LOGD("Gestures: NEUTRAL");
#endif
            mPointerGesture.activeGestureId = -1;
            mPointerGesture.currentGestureMode = PointerGesture::NEUTRAL;
            mPointerGesture.currentGestureIdBits.clear();
        }
    } else if (mCurrentTouch.pointerCount == 1) {
        // Case 4. Exactly one finger down, button is not pressed. (HOVER or TAP_DRAG)
        // The pointer follows the active touch point.
        // When in HOVER, emit HOVER_MOVE events at the pointer location.
        // When in TAP_DRAG, emit MOVE events at the pointer location.
        LOG_ASSERT(activeTouchId >= 0);

        mPointerGesture.currentGestureMode = PointerGesture::HOVER;
        if (mPointerGesture.lastGestureMode == PointerGesture::TAP) {
            if (when <= mPointerGesture.tapUpTime + mConfig.pointerGestureTapDragInterval) {
                float x, y;
                mPointerController->getPosition(&x, &y);
                if (fabs(x - mPointerGesture.tapX) <= mConfig.pointerGestureTapSlop
                        && fabs(y - mPointerGesture.tapY) <= mConfig.pointerGestureTapSlop) {
                    mPointerGesture.currentGestureMode = PointerGesture::TAP_DRAG;
                } else {
#if DEBUG_GESTURES
                    LOGD("Gestures: Not a TAP_DRAG, deltaX=%f, deltaY=%f",
                            x - mPointerGesture.tapX,
                            y - mPointerGesture.tapY);
#endif
                }
            } else {
#if DEBUG_GESTURES
                LOGD("Gestures: Not a TAP_DRAG, %0.3fms time since up",
                        (when - mPointerGesture.tapUpTime) * 0.000001f);
#endif
            }
        } else if (mPointerGesture.lastGestureMode == PointerGesture::TAP_DRAG) {
            mPointerGesture.currentGestureMode = PointerGesture::TAP_DRAG;
        }

        if (mLastTouch.idBits.hasBit(activeTouchId)) {
            const PointerData& currentPointer =
                    mCurrentTouch.pointers[mCurrentTouch.idToIndex[activeTouchId]];
            const PointerData& lastPointer =
                    mLastTouch.pointers[mLastTouch.idToIndex[activeTouchId]];
            float deltaX = (currentPointer.x - lastPointer.x)
                    * mLocked.pointerGestureXMovementScale;
            float deltaY = (currentPointer.y - lastPointer.y)
                    * mLocked.pointerGestureYMovementScale;

            rotateDelta(mLocked.surfaceOrientation, &deltaX, &deltaY);
            mPointerGesture.pointerVelocityControl.move(when, &deltaX, &deltaY);

            // Move the pointer using a relative motion.
            // When using spots, the hover or drag will occur at the position of the anchor spot.
            mPointerController->move(deltaX, deltaY);
        } else {
            mPointerGesture.pointerVelocityControl.reset();
        }

        bool down;
        if (mPointerGesture.currentGestureMode == PointerGesture::TAP_DRAG) {
#if DEBUG_GESTURES
            LOGD("Gestures: TAP_DRAG");
#endif
            down = true;
        } else {
#if DEBUG_GESTURES
            LOGD("Gestures: HOVER");
#endif
            if (mPointerGesture.lastGestureMode != PointerGesture::HOVER) {
                *outFinishPreviousGesture = true;
            }
            mPointerGesture.activeGestureId = 0;
            down = false;
        }

        float x, y;
        mPointerController->getPosition(&x, &y);

        mPointerGesture.currentGestureIdBits.clear();
        mPointerGesture.currentGestureIdBits.markBit(mPointerGesture.activeGestureId);
        mPointerGesture.currentGestureIdToIndex[mPointerGesture.activeGestureId] = 0;
        mPointerGesture.currentGestureProperties[0].clear();
        mPointerGesture.currentGestureProperties[0].id = mPointerGesture.activeGestureId;
        mPointerGesture.currentGestureProperties[0].toolType =
                AMOTION_EVENT_TOOL_TYPE_INDIRECT_FINGER;
        mPointerGesture.currentGestureCoords[0].clear();
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, x);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE,
                down ? 1.0f : 0.0f);

        if (mLastTouch.pointerCount == 0 && mCurrentTouch.pointerCount != 0) {
            mPointerGesture.resetTap();
            mPointerGesture.tapDownTime = when;
            mPointerGesture.tapX = x;
            mPointerGesture.tapY = y;
        }
    } else {
        // Case 5. At least two fingers down, button is not pressed. (PRESS, SWIPE or FREEFORM)
        // We need to provide feedback for each finger that goes down so we cannot wait
        // for the fingers to move before deciding what to do.
        //
        // The ambiguous case is deciding what to do when there are two fingers down but they
        // have not moved enough to determine whether they are part of a drag or part of a
        // freeform gesture, or just a press or long-press at the pointer location.
        //
        // When there are two fingers we start with the PRESS hypothesis and we generate a
        // down at the pointer location.
        //
        // When the two fingers move enough or when additional fingers are added, we make
        // a decision to transition into SWIPE or FREEFORM mode accordingly.
        LOG_ASSERT(activeTouchId >= 0);

        bool settled = when >= mPointerGesture.firstTouchTime
                + mConfig.pointerGestureMultitouchSettleInterval;
        if (mPointerGesture.lastGestureMode != PointerGesture::PRESS
                && mPointerGesture.lastGestureMode != PointerGesture::SWIPE
                && mPointerGesture.lastGestureMode != PointerGesture::FREEFORM) {
            *outFinishPreviousGesture = true;
        } else if (!settled && mCurrentTouch.pointerCount > mLastTouch.pointerCount) {
            // Additional pointers have gone down but not yet settled.
            // Reset the gesture.
#if DEBUG_GESTURES
            LOGD("Gestures: Resetting gesture since additional pointers went down for MULTITOUCH, "
                    "settle time remaining %0.3fms", (mPointerGesture.firstTouchTime
                            + mConfig.pointerGestureMultitouchSettleInterval - when)
                            * 0.000001f);
#endif
            *outCancelPreviousGesture = true;
        } else {
            // Continue previous gesture.
            mPointerGesture.currentGestureMode = mPointerGesture.lastGestureMode;
        }

        if (*outFinishPreviousGesture || *outCancelPreviousGesture) {
            mPointerGesture.currentGestureMode = PointerGesture::PRESS;
            mPointerGesture.activeGestureId = 0;
            mPointerGesture.referenceIdBits.clear();
            mPointerGesture.pointerVelocityControl.reset();

            // Use the centroid and pointer location as the reference points for the gesture.
#if DEBUG_GESTURES
            LOGD("Gestures: Using centroid as reference for MULTITOUCH, "
                    "settle time remaining %0.3fms", (mPointerGesture.firstTouchTime
                            + mConfig.pointerGestureMultitouchSettleInterval - when)
                            * 0.000001f);
#endif
            mCurrentTouch.getCentroid(&mPointerGesture.referenceTouchX,
                    &mPointerGesture.referenceTouchY);
            mPointerController->getPosition(&mPointerGesture.referenceGestureX,
                    &mPointerGesture.referenceGestureY);
        }

        // Clear the reference deltas for fingers not yet included in the reference calculation.
        for (BitSet32 idBits(mCurrentTouch.idBits.value & ~mPointerGesture.referenceIdBits.value);
                !idBits.isEmpty(); ) {
            uint32_t id = idBits.firstMarkedBit();
            idBits.clearBit(id);

            mPointerGesture.referenceDeltas[id].dx = 0;
            mPointerGesture.referenceDeltas[id].dy = 0;
        }
        mPointerGesture.referenceIdBits = mCurrentTouch.idBits;

        // Add delta for all fingers and calculate a common movement delta.
        float commonDeltaX = 0, commonDeltaY = 0;
        BitSet32 commonIdBits(mLastTouch.idBits.value & mCurrentTouch.idBits.value);
        for (BitSet32 idBits(commonIdBits); !idBits.isEmpty(); ) {
            bool first = (idBits == commonIdBits);
            uint32_t id = idBits.firstMarkedBit();
            idBits.clearBit(id);

            const PointerData& cpd = mCurrentTouch.pointers[mCurrentTouch.idToIndex[id]];
            const PointerData& lpd = mLastTouch.pointers[mLastTouch.idToIndex[id]];
            PointerGesture::Delta& delta = mPointerGesture.referenceDeltas[id];
            delta.dx += cpd.x - lpd.x;
            delta.dy += cpd.y - lpd.y;

            if (first) {
                commonDeltaX = delta.dx;
                commonDeltaY = delta.dy;
            } else {
                commonDeltaX = calculateCommonVector(commonDeltaX, delta.dx);
                commonDeltaY = calculateCommonVector(commonDeltaY, delta.dy);
            }
        }

        // Consider transitions from PRESS to SWIPE or MULTITOUCH.
        if (mPointerGesture.currentGestureMode == PointerGesture::PRESS) {
            float dist[MAX_POINTER_ID + 1];
            int32_t distOverThreshold = 0;
            for (BitSet32 idBits(mPointerGesture.referenceIdBits); !idBits.isEmpty(); ) {
                uint32_t id = idBits.firstMarkedBit();
                idBits.clearBit(id);

                PointerGesture::Delta& delta = mPointerGesture.referenceDeltas[id];
                dist[id] = hypotf(delta.dx * mLocked.pointerGestureXZoomScale,
                        delta.dy * mLocked.pointerGestureYZoomScale);
                if (dist[id] > mConfig.pointerGestureMultitouchMinDistance) {
                    distOverThreshold += 1;
                }
            }

            // Only transition when at least two pointers have moved further than
            // the minimum distance threshold.
            if (distOverThreshold >= 2) {
                float d;
                if (mCurrentTouch.pointerCount > 2) {
                    // There are more than two pointers, switch to FREEFORM.
#if DEBUG_GESTURES
                    LOGD("Gestures: PRESS transitioned to FREEFORM, number of pointers %d > 2",
                            mCurrentTouch.pointerCount);
#endif
                    *outCancelPreviousGesture = true;
                    mPointerGesture.currentGestureMode = PointerGesture::FREEFORM;
                } else if (((d = distance(
                        mCurrentTouch.pointers[0].x, mCurrentTouch.pointers[0].y,
                        mCurrentTouch.pointers[1].x, mCurrentTouch.pointers[1].y))
                                > mLocked.pointerGestureMaxSwipeWidth)) {
                    // There are two pointers but they are too far apart for a SWIPE,
                    // switch to FREEFORM.
#if DEBUG_GESTURES
                    LOGD("Gestures: PRESS transitioned to FREEFORM, distance %0.3f > %0.3f",
                            d, mLocked.pointerGestureMaxSwipeWidth);
#endif
                    *outCancelPreviousGesture = true;
                    mPointerGesture.currentGestureMode = PointerGesture::FREEFORM;
                } else {
                    // There are two pointers.  Wait for both pointers to start moving
                    // before deciding whether this is a SWIPE or FREEFORM gesture.
                    uint32_t id1 = mCurrentTouch.pointers[0].id;
                    uint32_t id2 = mCurrentTouch.pointers[1].id;
                    float dist1 = dist[id1];
                    float dist2 = dist[id2];
                    if (dist1 >= mConfig.pointerGestureMultitouchMinDistance
                            && dist2 >= mConfig.pointerGestureMultitouchMinDistance) {
                        // Calculate the dot product of the displacement vectors.
                        // When the vectors are oriented in approximately the same direction,
                        // the angle betweeen them is near zero and the cosine of the angle
                        // approches 1.0.  Recall that dot(v1, v2) = cos(angle) * mag(v1) * mag(v2).
                        PointerGesture::Delta& delta1 = mPointerGesture.referenceDeltas[id1];
                        PointerGesture::Delta& delta2 = mPointerGesture.referenceDeltas[id2];
                        float dx1 = delta1.dx * mLocked.pointerGestureXZoomScale;
                        float dy1 = delta1.dy * mLocked.pointerGestureYZoomScale;
                        float dx2 = delta2.dx * mLocked.pointerGestureXZoomScale;
                        float dy2 = delta2.dy * mLocked.pointerGestureYZoomScale;
                        float dot = dx1 * dx2 + dy1 * dy2;
                        float cosine = dot / (dist1 * dist2); // denominator always > 0
                        if (cosine >= mConfig.pointerGestureSwipeTransitionAngleCosine) {
                            // Pointers are moving in the same direction.  Switch to SWIPE.
#if DEBUG_GESTURES
                            LOGD("Gestures: PRESS transitioned to SWIPE, "
                                    "dist1 %0.3f >= %0.3f, dist2 %0.3f >= %0.3f, "
                                    "cosine %0.3f >= %0.3f",
                                    dist1, mConfig.pointerGestureMultitouchMinDistance,
                                    dist2, mConfig.pointerGestureMultitouchMinDistance,
                                    cosine, mConfig.pointerGestureSwipeTransitionAngleCosine);
#endif
                            mPointerGesture.currentGestureMode = PointerGesture::SWIPE;
                        } else {
                            // Pointers are moving in different directions.  Switch to FREEFORM.
#if DEBUG_GESTURES
                            LOGD("Gestures: PRESS transitioned to FREEFORM, "
                                    "dist1 %0.3f >= %0.3f, dist2 %0.3f >= %0.3f, "
                                    "cosine %0.3f < %0.3f",
                                    dist1, mConfig.pointerGestureMultitouchMinDistance,
                                    dist2, mConfig.pointerGestureMultitouchMinDistance,
                                    cosine, mConfig.pointerGestureSwipeTransitionAngleCosine);
#endif
                            *outCancelPreviousGesture = true;
                            mPointerGesture.currentGestureMode = PointerGesture::FREEFORM;
                        }
                    }
                }
            }
        } else if (mPointerGesture.currentGestureMode == PointerGesture::SWIPE) {
            // Switch from SWIPE to FREEFORM if additional pointers go down.
            // Cancel previous gesture.
            if (mCurrentTouch.pointerCount > 2) {
#if DEBUG_GESTURES
                LOGD("Gestures: SWIPE transitioned to FREEFORM, number of pointers %d > 2",
                        mCurrentTouch.pointerCount);
#endif
                *outCancelPreviousGesture = true;
                mPointerGesture.currentGestureMode = PointerGesture::FREEFORM;
            }
        }

        // Move the reference points based on the overall group motion of the fingers
        // except in PRESS mode while waiting for a transition to occur.
        if (mPointerGesture.currentGestureMode != PointerGesture::PRESS
                && (commonDeltaX || commonDeltaY)) {
            for (BitSet32 idBits(mPointerGesture.referenceIdBits); !idBits.isEmpty(); ) {
                uint32_t id = idBits.firstMarkedBit();
                idBits.clearBit(id);

                PointerGesture::Delta& delta = mPointerGesture.referenceDeltas[id];
                delta.dx = 0;
                delta.dy = 0;
            }

            mPointerGesture.referenceTouchX += commonDeltaX;
            mPointerGesture.referenceTouchY += commonDeltaY;

            commonDeltaX *= mLocked.pointerGestureXMovementScale;
            commonDeltaY *= mLocked.pointerGestureYMovementScale;

            rotateDelta(mLocked.surfaceOrientation, &commonDeltaX, &commonDeltaY);
            mPointerGesture.pointerVelocityControl.move(when, &commonDeltaX, &commonDeltaY);

            mPointerGesture.referenceGestureX += commonDeltaX;
            mPointerGesture.referenceGestureY += commonDeltaY;
        }

        // Report gestures.
        if (mPointerGesture.currentGestureMode == PointerGesture::PRESS
                || mPointerGesture.currentGestureMode == PointerGesture::SWIPE) {
            // PRESS or SWIPE mode.
#if DEBUG_GESTURES
            LOGD("Gestures: PRESS or SWIPE activeTouchId=%d,"
                    "activeGestureId=%d, currentTouchPointerCount=%d",
                    activeTouchId, mPointerGesture.activeGestureId, mCurrentTouch.pointerCount);
#endif
            LOG_ASSERT(mPointerGesture.activeGestureId >= 0);

            mPointerGesture.currentGestureIdBits.clear();
            mPointerGesture.currentGestureIdBits.markBit(mPointerGesture.activeGestureId);
            mPointerGesture.currentGestureIdToIndex[mPointerGesture.activeGestureId] = 0;
            mPointerGesture.currentGestureProperties[0].clear();
            mPointerGesture.currentGestureProperties[0].id = mPointerGesture.activeGestureId;
            mPointerGesture.currentGestureProperties[0].toolType =
                    AMOTION_EVENT_TOOL_TYPE_INDIRECT_FINGER;
            mPointerGesture.currentGestureCoords[0].clear();
            mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X,
                    mPointerGesture.referenceGestureX);
            mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y,
                    mPointerGesture.referenceGestureY);
            mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 1.0f);
        } else if (mPointerGesture.currentGestureMode == PointerGesture::FREEFORM) {
            // FREEFORM mode.
#if DEBUG_GESTURES
            LOGD("Gestures: FREEFORM activeTouchId=%d,"
                    "activeGestureId=%d, currentTouchPointerCount=%d",
                    activeTouchId, mPointerGesture.activeGestureId, mCurrentTouch.pointerCount);
#endif
            LOG_ASSERT(mPointerGesture.activeGestureId >= 0);

            mPointerGesture.currentGestureIdBits.clear();

            BitSet32 mappedTouchIdBits;
            BitSet32 usedGestureIdBits;
            if (mPointerGesture.lastGestureMode != PointerGesture::FREEFORM) {
                // Initially, assign the active gesture id to the active touch point
                // if there is one.  No other touch id bits are mapped yet.
                if (!*outCancelPreviousGesture) {
                    mappedTouchIdBits.markBit(activeTouchId);
                    usedGestureIdBits.markBit(mPointerGesture.activeGestureId);
                    mPointerGesture.freeformTouchToGestureIdMap[activeTouchId] =
                            mPointerGesture.activeGestureId;
                } else {
                    mPointerGesture.activeGestureId = -1;
                }
            } else {
                // Otherwise, assume we mapped all touches from the previous frame.
                // Reuse all mappings that are still applicable.
                mappedTouchIdBits.value = mLastTouch.idBits.value & mCurrentTouch.idBits.value;
                usedGestureIdBits = mPointerGesture.lastGestureIdBits;

                // Check whether we need to choose a new active gesture id because the
                // current went went up.
                for (BitSet32 upTouchIdBits(mLastTouch.idBits.value & ~mCurrentTouch.idBits.value);
                        !upTouchIdBits.isEmpty(); ) {
                    uint32_t upTouchId = upTouchIdBits.firstMarkedBit();
                    upTouchIdBits.clearBit(upTouchId);
                    uint32_t upGestureId = mPointerGesture.freeformTouchToGestureIdMap[upTouchId];
                    if (upGestureId == uint32_t(mPointerGesture.activeGestureId)) {
                        mPointerGesture.activeGestureId = -1;
                        break;
                    }
                }
            }

#if DEBUG_GESTURES
            LOGD("Gestures: FREEFORM follow up "
                    "mappedTouchIdBits=0x%08x, usedGestureIdBits=0x%08x, "
                    "activeGestureId=%d",
                    mappedTouchIdBits.value, usedGestureIdBits.value,
                    mPointerGesture.activeGestureId);
#endif

            for (uint32_t i = 0; i < mCurrentTouch.pointerCount; i++) {
                uint32_t touchId = mCurrentTouch.pointers[i].id;
                uint32_t gestureId;
                if (!mappedTouchIdBits.hasBit(touchId)) {
                    gestureId = usedGestureIdBits.firstUnmarkedBit();
                    usedGestureIdBits.markBit(gestureId);
                    mPointerGesture.freeformTouchToGestureIdMap[touchId] = gestureId;
#if DEBUG_GESTURES
                    LOGD("Gestures: FREEFORM "
                            "new mapping for touch id %d -> gesture id %d",
                            touchId, gestureId);
#endif
                } else {
                    gestureId = mPointerGesture.freeformTouchToGestureIdMap[touchId];
#if DEBUG_GESTURES
                    LOGD("Gestures: FREEFORM "
                            "existing mapping for touch id %d -> gesture id %d",
                            touchId, gestureId);
#endif
                }
                mPointerGesture.currentGestureIdBits.markBit(gestureId);
                mPointerGesture.currentGestureIdToIndex[gestureId] = i;

                float deltaX = (mCurrentTouch.pointers[i].x - mPointerGesture.referenceTouchX)
                        * mLocked.pointerGestureXZoomScale;
                float deltaY = (mCurrentTouch.pointers[i].y - mPointerGesture.referenceTouchY)
                        * mLocked.pointerGestureYZoomScale;
                rotateDelta(mLocked.surfaceOrientation, &deltaX, &deltaY);

                mPointerGesture.currentGestureProperties[i].clear();
                mPointerGesture.currentGestureProperties[i].id = gestureId;
                mPointerGesture.currentGestureProperties[i].toolType =
                        AMOTION_EVENT_TOOL_TYPE_INDIRECT_FINGER;
                mPointerGesture.currentGestureCoords[i].clear();
                mPointerGesture.currentGestureCoords[i].setAxisValue(
                        AMOTION_EVENT_AXIS_X, mPointerGesture.referenceGestureX + deltaX);
                mPointerGesture.currentGestureCoords[i].setAxisValue(
                        AMOTION_EVENT_AXIS_Y, mPointerGesture.referenceGestureY + deltaY);
                mPointerGesture.currentGestureCoords[i].setAxisValue(
                        AMOTION_EVENT_AXIS_PRESSURE, 1.0f);
            }

            if (mPointerGesture.activeGestureId < 0) {
                mPointerGesture.activeGestureId =
                        mPointerGesture.currentGestureIdBits.firstMarkedBit();
#if DEBUG_GESTURES
                LOGD("Gestures: FREEFORM new "
                        "activeGestureId=%d", mPointerGesture.activeGestureId);
#endif
            }
        }
    }

    mPointerController->setButtonState(mCurrentTouch.buttonState);

#if DEBUG_GESTURES
    LOGD("Gestures: finishPreviousGesture=%s, cancelPreviousGesture=%s, "
            "currentGestureMode=%d, currentGestureIdBits=0x%08x, "
            "lastGestureMode=%d, lastGestureIdBits=0x%08x",
            toString(*outFinishPreviousGesture), toString(*outCancelPreviousGesture),
            mPointerGesture.currentGestureMode, mPointerGesture.currentGestureIdBits.value,
            mPointerGesture.lastGestureMode, mPointerGesture.lastGestureIdBits.value);
    for (BitSet32 idBits = mPointerGesture.currentGestureIdBits; !idBits.isEmpty(); ) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);
        uint32_t index = mPointerGesture.currentGestureIdToIndex[id];
        const PointerProperties& properties = mPointerGesture.currentGestureProperties[index];
        const PointerCoords& coords = mPointerGesture.currentGestureCoords[index];
        LOGD("  currentGesture[%d]: index=%d, toolType=%d, "
                "x=%0.3f, y=%0.3f, pressure=%0.3f",
                id, index, properties.toolType,
                coords.getAxisValue(AMOTION_EVENT_AXIS_X),
                coords.getAxisValue(AMOTION_EVENT_AXIS_Y),
                coords.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE));
    }
    for (BitSet32 idBits = mPointerGesture.lastGestureIdBits; !idBits.isEmpty(); ) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);
        uint32_t index = mPointerGesture.lastGestureIdToIndex[id];
        const PointerProperties& properties = mPointerGesture.lastGestureProperties[index];
        const PointerCoords& coords = mPointerGesture.lastGestureCoords[index];
        LOGD("  lastGesture[%d]: index=%d, toolType=%d, "
                "x=%0.3f, y=%0.3f, pressure=%0.3f",
                id, index, properties.toolType,
                coords.getAxisValue(AMOTION_EVENT_AXIS_X),
                coords.getAxisValue(AMOTION_EVENT_AXIS_Y),
                coords.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE));
    }
#endif
    return true;
}

void TouchInputMapper::dispatchMotion(nsecs_t when, uint32_t policyFlags, uint32_t source,
        int32_t action, int32_t flags, int32_t metaState, int32_t buttonState, int32_t edgeFlags,
        const PointerProperties* properties, const PointerCoords* coords,
        const uint32_t* idToIndex, BitSet32 idBits,
        int32_t changedId, float xPrecision, float yPrecision, nsecs_t downTime) {
    PointerCoords pointerCoords[MAX_POINTERS];
    PointerProperties pointerProperties[MAX_POINTERS];
    uint32_t pointerCount = 0;
    while (!idBits.isEmpty()) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);
        uint32_t index = idToIndex[id];
        pointerProperties[pointerCount].copyFrom(properties[index]);
        pointerCoords[pointerCount].copyFrom(coords[index]);

        if (changedId >= 0 && id == uint32_t(changedId)) {
            action |= pointerCount << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT;
        }

        pointerCount += 1;
    }

    LOG_ASSERT(pointerCount != 0);

    if (changedId >= 0 && pointerCount == 1) {
        // Replace initial down and final up action.
        // We can compare the action without masking off the changed pointer index
        // because we know the index is 0.
        if (action == AMOTION_EVENT_ACTION_POINTER_DOWN) {
            action = AMOTION_EVENT_ACTION_DOWN;
        } else if (action == AMOTION_EVENT_ACTION_POINTER_UP) {
            action = AMOTION_EVENT_ACTION_UP;
        } else {
            // Can't happen.
            LOG_ASSERT(false);
        }
    }

    getDispatcher()->notifyMotion(when, getDeviceId(), source, policyFlags,
            action, flags, metaState, buttonState, edgeFlags,
            pointerCount, pointerProperties, pointerCoords, xPrecision, yPrecision, downTime);
}

bool TouchInputMapper::updateMovedPointers(const PointerProperties* inProperties,
        const PointerCoords* inCoords, const uint32_t* inIdToIndex,
        PointerProperties* outProperties, PointerCoords* outCoords, const uint32_t* outIdToIndex,
        BitSet32 idBits) const {
    bool changed = false;
    while (!idBits.isEmpty()) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);

        uint32_t inIndex = inIdToIndex[id];
        uint32_t outIndex = outIdToIndex[id];

        const PointerProperties& curInProperties = inProperties[inIndex];
        const PointerCoords& curInCoords = inCoords[inIndex];
        PointerProperties& curOutProperties = outProperties[outIndex];
        PointerCoords& curOutCoords = outCoords[outIndex];

        if (curInProperties != curOutProperties) {
            curOutProperties.copyFrom(curInProperties);
            changed = true;
        }

        if (curInCoords != curOutCoords) {
            curOutCoords.copyFrom(curInCoords);
            changed = true;
        }
    }
    return changed;
}

void TouchInputMapper::fadePointer() {
    { // acquire lock
        AutoMutex _l(mLock);
        if (mPointerController != NULL) {
            mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
        }
    } // release lock
}

int32_t TouchInputMapper::getTouchToolType(bool isStylus) const {
    if (mParameters.deviceType == Parameters::DEVICE_TYPE_TOUCH_SCREEN) {
        return isStylus ? AMOTION_EVENT_TOOL_TYPE_STYLUS : AMOTION_EVENT_TOOL_TYPE_FINGER;
    } else {
        return isStylus ? AMOTION_EVENT_TOOL_TYPE_INDIRECT_STYLUS
                : AMOTION_EVENT_TOOL_TYPE_INDIRECT_FINGER;
    }
}

bool TouchInputMapper::isPointInsideSurfaceLocked(int32_t x, int32_t y) {
    return x >= mRawAxes.x.minValue && x <= mRawAxes.x.maxValue
            && y >= mRawAxes.y.minValue && y <= mRawAxes.y.maxValue;
}

const TouchInputMapper::VirtualKey* TouchInputMapper::findVirtualKeyHitLocked(
        int32_t x, int32_t y) {
    size_t numVirtualKeys = mLocked.virtualKeys.size();
    for (size_t i = 0; i < numVirtualKeys; i++) {
        const VirtualKey& virtualKey = mLocked.virtualKeys[i];

#if DEBUG_VIRTUAL_KEYS
        LOGD("VirtualKeys: Hit test (%d, %d): keyCode=%d, scanCode=%d, "
                "left=%d, top=%d, right=%d, bottom=%d",
                x, y,
                virtualKey.keyCode, virtualKey.scanCode,
                virtualKey.hitLeft, virtualKey.hitTop,
                virtualKey.hitRight, virtualKey.hitBottom);
#endif

        if (virtualKey.isHit(x, y)) {
            return & virtualKey;
        }
    }

    return NULL;
}

void TouchInputMapper::calculatePointerIds() {
    uint32_t currentPointerCount = mCurrentTouch.pointerCount;
    uint32_t lastPointerCount = mLastTouch.pointerCount;

    if (currentPointerCount == 0) {
        // No pointers to assign.
        mCurrentTouch.idBits.clear();
    } else if (lastPointerCount == 0) {
        // All pointers are new.
        mCurrentTouch.idBits.clear();
        for (uint32_t i = 0; i < currentPointerCount; i++) {
            mCurrentTouch.pointers[i].id = i;
            mCurrentTouch.idToIndex[i] = i;
            mCurrentTouch.idBits.markBit(i);
        }
    } else if (currentPointerCount == 1 && lastPointerCount == 1) {
        // Only one pointer and no change in count so it must have the same id as before.
        uint32_t id = mLastTouch.pointers[0].id;
        mCurrentTouch.pointers[0].id = id;
        mCurrentTouch.idToIndex[id] = 0;
        mCurrentTouch.idBits.value = BitSet32::valueForBit(id);
    } else {
        // General case.
        // We build a heap of squared euclidean distances between current and last pointers
        // associated with the current and last pointer indices.  Then, we find the best
        // match (by distance) for each current pointer.
        PointerDistanceHeapElement heap[MAX_POINTERS * MAX_POINTERS];

        uint32_t heapSize = 0;
        for (uint32_t currentPointerIndex = 0; currentPointerIndex < currentPointerCount;
                currentPointerIndex++) {
            for (uint32_t lastPointerIndex = 0; lastPointerIndex < lastPointerCount;
                    lastPointerIndex++) {
                int64_t deltaX = mCurrentTouch.pointers[currentPointerIndex].x
                        - mLastTouch.pointers[lastPointerIndex].x;
                int64_t deltaY = mCurrentTouch.pointers[currentPointerIndex].y
                        - mLastTouch.pointers[lastPointerIndex].y;

                uint64_t distance = uint64_t(deltaX * deltaX + deltaY * deltaY);

                // Insert new element into the heap (sift up).
                heap[heapSize].currentPointerIndex = currentPointerIndex;
                heap[heapSize].lastPointerIndex = lastPointerIndex;
                heap[heapSize].distance = distance;
                heapSize += 1;
            }
        }

        // Heapify
        for (uint32_t startIndex = heapSize / 2; startIndex != 0; ) {
            startIndex -= 1;
            for (uint32_t parentIndex = startIndex; ;) {
                uint32_t childIndex = parentIndex * 2 + 1;
                if (childIndex >= heapSize) {
                    break;
                }

                if (childIndex + 1 < heapSize
                        && heap[childIndex + 1].distance < heap[childIndex].distance) {
                    childIndex += 1;
                }

                if (heap[parentIndex].distance <= heap[childIndex].distance) {
                    break;
                }

                swap(heap[parentIndex], heap[childIndex]);
                parentIndex = childIndex;
            }
        }

#if DEBUG_POINTER_ASSIGNMENT
        LOGD("calculatePointerIds - initial distance min-heap: size=%d", heapSize);
        for (size_t i = 0; i < heapSize; i++) {
            LOGD("  heap[%d]: cur=%d, last=%d, distance=%lld",
                    i, heap[i].currentPointerIndex, heap[i].lastPointerIndex,
                    heap[i].distance);
        }
#endif

        // Pull matches out by increasing order of distance.
        // To avoid reassigning pointers that have already been matched, the loop keeps track
        // of which last and current pointers have been matched using the matchedXXXBits variables.
        // It also tracks the used pointer id bits.
        BitSet32 matchedLastBits(0);
        BitSet32 matchedCurrentBits(0);
        BitSet32 usedIdBits(0);
        bool first = true;
        for (uint32_t i = min(currentPointerCount, lastPointerCount); i > 0; i--) {
            for (;;) {
                if (first) {
                    // The first time through the loop, we just consume the root element of
                    // the heap (the one with smallest distance).
                    first = false;
                } else {
                    // Previous iterations consumed the root element of the heap.
                    // Pop root element off of the heap (sift down).
                    heapSize -= 1;
                    LOG_ASSERT(heapSize > 0);

                    // Sift down.
                    heap[0] = heap[heapSize];
                    for (uint32_t parentIndex = 0; ;) {
                        uint32_t childIndex = parentIndex * 2 + 1;
                        if (childIndex >= heapSize) {
                            break;
                        }

                        if (childIndex + 1 < heapSize
                                && heap[childIndex + 1].distance < heap[childIndex].distance) {
                            childIndex += 1;
                        }

                        if (heap[parentIndex].distance <= heap[childIndex].distance) {
                            break;
                        }

                        swap(heap[parentIndex], heap[childIndex]);
                        parentIndex = childIndex;
                    }

#if DEBUG_POINTER_ASSIGNMENT
                    LOGD("calculatePointerIds - reduced distance min-heap: size=%d", heapSize);
                    for (size_t i = 0; i < heapSize; i++) {
                        LOGD("  heap[%d]: cur=%d, last=%d, distance=%lld",
                                i, heap[i].currentPointerIndex, heap[i].lastPointerIndex,
                                heap[i].distance);
                    }
#endif
                }

                uint32_t currentPointerIndex = heap[0].currentPointerIndex;
                if (matchedCurrentBits.hasBit(currentPointerIndex)) continue; // already matched

                uint32_t lastPointerIndex = heap[0].lastPointerIndex;
                if (matchedLastBits.hasBit(lastPointerIndex)) continue; // already matched

                matchedCurrentBits.markBit(currentPointerIndex);
                matchedLastBits.markBit(lastPointerIndex);

                uint32_t id = mLastTouch.pointers[lastPointerIndex].id;
                mCurrentTouch.pointers[currentPointerIndex].id = id;
                mCurrentTouch.idToIndex[id] = currentPointerIndex;
                usedIdBits.markBit(id);

#if DEBUG_POINTER_ASSIGNMENT
                LOGD("calculatePointerIds - matched: cur=%d, last=%d, id=%d, distance=%lld",
                        lastPointerIndex, currentPointerIndex, id, heap[0].distance);
#endif
                break;
            }
        }

        // Assign fresh ids to new pointers.
        if (currentPointerCount > lastPointerCount) {
            for (uint32_t i = currentPointerCount - lastPointerCount; ;) {
                uint32_t currentPointerIndex = matchedCurrentBits.firstUnmarkedBit();
                uint32_t id = usedIdBits.firstUnmarkedBit();

                mCurrentTouch.pointers[currentPointerIndex].id = id;
                mCurrentTouch.idToIndex[id] = currentPointerIndex;
                usedIdBits.markBit(id);

#if DEBUG_POINTER_ASSIGNMENT
                LOGD("calculatePointerIds - assigned: cur=%d, id=%d",
                        currentPointerIndex, id);
#endif

                if (--i == 0) break; // done
                matchedCurrentBits.markBit(currentPointerIndex);
            }
        }

        // Fix id bits.
        mCurrentTouch.idBits = usedIdBits;
    }
}

int32_t TouchInputMapper::getKeyCodeState(uint32_t sourceMask, int32_t keyCode) {
    { // acquire lock
        AutoMutex _l(mLock);

        if (mLocked.currentVirtualKey.down && mLocked.currentVirtualKey.keyCode == keyCode) {
            return AKEY_STATE_VIRTUAL;
        }

        size_t numVirtualKeys = mLocked.virtualKeys.size();
        for (size_t i = 0; i < numVirtualKeys; i++) {
            const VirtualKey& virtualKey = mLocked.virtualKeys[i];
            if (virtualKey.keyCode == keyCode) {
                return AKEY_STATE_UP;
            }
        }
    } // release lock

    return AKEY_STATE_UNKNOWN;
}

int32_t TouchInputMapper::getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
    { // acquire lock
        AutoMutex _l(mLock);

        if (mLocked.currentVirtualKey.down && mLocked.currentVirtualKey.scanCode == scanCode) {
            return AKEY_STATE_VIRTUAL;
        }

        size_t numVirtualKeys = mLocked.virtualKeys.size();
        for (size_t i = 0; i < numVirtualKeys; i++) {
            const VirtualKey& virtualKey = mLocked.virtualKeys[i];
            if (virtualKey.scanCode == scanCode) {
                return AKEY_STATE_UP;
            }
        }
    } // release lock

    return AKEY_STATE_UNKNOWN;
}

bool TouchInputMapper::markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) {
    { // acquire lock
        AutoMutex _l(mLock);

        size_t numVirtualKeys = mLocked.virtualKeys.size();
        for (size_t i = 0; i < numVirtualKeys; i++) {
            const VirtualKey& virtualKey = mLocked.virtualKeys[i];

            for (size_t i = 0; i < numCodes; i++) {
                if (virtualKey.keyCode == keyCodes[i]) {
                    outFlags[i] = 1;
                }
            }
        }
    } // release lock

    return true;
}


// --- SingleTouchInputMapper ---

SingleTouchInputMapper::SingleTouchInputMapper(InputDevice* device) :
        TouchInputMapper(device) {
    clearState();
}

SingleTouchInputMapper::~SingleTouchInputMapper() {
}

void SingleTouchInputMapper::clearState() {
    mAccumulator.clear();

    mDown = false;
    mX = 0;
    mY = 0;
    mPressure = 0; // default to 0 for devices that don't report pressure
    mToolWidth = 0; // default to 0 for devices that don't report tool width
    mButtonState = 0;
}

void SingleTouchInputMapper::reset() {
    TouchInputMapper::reset();

    clearState();
 }

void SingleTouchInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_KEY:
        switch (rawEvent->scanCode) {
        case BTN_TOUCH:
            mAccumulator.fields |= Accumulator::FIELD_BTN_TOUCH;
            mAccumulator.btnTouch = rawEvent->value != 0;
            // Don't sync immediately.  Wait until the next SYN_REPORT since we might
            // not have received valid position information yet.  This logic assumes that
            // BTN_TOUCH is always followed by SYN_REPORT as part of a complete packet.
            break;
        default:
            if (mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER) {
                int32_t buttonState = getButtonStateForScanCode(rawEvent->scanCode);
                if (buttonState) {
                    if (rawEvent->value) {
                        mAccumulator.buttonDown |= buttonState;
                    } else {
                        mAccumulator.buttonUp |= buttonState;
                    }
                    mAccumulator.fields |= Accumulator::FIELD_BUTTONS;
                }
            }
            break;
        }
        break;

    case EV_ABS:
        switch (rawEvent->scanCode) {
        case ABS_X:
            mAccumulator.fields |= Accumulator::FIELD_ABS_X;
            mAccumulator.absX = rawEvent->value;
            break;
        case ABS_Y:
            mAccumulator.fields |= Accumulator::FIELD_ABS_Y;
            mAccumulator.absY = rawEvent->value;
            break;
        case ABS_PRESSURE:
            mAccumulator.fields |= Accumulator::FIELD_ABS_PRESSURE;
            mAccumulator.absPressure = rawEvent->value;
            break;
        case ABS_TOOL_WIDTH:
            mAccumulator.fields |= Accumulator::FIELD_ABS_TOOL_WIDTH;
            mAccumulator.absToolWidth = rawEvent->value;
            break;
        }
        break;

    case EV_SYN:
        switch (rawEvent->scanCode) {
        case SYN_REPORT:
            sync(rawEvent->when);
            break;
        }
        break;
    }
}

void SingleTouchInputMapper::sync(nsecs_t when) {
    uint32_t fields = mAccumulator.fields;
    if (fields == 0) {
        return; // no new state changes, so nothing to do
    }

    if (fields & Accumulator::FIELD_BTN_TOUCH) {
        mDown = mAccumulator.btnTouch;
    }

    if (fields & Accumulator::FIELD_ABS_X) {
        mX = mAccumulator.absX;
    }

    if (fields & Accumulator::FIELD_ABS_Y) {
        mY = mAccumulator.absY;
    }

    if (fields & Accumulator::FIELD_ABS_PRESSURE) {
        mPressure = mAccumulator.absPressure;
    }

    if (fields & Accumulator::FIELD_ABS_TOOL_WIDTH) {
        mToolWidth = mAccumulator.absToolWidth;
    }

    if (fields & Accumulator::FIELD_BUTTONS) {
        mButtonState = (mButtonState | mAccumulator.buttonDown) & ~mAccumulator.buttonUp;
    }

    mCurrentTouch.clear();

    if (mDown) {
        mCurrentTouch.pointerCount = 1;
        mCurrentTouch.pointers[0].id = 0;
        mCurrentTouch.pointers[0].x = mX;
        mCurrentTouch.pointers[0].y = mY;
        mCurrentTouch.pointers[0].pressure = mPressure;
        mCurrentTouch.pointers[0].touchMajor = 0;
        mCurrentTouch.pointers[0].touchMinor = 0;
        mCurrentTouch.pointers[0].toolMajor = mToolWidth;
        mCurrentTouch.pointers[0].toolMinor = mToolWidth;
        mCurrentTouch.pointers[0].orientation = 0;
        mCurrentTouch.pointers[0].distance = 0;
        mCurrentTouch.pointers[0].isStylus = false; // TODO: Set stylus
        mCurrentTouch.idToIndex[0] = 0;
        mCurrentTouch.idBits.markBit(0);
        mCurrentTouch.buttonState = mButtonState;
    }

    syncTouch(when, true);

    mAccumulator.clear();
}

void SingleTouchInputMapper::configureRawAxes() {
    TouchInputMapper::configureRawAxes();

    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_X, & mRawAxes.x);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_Y, & mRawAxes.y);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_PRESSURE, & mRawAxes.pressure);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_TOOL_WIDTH, & mRawAxes.toolMajor);
}


// --- MultiTouchInputMapper ---

MultiTouchInputMapper::MultiTouchInputMapper(InputDevice* device) :
        TouchInputMapper(device), mSlotCount(0), mUsingSlotsProtocol(false) {
}

MultiTouchInputMapper::~MultiTouchInputMapper() {
}

void MultiTouchInputMapper::clearState() {
    mAccumulator.clearSlots(mSlotCount);
    mAccumulator.clearButtons();
    mButtonState = 0;
    mPointerIdBits.clear();

    if (mUsingSlotsProtocol) {
        // Query the driver for the current slot index and use it as the initial slot
        // before we start reading events from the device.  It is possible that the
        // current slot index will not be the same as it was when the first event was
        // written into the evdev buffer, which means the input mapper could start
        // out of sync with the initial state of the events in the evdev buffer.
        // In the extremely unlikely case that this happens, the data from
        // two slots will be confused until the next ABS_MT_SLOT event is received.
        // This can cause the touch point to "jump", but at least there will be
        // no stuck touches.
        status_t status = getEventHub()->getAbsoluteAxisValue(getDeviceId(), ABS_MT_SLOT,
                &mAccumulator.currentSlot);
        if (status) {
            LOGW("Could not retrieve current multitouch slot index.  status=%d", status);
            mAccumulator.currentSlot = -1;
        }
    }
}

void MultiTouchInputMapper::reset() {
    TouchInputMapper::reset();

    clearState();
}

void MultiTouchInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_KEY: {
        if (mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER) {
            int32_t buttonState = getButtonStateForScanCode(rawEvent->scanCode);
            if (buttonState) {
                if (rawEvent->value) {
                    mAccumulator.buttonDown |= buttonState;
                } else {
                    mAccumulator.buttonUp |= buttonState;
                }
            }
        }
        break;
    }

    case EV_ABS: {
        bool newSlot = false;
        if (mUsingSlotsProtocol && rawEvent->scanCode == ABS_MT_SLOT) {
            mAccumulator.currentSlot = rawEvent->value;
            newSlot = true;
        }

        if (mAccumulator.currentSlot < 0 || size_t(mAccumulator.currentSlot) >= mSlotCount) {
#if DEBUG_POINTERS
            if (newSlot) {
                LOGW("MultiTouch device %s emitted invalid slot index %d but it "
                        "should be between 0 and %d; ignoring this slot.",
                        getDeviceName().string(), mAccumulator.currentSlot, mSlotCount);
            }
#endif
            break;
        }

        Accumulator::Slot* slot = &mAccumulator.slots[mAccumulator.currentSlot];

        switch (rawEvent->scanCode) {
        case ABS_MT_POSITION_X:
            slot->fields |= Accumulator::FIELD_ABS_MT_POSITION_X;
            slot->absMTPositionX = rawEvent->value;
            break;
        case ABS_MT_POSITION_Y:
            slot->fields |= Accumulator::FIELD_ABS_MT_POSITION_Y;
            slot->absMTPositionY = rawEvent->value;
            break;
        case ABS_MT_TOUCH_MAJOR:
            slot->fields |= Accumulator::FIELD_ABS_MT_TOUCH_MAJOR;
            slot->absMTTouchMajor = rawEvent->value;
            break;
        case ABS_MT_TOUCH_MINOR:
            slot->fields |= Accumulator::FIELD_ABS_MT_TOUCH_MINOR;
            slot->absMTTouchMinor = rawEvent->value;
            break;
        case ABS_MT_WIDTH_MAJOR:
            slot->fields |= Accumulator::FIELD_ABS_MT_WIDTH_MAJOR;
            slot->absMTWidthMajor = rawEvent->value;
            break;
        case ABS_MT_WIDTH_MINOR:
            slot->fields |= Accumulator::FIELD_ABS_MT_WIDTH_MINOR;
            slot->absMTWidthMinor = rawEvent->value;
            break;
        case ABS_MT_ORIENTATION:
            slot->fields |= Accumulator::FIELD_ABS_MT_ORIENTATION;
            slot->absMTOrientation = rawEvent->value;
            break;
        case ABS_MT_TRACKING_ID:
            if (mUsingSlotsProtocol && rawEvent->value < 0) {
                slot->clear();
            } else {
                slot->fields |= Accumulator::FIELD_ABS_MT_TRACKING_ID;
                slot->absMTTrackingId = rawEvent->value;
            }
            break;
        case ABS_MT_PRESSURE:
            slot->fields |= Accumulator::FIELD_ABS_MT_PRESSURE;
            slot->absMTPressure = rawEvent->value;
            break;
        case ABS_MT_TOOL_TYPE:
            slot->fields |= Accumulator::FIELD_ABS_MT_TOOL_TYPE;
            slot->absMTToolType = rawEvent->value;
            break;
        }
        break;
    }

    case EV_SYN:
        switch (rawEvent->scanCode) {
        case SYN_MT_REPORT: {
            // MultiTouch Sync: The driver has returned all data for *one* of the pointers.
            mAccumulator.currentSlot += 1;
            break;
        }

        case SYN_REPORT:
            sync(rawEvent->when);
            break;
        }
        break;
    }
}

void MultiTouchInputMapper::sync(nsecs_t when) {
    static const uint32_t REQUIRED_FIELDS =
            Accumulator::FIELD_ABS_MT_POSITION_X | Accumulator::FIELD_ABS_MT_POSITION_Y;

    size_t inCount = mSlotCount;
    size_t outCount = 0;
    bool havePointerIds = true;

    mCurrentTouch.clear();

    for (size_t inIndex = 0; inIndex < inCount; inIndex++) {
        const Accumulator::Slot& inSlot = mAccumulator.slots[inIndex];
        uint32_t fields = inSlot.fields;

        if ((fields & REQUIRED_FIELDS) != REQUIRED_FIELDS) {
            // Some drivers send empty MT sync packets without X / Y to indicate a pointer up.
            // This may also indicate an unused slot.
            // Drop this finger.
            continue;
        }

        if (outCount >= MAX_POINTERS) {
#if DEBUG_POINTERS
            LOGD("MultiTouch device %s emitted more than maximum of %d pointers; "
                    "ignoring the rest.",
                    getDeviceName().string(), MAX_POINTERS);
#endif
            break; // too many fingers!
        }

        PointerData& outPointer = mCurrentTouch.pointers[outCount];
        outPointer.x = inSlot.absMTPositionX;
        outPointer.y = inSlot.absMTPositionY;

        if (fields & Accumulator::FIELD_ABS_MT_PRESSURE) {
            outPointer.pressure = inSlot.absMTPressure;
        } else {
            // Default pressure to 0 if absent.
            outPointer.pressure = 0;
        }

        if (fields & Accumulator::FIELD_ABS_MT_TOUCH_MAJOR) {
            outPointer.touchMajor = inSlot.absMTTouchMajor;
        } else {
            // Default touch area to 0 if absent.
            outPointer.touchMajor = 0;
        }

        if (fields & Accumulator::FIELD_ABS_MT_TOUCH_MINOR) {
            outPointer.touchMinor = inSlot.absMTTouchMinor;
        } else {
            // Assume touch area is circular.
            outPointer.touchMinor = outPointer.touchMajor;
        }

        if (fields & Accumulator::FIELD_ABS_MT_WIDTH_MAJOR) {
            outPointer.toolMajor = inSlot.absMTWidthMajor;
        } else {
            // Default tool area to 0 if absent.
            outPointer.toolMajor = 0;
        }

        if (fields & Accumulator::FIELD_ABS_MT_WIDTH_MINOR) {
            outPointer.toolMinor = inSlot.absMTWidthMinor;
        } else {
            // Assume tool area is circular.
            outPointer.toolMinor = outPointer.toolMajor;
        }

        if (fields & Accumulator::FIELD_ABS_MT_ORIENTATION) {
            outPointer.orientation = inSlot.absMTOrientation;
        } else {
            // Default orientation to vertical if absent.
            outPointer.orientation = 0;
        }

        if (fields & Accumulator::FIELD_ABS_MT_DISTANCE) {
            outPointer.distance = inSlot.absMTDistance;
        } else {
            // Default distance is 0 (direct contact).
            outPointer.distance = 0;
        }

        if (fields & Accumulator::FIELD_ABS_MT_TOOL_TYPE) {
            outPointer.isStylus = (inSlot.absMTToolType == MT_TOOL_PEN);
        } else {
            // Assume this is not a stylus.
            outPointer.isStylus = false;
        }

        // Assign pointer id using tracking id if available.
        if (havePointerIds) {
            int32_t id = -1;
            if (fields & Accumulator::FIELD_ABS_MT_TRACKING_ID) {
                int32_t trackingId = inSlot.absMTTrackingId;

                for (BitSet32 idBits(mPointerIdBits); !idBits.isEmpty(); ) {
                    uint32_t n = idBits.firstMarkedBit();
                    idBits.clearBit(n);

                    if (mPointerTrackingIdMap[n] == trackingId) {
                        id = n;
                    }
                }

                if (id < 0 && !mPointerIdBits.isFull()) {
                    id = mPointerIdBits.firstUnmarkedBit();
                    mPointerIdBits.markBit(id);
                    mPointerTrackingIdMap[id] = trackingId;
                }
            }
            if (id < 0) {
                havePointerIds = false;
                mCurrentTouch.idBits.clear();
            } else {
                outPointer.id = id;
                mCurrentTouch.idToIndex[id] = outCount;
                mCurrentTouch.idBits.markBit(id);
            }
        }

        outCount += 1;
    }

    mCurrentTouch.pointerCount = outCount;

    mButtonState = (mButtonState | mAccumulator.buttonDown) & ~mAccumulator.buttonUp;
    mCurrentTouch.buttonState = mButtonState;

    mPointerIdBits = mCurrentTouch.idBits;

    syncTouch(when, havePointerIds);

    if (!mUsingSlotsProtocol) {
        mAccumulator.clearSlots(mSlotCount);
    }
    mAccumulator.clearButtons();
}

void MultiTouchInputMapper::configureRawAxes() {
    TouchInputMapper::configureRawAxes();

    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_POSITION_X, &mRawAxes.x);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_POSITION_Y, &mRawAxes.y);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_TOUCH_MAJOR, &mRawAxes.touchMajor);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_TOUCH_MINOR, &mRawAxes.touchMinor);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_WIDTH_MAJOR, &mRawAxes.toolMajor);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_WIDTH_MINOR, &mRawAxes.toolMinor);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_ORIENTATION, &mRawAxes.orientation);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_PRESSURE, &mRawAxes.pressure);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_DISTANCE, &mRawAxes.distance);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_TRACKING_ID, &mRawAxes.trackingId);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_SLOT, &mRawAxes.slot);

    if (mRawAxes.trackingId.valid
            && mRawAxes.slot.valid && mRawAxes.slot.minValue == 0 && mRawAxes.slot.maxValue > 0) {
        mSlotCount = mRawAxes.slot.maxValue + 1;
        if (mSlotCount > MAX_SLOTS) {
            LOGW("MultiTouch Device %s reported %d slots but the framework "
                    "only supports a maximum of %d slots at this time.",
                    getDeviceName().string(), mSlotCount, MAX_SLOTS);
            mSlotCount = MAX_SLOTS;
        }
        mUsingSlotsProtocol = true;
    } else {
        mSlotCount = MAX_POINTERS;
        mUsingSlotsProtocol = false;
    }

    mAccumulator.allocateSlots(mSlotCount);

    clearState();
}


// --- JoystickInputMapper ---

JoystickInputMapper::JoystickInputMapper(InputDevice* device) :
        InputMapper(device) {
}

JoystickInputMapper::~JoystickInputMapper() {
}

uint32_t JoystickInputMapper::getSources() {
    return AINPUT_SOURCE_JOYSTICK;
}

void JoystickInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    for (size_t i = 0; i < mAxes.size(); i++) {
        const Axis& axis = mAxes.valueAt(i);
        info->addMotionRange(axis.axisInfo.axis, AINPUT_SOURCE_JOYSTICK,
                axis.min, axis.max, axis.flat, axis.fuzz);
        if (axis.axisInfo.mode == AxisInfo::MODE_SPLIT) {
            info->addMotionRange(axis.axisInfo.highAxis, AINPUT_SOURCE_JOYSTICK,
                    axis.min, axis.max, axis.flat, axis.fuzz);
        }
    }
}

void JoystickInputMapper::dump(String8& dump) {
    dump.append(INDENT2 "Joystick Input Mapper:\n");

    dump.append(INDENT3 "Axes:\n");
    size_t numAxes = mAxes.size();
    for (size_t i = 0; i < numAxes; i++) {
        const Axis& axis = mAxes.valueAt(i);
        const char* label = getAxisLabel(axis.axisInfo.axis);
        if (label) {
            dump.appendFormat(INDENT4 "%s", label);
        } else {
            dump.appendFormat(INDENT4 "%d", axis.axisInfo.axis);
        }
        if (axis.axisInfo.mode == AxisInfo::MODE_SPLIT) {
            label = getAxisLabel(axis.axisInfo.highAxis);
            if (label) {
                dump.appendFormat(" / %s (split at %d)", label, axis.axisInfo.splitValue);
            } else {
                dump.appendFormat(" / %d (split at %d)", axis.axisInfo.highAxis,
                        axis.axisInfo.splitValue);
            }
        } else if (axis.axisInfo.mode == AxisInfo::MODE_INVERT) {
            dump.append(" (invert)");
        }

        dump.appendFormat(": min=%0.5f, max=%0.5f, flat=%0.5f, fuzz=%0.5f\n",
                axis.min, axis.max, axis.flat, axis.fuzz);
        dump.appendFormat(INDENT4 "  scale=%0.5f, offset=%0.5f, "
                "highScale=%0.5f, highOffset=%0.5f\n",
                axis.scale, axis.offset, axis.highScale, axis.highOffset);
        dump.appendFormat(INDENT4 "  rawAxis=%d, rawMin=%d, rawMax=%d, "
                "rawFlat=%d, rawFuzz=%d, rawResolution=%d\n",
                mAxes.keyAt(i), axis.rawAxisInfo.minValue, axis.rawAxisInfo.maxValue,
                axis.rawAxisInfo.flat, axis.rawAxisInfo.fuzz, axis.rawAxisInfo.resolution);
    }
}

void JoystickInputMapper::configure(const InputReaderConfiguration* config, uint32_t changes) {
    InputMapper::configure(config, changes);

    if (!changes) { // first time only
        // Collect all axes.
        for (int32_t abs = 0; abs <= ABS_MAX; abs++) {
            RawAbsoluteAxisInfo rawAxisInfo;
            getEventHub()->getAbsoluteAxisInfo(getDeviceId(), abs, &rawAxisInfo);
            if (rawAxisInfo.valid) {
                // Map axis.
                AxisInfo axisInfo;
                bool explicitlyMapped = !getEventHub()->mapAxis(getDeviceId(), abs, &axisInfo);
                if (!explicitlyMapped) {
                    // Axis is not explicitly mapped, will choose a generic axis later.
                    axisInfo.mode = AxisInfo::MODE_NORMAL;
                    axisInfo.axis = -1;
                }

                // Apply flat override.
                int32_t rawFlat = axisInfo.flatOverride < 0
                        ? rawAxisInfo.flat : axisInfo.flatOverride;

                // Calculate scaling factors and limits.
                Axis axis;
                if (axisInfo.mode == AxisInfo::MODE_SPLIT) {
                    float scale = 1.0f / (axisInfo.splitValue - rawAxisInfo.minValue);
                    float highScale = 1.0f / (rawAxisInfo.maxValue - axisInfo.splitValue);
                    axis.initialize(rawAxisInfo, axisInfo, explicitlyMapped,
                            scale, 0.0f, highScale, 0.0f,
                            0.0f, 1.0f, rawFlat * scale, rawAxisInfo.fuzz * scale);
                } else if (isCenteredAxis(axisInfo.axis)) {
                    float scale = 2.0f / (rawAxisInfo.maxValue - rawAxisInfo.minValue);
                    float offset = avg(rawAxisInfo.minValue, rawAxisInfo.maxValue) * -scale;
                    axis.initialize(rawAxisInfo, axisInfo, explicitlyMapped,
                            scale, offset, scale, offset,
                            -1.0f, 1.0f, rawFlat * scale, rawAxisInfo.fuzz * scale);
                } else {
                    float scale = 1.0f / (rawAxisInfo.maxValue - rawAxisInfo.minValue);
                    axis.initialize(rawAxisInfo, axisInfo, explicitlyMapped,
                            scale, 0.0f, scale, 0.0f,
                            0.0f, 1.0f, rawFlat * scale, rawAxisInfo.fuzz * scale);
                }

                // To eliminate noise while the joystick is at rest, filter out small variations
                // in axis values up front.
                axis.filter = axis.flat * 0.25f;

                mAxes.add(abs, axis);
            }
        }

        // If there are too many axes, start dropping them.
        // Prefer to keep explicitly mapped axes.
        if (mAxes.size() > PointerCoords::MAX_AXES) {
            LOGI("Joystick '%s' has %d axes but the framework only supports a maximum of %d.",
                    getDeviceName().string(), mAxes.size(), PointerCoords::MAX_AXES);
            pruneAxes(true);
            pruneAxes(false);
        }

        // Assign generic axis ids to remaining axes.
        int32_t nextGenericAxisId = AMOTION_EVENT_AXIS_GENERIC_1;
        size_t numAxes = mAxes.size();
        for (size_t i = 0; i < numAxes; i++) {
            Axis& axis = mAxes.editValueAt(i);
            if (axis.axisInfo.axis < 0) {
                while (nextGenericAxisId <= AMOTION_EVENT_AXIS_GENERIC_16
                        && haveAxis(nextGenericAxisId)) {
                    nextGenericAxisId += 1;
                }

                if (nextGenericAxisId <= AMOTION_EVENT_AXIS_GENERIC_16) {
                    axis.axisInfo.axis = nextGenericAxisId;
                    nextGenericAxisId += 1;
                } else {
                    LOGI("Ignoring joystick '%s' axis %d because all of the generic axis ids "
                            "have already been assigned to other axes.",
                            getDeviceName().string(), mAxes.keyAt(i));
                    mAxes.removeItemsAt(i--);
                    numAxes -= 1;
                }
            }
        }
    }
}

bool JoystickInputMapper::haveAxis(int32_t axisId) {
    size_t numAxes = mAxes.size();
    for (size_t i = 0; i < numAxes; i++) {
        const Axis& axis = mAxes.valueAt(i);
        if (axis.axisInfo.axis == axisId
                || (axis.axisInfo.mode == AxisInfo::MODE_SPLIT
                        && axis.axisInfo.highAxis == axisId)) {
            return true;
        }
    }
    return false;
}

void JoystickInputMapper::pruneAxes(bool ignoreExplicitlyMappedAxes) {
    size_t i = mAxes.size();
    while (mAxes.size() > PointerCoords::MAX_AXES && i-- > 0) {
        if (ignoreExplicitlyMappedAxes && mAxes.valueAt(i).explicitlyMapped) {
            continue;
        }
        LOGI("Discarding joystick '%s' axis %d because there are too many axes.",
                getDeviceName().string(), mAxes.keyAt(i));
        mAxes.removeItemsAt(i);
    }
}

bool JoystickInputMapper::isCenteredAxis(int32_t axis) {
    switch (axis) {
    case AMOTION_EVENT_AXIS_X:
    case AMOTION_EVENT_AXIS_Y:
    case AMOTION_EVENT_AXIS_Z:
    case AMOTION_EVENT_AXIS_RX:
    case AMOTION_EVENT_AXIS_RY:
    case AMOTION_EVENT_AXIS_RZ:
    case AMOTION_EVENT_AXIS_HAT_X:
    case AMOTION_EVENT_AXIS_HAT_Y:
    case AMOTION_EVENT_AXIS_ORIENTATION:
    case AMOTION_EVENT_AXIS_RUDDER:
    case AMOTION_EVENT_AXIS_WHEEL:
        return true;
    default:
        return false;
    }
}

void JoystickInputMapper::reset() {
    // Recenter all axes.
    nsecs_t when = systemTime(SYSTEM_TIME_MONOTONIC);

    size_t numAxes = mAxes.size();
    for (size_t i = 0; i < numAxes; i++) {
        Axis& axis = mAxes.editValueAt(i);
        axis.resetValue();
    }

    sync(when, true /*force*/);

    InputMapper::reset();
}

void JoystickInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_ABS: {
        ssize_t index = mAxes.indexOfKey(rawEvent->scanCode);
        if (index >= 0) {
            Axis& axis = mAxes.editValueAt(index);
            float newValue, highNewValue;
            switch (axis.axisInfo.mode) {
            case AxisInfo::MODE_INVERT:
                newValue = (axis.rawAxisInfo.maxValue - rawEvent->value)
                        * axis.scale + axis.offset;
                highNewValue = 0.0f;
                break;
            case AxisInfo::MODE_SPLIT:
                if (rawEvent->value < axis.axisInfo.splitValue) {
                    newValue = (axis.axisInfo.splitValue - rawEvent->value)
                            * axis.scale + axis.offset;
                    highNewValue = 0.0f;
                } else if (rawEvent->value > axis.axisInfo.splitValue) {
                    newValue = 0.0f;
                    highNewValue = (rawEvent->value - axis.axisInfo.splitValue)
                            * axis.highScale + axis.highOffset;
                } else {
                    newValue = 0.0f;
                    highNewValue = 0.0f;
                }
                break;
            default:
                newValue = rawEvent->value * axis.scale + axis.offset;
                highNewValue = 0.0f;
                break;
            }
            axis.newValue = newValue;
            axis.highNewValue = highNewValue;
        }
        break;
    }

    case EV_SYN:
        switch (rawEvent->scanCode) {
        case SYN_REPORT:
            sync(rawEvent->when, false /*force*/);
            break;
        }
        break;
    }
}

void JoystickInputMapper::sync(nsecs_t when, bool force) {
    if (!filterAxes(force)) {
        return;
    }

    int32_t metaState = mContext->getGlobalMetaState();
    int32_t buttonState = 0;

    PointerProperties pointerProperties;
    pointerProperties.clear();
    pointerProperties.id = 0;
    pointerProperties.toolType = AMOTION_EVENT_TOOL_TYPE_UNKNOWN;

    PointerCoords pointerCoords;
    pointerCoords.clear();

    size_t numAxes = mAxes.size();
    for (size_t i = 0; i < numAxes; i++) {
        const Axis& axis = mAxes.valueAt(i);
        pointerCoords.setAxisValue(axis.axisInfo.axis, axis.currentValue);
        if (axis.axisInfo.mode == AxisInfo::MODE_SPLIT) {
            pointerCoords.setAxisValue(axis.axisInfo.highAxis, axis.highCurrentValue);
        }
    }

    // Moving a joystick axis should not wake the devide because joysticks can
    // be fairly noisy even when not in use.  On the other hand, pushing a gamepad
    // button will likely wake the device.
    // TODO: Use the input device configuration to control this behavior more finely.
    uint32_t policyFlags = 0;

    getDispatcher()->notifyMotion(when, getDeviceId(), AINPUT_SOURCE_JOYSTICK, policyFlags,
            AMOTION_EVENT_ACTION_MOVE, 0, metaState, buttonState, AMOTION_EVENT_EDGE_FLAG_NONE,
            1, &pointerProperties, &pointerCoords, 0, 0, 0);
}

bool JoystickInputMapper::filterAxes(bool force) {
    bool atLeastOneSignificantChange = force;
    size_t numAxes = mAxes.size();
    for (size_t i = 0; i < numAxes; i++) {
        Axis& axis = mAxes.editValueAt(i);
        if (force || hasValueChangedSignificantly(axis.filter,
                axis.newValue, axis.currentValue, axis.min, axis.max)) {
            axis.currentValue = axis.newValue;
            atLeastOneSignificantChange = true;
        }
        if (axis.axisInfo.mode == AxisInfo::MODE_SPLIT) {
            if (force || hasValueChangedSignificantly(axis.filter,
                    axis.highNewValue, axis.highCurrentValue, axis.min, axis.max)) {
                axis.highCurrentValue = axis.highNewValue;
                atLeastOneSignificantChange = true;
            }
        }
    }
    return atLeastOneSignificantChange;
}

bool JoystickInputMapper::hasValueChangedSignificantly(
        float filter, float newValue, float currentValue, float min, float max) {
    if (newValue != currentValue) {
        // Filter out small changes in value unless the value is converging on the axis
        // bounds or center point.  This is intended to reduce the amount of information
        // sent to applications by particularly noisy joysticks (such as PS3).
        if (fabs(newValue - currentValue) > filter
                || hasMovedNearerToValueWithinFilteredRange(filter, newValue, currentValue, min)
                || hasMovedNearerToValueWithinFilteredRange(filter, newValue, currentValue, max)
                || hasMovedNearerToValueWithinFilteredRange(filter, newValue, currentValue, 0)) {
            return true;
        }
    }
    return false;
}

bool JoystickInputMapper::hasMovedNearerToValueWithinFilteredRange(
        float filter, float newValue, float currentValue, float thresholdValue) {
    float newDistance = fabs(newValue - thresholdValue);
    if (newDistance < filter) {
        float oldDistance = fabs(currentValue - thresholdValue);
        if (newDistance < oldDistance) {
            return true;
        }
    }
    return false;
}

} // namespace android
