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
#include <utils/Pool.h>
#include <utils/BitSet.h>

#include <stddef.h>
#include <unistd.h>
#include <limits.h>


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
        FLAG_FOREGROUND = 0x01,

        /* This flag indicates that a MotionEvent with AMOTION_EVENT_ACTION_DOWN falls outside
         * of the area of this target and so should instead be delivered as an
         * AMOTION_EVENT_ACTION_OUTSIDE to this target. */
        FLAG_OUTSIDE = 0x02,

        /* This flag indicates that the target of a MotionEvent is partly or wholly
         * obscured by another visible window above it.  The motion event should be
         * delivered with flag AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED. */
        FLAG_WINDOW_IS_OBSCURED = 0x04,

        /* This flag indicates that a motion event is being split across multiple windows. */
        FLAG_SPLIT = 0x08,
    };

    // The input channel to be targeted.
    sp<InputChannel> inputChannel;

    // Flags for the input target.
    int32_t flags;

    // The x and y offset to add to a MotionEvent as it is delivered.
    // (ignored for KeyEvents)
    float xOffset, yOffset;

    // The subset of pointer ids to include in motion events dispatched to this input target
    // if FLAG_SPLIT is set.
    BitSet32 pointerIds;
};


/*
 * An input window describes the bounds of a window that can receive input.
 */
struct InputWindow {
    // Window flags from WindowManager.LayoutParams
    enum {
        FLAG_ALLOW_LOCK_WHILE_SCREEN_ON     = 0x00000001,
        FLAG_DIM_BEHIND        = 0x00000002,
        FLAG_BLUR_BEHIND        = 0x00000004,
        FLAG_NOT_FOCUSABLE      = 0x00000008,
        FLAG_NOT_TOUCHABLE      = 0x00000010,
        FLAG_NOT_TOUCH_MODAL    = 0x00000020,
        FLAG_TOUCHABLE_WHEN_WAKING = 0x00000040,
        FLAG_KEEP_SCREEN_ON     = 0x00000080,
        FLAG_LAYOUT_IN_SCREEN   = 0x00000100,
        FLAG_LAYOUT_NO_LIMITS   = 0x00000200,
        FLAG_FULLSCREEN      = 0x00000400,
        FLAG_FORCE_NOT_FULLSCREEN   = 0x00000800,
        FLAG_DITHER             = 0x00001000,
        FLAG_SECURE             = 0x00002000,
        FLAG_SCALED             = 0x00004000,
        FLAG_IGNORE_CHEEK_PRESSES    = 0x00008000,
        FLAG_LAYOUT_INSET_DECOR = 0x00010000,
        FLAG_ALT_FOCUSABLE_IM = 0x00020000,
        FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000,
        FLAG_SHOW_WHEN_LOCKED = 0x00080000,
        FLAG_SHOW_WALLPAPER = 0x00100000,
        FLAG_TURN_SCREEN_ON = 0x00200000,
        FLAG_DISMISS_KEYGUARD = 0x00400000,
        FLAG_SPLIT_TOUCH = 0x00800000,
        FLAG_KEEP_SURFACE_WHILE_ANIMATING = 0x10000000,
        FLAG_COMPATIBLE_WINDOW = 0x20000000,
        FLAG_SYSTEM_ERROR = 0x40000000,
    };

