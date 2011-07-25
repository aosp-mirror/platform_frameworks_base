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

#include "EventHub.h"
#include "InputDispatcher.h"
#include "PointerController.h"

#include <ui/Input.h>
#include <ui/DisplayInfo.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <utils/Timers.h>
#include <utils/RefBase.h>
#include <utils/String8.h>
#include <utils/BitSet.h>

#include <stddef.h>
#include <unistd.h>

namespace android {

class InputDevice;
class InputMapper;


/*
 * Input reader configuration.
 *
 * Specifies various options that modify the behavior of the input reader.
 */
struct InputReaderConfiguration {
    // Describes changes that have occurred.
    enum {
        // The pointer speed changed.
        CHANGE_POINTER_SPEED = 1 << 0,

        // The pointer gesture control changed.
        CHANGE_POINTER_GESTURE_ENABLEMENT = 1 << 1,

        // All devices must be reopened.
        CHANGE_MUST_REOPEN = 1 << 31,
    };

    // Gets the amount of time to disable virtual keys after the screen is touched
    // in order to filter out accidental virtual key presses due to swiping gestures
    // or taps near the edge of the display.  May be 0 to disable the feature.
    nsecs_t virtualKeyQuietTime;

    // The excluded device names for the platform.
    // Devices with these names will be ignored.
    Vector<String8> excludedDeviceNames;

    // Velocity control parameters for mouse pointer movements.
    VelocityControlParameters pointerVelocityControlParameters;

    // Velocity control parameters for mouse wheel movements.
    VelocityControlParameters wheelVelocityControlParameters;

    // True if pointer gestures are enabled.
    bool pointerGesturesEnabled;

    // Quiet time between certain pointer gesture transitions.
    // Time to allow for all fingers or buttons to settle into a stable state before
    // starting a new gesture.
    nsecs_t pointerGestureQuietInterval;

    // The minimum speed that a pointer must travel for us to consider switching the active
    // touch pointer to it during a drag.  This threshold is set to avoid switching due
    // to noise from a finger resting on the touch pad (perhaps just pressing it down).
    float pointerGestureDragMinSwitchSpeed; // in pixels per second

    // Tap gesture delay time.
    // The time between down and up must be less than this to be considered a tap.
    nsecs_t pointerGestureTapInterval;

    // Tap drag gesture delay time.
    // The time between the previous tap's up and the next down must be less than
    // this to be considered a drag.  Otherwise, the previous tap is finished and a
    // new tap begins.
    //
    // Note that the previous tap will be held down for this entire duration so this
    // interval must be shorter than the long press timeout.
    nsecs_t pointerGestureTapDragInterval;

    // The distance in pixels that the pointer is allowed to move from initial down
    // to up and still be called a tap.
    float pointerGestureTapSlop; // in pixels

    // Time after the first touch points go down to settle on an initial centroid.
    // This is intended to be enough time to handle cases where the user puts down two
    // fingers at almost but not quite exactly the same time.
    nsecs_t pointerGestureMultitouchSettleInterval;

    // The transition from PRESS to SWIPE or FREEFORM gesture mode is made when
    // at least two pointers have moved at least this far from their starting place.
    float pointerGestureMultitouchMinDistance; // in pixels

    // The transition from PRESS to SWIPE gesture mode can only occur when the
    // cosine of the angle between the two vectors is greater than or equal to than this value
    // which indicates that the vectors are oriented in the same direction.
    // When the vectors are oriented in the exactly same direction, the cosine is 1.0.
    // (In exactly opposite directions, the cosine is -1.0.)
    float pointerGestureSwipeTransitionAngleCosine;

    // The transition from PRESS to SWIPE gesture mode can only occur when the
    // fingers are no more than this far apart relative to the diagonal size of
    // the touch pad.  For example, a ratio of 0.5 means that the fingers must be
    // no more than half the diagonal size of the touch pad apart.
    float pointerGestureSwipeMaxWidthRatio;

    // The gesture movement speed factor relative to the size of the display.
    // Movement speed applies when the fingers are moving in the same direction.
    // Without acceleration, a full swipe of the touch pad diagonal in movement mode
    // will cover this portion of the display diagonal.
    float pointerGestureMovementSpeedRatio;

    // The gesture zoom speed factor relative to the size of the display.
    // Zoom speed applies when the fingers are mostly moving relative to each other
    // to execute a scale gesture or similar.
    // Without acceleration, a full swipe of the touch pad diagonal in zoom mode
    // will cover this portion of the display diagonal.
    float pointerGestureZoomSpeedRatio;

    InputReaderConfiguration() :
            virtualKeyQuietTime(0),
            pointerVelocityControlParameters(1.0f, 500.0f, 3000.0f, 3.0f),
            wheelVelocityControlParameters(1.0f, 15.0f, 50.0f, 4.0f),
            pointerGesturesEnabled(true),
            pointerGestureQuietInterval(100 * 1000000LL), // 100 ms
            pointerGestureDragMinSwitchSpeed(50), // 50 pixels per second
            pointerGestureTapInterval(150 * 1000000LL), // 150 ms
            pointerGestureTapDragInterval(150 * 1000000LL), // 150 ms
            pointerGestureTapSlop(10.0f), // 10 pixels
            pointerGestureMultitouchSettleInterval(100 * 1000000LL), // 100 ms
            pointerGestureMultitouchMinDistance(15), // 15 pixels
            pointerGestureSwipeTransitionAngleCosine(0.2588f), // cosine of 75 degrees
            pointerGestureSwipeMaxWidthRatio(0.25f),
            pointerGestureMovementSpeedRatio(0.8f),
            pointerGestureZoomSpeedRatio(0.3f) { }
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

    /* Gets information about the display with the specified id.
     * If external is true, returns the size of the external mirrored
     * counterpart of the specified display.
     * Returns true if the display info is available, false otherwise.
     */
    virtual bool getDisplayInfo(int32_t displayId, bool external,
            int32_t* width, int32_t* height, int32_t* orientation) = 0;

    /* Gets the input reader configuration. */
    virtual void getReaderConfiguration(InputReaderConfiguration* outConfig) = 0;

