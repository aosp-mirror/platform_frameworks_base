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
#include <utils/PollLoop.h>
#include <utils/Pool.h>

#include <stddef.h>
#include <unistd.h>


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
 * An input target specifies how an input event is to be dispatched to a particular window
 * including the window's input channel, control flags, a timeout, and an X / Y offset to
 * be added to input event coordinates to compensate for the absolute position of the
 * window area.
 */
struct InputTarget {
    enum {
        /* This flag indicates that subsequent event delivery should be held until the
         * current event is delivered to this target or a timeout occurs. */
        FLAG_SYNC = 0x01,

        /* This flag indicates that a MotionEvent with ACTION_DOWN falls outside of the area of
         * this target and so should instead be delivered as an ACTION_OUTSIDE to this target. */
        FLAG_OUTSIDE = 0x02,

        /* This flag indicates that a KeyEvent or MotionEvent is being canceled.
         * In the case of a key event, it should be delivered with KeyEvent.FLAG_CANCELED set.
         * In the case of a motion event, it should be delivered as MotionEvent.ACTION_CANCEL. */
        FLAG_CANCEL = 0x04
    };

    // The input channel to be targeted.
    sp<InputChannel> inputChannel;

    // Flags for the input target.
    int32_t flags;

    // The timeout for event delivery to this target in nanoseconds.  Or -1 if none.
    nsecs_t timeout;

    // The x and y offset to add to a MotionEvent as it is delivered.
    // (ignored for KeyEvents)
    float xOffset, yOffset;
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

    /* Notifies the system that an input channel is unrecoverably broken. */
    virtual void notifyInputChannelBroken(const sp<InputChannel>& inputChannel) = 0;

    /* Notifies the system that an input channel is not responding.
     * Returns true and a new timeout value if the dispatcher should keep waiting.
     * Otherwise returns false. */
    virtual bool notifyInputChannelANR(const sp<InputChannel>& inputChannel,
            nsecs_t& outNewTimeout) = 0;

    /* Notifies the system that an input channel recovered from ANR. */
    virtual void notifyInputChannelRecoveredFromANR(const sp<InputChannel>& inputChannel) = 0;

    /* Gets the key repeat timeout or -1 if automatic key repeating is disabled. */
    virtual nsecs_t getKeyRepeatTimeout() = 0;

    /* Waits for key event input targets to become available.
     * If the event is being injected, injectorPid and injectorUid should specify the
     * process id and used id of the injecting application, otherwise they should both
     * be -1.
     * Returns one of the INPUT_EVENT_INJECTION_XXX constants. */
    virtual int32_t waitForKeyEventTargets(KeyEvent* keyEvent, uint32_t policyFlags,
            int32_t injectorPid, int32_t injectorUid,
            Vector<InputTarget>& outTargets) = 0;

    /* Waits for motion event targets to become available.
     * If the event is being injected, injectorPid and injectorUid should specify the
     * process id and used id of the injecting application, otherwise they should both
     * be -1.
     * Returns one of the INPUT_EVENT_INJECTION_XXX constants. */
    virtual int32_t waitForMotionEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
            int32_t injectorPid, int32_t injectorUid,
            Vector<InputTarget>& outTargets) = 0;
};


/* Notifies the system about input events generated by the input reader.
 * The dispatcher is expected to be mostly asynchronous. */
class InputDispatcherInterface : public virtual RefBase {
protected:
    InputDispatcherInterface() { }
    virtual ~InputDispatcherInterface() { }

public:
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
    virtual void notifyAppSwitchComing(nsecs_t eventTime) = 0;
    virtual void notifyKey(nsecs_t eventTime, int32_t deviceId, int32_t nature,
            uint32_t policyFlags, int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime) = 0;
    virtual void notifyMotion(nsecs_t eventTime, int32_t deviceId, int32_t nature,
            uint32_t policyFlags, int32_t action, int32_t metaState, int32_t edgeFlags,
            uint32_t pointerCount, const int32_t* pointerIds, const PointerCoords* pointerCoords,
            float xPrecision, float yPrecision, nsecs_t downTime) = 0;

    /* Injects an input event and optionally waits for sync.
     * This method may block even if sync is false because it must wait for previous events
     * to be dispatched before it can determine whether input event injection will be
     * permitted based on the current input focus.
     * Returns one of the INPUT_EVENT_INJECTION_XXX constants.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, bool sync, int32_t timeoutMillis) = 0;

    /* Preempts input dispatch in progress by making pending synchronous
     * dispatches asynchronous instead.  This method is generally called during a focus
     * transition from one application to the next so as to enable the new application
     * to start receiving input as soon as possible without having to wait for the
     * old application to finish up.
     *
     * This method may be called on any thread (usually by the input manager).
     */
    virtual void preemptInputDispatch() = 0;

