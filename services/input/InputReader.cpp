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

namespace android {

// --- Constants ---

// Quiet time between certain gesture transitions.
// Time to allow for all fingers or buttons to settle into a stable state before
// starting a new gesture.
static const nsecs_t QUIET_INTERVAL = 100 * 1000000; // 100 ms

// The minimum speed that a pointer must travel for us to consider switching the active
// touch pointer to it during a drag.  This threshold is set to avoid switching due
// to noise from a finger resting on the touch pad (perhaps just pressing it down).
static const float DRAG_MIN_SWITCH_SPEED = 50.0f; // pixels per second

// Tap gesture delay time.
// The time between down and up must be less than this to be considered a tap.
static const nsecs_t TAP_INTERVAL = 100 * 1000000; // 100 ms

// The distance in pixels that the pointer is allowed to move from initial down
// to up and still be called a tap.
static const float TAP_SLOP = 5.0f; // 5 pixels

// The transition from INDETERMINATE_MULTITOUCH to SWIPE or FREEFORM gesture mode is made when
// all of the pointers have traveled this number of pixels from the start point.
static const float MULTITOUCH_MIN_TRAVEL = 5.0f;

// The transition from INDETERMINATE_MULTITOUCH to SWIPE gesture mode can only occur when the
// cosine of the angle between the two vectors is greater than or equal to than this value
// which indicates that the vectors are oriented in the same direction.
// When the vectors are oriented in the exactly same direction, the cosine is 1.0.
// (In exactly opposite directions, the cosine is -1.0.)
static const float SWIPE_TRANSITION_ANGLE_COSINE = 0.5f; // cosine of 45 degrees


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

inline static float pythag(float x, float y) {
    return sqrtf(x * x + y * y);
}

inline static int32_t distanceSquared(int32_t x1, int32_t y1, int32_t x2, int32_t y2) {
    int32_t dx = x1 - x2;
    int32_t dy = y1 - y2;
    return dx * dx + dy * dy;
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

int32_t rotateKeyCode(int32_t keyCode, int32_t orientation) {
    return rotateValueUsingRotationMap(keyCode, orientation,
            keyCodeRotationMap, keyCodeRotationMapSize);
}

static const int32_t edgeFlagRotationMap[][4] = {
        // edge flags enumerated counter-clockwise with the original (unrotated) edge flag first
        // no rotation,        90 degree rotation,  180 degree rotation, 270 degree rotation
        { AMOTION_EVENT_EDGE_FLAG_BOTTOM,   AMOTION_EVENT_EDGE_FLAG_RIGHT,
                AMOTION_EVENT_EDGE_FLAG_TOP,     AMOTION_EVENT_EDGE_FLAG_LEFT },
        { AMOTION_EVENT_EDGE_FLAG_RIGHT,  AMOTION_EVENT_EDGE_FLAG_TOP,
                AMOTION_EVENT_EDGE_FLAG_LEFT,   AMOTION_EVENT_EDGE_FLAG_BOTTOM },
        { AMOTION_EVENT_EDGE_FLAG_TOP,     AMOTION_EVENT_EDGE_FLAG_LEFT,
                AMOTION_EVENT_EDGE_FLAG_BOTTOM,   AMOTION_EVENT_EDGE_FLAG_RIGHT },
        { AMOTION_EVENT_EDGE_FLAG_LEFT,   AMOTION_EVENT_EDGE_FLAG_BOTTOM,
                AMOTION_EVENT_EDGE_FLAG_RIGHT,  AMOTION_EVENT_EDGE_FLAG_TOP },
};
static const size_t edgeFlagRotationMapSize =
        sizeof(edgeFlagRotationMap) / sizeof(edgeFlagRotationMap[0]);

static int32_t rotateEdgeFlag(int32_t edgeFlag, int32_t orientation) {
    return rotateValueUsingRotationMap(edgeFlag, orientation,
            edgeFlagRotationMap, edgeFlagRotationMapSize);
}

static inline bool sourcesMatchMask(uint32_t sources, uint32_t sourceMask) {
    return (sources & sourceMask & ~ AINPUT_SOURCE_CLASS_MASK) != 0;
}

static uint32_t getButtonStateForScanCode(int32_t scanCode) {
    // Currently all buttons are mapped to the primary button.
    switch (scanCode) {
    case BTN_LEFT:
    case BTN_RIGHT:
    case BTN_MIDDLE:
    case BTN_SIDE:
    case BTN_EXTRA:
    case BTN_FORWARD:
    case BTN_BACK:
    case BTN_TASK:
        return BUTTON_STATE_PRIMARY;
    default:
        return 0;
    }
}

// Returns true if the pointer should be reported as being down given the specified
// button states.
static bool isPointerDown(uint32_t buttonState) {
    return buttonState & BUTTON_STATE_PRIMARY;
}

static int32_t calculateEdgeFlagsUsingPointerBounds(
        const sp<PointerControllerInterface>& pointerController, float x, float y) {
    int32_t edgeFlags = 0;
    float minX, minY, maxX, maxY;
    if (pointerController->getBounds(&minX, &minY, &maxX, &maxY)) {
        if (x <= minX) {
            edgeFlags |= AMOTION_EVENT_EDGE_FLAG_LEFT;
        } else if (x >= maxX) {
            edgeFlags |= AMOTION_EVENT_EDGE_FLAG_RIGHT;
        }
        if (y <= minY) {
            edgeFlags |= AMOTION_EVENT_EDGE_FLAG_TOP;
        } else if (y >= maxY) {
            edgeFlags |= AMOTION_EVENT_EDGE_FLAG_BOTTOM;
        }
    }
    return edgeFlags;
}


// --- InputReader ---

InputReader::InputReader(const sp<EventHubInterface>& eventHub,
        const sp<InputReaderPolicyInterface>& policy,
        const sp<InputDispatcherInterface>& dispatcher) :
        mEventHub(eventHub), mPolicy(policy), mDispatcher(dispatcher),
        mGlobalMetaState(0), mDisableVirtualKeysTimeout(LLONG_MIN), mNextTimeout(LLONG_MAX) {
    configureExcludedDevices();
    updateGlobalMetaState();
    updateInputConfiguration();
}

InputReader::~InputReader() {
    for (size_t i = 0; i < mDevices.size(); i++) {
        delete mDevices.valueAt(i);
    }
}

void InputReader::loopOnce() {
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
    device->configure();

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

void InputReader::configureExcludedDevices() {
    Vector<String8> excludedDeviceNames;
    mPolicy->getExcludedDeviceNames(excludedDeviceNames);

    for (size_t i = 0; i < excludedDeviceNames.size(); i++) {
        mEventHub->addExcludedDevice(excludedDeviceNames[i]);
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
        mContext(context), mId(id), mName(name), mSources(0), mIsExternal(false) {
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

void InputDevice::configure() {
    if (! isIgnored()) {
        mContext->getEventHub()->getConfiguration(mId, &mConfiguration);
    }

    mSources = 0;

    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        mapper->configure();
        mSources |= mapper->getSources();
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
                "keycode=0x%04x value=0x%04x flags=0x%08x",
                rawEvent->deviceId, rawEvent->type, rawEvent->scanCode, rawEvent->keyCode,
                rawEvent->value, rawEvent->flags);
#endif

        for (size_t i = 0; i < numMappers; i++) {
            InputMapper* mapper = mMappers[i];
            mapper->process(rawEvent);
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

void InputMapper::configure() {
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
        dump.appendFormat(INDENT4 "%s: min=%d, max=%d, flat=%d, fuzz=%d\n",
                name, axis.minValue, axis.maxValue, axis.flat, axis.fuzz);
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


void KeyboardInputMapper::configure() {
    InputMapper::configure();

    // Configure basic parameters.
    configureParameters();

    // Reset LEDs.
    {
        AutoMutex _l(mLock);
        resetLedStateLocked();
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

void CursorInputMapper::configure() {
    InputMapper::configure();

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
        uint32_t buttonState;
        { // acquire lock
            AutoMutex _l(mLock);

            buttonState = mLocked.buttonState;
            if (!buttonState) {
                initializeLocked();
                break; // done
            }
        } // release lock

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
        uint32_t buttonState = getButtonStateForScanCode(rawEvent->scanCode);
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
    int32_t motionEventEdgeFlags;
    PointerCoords pointerCoords;
    nsecs_t downTime;
    float vscroll, hscroll;
    { // acquire lock
        AutoMutex _l(mLock);

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

            float temp;
            switch (orientation) {
            case DISPLAY_ORIENTATION_90:
                temp = deltaX;
                deltaX = deltaY;
                deltaY = -temp;
                break;

            case DISPLAY_ORIENTATION_180:
                deltaX = -deltaX;
                deltaY = -deltaY;
                break;

            case DISPLAY_ORIENTATION_270:
                temp = deltaX;
                deltaX = -deltaY;
                deltaY = temp;
                break;
            }
        }

        pointerCoords.clear();

        motionEventEdgeFlags = AMOTION_EVENT_EDGE_FLAG_NONE;

        if (mPointerController != NULL) {
            mPointerController->move(deltaX, deltaY);
            if (buttonsChanged) {
                mPointerController->setButtonState(mLocked.buttonState);
            }

            float x, y;
            mPointerController->getPosition(&x, &y);
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_X, x);
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, y);

            if (motionEventAction == AMOTION_EVENT_ACTION_DOWN) {
                motionEventEdgeFlags = calculateEdgeFlagsUsingPointerBounds(
                        mPointerController, x, y);
            }
        } else {
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_X, deltaX);
            pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, deltaY);
        }

        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, down ? 1.0f : 0.0f);

        if (mHaveVWheel && (fields & Accumulator::FIELD_REL_WHEEL)) {
            vscroll = mAccumulator.relWheel;
        } else {
            vscroll = 0;
        }
        if (mHaveHWheel && (fields & Accumulator::FIELD_REL_HWHEEL)) {
            hscroll = mAccumulator.relHWheel;
        } else {
            hscroll = 0;
        }
        if (hscroll != 0 || vscroll != 0) {
            mPointerController->unfade();
        }
    } // release lock

    // Moving an external trackball or mouse should wake the device.
    // We don't do this for internal cursor devices to prevent them from waking up
    // the device in your pocket.
    // TODO: Use the input device configuration to control this behavior more finely.
    uint32_t policyFlags = 0;
    if (getDevice()->isExternal()) {
        policyFlags |= POLICY_FLAG_WAKE_DROPPED;
    }

    int32_t metaState = mContext->getGlobalMetaState();
    int32_t pointerId = 0;
    getDispatcher()->notifyMotion(when, getDeviceId(), mSource, policyFlags,
            motionEventAction, 0, metaState, motionEventEdgeFlags,
            1, &pointerId, &pointerCoords, mXPrecision, mYPrecision, downTime);

    // Send hover move after UP to tell the application that the mouse is hovering now.
    if (motionEventAction == AMOTION_EVENT_ACTION_UP
            && mPointerController != NULL) {
        getDispatcher()->notifyMotion(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_HOVER_MOVE, 0, metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
                1, &pointerId, &pointerCoords, mXPrecision, mYPrecision, downTime);
    }

    // Send scroll events.
    if (vscroll != 0 || hscroll != 0) {
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_VSCROLL, vscroll);
        pointerCoords.setAxisValue(AMOTION_EVENT_AXIS_HSCROLL, hscroll);

        getDispatcher()->notifyMotion(when, getDeviceId(), mSource, policyFlags,
                AMOTION_EVENT_ACTION_SCROLL, 0, metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
                1, &pointerId, &pointerCoords, mXPrecision, mYPrecision, downTime);
    }

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
            mPointerController->fade();
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

        dump.appendFormat(INDENT3 "Last Touch:\n");
        dump.appendFormat(INDENT4 "Pointer Count: %d\n", mLastTouch.pointerCount);
        dump.appendFormat(INDENT4 "Button State: 0x%08x\n", mLastTouch.buttonState);

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
            dump.appendFormat(INDENT4 "MaxSwipeWidthSquared: %d\n",
                    mLocked.pointerGestureMaxSwipeWidthSquared);
        }
    } // release lock
}

void TouchInputMapper::initializeLocked() {
    mCurrentTouch.clear();
    mLastTouch.clear();
    mDownTime = 0;

    for (uint32_t i = 0; i < MAX_POINTERS; i++) {
        mAveragingTouchFilter.historyStart[i] = 0;
        mAveragingTouchFilter.historyEnd[i] = 0;
    }

    mJumpyTouchFilter.jumpyPointsDropped = 0;

    mLocked.currentVirtualKey.down = false;

    mLocked.orientedRanges.havePressure = false;
    mLocked.orientedRanges.haveSize = false;
    mLocked.orientedRanges.haveTouchSize = false;
    mLocked.orientedRanges.haveToolSize = false;
    mLocked.orientedRanges.haveOrientation = false;

    mPointerGesture.reset();
}

void TouchInputMapper::configure() {
    InputMapper::configure();

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

void TouchInputMapper::configureParameters() {
    mParameters.useBadTouchFilter = getPolicy()->filterTouchEvents();
    mParameters.useAveragingTouchFilter = getPolicy()->filterTouchEvents();
    mParameters.useJumpyTouchFilter = getPolicy()->filterJumpyTouchEvents();
    mParameters.virtualKeyQuietTime = getPolicy()->getVirtualKeyQuietTime();

    if (getEventHub()->hasRelativeAxis(getDeviceId(), REL_X)
            || getEventHub()->hasRelativeAxis(getDeviceId(), REL_Y)) {
        // The device is a cursor device with a touch pad attached.
        // By default don't use the touch pad to move the pointer.
        mParameters.deviceType = Parameters::DEVICE_TYPE_TOUCH_PAD;
    } else {
        // The device is just a touch pad.
        // By default use the touch pad to move the pointer and to perform related gestures.
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
        } else {
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

    dump.appendFormat(INDENT4 "UseBadTouchFilter: %s\n",
            toString(mParameters.useBadTouchFilter));
    dump.appendFormat(INDENT4 "UseAveragingTouchFilter: %s\n",
            toString(mParameters.useAveragingTouchFilter));
    dump.appendFormat(INDENT4 "UseJumpyTouchFilter: %s\n",
            toString(mParameters.useJumpyTouchFilter));
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
        float diagonalSize = pythag(width, height);

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

            mLocked.orientedRanges.orientation.axis = AMOTION_EVENT_AXIS_ORIENTATION;
            mLocked.orientedRanges.orientation.source = mTouchSource;
            mLocked.orientedRanges.orientation.min = - M_PI_2;
            mLocked.orientedRanges.orientation.max = M_PI_2;
            mLocked.orientedRanges.orientation.flat = 0;
            mLocked.orientedRanges.orientation.fuzz = 0;
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

            // Scale movements such that one whole swipe of the touch pad covers a portion
            // of the display along whichever axis of the touch pad is longer.
            // Assume that the touch pad has a square aspect ratio such that movements in
            // X and Y of the same number of raw units cover the same physical distance.
            const float scaleFactor = 0.8f;

            mLocked.pointerGestureXMovementScale = rawWidth > rawHeight
                    ? scaleFactor * float(mLocked.associatedDisplayWidth) / rawWidth
                    : scaleFactor * float(mLocked.associatedDisplayHeight) / rawHeight;
            mLocked.pointerGestureYMovementScale = mLocked.pointerGestureXMovementScale;

            // Scale zooms to cover a smaller range of the display than movements do.
            // This value determines the area around the pointer that is affected by freeform
            // pointer gestures.
            mLocked.pointerGestureXZoomScale = mLocked.pointerGestureXMovementScale * 0.4f;
            mLocked.pointerGestureYZoomScale = mLocked.pointerGestureYMovementScale * 0.4f;

            // Max width between pointers to detect a swipe gesture is 3/4 of the short
            // axis of the touch pad.  Touches that are wider than this are translated
            // into freeform gestures.
            mLocked.pointerGestureMaxSwipeWidthSquared = min(rawWidth, rawHeight) * 3 / 4;
            mLocked.pointerGestureMaxSwipeWidthSquared *=
                    mLocked.pointerGestureMaxSwipeWidthSquared;
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
    if (mParameters.useBadTouchFilter) {
        if (applyBadTouchFilter()) {
            havePointerIds = false;
        }
    }

    if (mParameters.useJumpyTouchFilter) {
        if (applyJumpyTouchFilter()) {
            havePointerIds = false;
        }
    }

    if (!havePointerIds) {
        calculatePointerIds();
    }

    TouchData temp;
    TouchData* savedTouch;
    if (mParameters.useAveragingTouchFilter) {
        temp.copyFrom(mCurrentTouch);
        savedTouch = & temp;

        applyAveragingTouchFilter();
    } else {
        savedTouch = & mCurrentTouch;
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

    // Process touches and virtual keys.
    TouchResult touchResult = consumeOffScreenTouches(when, policyFlags);
    if (touchResult == DISPATCH_TOUCH) {
        suppressSwipeOntoVirtualKeys(when);
        if (mPointerController != NULL) {
            dispatchPointerGestures(when, policyFlags);
        }
        dispatchTouches(when, policyFlags);
    }

    // Copy current touch to last touch in preparation for the next cycle.
    // Keep the button state so we can track edge-triggered button state changes.
    if (touchResult == DROP_STROKE) {
        mLastTouch.clear();
        mLastTouch.buttonState = savedTouch->buttonState;
    } else {
        mLastTouch.copyFrom(*savedTouch);
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
    if (mParameters.virtualKeyQuietTime > 0 && mCurrentTouch.pointerCount != 0) {
        mContext->disableVirtualKeysUntil(when + mParameters.virtualKeyQuietTime);
    }
}

void TouchInputMapper::dispatchTouches(nsecs_t when, uint32_t policyFlags) {
    uint32_t currentPointerCount = mCurrentTouch.pointerCount;
    uint32_t lastPointerCount = mLastTouch.pointerCount;
    if (currentPointerCount == 0 && lastPointerCount == 0) {
        return; // nothing to do!
    }

    // Update current touch coordinates.
    int32_t edgeFlags;
    float xPrecision, yPrecision;
    prepareTouches(&edgeFlags, &xPrecision, &yPrecision);

    // Dispatch motions.
    BitSet32 currentIdBits = mCurrentTouch.idBits;
    BitSet32 lastIdBits = mLastTouch.idBits;
    uint32_t metaState = getContext()->getGlobalMetaState();

    if (currentIdBits == lastIdBits) {
        // No pointer id changes so this is a move event.
        // The dispatcher takes care of batching moves so we don't have to deal with that here.
        dispatchMotion(when, policyFlags, mTouchSource,
                AMOTION_EVENT_ACTION_MOVE, 0, metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
                mCurrentTouchCoords, mCurrentTouch.idToIndex, currentIdBits, -1,
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
        bool moveNeeded = updateMovedPointerCoords(
                mCurrentTouchCoords, mCurrentTouch.idToIndex,
                mLastTouchCoords, mLastTouch.idToIndex,
                moveIdBits);

        // Dispatch pointer up events.
        while (!upIdBits.isEmpty()) {
            uint32_t upId = upIdBits.firstMarkedBit();
            upIdBits.clearBit(upId);

            dispatchMotion(when, policyFlags, mTouchSource,
                    AMOTION_EVENT_ACTION_POINTER_UP, 0, metaState, 0,
                    mLastTouchCoords, mLastTouch.idToIndex, dispatchedIdBits, upId,
                    xPrecision, yPrecision, mDownTime);
            dispatchedIdBits.clearBit(upId);
        }

        // Dispatch move events if any of the remaining pointers moved from their old locations.
        // Although applications receive new locations as part of individual pointer up
        // events, they do not generally handle them except when presented in a move event.
        if (moveNeeded) {
            LOG_ASSERT(moveIdBits.value == dispatchedIdBits.value);
            dispatchMotion(when, policyFlags, mTouchSource,
                    AMOTION_EVENT_ACTION_MOVE, 0, metaState, 0,
                    mCurrentTouchCoords, mCurrentTouch.idToIndex, dispatchedIdBits, -1,
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
            } else {
                // Only send edge flags with first pointer down.
                edgeFlags = AMOTION_EVENT_EDGE_FLAG_NONE;
            }

            dispatchMotion(when, policyFlags, mTouchSource,
                    AMOTION_EVENT_ACTION_POINTER_DOWN, 0, metaState, edgeFlags,
                    mCurrentTouchCoords, mCurrentTouch.idToIndex, dispatchedIdBits, downId,
                    xPrecision, yPrecision, mDownTime);
        }
    }

    // Update state for next time.
    for (uint32_t i = 0; i < currentPointerCount; i++) {
        mLastTouchCoords[i].copyFrom(mCurrentTouchCoords[i]);
    }
}

void TouchInputMapper::prepareTouches(int32_t* outEdgeFlags,
        float* outXPrecision, float* outYPrecision) {
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
                float scale = 1.0f + pythag(c1, c2) / 16.0f;
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
    }

    // Check edge flags by looking only at the first pointer since the flags are
    // global to the event.
    *outEdgeFlags = AMOTION_EVENT_EDGE_FLAG_NONE;
    if (lastPointerCount == 0 && currentPointerCount > 0) {
        const PointerData& in = mCurrentTouch.pointers[0];

        if (in.x <= mRawAxes.x.minValue) {
            *outEdgeFlags |= rotateEdgeFlag(AMOTION_EVENT_EDGE_FLAG_LEFT,
                    mLocked.surfaceOrientation);
        } else if (in.x >= mRawAxes.x.maxValue) {
            *outEdgeFlags |= rotateEdgeFlag(AMOTION_EVENT_EDGE_FLAG_RIGHT,
                    mLocked.surfaceOrientation);
        }
        if (in.y <= mRawAxes.y.minValue) {
            *outEdgeFlags |= rotateEdgeFlag(AMOTION_EVENT_EDGE_FLAG_TOP,
                    mLocked.surfaceOrientation);
        } else if (in.y >= mRawAxes.y.maxValue) {
            *outEdgeFlags |= rotateEdgeFlag(AMOTION_EVENT_EDGE_FLAG_BOTTOM,
                    mLocked.surfaceOrientation);
        }
    }

    *outXPrecision = mLocked.orientedXPrecision;
    *outYPrecision = mLocked.orientedYPrecision;
}

void TouchInputMapper::dispatchPointerGestures(nsecs_t when, uint32_t policyFlags) {
    // Update current gesture coordinates.
    bool cancelPreviousGesture, finishPreviousGesture;
    preparePointerGestures(when, &cancelPreviousGesture, &finishPreviousGesture);

    // Send events!
    uint32_t metaState = getContext()->getGlobalMetaState();

    // Update last coordinates of pointers that have moved so that we observe the new
    // pointer positions at the same time as other pointers that have just gone up.
    bool down = mPointerGesture.currentGestureMode == PointerGesture::CLICK_OR_DRAG
            || mPointerGesture.currentGestureMode == PointerGesture::SWIPE
            || mPointerGesture.currentGestureMode == PointerGesture::FREEFORM;
    bool moveNeeded = false;
    if (down && !cancelPreviousGesture && !finishPreviousGesture
            && mPointerGesture.lastGesturePointerCount != 0
            && mPointerGesture.currentGesturePointerCount != 0) {
        BitSet32 movedGestureIdBits(mPointerGesture.currentGestureIdBits.value
                & mPointerGesture.lastGestureIdBits.value);
        moveNeeded = updateMovedPointerCoords(
                mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                mPointerGesture.lastGestureCoords, mPointerGesture.lastGestureIdToIndex,
                movedGestureIdBits);
    }

    // Send motion events for all pointers that went up or were canceled.
    BitSet32 dispatchedGestureIdBits(mPointerGesture.lastGestureIdBits);
    if (!dispatchedGestureIdBits.isEmpty()) {
        if (cancelPreviousGesture) {
            dispatchMotion(when, policyFlags, mPointerSource,
                    AMOTION_EVENT_ACTION_CANCEL, 0, metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
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
                        metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
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
                AMOTION_EVENT_ACTION_MOVE, 0, metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
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

            int32_t edgeFlags = AMOTION_EVENT_EDGE_FLAG_NONE;
            if (dispatchedGestureIdBits.count() == 1) {
                // First pointer is going down.  Calculate edge flags and set down time.
                uint32_t index = mPointerGesture.currentGestureIdToIndex[id];
                const PointerCoords& downCoords = mPointerGesture.currentGestureCoords[index];
                edgeFlags = calculateEdgeFlagsUsingPointerBounds(mPointerController,
                        downCoords.getAxisValue(AMOTION_EVENT_AXIS_X),
                        downCoords.getAxisValue(AMOTION_EVENT_AXIS_Y));
                mPointerGesture.downTime = when;
            }

            dispatchMotion(when, policyFlags, mPointerSource,
                    AMOTION_EVENT_ACTION_POINTER_DOWN, 0, metaState, edgeFlags,
                    mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                    dispatchedGestureIdBits, id,
                    0, 0, mPointerGesture.downTime);
        }
    }

    // Send down and up for a tap.
    if (mPointerGesture.currentGestureMode == PointerGesture::TAP) {
        const PointerCoords& coords = mPointerGesture.currentGestureCoords[0];
        int32_t edgeFlags = calculateEdgeFlagsUsingPointerBounds(mPointerController,
                coords.getAxisValue(AMOTION_EVENT_AXIS_X),
                coords.getAxisValue(AMOTION_EVENT_AXIS_Y));
        nsecs_t downTime = mPointerGesture.downTime = mPointerGesture.tapTime;
        mPointerGesture.resetTapTime();

        dispatchMotion(downTime, policyFlags, mPointerSource,
                AMOTION_EVENT_ACTION_DOWN, 0, metaState, edgeFlags,
                mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                mPointerGesture.currentGestureIdBits, -1,
                0, 0, downTime);
        dispatchMotion(when, policyFlags, mPointerSource,
                AMOTION_EVENT_ACTION_UP, 0, metaState, edgeFlags,
                mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                mPointerGesture.currentGestureIdBits, -1,
                0, 0, downTime);
    }

    // Send motion events for hover.
    if (mPointerGesture.currentGestureMode == PointerGesture::HOVER) {
        dispatchMotion(when, policyFlags, mPointerSource,
                AMOTION_EVENT_ACTION_HOVER_MOVE, 0, metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
                mPointerGesture.currentGestureCoords, mPointerGesture.currentGestureIdToIndex,
                mPointerGesture.currentGestureIdBits, -1,
                0, 0, mPointerGesture.downTime);
    }

    // Update state.
    mPointerGesture.lastGestureMode = mPointerGesture.currentGestureMode;
    if (!down) {
        mPointerGesture.lastGesturePointerCount = 0;
        mPointerGesture.lastGestureIdBits.clear();
    } else {
        uint32_t currentGesturePointerCount = mPointerGesture.currentGesturePointerCount;
        mPointerGesture.lastGesturePointerCount = currentGesturePointerCount;
        mPointerGesture.lastGestureIdBits = mPointerGesture.currentGestureIdBits;
        for (BitSet32 idBits(mPointerGesture.currentGestureIdBits); !idBits.isEmpty(); ) {
            uint32_t id = idBits.firstMarkedBit();
            idBits.clearBit(id);
            uint32_t index = mPointerGesture.currentGestureIdToIndex[id];
            mPointerGesture.lastGestureCoords[index].copyFrom(
                    mPointerGesture.currentGestureCoords[index]);
            mPointerGesture.lastGestureIdToIndex[id] = index;
        }
    }
}

void TouchInputMapper::preparePointerGestures(nsecs_t when,
        bool* outCancelPreviousGesture, bool* outFinishPreviousGesture) {
    *outCancelPreviousGesture = false;
    *outFinishPreviousGesture = false;

    AutoMutex _l(mLock);

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
    // We always switch to the newest pointer down because that's usually where the user's
    // attention is focused.
    int32_t activeTouchId;
    BitSet32 downTouchIdBits(mCurrentTouch.idBits.value & ~mLastTouch.idBits.value);
    if (!downTouchIdBits.isEmpty()) {
        activeTouchId = mPointerGesture.activeTouchId = downTouchIdBits.firstMarkedBit();
    } else {
        activeTouchId = mPointerGesture.activeTouchId;
        if (activeTouchId < 0 || !mCurrentTouch.idBits.hasBit(activeTouchId)) {
            if (!mCurrentTouch.idBits.isEmpty()) {
                activeTouchId = mPointerGesture.activeTouchId =
                        mCurrentTouch.idBits.firstMarkedBit();
            } else {
                activeTouchId = mPointerGesture.activeTouchId = -1;
            }
        }
    }

    // Update the touch origin data to track where each finger originally went down.
    if (mCurrentTouch.pointerCount == 0 || mPointerGesture.touchOrigin.pointerCount == 0) {
        // Fast path when all fingers have gone up or down.
        mPointerGesture.touchOrigin.copyFrom(mCurrentTouch);
    } else {
        // Slow path when only some fingers have gone up or down.
        for (BitSet32 idBits(mPointerGesture.touchOrigin.idBits.value
                & ~mCurrentTouch.idBits.value); !idBits.isEmpty(); ) {
            uint32_t id = idBits.firstMarkedBit();
            idBits.clearBit(id);
            mPointerGesture.touchOrigin.idBits.clearBit(id);
            uint32_t index = mPointerGesture.touchOrigin.idToIndex[id];
            uint32_t count = --mPointerGesture.touchOrigin.pointerCount;
            while (index < count) {
                mPointerGesture.touchOrigin.pointers[index] =
                        mPointerGesture.touchOrigin.pointers[index + 1];
                uint32_t movedId = mPointerGesture.touchOrigin.pointers[index].id;
                mPointerGesture.touchOrigin.idToIndex[movedId] = index;
                index += 1;
            }
        }
        for (BitSet32 idBits(mCurrentTouch.idBits.value
                & ~mPointerGesture.touchOrigin.idBits.value); !idBits.isEmpty(); ) {
            uint32_t id = idBits.firstMarkedBit();
            idBits.clearBit(id);
            mPointerGesture.touchOrigin.idBits.markBit(id);
            uint32_t index = mPointerGesture.touchOrigin.pointerCount++;
            mPointerGesture.touchOrigin.pointers[index] =
                    mCurrentTouch.pointers[mCurrentTouch.idToIndex[id]];
            mPointerGesture.touchOrigin.idToIndex[id] = index;
        }
    }

    // Determine whether we are in quiet time.
    bool isQuietTime = when < mPointerGesture.quietTime + QUIET_INTERVAL;
    if (!isQuietTime) {
        if ((mPointerGesture.lastGestureMode == PointerGesture::SWIPE
                || mPointerGesture.lastGestureMode == PointerGesture::FREEFORM)
                && mCurrentTouch.pointerCount < 2) {
            // Enter quiet time when exiting swipe or freeform state.
            // This is to prevent accidentally entering the hover state and flinging the
            // pointer when finishing a swipe and there is still one pointer left onscreen.
            isQuietTime = true;
        } else if (mPointerGesture.lastGestureMode == PointerGesture::CLICK_OR_DRAG
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

    // Switch states based on button and pointer state.
    if (isQuietTime) {
        // Case 1: Quiet time. (QUIET)
#if DEBUG_GESTURES
        LOGD("Gestures: QUIET for next %0.3fms",
                (mPointerGesture.quietTime + QUIET_INTERVAL - when) * 0.000001f);
#endif
        *outFinishPreviousGesture = true;

        mPointerGesture.activeGestureId = -1;
        mPointerGesture.currentGestureMode = PointerGesture::QUIET;
        mPointerGesture.currentGesturePointerCount = 0;
        mPointerGesture.currentGestureIdBits.clear();
    } else if (isPointerDown(mCurrentTouch.buttonState)) {
        // Case 2: Button is pressed. (DRAG)
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
        LOGD("Gestures: CLICK_OR_DRAG activeTouchId=%d, "
                "currentTouchPointerCount=%d", activeTouchId, mCurrentTouch.pointerCount);
#endif
        // Reset state when just starting.
        if (mPointerGesture.lastGestureMode != PointerGesture::CLICK_OR_DRAG) {
            *outFinishPreviousGesture = true;
            mPointerGesture.activeGestureId = 0;
        }

        // Switch pointers if needed.
        // Find the fastest pointer and follow it.
        if (activeTouchId >= 0) {
            if (mCurrentTouch.pointerCount > 1) {
                int32_t bestId = -1;
                float bestSpeed = DRAG_MIN_SWITCH_SPEED;
                for (uint32_t i = 0; i < mCurrentTouch.pointerCount; i++) {
                    uint32_t id = mCurrentTouch.pointers[i].id;
                    float vx, vy;
                    if (mPointerGesture.velocityTracker.getVelocity(id, &vx, &vy)) {
                        float speed = pythag(vx, vy);
                        if (speed > bestSpeed) {
                            bestId = id;
                            bestSpeed = speed;
                        }
                    }
                }
                if (bestId >= 0 && bestId != activeTouchId) {
                    mPointerGesture.activeTouchId = activeTouchId = bestId;
#if DEBUG_GESTURES
                    LOGD("Gestures: CLICK_OR_DRAG switched pointers, "
                            "bestId=%d, bestSpeed=%0.3f", bestId, bestSpeed);
#endif
                }
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
                mPointerController->move(deltaX, deltaY);
            }
        }

        float x, y;
        mPointerController->getPosition(&x, &y);

        mPointerGesture.currentGestureMode = PointerGesture::CLICK_OR_DRAG;
        mPointerGesture.currentGesturePointerCount = 1;
        mPointerGesture.currentGestureIdBits.clear();
        mPointerGesture.currentGestureIdBits.markBit(mPointerGesture.activeGestureId);
        mPointerGesture.currentGestureIdToIndex[mPointerGesture.activeGestureId] = 0;
        mPointerGesture.currentGestureCoords[0].clear();
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, x);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 1.0f);
    } else if (mCurrentTouch.pointerCount == 0) {
        // Case 3. No fingers down and button is not pressed. (NEUTRAL)
        *outFinishPreviousGesture = true;

        // Watch for taps coming out of HOVER or INDETERMINATE_MULTITOUCH mode.
        bool tapped = false;
        if (mPointerGesture.lastGestureMode == PointerGesture::HOVER
                || mPointerGesture.lastGestureMode
                        == PointerGesture::INDETERMINATE_MULTITOUCH) {
            if (when <= mPointerGesture.tapTime + TAP_INTERVAL) {
                float x, y;
                mPointerController->getPosition(&x, &y);
                if (fabs(x - mPointerGesture.initialPointerX) <= TAP_SLOP
                        && fabs(y - mPointerGesture.initialPointerY) <= TAP_SLOP) {
#if DEBUG_GESTURES
                    LOGD("Gestures: TAP");
#endif
                    mPointerGesture.activeGestureId = 0;
                    mPointerGesture.currentGestureMode = PointerGesture::TAP;
                    mPointerGesture.currentGesturePointerCount = 1;
                    mPointerGesture.currentGestureIdBits.clear();
                    mPointerGesture.currentGestureIdBits.markBit(
                            mPointerGesture.activeGestureId);
                    mPointerGesture.currentGestureIdToIndex[
                            mPointerGesture.activeGestureId] = 0;
                    mPointerGesture.currentGestureCoords[0].clear();
                    mPointerGesture.currentGestureCoords[0].setAxisValue(
                            AMOTION_EVENT_AXIS_X, mPointerGesture.initialPointerX);
                    mPointerGesture.currentGestureCoords[0].setAxisValue(
                            AMOTION_EVENT_AXIS_Y, mPointerGesture.initialPointerY);
                    mPointerGesture.currentGestureCoords[0].setAxisValue(
                            AMOTION_EVENT_AXIS_PRESSURE, 1.0f);
                    tapped = true;
                } else {
#if DEBUG_GESTURES
                    LOGD("Gestures: Not a TAP, deltaX=%f, deltaY=%f",
                            x - mPointerGesture.initialPointerX,
                            y - mPointerGesture.initialPointerY);
#endif
                }
            } else {
#if DEBUG_GESTURES
                LOGD("Gestures: Not a TAP, delay=%lld",
                        when - mPointerGesture.tapTime);
#endif
            }
        }
        if (!tapped) {
#if DEBUG_GESTURES
            LOGD("Gestures: NEUTRAL");
#endif
            mPointerGesture.activeGestureId = -1;
            mPointerGesture.currentGestureMode = PointerGesture::NEUTRAL;
            mPointerGesture.currentGesturePointerCount = 0;
            mPointerGesture.currentGestureIdBits.clear();
        }
    } else if (mCurrentTouch.pointerCount == 1) {
        // Case 4. Exactly one finger down, button is not pressed. (HOVER)
        // The pointer follows the active touch point.
        // Emit HOVER_MOVE events at the pointer location.
        LOG_ASSERT(activeTouchId >= 0);

#if DEBUG_GESTURES
        LOGD("Gestures: HOVER");
#endif

        if (mLastTouch.idBits.hasBit(activeTouchId)) {
            const PointerData& currentPointer =
                    mCurrentTouch.pointers[mCurrentTouch.idToIndex[activeTouchId]];
            const PointerData& lastPointer =
                    mLastTouch.pointers[mLastTouch.idToIndex[activeTouchId]];
            float deltaX = (currentPointer.x - lastPointer.x)
                    * mLocked.pointerGestureXMovementScale;
            float deltaY = (currentPointer.y - lastPointer.y)
                    * mLocked.pointerGestureYMovementScale;
            mPointerController->move(deltaX, deltaY);
        }

        *outFinishPreviousGesture = true;
        mPointerGesture.activeGestureId = 0;

        float x, y;
        mPointerController->getPosition(&x, &y);

        mPointerGesture.currentGestureMode = PointerGesture::HOVER;
        mPointerGesture.currentGesturePointerCount = 1;
        mPointerGesture.currentGestureIdBits.clear();
        mPointerGesture.currentGestureIdBits.markBit(mPointerGesture.activeGestureId);
        mPointerGesture.currentGestureIdToIndex[mPointerGesture.activeGestureId] = 0;
        mPointerGesture.currentGestureCoords[0].clear();
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, x);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, y);
        mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 0.0f);

        if (mLastTouch.pointerCount == 0 && mCurrentTouch.pointerCount != 0) {
            mPointerGesture.tapTime = when;
            mPointerGesture.initialPointerX = x;
            mPointerGesture.initialPointerY = y;
        }
    } else {
        // Case 5. At least two fingers down, button is not pressed. (SWIPE or FREEFORM
        // or INDETERMINATE_MULTITOUCH)
        // Initially we watch and wait for something interesting to happen so as to
        // avoid making a spurious guess as to the nature of the gesture.  For example,
        // the fingers may be in transition to some other state such as pressing or
        // releasing the button or we may be performing a two finger tap.
        //
        // Fix the centroid of the figure when the gesture actually starts.
        // We do not recalculate the centroid at any other time during the gesture because
        // it would affect the relationship of the touch points relative to the pointer location.
        LOG_ASSERT(activeTouchId >= 0);

        uint32_t currentTouchPointerCount = mCurrentTouch.pointerCount;
        if (currentTouchPointerCount > MAX_POINTERS) {
            currentTouchPointerCount = MAX_POINTERS;
        }

        if (mPointerGesture.lastGestureMode != PointerGesture::INDETERMINATE_MULTITOUCH
                && mPointerGesture.lastGestureMode != PointerGesture::SWIPE
                && mPointerGesture.lastGestureMode != PointerGesture::FREEFORM) {
            mPointerGesture.currentGestureMode = PointerGesture::INDETERMINATE_MULTITOUCH;

            *outFinishPreviousGesture = true;
            mPointerGesture.activeGestureId = -1;

            // Remember the initial pointer location.
            // Everything we do will be relative to this location.
            mPointerController->getPosition(&mPointerGesture.initialPointerX,
                    &mPointerGesture.initialPointerY);

            // Track taps.
            if (mLastTouch.pointerCount == 0 && mCurrentTouch.pointerCount != 0) {
                mPointerGesture.tapTime = when;
            }

            // Reset the touch origin to be relative to exactly where the fingers are now
            // in case they have moved some distance away as part of a previous gesture.
            // We want to know how far the fingers have traveled since we started considering
            // a multitouch gesture.
            mPointerGesture.touchOrigin.copyFrom(mCurrentTouch);
        } else {
            mPointerGesture.currentGestureMode = mPointerGesture.lastGestureMode;
        }

        if (mPointerGesture.currentGestureMode == PointerGesture::INDETERMINATE_MULTITOUCH) {
            // Wait for the pointers to start moving before doing anything.
            bool decideNow = true;
            for (uint32_t i = 0; i < currentTouchPointerCount; i++) {
                const PointerData& current = mCurrentTouch.pointers[i];
                const PointerData& origin = mPointerGesture.touchOrigin.pointers[
                        mPointerGesture.touchOrigin.idToIndex[current.id]];
                float distance = pythag(
                        (current.x - origin.x) * mLocked.pointerGestureXZoomScale,
                        (current.y - origin.y) * mLocked.pointerGestureYZoomScale);
                if (distance < MULTITOUCH_MIN_TRAVEL) {
                    decideNow = false;
                    break;
                }
            }

            if (decideNow) {
                mPointerGesture.currentGestureMode = PointerGesture::FREEFORM;
                if (currentTouchPointerCount == 2
                        && distanceSquared(
                                mCurrentTouch.pointers[0].x, mCurrentTouch.pointers[0].y,
                                mCurrentTouch.pointers[1].x, mCurrentTouch.pointers[1].y)
                                <= mLocked.pointerGestureMaxSwipeWidthSquared) {
                    const PointerData& current1 = mCurrentTouch.pointers[0];
                    const PointerData& current2 = mCurrentTouch.pointers[1];
                    const PointerData& origin1 = mPointerGesture.touchOrigin.pointers[
                            mPointerGesture.touchOrigin.idToIndex[current1.id]];
                    const PointerData& origin2 = mPointerGesture.touchOrigin.pointers[
                            mPointerGesture.touchOrigin.idToIndex[current2.id]];

                    float x1 = (current1.x - origin1.x) * mLocked.pointerGestureXZoomScale;
                    float y1 = (current1.y - origin1.y) * mLocked.pointerGestureYZoomScale;
                    float x2 = (current2.x - origin2.x) * mLocked.pointerGestureXZoomScale;
                    float y2 = (current2.y - origin2.y) * mLocked.pointerGestureYZoomScale;
                    float magnitude1 = pythag(x1, y1);
                    float magnitude2 = pythag(x2, y2);

                    // Calculate the dot product of the vectors.
                    // When the vectors are oriented in approximately the same direction,
                    // the angle betweeen them is near zero and the cosine of the angle
                    // approches 1.0.  Recall that dot(v1, v2) = cos(angle) * mag(v1) * mag(v2).
                    // We know that the magnitude is at least MULTITOUCH_MIN_TRAVEL because
                    // we checked it above.
                    float dot = x1 * x2 + y1 * y2;
                    float cosine = dot / (magnitude1 * magnitude2); // denominator always > 0
                    if (cosine > SWIPE_TRANSITION_ANGLE_COSINE) {
                        mPointerGesture.currentGestureMode = PointerGesture::SWIPE;
                    }
                }

                // Remember the initial centroid for the duration of the gesture.
                mPointerGesture.initialCentroidX = 0;
                mPointerGesture.initialCentroidY = 0;
                for (uint32_t i = 0; i < currentTouchPointerCount; i++) {
                    const PointerData& touch = mCurrentTouch.pointers[i];
                    mPointerGesture.initialCentroidX += touch.x;
                    mPointerGesture.initialCentroidY += touch.y;
                }
                mPointerGesture.initialCentroidX /= int32_t(currentTouchPointerCount);
                mPointerGesture.initialCentroidY /= int32_t(currentTouchPointerCount);

                mPointerGesture.activeGestureId = 0;
            }
        } else if (mPointerGesture.currentGestureMode == PointerGesture::SWIPE) {
            // Switch to FREEFORM if additional pointers go down.
            if (currentTouchPointerCount > 2) {
                *outCancelPreviousGesture = true;
                mPointerGesture.currentGestureMode = PointerGesture::FREEFORM;
            }
        }

        if (mPointerGesture.currentGestureMode == PointerGesture::SWIPE) {
            // SWIPE mode.
#if DEBUG_GESTURES
            LOGD("Gestures: SWIPE activeTouchId=%d,"
                    "activeGestureId=%d, currentTouchPointerCount=%d",
                    activeTouchId, mPointerGesture.activeGestureId, currentTouchPointerCount);
#endif
            LOG_ASSERT(mPointerGesture.activeGestureId >= 0);

            float x = (mCurrentTouch.pointers[0].x + mCurrentTouch.pointers[1].x
                    - mPointerGesture.initialCentroidX * 2) * 0.5f
                    * mLocked.pointerGestureXMovementScale + mPointerGesture.initialPointerX;
            float y = (mCurrentTouch.pointers[0].y + mCurrentTouch.pointers[1].y
                    - mPointerGesture.initialCentroidY * 2) * 0.5f
                    * mLocked.pointerGestureYMovementScale + mPointerGesture.initialPointerY;

            mPointerGesture.currentGesturePointerCount = 1;
            mPointerGesture.currentGestureIdBits.clear();
            mPointerGesture.currentGestureIdBits.markBit(mPointerGesture.activeGestureId);
            mPointerGesture.currentGestureIdToIndex[mPointerGesture.activeGestureId] = 0;
            mPointerGesture.currentGestureCoords[0].clear();
            mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, x);
            mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, y);
            mPointerGesture.currentGestureCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 1.0f);
        } else if (mPointerGesture.currentGestureMode == PointerGesture::FREEFORM) {
            // FREEFORM mode.
#if DEBUG_GESTURES
            LOGD("Gestures: FREEFORM activeTouchId=%d,"
                    "activeGestureId=%d, currentTouchPointerCount=%d",
                    activeTouchId, mPointerGesture.activeGestureId, currentTouchPointerCount);
#endif
            LOG_ASSERT(mPointerGesture.activeGestureId >= 0);

            mPointerGesture.currentGesturePointerCount = currentTouchPointerCount;
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

            for (uint32_t i = 0; i < currentTouchPointerCount; i++) {
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

                float x = (mCurrentTouch.pointers[i].x - mPointerGesture.initialCentroidX)
                        * mLocked.pointerGestureXZoomScale + mPointerGesture.initialPointerX;
                float y = (mCurrentTouch.pointers[i].y - mPointerGesture.initialCentroidY)
                        * mLocked.pointerGestureYZoomScale + mPointerGesture.initialPointerY;

                mPointerGesture.currentGestureCoords[i].clear();
                mPointerGesture.currentGestureCoords[i].setAxisValue(
                        AMOTION_EVENT_AXIS_X, x);
                mPointerGesture.currentGestureCoords[i].setAxisValue(
                        AMOTION_EVENT_AXIS_Y, y);
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
        } else {
            // INDETERMINATE_MULTITOUCH mode.
            // Do nothing.
#if DEBUG_GESTURES
            LOGD("Gestures: INDETERMINATE_MULTITOUCH");
#endif
        }
    }

    // Unfade the pointer if the user is doing anything with the touch pad.
    mPointerController->setButtonState(mCurrentTouch.buttonState);
    if (mCurrentTouch.buttonState || mCurrentTouch.pointerCount != 0) {
        mPointerController->unfade();
    }

#if DEBUG_GESTURES
    LOGD("Gestures: finishPreviousGesture=%s, cancelPreviousGesture=%s, "
            "currentGestureMode=%d, currentGesturePointerCount=%d, currentGestureIdBits=0x%08x, "
            "lastGestureMode=%d, lastGesturePointerCount=%d, lastGestureIdBits=0x%08x",
            toString(*outFinishPreviousGesture), toString(*outCancelPreviousGesture),
            mPointerGesture.currentGestureMode, mPointerGesture.currentGesturePointerCount,
            mPointerGesture.currentGestureIdBits.value,
            mPointerGesture.lastGestureMode, mPointerGesture.lastGesturePointerCount,
            mPointerGesture.lastGestureIdBits.value);
    for (BitSet32 idBits = mPointerGesture.currentGestureIdBits; !idBits.isEmpty(); ) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);
        uint32_t index = mPointerGesture.currentGestureIdToIndex[id];
        const PointerCoords& coords = mPointerGesture.currentGestureCoords[index];
        LOGD("  currentGesture[%d]: index=%d, x=%0.3f, y=%0.3f, pressure=%0.3f",
                id, index, coords.getAxisValue(AMOTION_EVENT_AXIS_X),
                coords.getAxisValue(AMOTION_EVENT_AXIS_Y),
                coords.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE));
    }
    for (BitSet32 idBits = mPointerGesture.lastGestureIdBits; !idBits.isEmpty(); ) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);
        uint32_t index = mPointerGesture.lastGestureIdToIndex[id];
        const PointerCoords& coords = mPointerGesture.lastGestureCoords[index];
        LOGD("  lastGesture[%d]: index=%d, x=%0.3f, y=%0.3f, pressure=%0.3f",
                id, index, coords.getAxisValue(AMOTION_EVENT_AXIS_X),
                coords.getAxisValue(AMOTION_EVENT_AXIS_Y),
                coords.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE));
    }
