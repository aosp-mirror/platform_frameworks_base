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

#ifndef _UI_INPUT_DISPATCHER_H
#define _UI_INPUT_DISPATCHER_H

#include <ui/Input.h>
#include <ui/InputTransport.h>
#include <utils/KeyedVector.h>
#include <utils/Vector.h>
#include <utils/threads.h>
#include <utils/Timers.h>
#include <utils/RefBase.h>
#include <utils/String8.h>
#include <utils/Looper.h>
#include <utils/BitSet.h>

#include <stddef.h>
#include <unistd.h>
#include <limits.h>

#include "InputWindow.h"
#include "InputApplication.h"
#include "InputListener.h"


namespace android {

/*
 * Constants used to report the outcome of input event injection.
 */
enum {
    /* (INTERNAL USE ONLY) Specifies that injection is pending and its outcome is unknown. */
    INPUT_EVENT_INJECTION_PENDING = -1,

    /* Injection succeeded. */
    INPUT_EVENT_INJECTION_SUCCEEDED = 0,

    /* Injection failed because the injector did not have permission to inject
     * into the application with input focus. */
    INPUT_EVENT_INJECTION_PERMISSION_DENIED = 1,

    /* Injection failed because there were no available input targets. */
    INPUT_EVENT_INJECTION_FAILED = 2,

    /* Injection failed due to a timeout. */
    INPUT_EVENT_INJECTION_TIMED_OUT = 3
};

/*
 * Constants used to determine the input event injection synchronization mode.
 */
enum {
    /* Injection is asynchronous and is assumed always to be successful. */
    INPUT_EVENT_INJECTION_SYNC_NONE = 0,

    /* Waits for previous events to be dispatched so that the input dispatcher can determine
     * whether input event injection willbe permitted based on the current input focus.
     * Does not wait for the input event to finish processing. */
    INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT = 1,

    /* Waits for the input event to be completely processed. */
    INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISHED = 2,
};


/*
 * An input target specifies how an input event is to be dispatched to a particular window
 * including the window's input channel, control flags, a timeout, and an X / Y offset to
 * be added to input event coordinates to compensate for the absolute position of the
 * window area.
 */
struct InputTarget {
    enum {
        /* This flag indicates that the event is being delivered to a foreground application. */
        FLAG_FOREGROUND = 1 << 0,

        /* This flag indicates that the target of a MotionEvent is partly or wholly
         * obscured by another visible window above it.  The motion event should be
         * delivered with flag AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED. */
        FLAG_WINDOW_IS_OBSCURED = 1 << 1,

        /* This flag indicates that a motion event is being split across multiple windows. */
        FLAG_SPLIT = 1 << 2,

        /* This flag indicates that the pointer coordinates dispatched to the application
         * will be zeroed out to avoid revealing information to an application. This is
         * used in conjunction with FLAG_DISPATCH_AS_OUTSIDE to prevent apps not sharing
         * the same UID from watching all touches. */
        FLAG_ZERO_COORDS = 1 << 3,

        /* This flag indicates that the event should be sent as is.
         * Should always be set unless the event is to be transmuted. */
        FLAG_DISPATCH_AS_IS = 1 << 8,

        /* This flag indicates that a MotionEvent with AMOTION_EVENT_ACTION_DOWN falls outside
         * of the area of this target and so should instead be delivered as an
         * AMOTION_EVENT_ACTION_OUTSIDE to this target. */
        FLAG_DISPATCH_AS_OUTSIDE = 1 << 9,

        /* This flag indicates that a hover sequence is starting in the given window.
         * The event is transmuted into ACTION_HOVER_ENTER. */
        FLAG_DISPATCH_AS_HOVER_ENTER = 1 << 10,

        /* This flag indicates that a hover event happened outside of a window which handled
         * previous hover events, signifying the end of the current hover sequence for that
         * window.
         * The event is transmuted into ACTION_HOVER_ENTER. */
        FLAG_DISPATCH_AS_HOVER_EXIT = 1 << 11,

        /* This flag indicates that the event should be canceled.
         * It is used to transmute ACTION_MOVE into ACTION_CANCEL when a touch slips
         * outside of a window. */
        FLAG_DISPATCH_AS_SLIPPERY_EXIT = 1 << 12,

        /* This flag indicates that the event should be dispatched as an initial down.
         * It is used to transmute ACTION_MOVE into ACTION_DOWN when a touch slips
         * into a new window. */
        FLAG_DISPATCH_AS_SLIPPERY_ENTER = 1 << 13,

        /* Mask for all dispatch modes. */
        FLAG_DISPATCH_MASK = FLAG_DISPATCH_AS_IS
                | FLAG_DISPATCH_AS_OUTSIDE
                | FLAG_DISPATCH_AS_HOVER_ENTER
                | FLAG_DISPATCH_AS_HOVER_EXIT
                | FLAG_DISPATCH_AS_SLIPPERY_EXIT
                | FLAG_DISPATCH_AS_SLIPPERY_ENTER,
    };

    // The input channel to be targeted.
    sp<InputChannel> inputChannel;

    // Flags for the input target.
    int32_t flags;

    // The x and y offset to add to a MotionEvent as it is delivered.
    // (ignored for KeyEvents)
    float xOffset, yOffset;

    // Scaling factor to apply to MotionEvent as it is delivered.
    // (ignored for KeyEvents)
    float scaleFactor;

