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
        NotifyKeyArgs args(when, deviceId, source, policyFlags,
                action, 0, keyCode, 0, context->getGlobalMetaState(), when);
        context->getListener()->notifyKey(&args);
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


// --- InputReaderConfiguration ---

bool InputReaderConfiguration::getDisplayInfo(int32_t displayId, bool external,
        int32_t* width, int32_t* height, int32_t* orientation) const {
    if (displayId == 0) {
        const DisplayInfo& info = external ? mExternalDisplay : mInternalDisplay;
        if (info.width > 0 && info.height > 0) {
            if (width) {
                *width = info.width;
            }
            if (height) {
                *height = info.height;
            }
            if (orientation) {
                *orientation = info.orientation;
            }
            return true;
        }
    }
    return false;
}

void InputReaderConfiguration::setDisplayInfo(int32_t displayId, bool external,
        int32_t width, int32_t height, int32_t orientation) {
    if (displayId == 0) {
        DisplayInfo& info = external ? mExternalDisplay : mInternalDisplay;
        info.width = width;
        info.height = height;
        info.orientation = orientation;
    }
}


// --- InputReader ---

InputReader::InputReader(const sp<EventHubInterface>& eventHub,
        const sp<InputReaderPolicyInterface>& policy,
        const sp<InputListenerInterface>& listener) :
        mContext(this), mEventHub(eventHub), mPolicy(policy),
        mGlobalMetaState(0), mDisableVirtualKeysTimeout(LLONG_MIN), mNextTimeout(LLONG_MAX),
        mConfigurationChangesToRefresh(0) {
    mQueuedListener = new QueuedInputListener(listener);

    { // acquire lock
        AutoMutex _l(mLock);

        refreshConfigurationLocked(0);
        updateGlobalMetaStateLocked();
        updateInputConfigurationLocked();
    } // release lock
}

InputReader::~InputReader() {
    for (size_t i = 0; i < mDevices.size(); i++) {
        delete mDevices.valueAt(i);
    }
}

void InputReader::loopOnce() {
    int32_t timeoutMillis;
    { // acquire lock
        AutoMutex _l(mLock);

        uint32_t changes = mConfigurationChangesToRefresh;
        if (changes) {
            mConfigurationChangesToRefresh = 0;
            refreshConfigurationLocked(changes);
        }

        timeoutMillis = -1;
        if (mNextTimeout != LLONG_MAX) {
            nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
            timeoutMillis = toMillisecondTimeoutDelay(now, mNextTimeout);
        }
    } // release lock

    size_t count = mEventHub->getEvents(timeoutMillis, mEventBuffer, EVENT_BUFFER_SIZE);

    { // acquire lock
        AutoMutex _l(mLock);

        if (count) {
            processEventsLocked(mEventBuffer, count);
        }
        if (!count || timeoutMillis == 0) {
            nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
#if DEBUG_RAW_EVENTS
            LOGD("Timeout expired, latency=%0.3fms", (now - mNextTimeout) * 0.000001f);
#endif
            mNextTimeout = LLONG_MAX;
            timeoutExpiredLocked(now);
        }
    } // release lock

    // Flush queued events out to the listener.
    // This must happen outside of the lock because the listener could potentially call
    // back into the InputReader's methods, such as getScanCodeState, or become blocked
    // on another thread similarly waiting to acquire the InputReader lock thereby
    // resulting in a deadlock.  This situation is actually quite plausible because the
    // listener is actually the input dispatcher, which calls into the window manager,
    // which occasionally calls into the input reader.
    mQueuedListener->flush();
}

