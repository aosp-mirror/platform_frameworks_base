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

#ifndef _UI_INPUT_READER_H
#define _UI_INPUT_READER_H

#include <ui/EventHub.h>
#include <ui/Input.h>
#include <ui/InputDispatcher.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <utils/Timers.h>
#include <utils/RefBase.h>
#include <utils/String8.h>
#include <utils/BitSet.h>

#include <stddef.h>
#include <unistd.h>

/* Maximum pointer id value supported.
 * (This is limited by our use of BitSet32 to track pointer assignments.) */
#define MAX_POINTER_ID 32

/* Maximum number of historical samples to average. */
#define AVERAGING_HISTORY_SIZE 5


namespace android {

extern int32_t updateMetaState(int32_t keyCode, bool down, int32_t oldMetaState);
extern int32_t rotateKeyCode(int32_t keyCode, int32_t orientation);

/*
 * An input device structure tracks the state of a single input device.
 *
 * This structure is only used by ReaderThread and is not intended to be shared with
 * DispatcherThread (because that would require locking).  This works out fine because
 * DispatcherThread is only interested in cooked event data anyways and does not need
 * any of the low-level data from InputDevice.
 */
struct InputDevice {
    struct AbsoluteAxisInfo {
        int32_t minValue;  // minimum value
        int32_t maxValue;  // maximum value
        int32_t range;     // range of values, equal to maxValue - minValue
        int32_t flat;      // center flat position, eg. flat == 8 means center is between -8 and 8
        int32_t fuzz;      // error tolerance, eg. fuzz == 4 means value is +/- 4 due to noise
    };

    struct VirtualKey {
        int32_t keyCode;
        int32_t scanCode;
        uint32_t flags;

        // computed hit box, specified in touch screen coords based on known display size
        int32_t hitLeft;
        int32_t hitTop;
        int32_t hitRight;
        int32_t hitBottom;

        inline bool isHit(int32_t x, int32_t y) const {
            return x >= hitLeft && x <= hitRight && y >= hitTop && y <= hitBottom;
        }
    };

    struct KeyboardState {
        struct Current {
            int32_t metaState;
            nsecs_t downTime; // time of most recent key down
        } current;

        void reset();
    };

    struct TrackballState {
        struct Accumulator {
            enum {
                FIELD_BTN_MOUSE = 1,
                FIELD_REL_X = 2,
                FIELD_REL_Y = 4
            };

            uint32_t fields;

            bool btnMouse;
            int32_t relX;
            int32_t relY;

            inline void clear() {
                fields = 0;
            }

            inline bool isDirty() {
                return fields != 0;
            }
        } accumulator;

        struct Current {
            bool down;
            nsecs_t downTime;
        } current;

        struct Precalculated {
            float xScale;
            float yScale;
            float xPrecision;
            float yPrecision;
        } precalculated;

        void reset();
    };

    struct SingleTouchScreenState {
        struct Accumulator {
            enum {
                FIELD_BTN_TOUCH = 1,
                FIELD_ABS_X = 2,
                FIELD_ABS_Y = 4,
                FIELD_ABS_PRESSURE = 8,
                FIELD_ABS_TOOL_WIDTH = 16
            };

            uint32_t fields;

            bool btnTouch;
            int32_t absX;
            int32_t absY;
            int32_t absPressure;
            int32_t absToolWidth;

            inline void clear() {
                fields = 0;
            }

            inline bool isDirty() {
                return fields != 0;
            }
        } accumulator;

        struct Current {
            bool down;
            int32_t x;
            int32_t y;
            int32_t pressure;
            int32_t size;
        } current;

        void reset();
    };

    struct MultiTouchScreenState {
        struct Accumulator {
            enum {
                FIELD_ABS_MT_POSITION_X = 1,
                FIELD_ABS_MT_POSITION_Y = 2,
                FIELD_ABS_MT_TOUCH_MAJOR = 4,
                FIELD_ABS_MT_WIDTH_MAJOR = 8,
                FIELD_ABS_MT_TRACKING_ID = 16
            };

