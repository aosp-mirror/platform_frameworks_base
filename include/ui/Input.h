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
#include <utils/BitSet.h>

#ifdef HAVE_ANDROID_OS
class SkMatrix;
#endif

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
 * SystemUiVisibility constants from View.
 */
enum {
    ASYSTEM_UI_VISIBILITY_STATUS_BAR_VISIBLE = 0,
    ASYSTEM_UI_VISIBILITY_STATUS_BAR_HIDDEN = 0x00000001,
};

/*
 * Maximum number of pointers supported per motion event.
 * Smallest number of pointers is 1.
 * (We want at least 10 but some touch controllers obstensibly configured for 10 pointers
 * will occasionally emit 11.  There is not much harm making this constant bigger.)
 */
#define MAX_POINTERS 16

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

#ifdef HAVE_ANDROID_OS
class Parcel;
#endif

/*
 * Flags that flow alongside events in the input dispatch system to help with certain
 * policy decisions such as waking from device sleep.
 *
 * These flags are also defined in frameworks/base/core/java/android/view/WindowManagerPolicy.java.
 */
enum {
    /* These flags originate in RawEvents and are generally set in the key map.
     * NOTE: If you edit these flags, also edit labels in KeycodeLabels.h. */

    POLICY_FLAG_WAKE = 0x00000001,
    POLICY_FLAG_WAKE_DROPPED = 0x00000002,
    POLICY_FLAG_SHIFT = 0x00000004,
    POLICY_FLAG_CAPS_LOCK = 0x00000008,
    POLICY_FLAG_ALT = 0x00000010,
    POLICY_FLAG_ALT_GR = 0x00000020,
    POLICY_FLAG_MENU = 0x00000040,
    POLICY_FLAG_LAUNCHER = 0x00000080,
    POLICY_FLAG_VIRTUAL = 0x00000100,
    POLICY_FLAG_FUNCTION = 0x00000200,

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
 * Button state.
 */
enum {
    // Primary button pressed (left mouse button).
    BUTTON_STATE_PRIMARY = 1 << 0,
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
    enum { MAX_AXES = 14 }; // 14 so that sizeof(PointerCoords) == 64

    // Bitfield of axes that are present in this structure.
    uint64_t bits;

    // Values of axes that are stored in this structure packed in order by axis id
    // for each axis that is present in the structure according to 'bits'.
    float values[MAX_AXES];

    inline void clear() {
        bits = 0;
    }

    float getAxisValue(int32_t axis) const;
    status_t setAxisValue(int32_t axis, float value);
    float* editAxisValue(int32_t axis);

    void scale(float scale);

#ifdef HAVE_ANDROID_OS
    status_t readFromParcel(Parcel* parcel);
    status_t writeToParcel(Parcel* parcel) const;
#endif

    bool operator==(const PointerCoords& other) const;
    inline bool operator!=(const PointerCoords& other) const {
        return !(*this == other);
    }

    void copyFrom(const PointerCoords& other);

private:
    void tooManyAxes(int axis);
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

    inline void setSource(int32_t source) { mSource = source; }

protected:
    void initialize(int32_t deviceId, int32_t source);
    void initialize(const InputEvent& from);

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

protected:
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

    inline int32_t getActionMasked() const { return mAction & AMOTION_EVENT_ACTION_MASK; }

    inline int32_t getActionIndex() const {
        return (mAction & AMOTION_EVENT_ACTION_POINTER_INDEX_MASK)
                >> AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT;
    }

    inline void setAction(int32_t action) { mAction = action; }

    inline int32_t getFlags() const { return mFlags; }

    inline int32_t getEdgeFlags() const { return mEdgeFlags; }

    inline void setEdgeFlags(int32_t edgeFlags) { mEdgeFlags = edgeFlags; }

    inline int32_t getMetaState() const { return mMetaState; }

    inline void setMetaState(int32_t metaState) { mMetaState = metaState; }

    inline float getXOffset() const { return mXOffset; }

    inline float getYOffset() const { return mYOffset; }