void InputReader::processEventsLocked(const RawEvent* rawEvents, size_t count) {
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
            processEventsForDeviceLocked(deviceId, rawEvent, batchSize);
        } else {
            switch (rawEvent->type) {
            case EventHubInterface::DEVICE_ADDED:
                addDeviceLocked(rawEvent->when, rawEvent->deviceId);
                break;
            case EventHubInterface::DEVICE_REMOVED:
                removeDeviceLocked(rawEvent->when, rawEvent->deviceId);
                break;
            case EventHubInterface::FINISHED_DEVICE_SCAN:
                handleConfigurationChangedLocked(rawEvent->when);
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

void InputReader::addDeviceLocked(nsecs_t when, int32_t deviceId) {
    String8 name = mEventHub->getDeviceName(deviceId);
    uint32_t classes = mEventHub->getDeviceClasses(deviceId);

    InputDevice* device = createDeviceLocked(deviceId, name, classes);
    device->configure(when, &mConfig, 0);
    device->reset(when);

    if (device->isIgnored()) {
        LOGI("Device added: id=%d, name='%s' (ignored non-input device)", deviceId, name.string());
    } else {
        LOGI("Device added: id=%d, name='%s', sources=0x%08x", deviceId, name.string(),
                device->getSources());
    }

    ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
    if (deviceIndex < 0) {
        mDevices.add(deviceId, device);
    } else {
        LOGW("Ignoring spurious device added event for deviceId %d.", deviceId);
        delete device;
        return;
    }
}

void InputReader::removeDeviceLocked(nsecs_t when, int32_t deviceId) {
    InputDevice* device = NULL;
    ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
    if (deviceIndex >= 0) {
        device = mDevices.valueAt(deviceIndex);
        mDevices.removeItemsAt(deviceIndex, 1);
    } else {
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

    device->reset(when);
    delete device;
}

InputDevice* InputReader::createDeviceLocked(int32_t deviceId,
        const String8& name, uint32_t classes) {
    InputDevice* device = new InputDevice(&mContext, deviceId, name);

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

void InputReader::processEventsForDeviceLocked(int32_t deviceId,
        const RawEvent* rawEvents, size_t count) {
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
}

void InputReader::timeoutExpiredLocked(nsecs_t when) {
    for (size_t i = 0; i < mDevices.size(); i++) {
        InputDevice* device = mDevices.valueAt(i);
        if (!device->isIgnored()) {
            device->timeoutExpired(when);
        }
    }
}

void InputReader::handleConfigurationChangedLocked(nsecs_t when) {
    // Reset global meta state because it depends on the list of all configured devices.
    updateGlobalMetaStateLocked();

    // Update input configuration.
    updateInputConfigurationLocked();

    // Enqueue configuration changed.
    NotifyConfigurationChangedArgs args(when);
    mQueuedListener->notifyConfigurationChanged(&args);
}

void InputReader::refreshConfigurationLocked(uint32_t changes) {
    mPolicy->getReaderConfiguration(&mConfig);
    mEventHub->setExcludedDevices(mConfig.excludedDeviceNames);

    if (changes) {
        LOGI("Reconfiguring input devices.  changes=0x%08x", changes);
        nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);

        if (changes & InputReaderConfiguration::CHANGE_MUST_REOPEN) {
            mEventHub->requestReopenDevices();
        } else {
            for (size_t i = 0; i < mDevices.size(); i++) {
                InputDevice* device = mDevices.valueAt(i);
                device->configure(now, &mConfig, changes);
            }
        }
    }
}

void InputReader::updateGlobalMetaStateLocked() {
    mGlobalMetaState = 0;

    for (size_t i = 0; i < mDevices.size(); i++) {
        InputDevice* device = mDevices.valueAt(i);
        mGlobalMetaState |= device->getMetaState();
    }
}

int32_t InputReader::getGlobalMetaStateLocked() {
    return mGlobalMetaState;
}

void InputReader::updateInputConfigurationLocked() {
    int32_t touchScreenConfig = InputConfiguration::TOUCHSCREEN_NOTOUCH;
    int32_t keyboardConfig = InputConfiguration::KEYBOARD_NOKEYS;
    int32_t navigationConfig = InputConfiguration::NAVIGATION_NONAV;
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

    mInputConfiguration.touchScreen = touchScreenConfig;
    mInputConfiguration.keyboard = keyboardConfig;
    mInputConfiguration.navigation = navigationConfig;
}

void InputReader::disableVirtualKeysUntilLocked(nsecs_t time) {
    mDisableVirtualKeysTimeout = time;
}

bool InputReader::shouldDropVirtualKeyLocked(nsecs_t now,
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

void InputReader::fadePointerLocked() {
    for (size_t i = 0; i < mDevices.size(); i++) {
        InputDevice* device = mDevices.valueAt(i);
        device->fadePointer();
    }
}

void InputReader::requestTimeoutAtTimeLocked(nsecs_t when) {
    if (when < mNextTimeout) {
        mNextTimeout = when;
    }
}

void InputReader::getInputConfiguration(InputConfiguration* outConfiguration) {
    AutoMutex _l(mLock);

    *outConfiguration = mInputConfiguration;
}

status_t InputReader::getInputDeviceInfo(int32_t deviceId, InputDeviceInfo* outDeviceInfo) {
    AutoMutex _l(mLock);

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
}

void InputReader::getInputDeviceIds(Vector<int32_t>& outDeviceIds) {
    AutoMutex _l(mLock);

    outDeviceIds.clear();

    size_t numDevices = mDevices.size();
    for (size_t i = 0; i < numDevices; i++) {
        InputDevice* device = mDevices.valueAt(i);
        if (!device->isIgnored()) {
            outDeviceIds.add(device->getId());
        }
    }
}

int32_t InputReader::getKeyCodeState(int32_t deviceId, uint32_t sourceMask,
        int32_t keyCode) {
    AutoMutex _l(mLock);

    return getStateLocked(deviceId, sourceMask, keyCode, &InputDevice::getKeyCodeState);
}

int32_t InputReader::getScanCodeState(int32_t deviceId, uint32_t sourceMask,
        int32_t scanCode) {
    AutoMutex _l(mLock);

    return getStateLocked(deviceId, sourceMask, scanCode, &InputDevice::getScanCodeState);
}

int32_t InputReader::getSwitchState(int32_t deviceId, uint32_t sourceMask, int32_t switchCode) {
    AutoMutex _l(mLock);

    return getStateLocked(deviceId, sourceMask, switchCode, &InputDevice::getSwitchState);
}

int32_t InputReader::getStateLocked(int32_t deviceId, uint32_t sourceMask, int32_t code,
        GetStateFunc getStateFunc) {
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
}

bool InputReader::hasKeys(int32_t deviceId, uint32_t sourceMask,
        size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) {
    AutoMutex _l(mLock);

    memset(outFlags, 0, numCodes);
    return markSupportedKeyCodesLocked(deviceId, sourceMask, numCodes, keyCodes, outFlags);
}

bool InputReader::markSupportedKeyCodesLocked(int32_t deviceId, uint32_t sourceMask,
        size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) {
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
}

void InputReader::requestRefreshConfiguration(uint32_t changes) {
    AutoMutex _l(mLock);

    if (changes) {
        bool needWake = !mConfigurationChangesToRefresh;
        mConfigurationChangesToRefresh |= changes;

        if (needWake) {
            mEventHub->wake();
        }
    }
}

void InputReader::dump(String8& dump) {
    AutoMutex _l(mLock);

    mEventHub->dump(dump);
    dump.append("\n");

    dump.append("Input Reader State:\n");

    for (size_t i = 0; i < mDevices.size(); i++) {
        mDevices.valueAt(i)->dump(dump);
    }

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

void InputReader::monitor() {
    // Acquire and release the lock to ensure that the reader has not deadlocked.
    mLock.lock();
    mLock.unlock();

    // Check the EventHub
    mEventHub->monitor();
}


// --- InputReader::ContextImpl ---

InputReader::ContextImpl::ContextImpl(InputReader* reader) :
        mReader(reader) {
}

void InputReader::ContextImpl::updateGlobalMetaState() {
    // lock is already held by the input loop
    mReader->updateGlobalMetaStateLocked();
}

int32_t InputReader::ContextImpl::getGlobalMetaState() {
    // lock is already held by the input loop
    return mReader->getGlobalMetaStateLocked();
}

void InputReader::ContextImpl::disableVirtualKeysUntil(nsecs_t time) {
    // lock is already held by the input loop
    mReader->disableVirtualKeysUntilLocked(time);
}

bool InputReader::ContextImpl::shouldDropVirtualKey(nsecs_t now,
        InputDevice* device, int32_t keyCode, int32_t scanCode) {
    // lock is already held by the input loop
    return mReader->shouldDropVirtualKeyLocked(now, device, keyCode, scanCode);
}

void InputReader::ContextImpl::fadePointer() {
    // lock is already held by the input loop
    mReader->fadePointerLocked();
}

void InputReader::ContextImpl::requestTimeoutAtTime(nsecs_t when) {
    // lock is already held by the input loop
    mReader->requestTimeoutAtTimeLocked(when);
}

InputReaderPolicyInterface* InputReader::ContextImpl::getPolicy() {
    return mReader->mPolicy.get();
}

InputListenerInterface* InputReader::ContextImpl::getListener() {
    return mReader->mQueuedListener.get();
}

EventHubInterface* InputReader::ContextImpl::getEventHub() {
    return mReader->mEventHub.get();
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

void InputDevice::configure(nsecs_t when, const InputReaderConfiguration* config, uint32_t changes) {
    mSources = 0;

    if (!isIgnored()) {
        if (!changes) { // first time only
            mContext->getEventHub()->getConfiguration(mId, &mConfiguration);
        }

        size_t numMappers = mMappers.size();
        for (size_t i = 0; i < numMappers; i++) {
            InputMapper* mapper = mMappers[i];
            mapper->configure(when, config, changes);
            mSources |= mapper->getSources();
        }
    }
}

void InputDevice::reset(nsecs_t when) {
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        mapper->reset(when);
    }

    mContext->updateGlobalMetaState();

    notifyReset(when);
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
            reset(rawEvent->when);
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

void InputDevice::notifyReset(nsecs_t when) {
    NotifyDeviceResetArgs args(when, mId);
    mContext->getListener()->notifyDeviceReset(&args);
}


// --- CursorButtonAccumulator ---

CursorButtonAccumulator::CursorButtonAccumulator() {
    clearButtons();
}

void CursorButtonAccumulator::reset(InputDevice* device) {
    mBtnLeft = device->isKeyPressed(BTN_LEFT);
    mBtnRight = device->isKeyPressed(BTN_RIGHT);
    mBtnMiddle = device->isKeyPressed(BTN_MIDDLE);
    mBtnBack = device->isKeyPressed(BTN_BACK);
    mBtnSide = device->isKeyPressed(BTN_SIDE);
    mBtnForward = device->isKeyPressed(BTN_FORWARD);
    mBtnExtra = device->isKeyPressed(BTN_EXTRA);
    mBtnTask = device->isKeyPressed(BTN_TASK);
}

void CursorButtonAccumulator::clearButtons() {
    mBtnLeft = 0;
    mBtnRight = 0;
    mBtnMiddle = 0;
    mBtnBack = 0;
    mBtnSide = 0;
    mBtnForward = 0;
    mBtnExtra = 0;
    mBtnTask = 0;
}

void CursorButtonAccumulator::process(const RawEvent* rawEvent) {
    if (rawEvent->type == EV_KEY) {
        switch (rawEvent->scanCode) {
        case BTN_LEFT:
            mBtnLeft = rawEvent->value;
            break;
        case BTN_RIGHT:
            mBtnRight = rawEvent->value;
            break;
        case BTN_MIDDLE:
            mBtnMiddle = rawEvent->value;
            break;
        case BTN_BACK:
            mBtnBack = rawEvent->value;
            break;
        case BTN_SIDE:
            mBtnSide = rawEvent->value;
            break;
        case BTN_FORWARD:
            mBtnForward = rawEvent->value;
            break;
        case BTN_EXTRA:
            mBtnExtra = rawEvent->value;
            break;
        case BTN_TASK:
            mBtnTask = rawEvent->value;
            break;
        }
    }
}

uint32_t CursorButtonAccumulator::getButtonState() const {
    uint32_t result = 0;
    if (mBtnLeft) {
        result |= AMOTION_EVENT_BUTTON_PRIMARY;
    }
    if (mBtnRight) {
        result |= AMOTION_EVENT_BUTTON_SECONDARY;
    }
    if (mBtnMiddle) {
        result |= AMOTION_EVENT_BUTTON_TERTIARY;
    }
    if (mBtnBack || mBtnSide) {
        result |= AMOTION_EVENT_BUTTON_BACK;
    }
    if (mBtnForward || mBtnExtra) {
        result |= AMOTION_EVENT_BUTTON_FORWARD;
    }
    return result;
}


// --- CursorMotionAccumulator ---

CursorMotionAccumulator::CursorMotionAccumulator() {
    clearRelativeAxes();
}

void CursorMotionAccumulator::reset(InputDevice* device) {
    clearRelativeAxes();
}

void CursorMotionAccumulator::clearRelativeAxes() {
    mRelX = 0;
    mRelY = 0;
}

void CursorMotionAccumulator::process(const RawEvent* rawEvent) {
    if (rawEvent->type == EV_REL) {
        switch (rawEvent->scanCode) {
        case REL_X:
            mRelX = rawEvent->value;
            break;
        case REL_Y:
            mRelY = rawEvent->value;
            break;
        }
    }
}

void CursorMotionAccumulator::finishSync() {
    clearRelativeAxes();
}


// --- CursorScrollAccumulator ---

CursorScrollAccumulator::CursorScrollAccumulator() :
        mHaveRelWheel(false), mHaveRelHWheel(false) {
    clearRelativeAxes();
}

void CursorScrollAccumulator::configure(InputDevice* device) {
    mHaveRelWheel = device->getEventHub()->hasRelativeAxis(device->getId(), REL_WHEEL);
    mHaveRelHWheel = device->getEventHub()->hasRelativeAxis(device->getId(), REL_HWHEEL);
}

void CursorScrollAccumulator::reset(InputDevice* device) {
    clearRelativeAxes();
}

void CursorScrollAccumulator::clearRelativeAxes() {
    mRelWheel = 0;
    mRelHWheel = 0;
}

void CursorScrollAccumulator::process(const RawEvent* rawEvent) {
    if (rawEvent->type == EV_REL) {
        switch (rawEvent->scanCode) {
        case REL_WHEEL:
            mRelWheel = rawEvent->value;
            break;
        case REL_HWHEEL:
            mRelHWheel = rawEvent->value;
            break;
        }
    }
}

void CursorScrollAccumulator::finishSync() {
    clearRelativeAxes();
}


// --- TouchButtonAccumulator ---

TouchButtonAccumulator::TouchButtonAccumulator() :
        mHaveBtnTouch(false) {
    clearButtons();
}

void TouchButtonAccumulator::configure(InputDevice* device) {
    mHaveBtnTouch = device->hasKey(BTN_TOUCH);
}

void TouchButtonAccumulator::reset(InputDevice* device) {
    mBtnTouch = device->isKeyPressed(BTN_TOUCH);
    mBtnStylus = device->isKeyPressed(BTN_STYLUS);
    mBtnStylus2 = device->isKeyPressed(BTN_STYLUS);
    mBtnToolFinger = device->isKeyPressed(BTN_TOOL_FINGER);
    mBtnToolPen = device->isKeyPressed(BTN_TOOL_PEN);
    mBtnToolRubber = device->isKeyPressed(BTN_TOOL_RUBBER);
    mBtnToolBrush = device->isKeyPressed(BTN_TOOL_BRUSH);
    mBtnToolPencil = device->isKeyPressed(BTN_TOOL_PENCIL);
    mBtnToolAirbrush = device->isKeyPressed(BTN_TOOL_AIRBRUSH);
    mBtnToolMouse = device->isKeyPressed(BTN_TOOL_MOUSE);
    mBtnToolLens = device->isKeyPressed(BTN_TOOL_LENS);
    mBtnToolDoubleTap = device->isKeyPressed(BTN_TOOL_DOUBLETAP);
    mBtnToolTripleTap = device->isKeyPressed(BTN_TOOL_TRIPLETAP);
    mBtnToolQuadTap = device->isKeyPressed(BTN_TOOL_QUADTAP);
}

void TouchButtonAccumulator::clearButtons() {
    mBtnTouch = 0;
    mBtnStylus = 0;
    mBtnStylus2 = 0;
    mBtnToolFinger = 0;
    mBtnToolPen = 0;
    mBtnToolRubber = 0;
    mBtnToolBrush = 0;
    mBtnToolPencil = 0;
    mBtnToolAirbrush = 0;
    mBtnToolMouse = 0;
    mBtnToolLens = 0;
    mBtnToolDoubleTap = 0;
    mBtnToolTripleTap = 0;
    mBtnToolQuadTap = 0;
}

void TouchButtonAccumulator::process(const RawEvent* rawEvent) {
    if (rawEvent->type == EV_KEY) {
        switch (rawEvent->scanCode) {
        case BTN_TOUCH:
            mBtnTouch = rawEvent->value;
            break;
        case BTN_STYLUS:
            mBtnStylus = rawEvent->value;
            break;
        case BTN_STYLUS2:
            mBtnStylus2 = rawEvent->value;
            break;
        case BTN_TOOL_FINGER:
            mBtnToolFinger = rawEvent->value;
            break;
        case BTN_TOOL_PEN:
            mBtnToolPen = rawEvent->value;
            break;
        case BTN_TOOL_RUBBER:
            mBtnToolRubber = rawEvent->value;
            break;
        case BTN_TOOL_BRUSH:
            mBtnToolBrush = rawEvent->value;
            break;
        case BTN_TOOL_PENCIL:
            mBtnToolPencil = rawEvent->value;
            break;
        case BTN_TOOL_AIRBRUSH:
            mBtnToolAirbrush = rawEvent->value;
            break;
        case BTN_TOOL_MOUSE:
            mBtnToolMouse = rawEvent->value;
            break;
        case BTN_TOOL_LENS:
            mBtnToolLens = rawEvent->value;
            break;
        case BTN_TOOL_DOUBLETAP:
            mBtnToolDoubleTap = rawEvent->value;
            break;
        case BTN_TOOL_TRIPLETAP:
            mBtnToolTripleTap = rawEvent->value;
            break;
        case BTN_TOOL_QUADTAP:
            mBtnToolQuadTap = rawEvent->value;
            break;
        }
    }
}

uint32_t TouchButtonAccumulator::getButtonState() const {
    uint32_t result = 0;
    if (mBtnStylus) {
        result |= AMOTION_EVENT_BUTTON_SECONDARY;
    }
    if (mBtnStylus2) {
        result |= AMOTION_EVENT_BUTTON_TERTIARY;
    }
    return result;
}

int32_t TouchButtonAccumulator::getToolType() const {
    if (mBtnToolMouse || mBtnToolLens) {
        return AMOTION_EVENT_TOOL_TYPE_MOUSE;
    }
    if (mBtnToolRubber) {
        return AMOTION_EVENT_TOOL_TYPE_ERASER;
    }
    if (mBtnToolPen || mBtnToolBrush || mBtnToolPencil || mBtnToolAirbrush) {
        return AMOTION_EVENT_TOOL_TYPE_STYLUS;
    }
    if (mBtnToolFinger || mBtnToolDoubleTap || mBtnToolTripleTap || mBtnToolQuadTap) {
        return AMOTION_EVENT_TOOL_TYPE_FINGER;
    }
    return AMOTION_EVENT_TOOL_TYPE_UNKNOWN;
}

bool TouchButtonAccumulator::isToolActive() const {
    return mBtnTouch || mBtnToolFinger || mBtnToolPen || mBtnToolRubber
            || mBtnToolBrush || mBtnToolPencil || mBtnToolAirbrush
            || mBtnToolMouse || mBtnToolLens
            || mBtnToolDoubleTap || mBtnToolTripleTap || mBtnToolQuadTap;
}

bool TouchButtonAccumulator::isHovering() const {
    return mHaveBtnTouch && !mBtnTouch;
}


// --- RawPointerAxes ---

RawPointerAxes::RawPointerAxes() {
    clear();
}

void RawPointerAxes::clear() {
    x.clear();
    y.clear();
    pressure.clear();
    touchMajor.clear();
    touchMinor.clear();
    toolMajor.clear();
    toolMinor.clear();
    orientation.clear();
    distance.clear();
    tiltX.clear();
    tiltY.clear();
    trackingId.clear();
    slot.clear();
}


// --- RawPointerData ---

RawPointerData::RawPointerData() {
    clear();
}

void RawPointerData::clear() {
    pointerCount = 0;
    clearIdBits();
}

void RawPointerData::copyFrom(const RawPointerData& other) {
    pointerCount = other.pointerCount;
    hoveringIdBits = other.hoveringIdBits;
    touchingIdBits = other.touchingIdBits;

    for (uint32_t i = 0; i < pointerCount; i++) {
        pointers[i] = other.pointers[i];

        int id = pointers[i].id;
        idToIndex[id] = other.idToIndex[id];
    }
}

void RawPointerData::getCentroidOfTouchingPointers(float* outX, float* outY) const {
    float x = 0, y = 0;
    uint32_t count = touchingIdBits.count();
    if (count) {
        for (BitSet32 idBits(touchingIdBits); !idBits.isEmpty(); ) {
            uint32_t id = idBits.clearFirstMarkedBit();
            const Pointer& pointer = pointerForId(id);
            x += pointer.x;
            y += pointer.y;
        }
        x /= count;
        y /= count;
    }
    *outX = x;
    *outY = y;
}


// --- CookedPointerData ---

CookedPointerData::CookedPointerData() {
    clear();
}

void CookedPointerData::clear() {
    pointerCount = 0;
    hoveringIdBits.clear();
    touchingIdBits.clear();
}

void CookedPointerData::copyFrom(const CookedPointerData& other) {
    pointerCount = other.pointerCount;
    hoveringIdBits = other.hoveringIdBits;
    touchingIdBits = other.touchingIdBits;

    for (uint32_t i = 0; i < pointerCount; i++) {
        pointerProperties[i].copyFrom(other.pointerProperties[i]);
        pointerCoords[i].copyFrom(other.pointerCoords[i]);

        int id = pointerProperties[i].id;
        idToIndex[id] = other.idToIndex[id];
    }
}


// --- SingleTouchMotionAccumulator ---

SingleTouchMotionAccumulator::SingleTouchMotionAccumulator() {
    clearAbsoluteAxes();
}

void SingleTouchMotionAccumulator::reset(InputDevice* device) {
    mAbsX = device->getAbsoluteAxisValue(ABS_X);
    mAbsY = device->getAbsoluteAxisValue(ABS_Y);
    mAbsPressure = device->getAbsoluteAxisValue(ABS_PRESSURE);
    mAbsToolWidth = device->getAbsoluteAxisValue(ABS_TOOL_WIDTH);
    mAbsDistance = device->getAbsoluteAxisValue(ABS_DISTANCE);
    mAbsTiltX = device->getAbsoluteAxisValue(ABS_TILT_X);
    mAbsTiltY = device->getAbsoluteAxisValue(ABS_TILT_Y);
}

void SingleTouchMotionAccumulator::clearAbsoluteAxes() {
    mAbsX = 0;
    mAbsY = 0;
    mAbsPressure = 0;
    mAbsToolWidth = 0;
    mAbsDistance = 0;
    mAbsTiltX = 0;
    mAbsTiltY = 0;
}

void SingleTouchMotionAccumulator::process(const RawEvent* rawEvent) {
    if (rawEvent->type == EV_ABS) {
        switch (rawEvent->scanCode) {
        case ABS_X:
            mAbsX = rawEvent->value;
            break;
        case ABS_Y:
            mAbsY = rawEvent->value;
            break;
        case ABS_PRESSURE:
            mAbsPressure = rawEvent->value;
            break;
        case ABS_TOOL_WIDTH:
            mAbsToolWidth = rawEvent->value;
            break;
        case ABS_DISTANCE:
            mAbsDistance = rawEvent->value;
            break;
        case ABS_TILT_X:
            mAbsTiltX = rawEvent->value;
            break;
        case ABS_TILT_Y:
            mAbsTiltY = rawEvent->value;
            break;
        }
    }
}


// --- MultiTouchMotionAccumulator ---

MultiTouchMotionAccumulator::MultiTouchMotionAccumulator() :
        mCurrentSlot(-1), mSlots(NULL), mSlotCount(0), mUsingSlotsProtocol(false) {
}

MultiTouchMotionAccumulator::~MultiTouchMotionAccumulator() {
    delete[] mSlots;
}

void MultiTouchMotionAccumulator::configure(size_t slotCount, bool usingSlotsProtocol) {
    mSlotCount = slotCount;
    mUsingSlotsProtocol = usingSlotsProtocol;

    delete[] mSlots;
    mSlots = new Slot[slotCount];
}

void MultiTouchMotionAccumulator::reset(InputDevice* device) {
    // Unfortunately there is no way to read the initial contents of the slots.
    // So when we reset the accumulator, we must assume they are all zeroes.
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
        int32_t initialSlot;
        status_t status = device->getEventHub()->getAbsoluteAxisValue(device->getId(),
                ABS_MT_SLOT, &initialSlot);
        if (status) {
            LOGD("Could not retrieve current multitouch slot index.  status=%d", status);
            initialSlot = -1;
        }
        clearSlots(initialSlot);
    } else {
        clearSlots(-1);
    }
}

void MultiTouchMotionAccumulator::clearSlots(int32_t initialSlot) {
    if (mSlots) {
        for (size_t i = 0; i < mSlotCount; i++) {
            mSlots[i].clear();
        }
    }
    mCurrentSlot = initialSlot;
}

void MultiTouchMotionAccumulator::process(const RawEvent* rawEvent) {
    if (rawEvent->type == EV_ABS) {
        bool newSlot = false;
        if (mUsingSlotsProtocol) {
            if (rawEvent->scanCode == ABS_MT_SLOT) {
                mCurrentSlot = rawEvent->value;
                newSlot = true;
            }
        } else if (mCurrentSlot < 0) {
            mCurrentSlot = 0;
        }

        if (mCurrentSlot < 0 || size_t(mCurrentSlot) >= mSlotCount) {
#if DEBUG_POINTERS
            if (newSlot) {
                LOGW("MultiTouch device emitted invalid slot index %d but it "
                        "should be between 0 and %d; ignoring this slot.",
                        mCurrentSlot, mSlotCount - 1);
            }
#endif
        } else {
            Slot* slot = &mSlots[mCurrentSlot];

            switch (rawEvent->scanCode) {
            case ABS_MT_POSITION_X:
                slot->mInUse = true;
                slot->mAbsMTPositionX = rawEvent->value;
                break;
            case ABS_MT_POSITION_Y:
                slot->mInUse = true;
                slot->mAbsMTPositionY = rawEvent->value;
                break;
            case ABS_MT_TOUCH_MAJOR:
                slot->mInUse = true;
                slot->mAbsMTTouchMajor = rawEvent->value;
                break;
            case ABS_MT_TOUCH_MINOR:
                slot->mInUse = true;
                slot->mAbsMTTouchMinor = rawEvent->value;
                slot->mHaveAbsMTTouchMinor = true;
                break;
            case ABS_MT_WIDTH_MAJOR:
                slot->mInUse = true;
                slot->mAbsMTWidthMajor = rawEvent->value;
                break;
            case ABS_MT_WIDTH_MINOR:
                slot->mInUse = true;
                slot->mAbsMTWidthMinor = rawEvent->value;
                slot->mHaveAbsMTWidthMinor = true;
                break;
            case ABS_MT_ORIENTATION:
                slot->mInUse = true;
                slot->mAbsMTOrientation = rawEvent->value;
                break;
            case ABS_MT_TRACKING_ID:
                if (mUsingSlotsProtocol && rawEvent->value < 0) {
                    // The slot is no longer in use but it retains its previous contents,
                    // which may be reused for subsequent touches.
                    slot->mInUse = false;
                } else {
                    slot->mInUse = true;
                    slot->mAbsMTTrackingId = rawEvent->value;
                }
                break;
            case ABS_MT_PRESSURE:
                slot->mInUse = true;
                slot->mAbsMTPressure = rawEvent->value;
                break;
            case ABS_MT_DISTANCE:
                slot->mInUse = true;
                slot->mAbsMTDistance = rawEvent->value;
                break;
            case ABS_MT_TOOL_TYPE:
                slot->mInUse = true;
                slot->mAbsMTToolType = rawEvent->value;
                slot->mHaveAbsMTToolType = true;
                break;
            }
        }
    } else if (rawEvent->type == EV_SYN && rawEvent->scanCode == SYN_MT_REPORT) {
        // MultiTouch Sync: The driver has returned all data for *one* of the pointers.
        mCurrentSlot += 1;
    }
}

void MultiTouchMotionAccumulator::finishSync() {
    if (!mUsingSlotsProtocol) {
        clearSlots(-1);
    }
}


// --- MultiTouchMotionAccumulator::Slot ---

MultiTouchMotionAccumulator::Slot::Slot() {
    clear();
}

void MultiTouchMotionAccumulator::Slot::clear() {
    mInUse = false;
    mHaveAbsMTTouchMinor = false;
    mHaveAbsMTWidthMinor = false;
    mHaveAbsMTToolType = false;
    mAbsMTPositionX = 0;
    mAbsMTPositionY = 0;
    mAbsMTTouchMajor = 0;
    mAbsMTTouchMinor = 0;
    mAbsMTWidthMajor = 0;
    mAbsMTWidthMinor = 0;
    mAbsMTOrientation = 0;
    mAbsMTTrackingId = -1;
    mAbsMTPressure = 0;
    mAbsMTDistance = 0;
    mAbsMTToolType = 0;
}

int32_t MultiTouchMotionAccumulator::Slot::getToolType() const {
    if (mHaveAbsMTToolType) {
        switch (mAbsMTToolType) {
        case MT_TOOL_FINGER:
            return AMOTION_EVENT_TOOL_TYPE_FINGER;
        case MT_TOOL_PEN:
            return AMOTION_EVENT_TOOL_TYPE_STYLUS;
        }
    }
    return AMOTION_EVENT_TOOL_TYPE_UNKNOWN;
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

void InputMapper::configure(nsecs_t when,
        const InputReaderConfiguration* config, uint32_t changes) {
}

void InputMapper::reset(nsecs_t when) {
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

status_t InputMapper::getAbsoluteAxisInfo(int32_t axis, RawAbsoluteAxisInfo* axisInfo) {
    return getEventHub()->getAbsoluteAxisInfo(getDeviceId(), axis, axisInfo);
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
    NotifySwitchArgs args(when, 0, switchCode, switchValue);
    getListener()->notifySwitch(&args);
}

int32_t SwitchInputMapper::getSwitchState(uint32_t sourceMask, int32_t switchCode) {
    return getEventHub()->getSwitchState(getDeviceId(), switchCode);
}


// --- KeyboardInputMapper ---

KeyboardInputMapper::KeyboardInputMapper(InputDevice* device,
        uint32_t source, int32_t keyboardType) :
        InputMapper(device), mSource(source),
        mKeyboardType(keyboardType) {
}

KeyboardInputMapper::~KeyboardInputMapper() {
}

uint32_t KeyboardInputMapper::getSources() {
    return mSource;
}

void KeyboardInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    info->setKeyboardType(mKeyboardType);
}

void KeyboardInputMapper::dump(String8& dump) {
    dump.append(INDENT2 "Keyboard Input Mapper:\n");
    dumpParameters(dump);
    dump.appendFormat(INDENT3 "KeyboardType: %d\n", mKeyboardType);
    dump.appendFormat(INDENT3 "Orientation: %d\n", mOrientation);
    dump.appendFormat(INDENT3 "KeyDowns: %d keys currently down\n", mKeyDowns.size());
    dump.appendFormat(INDENT3 "MetaState: 0x%0x\n", mMetaState);
    dump.appendFormat(INDENT3 "DownTime: %lld\n", mDownTime);
}


void KeyboardInputMapper::configure(nsecs_t when,
        const InputReaderConfiguration* config, uint32_t changes) {
    InputMapper::configure(when, config, changes);

    if (!changes) { // first time only
        // Configure basic parameters.
        configureParameters();
    }

    if (!changes || (changes & InputReaderConfiguration::CHANGE_DISPLAY_INFO)) {
        if (mParameters.orientationAware && mParameters.associatedDisplayId >= 0) {
            if (!config->getDisplayInfo(mParameters.associatedDisplayId,
                        false /*external*/, NULL, NULL, &mOrientation)) {
                mOrientation = DISPLAY_ORIENTATION_0;
            }
        } else {
            mOrientation = DISPLAY_ORIENTATION_0;
        }
    }
}

void KeyboardInputMapper::configureParameters() {
    mParameters.orientationAware = false;
    getDevice()->getConfiguration().tryGetProperty(String8("keyboard.orientationAware"),
            mParameters.orientationAware);

    mParameters.associatedDisplayId = -1;
    if (mParameters.orientationAware) {
        mParameters.associatedDisplayId = 0;
    }
}

void KeyboardInputMapper::dumpParameters(String8& dump) {
    dump.append(INDENT3 "Parameters:\n");
    dump.appendFormat(INDENT4 "AssociatedDisplayId: %d\n",
            mParameters.associatedDisplayId);
    dump.appendFormat(INDENT4 "OrientationAware: %s\n",
            toString(mParameters.orientationAware));
}

void KeyboardInputMapper::reset(nsecs_t when) {
    mMetaState = AMETA_NONE;
    mDownTime = 0;
    mKeyDowns.clear();

    resetLedState();

    InputMapper::reset(when);
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

    if (down) {
        // Rotate key codes according to orientation if needed.
        if (mParameters.orientationAware && mParameters.associatedDisplayId >= 0) {
            keyCode = rotateKeyCode(keyCode, mOrientation);
        }

        // Add key down.
        ssize_t keyDownIndex = findKeyDown(scanCode);
        if (keyDownIndex >= 0) {
            // key repeat, be sure to use same keycode as before in case of rotation
            keyCode = mKeyDowns.itemAt(keyDownIndex).keyCode;
        } else {
            // key down
            if ((policyFlags & POLICY_FLAG_VIRTUAL)
                    && mContext->shouldDropVirtualKey(when,
                            getDevice(), keyCode, scanCode)) {
                return;
            }

            mKeyDowns.push();
            KeyDown& keyDown = mKeyDowns.editTop();
            keyDown.keyCode = keyCode;
            keyDown.scanCode = scanCode;
        }

        mDownTime = when;
    } else {
        // Remove key down.
        ssize_t keyDownIndex = findKeyDown(scanCode);
        if (keyDownIndex >= 0) {
            // key up, be sure to use same keycode as before in case of rotation
            keyCode = mKeyDowns.itemAt(keyDownIndex).keyCode;
            mKeyDowns.removeAt(size_t(keyDownIndex));
        } else {
            // key was not actually down
            LOGI("Dropping key up from device %s because the key was not down.  "
                    "keyCode=%d, scanCode=%d",
                    getDeviceName().string(), keyCode, scanCode);
            return;
        }
    }

    bool metaStateChanged = false;
    int32_t oldMetaState = mMetaState;
    int32_t newMetaState = updateMetaState(keyCode, down, oldMetaState);
    if (oldMetaState != newMetaState) {
        mMetaState = newMetaState;
        metaStateChanged = true;
        updateLedState(false);
    }

    nsecs_t downTime = mDownTime;

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

    NotifyKeyArgs args(when, getDeviceId(), mSource, policyFlags,
            down ? AKEY_EVENT_ACTION_DOWN : AKEY_EVENT_ACTION_UP,
            AKEY_EVENT_FLAG_FROM_SYSTEM, keyCode, scanCode, newMetaState, downTime);
    getListener()->notifyKey(&args);
}

ssize_t KeyboardInputMapper::findKeyDown(int32_t scanCode) {
    size_t n = mKeyDowns.size();
    for (size_t i = 0; i < n; i++) {
        if (mKeyDowns[i].scanCode == scanCode) {
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
    return mMetaState;
}

void KeyboardInputMapper::resetLedState() {
    initializeLedState(mCapsLockLedState, LED_CAPSL);
    initializeLedState(mNumLockLedState, LED_NUML);
    initializeLedState(mScrollLockLedState, LED_SCROLLL);

    updateLedState(true);
}

void KeyboardInputMapper::initializeLedState(LedState& ledState, int32_t led) {
    ledState.avail = getEventHub()->hasLed(getDeviceId(), led);
    ledState.on = false;
}

void KeyboardInputMapper::updateLedState(bool reset) {
    updateLedStateForModifier(mCapsLockLedState, LED_CAPSL,
            AMETA_CAPS_LOCK_ON, reset);
    updateLedStateForModifier(mNumLockLedState, LED_NUML,
            AMETA_NUM_LOCK_ON, reset);
    updateLedStateForModifier(mScrollLockLedState, LED_SCROLLL,
            AMETA_SCROLL_LOCK_ON, reset);
}

void KeyboardInputMapper::updateLedStateForModifier(LedState& ledState,
        int32_t led, int32_t modifier, bool reset) {
    if (ledState.avail) {
        bool desiredState = (mMetaState & modifier) != 0;
        if (reset || ledState.on != desiredState) {
            getEventHub()->setLedState(getDeviceId(), led, desiredState);
            ledState.on = desiredState;
        }
    }
}


// --- CursorInputMapper ---

CursorInputMapper::CursorInputMapper(InputDevice* device) :
        InputMapper(device) {
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

    if (mCursorScrollAccumulator.haveRelativeVWheel()) {
        info->addMotionRange(AMOTION_EVENT_AXIS_VSCROLL, mSource, -1.0f, 1.0f, 0.0f, 0.0f);
    }
    if (mCursorScrollAccumulator.haveRelativeHWheel()) {
        info->addMotionRange(AMOTION_EVENT_AXIS_HSCROLL, mSource, -1.0f, 1.0f, 0.0f, 0.0f);
    }
}

void CursorInputMapper::dump(String8& dump) {
    dump.append(INDENT2 "Cursor Input Mapper:\n");
    dumpParameters(dump);
    dump.appendFormat(INDENT3 "XScale: %0.3f\n", mXScale);
    dump.appendFormat(INDENT3 "YScale: %0.3f\n", mYScale);
    dump.appendFormat(INDENT3 "XPrecision: %0.3f\n", mXPrecision);
    dump.appendFormat(INDENT3 "YPrecision: %0.3f\n", mYPrecision);
    dump.appendFormat(INDENT3 "HaveVWheel: %s\n",
            toString(mCursorScrollAccumulator.haveRelativeVWheel()));
    dump.appendFormat(INDENT3 "HaveHWheel: %s\n",
            toString(mCursorScrollAccumulator.haveRelativeHWheel()));
    dump.appendFormat(INDENT3 "VWheelScale: %0.3f\n", mVWheelScale);
    dump.appendFormat(INDENT3 "HWheelScale: %0.3f\n", mHWheelScale);
    dump.appendFormat(INDENT3 "Orientation: %d\n", mOrientation);
    dump.appendFormat(INDENT3 "ButtonState: 0x%08x\n", mButtonState);
    dump.appendFormat(INDENT3 "Down: %s\n", toString(isPointerDown(mButtonState)));
    dump.appendFormat(INDENT3 "DownTime: %lld\n", mDownTime);
}

void CursorInputMapper::configure(nsecs_t when,
        const InputReaderConfiguration* config, uint32_t changes) {
    InputMapper::configure(when, config, changes);

    if (!changes) { // first time only
        mCursorScrollAccumulator.configure(getDevice());

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
    }

    if (!changes || (changes & InputReaderConfiguration::CHANGE_POINTER_SPEED)) {
        mPointerVelocityControl.setParameters(config->pointerVelocityControlParameters);
        mWheelXVelocityControl.setParameters(config->wheelVelocityControlParameters);
        mWheelYVelocityControl.setParameters(config->wheelVelocityControlParameters);
    }

    if (!changes || (changes & InputReaderConfiguration::CHANGE_DISPLAY_INFO)) {
        if (mParameters.orientationAware && mParameters.associatedDisplayId >= 0) {
            if (!config->getDisplayInfo(mParameters.associatedDisplayId,
                        false /*external*/, NULL, NULL, &mOrientation)) {
                mOrientation = DISPLAY_ORIENTATION_0;
            }
        } else {
            mOrientation = DISPLAY_ORIENTATION_0;
        }
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

    mParameters.associatedDisplayId = -1;
    if (mParameters.mode == Parameters::MODE_POINTER || mParameters.orientationAware) {
        mParameters.associatedDisplayId = 0;
    }
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

void CursorInputMapper::reset(nsecs_t when) {
    mButtonState = 0;
    mDownTime = 0;

    mPointerVelocityControl.reset();
    mWheelXVelocityControl.reset();
    mWheelYVelocityControl.reset();

    mCursorButtonAccumulator.reset(getDevice());
    mCursorMotionAccumulator.reset(getDevice());
    mCursorScrollAccumulator.reset(getDevice());

    InputMapper::reset(when);
}

void CursorInputMapper::process(const RawEvent* rawEvent) {
    mCursorButtonAccumulator.process(rawEvent);
    mCursorMotionAccumulator.process(rawEvent);
    mCursorScrollAccumulator.process(rawEvent);

    if (rawEvent->type == EV_SYN && rawEvent->scanCode == SYN_REPORT) {
        sync(rawEvent->when);
    }
}

void CursorInputMapper::sync(nsecs_t when) {
    int32_t lastButtonState = mButtonState;
    int32_t currentButtonState = mCursorButtonAccumulator.getButtonState();
    mButtonState = currentButtonState;

    bool wasDown = isPointerDown(lastButtonState);
    bool down = isPointerDown(currentButtonState);
    bool downChanged;
    if (!wasDown && down) {
        mDownTime = when;
        downChanged = true;
    } else if (wasDown && !down) {
        downChanged = true;
    } else {
        downChanged = false;
    }
    nsecs_t downTime = mDownTime;
    bool buttonsChanged = currentButtonState != lastButtonState;

    float deltaX = mCursorMotionAccumulator.getRelativeX() * mXScale;
    float deltaY = mCursorMotionAccumulator.getRelativeY() * mYScale;
    bool moved = deltaX != 0 || deltaY != 0;

    // Rotate delta according to orientation if needed.
    if (mParameters.orientationAware && mParameters.associatedDisplayId >= 0
            && (deltaX != 0.0f || deltaY != 0.0f)) {
        rotateDelta(mOrientation, &deltaX, &deltaY);
    }

    // Move the pointer.
    PointerProperties pointerProperties;
    pointerProperties.clear();
    pointerProperties.id = 0;
    pointerProperties.toolType = AMOTION_EVENT_TOOL_TYPE_MOUSE;

    PointerCoords pointerCoords;
    pointerCoords.clear();

    float vscroll = mCursorScrollAccumulator.getRelativeVWheel();
    float hscroll = mCursorScrollAccumulator.getRelativeHWheel();
    bool scrolled = vscroll != 0 || hscroll != 0;

    mWheelYVelocityControl.move(when, NULL, &vscroll);
    mWheelXVelocityControl.move(when, &hscroll, NULL);

    mPointerVelocityControl.move(when, &deltaX, &deltaY);

    if (mPointerController != NULL) {
        if (moved || scrolled || buttonsChanged) {
            mPointerController->setPresentation(
                    PointerControllerInterface::PRESENTATION_POINTER);

            if (moved) {
                mPointerController->move(deltaX, deltaY);
            }

            if (buttonsChanged) {
                mPointerController->setButtonState(currentButtonState);
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
    if (downChanged || moved || scrolled || buttonsChanged) {
        int32_t metaState = mContext->getGlobalMetaState();
        int32_t motionEventAction;
        if (downChanged) {
            motionEventAction = down ? AMOTION_EVENT_ACTION_DOWN : AMOTION_EVENT_ACTION_UP;
        } else if (down || mPointerController == NULL) {
            motionEventAction = AMOTION_EVENT_ACTION_MOVE;
        } else {
            motionEventAction = AMOTION_EVENT_ACTION_HOVER_MOVE;
        }

        NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                motionEventAction, 0, metaState, currentButtonState, 0,
                1, &pointerProperties, &pointerCoords, mXPrecision, mYPrecision, downTime);
        getListener()->notifyMotion(&args);

        // Send hover move after UP to tell the application that the mouse is hovering now.
        if (motionEventAction == AMOTION_EVENT_ACTION_UP
                && mPointerController != NULL) {
            NotifyMotionArgs hoverArgs(when, getDeviceId(), mSource, policyFlags,
                    AMOTION_EVENT_ACTION_HOVER_MOVE, 0,
                    metaState, currentButtonState, AMOTION_EVENT_EDGE_FLAG_NONE,
                    1, &pointerProperties, &pointerCoords, mXPrecision, mYPrecision, downTime);
            getListener()->notifyMotion(&hoverArgs);
        }

        // Send scroll events.
        if (scrolled) {
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_VSCROLL, vscroll);
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_HSCROLL, hscroll);

            NotifyMotionArgs scrollArgs(when, getDeviceId(), mSource, policyFlags,
                    AMOTION_EVENT_ACTION_SCROLL, 0, metaState, currentButtonState,
                    AMOTION_EVENT_EDGE_FLAG_NONE,
                    1, &pointerProperties, &pointerCoords, mXPrecision, mYPrecision, downTime);
            getListener()->notifyMotion(&scrollArgs);
        }
    }

    // Synthesize key up from buttons if needed.
    synthesizeButtonKeys(getContext(), AKEY_EVENT_ACTION_UP, when, getDeviceId(), mSource,
            policyFlags, lastButtonState, currentButtonState);

    mCursorMotionAccumulator.finishSync();
    mCursorScrollAccumulator.finishSync();
}

int32_t CursorInputMapper::getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
    if (scanCode >= BTN_MOUSE && scanCode < BTN_JOYSTICK) {
        return getEventHub()->getScanCodeState(getDeviceId(), scanCode);
    } else {
        return AKEY_STATE_UNKNOWN;
    }
}

void CursorInputMapper::fadePointer() {
    if (mPointerController != NULL) {
        mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
    }
}


// --- TouchInputMapper ---

TouchInputMapper::TouchInputMapper(InputDevice* device) :
        InputMapper(device),
        mSource(0), mDeviceMode(DEVICE_MODE_DISABLED),
        mSurfaceOrientation(-1), mSurfaceWidth(-1), mSurfaceHeight(-1) {
}

TouchInputMapper::~TouchInputMapper() {
}

uint32_t TouchInputMapper::getSources() {
    return mSource;
}

void TouchInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    if (mDeviceMode != DEVICE_MODE_DISABLED) {
        info->addMotionRange(mOrientedRanges.x);
        info->addMotionRange(mOrientedRanges.y);
        info->addMotionRange(mOrientedRanges.pressure);

        if (mOrientedRanges.haveSize) {
            info->addMotionRange(mOrientedRanges.size);
        }

        if (mOrientedRanges.haveTouchSize) {
            info->addMotionRange(mOrientedRanges.touchMajor);
            info->addMotionRange(mOrientedRanges.touchMinor);
        }

        if (mOrientedRanges.haveToolSize) {
            info->addMotionRange(mOrientedRanges.toolMajor);
            info->addMotionRange(mOrientedRanges.toolMinor);
        }

        if (mOrientedRanges.haveOrientation) {
            info->addMotionRange(mOrientedRanges.orientation);
        }

        if (mOrientedRanges.haveDistance) {
            info->addMotionRange(mOrientedRanges.distance);
        }

        if (mOrientedRanges.haveTilt) {
            info->addMotionRange(mOrientedRanges.tilt);
        }

        if (mCursorScrollAccumulator.haveRelativeVWheel()) {
            info->addMotionRange(AMOTION_EVENT_AXIS_VSCROLL, mSource, -1.0f, 1.0f, 0.0f, 0.0f);
        }
        if (mCursorScrollAccumulator.haveRelativeHWheel()) {
            info->addMotionRange(AMOTION_EVENT_AXIS_HSCROLL, mSource, -1.0f, 1.0f, 0.0f, 0.0f);
        }
    }
}

void TouchInputMapper::dump(String8& dump) {
    dump.append(INDENT2 "Touch Input Mapper:\n");
    dumpParameters(dump);
    dumpVirtualKeys(dump);
    dumpRawPointerAxes(dump);
    dumpCalibration(dump);
    dumpSurface(dump);

    dump.appendFormat(INDENT3 "Translation and Scaling Factors:\n");
    dump.appendFormat(INDENT4 "XScale: %0.3f\n", mXScale);
    dump.appendFormat(INDENT4 "YScale: %0.3f\n", mYScale);
    dump.appendFormat(INDENT4 "XPrecision: %0.3f\n", mXPrecision);
    dump.appendFormat(INDENT4 "YPrecision: %0.3f\n", mYPrecision);
    dump.appendFormat(INDENT4 "GeometricScale: %0.3f\n", mGeometricScale);
    dump.appendFormat(INDENT4 "PressureScale: %0.3f\n", mPressureScale);
    dump.appendFormat(INDENT4 "SizeScale: %0.3f\n", mSizeScale);
    dump.appendFormat(INDENT4 "OrientationCenter: %0.3f\n", mOrientationCenter);
    dump.appendFormat(INDENT4 "OrientationScale: %0.3f\n", mOrientationScale);
    dump.appendFormat(INDENT4 "DistanceScale: %0.3f\n", mDistanceScale);
    dump.appendFormat(INDENT4 "HaveTilt: %s\n", toString(mHaveTilt));
    dump.appendFormat(INDENT4 "TiltXCenter: %0.3f\n", mTiltXCenter);
    dump.appendFormat(INDENT4 "TiltXScale: %0.3f\n", mTiltXScale);
    dump.appendFormat(INDENT4 "TiltYCenter: %0.3f\n", mTiltYCenter);
    dump.appendFormat(INDENT4 "TiltYScale: %0.3f\n", mTiltYScale);

    dump.appendFormat(INDENT3 "Last Button State: 0x%08x\n", mLastButtonState);

    dump.appendFormat(INDENT3 "Last Raw Touch: pointerCount=%d\n",
            mLastRawPointerData.pointerCount);
    for (uint32_t i = 0; i < mLastRawPointerData.pointerCount; i++) {
        const RawPointerData::Pointer& pointer = mLastRawPointerData.pointers[i];
        dump.appendFormat(INDENT4 "[%d]: id=%d, x=%d, y=%d, pressure=%d, "
                "touchMajor=%d, touchMinor=%d, toolMajor=%d, toolMinor=%d, "
                "orientation=%d, tiltX=%d, tiltY=%d, distance=%d, "
                "toolType=%d, isHovering=%s\n", i,
                pointer.id, pointer.x, pointer.y, pointer.pressure,
                pointer.touchMajor, pointer.touchMinor,
                pointer.toolMajor, pointer.toolMinor,
                pointer.orientation, pointer.tiltX, pointer.tiltY, pointer.distance,
                pointer.toolType, toString(pointer.isHovering));
    }

    dump.appendFormat(INDENT3 "Last Cooked Touch: pointerCount=%d\n",
            mLastCookedPointerData.pointerCount);
    for (uint32_t i = 0; i < mLastCookedPointerData.pointerCount; i++) {
        const PointerProperties& pointerProperties = mLastCookedPointerData.pointerProperties[i];
        const PointerCoords& pointerCoords = mLastCookedPointerData.pointerCoords[i];
        dump.appendFormat(INDENT4 "[%d]: id=%d, x=%0.3f, y=%0.3f, pressure=%0.3f, "
                "touchMajor=%0.3f, touchMinor=%0.3f, toolMajor=%0.3f, toolMinor=%0.3f, "
                "orientation=%0.3f, tilt=%0.3f, distance=%0.3f, "
                "toolType=%d, isHovering=%s\n", i,
                pointerProperties.id,
                pointerCoords.getX(),
                pointerCoords.getY(),
                pointerCoords.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE),
                pointerCoords.getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR),
                pointerCoords.getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR),
                pointerCoords.getAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR),
                pointerCoords.getAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR),
                pointerCoords.getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION),
                pointerCoords.getAxisValue(AMOTION_EVENT_AXIS_TILT),
                pointerCoords.getAxisValue(AMOTION_EVENT_AXIS_DISTANCE),
                pointerProperties.toolType,
                toString(mLastCookedPointerData.isHovering(i)));
    }

    if (mDeviceMode == DEVICE_MODE_POINTER) {
        dump.appendFormat(INDENT3 "Pointer Gesture Detector:\n");
        dump.appendFormat(INDENT4 "XMovementScale: %0.3f\n",
                mPointerXMovementScale);
        dump.appendFormat(INDENT4 "YMovementScale: %0.3f\n",
                mPointerYMovementScale);
        dump.appendFormat(INDENT4 "XZoomScale: %0.3f\n",
                mPointerXZoomScale);
        dump.appendFormat(INDENT4 "YZoomScale: %0.3f\n",
                mPointerYZoomScale);
        dump.appendFormat(INDENT4 "MaxSwipeWidth: %f\n",
                mPointerGestureMaxSwipeWidth);
    }
}

void TouchInputMapper::configure(nsecs_t when,
        const InputReaderConfiguration* config, uint32_t changes) {
    InputMapper::configure(when, config, changes);

    mConfig = *config;

    if (!changes) { // first time only
        // Configure basic parameters.
        configureParameters();

        // Configure common accumulators.
        mCursorScrollAccumulator.configure(getDevice());
        mTouchButtonAccumulator.configure(getDevice());

        // Configure absolute axis information.
        configureRawPointerAxes();

        // Prepare input device calibration.
        parseCalibration();
        resolveCalibration();
    }

    if (!changes || (changes & InputReaderConfiguration::CHANGE_POINTER_SPEED)) {
        // Update pointer speed.
        mPointerVelocityControl.setParameters(mConfig.pointerVelocityControlParameters);
        mWheelXVelocityControl.setParameters(mConfig.wheelVelocityControlParameters);
        mWheelYVelocityControl.setParameters(mConfig.wheelVelocityControlParameters);
    }

    bool resetNeeded = false;
    if (!changes || (changes & (InputReaderConfiguration::CHANGE_DISPLAY_INFO
            | InputReaderConfiguration::CHANGE_POINTER_GESTURE_ENABLEMENT))) {
        // Configure device sources, surface dimensions, orientation and
        // scaling factors.
        configureSurface(when, &resetNeeded);
    }

    if (changes && resetNeeded) {
        // Send reset, unless this is the first time the device has been configured,
        // in which case the reader will call reset itself after all mappers are ready.
        getDevice()->notifyReset(when);
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

    mParameters.associatedDisplayId = -1;
    mParameters.associatedDisplayIsExternal = false;
    if (mParameters.orientationAware
            || mParameters.deviceType == Parameters::DEVICE_TYPE_TOUCH_SCREEN
            || mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER) {
        mParameters.associatedDisplayIsExternal =
                mParameters.deviceType == Parameters::DEVICE_TYPE_TOUCH_SCREEN
                        && getDevice()->isExternal();
        mParameters.associatedDisplayId = 0;
    }
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

    dump.appendFormat(INDENT4 "AssociatedDisplay: id=%d, isExternal=%s\n",
            mParameters.associatedDisplayId, toString(mParameters.associatedDisplayIsExternal));
    dump.appendFormat(INDENT4 "OrientationAware: %s\n",
            toString(mParameters.orientationAware));
}

void TouchInputMapper::configureRawPointerAxes() {
    mRawPointerAxes.clear();
}

void TouchInputMapper::dumpRawPointerAxes(String8& dump) {
    dump.append(INDENT3 "Raw Touch Axes:\n");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.x, "X");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.y, "Y");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.pressure, "Pressure");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.touchMajor, "TouchMajor");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.touchMinor, "TouchMinor");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.toolMajor, "ToolMajor");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.toolMinor, "ToolMinor");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.orientation, "Orientation");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.distance, "Distance");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.tiltX, "TiltX");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.tiltY, "TiltY");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.trackingId, "TrackingId");
    dumpRawAbsoluteAxisInfo(dump, mRawPointerAxes.slot, "Slot");
}

