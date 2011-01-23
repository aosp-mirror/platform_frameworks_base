//
// Copyright 2010 The Android Open Source Project
//
// The input reader.
//
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

#include <cutils/log.h>
#include <ui/InputReader.h>

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

static inline const char* toString(bool value) {
    return value ? "true" : "false";
}


int32_t updateMetaState(int32_t keyCode, bool down, int32_t oldMetaState) {
    int32_t mask;
    switch (keyCode) {
    case AKEYCODE_ALT_LEFT:
        mask = AMETA_ALT_LEFT_ON;
        break;
    case AKEYCODE_ALT_RIGHT:
        mask = AMETA_ALT_RIGHT_ON;
        break;
    case AKEYCODE_SHIFT_LEFT:
        mask = AMETA_SHIFT_LEFT_ON;
        break;
    case AKEYCODE_SHIFT_RIGHT:
        mask = AMETA_SHIFT_RIGHT_ON;
        break;
    case AKEYCODE_SYM:
        mask = AMETA_SYM_ON;
        break;
    default:
        return oldMetaState;
    }

    int32_t newMetaState = down ? oldMetaState | mask : oldMetaState & ~ mask
            & ~ (AMETA_ALT_ON | AMETA_SHIFT_ON);

    if (newMetaState & (AMETA_ALT_LEFT_ON | AMETA_ALT_RIGHT_ON)) {
        newMetaState |= AMETA_ALT_ON;
    }

    if (newMetaState & (AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_RIGHT_ON)) {
        newMetaState |= AMETA_SHIFT_ON;
    }

    return newMetaState;
}

static const int32_t keyCodeRotationMap[][4] = {
        // key codes enumerated counter-clockwise with the original (unrotated) key first
        // no rotation,        90 degree rotation,  180 degree rotation, 270 degree rotation
        { AKEYCODE_DPAD_DOWN,   AKEYCODE_DPAD_RIGHT,  AKEYCODE_DPAD_UP,     AKEYCODE_DPAD_LEFT },
        { AKEYCODE_DPAD_RIGHT,  AKEYCODE_DPAD_UP,     AKEYCODE_DPAD_LEFT,   AKEYCODE_DPAD_DOWN },
        { AKEYCODE_DPAD_UP,     AKEYCODE_DPAD_LEFT,   AKEYCODE_DPAD_DOWN,   AKEYCODE_DPAD_RIGHT },
        { AKEYCODE_DPAD_LEFT,   AKEYCODE_DPAD_DOWN,   AKEYCODE_DPAD_RIGHT,  AKEYCODE_DPAD_UP },
};
static const int keyCodeRotationMapSize =
        sizeof(keyCodeRotationMap) / sizeof(keyCodeRotationMap[0]);

int32_t rotateKeyCode(int32_t keyCode, int32_t orientation) {
    if (orientation != InputReaderPolicyInterface::ROTATION_0) {
        for (int i = 0; i < keyCodeRotationMapSize; i++) {
            if (keyCode == keyCodeRotationMap[i][0]) {
                return keyCodeRotationMap[i][orientation];
            }
        }
    }
    return keyCode;
}

static inline bool sourcesMatchMask(uint32_t sources, uint32_t sourceMask) {
    return (sources & sourceMask & ~ AINPUT_SOURCE_CLASS_MASK) != 0;
}


// --- InputDeviceCalibration ---

InputDeviceCalibration::InputDeviceCalibration() {
}

void InputDeviceCalibration::clear() {
    mProperties.clear();
}

void InputDeviceCalibration::addProperty(const String8& key, const String8& value) {
    mProperties.add(key, value);
}

bool InputDeviceCalibration::tryGetProperty(const String8& key, String8& outValue) const {
    ssize_t index = mProperties.indexOfKey(key);
    if (index < 0) {
        return false;
    }

    outValue = mProperties.valueAt(index);
    return true;
}

bool InputDeviceCalibration::tryGetProperty(const String8& key, int32_t& outValue) const {
    String8 stringValue;
    if (! tryGetProperty(key, stringValue) || stringValue.length() == 0) {
        return false;
    }

    char* end;
    int value = strtol(stringValue.string(), & end, 10);
    if (*end != '\0') {
        LOGW("Input device calibration key '%s' has invalid value '%s'.  Expected an integer.",
                key.string(), stringValue.string());
        return false;
    }
    outValue = value;
    return true;
}

bool InputDeviceCalibration::tryGetProperty(const String8& key, float& outValue) const {
    String8 stringValue;
    if (! tryGetProperty(key, stringValue) || stringValue.length() == 0) {
        return false;
    }

    char* end;
    float value = strtof(stringValue.string(), & end);
    if (*end != '\0') {
        LOGW("Input device calibration key '%s' has invalid value '%s'.  Expected a float.",
                key.string(), stringValue.string());
        return false;
    }
    outValue = value;
    return true;
}


// --- InputReader ---

