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

#ifndef _UI_INPUT_H
#define _UI_INPUT_H

/**
 * Native input event structures.
 */

#include <android/input.h>
#include <utils/Vector.h>
#include <utils/KeyedVector.h>
#include <utils/Timers.h>
#include <utils/RefBase.h>
#include <utils/String8.h>

/*
 * Additional private constants not defined in ndk/ui/input.h.
 */
enum {
    /*
     * Private control to determine when an app is tracking a key sequence.
     */
    AKEY_EVENT_FLAG_START_TRACKING = 0x40000000
};

enum {
    /*
     * Indicates that an input device has switches.
     * This input source flag is hidden from the API because switches are only used by the system
     * and applications have no way to interact with them.
     */
    AINPUT_SOURCE_SWITCH = 0x80000000,
};

/*
 * Maximum number of pointers supported per motion event.
 * Smallest number of pointers is 1.
 */
#define MAX_POINTERS 10

/*
 * Maximum pointer id value supported in a motion event.
 * Smallest pointer id is 0.
 * (This is limited by our use of BitSet32 to track pointer assignments.)
 */
#define MAX_POINTER_ID 31

/*
 * Declare a concrete type for the NDK's input event forward declaration.
 */
struct AInputEvent {
    virtual ~AInputEvent() { }
};

/*
 * Declare a concrete type for the NDK's input device forward declaration.
 */
struct AInputDevice {
    virtual ~AInputDevice() { }
};


namespace android {

/*
 * Flags that flow alongside events in the input dispatch system to help with certain
 * policy decisions such as waking from device sleep.
 *
 * These flags are also defined in frameworks/base/core/java/android/view/WindowManagerPolicy.java.
 */
enum {
    /* These flags originate in RawEvents and are generally set in the key map.
     * See also labels for policy flags in KeycodeLabels.h. */

    POLICY_FLAG_WAKE = 0x00000001,
    POLICY_FLAG_WAKE_DROPPED = 0x00000002,
    POLICY_FLAG_SHIFT = 0x00000004,
    POLICY_FLAG_CAPS_LOCK = 0x00000008,
    POLICY_FLAG_ALT = 0x00000010,
    POLICY_FLAG_ALT_GR = 0x00000020,
    POLICY_FLAG_MENU = 0x00000040,
    POLICY_FLAG_LAUNCHER = 0x00000080,
    POLICY_FLAG_VIRTUAL = 0x00000100,

    POLICY_FLAG_RAW_MASK = 0x0000ffff,

    /* These flags are set by the input dispatcher. */

    // Indicates that the input event was injected.
    POLICY_FLAG_INJECTED = 0x01000000,

    // Indicates that the input event is from a trusted source such as a directly attached
    // input device or an application with system-wide event injection permission.
    POLICY_FLAG_TRUSTED = 0x02000000,

    /* These flags are set by the input reader policy as it intercepts each event. */

    // Indicates that the screen was off when the event was received and the event
    // should wake the device.
    POLICY_FLAG_WOKE_HERE = 0x10000000,

    // Indicates that the screen was dim when the event was received and the event
    // should brighten the device.
    POLICY_FLAG_BRIGHT_HERE = 0x20000000,

    // Indicates that the event should be dispatched to applications.
    // The input event should still be sent to the InputDispatcher so that it can see all
    // input events received include those that it will not deliver.
    POLICY_FLAG_PASS_TO_USER = 0x40000000,
};

/*
 * Describes the basic configuration of input devices that are present.
 */
struct InputConfiguration {
    enum {
        TOUCHSCREEN_UNDEFINED = 0,
        TOUCHSCREEN_NOTOUCH = 1,
        TOUCHSCREEN_STYLUS = 2,
        TOUCHSCREEN_FINGER = 3
    };

    enum {
        KEYBOARD_UNDEFINED = 0,
        KEYBOARD_NOKEYS = 1,
        KEYBOARD_QWERTY = 2,
        KEYBOARD_12KEY = 3
    };

    enum {
        NAVIGATION_UNDEFINED = 0,
        NAVIGATION_NONAV = 1,
        NAVIGATION_DPAD = 2,
        NAVIGATION_TRACKBALL = 3,
        NAVIGATION_WHEEL = 4
    };

