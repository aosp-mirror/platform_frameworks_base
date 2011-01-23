//
// Copyright 2010 The Android Open Source Project
//

#include <ui/InputReader.h>
#include <utils/List.h>
#include <gtest/gtest.h>
#include <math.h>

namespace android {

// An arbitrary time value.
static const nsecs_t ARBITRARY_TIME = 1234;

// Arbitrary display properties.
static const int32_t DISPLAY_ID = 0;
static const int32_t DISPLAY_WIDTH = 480;
static const int32_t DISPLAY_HEIGHT = 800;

// Error tolerance for floating point assertions.
static const float EPSILON = 0.001f;

template<typename T>
static inline T min(T a, T b) {
    return a < b ? a : b;
}

static inline float avg(float x, float y) {
    return (x + y) / 2;
}


// --- FakeInputReaderPolicy ---

class FakeInputReaderPolicy : public InputReaderPolicyInterface {
    struct DisplayInfo {
        int32_t width;
        int32_t height;
        int32_t orientation;
    };

    KeyedVector<int32_t, DisplayInfo> mDisplayInfos;
    bool mFilterTouchEvents;
    bool mFilterJumpyTouchEvents;
    KeyedVector<String8, Vector<VirtualKeyDefinition> > mVirtualKeyDefinitions;
    KeyedVector<String8, InputDeviceCalibration> mInputDeviceCalibrations;
    Vector<String8> mExcludedDeviceNames;

protected:
    virtual ~FakeInputReaderPolicy() { }

public:
    FakeInputReaderPolicy() :
            mFilterTouchEvents(false), mFilterJumpyTouchEvents(false) {
    }

    void removeDisplayInfo(int32_t displayId) {
        mDisplayInfos.removeItem(displayId);
    }

    void setDisplayInfo(int32_t displayId, int32_t width, int32_t height, int32_t orientation) {
        removeDisplayInfo(displayId);

        DisplayInfo info;
        info.width = width;
        info.height = height;
        info.orientation = orientation;
        mDisplayInfos.add(displayId, info);
    }

    void setFilterTouchEvents(bool enabled) {
        mFilterTouchEvents = enabled;
    }

    void setFilterJumpyTouchEvents(bool enabled) {
        mFilterJumpyTouchEvents = enabled;
    }

    void addInputDeviceCalibration(const String8& deviceName,
            const InputDeviceCalibration& calibration) {
        mInputDeviceCalibrations.add(deviceName, calibration);
    }

    void addInputDeviceCalibrationProperty(const String8& deviceName,
            const String8& key, const String8& value) {
        ssize_t index = mInputDeviceCalibrations.indexOfKey(deviceName);
        if (index < 0) {
            index = mInputDeviceCalibrations.add(deviceName, InputDeviceCalibration());
        }
        mInputDeviceCalibrations.editValueAt(index).addProperty(key, value);
    }

    void addVirtualKeyDefinition(const String8& deviceName,
            const VirtualKeyDefinition& definition) {
        if (mVirtualKeyDefinitions.indexOfKey(deviceName) < 0) {
            mVirtualKeyDefinitions.add(deviceName, Vector<VirtualKeyDefinition>());
        }

        mVirtualKeyDefinitions.editValueFor(deviceName).push(definition);
    }

    void addExcludedDeviceName(const String8& deviceName) {
        mExcludedDeviceNames.push(deviceName);
    }

private:
    virtual bool getDisplayInfo(int32_t displayId,
            int32_t* width, int32_t* height, int32_t* orientation) {
        ssize_t index = mDisplayInfos.indexOfKey(displayId);
        if (index >= 0) {
            const DisplayInfo& info = mDisplayInfos.valueAt(index);
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
        return false;
    }

    virtual bool filterTouchEvents() {
        return mFilterTouchEvents;
    }

    virtual bool filterJumpyTouchEvents() {
        return mFilterJumpyTouchEvents;
    }

    virtual nsecs_t getVirtualKeyQuietTime() {
        return 0;
    }

    virtual void getVirtualKeyDefinitions(const String8& deviceName,
            Vector<VirtualKeyDefinition>& outVirtualKeyDefinitions) {
        ssize_t index = mVirtualKeyDefinitions.indexOfKey(deviceName);
        if (index >= 0) {
            outVirtualKeyDefinitions.appendVector(mVirtualKeyDefinitions.valueAt(index));
        }
    }

    virtual void getInputDeviceCalibration(const String8& deviceName,
            InputDeviceCalibration& outCalibration) {
        ssize_t index = mInputDeviceCalibrations.indexOfKey(deviceName);
        if (index >= 0) {
            outCalibration = mInputDeviceCalibrations.valueAt(index);
        }
    }

    virtual void getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames) {
        outExcludedDeviceNames.appendVector(mExcludedDeviceNames);
    }
};


// --- FakeInputDispatcher ---

class FakeInputDispatcher : public InputDispatcherInterface {
public:
    struct NotifyConfigurationChangedArgs {
        nsecs_t eventTime;
    };

    struct NotifyKeyArgs {
        nsecs_t eventTime;
        int32_t deviceId;
        int32_t source;
        uint32_t policyFlags;
        int32_t action;
        int32_t flags;
        int32_t keyCode;
        int32_t scanCode;
        int32_t metaState;
        nsecs_t downTime;
    };

    struct NotifyMotionArgs {
        nsecs_t eventTime;
        int32_t deviceId;
        int32_t source;
        uint32_t policyFlags;
        int32_t action;
        int32_t flags;
        int32_t metaState;
        int32_t edgeFlags;
        uint32_t pointerCount;
        Vector<int32_t> pointerIds;
        Vector<PointerCoords> pointerCoords;
        float xPrecision;
        float yPrecision;
        nsecs_t downTime;
    };

    struct NotifySwitchArgs {
        nsecs_t when;
        int32_t switchCode;
        int32_t switchValue;
        uint32_t policyFlags;
    };

private:
    List<NotifyConfigurationChangedArgs> mNotifyConfigurationChangedArgs;
    List<NotifyKeyArgs> mNotifyKeyArgs;
    List<NotifyMotionArgs> mNotifyMotionArgs;
    List<NotifySwitchArgs> mNotifySwitchArgs;

protected:
    virtual ~FakeInputDispatcher() { }

public:
    FakeInputDispatcher() {
    }

    void assertNotifyConfigurationChangedWasCalled(NotifyConfigurationChangedArgs* outArgs = NULL) {
        ASSERT_FALSE(mNotifyConfigurationChangedArgs.empty())
                << "Expected notifyConfigurationChanged() to have been called.";
        if (outArgs) {
            *outArgs = *mNotifyConfigurationChangedArgs.begin();
        }
        mNotifyConfigurationChangedArgs.erase(mNotifyConfigurationChangedArgs.begin());
    }

    void assertNotifyKeyWasCalled(NotifyKeyArgs* outArgs = NULL) {
        ASSERT_FALSE(mNotifyKeyArgs.empty())
                << "Expected notifyKey() to have been called.";
        if (outArgs) {
            *outArgs = *mNotifyKeyArgs.begin();
        }
        mNotifyKeyArgs.erase(mNotifyKeyArgs.begin());
    }

    void assertNotifyKeyWasNotCalled() {
        ASSERT_TRUE(mNotifyKeyArgs.empty())
                << "Expected notifyKey() to not have been called.";
    }

    void assertNotifyMotionWasCalled(NotifyMotionArgs* outArgs = NULL) {
        ASSERT_FALSE(mNotifyMotionArgs.empty())
                << "Expected notifyMotion() to have been called.";
        if (outArgs) {
            *outArgs = *mNotifyMotionArgs.begin();
        }
        mNotifyMotionArgs.erase(mNotifyMotionArgs.begin());
    }

    void assertNotifyMotionWasNotCalled() {
        ASSERT_TRUE(mNotifyMotionArgs.empty())
                << "Expected notifyMotion() to not have been called.";
    }

    void assertNotifySwitchWasCalled(NotifySwitchArgs* outArgs = NULL) {
        ASSERT_FALSE(mNotifySwitchArgs.empty())
                << "Expected notifySwitch() to have been called.";
        if (outArgs) {
            *outArgs = *mNotifySwitchArgs.begin();
        }
        mNotifySwitchArgs.erase(mNotifySwitchArgs.begin());
    }

private:
    virtual void notifyConfigurationChanged(nsecs_t eventTime) {
        NotifyConfigurationChangedArgs args;
        args.eventTime = eventTime;
        mNotifyConfigurationChangedArgs.push_back(args);
    }

    virtual void notifyKey(nsecs_t eventTime, int32_t deviceId, int32_t source,
            uint32_t policyFlags, int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime) {
        NotifyKeyArgs args;
        args.eventTime = eventTime;
        args.deviceId = deviceId;
        args.source = source;
        args.policyFlags = policyFlags;
        args.action = action;
        args.flags = flags;
        args.keyCode = keyCode;
        args.scanCode = scanCode;
        args.metaState = metaState;
        args.downTime = downTime;
        mNotifyKeyArgs.push_back(args);
    }

    virtual void notifyMotion(nsecs_t eventTime, int32_t deviceId, int32_t source,
            uint32_t policyFlags, int32_t action, int32_t flags,
            int32_t metaState, int32_t edgeFlags,
            uint32_t pointerCount, const int32_t* pointerIds, const PointerCoords* pointerCoords,
            float xPrecision, float yPrecision, nsecs_t downTime) {
        NotifyMotionArgs args;
        args.eventTime = eventTime;
        args.deviceId = deviceId;
        args.source = source;
        args.policyFlags = policyFlags;
        args.action = action;
        args.flags = flags;
        args.metaState = metaState;
        args.edgeFlags = edgeFlags;
        args.pointerCount = pointerCount;
        args.pointerIds.clear();
        args.pointerIds.appendArray(pointerIds, pointerCount);
        args.pointerCoords.clear();
        args.pointerCoords.appendArray(pointerCoords, pointerCount);
        args.xPrecision = xPrecision;
        args.yPrecision = yPrecision;
        args.downTime = downTime;
        mNotifyMotionArgs.push_back(args);
    }

    virtual void notifySwitch(nsecs_t when,
            int32_t switchCode, int32_t switchValue, uint32_t policyFlags) {
        NotifySwitchArgs args;
        args.when = when;
        args.switchCode = switchCode;
        args.switchValue = switchValue;
        args.policyFlags = policyFlags;
        mNotifySwitchArgs.push_back(args);
    }

    virtual void dump(String8& dump) {
        ADD_FAILURE() << "Should never be called by input reader.";
    }

    virtual void dispatchOnce() {
        ADD_FAILURE() << "Should never be called by input reader.";
    }

    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, int32_t syncMode, int32_t timeoutMillis) {
        ADD_FAILURE() << "Should never be called by input reader.";
        return INPUT_EVENT_INJECTION_FAILED;
    }

    virtual void setInputWindows(const Vector<InputWindow>& inputWindows) {
        ADD_FAILURE() << "Should never be called by input reader.";
    }

    virtual void setFocusedApplication(const InputApplication* inputApplication) {
        ADD_FAILURE() << "Should never be called by input reader.";
    }

    virtual void setInputDispatchMode(bool enabled, bool frozen) {
        ADD_FAILURE() << "Should never be called by input reader.";
    }

    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel, bool monitor) {
        ADD_FAILURE() << "Should never be called by input reader.";
        return 0;
    }

    virtual status_t unregisterInputChannel(const sp<InputChannel>& inputChannel) {
        ADD_FAILURE() << "Should never be called by input reader.";
        return 0;
    }
};


// --- FakeEventHub ---

class FakeEventHub : public EventHubInterface {
    struct KeyInfo {
        int32_t keyCode;
        uint32_t flags;
    };

    struct Device {
        String8 name;
        uint32_t classes;
        KeyedVector<int, RawAbsoluteAxisInfo> axes;
        KeyedVector<int32_t, int32_t> keyCodeStates;
        KeyedVector<int32_t, int32_t> scanCodeStates;
        KeyedVector<int32_t, int32_t> switchStates;
        KeyedVector<int32_t, KeyInfo> keys;

        Device(const String8& name, uint32_t classes) :
                name(name), classes(classes) {
        }
    };

    KeyedVector<int32_t, Device*> mDevices;
    Vector<String8> mExcludedDevices;
    List<RawEvent> mEvents;

protected:
    virtual ~FakeEventHub() {
        for (size_t i = 0; i < mDevices.size(); i++) {
            delete mDevices.valueAt(i);
        }
    }

public:
    FakeEventHub() { }

    void addDevice(int32_t deviceId, const String8& name, uint32_t classes) {
        Device* device = new Device(name, classes);
        mDevices.add(deviceId, device);

        enqueueEvent(ARBITRARY_TIME, deviceId, EventHubInterface::DEVICE_ADDED, 0, 0, 0, 0);
    }

    void removeDevice(int32_t deviceId) {
        delete mDevices.valueFor(deviceId);
        mDevices.removeItem(deviceId);

        enqueueEvent(ARBITRARY_TIME, deviceId, EventHubInterface::DEVICE_REMOVED, 0, 0, 0, 0);
    }

    void finishDeviceScan() {
        enqueueEvent(ARBITRARY_TIME, 0, EventHubInterface::FINISHED_DEVICE_SCAN, 0, 0, 0, 0);
    }

    void addAxis(int32_t deviceId, int axis,
            int32_t minValue, int32_t maxValue, int flat, int fuzz) {
        Device* device = getDevice(deviceId);

        RawAbsoluteAxisInfo info;
        info.valid = true;
        info.minValue = minValue;
        info.maxValue = maxValue;
        info.flat = flat;
        info.fuzz = fuzz;
        device->axes.add(axis, info);
    }

    void setKeyCodeState(int32_t deviceId, int32_t keyCode, int32_t state) {
        Device* device = getDevice(deviceId);
        device->keyCodeStates.replaceValueFor(keyCode, state);
    }

    void setScanCodeState(int32_t deviceId, int32_t scanCode, int32_t state) {
        Device* device = getDevice(deviceId);
        device->scanCodeStates.replaceValueFor(scanCode, state);
    }