    /* Gets a pointer controller associated with the specified cursor device (ie. a mouse). */
    virtual sp<PointerControllerInterface> obtainPointerController(int32_t deviceId) = 0;
};


/* Processes raw input events and sends cooked event data to an input dispatcher. */
class InputReaderInterface : public virtual RefBase {
protected:
    InputReaderInterface() { }
    virtual ~InputReaderInterface() { }

public:
    /* Dumps the state of the input reader.
     *
     * This method may be called on any thread (usually by the input manager). */
    virtual void dump(String8& dump) = 0;

    /* Runs a single iteration of the processing loop.
     * Nominally reads and processes one incoming message from the EventHub.
     *
     * This method should be called on the input reader thread.
     */
    virtual void loopOnce() = 0;

    /* Gets the current input device configuration.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void getInputConfiguration(InputConfiguration* outConfiguration) = 0;

    /* Gets information about the specified input device.
     * Returns OK if the device information was obtained or NAME_NOT_FOUND if there
     * was no such device.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual status_t getInputDeviceInfo(int32_t deviceId, InputDeviceInfo* outDeviceInfo) = 0;

    /* Gets the list of all registered device ids. */
    virtual void getInputDeviceIds(Vector<int32_t>& outDeviceIds) = 0;

    /* Query current input state. */
    virtual int32_t getScanCodeState(int32_t deviceId, uint32_t sourceMask,
            int32_t scanCode) = 0;
    virtual int32_t getKeyCodeState(int32_t deviceId, uint32_t sourceMask,
            int32_t keyCode) = 0;
    virtual int32_t getSwitchState(int32_t deviceId, uint32_t sourceMask,
            int32_t sw) = 0;

    /* Determine whether physical keys exist for the given framework-domain key codes. */
    virtual bool hasKeys(int32_t deviceId, uint32_t sourceMask,
            size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) = 0;

    /* Requests that a reconfiguration of all input devices.
     * The changes flag is a bitfield that indicates what has changed and whether
     * the input devices must all be reopened. */
    virtual void requestRefreshConfiguration(uint32_t changes) = 0;
};


/* Internal interface used by individual input devices to access global input device state
 * and parameters maintained by the input reader.
 */
class InputReaderContext {
public:
    InputReaderContext() { }
    virtual ~InputReaderContext() { }

    virtual void updateGlobalMetaState() = 0;
    virtual int32_t getGlobalMetaState() = 0;

    virtual void disableVirtualKeysUntil(nsecs_t time) = 0;
    virtual bool shouldDropVirtualKey(nsecs_t now,
            InputDevice* device, int32_t keyCode, int32_t scanCode) = 0;

    virtual void fadePointer() = 0;

    virtual void requestTimeoutAtTime(nsecs_t when) = 0;

    virtual InputReaderPolicyInterface* getPolicy() = 0;
    virtual InputDispatcherInterface* getDispatcher() = 0;
    virtual EventHubInterface* getEventHub() = 0;
};


/* The input reader reads raw event data from the event hub and processes it into input events
 * that it sends to the input dispatcher.  Some functions of the input reader, such as early
 * event filtering in low power states, are controlled by a separate policy object.
 *
 * IMPORTANT INVARIANT:
 *     Because the policy and dispatcher can potentially block or cause re-entrance into
 *     the input reader, the input reader never calls into other components while holding
 *     an exclusive internal lock whenever re-entrance can happen.
 */
class InputReader : public InputReaderInterface, protected InputReaderContext {
public:
    InputReader(const sp<EventHubInterface>& eventHub,
            const sp<InputReaderPolicyInterface>& policy,
            const sp<InputDispatcherInterface>& dispatcher);
    virtual ~InputReader();

    virtual void dump(String8& dump);

    virtual void loopOnce();

    virtual void getInputConfiguration(InputConfiguration* outConfiguration);

    virtual status_t getInputDeviceInfo(int32_t deviceId, InputDeviceInfo* outDeviceInfo);
    virtual void getInputDeviceIds(Vector<int32_t>& outDeviceIds);

    virtual int32_t getScanCodeState(int32_t deviceId, uint32_t sourceMask,
            int32_t scanCode);
    virtual int32_t getKeyCodeState(int32_t deviceId, uint32_t sourceMask,
            int32_t keyCode);
    virtual int32_t getSwitchState(int32_t deviceId, uint32_t sourceMask,
            int32_t sw);

    virtual bool hasKeys(int32_t deviceId, uint32_t sourceMask,
            size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags);

    virtual void requestRefreshConfiguration(uint32_t changes);

protected:
    // These methods are protected virtual so they can be overridden and instrumented
    // by test cases.
    virtual InputDevice* createDevice(int32_t deviceId, const String8& name, uint32_t classes);

private:
    sp<EventHubInterface> mEventHub;
    sp<InputReaderPolicyInterface> mPolicy;
    sp<InputDispatcherInterface> mDispatcher;

    InputReaderConfiguration mConfig;

    virtual InputReaderPolicyInterface* getPolicy() { return mPolicy.get(); }
    virtual InputDispatcherInterface* getDispatcher() { return mDispatcher.get(); }
    virtual EventHubInterface* getEventHub() { return mEventHub.get(); }

    // The event queue.
    static const int EVENT_BUFFER_SIZE = 256;
    RawEvent mEventBuffer[EVENT_BUFFER_SIZE];

    // This reader/writer lock guards the list of input devices.
    // The writer lock must be held whenever the list of input devices is modified
    //   and then promptly released.
    // The reader lock must be held whenever the list of input devices is traversed or an
    //   input device in the list is accessed.
    // This lock only protects the registry and prevents inadvertent deletion of device objects
    // that are in use.  Individual devices are responsible for guarding their own internal state
    // as needed for concurrent operation.
    RWLock mDeviceRegistryLock;
    KeyedVector<int32_t, InputDevice*> mDevices;

    // low-level input event decoding and device management
    void processEvents(const RawEvent* rawEvents, size_t count);