    int32_t touchScreen;
    int32_t keyboard;
    int32_t navigation;
};

/*
 * Pointer coordinate data.
 */
struct PointerCoords {
    float x;
    float y;
    float pressure;
    float size;
    float touchMajor;
    float touchMinor;
    float toolMajor;
    float toolMinor;
    float orientation;
};

/*
 * Input events.
 */
class InputEvent : public AInputEvent {
public:
    virtual ~InputEvent() { }

    virtual int32_t getType() const = 0;

    inline int32_t getDeviceId() const { return mDeviceId; }

    inline int32_t getSource() const { return mSource; }
    
protected:
    void initialize(int32_t deviceId, int32_t source);
    void initialize(const InputEvent& from);

private:
    int32_t mDeviceId;
    int32_t mSource;
};

/*
 * Key events.
 */
class KeyEvent : public InputEvent {
public:
    virtual ~KeyEvent() { }

    virtual int32_t getType() const { return AINPUT_EVENT_TYPE_KEY; }

    inline int32_t getAction() const { return mAction; }

    inline int32_t getFlags() const { return mFlags; }

    inline int32_t getKeyCode() const { return mKeyCode; }

    inline int32_t getScanCode() const { return mScanCode; }

    inline int32_t getMetaState() const { return mMetaState; }

    inline int32_t getRepeatCount() const { return mRepeatCount; }

    inline nsecs_t getDownTime() const { return mDownTime; }

    inline nsecs_t getEventTime() const { return mEventTime; }

    // Return true if this event may have a default action implementation.
    static bool hasDefaultAction(int32_t keyCode);
    bool hasDefaultAction() const;

    // Return true if this event represents a system key.
    static bool isSystemKey(int32_t keyCode);
    bool isSystemKey() const;
    
    void initialize(
            int32_t deviceId,
            int32_t source,
            int32_t action,
            int32_t flags,
            int32_t keyCode,
            int32_t scanCode,
            int32_t metaState,
            int32_t repeatCount,
            nsecs_t downTime,
            nsecs_t eventTime);
    void initialize(const KeyEvent& from);

private:
    int32_t mAction;
    int32_t mFlags;
    int32_t mKeyCode;
    int32_t mScanCode;
    int32_t mMetaState;
    int32_t mRepeatCount;
    nsecs_t mDownTime;
    nsecs_t mEventTime;
};

/*
 * Motion events.
 */
class MotionEvent : public InputEvent {
public:
    virtual ~MotionEvent() { }

    virtual int32_t getType() const { return AINPUT_EVENT_TYPE_MOTION; }

    inline int32_t getAction() const { return mAction; }

    inline int32_t getFlags() const { return mFlags; }

    inline int32_t getEdgeFlags() const { return mEdgeFlags; }

    inline int32_t getMetaState() const { return mMetaState; }

    inline float getXOffset() const { return mXOffset; }

    inline float getYOffset() const { return mYOffset; }

    inline float getXPrecision() const { return mXPrecision; }

    inline float getYPrecision() const { return mYPrecision; }

    inline nsecs_t getDownTime() const { return mDownTime; }

    inline size_t getPointerCount() const { return mPointerIds.size(); }

    inline int32_t getPointerId(size_t pointerIndex) const { return mPointerIds[pointerIndex]; }

    inline nsecs_t getEventTime() const { return mSampleEventTimes[getHistorySize()]; }

    inline float getRawX(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).x;
    }