    void setSwitchState(int32_t deviceId, int32_t switchCode, int32_t state) {
        Device* device = getDevice(deviceId);
        device->switchStates.replaceValueFor(switchCode, state);
    }

    void addKey(int32_t deviceId, int32_t scanCode, int32_t keyCode, uint32_t flags) {
        Device* device = getDevice(deviceId);
        KeyInfo info;
        info.keyCode = keyCode;
        info.flags = flags;
        device->keys.add(scanCode, info);
    }

    Vector<String8>& getExcludedDevices() {
        return mExcludedDevices;
    }

    void enqueueEvent(nsecs_t when, int32_t deviceId, int32_t type,
            int32_t scanCode, int32_t keyCode, int32_t value, uint32_t flags) {
        RawEvent event;
        event.when = when;
        event.deviceId = deviceId;
        event.type = type;
        event.scanCode = scanCode;
        event.keyCode = keyCode;
        event.value = value;
        event.flags = flags;
        mEvents.push_back(event);
    }

    void assertQueueIsEmpty() {
        ASSERT_EQ(size_t(0), mEvents.size())
                << "Expected the event queue to be empty (fully consumed).";
    }

private:
    Device* getDevice(int32_t deviceId) const {
        ssize_t index = mDevices.indexOfKey(deviceId);
        return index >= 0 ? mDevices.valueAt(index) : NULL;
    }

    virtual uint32_t getDeviceClasses(int32_t deviceId) const {
        Device* device = getDevice(deviceId);
        return device ? device->classes : 0;
    }

    virtual String8 getDeviceName(int32_t deviceId) const {
        Device* device = getDevice(deviceId);
        return device ? device->name : String8("unknown");
    }

    virtual status_t getAbsoluteAxisInfo(int32_t deviceId, int axis,
            RawAbsoluteAxisInfo* outAxisInfo) const {
        Device* device = getDevice(deviceId);
        if (device) {
            ssize_t index = device->axes.indexOfKey(axis);
            if (index >= 0) {
                *outAxisInfo = device->axes.valueAt(index);
                return OK;
            }
        }
        return -1;
    }

    virtual status_t scancodeToKeycode(int32_t deviceId, int scancode,
            int32_t* outKeycode, uint32_t* outFlags) const {
        Device* device = getDevice(deviceId);
        if (device) {
            ssize_t index = device->keys.indexOfKey(scancode);
            if (index >= 0) {
                if (outKeycode) {
                    *outKeycode = device->keys.valueAt(index).keyCode;
                }
                if (outFlags) {
                    *outFlags = device->keys.valueAt(index).flags;
                }
                return OK;
            }
        }
        return NAME_NOT_FOUND;
    }

    virtual void addExcludedDevice(const char* deviceName) {
        mExcludedDevices.add(String8(deviceName));
    }

    virtual bool getEvent(RawEvent* outEvent) {
        if (mEvents.empty()) {
            return false;
        }

        *outEvent = *mEvents.begin();
        mEvents.erase(mEvents.begin());
        return true;
    }

    virtual int32_t getScanCodeState(int32_t deviceId, int32_t scanCode) const {
        Device* device = getDevice(deviceId);
        if (device) {
            ssize_t index = device->scanCodeStates.indexOfKey(scanCode);
            if (index >= 0) {
                return device->scanCodeStates.valueAt(index);
            }
        }
        return AKEY_STATE_UNKNOWN;
    }

    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t keyCode) const {
        Device* device = getDevice(deviceId);
        if (device) {
            ssize_t index = device->keyCodeStates.indexOfKey(keyCode);
            if (index >= 0) {
                return device->keyCodeStates.valueAt(index);
            }
        }
        return AKEY_STATE_UNKNOWN;
    }

    virtual int32_t getSwitchState(int32_t deviceId, int32_t sw) const {
        Device* device = getDevice(deviceId);
        if (device) {
            ssize_t index = device->switchStates.indexOfKey(sw);
            if (index >= 0) {
                return device->switchStates.valueAt(index);
            }
        }
        return AKEY_STATE_UNKNOWN;
    }

    virtual bool markSupportedKeyCodes(int32_t deviceId, size_t numCodes, const int32_t* keyCodes,
            uint8_t* outFlags) const {
        bool result = false;
        Device* device = getDevice(deviceId);
        if (device) {
            for (size_t i = 0; i < numCodes; i++) {
                for (size_t j = 0; j < device->keys.size(); j++) {
                    if (keyCodes[i] == device->keys.valueAt(j).keyCode) {
                        outFlags[i] = 1;
                        result = true;
                    }
                }
            }
        }
        return result;
    }

    virtual void dump(String8& dump) {
    }
};


// --- FakeInputReaderContext ---

class FakeInputReaderContext : public InputReaderContext {
    sp<EventHubInterface> mEventHub;
    sp<InputReaderPolicyInterface> mPolicy;
    sp<InputDispatcherInterface> mDispatcher;
    int32_t mGlobalMetaState;
    bool mUpdateGlobalMetaStateWasCalled;

public:
    FakeInputReaderContext(const sp<EventHubInterface>& eventHub,
            const sp<InputReaderPolicyInterface>& policy,
            const sp<InputDispatcherInterface>& dispatcher) :
            mEventHub(eventHub), mPolicy(policy), mDispatcher(dispatcher),
            mGlobalMetaState(0) {
    }

    virtual ~FakeInputReaderContext() { }

    void assertUpdateGlobalMetaStateWasCalled() {
        ASSERT_TRUE(mUpdateGlobalMetaStateWasCalled)
                << "Expected updateGlobalMetaState() to have been called.";
        mUpdateGlobalMetaStateWasCalled = false;
    }

    void setGlobalMetaState(int32_t state) {
        mGlobalMetaState = state;
    }

private:
    virtual void updateGlobalMetaState() {
        mUpdateGlobalMetaStateWasCalled = true;
    }

    virtual int32_t getGlobalMetaState() {
        return mGlobalMetaState;
    }

    virtual EventHubInterface* getEventHub() {
        return mEventHub.get();
    }

    virtual InputReaderPolicyInterface* getPolicy() {
        return mPolicy.get();
    }

    virtual InputDispatcherInterface* getDispatcher() {
        return mDispatcher.get();
    }

    virtual void disableVirtualKeysUntil(nsecs_t time) {
    }

    virtual bool shouldDropVirtualKey(nsecs_t now,
            InputDevice* device, int32_t keyCode, int32_t scanCode) {
        return false;
    }
};


// --- FakeInputMapper ---

class FakeInputMapper : public InputMapper {
    uint32_t mSources;
    int32_t mKeyboardType;
    int32_t mMetaState;
    KeyedVector<int32_t, int32_t> mKeyCodeStates;
    KeyedVector<int32_t, int32_t> mScanCodeStates;
    KeyedVector<int32_t, int32_t> mSwitchStates;
    Vector<int32_t> mSupportedKeyCodes;
    RawEvent mLastEvent;

    bool mConfigureWasCalled;
    bool mResetWasCalled;
    bool mProcessWasCalled;

public:
    FakeInputMapper(InputDevice* device, uint32_t sources) :
            InputMapper(device),
            mSources(sources), mKeyboardType(AINPUT_KEYBOARD_TYPE_NONE),
            mMetaState(0),
            mConfigureWasCalled(false), mResetWasCalled(false), mProcessWasCalled(false) {
    }

    virtual ~FakeInputMapper() { }

    void setKeyboardType(int32_t keyboardType) {
        mKeyboardType = keyboardType;
    }

    void setMetaState(int32_t metaState) {
        mMetaState = metaState;
    }

    void assertConfigureWasCalled() {
        ASSERT_TRUE(mConfigureWasCalled)
                << "Expected configure() to have been called.";
        mConfigureWasCalled = false;
    }

    void assertResetWasCalled() {
        ASSERT_TRUE(mResetWasCalled)
                << "Expected reset() to have been called.";
        mResetWasCalled = false;
    }

    void assertProcessWasCalled(RawEvent* outLastEvent = NULL) {
        ASSERT_TRUE(mProcessWasCalled)
                << "Expected process() to have been called.";
        if (outLastEvent) {
            *outLastEvent = mLastEvent;
        }
        mProcessWasCalled = false;
    }

    void setKeyCodeState(int32_t keyCode, int32_t state) {
        mKeyCodeStates.replaceValueFor(keyCode, state);
    }

    void setScanCodeState(int32_t scanCode, int32_t state) {
        mScanCodeStates.replaceValueFor(scanCode, state);
    }

    void setSwitchState(int32_t switchCode, int32_t state) {
        mSwitchStates.replaceValueFor(switchCode, state);
    }

    void addSupportedKeyCode(int32_t keyCode) {
        mSupportedKeyCodes.add(keyCode);
    }

private:
    virtual uint32_t getSources() {
        return mSources;
    }

    virtual void populateDeviceInfo(InputDeviceInfo* deviceInfo) {
        InputMapper::populateDeviceInfo(deviceInfo);

        if (mKeyboardType != AINPUT_KEYBOARD_TYPE_NONE) {
            deviceInfo->setKeyboardType(mKeyboardType);
        }
    }

    virtual void configure() {
        mConfigureWasCalled = true;
    }

    virtual void reset() {
        mResetWasCalled = true;
    }

    virtual void process(const RawEvent* rawEvent) {
        mLastEvent = *rawEvent;
        mProcessWasCalled = true;
    }

    virtual int32_t getKeyCodeState(uint32_t sourceMask, int32_t keyCode) {
        ssize_t index = mKeyCodeStates.indexOfKey(keyCode);
        return index >= 0 ? mKeyCodeStates.valueAt(index) : AKEY_STATE_UNKNOWN;
    }

    virtual int32_t getScanCodeState(uint32_t sourceMask, int32_t scanCode) {
        ssize_t index = mScanCodeStates.indexOfKey(scanCode);
        return index >= 0 ? mScanCodeStates.valueAt(index) : AKEY_STATE_UNKNOWN;
    }

    virtual int32_t getSwitchState(uint32_t sourceMask, int32_t switchCode) {
        ssize_t index = mSwitchStates.indexOfKey(switchCode);
        return index >= 0 ? mSwitchStates.valueAt(index) : AKEY_STATE_UNKNOWN;
    }

    virtual bool markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags) {
        bool result = false;
        for (size_t i = 0; i < numCodes; i++) {
            for (size_t j = 0; j < mSupportedKeyCodes.size(); j++) {
                if (keyCodes[i] == mSupportedKeyCodes[j]) {
                    outFlags[i] = 1;
                    result = true;
                }
            }
        }
        return result;
    }

    virtual int32_t getMetaState() {
        return mMetaState;
    }
};


// --- InstrumentedInputReader ---

class InstrumentedInputReader : public InputReader {
    InputDevice* mNextDevice;

public:
    InstrumentedInputReader(const sp<EventHubInterface>& eventHub,
            const sp<InputReaderPolicyInterface>& policy,
            const sp<InputDispatcherInterface>& dispatcher) :
            InputReader(eventHub, policy, dispatcher) {
    }

    virtual ~InstrumentedInputReader() {
        if (mNextDevice) {
            delete mNextDevice;
        }
    }

    void setNextDevice(InputDevice* device) {
        mNextDevice = device;
    }

protected:
    virtual InputDevice* createDevice(int32_t deviceId, const String8& name, uint32_t classes) {
        if (mNextDevice) {
            InputDevice* device = mNextDevice;
            mNextDevice = NULL;
            return device;
        }
        return InputReader::createDevice(deviceId, name, classes);
    }

    friend class InputReaderTest;
};


// --- InputReaderTest ---

class InputReaderTest : public testing::Test {
protected:
    sp<FakeInputDispatcher> mFakeDispatcher;
    sp<FakeInputReaderPolicy> mFakePolicy;
    sp<FakeEventHub> mFakeEventHub;
    sp<InstrumentedInputReader> mReader;

    virtual void SetUp() {
        mFakeEventHub = new FakeEventHub();
        mFakePolicy = new FakeInputReaderPolicy();
        mFakeDispatcher = new FakeInputDispatcher();

        mReader = new InstrumentedInputReader(mFakeEventHub, mFakePolicy, mFakeDispatcher);
    }

    virtual void TearDown() {
        mReader.clear();

        mFakeDispatcher.clear();
        mFakePolicy.clear();
        mFakeEventHub.clear();
    }

    void addDevice(int32_t deviceId, const String8& name, uint32_t classes) {
        mFakeEventHub->addDevice(deviceId, name, classes);
        mFakeEventHub->finishDeviceScan();
        mReader->loopOnce();
        mReader->loopOnce();
        mFakeEventHub->assertQueueIsEmpty();
    }

    FakeInputMapper* addDeviceWithFakeInputMapper(int32_t deviceId,
            const String8& name, uint32_t classes, uint32_t sources) {
        InputDevice* device = new InputDevice(mReader.get(), deviceId, name);
        FakeInputMapper* mapper = new FakeInputMapper(device, sources);
        device->addMapper(mapper);
        mReader->setNextDevice(device);
        addDevice(deviceId, name, classes);
        return mapper;
    }
};

TEST_F(InputReaderTest, GetInputConfiguration_WhenNoDevices_ReturnsDefaults) {
    InputConfiguration config;
    mReader->getInputConfiguration(&config);

    ASSERT_EQ(InputConfiguration::KEYBOARD_NOKEYS, config.keyboard);
    ASSERT_EQ(InputConfiguration::NAVIGATION_NONAV, config.navigation);
    ASSERT_EQ(InputConfiguration::TOUCHSCREEN_NOTOUCH, config.touchScreen);
}

TEST_F(InputReaderTest, GetInputConfiguration_WhenAlphabeticKeyboardPresent_ReturnsQwertyKeyboard) {
    ASSERT_NO_FATAL_FAILURE(addDevice(0, String8("keyboard"),
            INPUT_DEVICE_CLASS_KEYBOARD | INPUT_DEVICE_CLASS_ALPHAKEY));

    InputConfiguration config;
    mReader->getInputConfiguration(&config);

    ASSERT_EQ(InputConfiguration::KEYBOARD_QWERTY, config.keyboard);
    ASSERT_EQ(InputConfiguration::NAVIGATION_NONAV, config.navigation);
    ASSERT_EQ(InputConfiguration::TOUCHSCREEN_NOTOUCH, config.touchScreen);
}