    // The subset of pointer ids to include in motion events dispatched to this input target
    // if FLAG_SPLIT is set.
    BitSet32 pointerIds;
};


/*
 * Input dispatcher configuration.
 *
 * Specifies various options that modify the behavior of the input dispatcher.
 */
struct InputDispatcherConfiguration {
    // The key repeat initial timeout.
    nsecs_t keyRepeatTimeout;

    // The key repeat inter-key delay.
    nsecs_t keyRepeatDelay;

    // The maximum suggested event delivery rate per second.
    // This value is used to throttle motion event movement actions on a per-device
    // basis.  It is not intended to be a hard limit.
    int32_t maxEventsPerSecond;

    InputDispatcherConfiguration() :
            keyRepeatTimeout(500 * 1000000LL),
            keyRepeatDelay(50 * 1000000LL),
            maxEventsPerSecond(60) { }
};


/*
 * Input dispatcher policy interface.
 *
 * The input reader policy is used by the input reader to interact with the Window Manager
 * and other system components.
 *
 * The actual implementation is partially supported by callbacks into the DVM
 * via JNI.  This interface is also mocked in the unit tests.
 */
class InputDispatcherPolicyInterface : public virtual RefBase {
protected:
    InputDispatcherPolicyInterface() { }
    virtual ~InputDispatcherPolicyInterface() { }

public:
    /* Notifies the system that a configuration change has occurred. */
    virtual void notifyConfigurationChanged(nsecs_t when) = 0;

    /* Notifies the system that an application is not responding.
     * Returns a new timeout to continue waiting, or 0 to abort dispatch. */
    virtual nsecs_t notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
            const sp<InputWindowHandle>& inputWindowHandle) = 0;

    /* Notifies the system that an input channel is unrecoverably broken. */
    virtual void notifyInputChannelBroken(const sp<InputWindowHandle>& inputWindowHandle) = 0;

    /* Gets the input dispatcher configuration. */
    virtual void getDispatcherConfiguration(InputDispatcherConfiguration* outConfig) = 0;

    /* Returns true if automatic key repeating is enabled. */
    virtual bool isKeyRepeatEnabled() = 0;

    /* Filters an input event.
     * Return true to dispatch the event unmodified, false to consume the event.
     * A filter can also transform and inject events later by passing POLICY_FLAG_FILTERED
     * to injectInputEvent.
     */
    virtual bool filterInputEvent(const InputEvent* inputEvent, uint32_t policyFlags) = 0;

    /* Intercepts a key event immediately before queueing it.
     * The policy can use this method as an opportunity to perform power management functions
     * and early event preprocessing such as updating policy flags.
     *
     * This method is expected to set the POLICY_FLAG_PASS_TO_USER policy flag if the event
     * should be dispatched to applications.
     */
    virtual void interceptKeyBeforeQueueing(const KeyEvent* keyEvent, uint32_t& policyFlags) = 0;

    /* Intercepts a touch, trackball or other motion event before queueing it.
     * The policy can use this method as an opportunity to perform power management functions
     * and early event preprocessing such as updating policy flags.
     *
     * This method is expected to set the POLICY_FLAG_PASS_TO_USER policy flag if the event
     * should be dispatched to applications.
     */
    virtual void interceptMotionBeforeQueueing(nsecs_t when, uint32_t& policyFlags) = 0;

    /* Allows the policy a chance to intercept a key before dispatching. */
    virtual nsecs_t interceptKeyBeforeDispatching(const sp<InputWindowHandle>& inputWindowHandle,
            const KeyEvent* keyEvent, uint32_t policyFlags) = 0;

    /* Allows the policy a chance to perform default processing for an unhandled key.
     * Returns an alternate keycode to redispatch as a fallback, or 0 to give up. */
    virtual bool dispatchUnhandledKey(const sp<InputWindowHandle>& inputWindowHandle,
            const KeyEvent* keyEvent, uint32_t policyFlags, KeyEvent* outFallbackKeyEvent) = 0;

    /* Notifies the policy about switch events.
     */
    virtual void notifySwitch(nsecs_t when,
            int32_t switchCode, int32_t switchValue, uint32_t policyFlags) = 0;

    /* Poke user activity for an event dispatched to a window. */
    virtual void pokeUserActivity(nsecs_t eventTime, int32_t eventType) = 0;

    /* Checks whether a given application pid/uid has permission to inject input events
     * into other applications.
     *
     * This method is special in that its implementation promises to be non-reentrant and
     * is safe to call while holding other locks.  (Most other methods make no such guarantees!)
     */
    virtual bool checkInjectEventsPermissionNonReentrant(
            int32_t injectorPid, int32_t injectorUid) = 0;
};


/* Notifies the system about input events generated by the input reader.
 * The dispatcher is expected to be mostly asynchronous. */
class InputDispatcherInterface : public virtual RefBase, public InputListenerInterface {
protected:
    InputDispatcherInterface() { }
    virtual ~InputDispatcherInterface() { }

public:
    /* Dumps the state of the input dispatcher.
     *
     * This method may be called on any thread (usually by the input manager). */
    virtual void dump(String8& dump) = 0;

    /* Called by the heatbeat to ensures that the dispatcher has not deadlocked. */
    virtual void monitor() = 0;