    void addDevice(int32_t deviceId);
    void removeDevice(int32_t deviceId);
    void processEventsForDevice(int32_t deviceId, const RawEvent* rawEvents, size_t count);
    void timeoutExpired(nsecs_t when);

    void handleConfigurationChanged(nsecs_t when);

    // state management for all devices
    Mutex mStateLock;

    int32_t mGlobalMetaState; // guarded by mStateLock
    virtual void updateGlobalMetaState();
    virtual int32_t getGlobalMetaState();

    virtual void fadePointer();

    InputConfiguration mInputConfiguration; // guarded by mStateLock
    void updateInputConfiguration();

    nsecs_t mDisableVirtualKeysTimeout; // only accessed by reader thread
    virtual void disableVirtualKeysUntil(nsecs_t time);
    virtual bool shouldDropVirtualKey(nsecs_t now,
            InputDevice* device, int32_t keyCode, int32_t scanCode);

    nsecs_t mNextTimeout; // only accessed by reader thread, not guarded
    virtual void requestTimeoutAtTime(nsecs_t when);

    uint32_t mConfigurationChangesToRefresh; // guarded by mStateLock
    void refreshConfiguration(uint32_t changes);

    // state queries
    typedef int32_t (InputDevice::*GetStateFunc)(uint32_t sourceMask, int32_t code);
    int32_t getState(int32_t deviceId, uint32_t sourceMask, int32_t code,
            GetStateFunc getStateFunc);
    bool markSupportedKeyCodes(int32_t deviceId, uint32_t sourceMask, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags);
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


/* Represents the state of a single input device. */
class InputDevice {
public:
    InputDevice(InputReaderContext* context, int32_t id, const String8& name);
    ~InputDevice();

    inline InputReaderContext* getContext() { return mContext; }
    inline int32_t getId() { return mId; }
    inline const String8& getName() { return mName; }
    inline uint32_t getSources() { return mSources; }

    inline bool isExternal() { return mIsExternal; }
    inline void setExternal(bool external) { mIsExternal = external; }

    inline bool isIgnored() { return mMappers.isEmpty(); }

    void dump(String8& dump);
    void addMapper(InputMapper* mapper);
    void configure(const InputReaderConfiguration* config, uint32_t changes);
    void reset();
    void process(const RawEvent* rawEvents, size_t count);
    void timeoutExpired(nsecs_t when);

    void getDeviceInfo(InputDeviceInfo* outDeviceInfo);
    int32_t getKeyCodeState(uint32_t sourceMask, int32_t keyCode);
    int32_t getScanCodeState(uint32_t sourceMask, int32_t scanCode);
    int32_t getSwitchState(uint32_t sourceMask, int32_t switchCode);
    bool markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags);

    int32_t getMetaState();

    void fadePointer();

    inline const PropertyMap& getConfiguration() { return mConfiguration; }
    inline EventHubInterface* getEventHub() { return mContext->getEventHub(); }

private:
    InputReaderContext* mContext;
    int32_t mId;

    Vector<InputMapper*> mMappers;

    String8 mName;
    uint32_t mSources;
    bool mIsExternal;
    bool mDropUntilNextSync;

    typedef int32_t (InputMapper::*GetStateFunc)(uint32_t sourceMask, int32_t code);
    int32_t getState(uint32_t sourceMask, int32_t code, GetStateFunc getStateFunc);

    PropertyMap mConfiguration;
};


/* Keeps track of the state of mouse or touch pad buttons. */
class CursorButtonAccumulator {
public:
    CursorButtonAccumulator();

    void clearButtons();
    void process(const RawEvent* rawEvent);

    uint32_t getButtonState() const;

private:
    bool mBtnLeft;
    bool mBtnRight;
    bool mBtnMiddle;
    bool mBtnBack;
    bool mBtnSide;
    bool mBtnForward;
    bool mBtnExtra;
    bool mBtnTask;
};


/* Keeps track of cursor movements. */

class CursorMotionAccumulator {
public:
    CursorMotionAccumulator();
    void configure(InputDevice* device);

    void clearRelativeAxes();
    void process(const RawEvent* rawEvent);

    inline bool haveRelativeVWheel() const { return mHaveRelWheel; }
    inline bool haveRelativeHWheel() const { return mHaveRelHWheel; }

    inline int32_t getRelativeX() const { return mRelX; }
    inline int32_t getRelativeY() const { return mRelY; }
    inline int32_t getRelativeVWheel() const { return mRelWheel; }
    inline int32_t getRelativeHWheel() const { return mRelHWheel; }

private:
    bool mHaveRelWheel;
    bool mHaveRelHWheel;

    int32_t mRelX;
    int32_t mRelY;
    int32_t mRelWheel;
    int32_t mRelHWheel;
};


/* Keeps track of the state of touch, stylus and tool buttons. */
class TouchButtonAccumulator {
public:
    TouchButtonAccumulator();
    void configure(InputDevice* device);

    void clearButtons();
    void process(const RawEvent* rawEvent);

    uint32_t getButtonState() const;
    int32_t getToolType() const;
    bool isActive() const;
    bool isHovering() const;

private:
    bool mHaveBtnTouch;

    bool mBtnTouch;
    bool mBtnStylus;
    bool mBtnStylus2;
    bool mBtnToolFinger;
    bool mBtnToolPen;
    bool mBtnToolRubber;
};


/* Keeps track of the state of single-touch protocol. */
class SingleTouchMotionAccumulator {
public:
    SingleTouchMotionAccumulator();

    void clearAbsoluteAxes();
    void process(const RawEvent* rawEvent);