TEST_F(InputReaderTest, GetInputConfiguration_WhenTouchScreenPresent_ReturnsFingerTouchScreen) {
    ASSERT_NO_FATAL_FAILURE(addDevice(0, String8("touchscreen"),
            INPUT_DEVICE_CLASS_TOUCHSCREEN));

    InputConfiguration config;
    mReader->getInputConfiguration(&config);

    ASSERT_EQ(InputConfiguration::KEYBOARD_NOKEYS, config.keyboard);
    ASSERT_EQ(InputConfiguration::NAVIGATION_NONAV, config.navigation);
    ASSERT_EQ(InputConfiguration::TOUCHSCREEN_FINGER, config.touchScreen);
}

TEST_F(InputReaderTest, GetInputConfiguration_WhenTrackballPresent_ReturnsTrackballNavigation) {
    ASSERT_NO_FATAL_FAILURE(addDevice(0, String8("trackball"),
            INPUT_DEVICE_CLASS_TRACKBALL));

    InputConfiguration config;
    mReader->getInputConfiguration(&config);

    ASSERT_EQ(InputConfiguration::KEYBOARD_NOKEYS, config.keyboard);
    ASSERT_EQ(InputConfiguration::NAVIGATION_TRACKBALL, config.navigation);
    ASSERT_EQ(InputConfiguration::TOUCHSCREEN_NOTOUCH, config.touchScreen);
}

TEST_F(InputReaderTest, GetInputConfiguration_WhenDPadPresent_ReturnsDPadNavigation) {
    ASSERT_NO_FATAL_FAILURE(addDevice(0, String8("dpad"),
            INPUT_DEVICE_CLASS_DPAD));

    InputConfiguration config;
    mReader->getInputConfiguration(&config);

    ASSERT_EQ(InputConfiguration::KEYBOARD_NOKEYS, config.keyboard);
    ASSERT_EQ(InputConfiguration::NAVIGATION_DPAD, config.navigation);
    ASSERT_EQ(InputConfiguration::TOUCHSCREEN_NOTOUCH, config.touchScreen);
}

TEST_F(InputReaderTest, GetInputDeviceInfo_WhenDeviceIdIsValid) {
    ASSERT_NO_FATAL_FAILURE(addDevice(1, String8("keyboard"),
            INPUT_DEVICE_CLASS_KEYBOARD));

    InputDeviceInfo info;
    status_t result = mReader->getInputDeviceInfo(1, &info);

    ASSERT_EQ(OK, result);
    ASSERT_EQ(1, info.getId());
    ASSERT_STREQ("keyboard", info.getName().string());
    ASSERT_EQ(AINPUT_KEYBOARD_TYPE_NON_ALPHABETIC, info.getKeyboardType());
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, info.getSources());
    ASSERT_EQ(size_t(0), info.getMotionRanges().size());
}

TEST_F(InputReaderTest, GetInputDeviceInfo_WhenDeviceIdIsInvalid) {
    InputDeviceInfo info;
    status_t result = mReader->getInputDeviceInfo(-1, &info);

    ASSERT_EQ(NAME_NOT_FOUND, result);
}

TEST_F(InputReaderTest, GetInputDeviceInfo_WhenDeviceIdIsIgnored) {
    addDevice(1, String8("ignored"), 0); // no classes so device will be ignored

    InputDeviceInfo info;
    status_t result = mReader->getInputDeviceInfo(1, &info);

    ASSERT_EQ(NAME_NOT_FOUND, result);
}

TEST_F(InputReaderTest, GetInputDeviceIds) {
    ASSERT_NO_FATAL_FAILURE(addDevice(1, String8("keyboard"),
            INPUT_DEVICE_CLASS_KEYBOARD | INPUT_DEVICE_CLASS_ALPHAKEY));
    ASSERT_NO_FATAL_FAILURE(addDevice(2, String8("trackball"),
            INPUT_DEVICE_CLASS_TRACKBALL));

    Vector<int32_t> ids;
    mReader->getInputDeviceIds(ids);

    ASSERT_EQ(size_t(2), ids.size());
    ASSERT_EQ(1, ids[0]);
    ASSERT_EQ(2, ids[1]);
}

TEST_F(InputReaderTest, GetKeyCodeState_ForwardsRequestsToMappers) {
    FakeInputMapper* mapper = NULL;
    ASSERT_NO_FATAL_FAILURE(mapper = addDeviceWithFakeInputMapper(1, String8("fake"),
            INPUT_DEVICE_CLASS_KEYBOARD, AINPUT_SOURCE_KEYBOARD));
    mapper->setKeyCodeState(AKEYCODE_A, AKEY_STATE_DOWN);

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getKeyCodeState(0,
            AINPUT_SOURCE_ANY, AKEYCODE_A))
            << "Should return unknown when the device id is >= 0 but unknown.";

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getKeyCodeState(1,
            AINPUT_SOURCE_TRACKBALL, AKEYCODE_A))
            << "Should return unknown when the device id is valid but the sources are not supported by the device.";

    ASSERT_EQ(AKEY_STATE_DOWN, mReader->getKeyCodeState(1,
            AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TRACKBALL, AKEYCODE_A))
            << "Should return value provided by mapper when device id is valid and the device supports some of the sources.";

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getKeyCodeState(-1,
            AINPUT_SOURCE_TRACKBALL, AKEYCODE_A))
            << "Should return unknown when the device id is < 0 but the sources are not supported by any device.";

    ASSERT_EQ(AKEY_STATE_DOWN, mReader->getKeyCodeState(-1,
            AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TRACKBALL, AKEYCODE_A))
            << "Should return value provided by mapper when device id is < 0 and one of the devices supports some of the sources.";
}

TEST_F(InputReaderTest, GetScanCodeState_ForwardsRequestsToMappers) {
    FakeInputMapper* mapper = NULL;
    ASSERT_NO_FATAL_FAILURE(mapper = addDeviceWithFakeInputMapper(1, String8("fake"),
            INPUT_DEVICE_CLASS_KEYBOARD, AINPUT_SOURCE_KEYBOARD));
    mapper->setScanCodeState(KEY_A, AKEY_STATE_DOWN);

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getScanCodeState(0,
            AINPUT_SOURCE_ANY, KEY_A))
            << "Should return unknown when the device id is >= 0 but unknown.";

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getScanCodeState(1,
            AINPUT_SOURCE_TRACKBALL, KEY_A))
            << "Should return unknown when the device id is valid but the sources are not supported by the device.";

    ASSERT_EQ(AKEY_STATE_DOWN, mReader->getScanCodeState(1,
            AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TRACKBALL, KEY_A))
            << "Should return value provided by mapper when device id is valid and the device supports some of the sources.";

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getScanCodeState(-1,
            AINPUT_SOURCE_TRACKBALL, KEY_A))
            << "Should return unknown when the device id is < 0 but the sources are not supported by any device.";

    ASSERT_EQ(AKEY_STATE_DOWN, mReader->getScanCodeState(-1,
            AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TRACKBALL, KEY_A))
            << "Should return value provided by mapper when device id is < 0 and one of the devices supports some of the sources.";
}

TEST_F(InputReaderTest, GetSwitchState_ForwardsRequestsToMappers) {
    FakeInputMapper* mapper = NULL;
    ASSERT_NO_FATAL_FAILURE(mapper = addDeviceWithFakeInputMapper(1, String8("fake"),
            INPUT_DEVICE_CLASS_KEYBOARD, AINPUT_SOURCE_KEYBOARD));
    mapper->setSwitchState(SW_LID, AKEY_STATE_DOWN);

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getSwitchState(0,
            AINPUT_SOURCE_ANY, SW_LID))
            << "Should return unknown when the device id is >= 0 but unknown.";

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getSwitchState(1,
            AINPUT_SOURCE_TRACKBALL, SW_LID))
            << "Should return unknown when the device id is valid but the sources are not supported by the device.";

    ASSERT_EQ(AKEY_STATE_DOWN, mReader->getSwitchState(1,
            AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TRACKBALL, SW_LID))
            << "Should return value provided by mapper when device id is valid and the device supports some of the sources.";

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mReader->getSwitchState(-1,
            AINPUT_SOURCE_TRACKBALL, SW_LID))
            << "Should return unknown when the device id is < 0 but the sources are not supported by any device.";

    ASSERT_EQ(AKEY_STATE_DOWN, mReader->getSwitchState(-1,
            AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TRACKBALL, SW_LID))
            << "Should return value provided by mapper when device id is < 0 and one of the devices supports some of the sources.";
}

TEST_F(InputReaderTest, MarkSupportedKeyCodes_ForwardsRequestsToMappers) {
    FakeInputMapper* mapper = NULL;
    ASSERT_NO_FATAL_FAILURE(mapper = addDeviceWithFakeInputMapper(1, String8("fake"),
            INPUT_DEVICE_CLASS_KEYBOARD, AINPUT_SOURCE_KEYBOARD));
    mapper->addSupportedKeyCode(AKEYCODE_A);
    mapper->addSupportedKeyCode(AKEYCODE_B);

    const int32_t keyCodes[4] = { AKEYCODE_A, AKEYCODE_B, AKEYCODE_1, AKEYCODE_2 };
    uint8_t flags[4] = { 0, 0, 0, 1 };

    ASSERT_FALSE(mReader->hasKeys(0, AINPUT_SOURCE_ANY, 4, keyCodes, flags))
            << "Should return false when device id is >= 0 but unknown.";
    ASSERT_TRUE(!flags[0] && !flags[1] && !flags[2] && !flags[3]);

    flags[3] = 1;
    ASSERT_FALSE(mReader->hasKeys(1, AINPUT_SOURCE_TRACKBALL, 4, keyCodes, flags))
            << "Should return false when device id is valid but the sources are not supported by the device.";
    ASSERT_TRUE(!flags[0] && !flags[1] && !flags[2] && !flags[3]);

    flags[3] = 1;
    ASSERT_TRUE(mReader->hasKeys(1, AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TRACKBALL, 4, keyCodes, flags))
            << "Should return value provided by mapper when device id is valid and the device supports some of the sources.";
    ASSERT_TRUE(flags[0] && flags[1] && !flags[2] && !flags[3]);

    flags[3] = 1;
    ASSERT_FALSE(mReader->hasKeys(-1, AINPUT_SOURCE_TRACKBALL, 4, keyCodes, flags))
            << "Should return false when the device id is < 0 but the sources are not supported by any device.";
    ASSERT_TRUE(!flags[0] && !flags[1] && !flags[2] && !flags[3]);

    flags[3] = 1;
    ASSERT_TRUE(mReader->hasKeys(-1, AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TRACKBALL, 4, keyCodes, flags))
            << "Should return value provided by mapper when device id is < 0 and one of the devices supports some of the sources.";
    ASSERT_TRUE(flags[0] && flags[1] && !flags[2] && !flags[3]);
}

TEST_F(InputReaderTest, LoopOnce_WhenDeviceScanFinished_SendsConfigurationChanged) {
    addDevice(1, String8("ignored"), INPUT_DEVICE_CLASS_KEYBOARD);

    FakeInputDispatcher::NotifyConfigurationChangedArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyConfigurationChangedWasCalled(&args));
    ASSERT_EQ(ARBITRARY_TIME, args.eventTime);
}

TEST_F(InputReaderTest, LoopOnce_ForwardsRawEventsToMappers) {
    FakeInputMapper* mapper = NULL;
    ASSERT_NO_FATAL_FAILURE(mapper = addDeviceWithFakeInputMapper(1, String8("fake"),
            INPUT_DEVICE_CLASS_KEYBOARD, AINPUT_SOURCE_KEYBOARD));

    mFakeEventHub->enqueueEvent(0, 1, EV_KEY, KEY_A, AKEYCODE_A, 1, POLICY_FLAG_WAKE);
    mReader->loopOnce();
    ASSERT_NO_FATAL_FAILURE(mFakeEventHub->assertQueueIsEmpty());

    RawEvent event;
    ASSERT_NO_FATAL_FAILURE(mapper->assertProcessWasCalled(&event));
    ASSERT_EQ(0, event.when);
    ASSERT_EQ(1, event.deviceId);
    ASSERT_EQ(EV_KEY, event.type);
    ASSERT_EQ(KEY_A, event.scanCode);
    ASSERT_EQ(AKEYCODE_A, event.keyCode);
    ASSERT_EQ(1, event.value);
    ASSERT_EQ(POLICY_FLAG_WAKE, event.flags);
}


// --- InputDeviceTest ---

class InputDeviceTest : public testing::Test {
protected:
    static const char* DEVICE_NAME;
    static const int32_t DEVICE_ID;

    sp<FakeEventHub> mFakeEventHub;
    sp<FakeInputReaderPolicy> mFakePolicy;
    sp<FakeInputDispatcher> mFakeDispatcher;
    FakeInputReaderContext* mFakeContext;

    InputDevice* mDevice;

    virtual void SetUp() {
        mFakeEventHub = new FakeEventHub();
        mFakePolicy = new FakeInputReaderPolicy();
        mFakeDispatcher = new FakeInputDispatcher();
        mFakeContext = new FakeInputReaderContext(mFakeEventHub, mFakePolicy, mFakeDispatcher);

        mDevice = new InputDevice(mFakeContext, DEVICE_ID, String8(DEVICE_NAME));
    }

    virtual void TearDown() {
        delete mDevice;

        delete mFakeContext;
        mFakeDispatcher.clear();
        mFakePolicy.clear();
        mFakeEventHub.clear();
    }
};

const char* InputDeviceTest::DEVICE_NAME = "device";
const int32_t InputDeviceTest::DEVICE_ID = 1;

TEST_F(InputDeviceTest, ImmutableProperties) {
    ASSERT_EQ(DEVICE_ID, mDevice->getId());
    ASSERT_STREQ(DEVICE_NAME, mDevice->getName());
}