    // Window types from WindowManager.LayoutParams
    enum {
        FIRST_APPLICATION_WINDOW = 1,
        TYPE_BASE_APPLICATION   = 1,
        TYPE_APPLICATION        = 2,
        TYPE_APPLICATION_STARTING = 3,
        LAST_APPLICATION_WINDOW = 99,
        FIRST_SUB_WINDOW        = 1000,
        TYPE_APPLICATION_PANEL  = FIRST_SUB_WINDOW,
        TYPE_APPLICATION_MEDIA  = FIRST_SUB_WINDOW+1,
        TYPE_APPLICATION_SUB_PANEL = FIRST_SUB_WINDOW+2,
        TYPE_APPLICATION_ATTACHED_DIALOG = FIRST_SUB_WINDOW+3,
        TYPE_APPLICATION_MEDIA_OVERLAY  = FIRST_SUB_WINDOW+4,
        LAST_SUB_WINDOW         = 1999,
        FIRST_SYSTEM_WINDOW     = 2000,
        TYPE_STATUS_BAR         = FIRST_SYSTEM_WINDOW,
        TYPE_SEARCH_BAR         = FIRST_SYSTEM_WINDOW+1,
        TYPE_PHONE              = FIRST_SYSTEM_WINDOW+2,
        TYPE_SYSTEM_ALERT       = FIRST_SYSTEM_WINDOW+3,
        TYPE_KEYGUARD           = FIRST_SYSTEM_WINDOW+4,
        TYPE_TOAST              = FIRST_SYSTEM_WINDOW+5,
        TYPE_SYSTEM_OVERLAY     = FIRST_SYSTEM_WINDOW+6,
        TYPE_PRIORITY_PHONE     = FIRST_SYSTEM_WINDOW+7,
        TYPE_SYSTEM_DIALOG      = FIRST_SYSTEM_WINDOW+8,
        TYPE_KEYGUARD_DIALOG    = FIRST_SYSTEM_WINDOW+9,
        TYPE_SYSTEM_ERROR       = FIRST_SYSTEM_WINDOW+10,
        TYPE_INPUT_METHOD       = FIRST_SYSTEM_WINDOW+11,
        TYPE_INPUT_METHOD_DIALOG= FIRST_SYSTEM_WINDOW+12,
        TYPE_WALLPAPER          = FIRST_SYSTEM_WINDOW+13,
        TYPE_STATUS_BAR_PANEL   = FIRST_SYSTEM_WINDOW+14,
        TYPE_SECURE_SYSTEM_OVERLAY = FIRST_SYSTEM_WINDOW+15,
        LAST_SYSTEM_WINDOW      = 2999,
    };

    sp<InputChannel> inputChannel;
    String8 name;
    int32_t layoutParamsFlags;
    int32_t layoutParamsType;
    nsecs_t dispatchingTimeout;
    int32_t frameLeft;
    int32_t frameTop;
    int32_t frameRight;
    int32_t frameBottom;
    int32_t visibleFrameLeft;
    int32_t visibleFrameTop;
    int32_t visibleFrameRight;
    int32_t visibleFrameBottom;
    int32_t touchableAreaLeft;
    int32_t touchableAreaTop;
    int32_t touchableAreaRight;
    int32_t touchableAreaBottom;
    bool visible;
    bool canReceiveKeys;
    bool hasFocus;
    bool hasWallpaper;
    bool paused;
    int32_t layer;
    int32_t ownerPid;
    int32_t ownerUid;

    bool touchableAreaContainsPoint(int32_t x, int32_t y) const;
    bool frameContainsPoint(int32_t x, int32_t y) const;

    /* Returns true if the window is of a trusted type that is allowed to silently
     * overlay other windows for the purpose of implementing the secure views feature.
     * Trusted overlays, such as IME windows, can partly obscure other windows without causing
     * motion events to be delivered to them with AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED.
     */
    bool isTrustedOverlay() const;
};


/*
 * A private handle type used by the input manager to track the window.
 */
class InputApplicationHandle : public RefBase {
protected:
    InputApplicationHandle() { }
    virtual ~InputApplicationHandle() { }
};


/*
 * An input application describes properties of an application that can receive input.
 */