    inline int32_t getAbsoluteX() const { return mAbsX; }
    inline int32_t getAbsoluteY() const { return mAbsY; }
    inline int32_t getAbsolutePressure() const { return mAbsPressure; }
    inline int32_t getAbsoluteToolWidth() const { return mAbsToolWidth; }
    inline int32_t getAbsoluteDistance() const { return mAbsDistance; }

private:
    int32_t mAbsX;
    int32_t mAbsY;
    int32_t mAbsPressure;
    int32_t mAbsToolWidth;
    int32_t mAbsDistance;
};


/* Keeps track of the state of multi-touch protocol. */
class MultiTouchMotionAccumulator {
public:
    class Slot {
    public:
        inline bool isInUse() const { return mInUse; }
        inline int32_t getX() const { return mAbsMTPositionX; }
        inline int32_t getY() const { return mAbsMTPositionY; }
        inline int32_t getTouchMajor() const { return mAbsMTTouchMajor; }
        inline int32_t getTouchMinor() const {
            return mHaveAbsMTTouchMinor ? mAbsMTTouchMinor : mAbsMTTouchMajor; }
        inline int32_t getToolMajor() const { return mAbsMTWidthMajor; }
        inline int32_t getToolMinor() const {
            return mHaveAbsMTWidthMinor ? mAbsMTWidthMinor : mAbsMTWidthMajor; }
        inline int32_t getOrientation() const { return mAbsMTOrientation; }
        inline int32_t getTrackingId() const { return mAbsMTTrackingId; }
        inline int32_t getPressure() const { return mAbsMTPressure; }
        inline int32_t getDistance() const { return mAbsMTDistance; }
        inline int32_t getToolType() const;

    private:
        friend class MultiTouchMotionAccumulator;

        bool mInUse;
        bool mHaveAbsMTTouchMinor;
        bool mHaveAbsMTWidthMinor;
        bool mHaveAbsMTToolType;

        int32_t mAbsMTPositionX;
        int32_t mAbsMTPositionY;
        int32_t mAbsMTTouchMajor;
        int32_t mAbsMTTouchMinor;
        int32_t mAbsMTWidthMajor;
        int32_t mAbsMTWidthMinor;
        int32_t mAbsMTOrientation;
        int32_t mAbsMTTrackingId;
        int32_t mAbsMTPressure;
        int32_t mAbsMTToolType;
        int32_t mAbsMTDistance;

        Slot();
        void clearIfInUse();
        void clear();
    };

    MultiTouchMotionAccumulator();
    ~MultiTouchMotionAccumulator();

    void configure(size_t slotCount, bool usingSlotsProtocol);
    void process(const RawEvent* rawEvent);

    void clearSlots(int32_t initialSlot);

    inline bool isUsingSlotsProtocol() const { return mUsingSlotsProtocol; }
    inline size_t getSlotCount() const { return mSlotCount; }
    inline const Slot* getSlot(size_t index) const { return &mSlots[index]; }

private:
    int32_t mCurrentSlot;
    Slot* mSlots;
    size_t mSlotCount;
    bool mUsingSlotsProtocol;
};


/* An input mapper transforms raw input events into cooked event data.
 * A single input device can have multiple associated input mappers in order to interpret
 * different classes of events.
 */
class InputMapper {
public:
    InputMapper(InputDevice* device);
    virtual ~InputMapper();

    inline InputDevice* getDevice() { return mDevice; }
    inline int32_t getDeviceId() { return mDevice->getId(); }
    inline const String8 getDeviceName() { return mDevice->getName(); }
    inline InputReaderContext* getContext() { return mContext; }
    inline InputReaderPolicyInterface* getPolicy() { return mContext->getPolicy(); }
    inline InputDispatcherInterface* getDispatcher() { return mContext->getDispatcher(); }
    inline EventHubInterface* getEventHub() { return mContext->getEventHub(); }

    virtual uint32_t getSources() = 0;
    virtual void populateDeviceInfo(InputDeviceInfo* deviceInfo);
    virtual void dump(String8& dump);
    virtual void configure(const InputReaderConfiguration* config, uint32_t changes);
    virtual void reset();
    virtual void process(const RawEvent* rawEvent) = 0;
    virtual void timeoutExpired(nsecs_t when);

    virtual int32_t getKeyCodeState(uint32_t sourceMask, int32_t keyCode);
    virtual int32_t getScanCodeState(uint32_t sourceMask, int32_t scanCode);
    virtual int32_t getSwitchState(uint32_t sourceMask, int32_t switchCode);
    virtual bool markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags);

    virtual int32_t getMetaState();

    virtual void fadePointer();

protected:
    InputDevice* mDevice;
    InputReaderContext* mContext;

    static void dumpRawAbsoluteAxisInfo(String8& dump,
            const RawAbsoluteAxisInfo& axis, const char* name);
};


class SwitchInputMapper : public InputMapper {
public:
    SwitchInputMapper(InputDevice* device);
    virtual ~SwitchInputMapper();

    virtual uint32_t getSources();
    virtual void process(const RawEvent* rawEvent);

    virtual int32_t getSwitchState(uint32_t sourceMask, int32_t switchCode);

private:
    void processSwitch(nsecs_t when, int32_t switchCode, int32_t switchValue);
};


class KeyboardInputMapper : public InputMapper {
public:
    KeyboardInputMapper(InputDevice* device, uint32_t source, int32_t keyboardType);
    virtual ~KeyboardInputMapper();

    virtual uint32_t getSources();
    virtual void populateDeviceInfo(InputDeviceInfo* deviceInfo);
    virtual void dump(String8& dump);
    virtual void configure(const InputReaderConfiguration* config, uint32_t changes);
    virtual void reset();
    virtual void process(const RawEvent* rawEvent);

    virtual int32_t getKeyCodeState(uint32_t sourceMask, int32_t keyCode);
    virtual int32_t getScanCodeState(uint32_t sourceMask, int32_t scanCode);
    virtual bool markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags);

    virtual int32_t getMetaState();