TEST_F(InputDeviceTest, WhenNoMappersAreRegistered_DeviceIsIgnored) {
    // Configuration.
    mDevice->configure();

    // Metadata.
    ASSERT_TRUE(mDevice->isIgnored());
    ASSERT_EQ(AINPUT_SOURCE_UNKNOWN, mDevice->getSources());

    InputDeviceInfo info;
    mDevice->getDeviceInfo(&info);
    ASSERT_EQ(DEVICE_ID, info.getId());
    ASSERT_STREQ(DEVICE_NAME, info.getName().string());
    ASSERT_EQ(AINPUT_KEYBOARD_TYPE_NONE, info.getKeyboardType());
    ASSERT_EQ(AINPUT_SOURCE_UNKNOWN, info.getSources());

    // State queries.
    ASSERT_EQ(0, mDevice->getMetaState());

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mDevice->getKeyCodeState(AINPUT_SOURCE_KEYBOARD, 0))
            << "Ignored device should return unknown key code state.";
    ASSERT_EQ(AKEY_STATE_UNKNOWN, mDevice->getScanCodeState(AINPUT_SOURCE_KEYBOARD, 0))
            << "Ignored device should return unknown scan code state.";
    ASSERT_EQ(AKEY_STATE_UNKNOWN, mDevice->getSwitchState(AINPUT_SOURCE_KEYBOARD, 0))
            << "Ignored device should return unknown switch state.";

    const int32_t keyCodes[2] = { AKEYCODE_A, AKEYCODE_B };
    uint8_t flags[2] = { 0, 1 };
    ASSERT_FALSE(mDevice->markSupportedKeyCodes(AINPUT_SOURCE_KEYBOARD, 2, keyCodes, flags))
            << "Ignored device should never mark any key codes.";
    ASSERT_EQ(0, flags[0]) << "Flag for unsupported key should be unchanged.";
    ASSERT_EQ(1, flags[1]) << "Flag for unsupported key should be unchanged.";

    // Reset.
    mDevice->reset();
}

TEST_F(InputDeviceTest, WhenMappersAreRegistered_DeviceIsNotIgnoredAndForwardsRequestsToMappers) {
    // Configuration.
    InputDeviceCalibration calibration;
    calibration.addProperty(String8("key"), String8("value"));
    mFakePolicy->addInputDeviceCalibration(String8(DEVICE_NAME), calibration);

    FakeInputMapper* mapper1 = new FakeInputMapper(mDevice, AINPUT_SOURCE_KEYBOARD);
    mapper1->setKeyboardType(AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    mapper1->setMetaState(AMETA_ALT_ON);
    mapper1->addSupportedKeyCode(AKEYCODE_A);
    mapper1->addSupportedKeyCode(AKEYCODE_B);
    mapper1->setKeyCodeState(AKEYCODE_A, AKEY_STATE_DOWN);
    mapper1->setKeyCodeState(AKEYCODE_B, AKEY_STATE_UP);
    mapper1->setScanCodeState(2, AKEY_STATE_DOWN);
    mapper1->setScanCodeState(3, AKEY_STATE_UP);
    mapper1->setSwitchState(4, AKEY_STATE_DOWN);
    mDevice->addMapper(mapper1);

    FakeInputMapper* mapper2 = new FakeInputMapper(mDevice, AINPUT_SOURCE_TOUCHSCREEN);
    mapper2->setMetaState(AMETA_SHIFT_ON);
    mDevice->addMapper(mapper2);

    mDevice->configure();

    String8 propertyValue;
    ASSERT_TRUE(mDevice->getCalibration().tryGetProperty(String8("key"), propertyValue))
            << "Device should have read calibration during configuration phase.";
    ASSERT_STREQ("value", propertyValue.string());

    ASSERT_NO_FATAL_FAILURE(mapper1->assertConfigureWasCalled());
    ASSERT_NO_FATAL_FAILURE(mapper2->assertConfigureWasCalled());

    // Metadata.
    ASSERT_FALSE(mDevice->isIgnored());
    ASSERT_EQ(uint32_t(AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TOUCHSCREEN), mDevice->getSources());

    InputDeviceInfo info;
    mDevice->getDeviceInfo(&info);
    ASSERT_EQ(DEVICE_ID, info.getId());
    ASSERT_STREQ(DEVICE_NAME, info.getName().string());
    ASSERT_EQ(AINPUT_KEYBOARD_TYPE_ALPHABETIC, info.getKeyboardType());
    ASSERT_EQ(uint32_t(AINPUT_SOURCE_KEYBOARD | AINPUT_SOURCE_TOUCHSCREEN), info.getSources());

    // State queries.
    ASSERT_EQ(AMETA_ALT_ON | AMETA_SHIFT_ON, mDevice->getMetaState())
            << "Should query mappers and combine meta states.";

    ASSERT_EQ(AKEY_STATE_UNKNOWN, mDevice->getKeyCodeState(AINPUT_SOURCE_TRACKBALL, AKEYCODE_A))
            << "Should return unknown key code state when source not supported.";
    ASSERT_EQ(AKEY_STATE_UNKNOWN, mDevice->getScanCodeState(AINPUT_SOURCE_TRACKBALL, AKEYCODE_A))
            << "Should return unknown scan code state when source not supported.";
    ASSERT_EQ(AKEY_STATE_UNKNOWN, mDevice->getSwitchState(AINPUT_SOURCE_TRACKBALL, AKEYCODE_A))
            << "Should return unknown switch state when source not supported.";

    ASSERT_EQ(AKEY_STATE_DOWN, mDevice->getKeyCodeState(AINPUT_SOURCE_KEYBOARD, AKEYCODE_A))
            << "Should query mapper when source is supported.";
    ASSERT_EQ(AKEY_STATE_UP, mDevice->getScanCodeState(AINPUT_SOURCE_KEYBOARD, 3))
            << "Should query mapper when source is supported.";
    ASSERT_EQ(AKEY_STATE_DOWN, mDevice->getSwitchState(AINPUT_SOURCE_KEYBOARD, 4))
            << "Should query mapper when source is supported.";

    const int32_t keyCodes[4] = { AKEYCODE_A, AKEYCODE_B, AKEYCODE_1, AKEYCODE_2 };
    uint8_t flags[4] = { 0, 0, 0, 1 };
    ASSERT_FALSE(mDevice->markSupportedKeyCodes(AINPUT_SOURCE_TRACKBALL, 4, keyCodes, flags))
            << "Should do nothing when source is unsupported.";
    ASSERT_EQ(0, flags[0]) << "Flag should be unchanged when source is unsupported.";
    ASSERT_EQ(0, flags[1]) << "Flag should be unchanged when source is unsupported.";
    ASSERT_EQ(0, flags[2]) << "Flag should be unchanged when source is unsupported.";
    ASSERT_EQ(1, flags[3]) << "Flag should be unchanged when source is unsupported.";

    ASSERT_TRUE(mDevice->markSupportedKeyCodes(AINPUT_SOURCE_KEYBOARD, 4, keyCodes, flags))
            << "Should query mapper when source is supported.";
    ASSERT_EQ(1, flags[0]) << "Flag for supported key should be set.";
    ASSERT_EQ(1, flags[1]) << "Flag for supported key should be set.";
    ASSERT_EQ(0, flags[2]) << "Flag for unsupported key should be unchanged.";
    ASSERT_EQ(1, flags[3]) << "Flag for unsupported key should be unchanged.";

    // Event handling.
    RawEvent event;
    mDevice->process(&event);

    ASSERT_NO_FATAL_FAILURE(mapper1->assertProcessWasCalled());
    ASSERT_NO_FATAL_FAILURE(mapper2->assertProcessWasCalled());

    // Reset.
    mDevice->reset();

    ASSERT_NO_FATAL_FAILURE(mapper1->assertResetWasCalled());
    ASSERT_NO_FATAL_FAILURE(mapper2->assertResetWasCalled());
}


// --- InputMapperTest ---

class InputMapperTest : public testing::Test {
protected:
    static const char* DEVICE_NAME;
    static const int32_t DEVICE_ID;

    sp<FakeEventHub> mFakeEventHub;
    sp<FakeInputReaderPolicy> mFakePolicy;
    sp<FakeInputDispatcher> mFakeDispatcher;
    FakeInputReaderContext* mFakeContext;
    InputDevice* mDevice;

    virtual void SetUp() {
        mFakeEventHub = new FakeEventHub();
        mFakePolicy = new FakeInputReaderPolicy();
        mFakeDispatcher = new FakeInputDispatcher();
        mFakeContext = new FakeInputReaderContext(mFakeEventHub, mFakePolicy, mFakeDispatcher);
        mDevice = new InputDevice(mFakeContext, DEVICE_ID, String8(DEVICE_NAME));

        mFakeEventHub->addDevice(DEVICE_ID, String8(DEVICE_NAME), 0);
    }

    virtual void TearDown() {
        delete mDevice;
        delete mFakeContext;
        mFakeDispatcher.clear();
        mFakePolicy.clear();
        mFakeEventHub.clear();
    }

    void prepareCalibration(const char* key, const char* value) {
        mFakePolicy->addInputDeviceCalibrationProperty(String8(DEVICE_NAME),
                String8(key), String8(value));
    }

    void addMapperAndConfigure(InputMapper* mapper) {
        mDevice->addMapper(mapper);
        mDevice->configure();
    }

    static void process(InputMapper* mapper, nsecs_t when, int32_t deviceId, int32_t type,
            int32_t scanCode, int32_t keyCode, int32_t value, uint32_t flags) {
        RawEvent event;
        event.when = when;
        event.deviceId = deviceId;
        event.type = type;
        event.scanCode = scanCode;
        event.keyCode = keyCode;
        event.value = value;
        event.flags = flags;
        mapper->process(&event);
    }

    static void assertMotionRange(const InputDeviceInfo& info,
            int32_t rangeType, float min, float max, float flat, float fuzz) {
        const InputDeviceInfo::MotionRange* range = info.getMotionRange(rangeType);
        ASSERT_TRUE(range != NULL) << "Range: " << rangeType;
        ASSERT_NEAR(min, range->min, EPSILON) << "Range: " << rangeType;
        ASSERT_NEAR(max, range->max, EPSILON) << "Range: " << rangeType;
        ASSERT_NEAR(flat, range->flat, EPSILON) << "Range: " << rangeType;
        ASSERT_NEAR(fuzz, range->fuzz, EPSILON) << "Range: " << rangeType;
    }

    static void assertPointerCoords(const PointerCoords& coords,
            float x, float y, float pressure, float size,
            float touchMajor, float touchMinor, float toolMajor, float toolMinor,
            float orientation) {
        ASSERT_NEAR(x, coords.x, 1);
        ASSERT_NEAR(y, coords.y, 1);
        ASSERT_NEAR(pressure, coords.pressure, EPSILON);
        ASSERT_NEAR(size, coords.size, EPSILON);
        ASSERT_NEAR(touchMajor, coords.touchMajor, 1);
        ASSERT_NEAR(touchMinor, coords.touchMinor, 1);
        ASSERT_NEAR(toolMajor, coords.toolMajor, 1);
        ASSERT_NEAR(toolMinor, coords.toolMinor, 1);
        ASSERT_NEAR(orientation, coords.orientation, EPSILON);
    }
};

const char* InputMapperTest::DEVICE_NAME = "device";
const int32_t InputMapperTest::DEVICE_ID = 1;


// --- SwitchInputMapperTest ---

class SwitchInputMapperTest : public InputMapperTest {
protected:
};

TEST_F(SwitchInputMapperTest, GetSources) {
    SwitchInputMapper* mapper = new SwitchInputMapper(mDevice);
    addMapperAndConfigure(mapper);

    ASSERT_EQ(uint32_t(AINPUT_SOURCE_SWITCH), mapper->getSources());
}

TEST_F(SwitchInputMapperTest, GetSwitchState) {
    SwitchInputMapper* mapper = new SwitchInputMapper(mDevice);
    addMapperAndConfigure(mapper);

    mFakeEventHub->setSwitchState(DEVICE_ID, SW_LID, 1);
    ASSERT_EQ(1, mapper->getSwitchState(AINPUT_SOURCE_ANY, SW_LID));

    mFakeEventHub->setSwitchState(DEVICE_ID, SW_LID, 0);
    ASSERT_EQ(0, mapper->getSwitchState(AINPUT_SOURCE_ANY, SW_LID));
}

TEST_F(SwitchInputMapperTest, Process) {
    SwitchInputMapper* mapper = new SwitchInputMapper(mDevice);
    addMapperAndConfigure(mapper);

    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SW, SW_LID, 0, 1, 0);

    FakeInputDispatcher::NotifySwitchArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifySwitchWasCalled(&args));
    ASSERT_EQ(ARBITRARY_TIME, args.when);
    ASSERT_EQ(SW_LID, args.switchCode);
    ASSERT_EQ(1, args.switchValue);
    ASSERT_EQ(uint32_t(0), args.policyFlags);
}


// --- KeyboardInputMapperTest ---

class KeyboardInputMapperTest : public InputMapperTest {
protected:
    void testDPadKeyRotation(KeyboardInputMapper* mapper,
            int32_t originalScanCode, int32_t originalKeyCode, int32_t rotatedKeyCode);
};

void KeyboardInputMapperTest::testDPadKeyRotation(KeyboardInputMapper* mapper,
        int32_t originalScanCode, int32_t originalKeyCode, int32_t rotatedKeyCode) {
    FakeInputDispatcher::NotifyKeyArgs args;

    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, originalScanCode, originalKeyCode, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(AKEY_EVENT_ACTION_DOWN, args.action);
    ASSERT_EQ(originalScanCode, args.scanCode);
    ASSERT_EQ(rotatedKeyCode, args.keyCode);

    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, originalScanCode, originalKeyCode, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(AKEY_EVENT_ACTION_UP, args.action);
    ASSERT_EQ(originalScanCode, args.scanCode);
    ASSERT_EQ(rotatedKeyCode, args.keyCode);
}