    inline float getRawY(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).y;
    }

    inline float getX(size_t pointerIndex) const {
        return getRawX(pointerIndex) + mXOffset;
    }

    inline float getY(size_t pointerIndex) const {
        return getRawY(pointerIndex) + mYOffset;
    }

    inline float getPressure(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).pressure;
    }

    inline float getSize(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).size;
    }

    inline float getTouchMajor(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).touchMajor;
    }

    inline float getTouchMinor(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).touchMinor;
    }

    inline float getToolMajor(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).toolMajor;
    }

    inline float getToolMinor(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).toolMinor;
    }

    inline float getOrientation(size_t pointerIndex) const {
        return getCurrentPointerCoords(pointerIndex).orientation;
    }

    inline size_t getHistorySize() const { return mSampleEventTimes.size() - 1; }

    inline nsecs_t getHistoricalEventTime(size_t historicalIndex) const {
        return mSampleEventTimes[historicalIndex];
    }

    inline float getHistoricalRawX(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).x;
    }

    inline float getHistoricalRawY(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).y;
    }

    inline float getHistoricalX(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalRawX(pointerIndex, historicalIndex) + mXOffset;
    }

    inline float getHistoricalY(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalRawY(pointerIndex, historicalIndex) + mYOffset;
    }

    inline float getHistoricalPressure(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).pressure;
    }

    inline float getHistoricalSize(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).size;
    }

    inline float getHistoricalTouchMajor(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).touchMajor;
    }

    inline float getHistoricalTouchMinor(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).touchMinor;
    }

    inline float getHistoricalToolMajor(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).toolMajor;
    }

    inline float getHistoricalToolMinor(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).toolMinor;
    }

    inline float getHistoricalOrientation(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalPointerCoords(pointerIndex, historicalIndex).orientation;
    }

    void initialize(
            int32_t deviceId,
            int32_t source,
            int32_t action,
            int32_t flags,
            int32_t edgeFlags,
            int32_t metaState,
            float xOffset,
            float yOffset,
            float xPrecision,
            float yPrecision,
            nsecs_t downTime,
            nsecs_t eventTime,
            size_t pointerCount,
            const int32_t* pointerIds,
            const PointerCoords* pointerCoords);

    void addSample(
            nsecs_t eventTime,
            const PointerCoords* pointerCoords);

    void offsetLocation(float xOffset, float yOffset);

    // Low-level accessors.
    inline const int32_t* getPointerIds() const { return mPointerIds.array(); }
    inline const nsecs_t* getSampleEventTimes() const { return mSampleEventTimes.array(); }
    inline const PointerCoords* getSamplePointerCoords() const {
            return mSamplePointerCoords.array();
    }

private:
    int32_t mAction;
    int32_t mFlags;
    int32_t mEdgeFlags;
    int32_t mMetaState;
    float mXOffset;
    float mYOffset;
    float mXPrecision;
    float mYPrecision;
    nsecs_t mDownTime;
    Vector<int32_t> mPointerIds;
    Vector<nsecs_t> mSampleEventTimes;
    Vector<PointerCoords> mSamplePointerCoords;

    inline const PointerCoords& getCurrentPointerCoords(size_t pointerIndex) const {
        return mSamplePointerCoords[getHistorySize() * getPointerCount() + pointerIndex];
    }

    inline const PointerCoords& getHistoricalPointerCoords(
            size_t pointerIndex, size_t historicalIndex) const {
        return mSamplePointerCoords[historicalIndex * getPointerCount() + pointerIndex];
    }
};

/*
 * Input event factory.
 */
class InputEventFactoryInterface {
protected:
    virtual ~InputEventFactoryInterface() { }

public:
    InputEventFactoryInterface() { }

    virtual KeyEvent* createKeyEvent() = 0;
    virtual MotionEvent* createMotionEvent() = 0;
};

/*
 * A simple input event factory implementation that uses a single preallocated instance
 * of each type of input event that are reused for each request.
 */
class PreallocatedInputEventFactory : public InputEventFactoryInterface {
public:
    PreallocatedInputEventFactory() { }
    virtual ~PreallocatedInputEventFactory() { }

    virtual KeyEvent* createKeyEvent() { return & mKeyEvent; }
    virtual MotionEvent* createMotionEvent() { return & mMotionEvent; }

private:
    KeyEvent mKeyEvent;
    MotionEvent mMotionEvent;
};

/*
 * Describes the characteristics and capabilities of an input device.
 */
class InputDeviceInfo {
public:
    InputDeviceInfo();
    InputDeviceInfo(const InputDeviceInfo& other);
    ~InputDeviceInfo();

    struct MotionRange {
        float min;
        float max;
        float flat;
        float fuzz;
    };

    void initialize(int32_t id, const String8& name);

    inline int32_t getId() const { return mId; }
    inline const String8 getName() const { return mName; }
    inline uint32_t getSources() const { return mSources; }

    const MotionRange* getMotionRange(int32_t rangeType) const;

    void addSource(uint32_t source);
    void addMotionRange(int32_t rangeType, float min, float max, float flat, float fuzz);
    void addMotionRange(int32_t rangeType, const MotionRange& range);

    inline void setKeyboardType(int32_t keyboardType) { mKeyboardType = keyboardType; }
    inline int32_t getKeyboardType() const { return mKeyboardType; }

    inline const KeyedVector<int32_t, MotionRange> getMotionRanges() const {
        return mMotionRanges;
    }

private:
    int32_t mId;
    String8 mName;
    uint32_t mSources;
    int32_t mKeyboardType;

    KeyedVector<int32_t, MotionRange> mMotionRanges;
};


} // namespace android

#endif // _UI_INPUT_H