private:
    Mutex mLock;

    struct KeyDown {
        int32_t keyCode;
        int32_t scanCode;
    };

    uint32_t mSource;
    int32_t mKeyboardType;

    // Immutable configuration parameters.
    struct Parameters {
        int32_t associatedDisplayId;
        bool orientationAware;
    } mParameters;

    struct LockedState {
        Vector<KeyDown> keyDowns; // keys that are down
        int32_t metaState;
        nsecs_t downTime; // time of most recent key down

        struct LedState {
            bool avail; // led is available
            bool on;    // we think the led is currently on
        };
        LedState capsLockLedState;
        LedState numLockLedState;
        LedState scrollLockLedState;
    } mLocked;

    void initializeLocked();

    void configureParameters();
    void dumpParameters(String8& dump);

    bool isKeyboardOrGamepadKey(int32_t scanCode);

    void processKey(nsecs_t when, bool down, int32_t keyCode, int32_t scanCode,
            uint32_t policyFlags);

    ssize_t findKeyDownLocked(int32_t scanCode);

    void resetLedStateLocked();
    void initializeLedStateLocked(LockedState::LedState& ledState, int32_t led);
    void updateLedStateLocked(bool reset);
    void updateLedStateForModifierLocked(LockedState::LedState& ledState, int32_t led,
            int32_t modifier, bool reset);
};


class CursorInputMapper : public InputMapper {
public:
    CursorInputMapper(InputDevice* device);
    virtual ~CursorInputMapper();

    virtual uint32_t getSources();
    virtual void populateDeviceInfo(InputDeviceInfo* deviceInfo);
    virtual void dump(String8& dump);
    virtual void configure(const InputReaderConfiguration* config, uint32_t changes);
    virtual void reset();
    virtual void process(const RawEvent* rawEvent);

    virtual int32_t getScanCodeState(uint32_t sourceMask, int32_t scanCode);

    virtual void fadePointer();

private:
    // Amount that trackball needs to move in order to generate a key event.
    static const int32_t TRACKBALL_MOVEMENT_THRESHOLD = 6;

    Mutex mLock;

    // Immutable configuration parameters.
    struct Parameters {
        enum Mode {
            MODE_POINTER,
            MODE_NAVIGATION,
        };

        Mode mode;
        int32_t associatedDisplayId;
        bool orientationAware;
    } mParameters;

    CursorButtonAccumulator mCursorButtonAccumulator;
    CursorMotionAccumulator mCursorMotionAccumulator;

    int32_t mSource;
    float mXScale;
    float mYScale;
    float mXPrecision;
    float mYPrecision;

    float mVWheelScale;
    float mHWheelScale;

    // Velocity controls for mouse pointer and wheel movements.
    // The controls for X and Y wheel movements are separate to keep them decoupled.
    VelocityControl mPointerVelocityControl;
    VelocityControl mWheelXVelocityControl;
    VelocityControl mWheelYVelocityControl;

    sp<PointerControllerInterface> mPointerController;

    struct LockedState {
        int32_t buttonState;
        nsecs_t downTime;
    } mLocked;

    void initializeLocked();

    void configureParameters();
    void dumpParameters(String8& dump);

    void sync(nsecs_t when);
};


class TouchInputMapper : public InputMapper {
public:
    TouchInputMapper(InputDevice* device);
    virtual ~TouchInputMapper();

    virtual uint32_t getSources();
    virtual void populateDeviceInfo(InputDeviceInfo* deviceInfo);
    virtual void dump(String8& dump);
    virtual void configure(const InputReaderConfiguration* config, uint32_t changes);
    virtual void reset();

    virtual int32_t getKeyCodeState(uint32_t sourceMask, int32_t keyCode);
    virtual int32_t getScanCodeState(uint32_t sourceMask, int32_t scanCode);
    virtual bool markSupportedKeyCodes(uint32_t sourceMask, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags);

    virtual void fadePointer();
    virtual void timeoutExpired(nsecs_t when);

protected:
    Mutex mLock;

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

    // Raw data for a single pointer.
    struct PointerData {
        uint32_t id;
        int32_t x;
        int32_t y;
        int32_t pressure;
        int32_t touchMajor;
        int32_t touchMinor;
        int32_t toolMajor;
        int32_t toolMinor;
        int32_t orientation;
        int32_t distance;
        int32_t toolType; // AMOTION_EVENT_TOOL_TYPE constant
        bool isHovering;

        inline bool operator== (const PointerData& other) const {
            return id == other.id
                    && x == other.x
                    && y == other.y
                    && pressure == other.pressure
                    && touchMajor == other.touchMajor
                    && touchMinor == other.touchMinor
                    && toolMajor == other.toolMajor
                    && toolMinor == other.toolMinor
                    && orientation == other.orientation
                    && distance == other.distance
                    && toolType == other.toolType
                    && isHovering == other.isHovering;
        }
        inline bool operator!= (const PointerData& other) const {
            return !(*this == other);
        }
    };

    // Raw data for a collection of pointers including a pointer id mapping table.
    struct TouchData {
        uint32_t pointerCount;
        PointerData pointers[MAX_POINTERS];
        BitSet32 idBits;
        uint32_t idToIndex[MAX_POINTER_ID + 1];
        int32_t buttonState;

        void copyFrom(const TouchData& other) {
            pointerCount = other.pointerCount;
            idBits = other.idBits;
            buttonState = other.buttonState;

            for (uint32_t i = 0; i < pointerCount; i++) {
                pointers[i] = other.pointers[i];

                int id = pointers[i].id;
                idToIndex[id] = other.idToIndex[id];
            }
        }

        inline void clear() {
            pointerCount = 0;
            idBits.clear();
            buttonState = 0;
        }

        void getCentroid(float* outX, float* outY) {
            float x = 0, y = 0;
            if (pointerCount != 0) {
                for (uint32_t i = 0; i < pointerCount; i++) {
                    x += pointers[i].x;
                    y += pointers[i].y;
                }
                x /= pointerCount;
                y /= pointerCount;
            }
            *outX = x;
            *outY = y;
        }
    };

    // Input sources supported by the device.
    uint32_t mTouchSource; // sources when reporting touch data
    uint32_t mPointerSource; // sources when reporting pointer gestures

    // The reader's configuration.
    InputReaderConfiguration mConfig;

    // Immutable configuration parameters.
    struct Parameters {
        enum DeviceType {
            DEVICE_TYPE_TOUCH_SCREEN,
            DEVICE_TYPE_TOUCH_PAD,
            DEVICE_TYPE_POINTER,
        };

        DeviceType deviceType;
        int32_t associatedDisplayId;
        bool associatedDisplayIsExternal;
        bool orientationAware;