TEST_F(KeyboardInputMapperTest, GetSources) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, mapper->getSources());
}

TEST_F(KeyboardInputMapperTest, Process_SimpleKeyPress) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    // Key down.
    process(mapper, ARBITRARY_TIME, DEVICE_ID,
            EV_KEY, KEY_HOME, AKEYCODE_HOME, 1, POLICY_FLAG_WAKE);
    FakeInputDispatcher::NotifyKeyArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, args.source);
    ASSERT_EQ(ARBITRARY_TIME, args.eventTime);
    ASSERT_EQ(AKEY_EVENT_ACTION_DOWN, args.action);
    ASSERT_EQ(AKEYCODE_HOME, args.keyCode);
    ASSERT_EQ(KEY_HOME, args.scanCode);
    ASSERT_EQ(AMETA_NONE, args.metaState);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM, args.flags);
    ASSERT_EQ(POLICY_FLAG_WAKE, args.policyFlags);
    ASSERT_EQ(ARBITRARY_TIME, args.downTime);

    // Key up.
    process(mapper, ARBITRARY_TIME + 1, DEVICE_ID,
            EV_KEY, KEY_HOME, AKEYCODE_HOME, 0, POLICY_FLAG_WAKE);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, args.source);
    ASSERT_EQ(ARBITRARY_TIME + 1, args.eventTime);
    ASSERT_EQ(AKEY_EVENT_ACTION_UP, args.action);
    ASSERT_EQ(AKEYCODE_HOME, args.keyCode);
    ASSERT_EQ(KEY_HOME, args.scanCode);
    ASSERT_EQ(AMETA_NONE, args.metaState);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM, args.flags);
    ASSERT_EQ(POLICY_FLAG_WAKE, args.policyFlags);
    ASSERT_EQ(ARBITRARY_TIME, args.downTime);
}

TEST_F(KeyboardInputMapperTest, Reset_WhenKeysAreNotDown_DoesNotSynthesizeKeyUp) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    // Key down.
    process(mapper, ARBITRARY_TIME, DEVICE_ID,
            EV_KEY, KEY_HOME, AKEYCODE_HOME, 1, POLICY_FLAG_WAKE);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    // Key up.
    process(mapper, ARBITRARY_TIME, DEVICE_ID,
            EV_KEY, KEY_HOME, AKEYCODE_HOME, 0, POLICY_FLAG_WAKE);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    // Reset.  Since no keys still down, should not synthesize any key ups.
    mapper->reset();
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
}

TEST_F(KeyboardInputMapperTest, Reset_WhenKeysAreDown_SynthesizesKeyUps) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    // Metakey down.
    process(mapper, ARBITRARY_TIME, DEVICE_ID,
            EV_KEY, KEY_LEFTSHIFT, AKEYCODE_SHIFT_LEFT, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    // Key down.
    process(mapper, ARBITRARY_TIME + 1, DEVICE_ID,
            EV_KEY, KEY_A, AKEYCODE_A, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    // Reset.  Since two keys are still down, should synthesize two key ups in reverse order.
    mapper->reset();

    FakeInputDispatcher::NotifyKeyArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, args.source);
    ASSERT_EQ(AKEY_EVENT_ACTION_UP, args.action);
    ASSERT_EQ(AKEYCODE_A, args.keyCode);
    ASSERT_EQ(KEY_A, args.scanCode);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM, args.flags);
    ASSERT_EQ(uint32_t(0), args.policyFlags);
    ASSERT_EQ(ARBITRARY_TIME + 1, args.downTime);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, args.source);
    ASSERT_EQ(AKEY_EVENT_ACTION_UP, args.action);
    ASSERT_EQ(AKEYCODE_SHIFT_LEFT, args.keyCode);
    ASSERT_EQ(KEY_LEFTSHIFT, args.scanCode);
    ASSERT_EQ(AMETA_NONE, args.metaState);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM, args.flags);
    ASSERT_EQ(uint32_t(0), args.policyFlags);
    ASSERT_EQ(ARBITRARY_TIME + 1, args.downTime);

    // And that's it.
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
}

TEST_F(KeyboardInputMapperTest, Process_ShouldUpdateMetaState) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    // Initial metastate.
    ASSERT_EQ(AMETA_NONE, mapper->getMetaState());

    // Metakey down.
    process(mapper, ARBITRARY_TIME, DEVICE_ID,
            EV_KEY, KEY_LEFTSHIFT, AKEYCODE_SHIFT_LEFT, 1, 0);
    FakeInputDispatcher::NotifyKeyArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, mapper->getMetaState());
    ASSERT_NO_FATAL_FAILURE(mFakeContext->assertUpdateGlobalMetaStateWasCalled());

    // Key down.
    process(mapper, ARBITRARY_TIME + 1, DEVICE_ID,
            EV_KEY, KEY_A, AKEYCODE_A, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, mapper->getMetaState());

    // Key up.
    process(mapper, ARBITRARY_TIME + 2, DEVICE_ID,
            EV_KEY, KEY_A, AKEYCODE_A, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, mapper->getMetaState());

    // Metakey up.
    process(mapper, ARBITRARY_TIME + 3, DEVICE_ID,
            EV_KEY, KEY_LEFTSHIFT, AKEYCODE_SHIFT_LEFT, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(AMETA_NONE, args.metaState);
    ASSERT_EQ(AMETA_NONE, mapper->getMetaState());
    ASSERT_NO_FATAL_FAILURE(mFakeContext->assertUpdateGlobalMetaStateWasCalled());
}

TEST_F(KeyboardInputMapperTest, Process_WhenNotAttachedToDisplay_ShouldNotRotateDPad) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_UP, AKEYCODE_DPAD_UP, AKEYCODE_DPAD_UP));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_RIGHT, AKEYCODE_DPAD_RIGHT, AKEYCODE_DPAD_RIGHT));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_DOWN, AKEYCODE_DPAD_DOWN, AKEYCODE_DPAD_DOWN));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_LEFT, AKEYCODE_DPAD_LEFT, AKEYCODE_DPAD_LEFT));
}

TEST_F(KeyboardInputMapperTest, Process_WhenAttachedToDisplay_ShouldRotateDPad) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, DISPLAY_ID,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_0);
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_UP, AKEYCODE_DPAD_UP, AKEYCODE_DPAD_UP));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_RIGHT, AKEYCODE_DPAD_RIGHT, AKEYCODE_DPAD_RIGHT));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_DOWN, AKEYCODE_DPAD_DOWN, AKEYCODE_DPAD_DOWN));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_LEFT, AKEYCODE_DPAD_LEFT, AKEYCODE_DPAD_LEFT));

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_90);
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_UP, AKEYCODE_DPAD_UP, AKEYCODE_DPAD_LEFT));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_RIGHT, AKEYCODE_DPAD_RIGHT, AKEYCODE_DPAD_UP));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_DOWN, AKEYCODE_DPAD_DOWN, AKEYCODE_DPAD_RIGHT));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_LEFT, AKEYCODE_DPAD_LEFT, AKEYCODE_DPAD_DOWN));

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_180);
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_UP, AKEYCODE_DPAD_UP, AKEYCODE_DPAD_DOWN));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_RIGHT, AKEYCODE_DPAD_RIGHT, AKEYCODE_DPAD_LEFT));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_DOWN, AKEYCODE_DPAD_DOWN, AKEYCODE_DPAD_UP));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_LEFT, AKEYCODE_DPAD_LEFT, AKEYCODE_DPAD_RIGHT));

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_270);
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_UP, AKEYCODE_DPAD_UP, AKEYCODE_DPAD_RIGHT));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_RIGHT, AKEYCODE_DPAD_RIGHT, AKEYCODE_DPAD_DOWN));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_DOWN, AKEYCODE_DPAD_DOWN, AKEYCODE_DPAD_LEFT));
    ASSERT_NO_FATAL_FAILURE(testDPadKeyRotation(mapper,
            KEY_LEFT, AKEYCODE_DPAD_LEFT, AKEYCODE_DPAD_UP));

    // Special case: if orientation changes while key is down, we still emit the same keycode
    // in the key up as we did in the key down.
    FakeInputDispatcher::NotifyKeyArgs args;

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_270);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, KEY_UP, AKEYCODE_DPAD_UP, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(AKEY_EVENT_ACTION_DOWN, args.action);
    ASSERT_EQ(KEY_UP, args.scanCode);
    ASSERT_EQ(AKEYCODE_DPAD_RIGHT, args.keyCode);

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_180);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, KEY_UP, AKEYCODE_DPAD_UP, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(AKEY_EVENT_ACTION_UP, args.action);
    ASSERT_EQ(KEY_UP, args.scanCode);
    ASSERT_EQ(AKEYCODE_DPAD_RIGHT, args.keyCode);
}

TEST_F(KeyboardInputMapperTest, GetKeyCodeState) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    mFakeEventHub->setKeyCodeState(DEVICE_ID, AKEYCODE_A, 1);
    ASSERT_EQ(1, mapper->getKeyCodeState(AINPUT_SOURCE_ANY, AKEYCODE_A));

    mFakeEventHub->setKeyCodeState(DEVICE_ID, AKEYCODE_A, 0);
    ASSERT_EQ(0, mapper->getKeyCodeState(AINPUT_SOURCE_ANY, AKEYCODE_A));
}

TEST_F(KeyboardInputMapperTest, GetScanCodeState) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    mFakeEventHub->setScanCodeState(DEVICE_ID, KEY_A, 1);
    ASSERT_EQ(1, mapper->getScanCodeState(AINPUT_SOURCE_ANY, KEY_A));

    mFakeEventHub->setScanCodeState(DEVICE_ID, KEY_A, 0);
    ASSERT_EQ(0, mapper->getScanCodeState(AINPUT_SOURCE_ANY, KEY_A));
}

TEST_F(KeyboardInputMapperTest, MarkSupportedKeyCodes) {
    KeyboardInputMapper* mapper = new KeyboardInputMapper(mDevice, -1,
            AINPUT_SOURCE_KEYBOARD, AINPUT_KEYBOARD_TYPE_ALPHABETIC);
    addMapperAndConfigure(mapper);

    mFakeEventHub->addKey(DEVICE_ID, KEY_A, AKEYCODE_A, 0);

    const int32_t keyCodes[2] = { AKEYCODE_A, AKEYCODE_B };
    uint8_t flags[2] = { 0, 0 };
    ASSERT_TRUE(mapper->markSupportedKeyCodes(AINPUT_SOURCE_ANY, 1, keyCodes, flags));
    ASSERT_TRUE(flags[0]);
    ASSERT_FALSE(flags[1]);
}


// --- TrackballInputMapperTest ---

class TrackballInputMapperTest : public InputMapperTest {
protected:
    static const int32_t TRACKBALL_MOVEMENT_THRESHOLD;

    void testMotionRotation(TrackballInputMapper* mapper,
            int32_t originalX, int32_t originalY, int32_t rotatedX, int32_t rotatedY);
};

const int32_t TrackballInputMapperTest::TRACKBALL_MOVEMENT_THRESHOLD = 6;

void TrackballInputMapperTest::testMotionRotation(TrackballInputMapper* mapper,
        int32_t originalX, int32_t originalY, int32_t rotatedX, int32_t rotatedY) {
    FakeInputDispatcher::NotifyMotionArgs args;

    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_REL, REL_X, 0, originalX, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_REL, REL_Y, 0, originalY, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SYN, SYN_REPORT, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, args.action);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            float(rotatedX) / TRACKBALL_MOVEMENT_THRESHOLD,
            float(rotatedY) / TRACKBALL_MOVEMENT_THRESHOLD,
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
}

TEST_F(TrackballInputMapperTest, GetSources) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    ASSERT_EQ(AINPUT_SOURCE_TRACKBALL, mapper->getSources());
}

TEST_F(TrackballInputMapperTest, PopulateDeviceInfo) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    InputDeviceInfo info;
    mapper->populateDeviceInfo(&info);

    ASSERT_NO_FATAL_FAILURE(assertMotionRange(info, AINPUT_MOTION_RANGE_X,
            -1.0f, 1.0f, 0.0f, 1.0f / TRACKBALL_MOVEMENT_THRESHOLD));
    ASSERT_NO_FATAL_FAILURE(assertMotionRange(info, AINPUT_MOTION_RANGE_Y,
            -1.0f, 1.0f, 0.0f, 1.0f / TRACKBALL_MOVEMENT_THRESHOLD));
}