    inline float getXPrecision() const { return mXPrecision; }

    inline float getYPrecision() const { return mYPrecision; }

    inline nsecs_t getDownTime() const { return mDownTime; }

    inline size_t getPointerCount() const { return mPointerIds.size(); }

    inline int32_t getPointerId(size_t pointerIndex) const { return mPointerIds[pointerIndex]; }

    inline nsecs_t getEventTime() const { return mSampleEventTimes[getHistorySize()]; }

    const PointerCoords* getRawPointerCoords(size_t pointerIndex) const;

    float getRawAxisValue(int32_t axis, size_t pointerIndex) const;

    inline float getRawX(size_t pointerIndex) const {
        return getRawAxisValue(AMOTION_EVENT_AXIS_X, pointerIndex);
    }

    inline float getRawY(size_t pointerIndex) const {
        return getRawAxisValue(AMOTION_EVENT_AXIS_Y, pointerIndex);
    }

    float getAxisValue(int32_t axis, size_t pointerIndex) const;

    inline float getX(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_X, pointerIndex);
    }

    inline float getY(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_Y, pointerIndex);
    }

    inline float getPressure(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_PRESSURE, pointerIndex);
    }

    inline float getSize(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_SIZE, pointerIndex);
    }

    inline float getTouchMajor(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, pointerIndex);
    }

    inline float getTouchMinor(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, pointerIndex);
    }

    inline float getToolMajor(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, pointerIndex);
    }

    inline float getToolMinor(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR, pointerIndex);
    }

    inline float getOrientation(size_t pointerIndex) const {
        return getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, pointerIndex);
    }

    inline size_t getHistorySize() const { return mSampleEventTimes.size() - 1; }

    inline nsecs_t getHistoricalEventTime(size_t historicalIndex) const {
        return mSampleEventTimes[historicalIndex];
    }

    const PointerCoords* getHistoricalRawPointerCoords(
            size_t pointerIndex, size_t historicalIndex) const;

    float getHistoricalRawAxisValue(int32_t axis, size_t pointerIndex,
            size_t historicalIndex) const;

    inline float getHistoricalRawX(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalRawAxisValue(
                AMOTION_EVENT_AXIS_X, pointerIndex, historicalIndex);
    }

    inline float getHistoricalRawY(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalRawAxisValue(
                AMOTION_EVENT_AXIS_Y, pointerIndex, historicalIndex);
    }

    float getHistoricalAxisValue(int32_t axis, size_t pointerIndex, size_t historicalIndex) const;

    inline float getHistoricalX(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_X, pointerIndex, historicalIndex);
    }

    inline float getHistoricalY(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_Y, pointerIndex, historicalIndex);
    }

    inline float getHistoricalPressure(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_PRESSURE, pointerIndex, historicalIndex);
    }

    inline float getHistoricalSize(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_SIZE, pointerIndex, historicalIndex);
    }

    inline float getHistoricalTouchMajor(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_TOUCH_MAJOR, pointerIndex, historicalIndex);
    }

    inline float getHistoricalTouchMinor(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_TOUCH_MINOR, pointerIndex, historicalIndex);
    }

    inline float getHistoricalToolMajor(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_TOOL_MAJOR, pointerIndex, historicalIndex);
    }

    inline float getHistoricalToolMinor(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_TOOL_MINOR, pointerIndex, historicalIndex);
    }

    inline float getHistoricalOrientation(size_t pointerIndex, size_t historicalIndex) const {
        return getHistoricalAxisValue(
                AMOTION_EVENT_AXIS_ORIENTATION, pointerIndex, historicalIndex);
    }

    ssize_t findPointerIndex(int32_t pointerId) const;

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

    void copyFrom(const MotionEvent* other, bool keepHistory);

    void addSample(
            nsecs_t eventTime,
            const PointerCoords* pointerCoords);

    void offsetLocation(float xOffset, float yOffset);

    void scale(float scaleFactor);

#ifdef HAVE_ANDROID_OS
    void transform(const SkMatrix* matrix);

    status_t readFromParcel(Parcel* parcel);
    status_t writeToParcel(Parcel* parcel) const;