    /* Runs a single iteration of the dispatch loop.
     * Nominally processes one queued event, a timeout, or a response from an input consumer.
     *
     * This method should only be called on the input dispatcher thread.
     */
    virtual void dispatchOnce() = 0;

    /* Injects an input event and optionally waits for sync.
     * The synchronization mode determines whether the method blocks while waiting for
     * input injection to proceed.
     * Returns one of the INPUT_EVENT_INJECTION_XXX constants.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, int32_t syncMode, int32_t timeoutMillis,
            uint32_t policyFlags) = 0;

    /* Sets the list of input windows.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void setInputWindows(const Vector<sp<InputWindowHandle> >& inputWindowHandles) = 0;

    /* Sets the focused application.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void setFocusedApplication(
            const sp<InputApplicationHandle>& inputApplicationHandle) = 0;

    /* Sets the input dispatching mode.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void setInputDispatchMode(bool enabled, bool frozen) = 0;

    /* Sets whether input event filtering is enabled.
     * When enabled, incoming input events are sent to the policy's filterInputEvent
     * method instead of being dispatched.  The filter is expected to use
     * injectInputEvent to inject the events it would like to have dispatched.
     * It should include POLICY_FLAG_FILTERED in the policy flags during injection.
     */
    virtual void setInputFilterEnabled(bool enabled) = 0;

    /* Transfers touch focus from the window associated with one channel to the
     * window associated with the other channel.
     *
     * Returns true on success.  False if the window did not actually have touch focus.
     */
    virtual bool transferTouchFocus(const sp<InputChannel>& fromChannel,
            const sp<InputChannel>& toChannel) = 0;

    /* Registers or unregister input channels that may be used as targets for input events.
     * If monitor is true, the channel will receive a copy of all input events.
     *
     * These methods may be called on any thread (usually by the input manager).
     */
    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel,
            const sp<InputWindowHandle>& inputWindowHandle, bool monitor) = 0;
    virtual status_t unregisterInputChannel(const sp<InputChannel>& inputChannel) = 0;
};

/* Dispatches events to input targets.  Some functions of the input dispatcher, such as
 * identifying input targets, are controlled by a separate policy object.
 *
 * IMPORTANT INVARIANT:
 *     Because the policy can potentially block or cause re-entrance into the input dispatcher,
 *     the input dispatcher never calls into the policy while holding its internal locks.
 *     The implementation is also carefully designed to recover from scenarios such as an
 *     input channel becoming unregistered while identifying input targets or processing timeouts.
 *
 *     Methods marked 'Locked' must be called with the lock acquired.
 *
 *     Methods marked 'LockedInterruptible' must be called with the lock acquired but
 *     may during the course of their execution release the lock, call into the policy, and
 *     then reacquire the lock.  The caller is responsible for recovering gracefully.
 *
 *     A 'LockedInterruptible' method may called a 'Locked' method, but NOT vice-versa.
 */
class InputDispatcher : public InputDispatcherInterface {
protected:
    virtual ~InputDispatcher();

public:
    explicit InputDispatcher(const sp<InputDispatcherPolicyInterface>& policy);

    virtual void dump(String8& dump);
    virtual void monitor();

    virtual void dispatchOnce();

    virtual void notifyConfigurationChanged(const NotifyConfigurationChangedArgs* args);
    virtual void notifyKey(const NotifyKeyArgs* args);
    virtual void notifyMotion(const NotifyMotionArgs* args);
    virtual void notifySwitch(const NotifySwitchArgs* args);
    virtual void notifyDeviceReset(const NotifyDeviceResetArgs* args);

    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, int32_t syncMode, int32_t timeoutMillis,
            uint32_t policyFlags);

    virtual void setInputWindows(const Vector<sp<InputWindowHandle> >& inputWindowHandles);
    virtual void setFocusedApplication(const sp<InputApplicationHandle>& inputApplicationHandle);
    virtual void setInputDispatchMode(bool enabled, bool frozen);
    virtual void setInputFilterEnabled(bool enabled);

    virtual bool transferTouchFocus(const sp<InputChannel>& fromChannel,
            const sp<InputChannel>& toChannel);

    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel,
            const sp<InputWindowHandle>& inputWindowHandle, bool monitor);
    virtual status_t unregisterInputChannel(const sp<InputChannel>& inputChannel);