            uint32_t pointerCount;
            struct Pointer {
                uint32_t fields;

                int32_t absMTPositionX;
                int32_t absMTPositionY;
                int32_t absMTTouchMajor;
                int32_t absMTWidthMajor;
                int32_t absMTTrackingId;

                inline void clear() {
                    fields = 0;
                }
            } pointers[MAX_POINTERS + 1]; // + 1 to remove the need for extra range checks

            inline void clear() {
                pointerCount = 0;
                pointers[0].clear();
            }

            inline bool isDirty() {
                return pointerCount != 0;
            }
        } accumulator;

        void reset();
    };

    struct PointerData {
        uint32_t id;
        int32_t x;
        int32_t y;
        int32_t pressure;
        int32_t size;
    };

    struct TouchData {
        uint32_t pointerCount;
        PointerData pointers[MAX_POINTERS];
        BitSet32 idBits;
        uint32_t idToIndex[MAX_POINTER_ID];

        void copyFrom(const TouchData& other);

        inline void clear() {
            pointerCount = 0;
            idBits.clear();
        }
    };

    // common state used for both single-touch and multi-touch screens after the initial
    // touch decoding has been performed
    struct TouchScreenState {
        Vector<VirtualKey> virtualKeys;

        struct Parameters {
            bool useBadTouchFilter;
            bool useJumpyTouchFilter;
            bool useAveragingTouchFilter;

            AbsoluteAxisInfo xAxis;
            AbsoluteAxisInfo yAxis;
            AbsoluteAxisInfo pressureAxis;
            AbsoluteAxisInfo sizeAxis;
        } parameters;

        // The touch data of the current sample being processed.
        TouchData currentTouch;

        // The touch data of the previous sample that was processed.  This is updated
        // incrementally while the current sample is being processed.
        TouchData lastTouch;

        // The time the primary pointer last went down.
        nsecs_t downTime;

        struct CurrentVirtualKeyState {
            bool down;
            nsecs_t downTime;
            int32_t keyCode;
            int32_t scanCode;
        } currentVirtualKey;

        struct AveragingTouchFilterState {
            // Individual history tracks are stored by pointer id
            uint32_t historyStart[MAX_POINTERS];
            uint32_t historyEnd[MAX_POINTERS];
            struct {
                struct {
                    int32_t x;
                    int32_t y;
                    int32_t pressure;
                } pointers[MAX_POINTERS];
            } historyData[AVERAGING_HISTORY_SIZE];
        } averagingTouchFilter;

        struct JumpTouchFilterState {
            int32_t jumpyPointsDropped;
        } jumpyTouchFilter;

        struct Precalculated {
            float xScale;
            float yScale;
            float pressureScale;
            float sizeScale;
        } precalculated;

        void reset();

        bool applyBadTouchFilter();
        bool applyJumpyTouchFilter();
        void applyAveragingTouchFilter();
        void calculatePointerIds();

        bool isPointInsideDisplay(int32_t x, int32_t y) const;
    };

    InputDevice(int32_t id, uint32_t classes, String8 name);

    int32_t id;
    uint32_t classes;
    String8 name;
    bool ignored;

    KeyboardState keyboard;
    TrackballState trackball;
    TouchScreenState touchScreen;
    union {
        SingleTouchScreenState singleTouchScreen;
        MultiTouchScreenState multiTouchScreen;
    };

    void reset();