#endif
}

void TouchInputMapper::dispatchMotion(nsecs_t when, uint32_t policyFlags, uint32_t source,
        int32_t action, int32_t flags, uint32_t metaState, int32_t edgeFlags,
        const PointerCoords* coords, const uint32_t* idToIndex, BitSet32 idBits,
        int32_t changedId, float xPrecision, float yPrecision, nsecs_t downTime) {
    PointerCoords pointerCoords[MAX_POINTERS];
    int32_t pointerIds[MAX_POINTERS];
    uint32_t pointerCount = 0;
    while (!idBits.isEmpty()) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);
        uint32_t index = idToIndex[id];
        pointerIds[pointerCount] = id;
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
            action, flags, metaState, edgeFlags,
            pointerCount, pointerIds, pointerCoords, xPrecision, yPrecision, downTime);
}

bool TouchInputMapper::updateMovedPointerCoords(
        const PointerCoords* inCoords, const uint32_t* inIdToIndex,
        PointerCoords* outCoords, const uint32_t* outIdToIndex, BitSet32 idBits) const {
    bool changed = false;
    while (!idBits.isEmpty()) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);

        uint32_t inIndex = inIdToIndex[id];
        uint32_t outIndex = outIdToIndex[id];
        const PointerCoords& curInCoords = inCoords[inIndex];
        PointerCoords& curOutCoords = outCoords[outIndex];

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
            mPointerController->fade();
        }
    } // release lock
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