        enum GestureMode {
            GESTURE_MODE_POINTER,
            GESTURE_MODE_SPOTS,
        };
        GestureMode gestureMode;
    } mParameters;

    // Immutable calibration parameters in parsed form.
    struct Calibration {
        // Touch Size
        enum TouchSizeCalibration {
            TOUCH_SIZE_CALIBRATION_DEFAULT,
            TOUCH_SIZE_CALIBRATION_NONE,
            TOUCH_SIZE_CALIBRATION_GEOMETRIC,
            TOUCH_SIZE_CALIBRATION_PRESSURE,
        };

        TouchSizeCalibration touchSizeCalibration;

        // Tool Size
        enum ToolSizeCalibration {
            TOOL_SIZE_CALIBRATION_DEFAULT,
            TOOL_SIZE_CALIBRATION_NONE,
            TOOL_SIZE_CALIBRATION_GEOMETRIC,
            TOOL_SIZE_CALIBRATION_LINEAR,
            TOOL_SIZE_CALIBRATION_AREA,
        };

        ToolSizeCalibration toolSizeCalibration;
        bool haveToolSizeLinearScale;
        float toolSizeLinearScale;
        bool haveToolSizeLinearBias;
        float toolSizeLinearBias;
        bool haveToolSizeAreaScale;
        float toolSizeAreaScale;
        bool haveToolSizeAreaBias;
        float toolSizeAreaBias;
        bool haveToolSizeIsSummed;
        bool toolSizeIsSummed;

        // Pressure
        enum PressureCalibration {
            PRESSURE_CALIBRATION_DEFAULT,
            PRESSURE_CALIBRATION_NONE,
            PRESSURE_CALIBRATION_PHYSICAL,
            PRESSURE_CALIBRATION_AMPLITUDE,
        };
        enum PressureSource {
            PRESSURE_SOURCE_DEFAULT,
            PRESSURE_SOURCE_PRESSURE,
            PRESSURE_SOURCE_TOUCH,
        };

        PressureCalibration pressureCalibration;
        PressureSource pressureSource;
        bool havePressureScale;
        float pressureScale;

        // Size
        enum SizeCalibration {
            SIZE_CALIBRATION_DEFAULT,
            SIZE_CALIBRATION_NONE,
            SIZE_CALIBRATION_NORMALIZED,
        };

        SizeCalibration sizeCalibration;

        // Orientation
        enum OrientationCalibration {
            ORIENTATION_CALIBRATION_DEFAULT,
            ORIENTATION_CALIBRATION_NONE,
            ORIENTATION_CALIBRATION_INTERPOLATED,
            ORIENTATION_CALIBRATION_VECTOR,
        };

        OrientationCalibration orientationCalibration;

        // Distance
        enum DistanceCalibration {
            DISTANCE_CALIBRATION_DEFAULT,
            DISTANCE_CALIBRATION_NONE,
            DISTANCE_CALIBRATION_SCALED,
        };

        DistanceCalibration distanceCalibration;
        bool haveDistanceScale;
        float distanceScale;
    } mCalibration;

    // Raw axis information from the driver.
    struct RawAxes {
        RawAbsoluteAxisInfo x;
        RawAbsoluteAxisInfo y;
        RawAbsoluteAxisInfo pressure;
        RawAbsoluteAxisInfo touchMajor;
        RawAbsoluteAxisInfo touchMinor;
        RawAbsoluteAxisInfo toolMajor;
        RawAbsoluteAxisInfo toolMinor;
        RawAbsoluteAxisInfo orientation;
        RawAbsoluteAxisInfo distance;
        RawAbsoluteAxisInfo trackingId;
        RawAbsoluteAxisInfo slot;
    } mRawAxes;

    // Current and previous touch sample data.
    TouchData mCurrentTouch;
    PointerProperties mCurrentTouchProperties[MAX_POINTERS];
    PointerCoords mCurrentTouchCoords[MAX_POINTERS];

    TouchData mLastTouch;
    PointerProperties mLastTouchProperties[MAX_POINTERS];
    PointerCoords mLastTouchCoords[MAX_POINTERS];

    // The time the primary pointer last went down.
    nsecs_t mDownTime;

    // The pointer controller, or null if the device is not a pointer.
    sp<PointerControllerInterface> mPointerController;

    struct LockedState {
        Vector<VirtualKey> virtualKeys;

        // The surface orientation and width and height set by configureSurfaceLocked().
        int32_t surfaceOrientation;
        int32_t surfaceWidth, surfaceHeight;

        // The associated display orientation and width and height set by configureSurfaceLocked().
        int32_t associatedDisplayOrientation;
        int32_t associatedDisplayWidth, associatedDisplayHeight;

        // Translation and scaling factors, orientation-independent.
        float xScale;
        float xPrecision;

        float yScale;
        float yPrecision;

        float geometricScale;

        float toolSizeLinearScale;
        float toolSizeLinearBias;
        float toolSizeAreaScale;
        float toolSizeAreaBias;

        float pressureScale;

        float sizeScale;

        float orientationScale;

        float distanceScale;

        // Oriented motion ranges for input device info.
        struct OrientedRanges {
            InputDeviceInfo::MotionRange x;
            InputDeviceInfo::MotionRange y;

            bool havePressure;
            InputDeviceInfo::MotionRange pressure;

            bool haveSize;
            InputDeviceInfo::MotionRange size;

            bool haveTouchSize;
            InputDeviceInfo::MotionRange touchMajor;
            InputDeviceInfo::MotionRange touchMinor;

            bool haveToolSize;
            InputDeviceInfo::MotionRange toolMajor;
            InputDeviceInfo::MotionRange toolMinor;

            bool haveOrientation;
            InputDeviceInfo::MotionRange orientation;

            bool haveDistance;
            InputDeviceInfo::MotionRange distance;
        } orientedRanges;

        // Oriented dimensions and precision.
        float orientedSurfaceWidth, orientedSurfaceHeight;
        float orientedXPrecision, orientedYPrecision;