InputReader::InputReader(const sp<EventHubInterface>& eventHub,
        const sp<InputReaderPolicyInterface>& policy,
        const sp<InputDispatcherInterface>& dispatcher) :
        mEventHub(eventHub), mPolicy(policy), mDispatcher(dispatcher),
        mGlobalMetaState(0), mDisableVirtualKeysTimeout(-1) {
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
    RawEvent rawEvent;
    mEventHub->getEvent(& rawEvent);

#if DEBUG_RAW_EVENTS
    LOGD("Input event: device=0x%x type=0x%x scancode=%d keycode=%d value=%d",
            rawEvent.deviceId, rawEvent.type, rawEvent.scanCode, rawEvent.keyCode,
            rawEvent.value);
#endif

    process(& rawEvent);
}

void InputReader::process(const RawEvent* rawEvent) {
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
        consumeEvent(rawEvent);
        break;
    }
}

void InputReader::addDevice(int32_t deviceId) {
    String8 name = mEventHub->getDeviceName(deviceId);
    uint32_t classes = mEventHub->getDeviceClasses(deviceId);

    InputDevice* device = createDevice(deviceId, name, classes);
    device->configure();

    if (device->isIgnored()) {
        LOGI("Device added: id=0x%x, name=%s (ignored non-input device)", deviceId, name.string());
    } else {
        LOGI("Device added: id=0x%x, name=%s, sources=%08x", deviceId, name.string(),
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
        LOGI("Device removed: id=0x%x, name=%s (ignored non-input device)",
                device->getId(), device->getName().string());
    } else {
        LOGI("Device removed: id=0x%x, name=%s, sources=%08x",
                device->getId(), device->getName().string(), device->getSources());
    }

    device->reset();

    delete device;
}

InputDevice* InputReader::createDevice(int32_t deviceId, const String8& name, uint32_t classes) {
    InputDevice* device = new InputDevice(this, deviceId, name);

    const int32_t associatedDisplayId = 0; // FIXME: hardcoded for current single-display devices

    // Switch-like devices.
    if (classes & INPUT_DEVICE_CLASS_SWITCH) {
        device->addMapper(new SwitchInputMapper(device));
    }

    // Keyboard-like devices.
    uint32_t keyboardSources = 0;
    int32_t keyboardType = AINPUT_KEYBOARD_TYPE_NON_ALPHABETIC;
    if (classes & INPUT_DEVICE_CLASS_KEYBOARD) {
        keyboardSources |= AINPUT_SOURCE_KEYBOARD;
    }
    if (classes & INPUT_DEVICE_CLASS_ALPHAKEY) {
        keyboardType = AINPUT_KEYBOARD_TYPE_ALPHABETIC;
    }
    if (classes & INPUT_DEVICE_CLASS_DPAD) {
        keyboardSources |= AINPUT_SOURCE_DPAD;
    }

    if (keyboardSources != 0) {
        device->addMapper(new KeyboardInputMapper(device,
                associatedDisplayId, keyboardSources, keyboardType));
    }

    // Trackball-like devices.
    if (classes & INPUT_DEVICE_CLASS_TRACKBALL) {
        device->addMapper(new TrackballInputMapper(device, associatedDisplayId));
    }

    // Touchscreen-like devices.
    if (classes & INPUT_DEVICE_CLASS_TOUCHSCREEN_MT) {
        device->addMapper(new MultiTouchInputMapper(device, associatedDisplayId));
    } else if (classes & INPUT_DEVICE_CLASS_TOUCHSCREEN) {
        device->addMapper(new SingleTouchInputMapper(device, associatedDisplayId));
    }

    return device;
}

void InputReader::consumeEvent(const RawEvent* rawEvent) {
    int32_t deviceId = rawEvent->deviceId;

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

        device->process(rawEvent);
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
        mContext(context), mId(id), mName(name), mSources(0) {
}

InputDevice::~InputDevice() {
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        delete mMappers[i];
    }
    mMappers.clear();
}

static void dumpMotionRange(String8& dump, const InputDeviceInfo& deviceInfo,
        int32_t rangeType, const char* name) {
    const InputDeviceInfo::MotionRange* range = deviceInfo.getMotionRange(rangeType);
    if (range) {
        dump.appendFormat(INDENT3 "%s: min=%0.3f, max=%0.3f, flat=%0.3f, fuzz=%0.3f\n",
                name, range->min, range->max, range->flat, range->fuzz);
    }
}

void InputDevice::dump(String8& dump) {
    InputDeviceInfo deviceInfo;
    getDeviceInfo(& deviceInfo);

    dump.appendFormat(INDENT "Device 0x%x: %s\n", deviceInfo.getId(),
            deviceInfo.getName().string());
    dump.appendFormat(INDENT2 "Sources: 0x%08x\n", deviceInfo.getSources());
    dump.appendFormat(INDENT2 "KeyboardType: %d\n", deviceInfo.getKeyboardType());
    if (!deviceInfo.getMotionRanges().isEmpty()) {
        dump.append(INDENT2 "Motion Ranges:\n");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_X, "X");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_Y, "Y");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_PRESSURE, "Pressure");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_SIZE, "Size");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_TOUCH_MAJOR, "TouchMajor");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_TOUCH_MINOR, "TouchMinor");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_TOOL_MAJOR, "ToolMajor");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_TOOL_MINOR, "ToolMinor");
        dumpMotionRange(dump, deviceInfo, AINPUT_MOTION_RANGE_ORIENTATION, "Orientation");
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
        mContext->getPolicy()->getInputDeviceCalibration(mName, mCalibration);
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