    inline bool isKeyboard() const { return classes & INPUT_DEVICE_CLASS_KEYBOARD; }
    inline bool isAlphaKey() const { return classes & INPUT_DEVICE_CLASS_ALPHAKEY; }
    inline bool isTrackball() const { return classes & INPUT_DEVICE_CLASS_TRACKBALL; }
    inline bool isDPad() const { return classes & INPUT_DEVICE_CLASS_DPAD; }
    inline bool isSingleTouchScreen() const { return (classes
            & (INPUT_DEVICE_CLASS_TOUCHSCREEN | INPUT_DEVICE_CLASS_TOUCHSCREEN_MT))
            == INPUT_DEVICE_CLASS_TOUCHSCREEN; }
    inline bool isMultiTouchScreen() const { return classes
            & INPUT_DEVICE_CLASS_TOUCHSCREEN_MT; }
    inline bool isTouchScreen() const { return classes
            & (INPUT_DEVICE_CLASS_TOUCHSCREEN | INPUT_DEVICE_CLASS_TOUCHSCREEN_MT); }
};


/*
 * Input reader policy interface.
 *
 * The input reader policy is used by the input reader to interact with the Window Manager
 * and other system components.
 *
 * The actual implementation is partially supported by callbacks into the DVM
 * via JNI.  This interface is also mocked in the unit tests.
 */
class InputReaderPolicyInterface : public virtual RefBase {
protected:
    InputReaderPolicyInterface() { }
    virtual ~InputReaderPolicyInterface() { }

public:
    /* Display orientations. */
    enum {
        ROTATION_0 = 0,
        ROTATION_90 = 1,
        ROTATION_180 = 2,
        ROTATION_270 = 3
    };

    /* Actions returned by interceptXXX methods. */
    enum {
        // The input dispatcher should do nothing and discard the input unless other
        // flags are set.
        ACTION_NONE = 0,

        // The input dispatcher should dispatch the input to the application.
        ACTION_DISPATCH = 0x00000001,

        // The input dispatcher should perform special filtering in preparation for
        // a pending app switch.
        ACTION_APP_SWITCH_COMING = 0x00000002,

        // The input dispatcher should add POLICY_FLAG_WOKE_HERE to the policy flags it
        // passes through the dispatch pipeline.
        ACTION_WOKE_HERE = 0x00000004,

        // The input dispatcher should add POLICY_FLAG_BRIGHT_HERE to the policy flags it
        // passes through the dispatch pipeline.
        ACTION_BRIGHT_HERE = 0x00000008,

        // The input dispatcher should add POLICY_FLAG_INTERCEPT_DISPATCH to the policy flags
        // it passed through the dispatch pipeline.
        ACTION_INTERCEPT_DISPATCH = 0x00000010
    };

    /* Describes a virtual key. */
    struct VirtualKeyDefinition {
        int32_t scanCode;

        // configured position data, specified in display coords
        int32_t centerX;
        int32_t centerY;
        int32_t width;
        int32_t height;
    };

    /* Gets information about the display with the specified id.
     * Returns true if the display info is available, false otherwise.
     */
    virtual bool getDisplayInfo(int32_t displayId,
            int32_t* width, int32_t* height, int32_t* orientation) = 0;

    /* Provides feedback for a virtual key.
     */
    virtual void virtualKeyFeedback(nsecs_t when, int32_t deviceId,
            int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime) = 0;

    /* Intercepts a key event.
     * The policy can use this method as an opportunity to perform power management functions
     * and early event preprocessing.
     *
     * Returns a policy action constant such as ACTION_DISPATCH.
     */
    virtual int32_t interceptKey(nsecs_t when, int32_t deviceId,
            bool down, int32_t keyCode, int32_t scanCode, uint32_t policyFlags) = 0;

    /* Intercepts a trackball event.
     * The policy can use this method as an opportunity to perform power management functions
     * and early event preprocessing.
     *
     * Returns a policy action constant such as ACTION_DISPATCH.
     */
    virtual int32_t interceptTrackball(nsecs_t when, bool buttonChanged, bool buttonDown,
            bool rolled) = 0;

    /* Intercepts a touch event.
     * The policy can use this method as an opportunity to perform power management functions
     * and early event preprocessing.
     *
     * Returns a policy action constant such as ACTION_DISPATCH.
     */
    virtual int32_t interceptTouch(nsecs_t when) = 0;