void TouchInputMapper::configureSurface(nsecs_t when, bool* outResetNeeded) {
    int32_t oldDeviceMode = mDeviceMode;

    // Determine device mode.
    if (mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER
            && mConfig.pointerGesturesEnabled) {
        mSource = AINPUT_SOURCE_MOUSE;
        mDeviceMode = DEVICE_MODE_POINTER;
    } else if (mParameters.deviceType == Parameters::DEVICE_TYPE_TOUCH_SCREEN
            && mParameters.associatedDisplayId >= 0) {
        mSource = AINPUT_SOURCE_TOUCHSCREEN;
        mDeviceMode = DEVICE_MODE_DIRECT;
    } else {
        mSource = AINPUT_SOURCE_TOUCHPAD;
        mDeviceMode = DEVICE_MODE_UNSCALED;
    }

    // Ensure we have valid X and Y axes.
    if (!mRawPointerAxes.x.valid || !mRawPointerAxes.y.valid) {
        LOGW(INDENT "Touch device '%s' did not report support for X or Y axis!  "
                "The device will be inoperable.", getDeviceName().string());
        mDeviceMode = DEVICE_MODE_DISABLED;
        return;
    }

    // Get associated display dimensions.
    if (mParameters.associatedDisplayId >= 0) {
        if (!mConfig.getDisplayInfo(mParameters.associatedDisplayId,
                mParameters.associatedDisplayIsExternal,
                &mAssociatedDisplayWidth, &mAssociatedDisplayHeight,
                &mAssociatedDisplayOrientation)) {
            LOGI(INDENT "Touch device '%s' could not query the properties of its associated "
                    "display %d.  The device will be inoperable until the display size "
                    "becomes available.",
                    getDeviceName().string(), mParameters.associatedDisplayId);
            mDeviceMode = DEVICE_MODE_DISABLED;
            return;
        }
    }

    // Configure dimensions.
    int32_t width, height, orientation;
    if (mDeviceMode == DEVICE_MODE_DIRECT || mDeviceMode == DEVICE_MODE_POINTER) {
        width = mAssociatedDisplayWidth;
        height = mAssociatedDisplayHeight;
        orientation = mParameters.orientationAware ?
                mAssociatedDisplayOrientation : DISPLAY_ORIENTATION_0;
    } else {
        width = mRawPointerAxes.x.maxValue - mRawPointerAxes.x.minValue + 1;
        height = mRawPointerAxes.y.maxValue - mRawPointerAxes.y.minValue + 1;
        orientation = DISPLAY_ORIENTATION_0;
    }

    // If moving between pointer modes, need to reset some state.
    bool deviceModeChanged;
    if (mDeviceMode != oldDeviceMode) {
        deviceModeChanged = true;

        if (mDeviceMode == DEVICE_MODE_POINTER) {
            if (mPointerController == NULL) {
                mPointerController = getPolicy()->obtainPointerController(getDeviceId());
            }
        } else {
            mPointerController.clear();
        }

        mOrientedRanges.clear();
    }

    bool orientationChanged = mSurfaceOrientation != orientation;
    if (orientationChanged) {
        mSurfaceOrientation = orientation;
    }

    bool sizeChanged = mSurfaceWidth != width || mSurfaceHeight != height;
    if (sizeChanged || deviceModeChanged) {
        LOGI("Device reconfigured: id=%d, name='%s', surface size is now %dx%d, mode is %d",
                getDeviceId(), getDeviceName().string(), width, height, mDeviceMode);

        mSurfaceWidth = width;
        mSurfaceHeight = height;

        // Configure X and Y factors.
        mXScale = float(width) / (mRawPointerAxes.x.maxValue - mRawPointerAxes.x.minValue + 1);
        mYScale = float(height) / (mRawPointerAxes.y.maxValue - mRawPointerAxes.y.minValue + 1);
        mXPrecision = 1.0f / mXScale;
        mYPrecision = 1.0f / mYScale;

        mOrientedRanges.x.axis = AMOTION_EVENT_AXIS_X;
        mOrientedRanges.x.source = mSource;
        mOrientedRanges.y.axis = AMOTION_EVENT_AXIS_Y;
        mOrientedRanges.y.source = mSource;

        configureVirtualKeys();

        // Scale factor for terms that are not oriented in a particular axis.
        // If the pixels are square then xScale == yScale otherwise we fake it
        // by choosing an average.
        mGeometricScale = avg(mXScale, mYScale);

        // Size of diagonal axis.
        float diagonalSize = hypotf(width, height);

        // Size factors.
        if (mCalibration.sizeCalibration != Calibration::SIZE_CALIBRATION_NONE) {
            if (mRawPointerAxes.touchMajor.valid
                    && mRawPointerAxes.touchMajor.maxValue != 0) {
                mSizeScale = 1.0f / mRawPointerAxes.touchMajor.maxValue;
            } else if (mRawPointerAxes.toolMajor.valid
                    && mRawPointerAxes.toolMajor.maxValue != 0) {
                mSizeScale = 1.0f / mRawPointerAxes.toolMajor.maxValue;
            } else {
                mSizeScale = 0.0f;
            }

            mOrientedRanges.haveTouchSize = true;
            mOrientedRanges.haveToolSize = true;
            mOrientedRanges.haveSize = true;

            mOrientedRanges.touchMajor.axis = AMOTION_EVENT_AXIS_TOUCH_MAJOR;
            mOrientedRanges.touchMajor.source = mSource;
            mOrientedRanges.touchMajor.min = 0;
            mOrientedRanges.touchMajor.max = diagonalSize;
            mOrientedRanges.touchMajor.flat = 0;
            mOrientedRanges.touchMajor.fuzz = 0;

            mOrientedRanges.touchMinor = mOrientedRanges.touchMajor;
            mOrientedRanges.touchMinor.axis = AMOTION_EVENT_AXIS_TOUCH_MINOR;

            mOrientedRanges.toolMajor.axis = AMOTION_EVENT_AXIS_TOOL_MAJOR;
            mOrientedRanges.toolMajor.source = mSource;
            mOrientedRanges.toolMajor.min = 0;
            mOrientedRanges.toolMajor.max = diagonalSize;
            mOrientedRanges.toolMajor.flat = 0;
            mOrientedRanges.toolMajor.fuzz = 0;

            mOrientedRanges.toolMinor = mOrientedRanges.toolMajor;
            mOrientedRanges.toolMinor.axis = AMOTION_EVENT_AXIS_TOOL_MINOR;

            mOrientedRanges.size.axis = AMOTION_EVENT_AXIS_SIZE;
            mOrientedRanges.size.source = mSource;
            mOrientedRanges.size.min = 0;
            mOrientedRanges.size.max = 1.0;
            mOrientedRanges.size.flat = 0;
            mOrientedRanges.size.fuzz = 0;
        } else {
            mSizeScale = 0.0f;
        }

        // Pressure factors.
        mPressureScale = 0;
        if (mCalibration.pressureCalibration == Calibration::PRESSURE_CALIBRATION_PHYSICAL
                || mCalibration.pressureCalibration
                        == Calibration::PRESSURE_CALIBRATION_AMPLITUDE) {
            if (mCalibration.havePressureScale) {
                mPressureScale = mCalibration.pressureScale;
            } else if (mRawPointerAxes.pressure.valid
                    && mRawPointerAxes.pressure.maxValue != 0) {
                mPressureScale = 1.0f / mRawPointerAxes.pressure.maxValue;
            }
        }

        mOrientedRanges.pressure.axis = AMOTION_EVENT_AXIS_PRESSURE;
        mOrientedRanges.pressure.source = mSource;
        mOrientedRanges.pressure.min = 0;
        mOrientedRanges.pressure.max = 1.0;
        mOrientedRanges.pressure.flat = 0;
        mOrientedRanges.pressure.fuzz = 0;

        // Tilt
        mTiltXCenter = 0;
        mTiltXScale = 0;
        mTiltYCenter = 0;
        mTiltYScale = 0;
        mHaveTilt = mRawPointerAxes.tiltX.valid && mRawPointerAxes.tiltY.valid;
        if (mHaveTilt) {
            mTiltXCenter = avg(mRawPointerAxes.tiltX.minValue,
                    mRawPointerAxes.tiltX.maxValue);
            mTiltYCenter = avg(mRawPointerAxes.tiltY.minValue,
                    mRawPointerAxes.tiltY.maxValue);
            mTiltXScale = M_PI / 180;
            mTiltYScale = M_PI / 180;

            mOrientedRanges.haveTilt = true;

            mOrientedRanges.tilt.axis = AMOTION_EVENT_AXIS_TILT;
            mOrientedRanges.tilt.source = mSource;
            mOrientedRanges.tilt.min = 0;
            mOrientedRanges.tilt.max = M_PI_2;
            mOrientedRanges.tilt.flat = 0;
            mOrientedRanges.tilt.fuzz = 0;
        }

        // Orientation
        mOrientationCenter = 0;
        mOrientationScale = 0;
        if (mHaveTilt) {
            mOrientedRanges.haveOrientation = true;

            mOrientedRanges.orientation.axis = AMOTION_EVENT_AXIS_ORIENTATION;
            mOrientedRanges.orientation.source = mSource;
            mOrientedRanges.orientation.min = -M_PI;
            mOrientedRanges.orientation.max = M_PI;
            mOrientedRanges.orientation.flat = 0;
            mOrientedRanges.orientation.fuzz = 0;
        } else if (mCalibration.orientationCalibration !=
                Calibration::ORIENTATION_CALIBRATION_NONE) {
            if (mCalibration.orientationCalibration
                    == Calibration::ORIENTATION_CALIBRATION_INTERPOLATED) {
                if (mRawPointerAxes.orientation.valid) {
                    mOrientationCenter = avg(mRawPointerAxes.orientation.minValue,
                            mRawPointerAxes.orientation.maxValue);
                    mOrientationScale = M_PI / (mRawPointerAxes.orientation.maxValue -
                            mRawPointerAxes.orientation.minValue);
                }
            }

            mOrientedRanges.haveOrientation = true;

            mOrientedRanges.orientation.axis = AMOTION_EVENT_AXIS_ORIENTATION;
            mOrientedRanges.orientation.source = mSource;
            mOrientedRanges.orientation.min = -M_PI_2;
            mOrientedRanges.orientation.max = M_PI_2;
            mOrientedRanges.orientation.flat = 0;
            mOrientedRanges.orientation.fuzz = 0;
        }

        // Distance
        mDistanceScale = 0;
        if (mCalibration.distanceCalibration != Calibration::DISTANCE_CALIBRATION_NONE) {
            if (mCalibration.distanceCalibration
                    == Calibration::DISTANCE_CALIBRATION_SCALED) {
                if (mCalibration.haveDistanceScale) {
                    mDistanceScale = mCalibration.distanceScale;
                } else {
                    mDistanceScale = 1.0f;
                }
            }

            mOrientedRanges.haveDistance = true;

            mOrientedRanges.distance.axis = AMOTION_EVENT_AXIS_DISTANCE;
            mOrientedRanges.distance.source = mSource;
            mOrientedRanges.distance.min =
                    mRawPointerAxes.distance.minValue * mDistanceScale;
            mOrientedRanges.distance.max =
                    mRawPointerAxes.distance.minValue * mDistanceScale;
            mOrientedRanges.distance.flat = 0;
            mOrientedRanges.distance.fuzz =
                    mRawPointerAxes.distance.fuzz * mDistanceScale;
        }
    }

    if (orientationChanged || sizeChanged || deviceModeChanged) {
        // Compute oriented surface dimensions, precision, scales and ranges.
        // Note that the maximum value reported is an inclusive maximum value so it is one
        // unit less than the total width or height of surface.
        switch (mSurfaceOrientation) {
        case DISPLAY_ORIENTATION_90:
        case DISPLAY_ORIENTATION_270:
            mOrientedSurfaceWidth = mSurfaceHeight;
            mOrientedSurfaceHeight = mSurfaceWidth;

            mOrientedXPrecision = mYPrecision;
            mOrientedYPrecision = mXPrecision;

            mOrientedRanges.x.min = 0;
            mOrientedRanges.x.max = (mRawPointerAxes.y.maxValue - mRawPointerAxes.y.minValue)
                    * mYScale;
            mOrientedRanges.x.flat = 0;
            mOrientedRanges.x.fuzz = mYScale;

            mOrientedRanges.y.min = 0;
            mOrientedRanges.y.max = (mRawPointerAxes.x.maxValue - mRawPointerAxes.x.minValue)
                    * mXScale;
            mOrientedRanges.y.flat = 0;
            mOrientedRanges.y.fuzz = mXScale;
            break;

        default:
            mOrientedSurfaceWidth = mSurfaceWidth;
            mOrientedSurfaceHeight = mSurfaceHeight;

            mOrientedXPrecision = mXPrecision;
            mOrientedYPrecision = mYPrecision;

            mOrientedRanges.x.min = 0;
            mOrientedRanges.x.max = (mRawPointerAxes.x.maxValue - mRawPointerAxes.x.minValue)
                    * mXScale;
            mOrientedRanges.x.flat = 0;
            mOrientedRanges.x.fuzz = mXScale;

            mOrientedRanges.y.min = 0;
            mOrientedRanges.y.max = (mRawPointerAxes.y.maxValue - mRawPointerAxes.y.minValue)
                    * mYScale;
            mOrientedRanges.y.flat = 0;
            mOrientedRanges.y.fuzz = mYScale;
            break;
        }

        // Compute pointer gesture detection parameters.
        if (mDeviceMode == DEVICE_MODE_POINTER) {
            int32_t rawWidth = mRawPointerAxes.x.maxValue - mRawPointerAxes.x.minValue + 1;
            int32_t rawHeight = mRawPointerAxes.y.maxValue - mRawPointerAxes.y.minValue + 1;
            float rawDiagonal = hypotf(rawWidth, rawHeight);
            float displayDiagonal = hypotf(mAssociatedDisplayWidth,
                    mAssociatedDisplayHeight);

            // Scale movements such that one whole swipe of the touch pad covers a
            // given area relative to the diagonal size of the display when no acceleration
            // is applied.
            // Assume that the touch pad has a square aspect ratio such that movements in
            // X and Y of the same number of raw units cover the same physical distance.
            mPointerXMovementScale = mConfig.pointerGestureMovementSpeedRatio
                    * displayDiagonal / rawDiagonal;
            mPointerYMovementScale = mPointerXMovementScale;

            // Scale zooms to cover a smaller range of the display than movements do.
            // This value determines the area around the pointer that is affected by freeform
            // pointer gestures.
            mPointerXZoomScale = mConfig.pointerGestureZoomSpeedRatio
                    * displayDiagonal / rawDiagonal;
            mPointerYZoomScale = mPointerXZoomScale;

            // Max width between pointers to detect a swipe gesture is more than some fraction
            // of the diagonal axis of the touch pad.  Touches that are wider than this are
            // translated into freeform gestures.
            mPointerGestureMaxSwipeWidth =
                    mConfig.pointerGestureSwipeMaxWidthRatio * rawDiagonal;
        }

        // Abort current pointer usages because the state has changed.
        abortPointerUsage(when, 0 /*policyFlags*/);

        // Inform the dispatcher about the changes.
        *outResetNeeded = true;
    }
}