void InputDevice::process(const RawEvent* rawEvent) {
    size_t numMappers = mMappers.size();
    for (size_t i = 0; i < numMappers; i++) {
        InputMapper* mapper = mMappers[i];
        mapper->process(rawEvent);
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

KeyboardInputMapper::KeyboardInputMapper(InputDevice* device, int32_t associatedDisplayId,
        uint32_t sources, int32_t keyboardType) :
        InputMapper(device), mAssociatedDisplayId(associatedDisplayId), mSources(sources),
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
    return mSources;
}

void KeyboardInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    info->setKeyboardType(mKeyboardType);
}

void KeyboardInputMapper::dump(String8& dump) {
    { // acquire lock
        AutoMutex _l(mLock);
        dump.append(INDENT2 "Keyboard Input Mapper:\n");
        dump.appendFormat(INDENT3 "AssociatedDisplayId: %d\n", mAssociatedDisplayId);
        dump.appendFormat(INDENT3 "KeyboardType: %d\n", mKeyboardType);
        dump.appendFormat(INDENT3 "KeyDowns: %d keys currently down\n", mLocked.keyDowns.size());
        dump.appendFormat(INDENT3 "MetaState: 0x%0x\n", mLocked.metaState);
        dump.appendFormat(INDENT3 "DownTime: %lld\n", mLocked.downTime);
    } // release lock
}

