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
#include <ui/InputDispatchPolicy.h>
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

/** Amount that trackball needs to move in order to generate a key event. */
#define TRACKBALL_MOVEMENT_THRESHOLD 6

/* Slop distance for jumpy pointer detection.
 * The vertical range of the screen divided by this is our epsilon value. */
#define JUMPY_EPSILON_DIVISOR 212

/* Number of jumpy points to drop for touchscreens that need it. */
#define JUMPY_TRANSITION_DROPS 3
#define JUMPY_DROP_LIMIT 3

/* Maximum squared distance for averaging.
 * If moving farther than this, turn of averaging to avoid lag in response. */
#define AVERAGING_DISTANCE_LIMIT (75 * 75)

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


/* Processes raw input events and sends cooked event data to an input dispatcher
 * in accordance with the input dispatch policy. */
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
};

/* The input reader reads raw event data from the event hub and processes it into input events
 * that it sends to the input dispatcher.  Some functions of the input reader are controlled
 * by the input dispatch policy, such as early event filtering in low power states.
 */
class InputReader : public InputReaderInterface {
public:
    InputReader(const sp<EventHubInterface>& eventHub,
            const sp<InputDispatchPolicyInterface>& policy,
            const sp<InputDispatcherInterface>& dispatcher);
    virtual ~InputReader();

    virtual void loopOnce();

    virtual bool getCurrentVirtualKey(int32_t* outKeyCode, int32_t* outScanCode) const;

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

    // current virtual key information
    int32_t mGlobalVirtualKeyCode;
    int32_t mGlobalVirtualScanCode;

    // combined key meta state
    int32_t mGlobalMetaState;

    sp<EventHubInterface> mEventHub;
    sp<InputDispatchPolicyInterface> mPolicy;
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
    void onSwitch(nsecs_t when, InputDevice* device, bool down, int32_t code);
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

    // global meta state management for all devices
    void resetGlobalMetaState();
    int32_t globalMetaState();

    // virtual key management
    void updateGlobalVirtualKeyState();
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