struct InputApplication {
    String8 name;
    nsecs_t dispatchingTimeout;
    sp<InputApplicationHandle> handle;
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
            const sp<InputChannel>& inputChannel) = 0;

    /* Notifies the system that an input channel is unrecoverably broken. */
    virtual void notifyInputChannelBroken(const sp<InputChannel>& inputChannel) = 0;

    /* Gets the key repeat initial timeout or -1 if automatic key repeating is disabled. */
    virtual nsecs_t getKeyRepeatTimeout() = 0;

    /* Gets the key repeat inter-key delay. */
    virtual nsecs_t getKeyRepeatDelay() = 0;

    /* Gets the maximum suggested event delivery rate per second.
     * This value is used to throttle motion event movement actions on a per-device
     * basis.  It is not intended to be a hard limit.
     */
    virtual int32_t getMaxEventsPerSecond() = 0;

    /* Intercepts a key event immediately before queueing it.
     * The policy can use this method as an opportunity to perform power management functions
     * and early event preprocessing such as updating policy flags.
     *
     * This method is expected to set the POLICY_FLAG_PASS_TO_USER policy flag if the event
     * should be dispatched to applications.
     */
    virtual void interceptKeyBeforeQueueing(nsecs_t when, int32_t deviceId,
            int32_t action, int32_t& flags, int32_t keyCode, int32_t scanCode,
            uint32_t& policyFlags) = 0;

    /* Intercepts a generic touch, trackball or other event before queueing it.
     * The policy can use this method as an opportunity to perform power management functions
     * and early event preprocessing such as updating policy flags.
     *
     * This method is expected to set the POLICY_FLAG_PASS_TO_USER policy flag if the event
     * should be dispatched to applications.
     */
    virtual void interceptGenericBeforeQueueing(nsecs_t when, uint32_t& policyFlags) = 0;

    /* Allows the policy a chance to intercept a key before dispatching. */
    virtual bool interceptKeyBeforeDispatching(const sp<InputChannel>& inputChannel,
            const KeyEvent* keyEvent, uint32_t policyFlags) = 0;

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
class InputDispatcherInterface : public virtual RefBase {
protected:
    InputDispatcherInterface() { }
    virtual ~InputDispatcherInterface() { }

public:
    /* Dumps the state of the input dispatcher.
     *
     * This method may be called on any thread (usually by the input manager). */
    virtual void dump(String8& dump) = 0;

    /* Runs a single iteration of the dispatch loop.
     * Nominally processes one queued event, a timeout, or a response from an input consumer.
     *
     * This method should only be called on the input dispatcher thread.
     */
    virtual void dispatchOnce() = 0;

    /* Notifies the dispatcher about new events.
     *
     * These methods should only be called on the input reader thread.
     */
    virtual void notifyConfigurationChanged(nsecs_t eventTime) = 0;
    virtual void notifyKey(nsecs_t eventTime, int32_t deviceId, int32_t source,
            uint32_t policyFlags, int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime) = 0;
    virtual void notifyMotion(nsecs_t eventTime, int32_t deviceId, int32_t source,
            uint32_t policyFlags, int32_t action, int32_t flags,
            int32_t metaState, int32_t edgeFlags,
            uint32_t pointerCount, const int32_t* pointerIds, const PointerCoords* pointerCoords,
            float xPrecision, float yPrecision, nsecs_t downTime) = 0;
    virtual void notifySwitch(nsecs_t when,
            int32_t switchCode, int32_t switchValue, uint32_t policyFlags) = 0;