    /* Registers or unregister input channels that may be used as targets for input events.
     *
     * These methods may be called on any thread (usually by the input manager).
     */
    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel) = 0;
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

    virtual void dispatchOnce();

    virtual void notifyConfigurationChanged(nsecs_t eventTime);
    virtual void notifyAppSwitchComing(nsecs_t eventTime);
    virtual void notifyKey(nsecs_t eventTime, int32_t deviceId, int32_t nature,
            uint32_t policyFlags, int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime);
    virtual void notifyMotion(nsecs_t eventTime, int32_t deviceId, int32_t nature,
            uint32_t policyFlags, int32_t action, int32_t metaState, int32_t edgeFlags,
            uint32_t pointerCount, const int32_t* pointerIds, const PointerCoords* pointerCoords,
            float xPrecision, float yPrecision, nsecs_t downTime);

    virtual int32_t injectInputEvent(const InputEvent* event,
            int32_t injectorPid, int32_t injectorUid, bool sync, int32_t timeoutMillis);

    virtual void preemptInputDispatch();

    virtual status_t registerInputChannel(const sp<InputChannel>& inputChannel);
    virtual status_t unregisterInputChannel(const sp<InputChannel>& inputChannel);

private:
    template <typename T>
    struct Link {
        T* next;
        T* prev;
    };

    struct EventEntry : Link<EventEntry> {
        enum {
            TYPE_SENTINEL,
            TYPE_CONFIGURATION_CHANGED,
            TYPE_KEY,
            TYPE_MOTION
        };

        int32_t refCount;
        int32_t type;
        nsecs_t eventTime;

        int32_t injectionResult; // initially INPUT_EVENT_INJECTION_PENDING
        int32_t injectorPid;     // -1 if not injected
        int32_t injectorUid;     // -1 if not injected

        bool dispatchInProgress; // initially false, set to true while dispatching

        inline bool isInjected() { return injectorPid >= 0; }
    };

    struct ConfigurationChangedEntry : EventEntry {
    };

    struct KeyEntry : EventEntry {
        int32_t deviceId;
        int32_t nature;
        uint32_t policyFlags;
        int32_t action;
        int32_t flags;
        int32_t keyCode;
        int32_t scanCode;
        int32_t metaState;
        int32_t repeatCount;
        nsecs_t downTime;
    };

    struct MotionSample {
        MotionSample* next;

        nsecs_t eventTime;
        PointerCoords pointerCoords[MAX_POINTERS];
    };

    struct MotionEntry : EventEntry {
        int32_t deviceId;
        int32_t nature;
        uint32_t policyFlags;
        int32_t action;
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
    };

    // Tracks the progress of dispatching a particular event to a particular connection.
    struct DispatchEntry : Link<DispatchEntry> {
        EventEntry* eventEntry; // the event to dispatch
        int32_t targetFlags;
        float xOffset;
        float yOffset;
        nsecs_t timeout;

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
    };

    // Generic queue implementation.
    template <typename T>
    struct Queue {
        T head;
        T tail;

        inline Queue() {
            head.prev = NULL;
            head.next = & tail;
            tail.prev = & head;
            tail.next = NULL;
        }

        inline bool isEmpty() {
            return head.next == & tail;
        }

        inline void enqueueAtTail(T* entry) {
            T* last = tail.prev;
            last->next = entry;
            entry->prev = last;
            entry->next = & tail;
            tail.prev = entry;
        }

        inline void enqueueAtHead(T* entry) {
            T* first = head.next;
            head.next = entry;
            entry->prev = & head;
            entry->next = first;
            first->prev = entry;
        }

        inline void dequeue(T* entry) {
            entry->prev->next = entry->next;
            entry->next->prev = entry->prev;
        }

        inline T* dequeueAtHead() {
            T* first = head.next;
            dequeue(first);
            return first;
        }
    };

    /* Allocates queue entries and performs reference counting as needed. */
    class Allocator {
    public:
        Allocator();

        ConfigurationChangedEntry* obtainConfigurationChangedEntry(nsecs_t eventTime);
        KeyEntry* obtainKeyEntry(nsecs_t eventTime,
                int32_t deviceId, int32_t nature, uint32_t policyFlags, int32_t action,
                int32_t flags, int32_t keyCode, int32_t scanCode, int32_t metaState,
                int32_t repeatCount, nsecs_t downTime);
        MotionEntry* obtainMotionEntry(nsecs_t eventTime,
                int32_t deviceId, int32_t nature, uint32_t policyFlags, int32_t action,
                int32_t metaState, int32_t edgeFlags, float xPrecision, float yPrecision,
                nsecs_t downTime, uint32_t pointerCount,
                const int32_t* pointerIds, const PointerCoords* pointerCoords);
        DispatchEntry* obtainDispatchEntry(EventEntry* eventEntry);
        CommandEntry* obtainCommandEntry(Command command);

        void releaseEventEntry(EventEntry* entry);
        void releaseConfigurationChangedEntry(ConfigurationChangedEntry* entry);
        void releaseKeyEntry(KeyEntry* entry);
        void releaseMotionEntry(MotionEntry* entry);
        void releaseDispatchEntry(DispatchEntry* entry);
        void releaseCommandEntry(CommandEntry* entry);

        void appendMotionSample(MotionEntry* motionEntry,
                nsecs_t eventTime, const PointerCoords* pointerCoords);

    private:
        Pool<ConfigurationChangedEntry> mConfigurationChangeEntryPool;
        Pool<KeyEntry> mKeyEntryPool;
        Pool<MotionEntry> mMotionEntryPool;
        Pool<MotionSample> mMotionSamplePool;
        Pool<DispatchEntry> mDispatchEntryPool;
        Pool<CommandEntry> mCommandEntryPool;

        void initializeEventEntry(EventEntry* entry, int32_t type, nsecs_t eventTime);
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
            // The client is not responding.
            STATUS_NOT_RESPONDING,
            // The input channel has been unregistered.
            STATUS_ZOMBIE
        };

        Status status;
        sp<InputChannel> inputChannel;
        InputPublisher inputPublisher;
        Queue<DispatchEntry> outboundQueue;
        nsecs_t nextTimeoutTime; // next timeout time (LONG_LONG_MAX if none)

        nsecs_t lastEventTime; // the time when the event was originally captured
        nsecs_t lastDispatchTime; // the time when the last event was dispatched
        nsecs_t lastANRTime; // the time when the last ANR was recorded

        explicit Connection(const sp<InputChannel>& inputChannel);

        inline const char* getInputChannelName() const { return inputChannel->getName().string(); }

        const char* getStatusLabel() const;

        // Finds a DispatchEntry in the outbound queue associated with the specified event.
        // Returns NULL if not found.
        DispatchEntry* findQueuedDispatchEntryForEvent(const EventEntry* eventEntry) const;

        // Determine whether this connection has a pending synchronous dispatch target.
        // Since there can only ever be at most one such target at a time, if there is one,
        // it must be at the tail because nothing else can be enqueued after it.
        inline bool hasPendingSyncTarget() {
            return ! outboundQueue.isEmpty()
                    && (outboundQueue.tail.prev->targetFlags & InputTarget::FLAG_SYNC);
        }

        // Gets the time since the current event was originally obtained from the input driver.
        inline double getEventLatencyMillis(nsecs_t currentTime) {
            return (currentTime - lastEventTime) / 1000000.0;
        }

        // Gets the time since the current event entered the outbound dispatch queue.
        inline double getDispatchLatencyMillis(nsecs_t currentTime) {
            return (currentTime - lastDispatchTime) / 1000000.0;
        }

        // Gets the time since the current event ANR was declared, if applicable.
        inline double getANRLatencyMillis(nsecs_t currentTime) {
            return (currentTime - lastANRTime) / 1000000.0;
        }

        status_t initialize();

        void setNextTimeoutTime(nsecs_t currentTime, nsecs_t timeout);
    };

    sp<InputDispatcherPolicyInterface> mPolicy;

    Mutex mLock;

    Allocator mAllocator;
    sp<PollLoop> mPollLoop;

    Queue<EventEntry> mInboundQueue;
    Queue<CommandEntry> mCommandQueue;

    // All registered connections mapped by receive pipe file descriptor.
    KeyedVector<int, sp<Connection> > mConnectionsByReceiveFd;

    // Active connections are connections that have a non-empty outbound queue.
    // We don't use a ref-counted pointer here because we explicitly abort connections
    // during unregistration which causes the connection's outbound queue to be cleared
    // and the connection itself to be deactivated.
    Vector<Connection*> mActiveConnections;

    // List of connections that have timed out.  Only used by dispatchOnce()
    // We don't use a ref-counted pointer here because it is not possible for a connection
    // to be unregistered while processing timed out connections since we hold the lock for
    // the duration.
    Vector<Connection*> mTimedOutConnections;

    // Preallocated key and motion event objects used only to ask the input dispatcher policy
    // for the targets of an event that is to be dispatched.
    KeyEvent mReusableKeyEvent;
    MotionEvent mReusableMotionEvent;

    // The input targets that were most recently identified for dispatch.
    // If there is a synchronous event dispatch in progress, the current input targets will
    // remain unchanged until the dispatch has completed or been aborted.
    Vector<InputTarget> mCurrentInputTargets;
    bool mCurrentInputTargetsValid; // false while targets are being recomputed

    // Event injection and synchronization.
    Condition mInjectionResultAvailableCondition;
    Condition mFullySynchronizedCondition;
    bool isFullySynchronizedLocked();
    EventEntry* createEntryFromInputEventLocked(const InputEvent* event);
    void setInjectionResultLocked(EventEntry* entry, int32_t injectionResult);

    // Key repeat tracking.
    // XXX Move this up to the input reader instead.
    struct KeyRepeatState {
        KeyEntry* lastKeyEntry; // or null if no repeat
        nsecs_t nextRepeatTime;
    } mKeyRepeatState;

    void resetKeyRepeatLocked();

    // Deferred command processing.
    bool runCommandsLockedInterruptible();
    CommandEntry* postCommandLocked(Command command);

    // Process events that have just been dequeued from the head of the input queue.
    void processConfigurationChangedLockedInterruptible(
            nsecs_t currentTime, ConfigurationChangedEntry* entry);
    void processKeyLockedInterruptible(
            nsecs_t currentTime, KeyEntry* entry, nsecs_t keyRepeatTimeout);
    void processKeyRepeatLockedInterruptible(
            nsecs_t currentTime, nsecs_t keyRepeatTimeout);
    void processMotionLockedInterruptible(
            nsecs_t currentTime, MotionEntry* entry);

    // Identify input targets for an event and dispatch to them.
    void identifyInputTargetsAndDispatchKeyLockedInterruptible(
            nsecs_t currentTime, KeyEntry* entry);
    void identifyInputTargetsAndDispatchMotionLockedInterruptible(
            nsecs_t currentTime, MotionEntry* entry);
    void dispatchEventToCurrentInputTargetsLocked(
            nsecs_t currentTime, EventEntry* entry, bool resumeWithAppendedMotionSample);

    // Manage the dispatch cycle for a single connection.
    // These methods are deliberately not Interruptible because doing all of the work
    // with the mutex held makes it easier to ensure that connection invariants are maintained.
    // If needed, the methods post commands to run later once the critical bits are done.
    void prepareDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection,
            EventEntry* eventEntry, const InputTarget* inputTarget,
            bool resumeWithAppendedMotionSample);
    void startDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void finishDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void timeoutDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection);
    void resumeAfterTimeoutDispatchCycleLocked(nsecs_t currentTime,
            const sp<Connection>& connection, nsecs_t newTimeout);
    void abortDispatchCycleLocked(nsecs_t currentTime, const sp<Connection>& connection,
            bool broken);
    static bool handleReceiveCallback(int receiveFd, int events, void* data);

    // Add or remove a connection to the mActiveConnections vector.
    void activateConnectionLocked(Connection* connection);
    void deactivateConnectionLocked(Connection* connection);

    // Interesting events that we might like to log or tell the framework about.
    void onDispatchCycleStartedLocked(
            nsecs_t currentTime, const sp<Connection>& connection);
    void onDispatchCycleFinishedLocked(
            nsecs_t currentTime, const sp<Connection>& connection, bool recoveredFromANR);
    void onDispatchCycleANRLocked(
            nsecs_t currentTime, const sp<Connection>& connection);
    void onDispatchCycleBrokenLocked(
            nsecs_t currentTime, const sp<Connection>& connection);

    // Outbound policy interactions.
    void doNotifyInputChannelBrokenLockedInterruptible(CommandEntry* commandEntry);
    void doNotifyInputChannelANRLockedInterruptible(CommandEntry* commandEntry);
    void doNotifyInputChannelRecoveredFromANRLockedInterruptible(CommandEntry* commandEntry);
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

#endif // _UI_INPUT_DISPATCHER_PRIV_H