/* Special hack for devices that have bad screen data: if one of the
 * points has moved more than a screen height from the last position,
 * then drop it. */
bool TouchInputMapper::applyBadTouchFilter() {
    uint32_t pointerCount = mCurrentTouch.pointerCount;

    // Nothing to do if there are no points.
    if (pointerCount == 0) {
        return false;
    }

    // Don't do anything if a finger is going down or up.  We run
    // here before assigning pointer IDs, so there isn't a good
    // way to do per-finger matching.
    if (pointerCount != mLastTouch.pointerCount) {
        return false;
    }

    // We consider a single movement across more than a 7/16 of
    // the long size of the screen to be bad.  This was a magic value
    // determined by looking at the maximum distance it is feasible
    // to actually move in one sample.
    int32_t maxDeltaY = (mRawAxes.y.maxValue - mRawAxes.y.minValue + 1) * 7 / 16;

    // XXX The original code in InputDevice.java included commented out
    //     code for testing the X axis.  Note that when we drop a point
    //     we don't actually restore the old X either.  Strange.
    //     The old code also tries to track when bad points were previously
    //     detected but it turns out that due to the placement of a "break"
    //     at the end of the loop, we never set mDroppedBadPoint to true
    //     so it is effectively dead code.
    // Need to figure out if the old code is busted or just overcomplicated
    // but working as intended.

    // Look through all new points and see if any are farther than
    // acceptable from all previous points.
    for (uint32_t i = pointerCount; i-- > 0; ) {
        int32_t y = mCurrentTouch.pointers[i].y;
        int32_t closestY = INT_MAX;
        int32_t closestDeltaY = 0;

#if DEBUG_HACKS
        LOGD("BadTouchFilter: Looking at next point #%d: y=%d", i, y);
#endif

        for (uint32_t j = pointerCount; j-- > 0; ) {
            int32_t lastY = mLastTouch.pointers[j].y;
            int32_t deltaY = abs(y - lastY);

#if DEBUG_HACKS
            LOGD("BadTouchFilter: Comparing with last point #%d: y=%d deltaY=%d",
                    j, lastY, deltaY);
#endif

            if (deltaY < maxDeltaY) {
                goto SkipSufficientlyClosePoint;
            }
            if (deltaY < closestDeltaY) {
                closestDeltaY = deltaY;
                closestY = lastY;
            }
        }

        // Must not have found a close enough match.
#if DEBUG_HACKS
        LOGD("BadTouchFilter: Dropping bad point #%d: newY=%d oldY=%d deltaY=%d maxDeltaY=%d",
                i, y, closestY, closestDeltaY, maxDeltaY);
#endif

        mCurrentTouch.pointers[i].y = closestY;
        return true; // XXX original code only corrects one point

    SkipSufficientlyClosePoint: ;
    }

    // No change.
    return false;
}