        struct CurrentVirtualKeyState {
            bool down;
            nsecs_t downTime;
            int32_t keyCode;
            int32_t scanCode;
        } currentVirtualKey;

        // Scale factor for gesture based pointer movements.
        float pointerGestureXMovementScale;
        float pointerGestureYMovementScale;

        // Scale factor for gesture based zooming and other freeform motions.
        float pointerGestureXZoomScale;
        float pointerGestureYZoomScale;

        // The maximum swipe width.
        float pointerGestureMaxSwipeWidth;
    } mLocked;

    virtual void configureParameters();
    virtual void dumpParameters(String8& dump);
    virtual void configureRawAxes();
    virtual void dumpRawAxes(String8& dump);
    virtual bool configureSurfaceLocked();
    virtual void dumpSurfaceLocked(String8& dump);
    virtual void configureVirtualKeysLocked();
    virtual void dumpVirtualKeysLocked(String8& dump);
    virtual void parseCalibration();
    virtual void resolveCalibration();
    virtual void dumpCalibration(String8& dump);

    enum TouchResult {
        // Dispatch the touch normally.
        DISPATCH_TOUCH,
        // Do not dispatch the touch, but keep tracking the current stroke.
        SKIP_TOUCH,
        // Do not dispatch the touch, and drop all information associated with the current stoke
        // so the next movement will appear as a new down.
        DROP_STROKE
    };

    void syncTouch(nsecs_t when, bool havePointerIds);

private:
    struct PointerDistanceHeapElement {
        uint32_t currentPointerIndex : 8;
        uint32_t lastPointerIndex : 8;
        uint64_t distance : 48; // squared distance
    };

    struct PointerGesture {
        enum Mode {
            // No fingers, button is not pressed.
            // Nothing happening.
            NEUTRAL,

            // No fingers, button is not pressed.
            // Tap detected.
            // Emits DOWN and UP events at the pointer location.
            TAP,

            // Exactly one finger dragging following a tap.
            // Pointer follows the active finger.
            // Emits DOWN, MOVE and UP events at the pointer location.
            //
            // Detect double-taps when the finger goes up while in TAP_DRAG mode.
            TAP_DRAG,

            // Button is pressed.
            // Pointer follows the active finger if there is one.  Other fingers are ignored.
            // Emits DOWN, MOVE and UP events at the pointer location.
            BUTTON_CLICK_OR_DRAG,

            // Exactly one finger, button is not pressed.
            // Pointer follows the active finger.
            // Emits HOVER_MOVE events at the pointer location.
            //
            // Detect taps when the finger goes up while in HOVER mode.
            HOVER,

            // Exactly two fingers but neither have moved enough to clearly indicate
            // whether a swipe or freeform gesture was intended.  We consider the
            // pointer to be pressed so this enables clicking or long-pressing on buttons.
            // Pointer does not move.
            // Emits DOWN, MOVE and UP events with a single stationary pointer coordinate.
            PRESS,

            // Exactly two fingers moving in the same direction, button is not pressed.
            // Pointer does not move.
            // Emits DOWN, MOVE and UP events with a single pointer coordinate that
            // follows the midpoint between both fingers.
            SWIPE,

            // Two or more fingers moving in arbitrary directions, button is not pressed.
            // Pointer does not move.
            // Emits DOWN, POINTER_DOWN, MOVE, POINTER_UP and UP events that follow
            // each finger individually relative to the initial centroid of the finger.
            FREEFORM,

            // Waiting for quiet time to end before starting the next gesture.
            QUIET,
        };

        // Time the first finger went down.
        nsecs_t firstTouchTime;

        // The active pointer id from the raw touch data.
        int32_t activeTouchId; // -1 if none

        // The active pointer id from the gesture last delivered to the application.
        int32_t activeGestureId; // -1 if none

        // Pointer coords and ids for the current and previous pointer gesture.
        Mode currentGestureMode;
        BitSet32 currentGestureIdBits;
        uint32_t currentGestureIdToIndex[MAX_POINTER_ID + 1];
        PointerProperties currentGestureProperties[MAX_POINTERS];
        PointerCoords currentGestureCoords[MAX_POINTERS];

        Mode lastGestureMode;
        BitSet32 lastGestureIdBits;
        uint32_t lastGestureIdToIndex[MAX_POINTER_ID + 1];
        PointerProperties lastGestureProperties[MAX_POINTERS];
        PointerCoords lastGestureCoords[MAX_POINTERS];

        // Time the pointer gesture last went down.
        nsecs_t downTime;

        // Time when the pointer went down for a TAP.
        nsecs_t tapDownTime;

        // Time when the pointer went up for a TAP.
        nsecs_t tapUpTime;

        // Location of initial tap.
        float tapX, tapY;

        // Time we started waiting for quiescence.
        nsecs_t quietTime;

        // Reference points for multitouch gestures.
        float referenceTouchX;    // reference touch X/Y coordinates in surface units
        float referenceTouchY;
        float referenceGestureX;  // reference gesture X/Y coordinates in pixels
        float referenceGestureY;

        // Distance that each pointer has traveled which has not yet been
        // subsumed into the reference gesture position.
        BitSet32 referenceIdBits;
        struct Delta {
            float dx, dy;
        };
        Delta referenceDeltas[MAX_POINTER_ID + 1];

        // Describes how touch ids are mapped to gesture ids for freeform gestures.
        uint32_t freeformTouchToGestureIdMap[MAX_POINTER_ID + 1];

        // A velocity tracker for determining whether to switch active pointers during drags.
        VelocityTracker velocityTracker;

        // Velocity control for pointer movements.
        VelocityControl pointerVelocityControl;

        void reset() {
            firstTouchTime = LLONG_MIN;
            activeTouchId = -1;
            activeGestureId = -1;
            currentGestureMode = NEUTRAL;
            currentGestureIdBits.clear();
            lastGestureMode = NEUTRAL;
            lastGestureIdBits.clear();
            downTime = 0;
            velocityTracker.clear();
            resetTap();
            resetQuietTime();
            pointerVelocityControl.reset();
        }

