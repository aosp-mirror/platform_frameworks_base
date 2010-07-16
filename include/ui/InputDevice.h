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

#ifndef _UI_INPUT_DEVICE_H
#define _UI_INPUT_DEVICE_H

#include <ui/EventHub.h>
#include <ui/Input.h>
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
#define MAX_POINTER_ID 31

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
        bool valid;        // set to true if axis parameters are known, false otherwise

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
                FIELD_ABS_MT_TOUCH_MINOR = 8,
                FIELD_ABS_MT_WIDTH_MAJOR = 16,
                FIELD_ABS_MT_WIDTH_MINOR = 32,
                FIELD_ABS_MT_ORIENTATION = 64,
                FIELD_ABS_MT_TRACKING_ID = 128
            };

            uint32_t pointerCount;
            struct Pointer {
                uint32_t fields;

                int32_t absMTPositionX;
                int32_t absMTPositionY;
                int32_t absMTTouchMajor;
                int32_t absMTTouchMinor;
                int32_t absMTWidthMajor;
                int32_t absMTWidthMinor;
                int32_t absMTOrientation;
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
        int32_t touchMajor;
        int32_t touchMinor;
        int32_t toolMajor;
        int32_t toolMinor;
        int32_t orientation;
    };

    struct TouchData {
        uint32_t pointerCount;
        PointerData pointers[MAX_POINTERS];
        BitSet32 idBits;
        uint32_t idToIndex[MAX_POINTER_ID + 1];

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
            AbsoluteAxisInfo orientationAxis;
        } parameters;

        // The touch data of the current sample being processed.
        TouchData currentTouch;

        // The touch data of the previous sample that was processed.  This is updated
        // incrementally while the current sample is being processed.
        TouchData lastTouch;

        // The time the primary pointer last went down.
        nsecs_t downTime;

        struct CurrentVirtualKeyState {
            enum Status {
                STATUS_UP,
                STATUS_DOWN,
                STATUS_CANCELED
            };

            Status status;
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
            int32_t xOrigin;
            float xScale;

            int32_t yOrigin;
            float yScale;

            int32_t pressureOrigin;
            float pressureScale;

            int32_t sizeOrigin;
            float sizeScale;

            float orientationScale;
        } precalculated;

        void reset();

        bool applyBadTouchFilter();
        bool applyJumpyTouchFilter();
        void applyAveragingTouchFilter();
        void calculatePointerIds();

        bool isPointInsideDisplay(int32_t x, int32_t y) const;
        const InputDevice::VirtualKey* findVirtualKeyHit() const;
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

} // namespace android

#endif // _UI_INPUT_DEVICE_H