#endif

    static bool isTouchEvent(int32_t source, int32_t action);
    inline bool isTouchEvent() const {
        return isTouchEvent(mSource, mAction);
    }

    // Low-level accessors.
    inline const int32_t* getPointerIds() const { return mPointerIds.array(); }
    inline const nsecs_t* getSampleEventTimes() const { return mSampleEventTimes.array(); }
    inline const PointerCoords* getSamplePointerCoords() const {
            return mSamplePointerCoords.array();
    }

protected:
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
 * Calculates the velocity of pointer movements over time.
 */
class VelocityTracker {
public:
    struct Position {
        float x, y;
    };

    VelocityTracker();

    // Resets the velocity tracker state.
    void clear();

    // Resets the velocity tracker state for specific pointers.
    // Call this method when some pointers have changed and may be reusing
    // an id that was assigned to a different pointer earlier.
    void clearPointers(BitSet32 idBits);

    // Adds movement information for a set of pointers.
    // The idBits bitfield specifies the pointer ids of the pointers whose positions
    // are included in the movement.
    // The positions array contains position information for each pointer in order by
    // increasing id.  Its size should be equal to the number of one bits in idBits.
    void addMovement(nsecs_t eventTime, BitSet32 idBits, const Position* positions);

    // Adds movement information for all pointers in a MotionEvent, including historical samples.
    void addMovement(const MotionEvent* event);

    // Gets the velocity of the specified pointer id in position units per second.
    // Returns false and sets the velocity components to zero if there is no movement
    // information for the pointer.
    bool getVelocity(uint32_t id, float* outVx, float* outVy) const;

    // Gets the active pointer id, or -1 if none.
    inline int32_t getActivePointerId() const { return mActivePointerId; }

    // Gets a bitset containing all pointer ids from the most recent movement.
    inline BitSet32 getCurrentPointerIdBits() const { return mMovements[mIndex].idBits; }

private:
    // Number of samples to keep.
    static const uint32_t HISTORY_SIZE = 10;

    // Oldest sample to consider when calculating the velocity.
    static const nsecs_t MAX_AGE = 200 * 1000000; // 200 ms

    // The minimum duration between samples when estimating velocity.
    static const nsecs_t MIN_DURATION = 10 * 1000000; // 10 ms

    struct Movement {
        nsecs_t eventTime;
        BitSet32 idBits;
        Position positions[MAX_POINTERS];
    };

    uint32_t mIndex;
    Movement mMovements[HISTORY_SIZE];
    int32_t mActivePointerId;
};


/*
 * Specifies parameters that govern pointer or wheel acceleration.
 */
struct VelocityControlParameters {
    // A scale factor that is multiplied with the raw velocity deltas
    // prior to applying any other velocity control factors.  The scale
    // factor should be used to adapt the input device resolution
    // (eg. counts per inch) to the output device resolution (eg. pixels per inch).
    //
    // Must be a positive value.
    // Default is 1.0 (no scaling).
    float scale;

    // The scaled speed at which acceleration begins to be applied.
    // This value establishes the upper bound of a low speed regime for
    // small precise motions that are performed without any acceleration.
    //
    // Must be a non-negative value.
    // Default is 0.0 (no low threshold).
    float lowThreshold;

    // The scaled speed at which maximum acceleration is applied.
    // The difference between highThreshold and lowThreshold controls
    // the range of speeds over which the acceleration factor is interpolated.
    // The wider the range, the smoother the acceleration.
    //
    // Must be a non-negative value greater than or equal to lowThreshold.
    // Default is 0.0 (no high threshold).
    float highThreshold;

    // The acceleration factor.
    // When the speed is above the low speed threshold, the velocity will scaled
    // by an interpolated value between 1.0 and this amount.
    //
    // Must be a positive greater than or equal to 1.0.
    // Default is 1.0 (no acceleration).
    float acceleration;

    VelocityControlParameters() :
            scale(1.0f), lowThreshold(0.0f), highThreshold(0.0f), acceleration(1.0f) {
    }