        void resetTap() {
            tapDownTime = LLONG_MIN;
            tapUpTime = LLONG_MIN;
        }

        void resetQuietTime() {
            quietTime = LLONG_MIN;
        }
    } mPointerGesture;

    void initializeLocked();

    TouchResult consumeOffScreenTouches(nsecs_t when, uint32_t policyFlags);
    void dispatchTouches(nsecs_t when, uint32_t policyFlags);
    void prepareTouches(float* outXPrecision, float* outYPrecision);
    void dispatchPointerGestures(nsecs_t when, uint32_t policyFlags, bool isTimeout);
    bool preparePointerGestures(nsecs_t when,
            bool* outCancelPreviousGesture, bool* outFinishPreviousGesture, bool isTimeout);

    // Dispatches a motion event.
    // If the changedId is >= 0 and the action is POINTER_DOWN or POINTER_UP, the
    // method will take care of setting the index and transmuting the action to DOWN or UP
    // it is the first / last pointer to go down / up.
    void dispatchMotion(nsecs_t when, uint32_t policyFlags, uint32_t source,
            int32_t action, int32_t flags, int32_t metaState, int32_t buttonState,
            int32_t edgeFlags,
            const PointerProperties* properties, const PointerCoords* coords,
            const uint32_t* idToIndex, BitSet32 idBits,
            int32_t changedId, float xPrecision, float yPrecision, nsecs_t downTime);

    // Updates pointer coords and properties for pointers with specified ids that have moved.
    // Returns true if any of them changed.
    bool updateMovedPointers(const PointerProperties* inProperties,
            const PointerCoords* inCoords, const uint32_t* inIdToIndex,
            PointerProperties* outProperties, PointerCoords* outCoords,
            const uint32_t* outIdToIndex, BitSet32 idBits) const;

    void suppressSwipeOntoVirtualKeys(nsecs_t when);

    bool isPointInsideSurfaceLocked(int32_t x, int32_t y);
    const VirtualKey* findVirtualKeyHitLocked(int32_t x, int32_t y);

    void calculatePointerIds();
};


class SingleTouchInputMapper : public TouchInputMapper {
public:
    SingleTouchInputMapper(InputDevice* device);
    virtual ~SingleTouchInputMapper();

    virtual void reset();
    virtual void process(const RawEvent* rawEvent);

protected:
    virtual void configureRawAxes();

private:
    CursorButtonAccumulator mCursorButtonAccumulator;
    TouchButtonAccumulator mTouchButtonAccumulator;
    SingleTouchMotionAccumulator mSingleTouchMotionAccumulator;

    void clearState();

    void sync(nsecs_t when);
};


class MultiTouchInputMapper : public TouchInputMapper {
public:
    MultiTouchInputMapper(InputDevice* device);
    virtual ~MultiTouchInputMapper();

    virtual void reset();
    virtual void process(const RawEvent* rawEvent);

protected:
    virtual void configureRawAxes();

private:
    CursorButtonAccumulator mCursorButtonAccumulator;
    TouchButtonAccumulator mTouchButtonAccumulator;
    MultiTouchMotionAccumulator mMultiTouchMotionAccumulator;

    // Specifies the pointer id bits that are in use, and their associated tracking id.
    BitSet32 mPointerIdBits;
    int32_t mPointerTrackingIdMap[MAX_POINTER_ID + 1];

    void clearState();

    void sync(nsecs_t when);
};


class JoystickInputMapper : public InputMapper {
public:
    JoystickInputMapper(InputDevice* device);
    virtual ~JoystickInputMapper();

    virtual uint32_t getSources();
    virtual void populateDeviceInfo(InputDeviceInfo* deviceInfo);
    virtual void dump(String8& dump);
    virtual void configure(const InputReaderConfiguration* config, uint32_t changes);
    virtual void reset();
    virtual void process(const RawEvent* rawEvent);

private:
    struct Axis {
        RawAbsoluteAxisInfo rawAxisInfo;
        AxisInfo axisInfo;

        bool explicitlyMapped; // true if the axis was explicitly assigned an axis id

        float scale;   // scale factor from raw to normalized values
        float offset;  // offset to add after scaling for normalization
        float highScale;  // scale factor from raw to normalized values of high split
        float highOffset; // offset to add after scaling for normalization of high split

        float min;     // normalized inclusive minimum
        float max;     // normalized inclusive maximum
        float flat;    // normalized flat region size
        float fuzz;    // normalized error tolerance

        float filter;  // filter out small variations of this size
        float currentValue; // current value
        float newValue; // most recent value
        float highCurrentValue; // current value of high split
        float highNewValue; // most recent value of high split

        void initialize(const RawAbsoluteAxisInfo& rawAxisInfo, const AxisInfo& axisInfo,
                bool explicitlyMapped, float scale, float offset,
                float highScale, float highOffset,
                float min, float max, float flat, float fuzz) {
            this->rawAxisInfo = rawAxisInfo;
            this->axisInfo = axisInfo;
            this->explicitlyMapped = explicitlyMapped;
            this->scale = scale;
            this->offset = offset;
            this->highScale = highScale;
            this->highOffset = highOffset;
            this->min = min;
            this->max = max;
            this->flat = flat;
            this->fuzz = fuzz;
            this->filter = 0;
            resetValue();
        }

        void resetValue() {
            this->currentValue = 0;
            this->newValue = 0;
            this->highCurrentValue = 0;
            this->highNewValue = 0;
        }
    };

    // Axes indexed by raw ABS_* axis index.
    KeyedVector<int32_t, Axis> mAxes;

    void sync(nsecs_t when, bool force);

    bool haveAxis(int32_t axisId);
    void pruneAxes(bool ignoreExplicitlyMappedAxes);
    bool filterAxes(bool force);

    static bool hasValueChangedSignificantly(float filter,
            float newValue, float currentValue, float min, float max);
    static bool hasMovedNearerToValueWithinFilteredRange(float filter,
            float newValue, float currentValue, float thresholdValue);

    static bool isCenteredAxis(int32_t axis);
};

} // namespace android

#endif // _UI_INPUT_READER_H