void TouchInputMapper::dumpSurface(String8& dump) {
    dump.appendFormat(INDENT3 "SurfaceWidth: %dpx\n", mSurfaceWidth);
    dump.appendFormat(INDENT3 "SurfaceHeight: %dpx\n", mSurfaceHeight);
    dump.appendFormat(INDENT3 "SurfaceOrientation: %d\n", mSurfaceOrientation);
}

void TouchInputMapper::configureVirtualKeys() {
    Vector<VirtualKeyDefinition> virtualKeyDefinitions;
    getEventHub()->getVirtualKeyDefinitions(getDeviceId(), virtualKeyDefinitions);

    mVirtualKeys.clear();

    if (virtualKeyDefinitions.size() == 0) {
        return;
    }

    mVirtualKeys.setCapacity(virtualKeyDefinitions.size());

    int32_t touchScreenLeft = mRawPointerAxes.x.minValue;
    int32_t touchScreenTop = mRawPointerAxes.y.minValue;
    int32_t touchScreenWidth = mRawPointerAxes.x.maxValue - mRawPointerAxes.x.minValue + 1;
    int32_t touchScreenHeight = mRawPointerAxes.y.maxValue - mRawPointerAxes.y.minValue + 1;

    for (size_t i = 0; i < virtualKeyDefinitions.size(); i++) {
        const VirtualKeyDefinition& virtualKeyDefinition =
                virtualKeyDefinitions[i];

        mVirtualKeys.add();
        VirtualKey& virtualKey = mVirtualKeys.editTop();

        virtualKey.scanCode = virtualKeyDefinition.scanCode;
        int32_t keyCode;
        uint32_t flags;
        if (getEventHub()->mapKey(getDeviceId(), virtualKey.scanCode,
                & keyCode, & flags)) {
            LOGW(INDENT "VirtualKey %d: could not obtain key code, ignoring",
                    virtualKey.scanCode);
            mVirtualKeys.pop(); // drop the key
            continue;
        }

        virtualKey.keyCode = keyCode;
        virtualKey.flags = flags;

        // convert the key definition's display coordinates into touch coordinates for a hit box
        int32_t halfWidth = virtualKeyDefinition.width / 2;
        int32_t halfHeight = virtualKeyDefinition.height / 2;

        virtualKey.hitLeft = (virtualKeyDefinition.centerX - halfWidth)
                * touchScreenWidth / mSurfaceWidth + touchScreenLeft;
        virtualKey.hitRight= (virtualKeyDefinition.centerX + halfWidth)
                * touchScreenWidth / mSurfaceWidth + touchScreenLeft;
        virtualKey.hitTop = (virtualKeyDefinition.centerY - halfHeight)
                * touchScreenHeight / mSurfaceHeight + touchScreenTop;
        virtualKey.hitBottom = (virtualKeyDefinition.centerY + halfHeight)
                * touchScreenHeight / mSurfaceHeight + touchScreenTop;
    }
}