void KeyboardInputMapper::reset() {
    for (;;) {
        int32_t keyCode, scanCode;
        { // acquire lock
            AutoMutex _l(mLock);

            // Synthesize key up event on reset if keys are currently down.
            if (mLocked.keyDowns.isEmpty()) {
                initializeLocked();
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
        || (scanCode >= BTN_GAMEPAD && scanCode < BTN_DIGI);
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
            if (mAssociatedDisplayId >= 0) {
                int32_t orientation;
                if (! getPolicy()->getDisplayInfo(mAssociatedDisplayId, NULL, NULL, & orientation)) {
                    return;
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
                        && mContext->shouldDropVirtualKey(when, getDevice(), keyCode, scanCode)) {
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
        }

        downTime = mLocked.downTime;
    } // release lock

    if (metaStateChanged) {
        getContext()->updateGlobalMetaState();
    }

    getDispatcher()->notifyKey(when, getDeviceId(), AINPUT_SOURCE_KEYBOARD, policyFlags,
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


// --- TrackballInputMapper ---

TrackballInputMapper::TrackballInputMapper(InputDevice* device, int32_t associatedDisplayId) :
        InputMapper(device), mAssociatedDisplayId(associatedDisplayId) {
    mXPrecision = TRACKBALL_MOVEMENT_THRESHOLD;
    mYPrecision = TRACKBALL_MOVEMENT_THRESHOLD;
    mXScale = 1.0f / TRACKBALL_MOVEMENT_THRESHOLD;
    mYScale = 1.0f / TRACKBALL_MOVEMENT_THRESHOLD;

    initializeLocked();
}

TrackballInputMapper::~TrackballInputMapper() {
}

uint32_t TrackballInputMapper::getSources() {
    return AINPUT_SOURCE_TRACKBALL;
}

void TrackballInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    info->addMotionRange(AINPUT_MOTION_RANGE_X, -1.0f, 1.0f, 0.0f, mXScale);
    info->addMotionRange(AINPUT_MOTION_RANGE_Y, -1.0f, 1.0f, 0.0f, mYScale);
}

void TrackballInputMapper::dump(String8& dump) {
    { // acquire lock
        AutoMutex _l(mLock);
        dump.append(INDENT2 "Trackball Input Mapper:\n");
        dump.appendFormat(INDENT3 "AssociatedDisplayId: %d\n", mAssociatedDisplayId);
        dump.appendFormat(INDENT3 "XPrecision: %0.3f\n", mXPrecision);
        dump.appendFormat(INDENT3 "YPrecision: %0.3f\n", mYPrecision);
        dump.appendFormat(INDENT3 "Down: %s\n", toString(mLocked.down));
        dump.appendFormat(INDENT3 "DownTime: %lld\n", mLocked.downTime);
    } // release lock
}

void TrackballInputMapper::initializeLocked() {
    mAccumulator.clear();

    mLocked.down = false;
    mLocked.downTime = 0;
}

void TrackballInputMapper::reset() {
    for (;;) {
        { // acquire lock
            AutoMutex _l(mLock);

            if (! mLocked.down) {
                initializeLocked();
                break; // done
            }
        } // release lock

        // Synthesize trackball button up event on reset.
        nsecs_t when = systemTime(SYSTEM_TIME_MONOTONIC);
        mAccumulator.fields = Accumulator::FIELD_BTN_MOUSE;
        mAccumulator.btnMouse = false;
        sync(when);
    }

    InputMapper::reset();
}

void TrackballInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_KEY:
        switch (rawEvent->scanCode) {
        case BTN_MOUSE:
            mAccumulator.fields |= Accumulator::FIELD_BTN_MOUSE;
            mAccumulator.btnMouse = rawEvent->value != 0;
            // Sync now since BTN_MOUSE is not necessarily followed by SYN_REPORT and
            // we need to ensure that we report the up/down promptly.
            sync(rawEvent->when);
            break;
        }
        break;

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

void TrackballInputMapper::sync(nsecs_t when) {
    uint32_t fields = mAccumulator.fields;
    if (fields == 0) {
        return; // no new state changes, so nothing to do
    }

    int motionEventAction;
    PointerCoords pointerCoords;
    nsecs_t downTime;
    { // acquire lock
        AutoMutex _l(mLock);

        bool downChanged = fields & Accumulator::FIELD_BTN_MOUSE;

        if (downChanged) {
            if (mAccumulator.btnMouse) {
                mLocked.down = true;
                mLocked.downTime = when;
            } else {
                mLocked.down = false;
            }
        }

        downTime = mLocked.downTime;
        float x = fields & Accumulator::FIELD_REL_X ? mAccumulator.relX * mXScale : 0.0f;
        float y = fields & Accumulator::FIELD_REL_Y ? mAccumulator.relY * mYScale : 0.0f;

        if (downChanged) {
            motionEventAction = mLocked.down ? AMOTION_EVENT_ACTION_DOWN : AMOTION_EVENT_ACTION_UP;
        } else {
            motionEventAction = AMOTION_EVENT_ACTION_MOVE;
        }

        pointerCoords.x = x;
        pointerCoords.y = y;
        pointerCoords.pressure = mLocked.down ? 1.0f : 0.0f;
        pointerCoords.size = 0;
        pointerCoords.touchMajor = 0;
        pointerCoords.touchMinor = 0;
        pointerCoords.toolMajor = 0;
        pointerCoords.toolMinor = 0;
        pointerCoords.orientation = 0;

        if (mAssociatedDisplayId >= 0 && (x != 0.0f || y != 0.0f)) {
            // Rotate motion based on display orientation if needed.
            // Note: getDisplayInfo is non-reentrant so we can continue holding the lock.
            int32_t orientation;
            if (! getPolicy()->getDisplayInfo(mAssociatedDisplayId, NULL, NULL, & orientation)) {
                return;
            }

            float temp;
            switch (orientation) {
            case InputReaderPolicyInterface::ROTATION_90:
                temp = pointerCoords.x;
                pointerCoords.x = pointerCoords.y;
                pointerCoords.y = - temp;
                break;

            case InputReaderPolicyInterface::ROTATION_180:
                pointerCoords.x = - pointerCoords.x;
                pointerCoords.y = - pointerCoords.y;
                break;

            case InputReaderPolicyInterface::ROTATION_270:
                temp = pointerCoords.x;
                pointerCoords.x = - pointerCoords.y;
                pointerCoords.y = temp;
                break;
            }
        }
    } // release lock

    int32_t metaState = mContext->getGlobalMetaState();
    int32_t pointerId = 0;
    getDispatcher()->notifyMotion(when, getDeviceId(), AINPUT_SOURCE_TRACKBALL, 0,
            motionEventAction, 0, metaState, AMOTION_EVENT_EDGE_FLAG_NONE,
            1, &pointerId, &pointerCoords, mXPrecision, mYPrecision, downTime);

    mAccumulator.clear();
}

int32_t TrackballInputMapper::getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
    if (scanCode >= BTN_MOUSE && scanCode < BTN_JOYSTICK) {
        return getEventHub()->getScanCodeState(getDeviceId(), scanCode);
    } else {
        return AKEY_STATE_UNKNOWN;
    }
}


// --- TouchInputMapper ---

TouchInputMapper::TouchInputMapper(InputDevice* device, int32_t associatedDisplayId) :
        InputMapper(device), mAssociatedDisplayId(associatedDisplayId) {
    mLocked.surfaceOrientation = -1;
    mLocked.surfaceWidth = -1;
    mLocked.surfaceHeight = -1;

    initializeLocked();
}

TouchInputMapper::~TouchInputMapper() {
}

uint32_t TouchInputMapper::getSources() {
    return mAssociatedDisplayId >= 0 ? AINPUT_SOURCE_TOUCHSCREEN : AINPUT_SOURCE_TOUCHPAD;
}

void TouchInputMapper::populateDeviceInfo(InputDeviceInfo* info) {
    InputMapper::populateDeviceInfo(info);

    { // acquire lock
        AutoMutex _l(mLock);

        // Ensure surface information is up to date so that orientation changes are
        // noticed immediately.
        configureSurfaceLocked();

        info->addMotionRange(AINPUT_MOTION_RANGE_X, mLocked.orientedRanges.x);
        info->addMotionRange(AINPUT_MOTION_RANGE_Y, mLocked.orientedRanges.y);

        if (mLocked.orientedRanges.havePressure) {
            info->addMotionRange(AINPUT_MOTION_RANGE_PRESSURE,
                    mLocked.orientedRanges.pressure);
        }

        if (mLocked.orientedRanges.haveSize) {
            info->addMotionRange(AINPUT_MOTION_RANGE_SIZE,
                    mLocked.orientedRanges.size);
        }

        if (mLocked.orientedRanges.haveTouchSize) {
            info->addMotionRange(AINPUT_MOTION_RANGE_TOUCH_MAJOR,
                    mLocked.orientedRanges.touchMajor);
            info->addMotionRange(AINPUT_MOTION_RANGE_TOUCH_MINOR,
                    mLocked.orientedRanges.touchMinor);
        }

        if (mLocked.orientedRanges.haveToolSize) {
            info->addMotionRange(AINPUT_MOTION_RANGE_TOOL_MAJOR,
                    mLocked.orientedRanges.toolMajor);
            info->addMotionRange(AINPUT_MOTION_RANGE_TOOL_MINOR,
                    mLocked.orientedRanges.toolMinor);
        }

        if (mLocked.orientedRanges.haveOrientation) {
            info->addMotionRange(AINPUT_MOTION_RANGE_ORIENTATION,
                    mLocked.orientedRanges.orientation);
        }
    } // release lock
}

void TouchInputMapper::dump(String8& dump) {
    { // acquire lock
        AutoMutex _l(mLock);
        dump.append(INDENT2 "Touch Input Mapper:\n");
        dump.appendFormat(INDENT3 "AssociatedDisplayId: %d\n", mAssociatedDisplayId);
        dumpParameters(dump);
        dumpVirtualKeysLocked(dump);
        dumpRawAxes(dump);
        dumpCalibration(dump);
        dumpSurfaceLocked(dump);
        dump.appendFormat(INDENT3 "Translation and Scaling Factors:");
        dump.appendFormat(INDENT4 "XOrigin: %d\n", mLocked.xOrigin);
        dump.appendFormat(INDENT4 "YOrigin: %d\n", mLocked.yOrigin);
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
        dump.appendFormat(INDENT4 "OrientationSCale: %0.3f\n", mLocked.orientationScale);
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
}

void TouchInputMapper::configure() {
    InputMapper::configure();

    // Configure basic parameters.
    configureParameters();

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
}

void TouchInputMapper::dumpParameters(String8& dump) {
    dump.appendFormat(INDENT3 "UseBadTouchFilter: %s\n",
            toString(mParameters.useBadTouchFilter));
    dump.appendFormat(INDENT3 "UseAveragingTouchFilter: %s\n",
            toString(mParameters.useAveragingTouchFilter));
    dump.appendFormat(INDENT3 "UseJumpyTouchFilter: %s\n",
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

static void dumpAxisInfo(String8& dump, RawAbsoluteAxisInfo axis, const char* name) {
    if (axis.valid) {
        dump.appendFormat(INDENT4 "%s: min=%d, max=%d, flat=%d, fuzz=%d\n",
                name, axis.minValue, axis.maxValue, axis.flat, axis.fuzz);
    } else {
        dump.appendFormat(INDENT4 "%s: unknown range\n", name);
    }
}

void TouchInputMapper::dumpRawAxes(String8& dump) {
    dump.append(INDENT3 "Raw Axes:\n");
    dumpAxisInfo(dump, mRawAxes.x, "X");
    dumpAxisInfo(dump, mRawAxes.y, "Y");
    dumpAxisInfo(dump, mRawAxes.pressure, "Pressure");
    dumpAxisInfo(dump, mRawAxes.touchMajor, "TouchMajor");
    dumpAxisInfo(dump, mRawAxes.touchMinor, "TouchMinor");
    dumpAxisInfo(dump, mRawAxes.toolMajor, "ToolMajor");
    dumpAxisInfo(dump, mRawAxes.toolMinor, "ToolMinor");
    dumpAxisInfo(dump, mRawAxes.orientation, "Orientation");
}

bool TouchInputMapper::configureSurfaceLocked() {
    // Update orientation and dimensions if needed.
    int32_t orientation;
    int32_t width, height;
    if (mAssociatedDisplayId >= 0) {
        // Note: getDisplayInfo is non-reentrant so we can continue holding the lock.
        if (! getPolicy()->getDisplayInfo(mAssociatedDisplayId, & width, & height, & orientation)) {
            return false;
        }
    } else {
        orientation = InputReaderPolicyInterface::ROTATION_0;
        width = mRawAxes.x.getRange();
        height = mRawAxes.y.getRange();
    }

    bool orientationChanged = mLocked.surfaceOrientation != orientation;
    if (orientationChanged) {
        mLocked.surfaceOrientation = orientation;
    }

    bool sizeChanged = mLocked.surfaceWidth != width || mLocked.surfaceHeight != height;
    if (sizeChanged) {
        LOGI("Device reconfigured: id=0x%x, name=%s, display size is now %dx%d",
                getDeviceId(), getDeviceName().string(), width, height);

        mLocked.surfaceWidth = width;
        mLocked.surfaceHeight = height;

        // Configure X and Y factors.
        if (mRawAxes.x.valid && mRawAxes.y.valid) {
            mLocked.xOrigin = mRawAxes.x.minValue;
            mLocked.yOrigin = mRawAxes.y.minValue;
            mLocked.xScale = float(width) / mRawAxes.x.getRange();
            mLocked.yScale = float(height) / mRawAxes.y.getRange();
            mLocked.xPrecision = 1.0f / mLocked.xScale;
            mLocked.yPrecision = 1.0f / mLocked.yScale;

            configureVirtualKeysLocked();
        } else {
            LOGW(INDENT "Touch device did not report support for X or Y axis!");
            mLocked.xOrigin = 0;
            mLocked.yOrigin = 0;
            mLocked.xScale = 1.0f;
            mLocked.yScale = 1.0f;
            mLocked.xPrecision = 1.0f;
            mLocked.yPrecision = 1.0f;
        }

        // Scale factor for terms that are not oriented in a particular axis.
        // If the pixels are square then xScale == yScale otherwise we fake it
        // by choosing an average.
        mLocked.geometricScale = avg(mLocked.xScale, mLocked.yScale);

        // Size of diagonal axis.
        float diagonalSize = pythag(width, height);

        // TouchMajor and TouchMinor factors.
        if (mCalibration.touchSizeCalibration != Calibration::TOUCH_SIZE_CALIBRATION_NONE) {
            mLocked.orientedRanges.haveTouchSize = true;
            mLocked.orientedRanges.touchMajor.min = 0;
            mLocked.orientedRanges.touchMajor.max = diagonalSize;
            mLocked.orientedRanges.touchMajor.flat = 0;
            mLocked.orientedRanges.touchMajor.fuzz = 0;
            mLocked.orientedRanges.touchMinor = mLocked.orientedRanges.touchMajor;
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
            mLocked.orientedRanges.toolMajor.min = 0;
            mLocked.orientedRanges.toolMajor.max = diagonalSize;
            mLocked.orientedRanges.toolMajor.flat = 0;
            mLocked.orientedRanges.toolMajor.fuzz = 0;
            mLocked.orientedRanges.toolMinor = mLocked.orientedRanges.toolMajor;
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

            mLocked.orientedRanges.orientation.min = - M_PI_2;
            mLocked.orientedRanges.orientation.max = M_PI_2;
            mLocked.orientedRanges.orientation.flat = 0;
            mLocked.orientedRanges.orientation.fuzz = 0;
        }
    }

    if (orientationChanged || sizeChanged) {
        // Compute oriented surface dimensions, precision, and scales.
        float orientedXScale, orientedYScale;
        switch (mLocked.surfaceOrientation) {
        case InputReaderPolicyInterface::ROTATION_90:
        case InputReaderPolicyInterface::ROTATION_270:
            mLocked.orientedSurfaceWidth = mLocked.surfaceHeight;
            mLocked.orientedSurfaceHeight = mLocked.surfaceWidth;
            mLocked.orientedXPrecision = mLocked.yPrecision;
            mLocked.orientedYPrecision = mLocked.xPrecision;
            orientedXScale = mLocked.yScale;
            orientedYScale = mLocked.xScale;
            break;
        default:
            mLocked.orientedSurfaceWidth = mLocked.surfaceWidth;
            mLocked.orientedSurfaceHeight = mLocked.surfaceHeight;
            mLocked.orientedXPrecision = mLocked.xPrecision;
            mLocked.orientedYPrecision = mLocked.yPrecision;
            orientedXScale = mLocked.xScale;
            orientedYScale = mLocked.yScale;
            break;
        }

        // Configure position ranges.
        mLocked.orientedRanges.x.min = 0;
        mLocked.orientedRanges.x.max = mLocked.orientedSurfaceWidth;
        mLocked.orientedRanges.x.flat = 0;
        mLocked.orientedRanges.x.fuzz = orientedXScale;

        mLocked.orientedRanges.y.min = 0;
        mLocked.orientedRanges.y.max = mLocked.orientedSurfaceHeight;
        mLocked.orientedRanges.y.flat = 0;
        mLocked.orientedRanges.y.fuzz = orientedYScale;
    }

    return true;
}

void TouchInputMapper::dumpSurfaceLocked(String8& dump) {
    dump.appendFormat(INDENT3 "SurfaceWidth: %dpx\n", mLocked.surfaceWidth);
    dump.appendFormat(INDENT3 "SurfaceHeight: %dpx\n", mLocked.surfaceHeight);
    dump.appendFormat(INDENT3 "SurfaceOrientation: %d\n", mLocked.surfaceOrientation);
}

void TouchInputMapper::configureVirtualKeysLocked() {
    assert(mRawAxes.x.valid && mRawAxes.y.valid);

    // Note: getVirtualKeyDefinitions is non-reentrant so we can continue holding the lock.
    Vector<VirtualKeyDefinition> virtualKeyDefinitions;
    getPolicy()->getVirtualKeyDefinitions(getDeviceName(), virtualKeyDefinitions);

    mLocked.virtualKeys.clear();

    if (virtualKeyDefinitions.size() == 0) {
        return;
    }

    mLocked.virtualKeys.setCapacity(virtualKeyDefinitions.size());

    int32_t touchScreenLeft = mRawAxes.x.minValue;
    int32_t touchScreenTop = mRawAxes.y.minValue;
    int32_t touchScreenWidth = mRawAxes.x.getRange();
    int32_t touchScreenHeight = mRawAxes.y.getRange();

    for (size_t i = 0; i < virtualKeyDefinitions.size(); i++) {
        const VirtualKeyDefinition& virtualKeyDefinition =
                virtualKeyDefinitions[i];

        mLocked.virtualKeys.add();
        VirtualKey& virtualKey = mLocked.virtualKeys.editTop();

        virtualKey.scanCode = virtualKeyDefinition.scanCode;
        int32_t keyCode;
        uint32_t flags;
        if (getEventHub()->scancodeToKeycode(getDeviceId(), virtualKey.scanCode,
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
    const InputDeviceCalibration& in = getDevice()->getCalibration();
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
        assert(false);
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
        assert(false);
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
        dump.appendFormat(INDENT4 "touch.toolSize.isSummed: %d\n",
                mCalibration.toolSizeIsSummed);
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
        assert(false);
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
        assert(false);
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
        assert(false);
    }

    // Orientation
    switch (mCalibration.orientationCalibration) {
    case Calibration::ORIENTATION_CALIBRATION_NONE:
        dump.append(INDENT4 "touch.orientation.calibration: none\n");
        break;
    case Calibration::ORIENTATION_CALIBRATION_INTERPOLATED:
        dump.append(INDENT4 "touch.orientation.calibration: interpolated\n");
        break;
    default:
        assert(false);
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
    uint32_t policyFlags = 0;

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

    if (! havePointerIds) {
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

    // Process touches and virtual keys.

    TouchResult touchResult = consumeOffScreenTouches(when, policyFlags);
    if (touchResult == DISPATCH_TOUCH) {
        detectGestures(when);
        dispatchTouches(when, policyFlags);
    }

    // Copy current touch to last touch in preparation for the next cycle.

    if (touchResult == DROP_STROKE) {
        mLastTouch.clear();
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

void TouchInputMapper::detectGestures(nsecs_t when) {
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

    BitSet32 currentIdBits = mCurrentTouch.idBits;
    BitSet32 lastIdBits = mLastTouch.idBits;

    if (currentIdBits == lastIdBits) {
        // No pointer id changes so this is a move event.
        // The dispatcher takes care of batching moves so we don't have to deal with that here.
        int32_t motionEventAction = AMOTION_EVENT_ACTION_MOVE;
        dispatchTouch(when, policyFlags, & mCurrentTouch,
                currentIdBits, -1, currentPointerCount, motionEventAction);
    } else {
        // There may be pointers going up and pointers going down and pointers moving
        // all at the same time.
        BitSet32 upIdBits(lastIdBits.value & ~ currentIdBits.value);
        BitSet32 downIdBits(currentIdBits.value & ~ lastIdBits.value);
        BitSet32 activeIdBits(lastIdBits.value);
        uint32_t pointerCount = lastPointerCount;

        // Produce an intermediate representation of the touch data that consists of the
        // old location of pointers that have just gone up and the new location of pointers that
        // have just moved but omits the location of pointers that have just gone down.
        TouchData interimTouch;
        interimTouch.copyFrom(mLastTouch);

        BitSet32 moveIdBits(lastIdBits.value & currentIdBits.value);
        bool moveNeeded = false;
        while (!moveIdBits.isEmpty()) {
            uint32_t moveId = moveIdBits.firstMarkedBit();
            moveIdBits.clearBit(moveId);

            int32_t oldIndex = mLastTouch.idToIndex[moveId];
            int32_t newIndex = mCurrentTouch.idToIndex[moveId];
            if (mLastTouch.pointers[oldIndex] != mCurrentTouch.pointers[newIndex]) {
                interimTouch.pointers[oldIndex] = mCurrentTouch.pointers[newIndex];
                moveNeeded = true;
            }
        }

        // Dispatch pointer up events using the interim pointer locations.
        while (!upIdBits.isEmpty()) {
            uint32_t upId = upIdBits.firstMarkedBit();
            upIdBits.clearBit(upId);
            BitSet32 oldActiveIdBits = activeIdBits;
            activeIdBits.clearBit(upId);

            int32_t motionEventAction;
            if (activeIdBits.isEmpty()) {
                motionEventAction = AMOTION_EVENT_ACTION_UP;
            } else {
                motionEventAction = AMOTION_EVENT_ACTION_POINTER_UP;
            }

            dispatchTouch(when, policyFlags, &interimTouch,
                    oldActiveIdBits, upId, pointerCount, motionEventAction);
            pointerCount -= 1;
        }

        // Dispatch move events if any of the remaining pointers moved from their old locations.
        // Although applications receive new locations as part of individual pointer up
        // events, they do not generally handle them except when presented in a move event.
        if (moveNeeded) {
            dispatchTouch(when, policyFlags, &mCurrentTouch,
                    activeIdBits, -1, pointerCount, AMOTION_EVENT_ACTION_MOVE);
        }

        // Dispatch pointer down events using the new pointer locations.
        while (!downIdBits.isEmpty()) {
            uint32_t downId = downIdBits.firstMarkedBit();
            downIdBits.clearBit(downId);
            BitSet32 oldActiveIdBits = activeIdBits;
            activeIdBits.markBit(downId);

            int32_t motionEventAction;
            if (oldActiveIdBits.isEmpty()) {
                motionEventAction = AMOTION_EVENT_ACTION_DOWN;
                mDownTime = when;
            } else {
                motionEventAction = AMOTION_EVENT_ACTION_POINTER_DOWN;
            }

            pointerCount += 1;
            dispatchTouch(when, policyFlags, &mCurrentTouch,
                    activeIdBits, downId, pointerCount, motionEventAction);
        }
    }
}

void TouchInputMapper::dispatchTouch(nsecs_t when, uint32_t policyFlags,
        TouchData* touch, BitSet32 idBits, uint32_t changedId, uint32_t pointerCount,
        int32_t motionEventAction) {
    int32_t pointerIds[MAX_POINTERS];
    PointerCoords pointerCoords[MAX_POINTERS];
    int32_t motionEventEdgeFlags = 0;
    float xPrecision, yPrecision;

    { // acquire lock
        AutoMutex _l(mLock);

        // Walk through the the active pointers and map touch screen coordinates (TouchData) into
        // display coordinates (PointerCoords) and adjust for display orientation.
        for (uint32_t outIndex = 0; ! idBits.isEmpty(); outIndex++) {
            uint32_t id = idBits.firstMarkedBit();
            idBits.clearBit(id);
            uint32_t inIndex = touch->idToIndex[id];

            const PointerData& in = touch->pointers[inIndex];

            // X and Y
            float x = float(in.x - mLocked.xOrigin) * mLocked.xScale;
            float y = float(in.y - mLocked.yOrigin) * mLocked.yScale;

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
                toolMajor /= pointerCount;
                toolMinor /= pointerCount;
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
            default:
                orientation = 0;
            }

            // Adjust coords for orientation.
            switch (mLocked.surfaceOrientation) {
            case InputReaderPolicyInterface::ROTATION_90: {
                float xTemp = x;
                x = y;
                y = mLocked.surfaceWidth - xTemp;
                orientation -= M_PI_2;
                if (orientation < - M_PI_2) {
                    orientation += M_PI;
                }
                break;
            }
            case InputReaderPolicyInterface::ROTATION_180: {
                x = mLocked.surfaceWidth - x;
                y = mLocked.surfaceHeight - y;
                orientation = - orientation;
                break;
            }
            case InputReaderPolicyInterface::ROTATION_270: {
                float xTemp = x;
                x = mLocked.surfaceHeight - y;
                y = xTemp;
                orientation += M_PI_2;
                if (orientation > M_PI_2) {
                    orientation -= M_PI;
                }
                break;
            }
            }

            // Write output coords.
            PointerCoords& out = pointerCoords[outIndex];
            out.x = x;
            out.y = y;
            out.pressure = pressure;
            out.size = size;
            out.touchMajor = touchMajor;
            out.touchMinor = touchMinor;
            out.toolMajor = toolMajor;
            out.toolMinor = toolMinor;
            out.orientation = orientation;

            pointerIds[outIndex] = int32_t(id);

            if (id == changedId) {
                motionEventAction |= outIndex << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT;
            }
        }

        // Check edge flags by looking only at the first pointer since the flags are
        // global to the event.
        if (motionEventAction == AMOTION_EVENT_ACTION_DOWN) {
            if (pointerCoords[0].x <= 0) {
                motionEventEdgeFlags |= AMOTION_EVENT_EDGE_FLAG_LEFT;
            } else if (pointerCoords[0].x >= mLocked.orientedSurfaceWidth) {
                motionEventEdgeFlags |= AMOTION_EVENT_EDGE_FLAG_RIGHT;
            }
            if (pointerCoords[0].y <= 0) {
                motionEventEdgeFlags |= AMOTION_EVENT_EDGE_FLAG_TOP;
            } else if (pointerCoords[0].y >= mLocked.orientedSurfaceHeight) {
                motionEventEdgeFlags |= AMOTION_EVENT_EDGE_FLAG_BOTTOM;
            }
        }

        xPrecision = mLocked.orientedXPrecision;
        yPrecision = mLocked.orientedYPrecision;
    } // release lock

    getDispatcher()->notifyMotion(when, getDeviceId(), getSources(), policyFlags,
            motionEventAction, 0, getContext()->getGlobalMetaState(), motionEventEdgeFlags,
            pointerCount, pointerIds, pointerCoords,
            xPrecision, yPrecision, mDownTime);
}

bool TouchInputMapper::isPointInsideSurfaceLocked(int32_t x, int32_t y) {
    if (mRawAxes.x.valid && mRawAxes.y.valid) {
        return x >= mRawAxes.x.minValue && x <= mRawAxes.x.maxValue
                && y >= mRawAxes.y.minValue && y <= mRawAxes.y.maxValue;
    }
    return true;
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
                    assert(heapSize > 0);

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
    // This hack requires valid axis parameters.
    if (! mRawAxes.y.valid) {
        return false;
    }

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
    int32_t maxDeltaY = mRawAxes.y.getRange() * 7 / 16;

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
    // This hack requires valid axis parameters.
    if (! mRawAxes.y.valid) {
        return false;
    }

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
        int jumpyEpsilon = mRawAxes.y.getRange() / JUMPY_EPSILON_DIVISOR;

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

SingleTouchInputMapper::SingleTouchInputMapper(InputDevice* device, int32_t associatedDisplayId) :
        TouchInputMapper(device, associatedDisplayId) {
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

MultiTouchInputMapper::MultiTouchInputMapper(InputDevice* device, int32_t associatedDisplayId) :
        TouchInputMapper(device, associatedDisplayId) {
    initialize();
}

MultiTouchInputMapper::~MultiTouchInputMapper() {
}

void MultiTouchInputMapper::initialize() {
    mAccumulator.clear();
}

void MultiTouchInputMapper::reset() {
    TouchInputMapper::reset();

    initialize();
}

void MultiTouchInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
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


} // namespace android