    /* Injects an input event and optionally waits for sync.
     * The synchronization mode determines whether the method blocks while waiting for
     * input injection to proceed.
     * Returns one of the INPUT_EVENT_INJECTION_XXX constants.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, int32_t syncMode, int32_t timeoutMillis) = 0;

    /* Sets the list of input windows.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void setInputWindows(const Vector<InputWindow>& inputWindows) = 0;

    /* Sets the focused application.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void setFocusedApplication(const InputApplication* inputApplication) = 0;

    /* Sets the input dispatching mode.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void setInputDispatchMode(bool enabled, bool frozen) = 0;

    /* Registers or unregister input channels that may be used as targets for input events.
     * If monitor is true, the channel will receive a copy of all input events.
     *
     * These methods may be called on any thread (usually by the input manager).
     */
    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel, bool monitor) = 0;
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

    virtual void dispatchOnce();

    virtual void notifyConfigurationChanged(nsecs_t eventTime);
    virtual void notifyKey(nsecs_t eventTime, int32_t deviceId, int32_t source,
            uint32_t policyFlags, int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime);
    virtual void notifyMotion(nsecs_t eventTime, int32_t deviceId, int32_t source,
            uint32_t policyFlags, int32_t action, int32_t flags,
            int32_t metaState, int32_t edgeFlags,
            uint32_t pointerCount, const int32_t* pointerIds, const PointerCoords* pointerCoords,
            float xPrecision, float yPrecision, nsecs_t downTime);
    virtual void notifySwitch(nsecs_t when,
            int32_t switchCode, int32_t switchValue, uint32_t policyFlags) ;

    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, int32_t syncMode, int32_t timeoutMillis);

    virtual void setInputWindows(const Vector<InputWindow>& inputWindows);
    virtual void setFocusedApplication(const InputApplication* inputApplication);
    virtual void setInputDispatchMode(bool enabled, bool frozen);

    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel, bool monitor);
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
    };

    struct EventEntry : Link<EventEntry> {
        enum {
            TYPE_SENTINEL,
            TYPE_CONFIGURATION_CHANGED,
            TYPE_KEY,
            TYPE_MOTION
        };

        mutable int32_t refCount;
        int32_t type;
        nsecs_t eventTime;
        uint32_t policyFlags;
        InjectionState* injectionState;

        bool dispatchInProgress; // initially false, set to true while dispatching

        inline bool isInjected() { return injectionState != NULL; }
    };

    struct ConfigurationChangedEntry : EventEntry {
    };

    struct KeyEntry : EventEntry {
        int32_t deviceId;
        int32_t source;
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
        };
        InterceptKeyResult interceptKeyResult; // set based on the interception result
    };

    struct MotionSample {
        MotionSample* next;

        nsecs_t eventTime;
        PointerCoords pointerCoords[MAX_POINTERS];
    };

    struct MotionEntry : EventEntry {
        int32_t deviceId;
        int32_t source;
        int32_t action;
        int32_t flags;
        int32_t metaState;
        int32_t edgeFlags;
        float xPrecision;
        float yPrecision;
        nsecs_t downTime;
        uint32_t pointerCount;
        int32_t pointerIds[MAX_POINTERS];

        // Linked list of motion samples associated with this motion event.
        MotionSample firstSample;
        MotionSample* lastSample;

        uint32_t countSamples() const;
    };

    // Tracks the progress of dispatching a particular event to a particular connection.
    struct DispatchEntry : Link<DispatchEntry> {
        EventEntry* eventEntry; // the event to dispatch
        int32_t targetFlags;
        float xOffset;
        float yOffset;

        // True if dispatch has started.
        bool inProgress;

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
        CommandEntry();
        ~CommandEntry();

        Command command;

        // parameters for the command (usage varies by command)
        sp<Connection> connection;
        nsecs_t eventTime;
        KeyEntry* keyEntry;
        sp<InputChannel> inputChannel;
        sp<InputApplicationHandle> inputApplicationHandle;
        int32_t userActivityEventType;
    };

    // Generic queue implementation.
    template <typename T>
    struct Queue {
        T headSentinel;
        T tailSentinel;

        inline Queue() {
            headSentinel.prev = NULL;
            headSentinel.next = & tailSentinel;
            tailSentinel.prev = & headSentinel;
            tailSentinel.next = NULL;
        }

        inline bool isEmpty() const {
            return headSentinel.next == & tailSentinel;
        }

        inline void enqueueAtTail(T* entry) {
            T* last = tailSentinel.prev;
            last->next = entry;
            entry->prev = last;
            entry->next = & tailSentinel;
            tailSentinel.prev = entry;
        }

        inline void enqueueAtHead(T* entry) {
            T* first = headSentinel.next;
            headSentinel.next = entry;
            entry->prev = & headSentinel;
            entry->next = first;
            first->prev = entry;
        }

        inline void dequeue(T* entry) {
            entry->prev->next = entry->next;
            entry->next->prev = entry->prev;
        }

        inline T* dequeueAtHead() {
            T* first = headSentinel.next;
            dequeue(first);
            return first;
        }

        uint32_t count() const;
    };

    /* Allocates queue entries and performs reference counting as needed. */
    class Allocator {
    public:
        Allocator();

        InjectionState* obtainInjectionState(int32_t injectorPid, int32_t injectorUid);
        ConfigurationChangedEntry* obtainConfigurationChangedEntry(nsecs_t eventTime);
        KeyEntry* obtainKeyEntry(nsecs_t eventTime,
                int32_t deviceId, int32_t source, uint32_t policyFlags, int32_t action,
                int32_t flags, int32_t keyCode, int32_t scanCode, int32_t metaState,
                int32_t repeatCount, nsecs_t downTime);
        MotionEntry* obtainMotionEntry(nsecs_t eventTime,
                int32_t deviceId, int32_t source, uint32_t policyFlags, int32_t action,
                int32_t flags, int32_t metaState, int32_t edgeFlags,
                float xPrecision, float yPrecision,
                nsecs_t downTime, uint32_t pointerCount,
                const int32_t* pointerIds, const PointerCoords* pointerCoords);
        DispatchEntry* obtainDispatchEntry(EventEntry* eventEntry,
                int32_t targetFlags, float xOffset, float yOffset);
        CommandEntry* obtainCommandEntry(Command command);

        void releaseInjectionState(InjectionState* injectionState);
        void releaseEventEntry(EventEntry* entry);
        void releaseConfigurationChangedEntry(ConfigurationChangedEntry* entry);
        void releaseKeyEntry(KeyEntry* entry);
        void releaseMotionEntry(MotionEntry* entry);
        void releaseDispatchEntry(DispatchEntry* entry);
        void releaseCommandEntry(CommandEntry* entry);

        void recycleKeyEntry(KeyEntry* entry);

        void appendMotionSample(MotionEntry* motionEntry,
                nsecs_t eventTime, const PointerCoords* pointerCoords);

    private:
        Pool<InjectionState> mInjectionStatePool;
        Pool<ConfigurationChangedEntry> mConfigurationChangeEntryPool;
        Pool<KeyEntry> mKeyEntryPool;
        Pool<MotionEntry> mMotionEntryPool;
        Pool<MotionSample> mMotionSamplePool;
        Pool<DispatchEntry> mDispatchEntryPool;
        Pool<CommandEntry> mCommandEntryPool;

        void initializeEventEntry(EventEntry* entry, int32_t type, nsecs_t eventTime,
                uint32_t policyFlags);
        void releaseEventEntryInjectionState(EventEntry* entry);
    };

    /* Tracks dispatched key and motion event state so that cancelation events can be
     * synthesized when events are dropped. */
    class InputState {
    public:
        // Specifies whether a given event will violate input state consistency.
        enum Consistency {
            // The event is consistent with the current input state.
            CONSISTENT,
            // The event is inconsistent with the current input state but applications
            // will tolerate it.  eg. Down followed by another down.
            TOLERABLE,
            // The event is inconsistent with the current input state and will probably
            // cause applications to crash.  eg. Up without prior down, move with
            // unexpected number of pointers.
            BROKEN
        };

        // Specifies the sources to cancel.
        enum CancelationOptions {
            CANCEL_ALL_EVENTS = 0,
            CANCEL_POINTER_EVENTS = 1,
            CANCEL_NON_POINTER_EVENTS = 2,
        };

        InputState();
        ~InputState();

        // Returns true if there is no state to be canceled.
        bool isNeutral() const;

        // Records tracking information for an event that has just been published.
        // Returns whether the event is consistent with the current input state.
        Consistency trackEvent(const EventEntry* entry);

        // Records tracking information for a key event that has just been published.
        // Returns whether the event is consistent with the current input state.
        Consistency trackKey(const KeyEntry* entry);

        // Records tracking information for a motion event that has just been published.
        // Returns whether the event is consistent with the current input state.
        Consistency trackMotion(const MotionEntry* entry);

        // Synthesizes cancelation events for the current state and resets the tracked state.
        void synthesizeCancelationEvents(nsecs_t currentTime, Allocator* allocator,
                Vector<EventEntry*>& outEvents, CancelationOptions options);

        // Clears the current state.
        void clear();

    private:
        struct KeyMemento {
            int32_t deviceId;
            int32_t source;
            int32_t keyCode;
            int32_t scanCode;
            nsecs_t downTime;
        };

        struct MotionMemento {
            int32_t deviceId;
            int32_t source;
            float xPrecision;
            float yPrecision;
            nsecs_t downTime;
            uint32_t pointerCount;
            int32_t pointerIds[MAX_POINTERS];
            PointerCoords pointerCoords[MAX_POINTERS];

            void setPointers(const MotionEntry* entry);
        };

        Vector<KeyMemento> mKeyMementos;
        Vector<MotionMemento> mMotionMementos;

        static bool shouldCancelEvent(int32_t eventSource, CancelationOptions options);
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
        sp<InputChannel> inputChannel;
        InputPublisher inputPublisher;
        InputState inputState;
        Queue<DispatchEntry> outboundQueue;

        nsecs_t lastEventTime; // the time when the event was originally captured
        nsecs_t lastDispatchTime; // the time when the last event was dispatched

        explicit Connection(const sp<InputChannel>& inputChannel);

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
    };

    sp<InputDispatcherPolicyInterface> mPolicy;

    Mutex mLock;

    Allocator mAllocator;
    sp<Looper> mLooper;

    EventEntry* mPendingEvent;
    Queue<EventEntry> mInboundQueue;
    Queue<CommandEntry> mCommandQueue;

    Vector<EventEntry*> mTempCancelationEvents;

    void dispatchOnceInnerLocked(nsecs_t keyRepeatTimeout, nsecs_t keyRepeatDelay,
            nsecs_t* nextWakeupTime);

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

    // Preallocated key event object used for policy inquiries.
    KeyEvent mReusableKeyEvent;

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
    KeyEntry* synthesizeKeyRepeatLocked(nsecs_t currentTime, nsecs_t keyRepeatTimeout);

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

    Vector<InputWindow> mWindows;

    const InputWindow* getWindowLocked(const sp<InputChannel>& inputChannel);

    // Focus tracking for keys, trackball, etc.
    const InputWindow* mFocusedWindow;

    // Focus tracking for touch.
    struct TouchedWindow {
        const InputWindow* window;
        int32_t targetFlags;
        BitSet32 pointerIds;
        sp<InputChannel> channel;
    };
    struct TouchState {
        bool down;
        bool split;
        Vector<TouchedWindow> windows;

        TouchState();
        ~TouchState();
        void reset();
        void copyFrom(const TouchState& other);
        void addOrUpdateWindow(const InputWindow* window, int32_t targetFlags, BitSet32 pointerIds);
        void removeOutsideTouchWindows();
        const InputWindow* getFirstForegroundWindow();
    };

    TouchState mTouchState;
    TouchState mTempTouchState;

    // Focused application.
    InputApplication* mFocusedApplication;
    InputApplication mFocusedApplicationStorage; // preallocated storage for mFocusedApplication
    void releaseFocusedApplicationLocked();

    // Dispatch inbound events.
    bool dispatchConfigurationChangedLocked(
            nsecs_t currentTime, ConfigurationChangedEntry* entry);
    bool dispatchKeyLocked(
            nsecs_t currentTime, KeyEntry* entry, nsecs_t keyRepeatTimeout,
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

    // Finding targets for input events.
    void resetTargetsLocked();
    void commitTargetsLocked();
    int32_t handleTargetsNotReadyLocked(nsecs_t currentTime, const EventEntry* entry,
            const InputApplication* application, const InputWindow* window,
            nsecs_t* nextWakeupTime);
    void resumeAfterTargetsNotReadyTimeoutLocked(nsecs_t newTimeout,
            const sp<InputChannel>& inputChannel);
    nsecs_t getTimeSpentWaitingForApplicationLocked(nsecs_t currentTime);
    void resetANRTimeoutsLocked();

    int32_t findFocusedWindowTargetsLocked(nsecs_t currentTime, const EventEntry* entry,
            nsecs_t* nextWakeupTime);
    int32_t findTouchedWindowTargetsLocked(nsecs_t currentTime, const MotionEntry* entry,
            nsecs_t* nextWakeupTime);

    void addWindowTargetLocked(const InputWindow* window, int32_t targetFlags,
            BitSet32 pointerIds);
    void addMonitoringTargetsLocked();
    void pokeUserActivityLocked(const EventEntry* eventEntry);
    bool checkInjectionPermission(const InputWindow* window, const InjectionState* injectionState);
    bool isWindowObscuredAtPointLocked(const InputWindow* window, int32_t x, int32_t y) const;
    bool isWindowFinishedWithPreviousInputLocked(const InputWindow* window);
    String8 getApplicationWindowLabelLocked(const InputApplication* application,
            const InputWindow* window);

    // Manage the dispatch cycle for a single connection.
    // These methods are deliberately not Interruptible because doing all of the work
    // with the mutex held makes it easier to ensure that connection invariants are maintained.
    // If needed, the methods post commands to run later once the critical bits are done.
    void prepareDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection,
            EventEntry* eventEntry, const InputTarget* inputTarget,
            bool resumeWithAppendedMotionSample);
    void startDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void finishDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void startNextDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void abortBrokenDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void drainOutboundQueueLocked(Connection* connection);
    static int handleReceiveCallback(int receiveFd, int events, void* data);

    void synthesizeCancelationEventsForAllConnectionsLocked(
            InputState::CancelationOptions options, const char* reason);
    void synthesizeCancelationEventsForInputChannelLocked(const sp<InputChannel>& channel,
            InputState::CancelationOptions options, const char* reason);
    void synthesizeCancelationEventsForConnectionLocked(const sp<Connection>& connection,
            InputState::CancelationOptions options, const char* reason);

    // Splitting motion events across windows.
    MotionEntry* splitMotionEvent(const MotionEntry* originalMotionEntry, BitSet32 pointerIds);

    // Reset and drop everything the dispatcher is doing.
    void resetAndDropEverythingLocked(const char* reason);

    // Dump state.
    void dumpDispatchStateLocked(String8& dump);
    void logDispatchStateLocked();

    // Add or remove a connection to the mActiveConnections vector.
    void activateConnectionLocked(Connection* connection);
    void deactivateConnectionLocked(Connection* connection);

    // Interesting events that we might like to log or tell the framework about.
    void onDispatchCycleStartedLocked(
            nsecs_t currentTime, const sp<Connection>& connection);
    void onDispatchCycleFinishedLocked(
            nsecs_t currentTime, const sp<Connection>& connection);
    void onDispatchCycleBrokenLocked(
            nsecs_t currentTime, const sp<Connection>& connection);
    void onANRLocked(
            nsecs_t currentTime, const InputApplication* application, const InputWindow* window,
            nsecs_t eventTime, nsecs_t waitStartTime);

    // Outbound policy interactions.
    void doNotifyConfigurationChangedInterruptible(CommandEntry* commandEntry);
    void doNotifyInputChannelBrokenLockedInterruptible(CommandEntry* commandEntry);
    void doNotifyANRLockedInterruptible(CommandEntry* commandEntry);
    void doInterceptKeyBeforeDispatchingLockedInterruptible(CommandEntry* commandEntry);
    void doPokeUserActivityLockedInterruptible(CommandEntry* commandEntry);

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