    /* Intercepts a switch event.
     * The policy can use this method as an opportunity to perform power management functions
     * and early event preprocessing.
     *
     * Switches are not dispatched to applications so this method should
     * usually return ACTION_NONE.
     */
    virtual int32_t interceptSwitch(nsecs_t when, int32_t switchCode, int32_t switchValue) = 0;

    /* Determines whether to turn on some hacks we have to improve the touch interaction with a
     * certain device whose screen currently is not all that good.
     */
    virtual bool filterTouchEvents() = 0;

    /* Determines whether to turn on some hacks to improve touch interaction with another device
     * where touch coordinate data can get corrupted.
     */
    virtual bool filterJumpyTouchEvents() = 0;

    /* Gets the configured virtual key definitions for an input device. */
    virtual void getVirtualKeyDefinitions(const String8& deviceName,
            Vector<VirtualKeyDefinition>& outVirtualKeyDefinitions) = 0;

    /* Gets the excluded device names for the platform. */
    virtual void getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames) = 0;
};


/* Processes raw input events and sends cooked event data to an input dispatcher. */
class InputReaderInterface : public virtual RefBase {
protected:
    InputReaderInterface() { }
    virtual ~InputReaderInterface() { }

public:
    /* Runs a single iteration of the processing loop.
     * Nominally reads and processes one incoming message from the EventHub.
     *
     * This method should be called on the input reader thread.
     */
    virtual void loopOnce() = 0;

    /* Gets the current virtual key.  Returns false if not down.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual bool getCurrentVirtualKey(int32_t* outKeyCode, int32_t* outScanCode) const = 0;

    /* Gets the current input device configuration.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void getCurrentInputConfiguration(InputConfiguration* outConfiguration) const = 0;

    /*
     * Query current input state.
     *   deviceId may be -1 to search for the device automatically, filtered by class.
     *   deviceClasses may be -1 to ignore device class while searching.
     */
    virtual int32_t getCurrentScanCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t scanCode) const = 0;
    virtual int32_t getCurrentKeyCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t keyCode) const = 0;
    virtual int32_t getCurrentSwitchState(int32_t deviceId, int32_t deviceClasses,
            int32_t sw) const = 0;

    /* Determine whether physical keys exist for the given framework-domain key codes. */
    virtual bool hasKeys(size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) const = 0;
};


/* The input reader reads raw event data from the event hub and processes it into input events
 * that it sends to the input dispatcher.  Some functions of the input reader, such as early
 * event filtering in low power states, are controlled by a separate policy object.
 *
 * IMPORTANT INVARIANT:
 *     Because the policy can potentially block or cause re-entrance into the input reader,
 *     the input reader never calls into the policy while holding its internal locks.
 */
class InputReader : public InputReaderInterface {
public:
    InputReader(const sp<EventHubInterface>& eventHub,
            const sp<InputReaderPolicyInterface>& policy,
            const sp<InputDispatcherInterface>& dispatcher);
    virtual ~InputReader();

    virtual void loopOnce();

    virtual bool getCurrentVirtualKey(int32_t* outKeyCode, int32_t* outScanCode) const;

    virtual void getCurrentInputConfiguration(InputConfiguration* outConfiguration) const;

    virtual int32_t getCurrentScanCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t scanCode) const;
    virtual int32_t getCurrentKeyCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t keyCode) const;
    virtual int32_t getCurrentSwitchState(int32_t deviceId, int32_t deviceClasses,
            int32_t sw) const;

    virtual bool hasKeys(size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) const;