    VelocityControlParameters(float scale, float lowThreshold,
            float highThreshold, float acceleration) :
            scale(scale), lowThreshold(lowThreshold),
            highThreshold(highThreshold), acceleration(acceleration) {
    }
};

/*
 * Implements mouse pointer and wheel speed control and acceleration.
 */
class VelocityControl {
public:
    VelocityControl();

    /* Sets the various parameters. */
    void setParameters(const VelocityControlParameters& parameters);

    /* Resets the current movement counters to zero.
     * This has the effect of nullifying any acceleration. */
    void reset();

    /* Translates a raw movement delta into an appropriately
     * scaled / accelerated delta based on the current velocity. */
    void move(nsecs_t eventTime, float* deltaX, float* deltaY);

private:
    // If no movements are received within this amount of time,
    // we assume the movement has stopped and reset the movement counters.
    static const nsecs_t STOP_TIME = 500 * 1000000; // 500 ms

    VelocityControlParameters mParameters;

    nsecs_t mLastMovementTime;
    VelocityTracker::Position mRawPosition;
    VelocityTracker mVelocityTracker;
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
        int32_t axis;
        uint32_t source;
        float min;
        float max;
        float flat;
        float fuzz;
    };

    void initialize(int32_t id, const String8& name);

    inline int32_t getId() const { return mId; }
    inline const String8 getName() const { return mName; }
    inline uint32_t getSources() const { return mSources; }

    const MotionRange* getMotionRange(int32_t axis, uint32_t source) const;

    void addSource(uint32_t source);
    void addMotionRange(int32_t axis, uint32_t source,
            float min, float max, float flat, float fuzz);
    void addMotionRange(const MotionRange& range);

    inline void setKeyboardType(int32_t keyboardType) { mKeyboardType = keyboardType; }
    inline int32_t getKeyboardType() const { return mKeyboardType; }

    inline const Vector<MotionRange>& getMotionRanges() const {
        return mMotionRanges;
    }

private:
    int32_t mId;
    String8 mName;
    uint32_t mSources;
    int32_t mKeyboardType;

    Vector<MotionRange> mMotionRanges;
};

/*
 * Identifies a device.
 */
struct InputDeviceIdentifier {
    inline InputDeviceIdentifier() :
            bus(0), vendor(0), product(0), version(0) {
    }

    String8 name;
    String8 location;
    String8 uniqueId;
    uint16_t bus;
    uint16_t vendor;
    uint16_t product;
    uint16_t version;
};

/* Types of input device configuration files. */
enum InputDeviceConfigurationFileType {
    INPUT_DEVICE_CONFIGURATION_FILE_TYPE_CONFIGURATION = 0,     /* .idc file */
    INPUT_DEVICE_CONFIGURATION_FILE_TYPE_KEY_LAYOUT = 1,        /* .kl file */
    INPUT_DEVICE_CONFIGURATION_FILE_TYPE_KEY_CHARACTER_MAP = 2, /* .kcm file */
};

/*
 * Gets the path of an input device configuration file, if one is available.
 * Considers both system provided and user installed configuration files.
 *
 * The device identifier is used to construct several default configuration file
 * names to try based on the device name, vendor, product, and version.
 *
 * Returns an empty string if not found.
 */
extern String8 getInputDeviceConfigurationFilePathByDeviceIdentifier(
        const InputDeviceIdentifier& deviceIdentifier,
        InputDeviceConfigurationFileType type);

/*
 * Gets the path of an input device configuration file, if one is available.
 * Considers both system provided and user installed configuration files.
 *
 * The name is case-sensitive and is used to construct the filename to resolve.
 * All characters except 'a'-'z', 'A'-'Z', '0'-'9', '-', and '_' are replaced by underscores.
 *
 * Returns an empty string if not found.
 */
extern String8 getInputDeviceConfigurationFilePathByName(
        const String8& name, InputDeviceConfigurationFileType type);

} // namespace android

#endif // _UI_INPUT_H