/* Special hack for devices that have bad screen data: drop points where
 * the coordinate value for one axis has jumped to the other pointer's location.
 */
bool TouchInputMapper::applyJumpyTouchFilter() {
    uint32_t pointerCount = mCurrentTouch.pointerCount;
    if (mLastTouch.pointerCount != pointerCount) {
#if DEBUG_HACKS
        LOGD("JumpyTouchFilter: Different pointer count %d -> %d",
                mLastTouch.pointerCount, pointerCount);
        for (uint32_t i = 0; i < pointerCount; i++) {
            LOGD("  Pointer %d (%d, %d)", i,
                    mCurrentTouch.pointers[i].x, mCurrentTouch.pointers[i].y);
        }
#endif

        if (mJumpyTouchFilter.jumpyPointsDropped < JUMPY_TRANSITION_DROPS) {
            if (mLastTouch.pointerCount == 1 && pointerCount == 2) {
                // Just drop the first few events going from 1 to 2 pointers.
                // They're bad often enough that they're not worth considering.
                mCurrentTouch.pointerCount = 1;
                mJumpyTouchFilter.jumpyPointsDropped += 1;

#if DEBUG_HACKS
                LOGD("JumpyTouchFilter: Pointer 2 dropped");
#endif
                return true;
            } else if (mLastTouch.pointerCount == 2 && pointerCount == 1) {
                // The event when we go from 2 -> 1 tends to be messed up too
                mCurrentTouch.pointerCount = 2;
                mCurrentTouch.pointers[0] = mLastTouch.pointers[0];
                mCurrentTouch.pointers[1] = mLastTouch.pointers[1];
                mJumpyTouchFilter.jumpyPointsDropped += 1;

#if DEBUG_HACKS
                for (int32_t i = 0; i < 2; i++) {
                    LOGD("JumpyTouchFilter: Pointer %d replaced (%d, %d)", i,
                            mCurrentTouch.pointers[i].x, mCurrentTouch.pointers[i].y);
                }
#endif
                return true;
            }
        }
        // Reset jumpy points dropped on other transitions or if limit exceeded.
        mJumpyTouchFilter.jumpyPointsDropped = 0;

#if DEBUG_HACKS
        LOGD("JumpyTouchFilter: Transition - drop limit reset");
#endif
        return false;
    }

    // We have the same number of pointers as last time.
    // A 'jumpy' point is one where the coordinate value for one axis
    // has jumped to the other pointer's location. No need to do anything
    // else if we only have one pointer.
    if (pointerCount < 2) {
        return false;
    }

    if (mJumpyTouchFilter.jumpyPointsDropped < JUMPY_DROP_LIMIT) {
        int jumpyEpsilon = (mRawAxes.y.maxValue - mRawAxes.y.minValue + 1) / JUMPY_EPSILON_DIVISOR;

        // We only replace the single worst jumpy point as characterized by pointer distance
        // in a single axis.
        int32_t badPointerIndex = -1;
        int32_t badPointerReplacementIndex = -1;
        int32_t badPointerDistance = INT_MIN; // distance to be corrected

        for (uint32_t i = pointerCount; i-- > 0; ) {
            int32_t x = mCurrentTouch.pointers[i].x;
            int32_t y = mCurrentTouch.pointers[i].y;

#if DEBUG_HACKS
            LOGD("JumpyTouchFilter: Point %d (%d, %d)", i, x, y);
#endif

            // Check if a touch point is too close to another's coordinates
            bool dropX = false, dropY = false;
            for (uint32_t j = 0; j < pointerCount; j++) {
                if (i == j) {
                    continue;
                }

                if (abs(x - mCurrentTouch.pointers[j].x) <= jumpyEpsilon) {
                    dropX = true;
                    break;
                }

                if (abs(y - mCurrentTouch.pointers[j].y) <= jumpyEpsilon) {
                    dropY = true;
                    break;
                }
            }
            if (! dropX && ! dropY) {
                continue; // not jumpy
            }

            // Find a replacement candidate by comparing with older points on the
            // complementary (non-jumpy) axis.
            int32_t distance = INT_MIN; // distance to be corrected
            int32_t replacementIndex = -1;

            if (dropX) {
                // X looks too close.  Find an older replacement point with a close Y.
                int32_t smallestDeltaY = INT_MAX;
                for (uint32_t j = 0; j < pointerCount; j++) {
                    int32_t deltaY = abs(y - mLastTouch.pointers[j].y);
                    if (deltaY < smallestDeltaY) {
                        smallestDeltaY = deltaY;
                        replacementIndex = j;
                    }
                }
                distance = abs(x - mLastTouch.pointers[replacementIndex].x);
            } else {
                // Y looks too close.  Find an older replacement point with a close X.
                int32_t smallestDeltaX = INT_MAX;
                for (uint32_t j = 0; j < pointerCount; j++) {
                    int32_t deltaX = abs(x - mLastTouch.pointers[j].x);
                    if (deltaX < smallestDeltaX) {
                        smallestDeltaX = deltaX;
                        replacementIndex = j;
                    }
                }
                distance = abs(y - mLastTouch.pointers[replacementIndex].y);
            }

            // If replacing this pointer would correct a worse error than the previous ones
            // considered, then use this replacement instead.
            if (distance > badPointerDistance) {
                badPointerIndex = i;
                badPointerReplacementIndex = replacementIndex;
                badPointerDistance = distance;
            }
        }

        // Correct the jumpy pointer if one was found.
        if (badPointerIndex >= 0) {
#if DEBUG_HACKS
            LOGD("JumpyTouchFilter: Replacing bad pointer %d with (%d, %d)",
                    badPointerIndex,
                    mLastTouch.pointers[badPointerReplacementIndex].x,
                    mLastTouch.pointers[badPointerReplacementIndex].y);
#endif

            mCurrentTouch.pointers[badPointerIndex].x =
                    mLastTouch.pointers[badPointerReplacementIndex].x;
            mCurrentTouch.pointers[badPointerIndex].y =
                    mLastTouch.pointers[badPointerReplacementIndex].y;
            mJumpyTouchFilter.jumpyPointsDropped += 1;
            return true;
        }
    }

    mJumpyTouchFilter.jumpyPointsDropped = 0;
    return false;
}