TEST_F(TrackballInputMapperTest, Process_ShouldSetAllFieldsAndIncludeGlobalMetaState) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    mFakeContext->setGlobalMetaState(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON);

    FakeInputDispatcher::NotifyMotionArgs args;

    // Button press.
    // Mostly testing non x/y behavior here so we don't need to check again elsewhere.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(ARBITRARY_TIME, args.eventTime);
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TRACKBALL, args.source);
    ASSERT_EQ(uint32_t(0), args.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, args.action);
    ASSERT_EQ(0, args.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(0, args.edgeFlags);
    ASSERT_EQ(uint32_t(1), args.pointerCount);
    ASSERT_EQ(0, args.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
    ASSERT_EQ(TRACKBALL_MOVEMENT_THRESHOLD, args.xPrecision);
    ASSERT_EQ(TRACKBALL_MOVEMENT_THRESHOLD, args.yPrecision);
    ASSERT_EQ(ARBITRARY_TIME, args.downTime);

    // Button release.  Should have same down time.
    process(mapper, ARBITRARY_TIME + 1, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(ARBITRARY_TIME + 1, args.eventTime);
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TRACKBALL, args.source);
    ASSERT_EQ(uint32_t(0), args.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, args.action);
    ASSERT_EQ(0, args.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(0, args.edgeFlags);
    ASSERT_EQ(uint32_t(1), args.pointerCount);
    ASSERT_EQ(0, args.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
    ASSERT_EQ(TRACKBALL_MOVEMENT_THRESHOLD, args.xPrecision);
    ASSERT_EQ(TRACKBALL_MOVEMENT_THRESHOLD, args.yPrecision);
    ASSERT_EQ(ARBITRARY_TIME, args.downTime);
}

TEST_F(TrackballInputMapperTest, Process_ShouldHandleIndependentXYUpdates) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;

    // Motion in X but not Y.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_REL, REL_X, 0, 1, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SYN, SYN_REPORT, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, args.action);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            1.0f / TRACKBALL_MOVEMENT_THRESHOLD, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));

    // Motion in Y but not X.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_REL, REL_Y, 0, -2, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SYN, SYN_REPORT, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, args.action);
    ASSERT_NEAR(0.0f, args.pointerCoords[0].x, EPSILON);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            0.0f, -2.0f / TRACKBALL_MOVEMENT_THRESHOLD, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
}

TEST_F(TrackballInputMapperTest, Process_ShouldHandleIndependentButtonUpdates) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;

    // Button press without following sync.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, args.action);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));

    // Button release without following sync.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, args.action);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
}

TEST_F(TrackballInputMapperTest, Process_ShouldHandleCombinedXYAndButtonUpdates) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;

    // Combined X, Y and Button.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_REL, REL_X, 0, 1, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_REL, REL_Y, 0, -2, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 1, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SYN, SYN_REPORT, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, args.action);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            1.0f / TRACKBALL_MOVEMENT_THRESHOLD, -2.0f / TRACKBALL_MOVEMENT_THRESHOLD,
            1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));

    // Move X, Y a bit while pressed.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_REL, REL_X, 0, 2, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_REL, REL_Y, 0, 1, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SYN, SYN_REPORT, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, args.action);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            2.0f / TRACKBALL_MOVEMENT_THRESHOLD, 1.0f / TRACKBALL_MOVEMENT_THRESHOLD,
            1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));

    // Release Button.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, args.action);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
}

TEST_F(TrackballInputMapperTest, Reset_WhenButtonIsNotDown_ShouldNotSynthesizeButtonUp) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;

    // Button press.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));

    // Button release.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 0, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));

    // Reset.  Should not synthesize button up since button is not pressed.
    mapper->reset();

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasNotCalled());
}

TEST_F(TrackballInputMapperTest, Reset_WhenButtonIsDown_ShouldSynthesizeButtonUp) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;

    // Button press.
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_MOUSE, 0, 1, 0);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));

    // Reset.  Should synthesize button up.
    mapper->reset();

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, args.action);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
}

TEST_F(TrackballInputMapperTest, Process_WhenNotAttachedToDisplay_ShouldNotRotateMotions) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, -1);
    addMapperAndConfigure(mapper);

    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0,  1,  0,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  1,  1,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  0,  1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1, -1,  1, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0, -1,  0, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1, -1, -1, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  0, -1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  1, -1,  1));
}

TEST_F(TrackballInputMapperTest, Process_WhenAttachedToDisplay_ShouldRotateMotions) {
    TrackballInputMapper* mapper = new TrackballInputMapper(mDevice, DISPLAY_ID);
    addMapperAndConfigure(mapper);

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_0);
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0,  1,  0,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  1,  1,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  0,  1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1, -1,  1, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0, -1,  0, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1, -1, -1, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  0, -1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  1, -1,  1));

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_90);
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0,  1,  1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  1,  1, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  0,  0, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1, -1, -1, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0, -1, -1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1, -1, -1,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  0,  0,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  1,  1,  1));

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_180);
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0,  1,  0, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  1, -1, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  0, -1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1, -1, -1,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0, -1,  0,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1, -1,  1,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  0,  1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  1,  1, -1));

    mFakePolicy->setDisplayInfo(DISPLAY_ID,
            DISPLAY_WIDTH, DISPLAY_HEIGHT,
            InputReaderPolicyInterface::ROTATION_270);
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0,  1, -1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  1, -1,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1,  0,  0,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  1, -1,  1,  1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper,  0, -1,  1,  0));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1, -1,  1, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  0,  0, -1));
    ASSERT_NO_FATAL_FAILURE(testMotionRotation(mapper, -1,  1, -1, -1));
}


// --- TouchInputMapperTest ---

class TouchInputMapperTest : public InputMapperTest {
protected:
    static const int32_t RAW_X_MIN;
    static const int32_t RAW_X_MAX;
    static const int32_t RAW_Y_MIN;
    static const int32_t RAW_Y_MAX;
    static const int32_t RAW_TOUCH_MIN;
    static const int32_t RAW_TOUCH_MAX;
    static const int32_t RAW_TOOL_MIN;
    static const int32_t RAW_TOOL_MAX;
    static const int32_t RAW_PRESSURE_MIN;
    static const int32_t RAW_PRESSURE_MAX;
    static const int32_t RAW_ORIENTATION_MIN;
    static const int32_t RAW_ORIENTATION_MAX;
    static const int32_t RAW_ID_MIN;
    static const int32_t RAW_ID_MAX;
    static const float X_PRECISION;
    static const float Y_PRECISION;

    static const VirtualKeyDefinition VIRTUAL_KEYS[2];

    enum Axes {
        POSITION = 1 << 0,
        TOUCH = 1 << 1,
        TOOL = 1 << 2,
        PRESSURE = 1 << 3,
        ORIENTATION = 1 << 4,
        MINOR = 1 << 5,
        ID = 1 << 6,
    };

    void prepareDisplay(int32_t orientation);
    void prepareVirtualKeys();
    int32_t toRawX(float displayX);
    int32_t toRawY(float displayY);
    float toDisplayX(int32_t rawX);
    float toDisplayY(int32_t rawY);
};

const int32_t TouchInputMapperTest::RAW_X_MIN = 25;
const int32_t TouchInputMapperTest::RAW_X_MAX = 1020;
const int32_t TouchInputMapperTest::RAW_Y_MIN = 30;
const int32_t TouchInputMapperTest::RAW_Y_MAX = 1010;
const int32_t TouchInputMapperTest::RAW_TOUCH_MIN = 0;
const int32_t TouchInputMapperTest::RAW_TOUCH_MAX = 31;
const int32_t TouchInputMapperTest::RAW_TOOL_MIN = 0;
const int32_t TouchInputMapperTest::RAW_TOOL_MAX = 15;
const int32_t TouchInputMapperTest::RAW_PRESSURE_MIN = RAW_TOUCH_MIN;
const int32_t TouchInputMapperTest::RAW_PRESSURE_MAX = RAW_TOUCH_MAX;
const int32_t TouchInputMapperTest::RAW_ORIENTATION_MIN = -7;
const int32_t TouchInputMapperTest::RAW_ORIENTATION_MAX = 7;
const int32_t TouchInputMapperTest::RAW_ID_MIN = 0;
const int32_t TouchInputMapperTest::RAW_ID_MAX = 9;
const float TouchInputMapperTest::X_PRECISION = float(RAW_X_MAX - RAW_X_MIN) / DISPLAY_WIDTH;
const float TouchInputMapperTest::Y_PRECISION = float(RAW_Y_MAX - RAW_Y_MIN) / DISPLAY_HEIGHT;

const VirtualKeyDefinition TouchInputMapperTest::VIRTUAL_KEYS[2] = {
        { KEY_HOME, 60, DISPLAY_HEIGHT + 15, 20, 20 },
        { KEY_MENU, DISPLAY_HEIGHT - 60, DISPLAY_WIDTH + 15, 20, 20 },
};

void TouchInputMapperTest::prepareDisplay(int32_t orientation) {
    mFakePolicy->setDisplayInfo(DISPLAY_ID, DISPLAY_WIDTH, DISPLAY_HEIGHT, orientation);
}

void TouchInputMapperTest::prepareVirtualKeys() {
    mFakePolicy->addVirtualKeyDefinition(String8(DEVICE_NAME), VIRTUAL_KEYS[0]);
    mFakePolicy->addVirtualKeyDefinition(String8(DEVICE_NAME), VIRTUAL_KEYS[1]);
    mFakeEventHub->addKey(DEVICE_ID, KEY_HOME, AKEYCODE_HOME, POLICY_FLAG_WAKE);
    mFakeEventHub->addKey(DEVICE_ID, KEY_MENU, AKEYCODE_MENU, POLICY_FLAG_WAKE);
}

int32_t TouchInputMapperTest::toRawX(float displayX) {
    return int32_t(displayX * (RAW_X_MAX - RAW_X_MIN) / DISPLAY_WIDTH + RAW_X_MIN);
}

int32_t TouchInputMapperTest::toRawY(float displayY) {
    return int32_t(displayY * (RAW_Y_MAX - RAW_Y_MIN) / DISPLAY_HEIGHT + RAW_Y_MIN);
}

float TouchInputMapperTest::toDisplayX(int32_t rawX) {
    return float(rawX - RAW_X_MIN) * DISPLAY_WIDTH / (RAW_X_MAX - RAW_X_MIN);
}

float TouchInputMapperTest::toDisplayY(int32_t rawY) {
    return float(rawY - RAW_Y_MIN) * DISPLAY_HEIGHT / (RAW_Y_MAX - RAW_Y_MIN);
}


// --- SingleTouchInputMapperTest ---

class SingleTouchInputMapperTest : public TouchInputMapperTest {
protected:
    void prepareAxes(int axes);

    void processDown(SingleTouchInputMapper* mapper, int32_t x, int32_t y);
    void processMove(SingleTouchInputMapper* mapper, int32_t x, int32_t y);
    void processUp(SingleTouchInputMapper* mappery);
    void processPressure(SingleTouchInputMapper* mapper, int32_t pressure);
    void processToolMajor(SingleTouchInputMapper* mapper, int32_t toolMajor);
    void processSync(SingleTouchInputMapper* mapper);
};

void SingleTouchInputMapperTest::prepareAxes(int axes) {
    if (axes & POSITION) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_X, RAW_X_MIN, RAW_X_MAX, 0, 0);
        mFakeEventHub->addAxis(DEVICE_ID, ABS_Y, RAW_Y_MIN, RAW_Y_MAX, 0, 0);
    }
    if (axes & PRESSURE) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_PRESSURE, RAW_PRESSURE_MIN, RAW_PRESSURE_MAX, 0, 0);
    }
    if (axes & TOOL) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_TOOL_WIDTH, RAW_TOOL_MIN, RAW_TOOL_MAX, 0, 0);
    }
}

void SingleTouchInputMapperTest::processDown(SingleTouchInputMapper* mapper, int32_t x, int32_t y) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_TOUCH, 0, 1, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_X, 0, x, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_Y, 0, y, 0);
}

void SingleTouchInputMapperTest::processMove(SingleTouchInputMapper* mapper, int32_t x, int32_t y) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_X, 0, x, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_Y, 0, y, 0);
}

void SingleTouchInputMapperTest::processUp(SingleTouchInputMapper* mapper) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_KEY, BTN_TOUCH, 0, 0, 0);
}

void SingleTouchInputMapperTest::processPressure(
        SingleTouchInputMapper* mapper, int32_t pressure) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_PRESSURE, 0, pressure, 0);
}

void SingleTouchInputMapperTest::processToolMajor(
        SingleTouchInputMapper* mapper, int32_t toolMajor) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_TOOL_WIDTH, 0, toolMajor, 0);
}

void SingleTouchInputMapperTest::processSync(SingleTouchInputMapper* mapper) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SYN, SYN_REPORT, 0, 0, 0);
}


TEST_F(SingleTouchInputMapperTest, GetSources_WhenNotAttachedToADisplay_ReturnsTouchPad) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, -1);
    prepareAxes(POSITION);
    addMapperAndConfigure(mapper);

    ASSERT_EQ(AINPUT_SOURCE_TOUCHPAD, mapper->getSources());
}

TEST_F(SingleTouchInputMapperTest, GetSources_WhenAttachedToADisplay_ReturnsTouchScreen) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareAxes(POSITION);
    addMapperAndConfigure(mapper);

    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, mapper->getSources());
}

TEST_F(SingleTouchInputMapperTest, GetKeyCodeState) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    // Unknown key.
    ASSERT_EQ(AKEY_STATE_UNKNOWN, mapper->getKeyCodeState(AINPUT_SOURCE_ANY, AKEYCODE_A));

    // Virtual key is down.
    int32_t x = toRawX(VIRTUAL_KEYS[0].centerX);
    int32_t y = toRawY(VIRTUAL_KEYS[0].centerY);
    processDown(mapper, x, y);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    ASSERT_EQ(AKEY_STATE_VIRTUAL, mapper->getKeyCodeState(AINPUT_SOURCE_ANY, AKEYCODE_HOME));

    // Virtual key is up.
    processUp(mapper);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    ASSERT_EQ(AKEY_STATE_UP, mapper->getKeyCodeState(AINPUT_SOURCE_ANY, AKEYCODE_HOME));
}

TEST_F(SingleTouchInputMapperTest, GetScanCodeState) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    // Unknown key.
    ASSERT_EQ(AKEY_STATE_UNKNOWN, mapper->getScanCodeState(AINPUT_SOURCE_ANY, KEY_A));

    // Virtual key is down.
    int32_t x = toRawX(VIRTUAL_KEYS[0].centerX);
    int32_t y = toRawY(VIRTUAL_KEYS[0].centerY);
    processDown(mapper, x, y);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    ASSERT_EQ(AKEY_STATE_VIRTUAL, mapper->getScanCodeState(AINPUT_SOURCE_ANY, KEY_HOME));

    // Virtual key is up.
    processUp(mapper);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    ASSERT_EQ(AKEY_STATE_UP, mapper->getScanCodeState(AINPUT_SOURCE_ANY, KEY_HOME));
}

TEST_F(SingleTouchInputMapperTest, MarkSupportedKeyCodes) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    const int32_t keys[2] = { AKEYCODE_HOME, AKEYCODE_A };
    uint8_t flags[2] = { 0, 0 };
    ASSERT_TRUE(mapper->markSupportedKeyCodes(AINPUT_SOURCE_ANY, 2, keys, flags));
    ASSERT_TRUE(flags[0]);
    ASSERT_FALSE(flags[1]);
}