private:
    template <typename T>
    struct Link {
        T* next;
        T* prev;
    };

    struct InjectionState {
        mutable int32_t refCount;

        int32_t injectorPid;
        int32_t injectorUid;
        int32_t injectionResult;  // initially INPUT_EVENT_INJECTION_PENDING
        bool injectionIsAsync; // set to true if injection is not waiting for the result
        int32_t pendingForegroundDispatches; // the number of foreground dispatches in progress

        InjectionState(int32_t injectorPid, int32_t injectorUid);
        void release();

    private:
        ~InjectionState();
    };

    struct EventEntry : Link<EventEntry> {
        enum {
            TYPE_CONFIGURATION_CHANGED,
            TYPE_DEVICE_RESET,
            TYPE_KEY,
            TYPE_MOTION
        };

        mutable int32_t refCount;
        int32_t type;
        nsecs_t eventTime;
        uint32_t policyFlags;
        InjectionState* injectionState;

        bool dispatchInProgress; // initially false, set to true while dispatching

        inline bool isInjected() const { return injectionState != NULL; }

        void release();

    protected:
        EventEntry(int32_t type, nsecs_t eventTime, uint32_t policyFlags);
        virtual ~EventEntry();
        void releaseInjectionState();
    };

    struct ConfigurationChangedEntry : EventEntry {
        ConfigurationChangedEntry(nsecs_t eventTime);

    protected:
        virtual ~ConfigurationChangedEntry();
    };

    struct DeviceResetEntry : EventEntry {
        int32_t deviceId;

        DeviceResetEntry(nsecs_t eventTime, int32_t deviceId);

    protected:
        virtual ~DeviceResetEntry();
    };

    struct KeyEntry : EventEntry {
        int32_t deviceId;
        uint32_t source;
        int32_t action;
        int32_t flags;
        int32_t keyCode;
        int32_t scanCode;
        int32_t metaState;
        int32_t repeatCount;
        nsecs_t downTime;

        bool syntheticRepeat; // set to true for synthetic key repeats

        enum InterceptKeyResult {
            INTERCEPT_KEY_RESULT_UNKNOWN,
            INTERCEPT_KEY_RESULT_SKIP,
            INTERCEPT_KEY_RESULT_CONTINUE,
            INTERCEPT_KEY_RESULT_TRY_AGAIN_LATER,
        };
        InterceptKeyResult interceptKeyResult; // set based on the interception result
        nsecs_t interceptKeyWakeupTime; // used with INTERCEPT_KEY_RESULT_TRY_AGAIN_LATER

        KeyEntry(nsecs_t eventTime,
                int32_t deviceId, uint32_t source, uint32_t policyFlags, int32_t action,
                int32_t flags, int32_t keyCode, int32_t scanCode, int32_t metaState,
                int32_t repeatCount, nsecs_t downTime);
        void recycle();

    protected:
        virtual ~KeyEntry();
    };

    struct MotionSample {
        MotionSample* next;

        nsecs_t eventTime; // may be updated during coalescing
        nsecs_t eventTimeBeforeCoalescing; // not updated during coalescing
        PointerCoords pointerCoords[MAX_POINTERS];

        MotionSample(nsecs_t eventTime, const PointerCoords* pointerCoords,
                uint32_t pointerCount);
    };

    struct MotionEntry : EventEntry {
        int32_t deviceId;
        uint32_t source;
        int32_t action;
        int32_t flags;
        int32_t metaState;
        int32_t buttonState;
        int32_t edgeFlags;
        float xPrecision;
        float yPrecision;
        nsecs_t downTime;
        uint32_t pointerCount;
        PointerProperties pointerProperties[MAX_POINTERS];

        // Linked list of motion samples associated with this motion event.
        MotionSample firstSample;
        MotionSample* lastSample;

        MotionEntry(nsecs_t eventTime,
                int32_t deviceId, uint32_t source, uint32_t policyFlags, int32_t action,
                int32_t flags, int32_t metaState, int32_t buttonState, int32_t edgeFlags,
                float xPrecision, float yPrecision,
                nsecs_t downTime, uint32_t pointerCount,
                const PointerProperties* pointerProperties, const PointerCoords* pointerCoords);

        uint32_t countSamples() const;

        // Checks whether we can append samples, assuming the device id and source are the same.
        bool canAppendSamples(int32_t action, uint32_t pointerCount,
                const PointerProperties* pointerProperties) const;

        void appendSample(nsecs_t eventTime, const PointerCoords* pointerCoords);

    protected:
        virtual ~MotionEntry();
    };

    // Tracks the progress of dispatching a particular event to a particular connection.
    struct DispatchEntry : Link<DispatchEntry> {
        EventEntry* eventEntry; // the event to dispatch
        int32_t targetFlags;
        float xOffset;
        float yOffset;
        float scaleFactor;

        // True if dispatch has started.
        bool inProgress;

        // Set to the resolved action and flags when the event is enqueued.
        int32_t resolvedAction;
        int32_t resolvedFlags;

        // For motion events:
        //   Pointer to the first motion sample to dispatch in this cycle.
        //   Usually NULL to indicate that the list of motion samples begins at
        //   MotionEntry::firstSample.  Otherwise, some samples were dispatched in a previous
        //   cycle and this pointer indicates the location of the first remainining sample
        //   to dispatch during the current cycle.
        MotionSample* headMotionSample;
        //   Pointer to a motion sample to dispatch in the next cycle if the dispatcher was
        //   unable to send all motion samples during this cycle.  On the next cycle,
        //   headMotionSample will be initialized to tailMotionSample and tailMotionSample
        //   will be set to NULL.
        MotionSample* tailMotionSample;

        DispatchEntry(EventEntry* eventEntry,
                int32_t targetFlags, float xOffset, float yOffset, float scaleFactor);
        ~DispatchEntry();

        inline bool hasForegroundTarget() const {
            return targetFlags & InputTarget::FLAG_FOREGROUND;
        }

        inline bool isSplit() const {
            return targetFlags & InputTarget::FLAG_SPLIT;
        }
    };

    // A command entry captures state and behavior for an action to be performed in the
    // dispatch loop after the initial processing has taken place.  It is essentially
    // a kind of continuation used to postpone sensitive policy interactions to a point
    // in the dispatch loop where it is safe to release the lock (generally after finishing
    // the critical parts of the dispatch cycle).
    //
    // The special thing about commands is that they can voluntarily release and reacquire
    // the dispatcher lock at will.  Initially when the command starts running, the
    // dispatcher lock is held.  However, if the command needs to call into the policy to
    // do some work, it can release the lock, do the work, then reacquire the lock again
    // before returning.
    //
    // This mechanism is a bit clunky but it helps to preserve the invariant that the dispatch
    // never calls into the policy while holding its lock.
    //
    // Commands are implicitly 'LockedInterruptible'.
    struct CommandEntry;
    typedef void (InputDispatcher::*Command)(CommandEntry* commandEntry);

    class Connection;
    struct CommandEntry : Link<CommandEntry> {
        CommandEntry(Command command);
        ~CommandEntry();

        Command command;

        // parameters for the command (usage varies by command)
        sp<Connection> connection;
        nsecs_t eventTime;
        KeyEntry* keyEntry;
        sp<InputApplicationHandle> inputApplicationHandle;
        sp<InputWindowHandle> inputWindowHandle;
        int32_t userActivityEventType;
        bool handled;
    };

    // Generic queue implementation.
    template <typename T>
    struct Queue {
        T* head;
        T* tail;

        inline Queue() : head(NULL), tail(NULL) {
        }

        inline bool isEmpty() const {
            return !head;
        }

        inline void enqueueAtTail(T* entry) {
            entry->prev = tail;
            if (tail) {
                tail->next = entry;
            } else {
                head = entry;
            }
            entry->next = NULL;
            tail = entry;
        }

        inline void enqueueAtHead(T* entry) {
            entry->next = head;
            if (head) {
                head->prev = entry;
            } else {
                tail = entry;
            }
            entry->prev = NULL;
            head = entry;
        }

        inline void dequeue(T* entry) {
            if (entry->prev) {
                entry->prev->next = entry->next;
            } else {
                head = entry->next;
            }
            if (entry->next) {
                entry->next->prev = entry->prev;
            } else {
                tail = entry->prev;
            }
        }

        inline T* dequeueAtHead() {
            T* entry = head;
            head = entry->next;
            if (head) {
                head->prev = NULL;
            } else {
                tail = NULL;
            }
            return entry;
        }

        uint32_t count() const;
    };

    /* Specifies which events are to be canceled and why. */
    struct CancelationOptions {
        enum Mode {
            CANCEL_ALL_EVENTS = 0,
            CANCEL_POINTER_EVENTS = 1,
            CANCEL_NON_POINTER_EVENTS = 2,
            CANCEL_FALLBACK_EVENTS = 3,
        };

        // The criterion to use to determine which events should be canceled.
        Mode mode;

        // Descriptive reason for the cancelation.
        const char* reason;

        // The specific keycode of the key event to cancel, or -1 to cancel any key event.
        int32_t keyCode;

        // The specific device id of events to cancel, or -1 to cancel events from any device.
        int32_t deviceId;

        CancelationOptions(Mode mode, const char* reason) :
                mode(mode), reason(reason), keyCode(-1), deviceId(-1) { }
    };

    /* Tracks dispatched key and motion event state so that cancelation events can be
     * synthesized when events are dropped. */
    class InputState {
    public:
        InputState();
        ~InputState();

        // Returns true if there is no state to be canceled.
        bool isNeutral() const;

        // Returns true if the specified source is known to have received a hover enter
        // motion event.
        bool isHovering(int32_t deviceId, uint32_t source) const;

        // Records tracking information for a key event that has just been published.
        // Returns true if the event should be delivered, false if it is inconsistent
        // and should be skipped.
        bool trackKey(const KeyEntry* entry, int32_t action, int32_t flags);

        // Records tracking information for a motion event that has just been published.
        // Returns true if the event should be delivered, false if it is inconsistent
        // and should be skipped.
        bool trackMotion(const MotionEntry* entry, int32_t action, int32_t flags);

        // Synthesizes cancelation events for the current state and resets the tracked state.
        void synthesizeCancelationEvents(nsecs_t currentTime,
                Vector<EventEntry*>& outEvents, const CancelationOptions& options);

        // Clears the current state.
        void clear();

        // Copies pointer-related parts of the input state to another instance.
        void copyPointerStateTo(InputState& other) const;

        // Gets the fallback key associated with a keycode.
        // Returns -1 if none.
        // Returns AKEYCODE_UNKNOWN if we are only dispatching the unhandled key to the policy.
        int32_t getFallbackKey(int32_t originalKeyCode);

        // Sets the fallback key for a particular keycode.
        void setFallbackKey(int32_t originalKeyCode, int32_t fallbackKeyCode);

        // Removes the fallback key for a particular keycode.
        void removeFallbackKey(int32_t originalKeyCode);

        inline const KeyedVector<int32_t, int32_t>& getFallbackKeys() const {
            return mFallbackKeys;
        }

    private:
        struct KeyMemento {
            int32_t deviceId;
            uint32_t source;
            int32_t keyCode;
            int32_t scanCode;
            int32_t flags;
            nsecs_t downTime;
        };

        struct MotionMemento {
            int32_t deviceId;
            uint32_t source;
            int32_t flags;
            float xPrecision;
            float yPrecision;
            nsecs_t downTime;
            uint32_t pointerCount;
            PointerProperties pointerProperties[MAX_POINTERS];
            PointerCoords pointerCoords[MAX_POINTERS];
            bool hovering;

            void setPointers(const MotionEntry* entry);
        };

        Vector<KeyMemento> mKeyMementos;
        Vector<MotionMemento> mMotionMementos;
        KeyedVector<int32_t, int32_t> mFallbackKeys;

        ssize_t findKeyMemento(const KeyEntry* entry) const;
        ssize_t findMotionMemento(const MotionEntry* entry, bool hovering) const;

        void addKeyMemento(const KeyEntry* entry, int32_t flags);
        void addMotionMemento(const MotionEntry* entry, int32_t flags, bool hovering);

        static bool shouldCancelKey(const KeyMemento& memento,
                const CancelationOptions& options);
        static bool shouldCancelMotion(const MotionMemento& memento,
                const CancelationOptions& options);
    };

    /* Manages the dispatch state associated with a single input channel. */
    class Connection : public RefBase {
    protected:
        virtual ~Connection();

    public:
        enum Status {
            // Everything is peachy.
            STATUS_NORMAL,
            // An unrecoverable communication error has occurred.
            STATUS_BROKEN,
            // The input channel has been unregistered.
            STATUS_ZOMBIE
        };

        Status status;
        sp<InputChannel> inputChannel; // never null
        sp<InputWindowHandle> inputWindowHandle; // may be null
        bool monitor;
        InputPublisher inputPublisher;
        InputState inputState;
        Queue<DispatchEntry> outboundQueue;

        nsecs_t lastEventTime; // the time when the event was originally captured
        nsecs_t lastDispatchTime; // the time when the last event was dispatched

        explicit Connection(const sp<InputChannel>& inputChannel,
                const sp<InputWindowHandle>& inputWindowHandle, bool monitor);

        inline const char* getInputChannelName() const { return inputChannel->getName().string(); }

        const char* getStatusLabel() const;

        // Finds a DispatchEntry in the outbound queue associated with the specified event.
        // Returns NULL if not found.
        DispatchEntry* findQueuedDispatchEntryForEvent(const EventEntry* eventEntry) const;

        // Gets the time since the current event was originally obtained from the input driver.
        inline double getEventLatencyMillis(nsecs_t currentTime) const {
            return (currentTime - lastEventTime) / 1000000.0;
        }

        // Gets the time since the current event entered the outbound dispatch queue.
        inline double getDispatchLatencyMillis(nsecs_t currentTime) const {
            return (currentTime - lastDispatchTime) / 1000000.0;
        }

        status_t initialize();
    };

    enum DropReason {
        DROP_REASON_NOT_DROPPED = 0,
        DROP_REASON_POLICY = 1,
        DROP_REASON_APP_SWITCH = 2,
        DROP_REASON_DISABLED = 3,
        DROP_REASON_BLOCKED = 4,
        DROP_REASON_STALE = 5,
    };

    sp<InputDispatcherPolicyInterface> mPolicy;
    InputDispatcherConfiguration mConfig;

    Mutex mLock;

    Condition mDispatcherIsAliveCondition;

    sp<Looper> mLooper;

    EventEntry* mPendingEvent;
    Queue<EventEntry> mInboundQueue;
    Queue<CommandEntry> mCommandQueue;

    Vector<EventEntry*> mTempCancelationEvents;

    void dispatchOnceInnerLocked(nsecs_t* nextWakeupTime);
    void dispatchIdleLocked();

    // Batches a new sample onto a motion entry.
    // Assumes that the we have already checked that we can append samples.
    void batchMotionLocked(MotionEntry* entry, nsecs_t eventTime, int32_t metaState,
            const PointerCoords* pointerCoords, const char* eventDescription);

    // Enqueues an inbound event.  Returns true if mLooper->wake() should be called.
    bool enqueueInboundEventLocked(EventEntry* entry);

    // Cleans up input state when dropping an inbound event.
    void dropInboundEventLocked(EventEntry* entry, DropReason dropReason);

    // App switch latency optimization.
    bool mAppSwitchSawKeyDown;
    nsecs_t mAppSwitchDueTime;

    static bool isAppSwitchKeyCode(int32_t keyCode);
    bool isAppSwitchKeyEventLocked(KeyEntry* keyEntry);
    bool isAppSwitchPendingLocked();
    void resetPendingAppSwitchLocked(bool handled);

    // Stale event latency optimization.
    static bool isStaleEventLocked(nsecs_t currentTime, EventEntry* entry);

    // Blocked event latency optimization.  Drops old events when the user intends
    // to transfer focus to a new application.
    EventEntry* mNextUnblockedEvent;

    sp<InputWindowHandle> findTouchedWindowAtLocked(int32_t x, int32_t y);

    // All registered connections mapped by receive pipe file descriptor.
    KeyedVector<int, sp<Connection> > mConnectionsByReceiveFd;

    ssize_t getConnectionIndexLocked(const sp<InputChannel>& inputChannel);

    // Active connections are connections that have a non-empty outbound queue.
    // We don't use a ref-counted pointer here because we explicitly abort connections
    // during unregistration which causes the connection's outbound queue to be cleared
    // and the connection itself to be deactivated.
    Vector<Connection*> mActiveConnections;

    // Input channels that will receive a copy of all input events.
    Vector<sp<InputChannel> > mMonitoringChannels;

    // Event injection and synchronization.
    Condition mInjectionResultAvailableCondition;
    bool hasInjectionPermission(int32_t injectorPid, int32_t injectorUid);
    void setInjectionResultLocked(EventEntry* entry, int32_t injectionResult);

    Condition mInjectionSyncFinishedCondition;
    void incrementPendingForegroundDispatchesLocked(EventEntry* entry);
    void decrementPendingForegroundDispatchesLocked(EventEntry* entry);

    // Throttling state.
    struct ThrottleState {
        nsecs_t minTimeBetweenEvents;

        nsecs_t lastEventTime;
        int32_t lastDeviceId;
        uint32_t lastSource;

        uint32_t originalSampleCount; // only collected during debugging
    } mThrottleState;

    // Key repeat tracking.
    struct KeyRepeatState {
        KeyEntry* lastKeyEntry; // or null if no repeat
        nsecs_t nextRepeatTime;
    } mKeyRepeatState;

    void resetKeyRepeatLocked();
    KeyEntry* synthesizeKeyRepeatLocked(nsecs_t currentTime);

    // Deferred command processing.
    bool runCommandsLockedInterruptible();
    CommandEntry* postCommandLocked(Command command);

    // Inbound event processing.
    void drainInboundQueueLocked();
    void releasePendingEventLocked();
    void releaseInboundEventLocked(EventEntry* entry);

    // Dispatch state.
    bool mDispatchEnabled;
    bool mDispatchFrozen;
    bool mInputFilterEnabled;

    Vector<sp<InputWindowHandle> > mWindowHandles;

    sp<InputWindowHandle> getWindowHandleLocked(const sp<InputChannel>& inputChannel) const;
    bool hasWindowHandleLocked(const sp<InputWindowHandle>& windowHandle) const;

    // Focus tracking for keys, trackball, etc.
    sp<InputWindowHandle> mFocusedWindowHandle;

    // Focus tracking for touch.
    struct TouchedWindow {
        sp<InputWindowHandle> windowHandle;
        int32_t targetFlags;
        BitSet32 pointerIds;        // zero unless target flag FLAG_SPLIT is set
    };
    struct TouchState {
        bool down;
        bool split;
        int32_t deviceId; // id of the device that is currently down, others are rejected
        uint32_t source;  // source of the device that is current down, others are rejected
        Vector<TouchedWindow> windows;

        TouchState();
        ~TouchState();
        void reset();
        void copyFrom(const TouchState& other);
        void addOrUpdateWindow(const sp<InputWindowHandle>& windowHandle,
                int32_t targetFlags, BitSet32 pointerIds);
        void filterNonAsIsTouchWindows();
        sp<InputWindowHandle> getFirstForegroundWindowHandle() const;
        bool isSlippery() const;
    };

    TouchState mTouchState;
    TouchState mTempTouchState;

    // Focused application.
    sp<InputApplicationHandle> mFocusedApplicationHandle;

    // Dispatch inbound events.
    bool dispatchConfigurationChangedLocked(
            nsecs_t currentTime, ConfigurationChangedEntry* entry);
    bool dispatchDeviceResetLocked(
            nsecs_t currentTime, DeviceResetEntry* entry);
    bool dispatchKeyLocked(
            nsecs_t currentTime, KeyEntry* entry,
            DropReason* dropReason, nsecs_t* nextWakeupTime);
    bool dispatchMotionLocked(
            nsecs_t currentTime, MotionEntry* entry,
            DropReason* dropReason, nsecs_t* nextWakeupTime);
    void dispatchEventToCurrentInputTargetsLocked(
            nsecs_t currentTime, EventEntry* entry, bool resumeWithAppendedMotionSample);

    void logOutboundKeyDetailsLocked(const char* prefix, const KeyEntry* entry);
    void logOutboundMotionDetailsLocked(const char* prefix, const MotionEntry* entry);

    // The input targets that were most recently identified for dispatch.
    bool mCurrentInputTargetsValid; // false while targets are being recomputed
    Vector<InputTarget> mCurrentInputTargets;

    enum InputTargetWaitCause {
        INPUT_TARGET_WAIT_CAUSE_NONE,
        INPUT_TARGET_WAIT_CAUSE_SYSTEM_NOT_READY,
        INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY,
    };

    InputTargetWaitCause mInputTargetWaitCause;
    nsecs_t mInputTargetWaitStartTime;
    nsecs_t mInputTargetWaitTimeoutTime;
    bool mInputTargetWaitTimeoutExpired;
    sp<InputApplicationHandle> mInputTargetWaitApplicationHandle;

    // Contains the last window which received a hover event.
    sp<InputWindowHandle> mLastHoverWindowHandle;

    // Finding targets for input events.
    void resetTargetsLocked();
    void commitTargetsLocked();
    int32_t handleTargetsNotReadyLocked(nsecs_t currentTime, const EventEntry* entry,
            const sp<InputApplicationHandle>& applicationHandle,
            const sp<InputWindowHandle>& windowHandle,
            nsecs_t* nextWakeupTime);
    void resumeAfterTargetsNotReadyTimeoutLocked(nsecs_t newTimeout,
            const sp<InputChannel>& inputChannel);
    nsecs_t getTimeSpentWaitingForApplicationLocked(nsecs_t currentTime);
    void resetANRTimeoutsLocked();

    int32_t findFocusedWindowTargetsLocked(nsecs_t currentTime, const EventEntry* entry,
            nsecs_t* nextWakeupTime);
    int32_t findTouchedWindowTargetsLocked(nsecs_t currentTime, const MotionEntry* entry,
            nsecs_t* nextWakeupTime, bool* outConflictingPointerActions,
            const MotionSample** outSplitBatchAfterSample);

    void addWindowTargetLocked(const sp<InputWindowHandle>& windowHandle,
            int32_t targetFlags, BitSet32 pointerIds);
    void addMonitoringTargetsLocked();
    void pokeUserActivityLocked(const EventEntry* eventEntry);
    bool checkInjectionPermission(const sp<InputWindowHandle>& windowHandle,
            const InjectionState* injectionState);
    bool isWindowObscuredAtPointLocked(const sp<InputWindowHandle>& windowHandle,
            int32_t x, int32_t y) const;
    bool isWindowFinishedWithPreviousInputLocked(const sp<InputWindowHandle>& windowHandle);
    String8 getApplicationWindowLabelLocked(const sp<InputApplicationHandle>& applicationHandle,
            const sp<InputWindowHandle>& windowHandle);

    // Manage the dispatch cycle for a single connection.
    // These methods are deliberately not Interruptible because doing all of the work
    // with the mutex held makes it easier to ensure that connection invariants are maintained.
    // If needed, the methods post commands to run later once the critical bits are done.
    void prepareDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection,
            EventEntry* eventEntry, const InputTarget* inputTarget,
            bool resumeWithAppendedMotionSample);
    void enqueueDispatchEntriesLocked(nsecs_t currentTime, const sp<Connection>& connection,
            EventEntry* eventEntry, const InputTarget* inputTarget,
            bool resumeWithAppendedMotionSample);
    void enqueueDispatchEntryLocked(const sp<Connection>& connection,
            EventEntry* eventEntry, const InputTarget* inputTarget,
            bool resumeWithAppendedMotionSample, int32_t dispatchMode);
    void startDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void finishDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection,
            bool handled);
    void startNextDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void abortBrokenDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection,
            bool notify);
    void drainOutboundQueueLocked(Connection* connection);
    static int handleReceiveCallback(int receiveFd, int events, void* data);

    void synthesizeCancelationEventsForAllConnectionsLocked(
            const CancelationOptions& options);
    void synthesizeCancelationEventsForInputChannelLocked(const sp<InputChannel>& channel,
            const CancelationOptions& options);
    void synthesizeCancelationEventsForConnectionLocked(const sp<Connection>& connection,
            const CancelationOptions& options);

    // Splitting motion events across windows.
    MotionEntry* splitMotionEvent(const MotionEntry* originalMotionEntry, BitSet32 pointerIds);

    // Reset and drop everything the dispatcher is doing.
    void resetAndDropEverythingLocked(const char* reason);

    // Dump state.
    void dumpDispatchStateLocked(String8& dump);
    void logDispatchStateLocked();

    // Registration.
    void removeMonitorChannelLocked(const sp<InputChannel>& inputChannel);
    status_t unregisterInputChannelLocked(const sp<InputChannel>& inputChannel, bool notify);

    // Add or remove a connection to the mActiveConnections vector.
    void activateConnectionLocked(Connection* connection);
    void deactivateConnectionLocked(Connection* connection);

    // Interesting events that we might like to log or tell the framework about.
    void onDispatchCycleStartedLocked(
            nsecs_t currentTime, const sp<Connection>& connection);
    void onDispatchCycleFinishedLocked(
            nsecs_t currentTime, const sp<Connection>& connection, bool handled);
    void onDispatchCycleBrokenLocked(
            nsecs_t currentTime, const sp<Connection>& connection);
    void onANRLocked(
            nsecs_t currentTime, const sp<InputApplicationHandle>& applicationHandle,
            const sp<InputWindowHandle>& windowHandle,
            nsecs_t eventTime, nsecs_t waitStartTime);

    // Outbound policy interactions.
    void doNotifyConfigurationChangedInterruptible(CommandEntry* commandEntry);
    void doNotifyInputChannelBrokenLockedInterruptible(CommandEntry* commandEntry);
    void doNotifyANRLockedInterruptible(CommandEntry* commandEntry);
    void doInterceptKeyBeforeDispatchingLockedInterruptible(CommandEntry* commandEntry);
    void doDispatchCycleFinishedLockedInterruptible(CommandEntry* commandEntry);
    bool afterKeyEventLockedInterruptible(const sp<Connection>& connection,
            DispatchEntry* dispatchEntry, KeyEntry* keyEntry, bool handled);
    bool afterMotionEventLockedInterruptible(const sp<Connection>& connection,
            DispatchEntry* dispatchEntry, MotionEntry* motionEntry, bool handled);
    void doPokeUserActivityLockedInterruptible(CommandEntry* commandEntry);
    void initializeKeyEvent(KeyEvent* event, const KeyEntry* entry);

    // Statistics gathering.
    void updateDispatchStatisticsLocked(nsecs_t currentTime, const EventEntry* entry,
            int32_t injectionResult, nsecs_t timeSpentWaitingForApplication);
};

/* Enqueues and dispatches input events, endlessly. */
class InputDispatcherThread : public Thread {
public:
    explicit InputDispatcherThread(const sp<InputDispatcherInterface>& dispatcher);
    ~InputDispatcherThread();

private:
    virtual bool threadLoop();

    sp<InputDispatcherInterface> mDispatcher;
};

} // namespace android

#endif // _UI_INPUT_DISPATCHER_H