/* Special hack for devices that have bad screen data: aggregate and
 * compute averages of the coordinate data, to reduce the amount of
 * jitter seen by applications. */
void TouchInputMapper::applyAveragingTouchFilter() {
    for (uint32_t currentIndex = 0; currentIndex < mCurrentTouch.pointerCount; currentIndex++) {
        uint32_t id = mCurrentTouch.pointers[currentIndex].id;
        int32_t x = mCurrentTouch.pointers[currentIndex].x;
        int32_t y = mCurrentTouch.pointers[currentIndex].y;
        int32_t pressure;
        switch (mCalibration.pressureSource) {
        case Calibration::PRESSURE_SOURCE_PRESSURE:
            pressure = mCurrentTouch.pointers[currentIndex].pressure;
            break;
        case Calibration::PRESSURE_SOURCE_TOUCH:
            pressure = mCurrentTouch.pointers[currentIndex].touchMajor;
            break;
        default:
            pressure = 1;
            break;
        }

        if (mLastTouch.idBits.hasBit(id)) {
            // Pointer was down before and is still down now.
            // Compute average over history trace.
            uint32_t start = mAveragingTouchFilter.historyStart[id];
            uint32_t end = mAveragingTouchFilter.historyEnd[id];

            int64_t deltaX = x - mAveragingTouchFilter.historyData[end].pointers[id].x;
            int64_t deltaY = y - mAveragingTouchFilter.historyData[end].pointers[id].y;
            uint64_t distance = uint64_t(deltaX * deltaX + deltaY * deltaY);

#if DEBUG_HACKS
            LOGD("AveragingTouchFilter: Pointer id %d - Distance from last sample: %lld",
                    id, distance);
#endif

            if (distance < AVERAGING_DISTANCE_LIMIT) {
                // Increment end index in preparation for recording new historical data.
                end += 1;
                if (end > AVERAGING_HISTORY_SIZE) {
                    end = 0;
                }

                // If the end index has looped back to the start index then we have filled
                // the historical trace up to the desired size so we drop the historical
                // data at the start of the trace.
                if (end == start) {
                    start += 1;
                    if (start > AVERAGING_HISTORY_SIZE) {
                        start = 0;
                    }
                }

                // Add the raw data to the historical trace.
                mAveragingTouchFilter.historyStart[id] = start;
                mAveragingTouchFilter.historyEnd[id] = end;
                mAveragingTouchFilter.historyData[end].pointers[id].x = x;
                mAveragingTouchFilter.historyData[end].pointers[id].y = y;
                mAveragingTouchFilter.historyData[end].pointers[id].pressure = pressure;

                // Average over all historical positions in the trace by total pressure.
                int32_t averagedX = 0;
                int32_t averagedY = 0;
                int32_t totalPressure = 0;
                for (;;) {
                    int32_t historicalX = mAveragingTouchFilter.historyData[start].pointers[id].x;
                    int32_t historicalY = mAveragingTouchFilter.historyData[start].pointers[id].y;
                    int32_t historicalPressure = mAveragingTouchFilter.historyData[start]
                            .pointers[id].pressure;

                    averagedX += historicalX * historicalPressure;
                    averagedY += historicalY * historicalPressure;
                    totalPressure += historicalPressure;

                    if (start == end) {
                        break;
                    }

                    start += 1;
                    if (start > AVERAGING_HISTORY_SIZE) {
                        start = 0;
                    }
                }

                if (totalPressure != 0) {
                    averagedX /= totalPressure;
                    averagedY /= totalPressure;

#if DEBUG_HACKS
                    LOGD("AveragingTouchFilter: Pointer id %d - "
                            "totalPressure=%d, averagedX=%d, averagedY=%d", id, totalPressure,
                            averagedX, averagedY);
#endif

                    mCurrentTouch.pointers[currentIndex].x = averagedX;
                    mCurrentTouch.pointers[currentIndex].y = averagedY;
                }
            } else {
#if DEBUG_HACKS
                LOGD("AveragingTouchFilter: Pointer id %d - Exceeded max distance", id);
#endif
            }
        } else {
#if DEBUG_HACKS
            LOGD("AveragingTouchFilter: Pointer id %d - Pointer went up", id);
#endif
        }

        // Reset pointer history.
        mAveragingTouchFilter.historyStart[id] = 0;
        mAveragingTouchFilter.historyEnd[id] = 0;
        mAveragingTouchFilter.historyData[0].pointers[id].x = x;
        mAveragingTouchFilter.historyData[0].pointers[id].y = y;
        mAveragingTouchFilter.historyData[0].pointers[id].pressure = pressure;
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
    initialize();
}

SingleTouchInputMapper::~SingleTouchInputMapper() {
}

void SingleTouchInputMapper::initialize() {
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

    initialize();
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
                uint32_t buttonState = getButtonStateForScanCode(rawEvent->scanCode);
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
        TouchInputMapper(device) {
    initialize();
}

MultiTouchInputMapper::~MultiTouchInputMapper() {
}

void MultiTouchInputMapper::initialize() {
    mAccumulator.clear();
    mButtonState = 0;
}

void MultiTouchInputMapper::reset() {
    TouchInputMapper::reset();

    initialize();
}

void MultiTouchInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_KEY: {
        if (mParameters.deviceType == Parameters::DEVICE_TYPE_POINTER) {
            uint32_t buttonState = getButtonStateForScanCode(rawEvent->scanCode);
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
        uint32_t pointerIndex = mAccumulator.pointerCount;
        Accumulator::Pointer* pointer = & mAccumulator.pointers[pointerIndex];

        switch (rawEvent->scanCode) {
        case ABS_MT_POSITION_X:
            pointer->fields |= Accumulator::FIELD_ABS_MT_POSITION_X;
            pointer->absMTPositionX = rawEvent->value;
            break;
        case ABS_MT_POSITION_Y:
            pointer->fields |= Accumulator::FIELD_ABS_MT_POSITION_Y;
            pointer->absMTPositionY = rawEvent->value;
            break;
        case ABS_MT_TOUCH_MAJOR:
            pointer->fields |= Accumulator::FIELD_ABS_MT_TOUCH_MAJOR;
            pointer->absMTTouchMajor = rawEvent->value;
            break;
        case ABS_MT_TOUCH_MINOR:
            pointer->fields |= Accumulator::FIELD_ABS_MT_TOUCH_MINOR;
            pointer->absMTTouchMinor = rawEvent->value;
            break;
        case ABS_MT_WIDTH_MAJOR:
            pointer->fields |= Accumulator::FIELD_ABS_MT_WIDTH_MAJOR;
            pointer->absMTWidthMajor = rawEvent->value;
            break;
        case ABS_MT_WIDTH_MINOR:
            pointer->fields |= Accumulator::FIELD_ABS_MT_WIDTH_MINOR;
            pointer->absMTWidthMinor = rawEvent->value;
            break;
        case ABS_MT_ORIENTATION:
            pointer->fields |= Accumulator::FIELD_ABS_MT_ORIENTATION;
            pointer->absMTOrientation = rawEvent->value;
            break;
        case ABS_MT_TRACKING_ID:
            pointer->fields |= Accumulator::FIELD_ABS_MT_TRACKING_ID;
            pointer->absMTTrackingId = rawEvent->value;
            break;
        case ABS_MT_PRESSURE:
            pointer->fields |= Accumulator::FIELD_ABS_MT_PRESSURE;
            pointer->absMTPressure = rawEvent->value;
            break;
        }
        break;
    }

    case EV_SYN:
        switch (rawEvent->scanCode) {
        case SYN_MT_REPORT: {
            // MultiTouch Sync: The driver has returned all data for *one* of the pointers.
            uint32_t pointerIndex = mAccumulator.pointerCount;

            if (mAccumulator.pointers[pointerIndex].fields) {
                if (pointerIndex == MAX_POINTERS) {
                    LOGW("MultiTouch device driver returned more than maximum of %d pointers.",
                            MAX_POINTERS);
                } else {
                    pointerIndex += 1;
                    mAccumulator.pointerCount = pointerIndex;
                }
            }

            mAccumulator.pointers[pointerIndex].clear();
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

    uint32_t inCount = mAccumulator.pointerCount;
    uint32_t outCount = 0;
    bool havePointerIds = true;

    mCurrentTouch.clear();

    for (uint32_t inIndex = 0; inIndex < inCount; inIndex++) {
        const Accumulator::Pointer& inPointer = mAccumulator.pointers[inIndex];
        uint32_t fields = inPointer.fields;

        if ((fields & REQUIRED_FIELDS) != REQUIRED_FIELDS) {
            // Some drivers send empty MT sync packets without X / Y to indicate a pointer up.
            // Drop this finger.
            continue;
        }

        PointerData& outPointer = mCurrentTouch.pointers[outCount];
        outPointer.x = inPointer.absMTPositionX;
        outPointer.y = inPointer.absMTPositionY;

        if (fields & Accumulator::FIELD_ABS_MT_PRESSURE) {
            if (inPointer.absMTPressure <= 0) {
                // Some devices send sync packets with X / Y but with a 0 pressure to indicate
                // a pointer going up.  Drop this finger.
                continue;
            }
            outPointer.pressure = inPointer.absMTPressure;
        } else {
            // Default pressure to 0 if absent.
            outPointer.pressure = 0;
        }

        if (fields & Accumulator::FIELD_ABS_MT_TOUCH_MAJOR) {
            if (inPointer.absMTTouchMajor <= 0) {
                // Some devices send sync packets with X / Y but with a 0 touch major to indicate
                // a pointer going up.  Drop this finger.
                continue;
            }
            outPointer.touchMajor = inPointer.absMTTouchMajor;
        } else {
            // Default touch area to 0 if absent.
            outPointer.touchMajor = 0;
        }

        if (fields & Accumulator::FIELD_ABS_MT_TOUCH_MINOR) {
            outPointer.touchMinor = inPointer.absMTTouchMinor;
        } else {
            // Assume touch area is circular.
            outPointer.touchMinor = outPointer.touchMajor;
        }

        if (fields & Accumulator::FIELD_ABS_MT_WIDTH_MAJOR) {
            outPointer.toolMajor = inPointer.absMTWidthMajor;
        } else {
            // Default tool area to 0 if absent.
            outPointer.toolMajor = 0;
        }

        if (fields & Accumulator::FIELD_ABS_MT_WIDTH_MINOR) {
            outPointer.toolMinor = inPointer.absMTWidthMinor;
        } else {
            // Assume tool area is circular.
            outPointer.toolMinor = outPointer.toolMajor;
        }

        if (fields & Accumulator::FIELD_ABS_MT_ORIENTATION) {
            outPointer.orientation = inPointer.absMTOrientation;
        } else {
            // Default orientation to vertical if absent.
            outPointer.orientation = 0;
        }

        // Assign pointer id using tracking id if available.
        if (havePointerIds) {
            if (fields & Accumulator::FIELD_ABS_MT_TRACKING_ID) {
                uint32_t id = uint32_t(inPointer.absMTTrackingId);

                if (id > MAX_POINTER_ID) {
#if DEBUG_POINTERS
                    LOGD("Pointers: Ignoring driver provided pointer id %d because "
                            "it is larger than max supported id %d",
                            id, MAX_POINTER_ID);
#endif
                    havePointerIds = false;
                }
                else {
                    outPointer.id = id;
                    mCurrentTouch.idToIndex[id] = outCount;
                    mCurrentTouch.idBits.markBit(id);
                }
            } else {
                havePointerIds = false;
            }
        }

        outCount += 1;
    }

    mCurrentTouch.pointerCount = outCount;

    mButtonState = (mButtonState | mAccumulator.buttonDown) & ~mAccumulator.buttonUp;
    mCurrentTouch.buttonState = mButtonState;

    syncTouch(when, havePointerIds);

    mAccumulator.clear();
}

void MultiTouchInputMapper::configureRawAxes() {
    TouchInputMapper::configureRawAxes();

    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_POSITION_X, & mRawAxes.x);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_POSITION_Y, & mRawAxes.y);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_TOUCH_MAJOR, & mRawAxes.touchMajor);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_TOUCH_MINOR, & mRawAxes.touchMinor);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_WIDTH_MAJOR, & mRawAxes.toolMajor);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_WIDTH_MINOR, & mRawAxes.toolMinor);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_ORIENTATION, & mRawAxes.orientation);
    getEventHub()->getAbsoluteAxisInfo(getDeviceId(), ABS_MT_PRESSURE, & mRawAxes.pressure);
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
        dump.appendFormat(INDENT4 "  rawAxis=%d, rawMin=%d, rawMax=%d, rawFlat=%d, rawFuzz=%d\n",
                mAxes.keyAt(i), axis.rawAxisInfo.minValue, axis.rawAxisInfo.maxValue,
                axis.rawAxisInfo.flat, axis.rawAxisInfo.fuzz);
    }
}

void JoystickInputMapper::configure() {
    InputMapper::configure();

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

    int32_t pointerId = 0;
    getDispatcher()->notifyMotion(when, getDeviceId(), AINPUT_SOURCE_JOYSTICK, policyFlags,
            AMOTION_EVENT_ACTION_MOVE, 0, metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
            1, &pointerId, &pointerCoords, 0, 0, 0);
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