TEST_F(SingleTouchInputMapperTest, Reset_WhenVirtualKeysAreDown_SendsUp) {
    // Note: Ideally we should send cancels but the implementation is more straightforward
    // with up and this will only happen if a device is forcibly removed.
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    mFakeContext->setGlobalMetaState(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON);

    // Press virtual key.
    int32_t x = toRawX(VIRTUAL_KEYS[0].centerX);
    int32_t y = toRawY(VIRTUAL_KEYS[0].centerY);
    processDown(mapper, x, y);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    // Reset.  Since key is down, synthesize key up.
    mapper->reset();

    FakeInputDispatcher::NotifyKeyArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    //ASSERT_EQ(ARBITRARY_TIME, args.eventTime);
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, args.source);
    ASSERT_EQ(POLICY_FLAG_VIRTUAL, args.policyFlags);
    ASSERT_EQ(AKEY_EVENT_ACTION_UP, args.action);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY, args.flags);
    ASSERT_EQ(AKEYCODE_HOME, args.keyCode);
    ASSERT_EQ(KEY_HOME, args.scanCode);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(ARBITRARY_TIME, args.downTime);
}

TEST_F(SingleTouchInputMapperTest, Reset_WhenNothingIsPressed_NothingMuchHappens) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    // Press virtual key.
    int32_t x = toRawX(VIRTUAL_KEYS[0].centerX);
    int32_t y = toRawY(VIRTUAL_KEYS[0].centerY);
    processDown(mapper, x, y);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    // Release virtual key.
    processUp(mapper);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled());

    // Reset.  Since no key is down, nothing happens.
    mapper->reset();

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasNotCalled());
}

TEST_F(SingleTouchInputMapperTest, Process_WhenVirtualKeyIsPressedAndReleasedNormally_SendsKeyDownAndKeyUp) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    mFakeContext->setGlobalMetaState(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON);

    FakeInputDispatcher::NotifyKeyArgs args;

    // Press virtual key.
    int32_t x = toRawX(VIRTUAL_KEYS[0].centerX);
    int32_t y = toRawY(VIRTUAL_KEYS[0].centerY);
    processDown(mapper, x, y);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(ARBITRARY_TIME, args.eventTime);
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, args.source);
    ASSERT_EQ(POLICY_FLAG_VIRTUAL, args.policyFlags);
    ASSERT_EQ(AKEY_EVENT_ACTION_DOWN, args.action);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY, args.flags);
    ASSERT_EQ(AKEYCODE_HOME, args.keyCode);
    ASSERT_EQ(KEY_HOME, args.scanCode);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(ARBITRARY_TIME, args.downTime);

    // Release virtual key.
    processUp(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&args));
    ASSERT_EQ(ARBITRARY_TIME, args.eventTime);
    ASSERT_EQ(DEVICE_ID, args.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, args.source);
    ASSERT_EQ(POLICY_FLAG_VIRTUAL, args.policyFlags);
    ASSERT_EQ(AKEY_EVENT_ACTION_UP, args.action);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY, args.flags);
    ASSERT_EQ(AKEYCODE_HOME, args.keyCode);
    ASSERT_EQ(KEY_HOME, args.scanCode);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, args.metaState);
    ASSERT_EQ(ARBITRARY_TIME, args.downTime);

    // Should not have sent any motions.
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
}

TEST_F(SingleTouchInputMapperTest, Process_WhenVirtualKeyIsPressedAndMovedOutOfBounds_SendsKeyDownAndKeyCancel) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    mFakeContext->setGlobalMetaState(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON);

    FakeInputDispatcher::NotifyKeyArgs keyArgs;

    // Press virtual key.
    int32_t x = toRawX(VIRTUAL_KEYS[0].centerX);
    int32_t y = toRawY(VIRTUAL_KEYS[0].centerY);
    processDown(mapper, x, y);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&keyArgs));
    ASSERT_EQ(ARBITRARY_TIME, keyArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, keyArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, keyArgs.source);
    ASSERT_EQ(POLICY_FLAG_VIRTUAL, keyArgs.policyFlags);
    ASSERT_EQ(AKEY_EVENT_ACTION_DOWN, keyArgs.action);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY, keyArgs.flags);
    ASSERT_EQ(AKEYCODE_HOME, keyArgs.keyCode);
    ASSERT_EQ(KEY_HOME, keyArgs.scanCode);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, keyArgs.metaState);
    ASSERT_EQ(ARBITRARY_TIME, keyArgs.downTime);

    // Move out of bounds.  This should generate a cancel and a pointer down since we moved
    // into the display area.
    y -= 100;
    processMove(mapper, x, y);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasCalled(&keyArgs));
    ASSERT_EQ(ARBITRARY_TIME, keyArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, keyArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_KEYBOARD, keyArgs.source);
    ASSERT_EQ(POLICY_FLAG_VIRTUAL, keyArgs.policyFlags);
    ASSERT_EQ(AKEY_EVENT_ACTION_UP, keyArgs.action);
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM | AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY
            | AKEY_EVENT_FLAG_CANCELED, keyArgs.flags);
    ASSERT_EQ(AKEYCODE_HOME, keyArgs.keyCode);
    ASSERT_EQ(KEY_HOME, keyArgs.scanCode);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, keyArgs.metaState);
    ASSERT_EQ(ARBITRARY_TIME, keyArgs.downTime);

    FakeInputDispatcher::NotifyMotionArgs motionArgs;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x), toDisplayY(y), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Keep moving out of bounds.  Should generate a pointer move.
    y -= 50;
    processMove(mapper, x, y);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x), toDisplayY(y), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Release out of bounds.  Should generate a pointer up.
    processUp(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x), toDisplayY(y), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Should not have sent any more keys or motions.
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasNotCalled());
}

TEST_F(SingleTouchInputMapperTest, Process_WhenTouchStartsOutsideDisplayAndMovesIn_SendsDownAsTouchEntersDisplay) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    mFakeContext->setGlobalMetaState(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON);

    FakeInputDispatcher::NotifyMotionArgs motionArgs;

    // Initially go down out of bounds.
    int32_t x = -10;
    int32_t y = -10;
    processDown(mapper, x, y);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasNotCalled());

    // Move into the display area.  Should generate a pointer down.
    x = 50;
    y = 75;
    processMove(mapper, x, y);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x), toDisplayY(y), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Release.  Should generate a pointer up.
    processUp(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x), toDisplayY(y), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Should not have sent any more keys or motions.
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasNotCalled());
}

TEST_F(SingleTouchInputMapperTest, Process_NormalSingleTouchGesture) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    mFakeContext->setGlobalMetaState(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON);

    FakeInputDispatcher::NotifyMotionArgs motionArgs;

    // Down.
    int32_t x = 100;
    int32_t y = 125;
    processDown(mapper, x, y);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x), toDisplayY(y), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Move.
    x += 50;
    y += 75;
    processMove(mapper, x, y);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x), toDisplayY(y), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Up.
    processUp(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x), toDisplayY(y), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Should not have sent any more keys or motions.
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasNotCalled());
}

TEST_F(SingleTouchInputMapperTest, Process_Rotation) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareAxes(POSITION);
    addMapperAndConfigure(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;

    // Rotation 0.
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    processDown(mapper, toRawX(50), toRawY(75));
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_NEAR(50, args.pointerCoords[0].x, 1);
    ASSERT_NEAR(75, args.pointerCoords[0].y, 1);

    processUp(mapper);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled());

    // Rotation 90.
    prepareDisplay(InputReaderPolicyInterface::ROTATION_90);
    processDown(mapper, toRawX(50), toRawY(75));
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_NEAR(75, args.pointerCoords[0].x, 1);
    ASSERT_NEAR(DISPLAY_WIDTH - 50, args.pointerCoords[0].y, 1);

    processUp(mapper);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled());

    // Rotation 180.
    prepareDisplay(InputReaderPolicyInterface::ROTATION_180);
    processDown(mapper, toRawX(50), toRawY(75));
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_NEAR(DISPLAY_WIDTH - 50, args.pointerCoords[0].x, 1);
    ASSERT_NEAR(DISPLAY_HEIGHT - 75, args.pointerCoords[0].y, 1);

    processUp(mapper);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled());

    // Rotation 270.
    prepareDisplay(InputReaderPolicyInterface::ROTATION_270);
    processDown(mapper, toRawX(50), toRawY(75));
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_NEAR(DISPLAY_HEIGHT - 75, args.pointerCoords[0].x, 1);
    ASSERT_NEAR(50, args.pointerCoords[0].y, 1);

    processUp(mapper);
    processSync(mapper);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled());
}

TEST_F(SingleTouchInputMapperTest, Process_AllAxes_DefaultCalibration) {
    SingleTouchInputMapper* mapper = new SingleTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION | PRESSURE | TOOL);
    addMapperAndConfigure(mapper);

    // These calculations are based on the input device calibration documentation.
    int32_t rawX = 100;
    int32_t rawY = 200;
    int32_t rawPressure = 10;
    int32_t rawToolMajor = 12;

    float x = toDisplayX(rawX);
    float y = toDisplayY(rawY);
    float pressure = float(rawPressure) / RAW_PRESSURE_MAX;
    float size = float(rawToolMajor) / RAW_TOOL_MAX;
    float tool = min(DISPLAY_WIDTH, DISPLAY_HEIGHT) * size;
    float touch = min(tool * pressure, tool);

    processDown(mapper, rawX, rawY);
    processPressure(mapper, rawPressure);
    processToolMajor(mapper, rawToolMajor);
    processSync(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            x, y, pressure, size, touch, touch, tool, tool, 0));
}


// --- MultiTouchInputMapperTest ---

class MultiTouchInputMapperTest : public TouchInputMapperTest {
protected:
    void prepareAxes(int axes);

    void processPosition(MultiTouchInputMapper* mapper, int32_t x, int32_t y);
    void processTouchMajor(MultiTouchInputMapper* mapper, int32_t touchMajor);
    void processTouchMinor(MultiTouchInputMapper* mapper, int32_t touchMinor);
    void processToolMajor(MultiTouchInputMapper* mapper, int32_t toolMajor);
    void processToolMinor(MultiTouchInputMapper* mapper, int32_t toolMinor);
    void processOrientation(MultiTouchInputMapper* mapper, int32_t orientation);
    void processPressure(MultiTouchInputMapper* mapper, int32_t pressure);
    void processId(MultiTouchInputMapper* mapper, int32_t id);
    void processMTSync(MultiTouchInputMapper* mapper);
    void processSync(MultiTouchInputMapper* mapper);
};

void MultiTouchInputMapperTest::prepareAxes(int axes) {
    if (axes & POSITION) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_POSITION_X, RAW_X_MIN, RAW_X_MAX, 0, 0);
        mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_POSITION_Y, RAW_Y_MIN, RAW_Y_MAX, 0, 0);
    }
    if (axes & TOUCH) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_TOUCH_MAJOR, RAW_TOUCH_MIN, RAW_TOUCH_MAX, 0, 0);
        if (axes & MINOR) {
            mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_TOUCH_MINOR,
                    RAW_TOUCH_MIN, RAW_TOUCH_MAX, 0, 0);
        }
    }
    if (axes & TOOL) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_WIDTH_MAJOR, RAW_TOOL_MIN, RAW_TOOL_MAX, 0, 0);
        if (axes & MINOR) {
            mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_WIDTH_MINOR,
                    RAW_TOOL_MAX, RAW_TOOL_MAX, 0, 0);
        }
    }
    if (axes & ORIENTATION) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_ORIENTATION,
                RAW_ORIENTATION_MIN, RAW_ORIENTATION_MAX, 0, 0);
    }
    if (axes & PRESSURE) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_PRESSURE,
                RAW_PRESSURE_MIN, RAW_PRESSURE_MAX, 0, 0);
    }
    if (axes & ID) {
        mFakeEventHub->addAxis(DEVICE_ID, ABS_MT_TRACKING_ID,
                RAW_ID_MIN, RAW_ID_MAX, 0, 0);
    }
}

void MultiTouchInputMapperTest::processPosition(
        MultiTouchInputMapper* mapper, int32_t x, int32_t y) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_POSITION_X, 0, x, 0);
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_POSITION_Y, 0, y, 0);
}

void MultiTouchInputMapperTest::processTouchMajor(
        MultiTouchInputMapper* mapper, int32_t touchMajor) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_TOUCH_MAJOR, 0, touchMajor, 0);
}

void MultiTouchInputMapperTest::processTouchMinor(
        MultiTouchInputMapper* mapper, int32_t touchMinor) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_TOUCH_MINOR, 0, touchMinor, 0);
}

void MultiTouchInputMapperTest::processToolMajor(
        MultiTouchInputMapper* mapper, int32_t toolMajor) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_WIDTH_MAJOR, 0, toolMajor, 0);
}

void MultiTouchInputMapperTest::processToolMinor(
        MultiTouchInputMapper* mapper, int32_t toolMinor) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_WIDTH_MINOR, 0, toolMinor, 0);
}

void MultiTouchInputMapperTest::processOrientation(
        MultiTouchInputMapper* mapper, int32_t orientation) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_ORIENTATION, 0, orientation, 0);
}

void MultiTouchInputMapperTest::processPressure(
        MultiTouchInputMapper* mapper, int32_t pressure) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_PRESSURE, 0, pressure, 0);
}

void MultiTouchInputMapperTest::processId(
        MultiTouchInputMapper* mapper, int32_t id) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_ABS, ABS_MT_TRACKING_ID, 0, id, 0);
}

void MultiTouchInputMapperTest::processMTSync(MultiTouchInputMapper* mapper) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SYN, SYN_MT_REPORT, 0, 0, 0);
}

void MultiTouchInputMapperTest::processSync(MultiTouchInputMapper* mapper) {
    process(mapper, ARBITRARY_TIME, DEVICE_ID, EV_SYN, SYN_REPORT, 0, 0, 0);
}