private:
    // Lock that must be acquired while manipulating state that may be concurrently accessed
    // from other threads by input state query methods.  It should be held for as short a
    // time as possible.
    //
    // Exported state:
    //   - global virtual key code and scan code
    //   - device list and immutable properties of devices such as id, name, and class
    //     (but not other internal device state)
    mutable Mutex mExportedStateLock;

    // current virtual key information (lock mExportedStateLock)
    int32_t mExportedVirtualKeyCode;
    int32_t mExportedVirtualScanCode;

    // current input configuration (lock mExportedStateLock)
    InputConfiguration mExportedInputConfiguration;

    // combined key meta state
    int32_t mGlobalMetaState;

    sp<EventHubInterface> mEventHub;
    sp<InputReaderPolicyInterface> mPolicy;
    sp<InputDispatcherInterface> mDispatcher;

    KeyedVector<int32_t, InputDevice*> mDevices;

    // display properties needed to translate touch screen coordinates into display coordinates
    int32_t mDisplayOrientation;
    int32_t mDisplayWidth;
    int32_t mDisplayHeight;

    // low-level input event decoding
    void process(const RawEvent* rawEvent);
    void handleDeviceAdded(const RawEvent* rawEvent);
    void handleDeviceRemoved(const RawEvent* rawEvent);
    void handleSync(const RawEvent* rawEvent);
    void handleKey(const RawEvent* rawEvent);
    void handleRelativeMotion(const RawEvent* rawEvent);
    void handleAbsoluteMotion(const RawEvent* rawEvent);
    void handleSwitch(const RawEvent* rawEvent);

    // input policy processing and dispatch
    void onKey(nsecs_t when, InputDevice* device, bool down,
            int32_t keyCode, int32_t scanCode, uint32_t policyFlags);
    void onSwitch(nsecs_t when, InputDevice* device, int32_t switchCode, int32_t switchValue);
    void onSingleTouchScreenStateChanged(nsecs_t when, InputDevice* device);
    void onMultiTouchScreenStateChanged(nsecs_t when, InputDevice* device);
    void onTouchScreenChanged(nsecs_t when, InputDevice* device, bool havePointerIds);
    void onTrackballStateChanged(nsecs_t when, InputDevice* device);
    void onConfigurationChanged(nsecs_t when);

    bool applyStandardInputDispatchPolicyActions(nsecs_t when,
            int32_t policyActions, uint32_t* policyFlags);

    bool consumeVirtualKeyTouches(nsecs_t when, InputDevice* device, uint32_t policyFlags);
    void dispatchVirtualKey(nsecs_t when, InputDevice* device, uint32_t policyFlags,
            int32_t keyEventAction, int32_t keyEventFlags);
    void dispatchTouches(nsecs_t when, InputDevice* device, uint32_t policyFlags);
    void dispatchTouch(nsecs_t when, InputDevice* device, uint32_t policyFlags,
            InputDevice::TouchData* touch, BitSet32 idBits, int32_t motionEventAction);

    // display
    void resetDisplayProperties();
    bool refreshDisplayProperties();

    // device management
    InputDevice* getDevice(int32_t deviceId);
    InputDevice* getNonIgnoredDevice(int32_t deviceId);
    void addDevice(nsecs_t when, int32_t deviceId);
    void removeDevice(nsecs_t when, InputDevice* device);
    void configureDevice(InputDevice* device);
    void configureDeviceForCurrentDisplaySize(InputDevice* device);
    void configureVirtualKeys(InputDevice* device);
    void configureAbsoluteAxisInfo(InputDevice* device, int axis, const char* name,
            InputDevice::AbsoluteAxisInfo* out);
    void configureExcludedDevices();

    // global meta state management for all devices
    void resetGlobalMetaState();
    int32_t globalMetaState();

    // virtual key management
    void updateExportedVirtualKeyState();

    // input configuration management
    void updateExportedInputConfiguration();
};


/* Reads raw events from the event hub and processes them, endlessly. */
class InputReaderThread : public Thread {
public:
    InputReaderThread(const sp<InputReaderInterface>& reader);
    virtual ~InputReaderThread();

private:
    sp<InputReaderInterface> mReader;

    virtual bool threadLoop();
};

} // namespace android

#endif // _UI_INPUT_READER_H