void TouchInputMapper::dumpVirtualKeys(String8& dump) {
    if (!mVirtualKeys.isEmpty()) {
        dump.append(INDENT3 "Virtual Keys:\n");

        for (size_t i = 0; i < mVirtualKeys.size(); i++) {
            const VirtualKey& virtualKey = mVirtualKeys.itemAt(i);
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

    // Size
    out.sizeCalibration = Calibration::SIZE_CALIBRATION_DEFAULT;
    String8 sizeCalibrationString;
    if (in.tryGetProperty(String8("touch.size.calibration"), sizeCalibrationString)) {
        if (sizeCalibrationString == "none") {
            out.sizeCalibration = Calibration::SIZE_CALIBRATION_NONE;
        } else if (sizeCalibrationString == "geometric") {
            out.sizeCalibration = Calibration::SIZE_CALIBRATION_GEOMETRIC;
        } else if (sizeCalibrationString == "diameter") {
            out.sizeCalibration = Calibration::SIZE_CALIBRATION_DIAMETER;
        } else if (sizeCalibrationString == "area") {
            out.sizeCalibration = Calibration::SIZE_CALIBRATION_AREA;
        } else if (sizeCalibrationString != "default") {
            LOGW("Invalid value for touch.size.calibration: '%s'",
                    sizeCalibrationString.string());
        }
    }

    out.haveSizeScale = in.tryGetProperty(String8("touch.size.scale"),
            out.sizeScale);
    out.haveSizeBias = in.tryGetProperty(String8("touch.size.bias"),
            out.sizeBias);
    out.haveSizeIsSummed = in.tryGetProperty(String8("touch.size.isSummed"),
            out.sizeIsSummed);

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

    out.havePressureScale = in.tryGetProperty(String8("touch.pressure.scale"),
            out.pressureScale);

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
    // Size
    if (mRawPointerAxes.touchMajor.valid || mRawPointerAxes.toolMajor.valid) {
        if (mCalibration.sizeCalibration == Calibration::SIZE_CALIBRATION_DEFAULT) {
            mCalibration.sizeCalibration = Calibration::SIZE_CALIBRATION_GEOMETRIC;
        }
    } else {
        mCalibration.sizeCalibration = Calibration::SIZE_CALIBRATION_NONE;
    }

    // Pressure
    if (mRawPointerAxes.pressure.valid) {
        if (mCalibration.pressureCalibration == Calibration::PRESSURE_CALIBRATION_DEFAULT) {
            mCalibration.pressureCalibration = Calibration::PRESSURE_CALIBRATION_PHYSICAL;
        }
    } else {
        mCalibration.pressureCalibration = Calibration::PRESSURE_CALIBRATION_NONE;
    }

    // Orientation
    if (mRawPointerAxes.orientation.valid) {
        if (mCalibration.orientationCalibration == Calibration::ORIENTATION_CALIBRATION_DEFAULT) {
            mCalibration.orientationCalibration = Calibration::ORIENTATION_CALIBRATION_INTERPOLATED;
        }
    } else {
        mCalibration.orientationCalibration = Calibration::ORIENTATION_CALIBRATION_NONE;
    }

    // Distance
    if (mRawPointerAxes.distance.valid) {
        if (mCalibration.distanceCalibration == Calibration::DISTANCE_CALIBRATION_DEFAULT) {
            mCalibration.distanceCalibration = Calibration::DISTANCE_CALIBRATION_SCALED;
        }
    } else {
        mCalibration.distanceCalibration = Calibration::DISTANCE_CALIBRATION_NONE;
    }
}

void TouchInputMapper::dumpCalibration(String8& dump) {
    dump.append(INDENT3 "Calibration:\n");

    // Size
    switch (mCalibration.sizeCalibration) {
    case Calibration::SIZE_CALIBRATION_NONE:
        dump.append(INDENT4 "touch.size.calibration: none\n");
        break;
    case Calibration::SIZE_CALIBRATION_GEOMETRIC:
        dump.append(INDENT4 "touch.size.calibration: geometric\n");
        break;
    case Calibration::SIZE_CALIBRATION_DIAMETER:
        dump.append(INDENT4 "touch.size.calibration: diameter\n");
        break;
    case Calibration::SIZE_CALIBRATION_AREA:
        dump.append(INDENT4 "touch.size.calibration: area\n");
        break;
    default:
        LOG_ASSERT(false);
    }

    if (mCalibration.haveSizeScale) {
        dump.appendFormat(INDENT4 "touch.size.scale: %0.3f\n",
                mCalibration.sizeScale);
    }

    if (mCalibration.haveSizeBias) {
        dump.appendFormat(INDENT4 "touch.size.bias: %0.3f\n",
                mCalibration.sizeBias);
    }

    if (mCalibration.haveSizeIsSummed) {
        dump.appendFormat(INDENT4 "touch.size.isSummed: %s\n",
                toString(mCalibration.sizeIsSummed));
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

    if (mCalibration.havePressureScale) {
        dump.appendFormat(INDENT4 "touch.pressure.scale: %0.3f\n",
                mCalibration.pressureScale);
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

void TouchInputMapper::reset(nsecs_t when) {
    mCursorButtonAccumulator.reset(getDevice());
    mCursorScrollAccumulator.reset(getDevice());
    mTouchButtonAccumulator.reset(getDevice());

    mPointerVelocityControl.reset();
    mWheelXVelocityControl.reset();
    mWheelYVelocityControl.reset();

    mCurrentRawPointerData.clear();
    mLastRawPointerData.clear();
    mCurrentCookedPointerData.clear();
    mLastCookedPointerData.clear();
    mCurrentButtonState = 0;
    mLastButtonState = 0;
    mCurrentRawVScroll = 0;
    mCurrentRawHScroll = 0;
    mCurrentFingerIdBits.clear();
    mLastFingerIdBits.clear();
    mCurrentStylusIdBits.clear();
    mLastStylusIdBits.clear();
    mCurrentMouseIdBits.clear();
    mLastMouseIdBits.clear();
    mPointerUsage = POINTER_USAGE_NONE;
    mSentHoverEnter = false;
    mDownTime = 0;

    mCurrentVirtualKey.down = false;

    mPointerGesture.reset();
    mPointerSimple.reset();

    if (mPointerController != NULL) {
        mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
        mPointerController->clearSpots();
    }

    InputMapper::reset(when);
}

void TouchInputMapper::process(const RawEvent* rawEvent) {
    mCursorButtonAccumulator.process(rawEvent);
    mCursorScrollAccumulator.process(rawEvent);
    mTouchButtonAccumulator.process(rawEvent);

    if (rawEvent->type == EV_SYN && rawEvent->scanCode == SYN_REPORT) {
        sync(rawEvent->when);
    }
}

void TouchInputMapper::sync(nsecs_t when) {
    // Sync button state.
    mCurrentButtonState = mTouchButtonAccumulator.getButtonState()
            | mCursorButtonAccumulator.getButtonState();

    // Sync scroll state.
    mCurrentRawVScroll = mCursorScrollAccumulator.getRelativeVWheel();
    mCurrentRawHScroll = mCursorScrollAccumulator.getRelativeHWheel();
    mCursorScrollAccumulator.finishSync();

    // Sync touch state.
    bool havePointerIds = true;
    mCurrentRawPointerData.clear();
    syncTouch(when, &havePointerIds);

#if DEBUG_RAW_EVENTS
    if (!havePointerIds) {
        LOGD("syncTouch: pointerCount %d -> %d, no pointer ids",
                mLastRawPointerData.pointerCount,
                mCurrentRawPointerData.pointerCount);
    } else {
        LOGD("syncTouch: pointerCount %d -> %d, touching ids 0x%08x -> 0x%08x, "
                "hovering ids 0x%08x -> 0x%08x",
                mLastRawPointerData.pointerCount,
                mCurrentRawPointerData.pointerCount,
                mLastRawPointerData.touchingIdBits.value,
                mCurrentRawPointerData.touchingIdBits.value,
                mLastRawPointerData.hoveringIdBits.value,
                mCurrentRawPointerData.hoveringIdBits.value);
    }
#endif

    // Reset state that we will compute below.
    mCurrentFingerIdBits.clear();
    mCurrentStylusIdBits.clear();
    mCurrentMouseIdBits.clear();
    mCurrentCookedPointerData.clear();

    if (mDeviceMode == DEVICE_MODE_DISABLED) {
        // Drop all input if the device is disabled.
        mCurrentRawPointerData.clear();
        mCurrentButtonState = 0;
    } else {
        // Preprocess pointer data.
        if (!havePointerIds) {
            assignPointerIds();
        }

        // Handle policy on initial down or hover events.
        uint32_t policyFlags = 0;
        if (mLastRawPointerData.pointerCount == 0 && mCurrentRawPointerData.pointerCount != 0) {
            if (mDeviceMode == DEVICE_MODE_DIRECT) {
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

        // Synthesize key down from raw buttons if needed.
        synthesizeButtonKeys(getContext(), AKEY_EVENT_ACTION_DOWN, when, getDeviceId(), mSource,
                policyFlags, mLastButtonState, mCurrentButtonState);

        // Consume raw off-screen touches before cooking pointer data.
        // If touches are consumed, subsequent code will not receive any pointer data.
        if (consumeRawTouches(when, policyFlags)) {
            mCurrentRawPointerData.clear();
        }

        // Cook pointer data.  This call populates the mCurrentCookedPointerData structure
        // with cooked pointer data that has the same ids and indices as the raw data.
        // The following code can use either the raw or cooked data, as needed.
        cookPointerData();

        // Dispatch the touches either directly or by translation through a pointer on screen.
        if (mPointerController != NULL) {
            for (BitSet32 idBits(mCurrentRawPointerData.touchingIdBits); !idBits.isEmpty(); ) {
                uint32_t id = idBits.clearFirstMarkedBit();
                const RawPointerData::Pointer& pointer = mCurrentRawPointerData.pointerForId(id);
                if (pointer.toolType == AMOTION_EVENT_TOOL_TYPE_STYLUS
                        || pointer.toolType == AMOTION_EVENT_TOOL_TYPE_ERASER) {
                    mCurrentStylusIdBits.markBit(id);
                } else if (pointer.toolType == AMOTION_EVENT_TOOL_TYPE_FINGER
                        || pointer.toolType == AMOTION_EVENT_TOOL_TYPE_UNKNOWN) {
                    mCurrentFingerIdBits.markBit(id);
                } else if (pointer.toolType == AMOTION_EVENT_TOOL_TYPE_MOUSE) {
                    mCurrentMouseIdBits.markBit(id);
                }
            }
            for (BitSet32 idBits(mCurrentRawPointerData.hoveringIdBits); !idBits.isEmpty(); ) {
                uint32_t id = idBits.clearFirstMarkedBit();
                const RawPointerData::Pointer& pointer = mCurrentRawPointerData.pointerForId(id);
                if (pointer.toolType == AMOTION_EVENT_TOOL_TYPE_STYLUS
                        || pointer.toolType == AMOTION_EVENT_TOOL_TYPE_ERASER) {
                    mCurrentStylusIdBits.markBit(id);
                }
            }

            // Stylus takes precedence over all tools, then mouse, then finger.
            PointerUsage pointerUsage = mPointerUsage;
            if (!mCurrentStylusIdBits.isEmpty()) {
                mCurrentMouseIdBits.clear();
                mCurrentFingerIdBits.clear();
                pointerUsage = POINTER_USAGE_STYLUS;
            } else if (!mCurrentMouseIdBits.isEmpty()) {
                mCurrentFingerIdBits.clear();
                pointerUsage = POINTER_USAGE_MOUSE;
            } else if (!mCurrentFingerIdBits.isEmpty() || isPointerDown(mCurrentButtonState)) {
                pointerUsage = POINTER_USAGE_GESTURES;
            }

            dispatchPointerUsage(when, policyFlags, pointerUsage);
        } else {
            dispatchHoverExit(when, policyFlags);
            dispatchTouches(when, policyFlags);
            dispatchHoverEnterAndMove(when, policyFlags);
        }

        // Synthesize key up from raw buttons if needed.
        synthesizeButtonKeys(getContext(), AKEY_EVENT_ACTION_UP, when, getDeviceId(), mSource,
                policyFlags, mLastButtonState, mCurrentButtonState);
    }

    // Copy current touch to last touch in preparation for the next cycle.
    mLastRawPointerData.copyFrom(mCurrentRawPointerData);
    mLastCookedPointerData.copyFrom(mCurrentCookedPointerData);
    mLastButtonState = mCurrentButtonState;
    mLastFingerIdBits = mCurrentFingerIdBits;
    mLastStylusIdBits = mCurrentStylusIdBits;
    mLastMouseIdBits = mCurrentMouseIdBits;

    // Clear some transient state.
    mCurrentRawVScroll = 0;
    mCurrentRawHScroll = 0;
}

void TouchInputMapper::timeoutExpired(nsecs_t when) {
    if (mPointerController != NULL) {
        if (mPointerUsage == POINTER_USAGE_GESTURES) {
            dispatchPointerGestures(when, 0 /*policyFlags*/, true /*isTimeout*/);
        }
    }
}

bool TouchInputMapper::consumeRawTouches(nsecs_t when, uint32_t policyFlags) {
    // Check for release of a virtual key.
    if (mCurrentVirtualKey.down) {
        if (mCurrentRawPointerData.touchingIdBits.isEmpty()) {
            // Pointer went up while virtual key was down.
            mCurrentVirtualKey.down = false;
            if (!mCurrentVirtualKey.ignored) {
#if DEBUG_VIRTUAL_KEYS
                LOGD("VirtualKeys: Generating key up: keyCode=%d, scanCode=%d",
                        mCurrentVirtualKey.keyCode, mCurrentVirtualKey.scanCode);
#endif
                dispatchVirtualKey(when, policyFlags,
                        AKEY_EVENT_ACTION_UP,
                        AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY);
            }
            return true;
        }

        if (mCurrentRawPointerData.touchingIdBits.count() == 1) {
            uint32_t id = mCurrentRawPointerData.touchingIdBits.firstMarkedBit();
            const RawPointerData::Pointer& pointer = mCurrentRawPointerData.pointerForId(id);
            const VirtualKey* virtualKey = findVirtualKeyHit(pointer.x, pointer.y);
            if (virtualKey && virtualKey->keyCode == mCurrentVirtualKey.keyCode) {
                // Pointer is still within the space of the virtual key.
                return true;
            }
        }

        // Pointer left virtual key area or another pointer also went down.
        // Send key cancellation but do not consume the touch yet.
        // This is useful when the user swipes through from the virtual key area
        // into the main display surface.
        mCurrentVirtualKey.down = false;
        if (!mCurrentVirtualKey.ignored) {
#if DEBUG_VIRTUAL_KEYS
            LOGD("VirtualKeys: Canceling key: keyCode=%d, scanCode=%d",
                    mCurrentVirtualKey.keyCode, mCurrentVirtualKey.scanCode);
#endif
            dispatchVirtualKey(when, policyFlags,
                    AKEY_EVENT_ACTION_UP,
                    AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY
                            | AKEY_EVENT_FLAG_CANCELED);
        }
    }

    if (mLastRawPointerData.touchingIdBits.isEmpty()
            && !mCurrentRawPointerData.touchingIdBits.isEmpty()) {
        // Pointer just went down.  Check for virtual key press or off-screen touches.
        uint32_t id = mCurrentRawPointerData.touchingIdBits.firstMarkedBit();
        const RawPointerData::Pointer& pointer = mCurrentRawPointerData.pointerForId(id);
        if (!isPointInsideSurface(pointer.x, pointer.y)) {
            // If exactly one pointer went down, check for virtual key hit.
            // Otherwise we will drop the entire stroke.
            if (mCurrentRawPointerData.touchingIdBits.count() == 1) {
                const VirtualKey* virtualKey = findVirtualKeyHit(pointer.x, pointer.y);
                if (virtualKey) {
                    mCurrentVirtualKey.down = true;
                    mCurrentVirtualKey.downTime = when;
                    mCurrentVirtualKey.keyCode = virtualKey->keyCode;
                    mCurrentVirtualKey.scanCode = virtualKey->scanCode;
                    mCurrentVirtualKey.ignored = mContext->shouldDropVirtualKey(
                            when, getDevice(), virtualKey->keyCode, virtualKey->scanCode);

                    if (!mCurrentVirtualKey.ignored) {
#if DEBUG_VIRTUAL_KEYS
                        LOGD("VirtualKeys: Generating key down: keyCode=%d, scanCode=%d",
                                mCurrentVirtualKey.keyCode,
                                mCurrentVirtualKey.scanCode);
#endif
                        dispatchVirtualKey(when, policyFlags,
                                AKEY_EVENT_ACTION_DOWN,
                                AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY);
                    }
                }
            }
            return true;
        }
    }

    // Disable all virtual key touches that happen within a short time interval of the
    // most recent touch within the screen area.  The idea is to filter out stray
    // virtual key presses when interacting with the touch screen.
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
    if (mConfig.virtualKeyQuietTime > 0 && !mCurrentRawPointerData.touchingIdBits.isEmpty()) {
        mContext->disableVirtualKeysUntil(when + mConfig.virtualKeyQuietTime);
    }
    return false;
}

void TouchInputMapper::dispatchVirtualKey(nsecs_t when, uint32_t policyFlags,
        int32_t keyEventAction, int32_t keyEventFlags) {
    int32_t keyCode = mCurrentVirtualKey.keyCode;
    int32_t scanCode = mCurrentVirtualKey.scanCode;
    nsecs_t downTime = mCurrentVirtualKey.downTime;
    int32_t metaState = mContext->getGlobalMetaState();
    policyFlags |= POLICY_FLAG_VIRTUAL;

    NotifyKeyArgs args(when, getDeviceId(), AINPUT_SOURCE_KEYBOARD, policyFlags,
            keyEventAction, keyEventFlags, keyCode, scanCode, metaState, downTime);
    getListener()->notifyKey(&args);
}

void TouchInputMapper::dispatchTouches(nsecs_t when, uint32_t policyFlags) {
    BitSet32 currentIdBits = mCurrentCookedPointerData.touchingIdBits;
    BitSet32 lastIdBits = mLastCookedPointerData.touchingIdBits;
    int32_t metaState = getContext()->getGlobalMetaState();
    int32_t buttonState = mCurrentButtonState;

    if (currentIdBits == lastIdBits) {
        if (!currentIdBits.isEmpty()) {
            // No pointer id changes so this is a move event.
            // The listener takes care of batching moves so we don't have to deal with that here.
            dispatchMotion(when, policyFlags, mSource,
                    AMOTION_EVENT_ACTION_MOVE, 0, metaState, buttonState,
                    AMOTION_EVENT_EDGE_FLAG_NONE,
                    mCurrentCookedPointerData.pointerProperties,
                    mCurrentCookedPointerData.pointerCoords,
                    mCurrentCookedPointerData.idToIndex,
                    currentIdBits, -1,
                    mOrientedXPrecision, mOrientedYPrecision, mDownTime);
        }
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
                mCurrentCookedPointerData.pointerProperties,
                mCurrentCookedPointerData.pointerCoords,
                mCurrentCookedPointerData.idToIndex,
                mLastCookedPointerData.pointerProperties,
                mLastCookedPointerData.pointerCoords,
                mLastCookedPointerData.idToIndex,
                moveIdBits);
        if (buttonState != mLastButtonState) {
            moveNeeded = true;
        }

        // Dispatch pointer up events.
        while (!upIdBits.isEmpty()) {
            uint32_t upId = upIdBits.clearFirstMarkedBit();

            dispatchMotion(when, policyFlags, mSource,
                    AMOTION_EVENT_ACTION_POINTER_UP, 0, metaState, buttonState, 0,
                    mLastCookedPointerData.pointerProperties,
                    mLastCookedPointerData.pointerCoords,
                    mLastCookedPointerData.idToIndex,
                    dispatchedIdBits, upId,
                    mOrientedXPrecision, mOrientedYPrecision, mDownTime);
            dispatchedIdBits.clearBit(upId);
        }

        // Dispatch move events if any of the remaining pointers moved from their old locations.
        // Although applications receive new locations as part of individual pointer up
        // events, they do not generally handle them except when presented in a move event.
        if (moveNeeded) {
            LOG_ASSERT(moveIdBits.value == dispatchedIdBits.value);
            dispatchMotion(when, policyFlags, mSource,
                    AMOTION_EVENT_ACTION_MOVE, 0, metaState, buttonState, 0,
                    mCurrentCookedPointerData.pointerProperties,
                    mCurrentCookedPointerData.pointerCoords,
                    mCurrentCookedPointerData.idToIndex,
                    dispatchedIdBits, -1,
                    mOrientedXPrecision, mOrientedYPrecision, mDownTime);
        }

        // Dispatch pointer down events using the new pointer locations.
        while (!downIdBits.isEmpty()) {
            uint32_t downId = downIdBits.clearFirstMarkedBit();
            dispatchedIdBits.markBit(downId);

            if (dispatchedIdBits.count() == 1) {
                // First pointer is going down.  Set down time.
                mDownTime = when;
            }

            dispatchMotion(when, policyFlags, mSource,
                    AMOTION_EVENT_ACTION_POINTER_DOWN, 0, metaState, buttonState, 0,
                    mCurrentCookedPointerData.pointerProperties,
                    mCurrentCookedPointerData.pointerCoords,
                    mCurrentCookedPointerData.idToIndex,
                    dispatchedIdBits, downId,
                    mOrientedXPrecision, mOrientedYPrecision, mDownTime);
        }
    }
}

void TouchInputMapper::dispatchHoverExit(nsecs_t when, uint32_t policyFlags) {
    if (mSentHoverEnter &&
            (mCurrentCookedPointerData.hoveringIdBits.isEmpty()
                    || !mCurrentCookedPointerData.touchingIdBits.isEmpty())) {
        int32_t metaState = getContext()->getGlobalMetaState();
        dispatchMotion(when, policyFlags, mSource,
                AMOTION_EVENT_ACTION_HOVER_EXIT, 0, metaState, mLastButtonState, 0,
                mLastCookedPointerData.pointerProperties,
                mLastCookedPointerData.pointerCoords,
                mLastCookedPointerData.idToIndex,
                mLastCookedPointerData.hoveringIdBits, -1,
                mOrientedXPrecision, mOrientedYPrecision, mDownTime);
        mSentHoverEnter = false;
    }
}

void TouchInputMapper::dispatchHoverEnterAndMove(nsecs_t when, uint32_t policyFlags) {
    if (mCurrentCookedPointerData.touchingIdBits.isEmpty()
            && !mCurrentCookedPointerData.hoveringIdBits.isEmpty()) {
        int32_t metaState = getContext()->getGlobalMetaState();
        if (!mSentHoverEnter) {
            dispatchMotion(when, policyFlags, mSource,
                    AMOTION_EVENT_ACTION_HOVER_ENTER, 0, metaState, mCurrentButtonState, 0,
                    mCurrentCookedPointerData.pointerProperties,
                    mCurrentCookedPointerData.pointerCoords,
                    mCurrentCookedPointerData.idToIndex,
                    mCurrentCookedPointerData.hoveringIdBits, -1,
                    mOrientedXPrecision, mOrientedYPrecision, mDownTime);
            mSentHoverEnter = true;
        }

        dispatchMotion(when, policyFlags, mSource,
                AMOTION_EVENT_ACTION_HOVER_MOVE, 0, metaState, mCurrentButtonState, 0,
                mCurrentCookedPointerData.pointerProperties,
                mCurrentCookedPointerData.pointerCoords,
                mCurrentCookedPointerData.idToIndex,
                mCurrentCookedPointerData.hoveringIdBits, -1,
                mOrientedXPrecision, mOrientedYPrecision, mDownTime);
    }
}

void TouchInputMapper::cookPointerData() {
    uint32_t currentPointerCount = mCurrentRawPointerData.pointerCount;

    mCurrentCookedPointerData.clear();
    mCurrentCookedPointerData.pointerCount = currentPointerCount;
    mCurrentCookedPointerData.hoveringIdBits = mCurrentRawPointerData.hoveringIdBits;
    mCurrentCookedPointerData.touchingIdBits = mCurrentRawPointerData.touchingIdBits;

    // Walk through the the active pointers and map device coordinates onto
    // surface coordinates and adjust for display orientation.
    for (uint32_t i = 0; i < currentPointerCount; i++) {
        const RawPointerData::Pointer& in = mCurrentRawPointerData.pointers[i];

        // Size
        float touchMajor, touchMinor, toolMajor, toolMinor, size;
        switch (mCalibration.sizeCalibration) {
        case Calibration::SIZE_CALIBRATION_GEOMETRIC:
        case Calibration::SIZE_CALIBRATION_DIAMETER:
        case Calibration::SIZE_CALIBRATION_AREA:
            if (mRawPointerAxes.touchMajor.valid && mRawPointerAxes.toolMajor.valid) {
                touchMajor = in.touchMajor;
                touchMinor = mRawPointerAxes.touchMinor.valid ? in.touchMinor : in.touchMajor;
                toolMajor = in.toolMajor;
                toolMinor = mRawPointerAxes.toolMinor.valid ? in.toolMinor : in.toolMajor;
                size = mRawPointerAxes.touchMinor.valid
                        ? avg(in.touchMajor, in.touchMinor) : in.touchMajor;
            } else if (mRawPointerAxes.touchMajor.valid) {
                toolMajor = touchMajor = in.touchMajor;
                toolMinor = touchMinor = mRawPointerAxes.touchMinor.valid
                        ? in.touchMinor : in.touchMajor;
                size = mRawPointerAxes.touchMinor.valid
                        ? avg(in.touchMajor, in.touchMinor) : in.touchMajor;
            } else if (mRawPointerAxes.toolMajor.valid) {
                touchMajor = toolMajor = in.toolMajor;
                touchMinor = toolMinor = mRawPointerAxes.toolMinor.valid
                        ? in.toolMinor : in.toolMajor;
                size = mRawPointerAxes.toolMinor.valid
                        ? avg(in.toolMajor, in.toolMinor) : in.toolMajor;
            } else {
                LOG_ASSERT(false, "No touch or tool axes.  "
                        "Size calibration should have been resolved to NONE.");
                touchMajor = 0;
                touchMinor = 0;
                toolMajor = 0;
                toolMinor = 0;
                size = 0;
            }

            if (mCalibration.haveSizeIsSummed && mCalibration.sizeIsSummed) {
                uint32_t touchingCount = mCurrentRawPointerData.touchingIdBits.count();
                if (touchingCount > 1) {
                    touchMajor /= touchingCount;
                    touchMinor /= touchingCount;
                    toolMajor /= touchingCount;
                    toolMinor /= touchingCount;
                    size /= touchingCount;
                }
            }

            if (mCalibration.sizeCalibration == Calibration::SIZE_CALIBRATION_GEOMETRIC) {
                touchMajor *= mGeometricScale;
                touchMinor *= mGeometricScale;
                toolMajor *= mGeometricScale;
                toolMinor *= mGeometricScale;
            } else if (mCalibration.sizeCalibration == Calibration::SIZE_CALIBRATION_AREA) {
                touchMajor = touchMajor > 0 ? sqrtf(touchMajor) : 0;
                touchMinor = touchMajor;
                toolMajor = toolMajor > 0 ? sqrtf(toolMajor) : 0;
                toolMinor = toolMajor;
            } else if (mCalibration.sizeCalibration == Calibration::SIZE_CALIBRATION_DIAMETER) {
                touchMinor = touchMajor;
                toolMinor = toolMajor;
            }

            mCalibration.applySizeScaleAndBias(&touchMajor);
            mCalibration.applySizeScaleAndBias(&touchMinor);
            mCalibration.applySizeScaleAndBias(&toolMajor);
            mCalibration.applySizeScaleAndBias(&toolMinor);
            size *= mSizeScale;
            break;
        default:
            touchMajor = 0;
            touchMinor = 0;
            toolMajor = 0;
            toolMinor = 0;
            size = 0;
            break;
        }

        // Pressure
        float pressure;
        switch (mCalibration.pressureCalibration) {
        case Calibration::PRESSURE_CALIBRATION_PHYSICAL:
        case Calibration::PRESSURE_CALIBRATION_AMPLITUDE:
            pressure = in.pressure * mPressureScale;
            break;
        default:
            pressure = in.isHovering ? 0 : 1;
            break;
        }

        // Tilt and Orientation
        float tilt;
        float orientation;
        if (mHaveTilt) {
            float tiltXAngle = (in.tiltX - mTiltXCenter) * mTiltXScale;
            float tiltYAngle = (in.tiltY - mTiltYCenter) * mTiltYScale;
            orientation = atan2f(-sinf(tiltXAngle), sinf(tiltYAngle));
            tilt = acosf(cosf(tiltXAngle) * cosf(tiltYAngle));
        } else {
            tilt = 0;

            switch (mCalibration.orientationCalibration) {
            case Calibration::ORIENTATION_CALIBRATION_INTERPOLATED:
                orientation = (in.orientation - mOrientationCenter) * mOrientationScale;
                break;
            case Calibration::ORIENTATION_CALIBRATION_VECTOR: {
                int32_t c1 = signExtendNybble((in.orientation & 0xf0) >> 4);
                int32_t c2 = signExtendNybble(in.orientation & 0x0f);
                if (c1 != 0 || c2 != 0) {
                    orientation = atan2f(c1, c2) * 0.5f;
                    float confidence = hypotf(c1, c2);
                    float scale = 1.0f + confidence / 16.0f;
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
        }

        // Distance
        float distance;
        switch (mCalibration.distanceCalibration) {
        case Calibration::DISTANCE_CALIBRATION_SCALED:
            distance = in.distance * mDistanceScale;
            break;
        default:
            distance = 0;
        }

        // X and Y
        // Adjust coords for surface orientation.
        float x, y;
        switch (mSurfaceOrientation) {
        case DISPLAY_ORIENTATION_90:
            x = float(in.y - mRawPointerAxes.y.minValue) * mYScale;
            y = float(mRawPointerAxes.x.maxValue - in.x) * mXScale;
            orientation -= M_PI_2;
            if (orientation < - M_PI_2) {
                orientation += M_PI;
            }
            break;
        case DISPLAY_ORIENTATION_180:
            x = float(mRawPointerAxes.x.maxValue - in.x) * mXScale;
            y = float(mRawPointerAxes.y.maxValue - in.y) * mYScale;
            break;
        case DISPLAY_ORIENTATION_270:
            x = float(mRawPointerAxes.y.maxValue - in.y) * mYScale;
            y = float(in.x - mRawPointerAxes.x.minValue) * mXScale;
            orientation += M_PI_2;
            if (orientation > M_PI_2) {
                orientation -= M_PI;
            }
            break;
        default:
            x = float(in.x - mRawPointerAxes.x.minValue) * mXScale;
            y = float(in.y - mRawPointerAxes.y.minValue) * mYScale;
            break;
        }

        // Write output coords.
        PointerCoords& out = mCurrentCookedPointerData.pointerCoords[i];
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
        out.setAxisValue(AMOTION_EVENT_AXIS_TILT, tilt);
        out.setAxisValue(AMOTION_EVENT_AXIS_DISTANCE, distance);

        // Write output properties.
        PointerProperties& properties = mCurrentCookedPointerData.pointerProperties[i];
        uint32_t id = in.id;
        properties.clear();
        properties.id = id;
        properties.toolType = in.toolType;

        // Write id index.
        mCurrentCookedPointerData.idToIndex[id] = i;
    }
}

void TouchInputMapper::dispatchPointerUsage(nsecs_t when, uint32_t policyFlags,
        PointerUsage pointerUsage) {
    if (pointerUsage != mPointerUsage) {
        abortPointerUsage(when, policyFlags);
        mPointerUsage = pointerUsage;
    }

    switch (mPointerUsage) {
    case POINTER_USAGE_GESTURES:
        dispatchPointerGestures(when, policyFlags, false /*isTimeout*/);
        break;
    case POINTER_USAGE_STYLUS:
        dispatchPointerStylus(when, policyFlags);
        break;
    case POINTER_USAGE_MOUSE:
        dispatchPointerMouse(when, policyFlags);
        break;
    default:
        break;
    }
}

void TouchInputMapper::abortPointerUsage(nsecs_t when, uint32_t policyFlags) {
    switch (mPointerUsage) {
    case POINTER_USAGE_GESTURES:
        abortPointerGestures(when, policyFlags);
        break;
    case POINTER_USAGE_STYLUS:
        abortPointerStylus(when, policyFlags);
        break;
    case POINTER_USAGE_MOUSE:
        abortPointerMouse(when, policyFlags);
        break;
    default:
        break;
    }

    mPointerUsage = POINTER_USAGE_NONE;
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
    int32_t buttonState = mCurrentButtonState;

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
        if (buttonState != mLastButtonState) {
            moveNeeded = true;
        }
    }

    // Send motion events for all pointers that went up or were canceled.
    BitSet32 dispatchedGestureIdBits(mPointerGesture.lastGestureIdBits);
    if (!dispatchedGestureIdBits.isEmpty()) {
        if (cancelPreviousGesture) {
            dispatchMotion(when, policyFlags, mSource,
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
                uint32_t id = upGestureIdBits.clearFirstMarkedBit();

                dispatchMotion(when, policyFlags, mSource,
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
        dispatchMotion(when, policyFlags, mSource,
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
            uint32_t id = downGestureIdBits.clearFirstMarkedBit();
            dispatchedGestureIdBits.markBit(id);

            if (dispatchedGestureIdBits.count() == 1) {
                mPointerGesture.downTime = when;
            }

            dispatchMotion(when, policyFlags, mSource,
                    AMOTION_EVENT_ACTION_POINTER_DOWN, 0, metaState, buttonState, 0,
                    mPointerGesture.currentGestureProperties,
                    mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                    dispatchedGestureIdBits, id,
                    0, 0, mPointerGesture.downTime);
        }
    }

    // Send motion events for hover.
    if (mPointerGesture.currentGestureMode == PointerGesture::HOVER) {
        dispatchMotion(when, policyFlags, mSource,
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
        pointerProperties.toolType = AMOTION_EVENT_TOOL_TYPE_FINGER;

        PointerCoords pointerCoords;
        pointerCoords.clear();
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_X, x);
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, y);

        NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_HOVER_MOVE, 0,
                metaState, buttonState, AMOTION_EVENT_EDGE_FLAG_NONE,
                1, &pointerProperties, &pointerCoords, 0, 0, mPointerGesture.downTime);
        getListener()->notifyMotion(&args);
    }

    // Update state.
    mPointerGesture.lastGestureMode = mPointerGesture.currentGestureMode;
    if (!down) {
        mPointerGesture.lastGestureIdBits.clear();
    } else {
        mPointerGesture.lastGestureIdBits = mPointerGesture.currentGestureIdBits;
        for (BitSet32 idBits(mPointerGesture.currentGestureIdBits); !idBits.isEmpty(); ) {
            uint32_t id = idBits.clearFirstMarkedBit();
            uint32_t index = mPointerGesture.currentGestureIdToIndex[id];
            mPointerGesture.lastGestureProperties[index].copyFrom(
                    mPointerGesture.currentGestureProperties[index]);
            mPointerGesture.lastGestureCoords[index].copyFrom(
                    mPointerGesture.currentGestureCoords[index]);
            mPointerGesture.lastGestureIdToIndex[id] = index;
        }
    }
}

void TouchInputMapper::abortPointerGestures(nsecs_t when, uint32_t policyFlags) {
    // Cancel previously dispatches pointers.
    if (!mPointerGesture.lastGestureIdBits.isEmpty()) {
        int32_t metaState = getContext()->getGlobalMetaState();
        int32_t buttonState = mCurrentButtonState;
        dispatchMotion(when, policyFlags, mSource,
                AMOTION_EVENT_ACTION_CANCEL, 0, metaState, buttonState,
                AMOTION_EVENT_EDGE_FLAG_NONE,
                mPointerGesture.lastGestureProperties,
                mPointerGesture.lastGestureCoords, mPointerGesture.lastGestureIdToIndex,
                mPointerGesture.lastGestureIdBits, -1,
                0, 0, mPointerGesture.downTime);
    }

    // Reset the current pointer gesture.
    mPointerGesture.reset();
    mPointerVelocityControl.reset();

    // Remove any current spots.
    if (mPointerController != NULL) {
        mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
        mPointerController->clearSpots();
    }
}

bool TouchInputMapper::preparePointerGestures(nsecs_t when,
        bool* outCancelPreviousGesture, bool* outFinishPreviousGesture, bool isTimeout) {
    *outCancelPreviousGesture = false;
    *outFinishPreviousGesture = false;

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

                mPointerVelocityControl.reset();
                return true;
            }
        }

        // We did not handle this timeout.
        return false;
    }

    const uint32_t currentFingerCount = mCurrentFingerIdBits.count();
    const uint32_t lastFingerCount = mLastFingerIdBits.count();

    // Update the velocity tracker.
    {
        VelocityTracker::Position positions[MAX_POINTERS];
        uint32_t count = 0;
        for (BitSet32 idBits(mCurrentFingerIdBits); !idBits.isEmpty(); count++) {
            uint32_t id = idBits.clearFirstMarkedBit();
            const RawPointerData::Pointer& pointer = mCurrentRawPointerData.pointerForId(id);
            positions[count].x = pointer.x * mPointerXMovementScale;
            positions[count].y = pointer.y * mPointerYMovementScale;
        }
        mPointerGesture.velocityTracker.addMovement(when,
                mCurrentFingerIdBits, positions);
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
        if (!mCurrentFingerIdBits.isEmpty()) {
            activeTouchChanged = true;
            activeTouchId = mPointerGesture.activeTouchId =
                    mCurrentFingerIdBits.firstMarkedBit();
            mPointerGesture.firstTouchTime = when;
        }
    } else if (!mCurrentFingerIdBits.hasBit(activeTouchId)) {
        activeTouchChanged = true;
        if (!mCurrentFingerIdBits.isEmpty()) {
            activeTouchId = mPointerGesture.activeTouchId =
                    mCurrentFingerIdBits.firstMarkedBit();
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
                    && currentFingerCount < 2) {
                // Enter quiet time when exiting swipe or freeform state.
                // This is to prevent accidentally entering the hover state and flinging the
                // pointer when finishing a swipe and there is still one pointer left onscreen.
                isQuietTime = true;
            } else if (mPointerGesture.lastGestureMode == PointerGesture::BUTTON_CLICK_OR_DRAG
                    && currentFingerCount >= 2
                    && !isPointerDown(mCurrentButtonState)) {
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

        mPointerVelocityControl.reset();
    } else if (isPointerDown(mCurrentButtonState)) {
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
                "currentFingerCount=%d", activeTouchId, currentFingerCount);
#endif
        // Reset state when just starting.
        if (mPointerGesture.lastGestureMode != PointerGesture::BUTTON_CLICK_OR_DRAG) {
            *outFinishPreviousGesture = true;
            mPointerGesture.activeGestureId = 0;
        }

        // Switch pointers if needed.
        // Find the fastest pointer and follow it.
        if (activeTouchId >= 0 && currentFingerCount > 1) {
            int32_t bestId = -1;
            float bestSpeed = mConfig.pointerGestureDragMinSwitchSpeed;
            for (BitSet32 idBits(mCurrentFingerIdBits); !idBits.isEmpty(); ) {
                uint32_t id = idBits.clearFirstMarkedBit();
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

        if (activeTouchId >= 0 && mLastFingerIdBits.hasBit(activeTouchId)) {
            const RawPointerData::Pointer& currentPointer =
                    mCurrentRawPointerData.pointerForId(activeTouchId);
            const RawPointerData::Pointer& lastPointer =
                    mLastRawPointerData.pointerForId(activeTouchId);
            float deltaX = (currentPointer.x - lastPointer.x) * mPointerXMovementScale;
            float deltaY = (currentPointer.y - lastPointer.y) * mPointerYMovementScale;

            rotateDelta(mSurfaceOrientation, &deltaX, &deltaY);
            mPointerVelocityControl.move(when, &deltaX, &deltaY);

            // Move the pointer using a relative motion.
            // When using spots, the click will occur at the position of the anchor
            // spot and all other spots will move there.
            mPointerController->move(deltaX, deltaY);
        } else {
            mPointerVelocityControl.reset();
        }

        float x, y;
        mPointerController->getPosition(&x, &y);

        mPointerGesture.currentGestureMode = PointerGesture::BUTTON_CLICK_OR_DRAG;
        mPointerGesture.currentGestureIdBits.clear();
        mPointerGesture.currentGestureIdBits.markBit(mPointerGesture.activeGestureId);
        mPointerGesture.currentGestureIdToIndex[mPointerGesture.activeGestureId] = 0;
        mPointerGesture.currentGestureProperties[0].clear();
        mPointerGesture.currentGestureProperties[0].id = mPointerGesture.activeGestureId;
        mPointerGesture.currentGestureProperties[0].toolType = AMOTION_EVENT_TOOL_TYPE_FINGER;
        mPointerGesture.currentGestureCoords[0].clear();
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, x);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 1.0f);
    } else if (currentFingerCount == 0) {
        // Case 3. No fingers down and button is not pressed. (NEUTRAL)
        if (mPointerGesture.lastGestureMode != PointerGesture::NEUTRAL) {
            *outFinishPreviousGesture = true;
        }

        // Watch for taps coming out of HOVER or TAP_DRAG mode.
        // Checking for taps after TAP_DRAG allows us to detect double-taps.
        bool tapped = false;
        if ((mPointerGesture.lastGestureMode == PointerGesture::HOVER
                || mPointerGesture.lastGestureMode == PointerGesture::TAP_DRAG)
                && lastFingerCount == 1) {
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
                            AMOTION_EVENT_TOOL_TYPE_FINGER;
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

        mPointerVelocityControl.reset();

        if (!tapped) {
#if DEBUG_GESTURES
            LOGD("Gestures: NEUTRAL");
#endif
            mPointerGesture.activeGestureId = -1;
            mPointerGesture.currentGestureMode = PointerGesture::NEUTRAL;
            mPointerGesture.currentGestureIdBits.clear();
        }
    } else if (currentFingerCount == 1) {
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

        if (mLastFingerIdBits.hasBit(activeTouchId)) {
            const RawPointerData::Pointer& currentPointer =
                    mCurrentRawPointerData.pointerForId(activeTouchId);
            const RawPointerData::Pointer& lastPointer =
                    mLastRawPointerData.pointerForId(activeTouchId);
            float deltaX = (currentPointer.x - lastPointer.x)
                    * mPointerXMovementScale;
            float deltaY = (currentPointer.y - lastPointer.y)
                    * mPointerYMovementScale;

            rotateDelta(mSurfaceOrientation, &deltaX, &deltaY);
            mPointerVelocityControl.move(when, &deltaX, &deltaY);

            // Move the pointer using a relative motion.
            // When using spots, the hover or drag will occur at the position of the anchor spot.
            mPointerController->move(deltaX, deltaY);
        } else {
            mPointerVelocityControl.reset();
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
                AMOTION_EVENT_TOOL_TYPE_FINGER;
        mPointerGesture.currentGestureCoords[0].clear();
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, x);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE,
                down ? 1.0f : 0.0f);

        if (lastFingerCount == 0 && currentFingerCount != 0) {
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
        } else if (!settled && currentFingerCount > lastFingerCount) {
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
            mPointerVelocityControl.reset();

            // Use the centroid and pointer location as the reference points for the gesture.
#if DEBUG_GESTURES
            LOGD("Gestures: Using centroid as reference for MULTITOUCH, "
                    "settle time remaining %0.3fms", (mPointerGesture.firstTouchTime
                            + mConfig.pointerGestureMultitouchSettleInterval - when)
                            * 0.000001f);
#endif
            mCurrentRawPointerData.getCentroidOfTouchingPointers(
                    &mPointerGesture.referenceTouchX,
                    &mPointerGesture.referenceTouchY);
            mPointerController->getPosition(&mPointerGesture.referenceGestureX,
                    &mPointerGesture.referenceGestureY);
        }

        // Clear the reference deltas for fingers not yet included in the reference calculation.
        for (BitSet32 idBits(mCurrentFingerIdBits.value
                & ~mPointerGesture.referenceIdBits.value); !idBits.isEmpty(); ) {
            uint32_t id = idBits.clearFirstMarkedBit();
            mPointerGesture.referenceDeltas[id].dx = 0;
            mPointerGesture.referenceDeltas[id].dy = 0;
        }
        mPointerGesture.referenceIdBits = mCurrentFingerIdBits;

        // Add delta for all fingers and calculate a common movement delta.
        float commonDeltaX = 0, commonDeltaY = 0;
        BitSet32 commonIdBits(mLastFingerIdBits.value
                & mCurrentFingerIdBits.value);
        for (BitSet32 idBits(commonIdBits); !idBits.isEmpty(); ) {
            bool first = (idBits == commonIdBits);
            uint32_t id = idBits.clearFirstMarkedBit();
            const RawPointerData::Pointer& cpd = mCurrentRawPointerData.pointerForId(id);
            const RawPointerData::Pointer& lpd = mLastRawPointerData.pointerForId(id);
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
                uint32_t id = idBits.clearFirstMarkedBit();
                PointerGesture::Delta& delta = mPointerGesture.referenceDeltas[id];
                dist[id] = hypotf(delta.dx * mPointerXZoomScale,
                        delta.dy * mPointerYZoomScale);
                if (dist[id] > mConfig.pointerGestureMultitouchMinDistance) {
                    distOverThreshold += 1;
                }
            }

            // Only transition when at least two pointers have moved further than
            // the minimum distance threshold.
            if (distOverThreshold >= 2) {
                if (currentFingerCount > 2) {
                    // There are more than two pointers, switch to FREEFORM.
#if DEBUG_GESTURES
                    LOGD("Gestures: PRESS transitioned to FREEFORM, number of pointers %d > 2",
                            currentFingerCount);
#endif
                    *outCancelPreviousGesture = true;
                    mPointerGesture.currentGestureMode = PointerGesture::FREEFORM;
                } else {
                    // There are exactly two pointers.
                    BitSet32 idBits(mCurrentFingerIdBits);
                    uint32_t id1 = idBits.clearFirstMarkedBit();
                    uint32_t id2 = idBits.firstMarkedBit();
                    const RawPointerData::Pointer& p1 = mCurrentRawPointerData.pointerForId(id1);
                    const RawPointerData::Pointer& p2 = mCurrentRawPointerData.pointerForId(id2);
                    float mutualDistance = distance(p1.x, p1.y, p2.x, p2.y);
                    if (mutualDistance > mPointerGestureMaxSwipeWidth) {
                        // There are two pointers but they are too far apart for a SWIPE,
                        // switch to FREEFORM.
#if DEBUG_GESTURES
                        LOGD("Gestures: PRESS transitioned to FREEFORM, distance %0.3f > %0.3f",
                                mutualDistance, mPointerGestureMaxSwipeWidth);
#endif
                        *outCancelPreviousGesture = true;
                        mPointerGesture.currentGestureMode = PointerGesture::FREEFORM;
                    } else {
                        // There are two pointers.  Wait for both pointers to start moving
                        // before deciding whether this is a SWIPE or FREEFORM gesture.
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
                            float dx1 = delta1.dx * mPointerXZoomScale;
                            float dy1 = delta1.dy * mPointerYZoomScale;
                            float dx2 = delta2.dx * mPointerXZoomScale;
                            float dy2 = delta2.dy * mPointerYZoomScale;
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
            }
        } else if (mPointerGesture.currentGestureMode == PointerGesture::SWIPE) {
            // Switch from SWIPE to FREEFORM if additional pointers go down.
            // Cancel previous gesture.
            if (currentFingerCount > 2) {
#if DEBUG_GESTURES
                LOGD("Gestures: SWIPE transitioned to FREEFORM, number of pointers %d > 2",
                        currentFingerCount);
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
                uint32_t id = idBits.clearFirstMarkedBit();
                PointerGesture::Delta& delta = mPointerGesture.referenceDeltas[id];
                delta.dx = 0;
                delta.dy = 0;
            }

            mPointerGesture.referenceTouchX += commonDeltaX;
            mPointerGesture.referenceTouchY += commonDeltaY;

            commonDeltaX *= mPointerXMovementScale;
            commonDeltaY *= mPointerYMovementScale;

            rotateDelta(mSurfaceOrientation, &commonDeltaX, &commonDeltaY);
            mPointerVelocityControl.move(when, &commonDeltaX, &commonDeltaY);

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
                    activeTouchId, mPointerGesture.activeGestureId, currentFingerCount);
#endif
            LOG_ASSERT(mPointerGesture.activeGestureId >= 0);

            mPointerGesture.currentGestureIdBits.clear();
            mPointerGesture.currentGestureIdBits.markBit(mPointerGesture.activeGestureId);
            mPointerGesture.currentGestureIdToIndex[mPointerGesture.activeGestureId] = 0;
            mPointerGesture.currentGestureProperties[0].clear();
            mPointerGesture.currentGestureProperties[0].id = mPointerGesture.activeGestureId;
            mPointerGesture.currentGestureProperties[0].toolType =
                    AMOTION_EVENT_TOOL_TYPE_FINGER;
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
                    activeTouchId, mPointerGesture.activeGestureId, currentFingerCount);
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
                mappedTouchIdBits.value = mLastFingerIdBits.value
                        & mCurrentFingerIdBits.value;
                usedGestureIdBits = mPointerGesture.lastGestureIdBits;

                // Check whether we need to choose a new active gesture id because the
                // current went went up.
                for (BitSet32 upTouchIdBits(mLastFingerIdBits.value
                        & ~mCurrentFingerIdBits.value);
                        !upTouchIdBits.isEmpty(); ) {
                    uint32_t upTouchId = upTouchIdBits.clearFirstMarkedBit();
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

            BitSet32 idBits(mCurrentFingerIdBits);
            for (uint32_t i = 0; i < currentFingerCount; i++) {
                uint32_t touchId = idBits.clearFirstMarkedBit();
                uint32_t gestureId;
                if (!mappedTouchIdBits.hasBit(touchId)) {
                    gestureId = usedGestureIdBits.markFirstUnmarkedBit();
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

                const RawPointerData::Pointer& pointer =
                        mCurrentRawPointerData.pointerForId(touchId);
                float deltaX = (pointer.x - mPointerGesture.referenceTouchX)
                        * mPointerXZoomScale;
                float deltaY = (pointer.y - mPointerGesture.referenceTouchY)
                        * mPointerYZoomScale;
                rotateDelta(mSurfaceOrientation, &deltaX, &deltaY);

                mPointerGesture.currentGestureProperties[i].clear();
                mPointerGesture.currentGestureProperties[i].id = gestureId;
                mPointerGesture.currentGestureProperties[i].toolType =
                        AMOTION_EVENT_TOOL_TYPE_FINGER;
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

    mPointerController->setButtonState(mCurrentButtonState);

#if DEBUG_GESTURES
    LOGD("Gestures: finishPreviousGesture=%s, cancelPreviousGesture=%s, "
            "currentGestureMode=%d, currentGestureIdBits=0x%08x, "
            "lastGestureMode=%d, lastGestureIdBits=0x%08x",
            toString(*outFinishPreviousGesture), toString(*outCancelPreviousGesture),
            mPointerGesture.currentGestureMode, mPointerGesture.currentGestureIdBits.value,
            mPointerGesture.lastGestureMode, mPointerGesture.lastGestureIdBits.value);
    for (BitSet32 idBits = mPointerGesture.currentGestureIdBits; !idBits.isEmpty(); ) {
        uint32_t id = idBits.clearFirstMarkedBit();
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
        uint32_t id = idBits.clearFirstMarkedBit();
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

void TouchInputMapper::dispatchPointerStylus(nsecs_t when, uint32_t policyFlags) {
    mPointerSimple.currentCoords.clear();
    mPointerSimple.currentProperties.clear();

    bool down, hovering;
    if (!mCurrentStylusIdBits.isEmpty()) {
        uint32_t id = mCurrentStylusIdBits.firstMarkedBit();
        uint32_t index = mCurrentCookedPointerData.idToIndex[id];
        float x = mCurrentCookedPointerData.pointerCoords[index].getX();
        float y = mCurrentCookedPointerData.pointerCoords[index].getY();
        mPointerController->setPosition(x, y);

        hovering = mCurrentCookedPointerData.hoveringIdBits.hasBit(id);
        down = !hovering;

        mPointerController->getPosition(&x, &y);
        mPointerSimple.currentCoords.copyFrom(mCurrentCookedPointerData.pointerCoords[index]);
        mPointerSimple.currentCoords.setAxisValue(AMOTION_EVENT_AXIS_X, x);
        mPointerSimple.currentCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        mPointerSimple.currentProperties.id = 0;
        mPointerSimple.currentProperties.toolType =
                mCurrentCookedPointerData.pointerProperties[index].toolType;
    } else {
        down = false;
        hovering = false;
    }

    dispatchPointerSimple(when, policyFlags, down, hovering);
}

void TouchInputMapper::abortPointerStylus(nsecs_t when, uint32_t policyFlags) {
    abortPointerSimple(when, policyFlags);
}

void TouchInputMapper::dispatchPointerMouse(nsecs_t when, uint32_t policyFlags) {
    mPointerSimple.currentCoords.clear();
    mPointerSimple.currentProperties.clear();

    bool down, hovering;
    if (!mCurrentMouseIdBits.isEmpty()) {
        uint32_t id = mCurrentMouseIdBits.firstMarkedBit();
        uint32_t currentIndex = mCurrentRawPointerData.idToIndex[id];
        if (mLastMouseIdBits.hasBit(id)) {
            uint32_t lastIndex = mCurrentRawPointerData.idToIndex[id];
            float deltaX = (mCurrentRawPointerData.pointers[currentIndex].x
                    - mLastRawPointerData.pointers[lastIndex].x)
                    * mPointerXMovementScale;
            float deltaY = (mCurrentRawPointerData.pointers[currentIndex].y
                    - mLastRawPointerData.pointers[lastIndex].y)
                    * mPointerYMovementScale;

            rotateDelta(mSurfaceOrientation, &deltaX, &deltaY);
            mPointerVelocityControl.move(when, &deltaX, &deltaY);

            mPointerController->move(deltaX, deltaY);
        } else {
            mPointerVelocityControl.reset();
        }

        down = isPointerDown(mCurrentButtonState);
        hovering = !down;

        float x, y;
        mPointerController->getPosition(&x, &y);
        mPointerSimple.currentCoords.copyFrom(
                mCurrentCookedPointerData.pointerCoords[currentIndex]);
        mPointerSimple.currentCoords.setAxisValue(AMOTION_EVENT_AXIS_X, x);
        mPointerSimple.currentCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        mPointerSimple.currentCoords.setAxisValue(AMOTION_EVENT_AXIS_PRESSURE,
                hovering ? 0.0f : 1.0f);
        mPointerSimple.currentProperties.id = 0;
        mPointerSimple.currentProperties.toolType =
                mCurrentCookedPointerData.pointerProperties[currentIndex].toolType;
    } else {
        mPointerVelocityControl.reset();

        down = false;
        hovering = false;
    }

    dispatchPointerSimple(when, policyFlags, down, hovering);
}

void TouchInputMapper::abortPointerMouse(nsecs_t when, uint32_t policyFlags) {
    abortPointerSimple(when, policyFlags);

    mPointerVelocityControl.reset();
}

void TouchInputMapper::dispatchPointerSimple(nsecs_t when, uint32_t policyFlags,
        bool down, bool hovering) {
    int32_t metaState = getContext()->getGlobalMetaState();

    if (mPointerController != NULL) {
        if (down || hovering) {
            mPointerController->setPresentation(PointerControllerInterface::PRESENTATION_POINTER);
            mPointerController->clearSpots();
            mPointerController->setButtonState(mCurrentButtonState);
            mPointerController->unfade(PointerControllerInterface::TRANSITION_IMMEDIATE);
        } else if (!down && !hovering && (mPointerSimple.down || mPointerSimple.hovering)) {
            mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
        }
    }

    if (mPointerSimple.down && !down) {
        mPointerSimple.down = false;

        // Send up.
        NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                 AMOTION_EVENT_ACTION_UP, 0, metaState, mLastButtonState, 0,
                 1, &mPointerSimple.lastProperties, &mPointerSimple.lastCoords,
                 mOrientedXPrecision, mOrientedYPrecision,
                 mPointerSimple.downTime);
        getListener()->notifyMotion(&args);
    }

    if (mPointerSimple.hovering && !hovering) {
        mPointerSimple.hovering = false;

        // Send hover exit.
        NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_HOVER_EXIT, 0, metaState, mLastButtonState, 0,
                1, &mPointerSimple.lastProperties, &mPointerSimple.lastCoords,
                mOrientedXPrecision, mOrientedYPrecision,
                mPointerSimple.downTime);
        getListener()->notifyMotion(&args);
    }

    if (down) {
        if (!mPointerSimple.down) {
            mPointerSimple.down = true;
            mPointerSimple.downTime = when;

            // Send down.
            NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                    AMOTION_EVENT_ACTION_DOWN, 0, metaState, mCurrentButtonState, 0,
                    1, &mPointerSimple.currentProperties, &mPointerSimple.currentCoords,
                    mOrientedXPrecision, mOrientedYPrecision,
                    mPointerSimple.downTime);
            getListener()->notifyMotion(&args);
        }

        // Send move.
        NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_MOVE, 0, metaState, mCurrentButtonState, 0,
                1, &mPointerSimple.currentProperties, &mPointerSimple.currentCoords,
                mOrientedXPrecision, mOrientedYPrecision,
                mPointerSimple.downTime);
        getListener()->notifyMotion(&args);
    }

    if (hovering) {
        if (!mPointerSimple.hovering) {
            mPointerSimple.hovering = true;

            // Send hover enter.
            NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                    AMOTION_EVENT_ACTION_HOVER_ENTER, 0, metaState, mCurrentButtonState, 0,
                    1, &mPointerSimple.currentProperties, &mPointerSimple.currentCoords,
                    mOrientedXPrecision, mOrientedYPrecision,
                    mPointerSimple.downTime);
            getListener()->notifyMotion(&args);
        }

        // Send hover move.
        NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_HOVER_MOVE, 0, metaState, mCurrentButtonState, 0,
                1, &mPointerSimple.currentProperties, &mPointerSimple.currentCoords,
                mOrientedXPrecision, mOrientedYPrecision,
                mPointerSimple.downTime);
        getListener()->notifyMotion(&args);
    }

    if (mCurrentRawVScroll || mCurrentRawHScroll) {
        float vscroll = mCurrentRawVScroll;
        float hscroll = mCurrentRawHScroll;
        mWheelYVelocityControl.move(when, NULL, &vscroll);
        mWheelXVelocityControl.move(when, &hscroll, NULL);

        // Send scroll.
        PointerCoords pointerCoords;
        pointerCoords.copyFrom(mPointerSimple.currentCoords);
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_VSCROLL, vscroll);
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_HSCROLL, hscroll);

        NotifyMotionArgs args(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_SCROLL, 0, metaState, mCurrentButtonState, 0,
                1, &mPointerSimple.currentProperties, &pointerCoords,
                mOrientedXPrecision, mOrientedYPrecision,
                mPointerSimple.downTime);
        getListener()->notifyMotion(&args);
    }

    // Save state.
    if (down || hovering) {
        mPointerSimple.lastCoords.copyFrom(mPointerSimple.currentCoords);
        mPointerSimple.lastProperties.copyFrom(mPointerSimple.currentProperties);
    } else {
        mPointerSimple.reset();
    }
}

void TouchInputMapper::abortPointerSimple(nsecs_t when, uint32_t policyFlags) {
    mPointerSimple.currentCoords.clear();
    mPointerSimple.currentProperties.clear();

    dispatchPointerSimple(when, policyFlags, false, false);
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
        uint32_t id = idBits.clearFirstMarkedBit();
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

    NotifyMotionArgs args(when, getDeviceId(), source, policyFlags,
            action, flags, metaState, buttonState, edgeFlags,
            pointerCount, pointerProperties, pointerCoords, xPrecision, yPrecision, downTime);
    getListener()->notifyMotion(&args);
}

bool TouchInputMapper::updateMovedPointers(const PointerProperties* inProperties,
        const PointerCoords* inCoords, const uint32_t* inIdToIndex,
        PointerProperties* outProperties, PointerCoords* outCoords, const uint32_t* outIdToIndex,
        BitSet32 idBits) const {
    bool changed = false;
    while (!idBits.isEmpty()) {
        uint32_t id = idBits.clearFirstMarkedBit();
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
    if (mPointerController != NULL) {
        mPointerController->fade(PointerControllerInterface::TRANSITION_GRADUAL);
    }
}

bool TouchInputMapper::isPointInsideSurface(int32_t x, int32_t y) {
    return x >= mRawPointerAxes.x.minValue && x <= mRawPointerAxes.x.maxValue
            && y >= mRawPointerAxes.y.minValue && y <= mRawPointerAxes.y.maxValue;
}

const TouchInputMapper::VirtualKey* TouchInputMapper::findVirtualKeyHit(
        int32_t x, int32_t y) {
    size_t numVirtualKeys = mVirtualKeys.size();
    for (size_t i = 0; i < numVirtualKeys; i++) {
        const VirtualKey& virtualKey = mVirtualKeys[i];

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

void TouchInputMapper::assignPointerIds() {
    uint32_t currentPointerCount = mCurrentRawPointerData.pointerCount;
    uint32_t lastPointerCount = mLastRawPointerData.pointerCount;

    mCurrentRawPointerData.clearIdBits();

    if (currentPointerCount == 0) {
        // No pointers to assign.
        return;
    }

    if (lastPointerCount == 0) {
        // All pointers are new.
        for (uint32_t i = 0; i < currentPointerCount; i++) {
            uint32_t id = i;
            mCurrentRawPointerData.pointers[i].id = id;
            mCurrentRawPointerData.idToIndex[id] = i;
            mCurrentRawPointerData.markIdBit(id, mCurrentRawPointerData.isHovering(i));
        }
        return;
    }

    if (currentPointerCount == 1 && lastPointerCount == 1
            && mCurrentRawPointerData.pointers[0].toolType
                    == mLastRawPointerData.pointers[0].toolType) {
        // Only one pointer and no change in count so it must have the same id as before.
        uint32_t id = mLastRawPointerData.pointers[0].id;
        mCurrentRawPointerData.pointers[0].id = id;
        mCurrentRawPointerData.idToIndex[id] = 0;
        mCurrentRawPointerData.markIdBit(id, mCurrentRawPointerData.isHovering(0));
        return;
    }

    // General case.
    // We build a heap of squared euclidean distances between current and last pointers
    // associated with the current and last pointer indices.  Then, we find the best
    // match (by distance) for each current pointer.
    // The pointers must have the same tool type but it is possible for them to
    // transition from hovering to touching or vice-versa while retaining the same id.
    PointerDistanceHeapElement heap[MAX_POINTERS * MAX_POINTERS];

    uint32_t heapSize = 0;
    for (uint32_t currentPointerIndex = 0; currentPointerIndex < currentPointerCount;
            currentPointerIndex++) {
        for (uint32_t lastPointerIndex = 0; lastPointerIndex < lastPointerCount;
                lastPointerIndex++) {
            const RawPointerData::Pointer& currentPointer =
                    mCurrentRawPointerData.pointers[currentPointerIndex];
            const RawPointerData::Pointer& lastPointer =
                    mLastRawPointerData.pointers[lastPointerIndex];
            if (currentPointer.toolType == lastPointer.toolType) {
                int64_t deltaX = currentPointer.x - lastPointer.x;
                int64_t deltaY = currentPointer.y - lastPointer.y;

                uint64_t distance = uint64_t(deltaX * deltaX + deltaY * deltaY);

                // Insert new element into the heap (sift up).
                heap[heapSize].currentPointerIndex = currentPointerIndex;
                heap[heapSize].lastPointerIndex = lastPointerIndex;
                heap[heapSize].distance = distance;
                heapSize += 1;
            }
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
    LOGD("assignPointerIds - initial distance min-heap: size=%d", heapSize);
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
    for (uint32_t i = min(currentPointerCount, lastPointerCount); heapSize > 0 && i > 0; i--) {
        while (heapSize > 0) {
            if (first) {
                // The first time through the loop, we just consume the root element of
                // the heap (the one with smallest distance).
                first = false;
            } else {
                // Previous iterations consumed the root element of the heap.
                // Pop root element off of the heap (sift down).
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
                LOGD("assignPointerIds - reduced distance min-heap: size=%d", heapSize);
                for (size_t i = 0; i < heapSize; i++) {
                    LOGD("  heap[%d]: cur=%d, last=%d, distance=%lld",
                            i, heap[i].currentPointerIndex, heap[i].lastPointerIndex,
                            heap[i].distance);
                }
#endif
            }

            heapSize -= 1;

            uint32_t currentPointerIndex = heap[0].currentPointerIndex;
            if (matchedCurrentBits.hasBit(currentPointerIndex)) continue; // already matched

            uint32_t lastPointerIndex = heap[0].lastPointerIndex;
            if (matchedLastBits.hasBit(lastPointerIndex)) continue; // already matched

            matchedCurrentBits.markBit(currentPointerIndex);
            matchedLastBits.markBit(lastPointerIndex);

            uint32_t id = mLastRawPointerData.pointers[lastPointerIndex].id;
            mCurrentRawPointerData.pointers[currentPointerIndex].id = id;
            mCurrentRawPointerData.idToIndex[id] = currentPointerIndex;
            mCurrentRawPointerData.markIdBit(id,
                    mCurrentRawPointerData.isHovering(currentPointerIndex));
            usedIdBits.markBit(id);

#if DEBUG_POINTER_ASSIGNMENT
            LOGD("assignPointerIds - matched: cur=%d, last=%d, id=%d, distance=%lld",
                    lastPointerIndex, currentPointerIndex, id, heap[0].distance);
#endif
            break;
        }
    }

    // Assign fresh ids to pointers that were not matched in the process.
    for (uint32_t i = currentPointerCount - matchedCurrentBits.count(); i != 0; i--) {
        uint32_t currentPointerIndex = matchedCurrentBits.markFirstUnmarkedBit();
        uint32_t id = usedIdBits.markFirstUnmarkedBit();

        mCurrentRawPointerData.pointers[currentPointerIndex].id = id;
        mCurrentRawPointerData.idToIndex[id] = currentPointerIndex;
        mCurrentRawPointerData.markIdBit(id,
                mCurrentRawPointerData.isHovering(currentPointerIndex));

#if DEBUG_POINTER_ASSIGNMENT
        LOGD("assignPointerIds - assigned: cur=%d, id=%d",
                currentPointerIndex, id);
#endif
    }
}

int32_t TouchInputMapper::getKeyCodeState(uint32_t sourceMask, int32_t keyCode) {
    if (mCurrentVirtualKey.down && mCurrentVirtualKey.keyCode == keyCode) {
        return AKEY_STATE_VIRTUAL;
    }

    size_t numVirtualKeys = mVirtualKeys.size();
    for (size_t i = 0; i < numVirtualKeys; i++) {
        const VirtualKey& virtualKey = mVirtualKeys[i];
        if (virtualKey.keyCode == keyCode) {
            return AKEY_STATE_UP;
        }
    }

    return AKEY_STATE_UNKNOWN;
}

int32_t TouchInputMapper::getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
    if (mCurrentVirtualKey.down && mCurrentVirtualKey.scanCode == scanCode) {
        return AKEY_STATE_VIRTUAL;
    }

    size_t numVirtualKeys = mVirtualKeys.size();
    for (size_t i = 0; i < numVirtualKeys; i++) {
        const VirtualKey& virtualKey = mVirtualKeys[i];
        if (virtualKey.scanCode == scanCode) {
            return AKEY_STATE_UP;
        }
    }

    return AKEY_STATE_UNKNOWN;
}

bool TouchInputMapper::markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) {
    size_t numVirtualKeys = mVirtualKeys.size();
    for (size_t i = 0; i < numVirtualKeys; i++) {
        const VirtualKey& virtualKey = mVirtualKeys[i];

        for (size_t i = 0; i < numCodes; i++) {
            if (virtualKey.keyCode == keyCodes[i]) {
                outFlags[i] = 1;
            }
        }
    }

    return true;
}


// --- SingleTouchInputMapper ---

SingleTouchInputMapper::SingleTouchInputMapper(InputDevice* device) :
        TouchInputMapper(device) {
}

SingleTouchInputMapper::~SingleTouchInputMapper() {
}

void SingleTouchInputMapper::reset(nsecs_t when) {
    mSingleTouchMotionAccumulator.reset(getDevice());

    TouchInputMapper::reset(when);
}

void SingleTouchInputMapper::process(const RawEvent* rawEvent) {
    TouchInputMapper::process(rawEvent);

    mSingleTouchMotionAccumulator.process(rawEvent);
}

void SingleTouchInputMapper::syncTouch(nsecs_t when, bool* outHavePointerIds) {
    if (mTouchButtonAccumulator.isToolActive()) {
        mCurrentRawPointerData.pointerCount = 1;
        mCurrentRawPointerData.idToIndex[0] = 0;

        bool isHovering = mTouchButtonAccumulator.getToolType() != AMOTION_EVENT_TOOL_TYPE_MOUSE
                && (mTouchButtonAccumulator.isHovering()
                        || (mRawPointerAxes.pressure.valid
                                && mSingleTouchMotionAccumulator.getAbsolutePressure() <= 0));
        mCurrentRawPointerData.markIdBit(0, isHovering);

        RawPointerData::Pointer& outPointer = mCurrentRawPointerData.pointers[0];
        outPointer.id = 0;
        outPointer.x = mSingleTouchMotionAccumulator.getAbsoluteX();
        outPointer.y = mSingleTouchMotionAccumulator.getAbsoluteY();
        outPointer.pressure = mSingleTouchMotionAccumulator.getAbsolutePressure();
        outPointer.touchMajor = 0;
        outPointer.touchMinor = 0;
        outPointer.toolMajor = mSingleTouchMotionAccumulator.getAbsoluteToolWidth();
        outPointer.toolMinor = mSingleTouchMotionAccumulator.getAbsoluteToolWidth();
        outPointer.orientation = 0;
        outPointer.distance = mSingleTouchMotionAccumulator.getAbsoluteDistance();
        outPointer.tiltX = mSingleTouchMotionAccumulator.getAbsoluteTiltX();
        outPointer.tiltY = mSingleTouchMotionAccumulator.getAbsoluteTiltY();
        outPointer.toolType = mTouchButtonAccumulator.getToolType();
        if (outPointer.toolType == AMOTION_EVENT_TOOL_TYPE_UNKNOWN) {
            outPointer.toolType = AMOTION_EVENT_TOOL_TYPE_FINGER;
        }
        outPointer.isHovering = isHovering;
    }
}

void SingleTouchInputMapper::configureRawPointerAxes() {
    TouchInputMapper::configureRawPointerAxes();

    getAbsoluteAxisInfo(ABS_X, &mRawPointerAxes.x);
    getAbsoluteAxisInfo(ABS_Y, &mRawPointerAxes.y);
    getAbsoluteAxisInfo(ABS_PRESSURE, &mRawPointerAxes.pressure);
    getAbsoluteAxisInfo(ABS_TOOL_WIDTH, &mRawPointerAxes.toolMajor);
    getAbsoluteAxisInfo(ABS_DISTANCE, &mRawPointerAxes.distance);
    getAbsoluteAxisInfo(ABS_TILT_X, &mRawPointerAxes.tiltX);
    getAbsoluteAxisInfo(ABS_TILT_Y, &mRawPointerAxes.tiltY);
}


// --- MultiTouchInputMapper ---

MultiTouchInputMapper::MultiTouchInputMapper(InputDevice* device) :
        TouchInputMapper(device) {
}

MultiTouchInputMapper::~MultiTouchInputMapper() {
}

void MultiTouchInputMapper::reset(nsecs_t when) {
    mMultiTouchMotionAccumulator.reset(getDevice());

    mPointerIdBits.clear();

    TouchInputMapper::reset(when);
}

void MultiTouchInputMapper::process(const RawEvent* rawEvent) {
    TouchInputMapper::process(rawEvent);

    mMultiTouchMotionAccumulator.process(rawEvent);
}

void MultiTouchInputMapper::syncTouch(nsecs_t when, bool* outHavePointerIds) {
    size_t inCount = mMultiTouchMotionAccumulator.getSlotCount();
    size_t outCount = 0;
    BitSet32 newPointerIdBits;

    for (size_t inIndex = 0; inIndex < inCount; inIndex++) {
        const MultiTouchMotionAccumulator::Slot* inSlot =
                mMultiTouchMotionAccumulator.getSlot(inIndex);
        if (!inSlot->isInUse()) {
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

        RawPointerData::Pointer& outPointer = mCurrentRawPointerData.pointers[outCount];
        outPointer.x = inSlot->getX();
        outPointer.y = inSlot->getY();
        outPointer.pressure = inSlot->getPressure();
        outPointer.touchMajor = inSlot->getTouchMajor();
        outPointer.touchMinor = inSlot->getTouchMinor();
        outPointer.toolMajor = inSlot->getToolMajor();
        outPointer.toolMinor = inSlot->getToolMinor();
        outPointer.orientation = inSlot->getOrientation();
        outPointer.distance = inSlot->getDistance();
        outPointer.tiltX = 0;
        outPointer.tiltY = 0;

        outPointer.toolType = inSlot->getToolType();
        if (outPointer.toolType == AMOTION_EVENT_TOOL_TYPE_UNKNOWN) {
            outPointer.toolType = mTouchButtonAccumulator.getToolType();
            if (outPointer.toolType == AMOTION_EVENT_TOOL_TYPE_UNKNOWN) {
                outPointer.toolType = AMOTION_EVENT_TOOL_TYPE_FINGER;
            }
        }

        bool isHovering = mTouchButtonAccumulator.getToolType() != AMOTION_EVENT_TOOL_TYPE_MOUSE
                && (mTouchButtonAccumulator.isHovering()
                        || (mRawPointerAxes.pressure.valid && inSlot->getPressure() <= 0));
        outPointer.isHovering = isHovering;

        // Assign pointer id using tracking id if available.
        if (*outHavePointerIds) {
            int32_t trackingId = inSlot->getTrackingId();
            int32_t id = -1;
            if (trackingId >= 0) {
                for (BitSet32 idBits(mPointerIdBits); !idBits.isEmpty(); ) {
                    uint32_t n = idBits.clearFirstMarkedBit();
                    if (mPointerTrackingIdMap[n] == trackingId) {
                        id = n;
                    }
                }

                if (id < 0 && !mPointerIdBits.isFull()) {
                    id = mPointerIdBits.markFirstUnmarkedBit();
                    mPointerTrackingIdMap[id] = trackingId;
                }
            }
            if (id < 0) {
                *outHavePointerIds = false;
                mCurrentRawPointerData.clearIdBits();
                newPointerIdBits.clear();
            } else {
                outPointer.id = id;
                mCurrentRawPointerData.idToIndex[id] = outCount;
                mCurrentRawPointerData.markIdBit(id, isHovering);
                newPointerIdBits.markBit(id);
            }
        }

        outCount += 1;
    }

    mCurrentRawPointerData.pointerCount = outCount;
    mPointerIdBits = newPointerIdBits;

    mMultiTouchMotionAccumulator.finishSync();
}

void MultiTouchInputMapper::configureRawPointerAxes() {
    TouchInputMapper::configureRawPointerAxes();

    getAbsoluteAxisInfo(ABS_MT_POSITION_X, &mRawPointerAxes.x);
    getAbsoluteAxisInfo(ABS_MT_POSITION_Y, &mRawPointerAxes.y);
    getAbsoluteAxisInfo(ABS_MT_TOUCH_MAJOR, &mRawPointerAxes.touchMajor);
    getAbsoluteAxisInfo(ABS_MT_TOUCH_MINOR, &mRawPointerAxes.touchMinor);
    getAbsoluteAxisInfo(ABS_MT_WIDTH_MAJOR, &mRawPointerAxes.toolMajor);
    getAbsoluteAxisInfo(ABS_MT_WIDTH_MINOR, &mRawPointerAxes.toolMinor);
    getAbsoluteAxisInfo(ABS_MT_ORIENTATION, &mRawPointerAxes.orientation);
    getAbsoluteAxisInfo(ABS_MT_PRESSURE, &mRawPointerAxes.pressure);
    getAbsoluteAxisInfo(ABS_MT_DISTANCE, &mRawPointerAxes.distance);
    getAbsoluteAxisInfo(ABS_MT_TRACKING_ID, &mRawPointerAxes.trackingId);
    getAbsoluteAxisInfo(ABS_MT_SLOT, &mRawPointerAxes.slot);

    if (mRawPointerAxes.trackingId.valid
            && mRawPointerAxes.slot.valid
            && mRawPointerAxes.slot.minValue == 0 && mRawPointerAxes.slot.maxValue > 0) {
        size_t slotCount = mRawPointerAxes.slot.maxValue + 1;
        if (slotCount > MAX_SLOTS) {
            LOGW("MultiTouch Device %s reported %d slots but the framework "
                    "only supports a maximum of %d slots at this time.",
                    getDeviceName().string(), slotCount, MAX_SLOTS);
            slotCount = MAX_SLOTS;
        }
        mMultiTouchMotionAccumulator.configure(slotCount, true /*usingSlotsProtocol*/);
    } else {
        mMultiTouchMotionAccumulator.configure(MAX_POINTERS, false /*usingSlotsProtocol*/);
    }
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

void JoystickInputMapper::configure(nsecs_t when,
        const InputReaderConfiguration* config, uint32_t changes) {
    InputMapper::configure(when, config, changes);

    if (!changes) { // first time only
        // Collect all axes.
        for (int32_t abs = 0; abs <= ABS_MAX; abs++) {
            RawAbsoluteAxisInfo rawAxisInfo;
            getAbsoluteAxisInfo(abs, &rawAxisInfo);
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

void JoystickInputMapper::reset(nsecs_t when) {
    // Recenter all axes.
    size_t numAxes = mAxes.size();
    for (size_t i = 0; i < numAxes; i++) {
        Axis& axis = mAxes.editValueAt(i);
        axis.resetValue();
    }

    InputMapper::reset(when);
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

    NotifyMotionArgs args(when, getDeviceId(), AINPUT_SOURCE_JOYSTICK, policyFlags,
            AMOTION_EVENT_ACTION_MOVE, 0, metaState, buttonState, AMOTION_EVENT_EDGE_FLAG_NONE,
            1, &pointerProperties, &pointerCoords, 0, 0, 0);
    getListener()->notifyMotion(&args);
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