TEST_F(MultiTouchInputMapperTest, Process_NormalMultiTouchGesture_WithoutTrackingIds) {
    MultiTouchInputMapper* mapper = new MultiTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    mFakeContext->setGlobalMetaState(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON);

    FakeInputDispatcher::NotifyMotionArgs motionArgs;

    // Two fingers down at once.
    int32_t x1 = 100, y1 = 125, x2 = 300, y2 = 500;
    processPosition(mapper, x1, y1);
    processMTSync(mapper);
    processPosition(mapper, x2, y2);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x1), toDisplayY(y1), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_DOWN | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_EQ(1, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x1), toDisplayY(y1), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Move.
    x1 += 10; y1 += 15; x2 += 5; y2 -= 10;
    processPosition(mapper, x1, y1);
    processMTSync(mapper);
    processPosition(mapper, x2, y2);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_EQ(1, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x1), toDisplayY(y1), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // First finger up.
    x2 += 15; y2 -= 20;
    processPosition(mapper, x2, y2);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_UP | (0 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_EQ(1, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x1), toDisplayY(y1), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(1, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Move.
    x2 += 20; y2 -= 25;
    processPosition(mapper, x2, y2);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(1, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // New finger down.
    int32_t x3 = 700, y3 = 300;
    processPosition(mapper, x2, y2);
    processMTSync(mapper);
    processPosition(mapper, x3, y3);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_DOWN | (0 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_EQ(1, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x3), toDisplayY(y3), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Second finger up.
    x3 += 30; y3 -= 20;
    processPosition(mapper, x3, y3);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_UP | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_EQ(1, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x3), toDisplayY(y3), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x3), toDisplayY(y3), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Last finger up.
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.eventTime);
    ASSERT_EQ(DEVICE_ID, motionArgs.deviceId);
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, motionArgs.source);
    ASSERT_EQ(uint32_t(0), motionArgs.policyFlags);
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, motionArgs.action);
    ASSERT_EQ(0, motionArgs.flags);
    ASSERT_EQ(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON, motionArgs.metaState);
    ASSERT_EQ(0, motionArgs.edgeFlags);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(0, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x3), toDisplayY(y3), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NEAR(X_PRECISION, motionArgs.xPrecision, EPSILON);
    ASSERT_NEAR(Y_PRECISION, motionArgs.yPrecision, EPSILON);
    ASSERT_EQ(ARBITRARY_TIME, motionArgs.downTime);

    // Should not have sent any more keys or motions.
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasNotCalled());
}

TEST_F(MultiTouchInputMapperTest, Process_NormalMultiTouchGesture_WithTrackingIds) {
    MultiTouchInputMapper* mapper = new MultiTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION | ID);
    prepareVirtualKeys();
    addMapperAndConfigure(mapper);

    mFakeContext->setGlobalMetaState(AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_ON);

    FakeInputDispatcher::NotifyMotionArgs motionArgs;

    // Two fingers down at once.
    int32_t x1 = 100, y1 = 125, x2 = 300, y2 = 500;
    processPosition(mapper, x1, y1);
    processId(mapper, 1);
    processMTSync(mapper);
    processPosition(mapper, x2, y2);
    processId(mapper, 2);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, motionArgs.action);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(1, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x1), toDisplayY(y1), 1, 0, 0, 0, 0, 0, 0));

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_DOWN | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            motionArgs.action);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(1, motionArgs.pointerIds[0]);
    ASSERT_EQ(2, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x1), toDisplayY(y1), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));

    // Move.
    x1 += 10; y1 += 15; x2 += 5; y2 -= 10;
    processPosition(mapper, x1, y1);
    processId(mapper, 1);
    processMTSync(mapper);
    processPosition(mapper, x2, y2);
    processId(mapper, 2);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(1, motionArgs.pointerIds[0]);
    ASSERT_EQ(2, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x1), toDisplayY(y1), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));

    // First finger up.
    x2 += 15; y2 -= 20;
    processPosition(mapper, x2, y2);
    processId(mapper, 2);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_UP | (0 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            motionArgs.action);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(1, motionArgs.pointerIds[0]);
    ASSERT_EQ(2, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x1), toDisplayY(y1), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(2, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));

    // Move.
    x2 += 20; y2 -= 25;
    processPosition(mapper, x2, y2);
    processId(mapper, 2);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(2, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));

    // New finger down.
    int32_t x3 = 700, y3 = 300;
    processPosition(mapper, x2, y2);
    processId(mapper, 2);
    processMTSync(mapper);
    processPosition(mapper, x3, y3);
    processId(mapper, 3);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_DOWN | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            motionArgs.action);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(2, motionArgs.pointerIds[0]);
    ASSERT_EQ(3, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x3), toDisplayY(y3), 1, 0, 0, 0, 0, 0, 0));

    // Second finger up.
    x3 += 30; y3 -= 20;
    processPosition(mapper, x3, y3);
    processId(mapper, 3);
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_UP | (0 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            motionArgs.action);
    ASSERT_EQ(size_t(2), motionArgs.pointerCount);
    ASSERT_EQ(2, motionArgs.pointerIds[0]);
    ASSERT_EQ(3, motionArgs.pointerIds[1]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x2), toDisplayY(y2), 1, 0, 0, 0, 0, 0, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[1],
            toDisplayX(x3), toDisplayY(y3), 1, 0, 0, 0, 0, 0, 0));

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, motionArgs.action);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(3, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x3), toDisplayY(y3), 1, 0, 0, 0, 0, 0, 0));

    // Last finger up.
    processMTSync(mapper);
    processSync(mapper);

    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&motionArgs));
    ASSERT_EQ(AMOTION_EVENT_ACTION_UP, motionArgs.action);
    ASSERT_EQ(size_t(1), motionArgs.pointerCount);
    ASSERT_EQ(3, motionArgs.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(motionArgs.pointerCoords[0],
            toDisplayX(x3), toDisplayY(y3), 1, 0, 0, 0, 0, 0, 0));

    // Should not have sent any more keys or motions.
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyKeyWasNotCalled());
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasNotCalled());
}

TEST_F(MultiTouchInputMapperTest, Process_AllAxes_WithDefaultCalibration) {
    MultiTouchInputMapper* mapper = new MultiTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION | TOUCH | TOOL | PRESSURE | ORIENTATION | ID | MINOR);
    addMapperAndConfigure(mapper);

    // These calculations are based on the input device calibration documentation.
    int32_t rawX = 100;
    int32_t rawY = 200;
    int32_t rawTouchMajor = 7;
    int32_t rawTouchMinor = 6;
    int32_t rawToolMajor = 9;
    int32_t rawToolMinor = 8;
    int32_t rawPressure = 11;
    int32_t rawOrientation = 3;
    int32_t id = 5;

    float x = toDisplayX(rawX);
    float y = toDisplayY(rawY);
    float pressure = float(rawPressure) / RAW_PRESSURE_MAX;
    float size = avg(rawToolMajor, rawToolMinor) / RAW_TOOL_MAX;
    float toolMajor = float(min(DISPLAY_WIDTH, DISPLAY_HEIGHT)) * rawToolMajor / RAW_TOOL_MAX;
    float toolMinor = float(min(DISPLAY_WIDTH, DISPLAY_HEIGHT)) * rawToolMinor / RAW_TOOL_MAX;
    float touchMajor = min(toolMajor * pressure, toolMajor);
    float touchMinor = min(toolMinor * pressure, toolMinor);
    float orientation = float(rawOrientation) / RAW_ORIENTATION_MAX * M_PI_2;

    processPosition(mapper, rawX, rawY);
    processTouchMajor(mapper, rawTouchMajor);
    processTouchMinor(mapper, rawTouchMinor);
    processToolMajor(mapper, rawToolMajor);
    processToolMinor(mapper, rawToolMinor);
    processPressure(mapper, rawPressure);
    processOrientation(mapper, rawOrientation);
    processId(mapper, id);
    processMTSync(mapper);
    processSync(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(id, args.pointerIds[0]);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            x, y, pressure, size, touchMajor, touchMinor, toolMajor, toolMinor, orientation));
}

TEST_F(MultiTouchInputMapperTest, Process_TouchAndToolAxes_GeometricCalibration) {
    MultiTouchInputMapper* mapper = new MultiTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION | TOUCH | TOOL | MINOR);
    prepareCalibration("touch.touchSize.calibration", "geometric");
    prepareCalibration("touch.toolSize.calibration", "geometric");
    addMapperAndConfigure(mapper);

    // These calculations are based on the input device calibration documentation.
    int32_t rawX = 100;
    int32_t rawY = 200;
    int32_t rawTouchMajor = 140;
    int32_t rawTouchMinor = 120;
    int32_t rawToolMajor = 180;
    int32_t rawToolMinor = 160;

    float x = toDisplayX(rawX);
    float y = toDisplayY(rawY);
    float pressure = float(rawTouchMajor) / RAW_TOUCH_MAX;
    float size = avg(rawToolMajor, rawToolMinor) / RAW_TOOL_MAX;
    float scale = avg(float(DISPLAY_WIDTH) / (RAW_X_MAX - RAW_X_MIN),
            float(DISPLAY_HEIGHT) / (RAW_Y_MAX - RAW_Y_MIN));
    float toolMajor = float(rawToolMajor) * scale;
    float toolMinor = float(rawToolMinor) * scale;
    float touchMajor = min(float(rawTouchMajor) * scale, toolMajor);
    float touchMinor = min(float(rawTouchMinor) * scale, toolMinor);

    processPosition(mapper, rawX, rawY);
    processTouchMajor(mapper, rawTouchMajor);
    processTouchMinor(mapper, rawTouchMinor);
    processToolMajor(mapper, rawToolMajor);
    processToolMinor(mapper, rawToolMinor);
    processMTSync(mapper);
    processSync(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            x, y, pressure, size, touchMajor, touchMinor, toolMajor, toolMinor, 0));
}

TEST_F(MultiTouchInputMapperTest, Process_TouchToolPressureSizeAxes_SummedLinearCalibration) {
    MultiTouchInputMapper* mapper = new MultiTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION | TOUCH | TOOL);
    prepareCalibration("touch.touchSize.calibration", "pressure");
    prepareCalibration("touch.toolSize.calibration", "linear");
    prepareCalibration("touch.toolSize.linearScale", "10");
    prepareCalibration("touch.toolSize.linearBias", "160");
    prepareCalibration("touch.toolSize.isSummed", "1");
    prepareCalibration("touch.pressure.calibration", "amplitude");
    prepareCalibration("touch.pressure.source", "touch");
    prepareCalibration("touch.pressure.scale", "0.01");
    addMapperAndConfigure(mapper);

    // These calculations are based on the input device calibration documentation.
    // Note: We only provide a single common touch/tool value because the device is assumed
    //       not to emit separate values for each pointer (isSummed = 1).
    int32_t rawX = 100;
    int32_t rawY = 200;
    int32_t rawX2 = 150;
    int32_t rawY2 = 250;
    int32_t rawTouchMajor = 60;
    int32_t rawToolMajor = 5;

    float x = toDisplayX(rawX);
    float y = toDisplayY(rawY);
    float x2 = toDisplayX(rawX2);
    float y2 = toDisplayY(rawY2);
    float pressure = float(rawTouchMajor) * 0.01f;
    float size = float(rawToolMajor) / RAW_TOOL_MAX;
    float tool = (float(rawToolMajor) * 10.0f + 160.0f) / 2;
    float touch = min(tool * pressure, tool);

    processPosition(mapper, rawX, rawY);
    processTouchMajor(mapper, rawTouchMajor);
    processToolMajor(mapper, rawToolMajor);
    processMTSync(mapper);
    processPosition(mapper, rawX2, rawY2);
    processTouchMajor(mapper, rawTouchMajor);
    processToolMajor(mapper, rawToolMajor);
    processMTSync(mapper);
    processSync(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_DOWN, args.action);
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_EQ(AMOTION_EVENT_ACTION_POINTER_DOWN | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            args.action);
    ASSERT_EQ(size_t(2), args.pointerCount);
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            x, y, pressure, size, touch, touch, tool, tool, 0));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[1],
            x2, y2, pressure, size, touch, touch, tool, tool, 0));
}

TEST_F(MultiTouchInputMapperTest, Process_TouchToolPressureSizeAxes_AreaCalibration) {
    MultiTouchInputMapper* mapper = new MultiTouchInputMapper(mDevice, DISPLAY_ID);
    prepareDisplay(InputReaderPolicyInterface::ROTATION_0);
    prepareAxes(POSITION | TOUCH | TOOL);
    prepareCalibration("touch.touchSize.calibration", "pressure");
    prepareCalibration("touch.toolSize.calibration", "area");
    prepareCalibration("touch.toolSize.areaScale", "22");
    prepareCalibration("touch.toolSize.areaBias", "1");
    prepareCalibration("touch.toolSize.linearScale", "9.2");
    prepareCalibration("touch.toolSize.linearBias", "3");
    prepareCalibration("touch.pressure.calibration", "amplitude");
    prepareCalibration("touch.pressure.source", "touch");
    prepareCalibration("touch.pressure.scale", "0.01");
    addMapperAndConfigure(mapper);

    // These calculations are based on the input device calibration documentation.
    int32_t rawX = 100;
    int32_t rawY = 200;
    int32_t rawTouchMajor = 60;
    int32_t rawToolMajor = 5;

    float x = toDisplayX(rawX);
    float y = toDisplayY(rawY);
    float pressure = float(rawTouchMajor) * 0.01f;
    float size = float(rawToolMajor) / RAW_TOOL_MAX;
    float tool = sqrtf(float(rawToolMajor) * 22.0f + 1.0f) * 9.2f + 3.0f;
    float touch = min(tool * pressure, tool);

    processPosition(mapper, rawX, rawY);
    processTouchMajor(mapper, rawTouchMajor);
    processToolMajor(mapper, rawToolMajor);
    processMTSync(mapper);
    processSync(mapper);

    FakeInputDispatcher::NotifyMotionArgs args;
    ASSERT_NO_FATAL_FAILURE(mFakeDispatcher->assertNotifyMotionWasCalled(&args));
    ASSERT_NO_FATAL_FAILURE(assertPointerCoords(args.pointerCoords[0],
            x, y, pressure, size, touch, touch, tool, tool, 0));
}

} // namespace android
