//
// Copyright 2010 The Android Open Source Project
//
// The input dispatcher.
//
#define LOG_TAG "InputDispatcher"

//#define LOG_NDEBUG 0

// Log detailed debug messages about each inbound event notification to the dispatcher.
#define DEBUG_INBOUND_EVENT_DETAILS 0

// Log detailed debug messages about each outbound event processed by the dispatcher.
#define DEBUG_OUTBOUND_EVENT_DETAILS 0

// Log debug messages about batching.
#define DEBUG_BATCHING 0

// Log debug messages about the dispatch cycle.
#define DEBUG_DISPATCH_CYCLE 0

// Log debug messages about registrations.
#define DEBUG_REGISTRATION 0

// Log debug messages about performance statistics.
#define DEBUG_PERFORMANCE_STATISTICS 0

// Log debug messages about input event injection.
#define DEBUG_INJECTION 0

// Log debug messages about input event throttling.
#define DEBUG_THROTTLING 0

// Log debug messages about input focus tracking.
#define DEBUG_FOCUS 0

// Log debug messages about the app switch latency optimization.
#define DEBUG_APP_SWITCH 0

#include <android/input.h>
#include <cutils/log.h>
#include <ui/Input.h>
#include <ui/InputDispatcher.h>
#include <ui/PowerManager.h>

#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>

#define INDENT "  "
#define INDENT2 "    "

namespace android {

// Delay before reporting long touch events to the power manager.
const nsecs_t LONG_TOUCH_DELAY = 300 * 1000000LL; // 300 ms

// Default input dispatching timeout if there is no focused application or paused window
// from which to determine an appropriate dispatching timeout.
const nsecs_t DEFAULT_INPUT_DISPATCHING_TIMEOUT = 5000 * 1000000LL; // 5 sec

// Amount of time to allow for all pending events to be processed when an app switch
// key is on the way.  This is used to preempt input dispatch and drop input events
// when an application takes too long to respond and the user has pressed an app switch key.
const nsecs_t APP_SWITCH_TIMEOUT = 500 * 1000000LL; // 0.5sec


static inline nsecs_t now() {
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

static inline const char* toString(bool value) {
    return value ? "true" : "false";
}

static inline int32_t getMotionEventActionPointerIndex(int32_t action) {
    return (action & AMOTION_EVENT_ACTION_POINTER_INDEX_MASK)
            >> AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT;
}

static bool isValidKeyAction(int32_t action) {
    switch (action) {
    case AKEY_EVENT_ACTION_DOWN:
    case AKEY_EVENT_ACTION_UP:
        return true;
    default:
        return false;
    }
}

static bool validateKeyEvent(int32_t action) {
    if (! isValidKeyAction(action)) {
        LOGE("Key event has invalid action code 0x%x", action);
        return false;
    }
    return true;
}

static bool isValidMotionAction(int32_t action, size_t pointerCount) {
    switch (action & AMOTION_EVENT_ACTION_MASK) {
    case AMOTION_EVENT_ACTION_DOWN:
    case AMOTION_EVENT_ACTION_UP:
    case AMOTION_EVENT_ACTION_CANCEL:
    case AMOTION_EVENT_ACTION_MOVE:
    case AMOTION_EVENT_ACTION_OUTSIDE:
        return true;
    case AMOTION_EVENT_ACTION_POINTER_DOWN:
    case AMOTION_EVENT_ACTION_POINTER_UP: {
        int32_t index = getMotionEventActionPointerIndex(action);
        return index >= 0 && size_t(index) < pointerCount;
    }
    default:
        return false;
    }
}

static bool validateMotionEvent(int32_t action, size_t pointerCount,
        const int32_t* pointerIds) {
    if (! isValidMotionAction(action, pointerCount)) {
        LOGE("Motion event has invalid action code 0x%x", action);
        return false;
    }
    if (pointerCount < 1 || pointerCount > MAX_POINTERS) {
        LOGE("Motion event has invalid pointer count %d; value must be between 1 and %d.",
                pointerCount, MAX_POINTERS);
        return false;
    }
    BitSet32 pointerIdBits;
    for (size_t i = 0; i < pointerCount; i++) {
        int32_t id = pointerIds[i];
        if (id < 0 || id > MAX_POINTER_ID) {
            LOGE("Motion event has invalid pointer id %d; value must be between 0 and %d",
                    id, MAX_POINTER_ID);
            return false;
        }
        if (pointerIdBits.hasBit(id)) {
            LOGE("Motion event has duplicate pointer id %d", id);
            return false;
        }
        pointerIdBits.markBit(id);
    }
    return true;
}


// --- InputWindow ---

bool InputWindow::touchableAreaContainsPoint(int32_t x, int32_t y) const {
    return x >= touchableAreaLeft && x <= touchableAreaRight
            && y >= touchableAreaTop && y <= touchableAreaBottom;
}

bool InputWindow::frameContainsPoint(int32_t x, int32_t y) const {
    return x >= frameLeft && x <= frameRight
            && y >= frameTop && y <= frameBottom;
}

bool InputWindow::isTrustedOverlay() const {
    return layoutParamsType == TYPE_INPUT_METHOD
            || layoutParamsType == TYPE_INPUT_METHOD_DIALOG
            || layoutParamsType == TYPE_SECURE_SYSTEM_OVERLAY;
}


// --- InputDispatcher ---

InputDispatcher::InputDispatcher(const sp<InputDispatcherPolicyInterface>& policy) :
    mPolicy(policy),
    mPendingEvent(NULL), mAppSwitchDueTime(LONG_LONG_MAX),
    mDispatchEnabled(true), mDispatchFrozen(false),
    mFocusedWindow(NULL),
    mFocusedApplication(NULL),
    mCurrentInputTargetsValid(false),
    mInputTargetWaitCause(INPUT_TARGET_WAIT_CAUSE_NONE) {
    mLooper = new Looper(false);

    mInboundQueue.headSentinel.refCount = -1;
    mInboundQueue.headSentinel.type = EventEntry::TYPE_SENTINEL;
    mInboundQueue.headSentinel.eventTime = LONG_LONG_MIN;

    mInboundQueue.tailSentinel.refCount = -1;
    mInboundQueue.tailSentinel.type = EventEntry::TYPE_SENTINEL;
    mInboundQueue.tailSentinel.eventTime = LONG_LONG_MAX;

    mKeyRepeatState.lastKeyEntry = NULL;

    int32_t maxEventsPerSecond = policy->getMaxEventsPerSecond();
    mThrottleState.minTimeBetweenEvents = 1000000000LL / maxEventsPerSecond;
    mThrottleState.lastDeviceId = -1;

#if DEBUG_THROTTLING
    mThrottleState.originalSampleCount = 0;
    LOGD("Throttling - Max events per second = %d", maxEventsPerSecond);
#endif
}

InputDispatcher::~InputDispatcher() {
    { // acquire lock
        AutoMutex _l(mLock);

        resetKeyRepeatLocked();
        releasePendingEventLocked();
        drainInboundQueueLocked();
    }

    while (mConnectionsByReceiveFd.size() != 0) {
        unregisterInputChannel(mConnectionsByReceiveFd.valueAt(0)->inputChannel);
    }
}

void InputDispatcher::dispatchOnce() {
    nsecs_t keyRepeatTimeout = mPolicy->getKeyRepeatTimeout();
    nsecs_t keyRepeatDelay = mPolicy->getKeyRepeatDelay();

    nsecs_t nextWakeupTime = LONG_LONG_MAX;
    { // acquire lock
        AutoMutex _l(mLock);
        dispatchOnceInnerLocked(keyRepeatTimeout, keyRepeatDelay, & nextWakeupTime);

        if (runCommandsLockedInterruptible()) {
            nextWakeupTime = LONG_LONG_MIN;  // force next poll to wake up immediately
        }
    } // release lock

    // Wait for callback or timeout or wake.  (make sure we round up, not down)
    nsecs_t currentTime = now();
    int32_t timeoutMillis;
    if (nextWakeupTime > currentTime) {
        uint64_t timeout = uint64_t(nextWakeupTime - currentTime);
        timeout = (timeout + 999999LL) / 1000000LL;
        timeoutMillis = timeout > INT_MAX ? -1 : int32_t(timeout);
    } else {
        timeoutMillis = 0;
    }

    mLooper->pollOnce(timeoutMillis);
}

void InputDispatcher::dispatchOnceInnerLocked(nsecs_t keyRepeatTimeout,
        nsecs_t keyRepeatDelay, nsecs_t* nextWakeupTime) {
    nsecs_t currentTime = now();

    // Reset the key repeat timer whenever we disallow key events, even if the next event
    // is not a key.  This is to ensure that we abort a key repeat if the device is just coming
    // out of sleep.
    if (keyRepeatTimeout < 0) {
        resetKeyRepeatLocked();
    }

    // If dispatching is frozen, do not process timeouts or try to deliver any new events.
    if (mDispatchFrozen) {
#if DEBUG_FOCUS
        LOGD("Dispatch frozen.  Waiting some more.");
#endif
        return;
    }

    // Optimize latency of app switches.
    // Essentially we start a short timeout when an app switch key (HOME / ENDCALL) has
    // been pressed.  When it expires, we preempt dispatch and drop all other pending events.
    bool isAppSwitchDue = mAppSwitchDueTime <= currentTime;
    if (mAppSwitchDueTime < *nextWakeupTime) {
        *nextWakeupTime = mAppSwitchDueTime;
    }

    // Ready to start a new event.
    // If we don't already have a pending event, go grab one.
    if (! mPendingEvent) {
        if (mInboundQueue.isEmpty()) {
            if (isAppSwitchDue) {
                // The inbound queue is empty so the app switch key we were waiting
                // for will never arrive.  Stop waiting for it.
                resetPendingAppSwitchLocked(false);
                isAppSwitchDue = false;
            }

            // Synthesize a key repeat if appropriate.
            if (mKeyRepeatState.lastKeyEntry) {
                if (currentTime >= mKeyRepeatState.nextRepeatTime) {
                    mPendingEvent = synthesizeKeyRepeatLocked(currentTime, keyRepeatDelay);
                } else {
                    if (mKeyRepeatState.nextRepeatTime < *nextWakeupTime) {
                        *nextWakeupTime = mKeyRepeatState.nextRepeatTime;
                    }
                }
            }
            if (! mPendingEvent) {
                return;
            }
        } else {
            // Inbound queue has at least one entry.
            EventEntry* entry = mInboundQueue.headSentinel.next;

            // Throttle the entry if it is a move event and there are no
            // other events behind it in the queue.  Due to movement batching, additional
            // samples may be appended to this event by the time the throttling timeout
            // expires.
            // TODO Make this smarter and consider throttling per device independently.
            if (entry->type == EventEntry::TYPE_MOTION
                    && !isAppSwitchDue
                    && mDispatchEnabled
                    && (entry->policyFlags & POLICY_FLAG_PASS_TO_USER)
                    && !entry->isInjected()) {
                MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
                int32_t deviceId = motionEntry->deviceId;
                uint32_t source = motionEntry->source;
                if (! isAppSwitchDue
                        && motionEntry->next == & mInboundQueue.tailSentinel // exactly one event
                        && motionEntry->action == AMOTION_EVENT_ACTION_MOVE
                        && deviceId == mThrottleState.lastDeviceId
                        && source == mThrottleState.lastSource) {
                    nsecs_t nextTime = mThrottleState.lastEventTime
                            + mThrottleState.minTimeBetweenEvents;
                    if (currentTime < nextTime) {
                        // Throttle it!
#if DEBUG_THROTTLING
                        LOGD("Throttling - Delaying motion event for "
                                "device 0x%x, source 0x%08x by up to %0.3fms.",
                                deviceId, source, (nextTime - currentTime) * 0.000001);
#endif
                        if (nextTime < *nextWakeupTime) {
                            *nextWakeupTime = nextTime;
                        }
                        if (mThrottleState.originalSampleCount == 0) {
                            mThrottleState.originalSampleCount =
                                    motionEntry->countSamples();
                        }
                        return;
                    }
                }

#if DEBUG_THROTTLING
                if (mThrottleState.originalSampleCount != 0) {
                    uint32_t count = motionEntry->countSamples();
                    LOGD("Throttling - Motion event sample count grew by %d from %d to %d.",
                            count - mThrottleState.originalSampleCount,
                            mThrottleState.originalSampleCount, count);
                    mThrottleState.originalSampleCount = 0;
                }
#endif

                mThrottleState.lastEventTime = entry->eventTime < currentTime
                        ? entry->eventTime : currentTime;
                mThrottleState.lastDeviceId = deviceId;
                mThrottleState.lastSource = source;
            }

            mInboundQueue.dequeue(entry);
            mPendingEvent = entry;
        }

        // Poke user activity for this event.
        if (mPendingEvent->policyFlags & POLICY_FLAG_PASS_TO_USER) {
            pokeUserActivityLocked(mPendingEvent);
        }
    }

    // Now we have an event to dispatch.
    assert(mPendingEvent != NULL);
    bool done = false;
    DropReason dropReason = DROP_REASON_NOT_DROPPED;
    if (!(mPendingEvent->policyFlags & POLICY_FLAG_PASS_TO_USER)) {
        dropReason = DROP_REASON_POLICY;
    } else if (!mDispatchEnabled) {
        dropReason = DROP_REASON_DISABLED;
    }
    switch (mPendingEvent->type) {
    case EventEntry::TYPE_CONFIGURATION_CHANGED: {
        ConfigurationChangedEntry* typedEntry =
                static_cast<ConfigurationChangedEntry*>(mPendingEvent);
        done = dispatchConfigurationChangedLocked(currentTime, typedEntry);
        dropReason = DROP_REASON_NOT_DROPPED; // configuration changes are never dropped
        break;
    }

    case EventEntry::TYPE_KEY: {
        KeyEntry* typedEntry = static_cast<KeyEntry*>(mPendingEvent);
        if (isAppSwitchDue) {
            if (isAppSwitchKeyEventLocked(typedEntry)) {
                resetPendingAppSwitchLocked(true);
                isAppSwitchDue = false;
            } else if (dropReason == DROP_REASON_NOT_DROPPED) {
                dropReason = DROP_REASON_APP_SWITCH;
            }
        }
        done = dispatchKeyLocked(currentTime, typedEntry, keyRepeatTimeout,
                &dropReason, nextWakeupTime);
        break;
    }

    case EventEntry::TYPE_MOTION: {
        MotionEntry* typedEntry = static_cast<MotionEntry*>(mPendingEvent);
        if (dropReason == DROP_REASON_NOT_DROPPED && isAppSwitchDue) {
            dropReason = DROP_REASON_APP_SWITCH;
        }
        done = dispatchMotionLocked(currentTime, typedEntry,
                &dropReason, nextWakeupTime);
        break;
    }

    default:
        assert(false);
        break;
    }

    if (done) {
        if (dropReason != DROP_REASON_NOT_DROPPED) {
            dropInboundEventLocked(mPendingEvent, dropReason);
        }

        releasePendingEventLocked();
        *nextWakeupTime = LONG_LONG_MIN;  // force next poll to wake up immediately
    }
}

bool InputDispatcher::enqueueInboundEventLocked(EventEntry* entry) {
    bool needWake = mInboundQueue.isEmpty();
    mInboundQueue.enqueueAtTail(entry);

    switch (entry->type) {
    case EventEntry::TYPE_KEY: {
        KeyEntry* keyEntry = static_cast<KeyEntry*>(entry);
        if (isAppSwitchKeyEventLocked(keyEntry)) {
            if (keyEntry->action == AKEY_EVENT_ACTION_DOWN) {
                mAppSwitchSawKeyDown = true;
            } else if (keyEntry->action == AKEY_EVENT_ACTION_UP) {
                if (mAppSwitchSawKeyDown) {
#if DEBUG_APP_SWITCH
                    LOGD("App switch is pending!");
#endif
                    mAppSwitchDueTime = keyEntry->eventTime + APP_SWITCH_TIMEOUT;
                    mAppSwitchSawKeyDown = false;
                    needWake = true;
                }
            }
        }
        break;
    }
    }

    return needWake;
}

void InputDispatcher::dropInboundEventLocked(EventEntry* entry, DropReason dropReason) {
    const char* reason;
    switch (dropReason) {
    case DROP_REASON_POLICY:
#if DEBUG_INBOUND_EVENT_DETAILS
        LOGD("Dropped event because policy consumed it.");
#endif
        reason = "inbound event was dropped because the policy consumed it";
        break;
    case DROP_REASON_DISABLED:
        LOGI("Dropped event because input dispatch is disabled.");
        reason = "inbound event was dropped because input dispatch is disabled";
        break;
    case DROP_REASON_APP_SWITCH:
        LOGI("Dropped event because of pending overdue app switch.");
        reason = "inbound event was dropped because of pending overdue app switch";
        break;
    default:
        assert(false);
        return;
    }

    switch (entry->type) {
    case EventEntry::TYPE_KEY:
        synthesizeCancelationEventsForAllConnectionsLocked(
                InputState::CANCEL_NON_POINTER_EVENTS, reason);
        break;
    case EventEntry::TYPE_MOTION: {
        MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
        if (motionEntry->source & AINPUT_SOURCE_CLASS_POINTER) {
            synthesizeCancelationEventsForAllConnectionsLocked(
                    InputState::CANCEL_POINTER_EVENTS, reason);
        } else {
            synthesizeCancelationEventsForAllConnectionsLocked(
                    InputState::CANCEL_NON_POINTER_EVENTS, reason);
        }
        break;
    }
    }
}

bool InputDispatcher::isAppSwitchKeyCode(int32_t keyCode) {
    return keyCode == AKEYCODE_HOME || keyCode == AKEYCODE_ENDCALL;
}

bool InputDispatcher::isAppSwitchKeyEventLocked(KeyEntry* keyEntry) {
    return ! (keyEntry->flags & AKEY_EVENT_FLAG_CANCELED)
            && isAppSwitchKeyCode(keyEntry->keyCode)
            && (keyEntry->policyFlags & POLICY_FLAG_TRUSTED)
            && (keyEntry->policyFlags & POLICY_FLAG_PASS_TO_USER);
}

bool InputDispatcher::isAppSwitchPendingLocked() {
    return mAppSwitchDueTime != LONG_LONG_MAX;
}

void InputDispatcher::resetPendingAppSwitchLocked(bool handled) {
    mAppSwitchDueTime = LONG_LONG_MAX;

#if DEBUG_APP_SWITCH
    if (handled) {
        LOGD("App switch has arrived.");
    } else {
        LOGD("App switch was abandoned.");
    }
#endif
}

bool InputDispatcher::runCommandsLockedInterruptible() {
    if (mCommandQueue.isEmpty()) {
        return false;
    }

    do {
        CommandEntry* commandEntry = mCommandQueue.dequeueAtHead();

        Command command = commandEntry->command;
        (this->*command)(commandEntry); // commands are implicitly 'LockedInterruptible'

        commandEntry->connection.clear();
        mAllocator.releaseCommandEntry(commandEntry);
    } while (! mCommandQueue.isEmpty());
    return true;
}

InputDispatcher::CommandEntry* InputDispatcher::postCommandLocked(Command command) {
    CommandEntry* commandEntry = mAllocator.obtainCommandEntry(command);
    mCommandQueue.enqueueAtTail(commandEntry);
    return commandEntry;
}

void InputDispatcher::drainInboundQueueLocked() {
    while (! mInboundQueue.isEmpty()) {
        EventEntry* entry = mInboundQueue.dequeueAtHead();
        releaseInboundEventLocked(entry);
    }
}

void InputDispatcher::releasePendingEventLocked() {
    if (mPendingEvent) {
        releaseInboundEventLocked(mPendingEvent);
        mPendingEvent = NULL;
    }
}

void InputDispatcher::releaseInboundEventLocked(EventEntry* entry) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState && injectionState->injectionResult == INPUT_EVENT_INJECTION_PENDING) {
#if DEBUG_DISPATCH_CYCLE
        LOGD("Injected inbound event was dropped.");
#endif
        setInjectionResultLocked(entry, INPUT_EVENT_INJECTION_FAILED);
    }
    mAllocator.releaseEventEntry(entry);
}

void InputDispatcher::resetKeyRepeatLocked() {
    if (mKeyRepeatState.lastKeyEntry) {
        mAllocator.releaseKeyEntry(mKeyRepeatState.lastKeyEntry);
        mKeyRepeatState.lastKeyEntry = NULL;
    }
}

InputDispatcher::KeyEntry* InputDispatcher::synthesizeKeyRepeatLocked(
        nsecs_t currentTime, nsecs_t keyRepeatDelay) {
    KeyEntry* entry = mKeyRepeatState.lastKeyEntry;

    // Reuse the repeated key entry if it is otherwise unreferenced.
    uint32_t policyFlags = (entry->policyFlags & POLICY_FLAG_RAW_MASK)
            | POLICY_FLAG_PASS_TO_USER | POLICY_FLAG_TRUSTED;
    if (entry->refCount == 1) {
        mAllocator.recycleKeyEntry(entry);
        entry->eventTime = currentTime;
        entry->policyFlags = policyFlags;
        entry->repeatCount += 1;
    } else {
        KeyEntry* newEntry = mAllocator.obtainKeyEntry(currentTime,
                entry->deviceId, entry->source, policyFlags,
                entry->action, entry->flags, entry->keyCode, entry->scanCode,
                entry->metaState, entry->repeatCount + 1, entry->downTime);

        mKeyRepeatState.lastKeyEntry = newEntry;
        mAllocator.releaseKeyEntry(entry);

        entry = newEntry;
    }
    entry->syntheticRepeat = true;

    // Increment reference count since we keep a reference to the event in
    // mKeyRepeatState.lastKeyEntry in addition to the one we return.
    entry->refCount += 1;

    if (entry->repeatCount == 1) {
        entry->flags |= AKEY_EVENT_FLAG_LONG_PRESS;
    }

    mKeyRepeatState.nextRepeatTime = currentTime + keyRepeatDelay;
    return entry;
}

bool InputDispatcher::dispatchConfigurationChangedLocked(
        nsecs_t currentTime, ConfigurationChangedEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("dispatchConfigurationChanged - eventTime=%lld", entry->eventTime);
#endif

    // Reset key repeating in case a keyboard device was added or removed or something.
    resetKeyRepeatLocked();

    // Enqueue a command to run outside the lock to tell the policy that the configuration changed.
    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyConfigurationChangedInterruptible);
    commandEntry->eventTime = entry->eventTime;
    return true;
}

bool InputDispatcher::dispatchKeyLocked(
        nsecs_t currentTime, KeyEntry* entry, nsecs_t keyRepeatTimeout,
        DropReason* dropReason, nsecs_t* nextWakeupTime) {
    // Preprocessing.
    if (! entry->dispatchInProgress) {
        if (entry->repeatCount == 0
                && entry->action == AKEY_EVENT_ACTION_DOWN
                && (entry->policyFlags & POLICY_FLAG_TRUSTED)
                && !entry->isInjected()) {
            if (mKeyRepeatState.lastKeyEntry
                    && mKeyRepeatState.lastKeyEntry->keyCode == entry->keyCode) {
                // We have seen two identical key downs in a row which indicates that the device
                // driver is automatically generating key repeats itself.  We take note of the
                // repeat here, but we disable our own next key repeat timer since it is clear that
                // we will not need to synthesize key repeats ourselves.
                entry->repeatCount = mKeyRepeatState.lastKeyEntry->repeatCount + 1;
                resetKeyRepeatLocked();
                mKeyRepeatState.nextRepeatTime = LONG_LONG_MAX; // don't generate repeats ourselves
            } else {
                // Not a repeat.  Save key down state in case we do see a repeat later.
                resetKeyRepeatLocked();
                mKeyRepeatState.nextRepeatTime = entry->eventTime + keyRepeatTimeout;
            }
            mKeyRepeatState.lastKeyEntry = entry;
            entry->refCount += 1;
        } else if (! entry->syntheticRepeat) {
            resetKeyRepeatLocked();
        }

        entry->dispatchInProgress = true;
        resetTargetsLocked();

        logOutboundKeyDetailsLocked("dispatchKey - ", entry);
    }

    // Give the policy a chance to intercept the key.
    if (entry->interceptKeyResult == KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN) {
        if (entry->policyFlags & POLICY_FLAG_PASS_TO_USER) {
            CommandEntry* commandEntry = postCommandLocked(
                    & InputDispatcher::doInterceptKeyBeforeDispatchingLockedInterruptible);
            if (mFocusedWindow) {
                commandEntry->inputChannel = mFocusedWindow->inputChannel;
            }
            commandEntry->keyEntry = entry;
            entry->refCount += 1;
            return false; // wait for the command to run
        } else {
            entry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_CONTINUE;
        }
    } else if (entry->interceptKeyResult == KeyEntry::INTERCEPT_KEY_RESULT_SKIP) {
        if (*dropReason == DROP_REASON_NOT_DROPPED) {
            *dropReason = DROP_REASON_POLICY;
        }
    }

    // Clean up if dropping the event.
    if (*dropReason != DROP_REASON_NOT_DROPPED) {
        resetTargetsLocked();
        setInjectionResultLocked(entry, *dropReason == DROP_REASON_POLICY
                ? INPUT_EVENT_INJECTION_SUCCEEDED : INPUT_EVENT_INJECTION_FAILED);
        return true;
    }

    // Identify targets.
    if (! mCurrentInputTargetsValid) {
        int32_t injectionResult = findFocusedWindowTargetsLocked(currentTime,
                entry, nextWakeupTime);
        if (injectionResult == INPUT_EVENT_INJECTION_PENDING) {
            return false;
        }

        setInjectionResultLocked(entry, injectionResult);
        if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
            return true;
        }

        addMonitoringTargetsLocked();
        commitTargetsLocked();
    }

    // Dispatch the key.
    dispatchEventToCurrentInputTargetsLocked(currentTime, entry, false);
    return true;
}

void InputDispatcher::logOutboundKeyDetailsLocked(const char* prefix, const KeyEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("%seventTime=%lld, deviceId=0x%x, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, keyCode=0x%x, scanCode=0x%x, metaState=0x%x, "
            "repeatCount=%d, downTime=%lld",
            prefix,
            entry->eventTime, entry->deviceId, entry->source, entry->policyFlags,
            entry->action, entry->flags, entry->keyCode, entry->scanCode, entry->metaState,
            entry->repeatCount, entry->downTime);
#endif
}

bool InputDispatcher::dispatchMotionLocked(
        nsecs_t currentTime, MotionEntry* entry, DropReason* dropReason, nsecs_t* nextWakeupTime) {
    // Preprocessing.
    if (! entry->dispatchInProgress) {
        entry->dispatchInProgress = true;
        resetTargetsLocked();

        logOutboundMotionDetailsLocked("dispatchMotion - ", entry);
    }

    // Clean up if dropping the event.
    if (*dropReason != DROP_REASON_NOT_DROPPED) {
        resetTargetsLocked();
        setInjectionResultLocked(entry, *dropReason == DROP_REASON_POLICY
                ? INPUT_EVENT_INJECTION_SUCCEEDED : INPUT_EVENT_INJECTION_FAILED);
        return true;
    }

    bool isPointerEvent = entry->source & AINPUT_SOURCE_CLASS_POINTER;

    // Identify targets.
    if (! mCurrentInputTargetsValid) {
        int32_t injectionResult;
        if (isPointerEvent) {
            // Pointer event.  (eg. touchscreen)
            injectionResult = findTouchedWindowTargetsLocked(currentTime,
                    entry, nextWakeupTime);
        } else {
            // Non touch event.  (eg. trackball)
            injectionResult = findFocusedWindowTargetsLocked(currentTime,
                    entry, nextWakeupTime);
        }
        if (injectionResult == INPUT_EVENT_INJECTION_PENDING) {
            return false;
        }

        setInjectionResultLocked(entry, injectionResult);
        if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
            return true;
        }

        addMonitoringTargetsLocked();
        commitTargetsLocked();
    }

    // Dispatch the motion.
    dispatchEventToCurrentInputTargetsLocked(currentTime, entry, false);
    return true;
}


void InputDispatcher::logOutboundMotionDetailsLocked(const char* prefix, const MotionEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("%seventTime=%lld, deviceId=0x%x, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, "
            "metaState=0x%x, edgeFlags=0x%x, xPrecision=%f, yPrecision=%f, downTime=%lld",
            prefix,
            entry->eventTime, entry->deviceId, entry->source, entry->policyFlags,
            entry->action, entry->flags,
            entry->metaState, entry->edgeFlags, entry->xPrecision, entry->yPrecision,
            entry->downTime);

    // Print the most recent sample that we have available, this may change due to batching.
    size_t sampleCount = 1;
    const MotionSample* sample = & entry->firstSample;
    for (; sample->next != NULL; sample = sample->next) {
        sampleCount += 1;
    }
    for (uint32_t i = 0; i < entry->pointerCount; i++) {
        LOGD("  Pointer %d: id=%d, x=%f, y=%f, pressure=%f, size=%f, "
                "touchMajor=%f, touchMinor=%f, toolMajor=%f, toolMinor=%f, "
                "orientation=%f",
                i, entry->pointerIds[i],
                sample->pointerCoords[i].x, sample->pointerCoords[i].y,
                sample->pointerCoords[i].pressure, sample->pointerCoords[i].size,
                sample->pointerCoords[i].touchMajor, sample->pointerCoords[i].touchMinor,
                sample->pointerCoords[i].toolMajor, sample->pointerCoords[i].toolMinor,
                sample->pointerCoords[i].orientation);
    }

    // Keep in mind that due to batching, it is possible for the number of samples actually
    // dispatched to change before the application finally consumed them.
    if (entry->action == AMOTION_EVENT_ACTION_MOVE) {
        LOGD("  ... Total movement samples currently batched %d ...", sampleCount);
    }
#endif
}

void InputDispatcher::dispatchEventToCurrentInputTargetsLocked(nsecs_t currentTime,
        EventEntry* eventEntry, bool resumeWithAppendedMotionSample) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("dispatchEventToCurrentInputTargets - "
            "resumeWithAppendedMotionSample=%s",
            toString(resumeWithAppendedMotionSample));
#endif

    assert(eventEntry->dispatchInProgress); // should already have been set to true

    pokeUserActivityLocked(eventEntry);

    for (size_t i = 0; i < mCurrentInputTargets.size(); i++) {
        const InputTarget& inputTarget = mCurrentInputTargets.itemAt(i);

        ssize_t connectionIndex = getConnectionIndexLocked(inputTarget.inputChannel);
        if (connectionIndex >= 0) {
            sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
            prepareDispatchCycleLocked(currentTime, connection, eventEntry, & inputTarget,
                    resumeWithAppendedMotionSample);
        } else {
#if DEBUG_FOCUS
            LOGD("Dropping event delivery to target with channel '%s' because it "
                    "is no longer registered with the input dispatcher.",
                    inputTarget.inputChannel->getName().string());
#endif
        }
    }
}

void InputDispatcher::resetTargetsLocked() {
    mCurrentInputTargetsValid = false;
    mCurrentInputTargets.clear();
    mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_NONE;
}

void InputDispatcher::commitTargetsLocked() {
    mCurrentInputTargetsValid = true;
}

int32_t InputDispatcher::handleTargetsNotReadyLocked(nsecs_t currentTime,
        const EventEntry* entry, const InputApplication* application, const InputWindow* window,
        nsecs_t* nextWakeupTime) {
    if (application == NULL && window == NULL) {
        if (mInputTargetWaitCause != INPUT_TARGET_WAIT_CAUSE_SYSTEM_NOT_READY) {
#if DEBUG_FOCUS
            LOGD("Waiting for system to become ready for input.");
#endif
            mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_SYSTEM_NOT_READY;
            mInputTargetWaitStartTime = currentTime;
            mInputTargetWaitTimeoutTime = LONG_LONG_MAX;
            mInputTargetWaitTimeoutExpired = false;
        }
    } else {
        if (mInputTargetWaitCause != INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY) {
#if DEBUG_FOCUS
            LOGD("Waiting for application to become ready for input: %s",
                    getApplicationWindowLabelLocked(application, window).string());
#endif
            nsecs_t timeout = window ? window->dispatchingTimeout :
                application ? application->dispatchingTimeout : DEFAULT_INPUT_DISPATCHING_TIMEOUT;

            mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY;
            mInputTargetWaitStartTime = currentTime;
            mInputTargetWaitTimeoutTime = currentTime + timeout;
            mInputTargetWaitTimeoutExpired = false;
        }
    }

    if (mInputTargetWaitTimeoutExpired) {
        return INPUT_EVENT_INJECTION_TIMED_OUT;
    }

    if (currentTime >= mInputTargetWaitTimeoutTime) {
        onANRLocked(currentTime, application, window, entry->eventTime, mInputTargetWaitStartTime);

        // Force poll loop to wake up immediately on next iteration once we get the
        // ANR response back from the policy.
        *nextWakeupTime = LONG_LONG_MIN;
        return INPUT_EVENT_INJECTION_PENDING;
    } else {
        // Force poll loop to wake up when timeout is due.
        if (mInputTargetWaitTimeoutTime < *nextWakeupTime) {
            *nextWakeupTime = mInputTargetWaitTimeoutTime;
        }
        return INPUT_EVENT_INJECTION_PENDING;
    }
}

void InputDispatcher::resumeAfterTargetsNotReadyTimeoutLocked(nsecs_t newTimeout,
        const sp<InputChannel>& inputChannel) {
    if (newTimeout > 0) {
        // Extend the timeout.
        mInputTargetWaitTimeoutTime = now() + newTimeout;
    } else {
        // Give up.
        mInputTargetWaitTimeoutExpired = true;

        // Release the touch targets.
        mTouchState.reset();

        // Input state will not be realistic.  Mark it out of sync.
        if (inputChannel.get()) {
            ssize_t connectionIndex = getConnectionIndexLocked(inputChannel);
            if (connectionIndex >= 0) {
                sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
                synthesizeCancelationEventsForConnectionLocked(
                        connection, InputState::CANCEL_ALL_EVENTS,
                        "application not responding");
            }
        }
    }
}

nsecs_t InputDispatcher::getTimeSpentWaitingForApplicationLocked(
        nsecs_t currentTime) {
    if (mInputTargetWaitCause == INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY) {
        return currentTime - mInputTargetWaitStartTime;
    }
    return 0;
}

void InputDispatcher::resetANRTimeoutsLocked() {
#if DEBUG_FOCUS
        LOGD("Resetting ANR timeouts.");
#endif

    // Reset input target wait timeout.
    mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_NONE;
}

int32_t InputDispatcher::findFocusedWindowTargetsLocked(nsecs_t currentTime,
        const EventEntry* entry, nsecs_t* nextWakeupTime) {
    mCurrentInputTargets.clear();

    int32_t injectionResult;

    // If there is no currently focused window and no focused application
    // then drop the event.
    if (! mFocusedWindow) {
        if (mFocusedApplication) {
#if DEBUG_FOCUS
            LOGD("Waiting because there is no focused window but there is a "
                    "focused application that may eventually add a window: %s.",
                    getApplicationWindowLabelLocked(mFocusedApplication, NULL).string());
#endif
            injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                    mFocusedApplication, NULL, nextWakeupTime);
            goto Unresponsive;
        }

        LOGI("Dropping event because there is no focused window or focused application.");
        injectionResult = INPUT_EVENT_INJECTION_FAILED;
        goto Failed;
    }

    // Check permissions.
    if (! checkInjectionPermission(mFocusedWindow, entry->injectionState)) {
        injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
        goto Failed;
    }

    // If the currently focused window is paused then keep waiting.
    if (mFocusedWindow->paused) {
#if DEBUG_FOCUS
        LOGD("Waiting because focused window is paused.");
#endif
        injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                mFocusedApplication, mFocusedWindow, nextWakeupTime);
        goto Unresponsive;
    }

    // If the currently focused window is still working on previous events then keep waiting.
    if (! isWindowFinishedWithPreviousInputLocked(mFocusedWindow)) {
#if DEBUG_FOCUS
        LOGD("Waiting because focused window still processing previous input.");
#endif
        injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                mFocusedApplication, mFocusedWindow, nextWakeupTime);
        goto Unresponsive;
    }

    // Success!  Output targets.
    injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
    addWindowTargetLocked(mFocusedWindow, InputTarget::FLAG_FOREGROUND, BitSet32(0));

    // Done.
Failed:
Unresponsive:
    nsecs_t timeSpentWaitingForApplication = getTimeSpentWaitingForApplicationLocked(currentTime);
    updateDispatchStatisticsLocked(currentTime, entry,
            injectionResult, timeSpentWaitingForApplication);
#if DEBUG_FOCUS
    LOGD("findFocusedWindow finished: injectionResult=%d, "
            "timeSpendWaitingForApplication=%0.1fms",
            injectionResult, timeSpentWaitingForApplication / 1000000.0);
#endif
    return injectionResult;
}

int32_t InputDispatcher::findTouchedWindowTargetsLocked(nsecs_t currentTime,
        const MotionEntry* entry, nsecs_t* nextWakeupTime) {
    enum InjectionPermission {
        INJECTION_PERMISSION_UNKNOWN,
        INJECTION_PERMISSION_GRANTED,
        INJECTION_PERMISSION_DENIED
    };

    mCurrentInputTargets.clear();

    nsecs_t startTime = now();

    // For security reasons, we defer updating the touch state until we are sure that
    // event injection will be allowed.
    //
    // FIXME In the original code, screenWasOff could never be set to true.
    //       The reason is that the POLICY_FLAG_WOKE_HERE
    //       and POLICY_FLAG_BRIGHT_HERE flags were set only when preprocessing raw
    //       EV_KEY, EV_REL and EV_ABS events.  As it happens, the touch event was
    //       actually enqueued using the policyFlags that appeared in the final EV_SYN
    //       events upon which no preprocessing took place.  So policyFlags was always 0.
    //       In the new native input dispatcher we're a bit more careful about event
    //       preprocessing so the touches we receive can actually have non-zero policyFlags.
    //       Unfortunately we obtain undesirable behavior.
    //
    //       Here's what happens:
    //
    //       When the device dims in anticipation of going to sleep, touches
    //       in windows which have FLAG_TOUCHABLE_WHEN_WAKING cause
    //       the device to brighten and reset the user activity timer.
    //       Touches on other windows (such as the launcher window)
    //       are dropped.  Then after a moment, the device goes to sleep.  Oops.
    //
    //       Also notice how screenWasOff was being initialized using POLICY_FLAG_BRIGHT_HERE
    //       instead of POLICY_FLAG_WOKE_HERE...
    //
    bool screenWasOff = false; // original policy: policyFlags & POLICY_FLAG_BRIGHT_HERE;

    int32_t action = entry->action;
    int32_t maskedAction = action & AMOTION_EVENT_ACTION_MASK;

    // Update the touch state as needed based on the properties of the touch event.
    int32_t injectionResult = INPUT_EVENT_INJECTION_PENDING;
    InjectionPermission injectionPermission = INJECTION_PERMISSION_UNKNOWN;
    if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
        mTempTouchState.reset();
        mTempTouchState.down = true;
    } else {
        mTempTouchState.copyFrom(mTouchState);
    }

    bool isSplit = mTempTouchState.split && mTempTouchState.down;
    if (maskedAction == AMOTION_EVENT_ACTION_DOWN
            || (isSplit && maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN)) {
        /* Case 1: New splittable pointer going down. */

        int32_t pointerIndex = getMotionEventActionPointerIndex(action);
        int32_t x = int32_t(entry->firstSample.pointerCoords[pointerIndex].x);
        int32_t y = int32_t(entry->firstSample.pointerCoords[pointerIndex].y);
        const InputWindow* newTouchedWindow = NULL;
        const InputWindow* topErrorWindow = NULL;

        // Traverse windows from front to back to find touched window and outside targets.
        size_t numWindows = mWindows.size();
        for (size_t i = 0; i < numWindows; i++) {
            const InputWindow* window = & mWindows.editItemAt(i);
            int32_t flags = window->layoutParamsFlags;

            if (flags & InputWindow::FLAG_SYSTEM_ERROR) {
                if (! topErrorWindow) {
                    topErrorWindow = window;
                }
            }

            if (window->visible) {
                if (! (flags & InputWindow::FLAG_NOT_TOUCHABLE)) {
                    bool isTouchModal = (flags & (InputWindow::FLAG_NOT_FOCUSABLE
                            | InputWindow::FLAG_NOT_TOUCH_MODAL)) == 0;
                    if (isTouchModal || window->touchableAreaContainsPoint(x, y)) {
                        if (! screenWasOff || flags & InputWindow::FLAG_TOUCHABLE_WHEN_WAKING) {
                            newTouchedWindow = window;
                        }
                        break; // found touched window, exit window loop
                    }
                }

                if (maskedAction == AMOTION_EVENT_ACTION_DOWN
                        && (flags & InputWindow::FLAG_WATCH_OUTSIDE_TOUCH)) {
                    int32_t outsideTargetFlags = InputTarget::FLAG_OUTSIDE;
                    if (isWindowObscuredAtPointLocked(window, x, y)) {
                        outsideTargetFlags |= InputTarget::FLAG_WINDOW_IS_OBSCURED;
                    }

                    mTempTouchState.addOrUpdateWindow(window, outsideTargetFlags, BitSet32(0));
                }
            }
        }

        // If there is an error window but it is not taking focus (typically because
        // it is invisible) then wait for it.  Any other focused window may in
        // fact be in ANR state.
        if (topErrorWindow && newTouchedWindow != topErrorWindow) {
#if DEBUG_FOCUS
            LOGD("Waiting because system error window is pending.");
#endif
            injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                    NULL, NULL, nextWakeupTime);
            injectionPermission = INJECTION_PERMISSION_UNKNOWN;
            goto Unresponsive;
        }

        // Figure out whether splitting will be allowed for this window.
        if (newTouchedWindow
                && (newTouchedWindow->layoutParamsFlags & InputWindow::FLAG_SPLIT_TOUCH)) {
            // New window supports splitting.
            isSplit = true;
        } else if (isSplit) {
            // New window does not support splitting but we have already split events.
            // Assign the pointer to the first foreground window we find.
            // (May be NULL which is why we put this code block before the next check.)
            newTouchedWindow = mTempTouchState.getFirstForegroundWindow();
        }

        // If we did not find a touched window then fail.
        if (! newTouchedWindow) {
            if (mFocusedApplication) {
#if DEBUG_FOCUS
                LOGD("Waiting because there is no touched window but there is a "
                        "focused application that may eventually add a new window: %s.",
                        getApplicationWindowLabelLocked(mFocusedApplication, NULL).string());
#endif
                injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                        mFocusedApplication, NULL, nextWakeupTime);
                goto Unresponsive;
            }

            LOGI("Dropping event because there is no touched window or focused application.");
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            goto Failed;
        }

        // Set target flags.
        int32_t targetFlags = InputTarget::FLAG_FOREGROUND;
        if (isSplit) {
            targetFlags |= InputTarget::FLAG_SPLIT;
        }
        if (isWindowObscuredAtPointLocked(newTouchedWindow, x, y)) {
            targetFlags |= InputTarget::FLAG_WINDOW_IS_OBSCURED;
        }

        // Update the temporary touch state.
        BitSet32 pointerIds;
        if (isSplit) {
            uint32_t pointerId = entry->pointerIds[pointerIndex];
            pointerIds.markBit(pointerId);
        }
        mTempTouchState.addOrUpdateWindow(newTouchedWindow, targetFlags, pointerIds);
    } else {
        /* Case 2: Pointer move, up, cancel or non-splittable pointer down. */

        // If the pointer is not currently down, then ignore the event.
        if (! mTempTouchState.down) {
            LOGI("Dropping event because the pointer is not down.");
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            goto Failed;
        }
    }

    // Check permission to inject into all touched foreground windows and ensure there
    // is at least one touched foreground window.
    {
        bool haveForegroundWindow = false;
        for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTempTouchState.windows[i];
            if (touchedWindow.targetFlags & InputTarget::FLAG_FOREGROUND) {
                haveForegroundWindow = true;
                if (! checkInjectionPermission(touchedWindow.window, entry->injectionState)) {
                    injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
                    injectionPermission = INJECTION_PERMISSION_DENIED;
                    goto Failed;
                }
            }
        }
        if (! haveForegroundWindow) {
#if DEBUG_INPUT_DISPATCHER_POLICY
            LOGD("Dropping event because there is no touched foreground window to receive it.");
#endif
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            goto Failed;
        }

        // Permission granted to injection into all touched foreground windows.
        injectionPermission = INJECTION_PERMISSION_GRANTED;
    }

    // Ensure all touched foreground windows are ready for new input.
    for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
        const TouchedWindow& touchedWindow = mTempTouchState.windows[i];
        if (touchedWindow.targetFlags & InputTarget::FLAG_FOREGROUND) {
            // If the touched window is paused then keep waiting.
            if (touchedWindow.window->paused) {
#if DEBUG_INPUT_DISPATCHER_POLICY
                LOGD("Waiting because touched window is paused.");
#endif
                injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                        NULL, touchedWindow.window, nextWakeupTime);
                goto Unresponsive;
            }

            // If the touched window is still working on previous events then keep waiting.
            if (! isWindowFinishedWithPreviousInputLocked(touchedWindow.window)) {
#if DEBUG_FOCUS
                LOGD("Waiting because touched window still processing previous input.");
#endif
                injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                        NULL, touchedWindow.window, nextWakeupTime);
                goto Unresponsive;
            }
        }
    }

    // If this is the first pointer going down and the touched window has a wallpaper
    // then also add the touched wallpaper windows so they are locked in for the duration
    // of the touch gesture.
    if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
        const InputWindow* foregroundWindow = mTempTouchState.getFirstForegroundWindow();
        if (foregroundWindow->hasWallpaper) {
            for (size_t i = 0; i < mWindows.size(); i++) {
                const InputWindow* window = & mWindows[i];
                if (window->layoutParamsType == InputWindow::TYPE_WALLPAPER) {
                    mTempTouchState.addOrUpdateWindow(window,
                            InputTarget::FLAG_WINDOW_IS_OBSCURED, BitSet32(0));
                }
            }
        }
    }

    // Success!  Output targets.
    injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;

    for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
        const TouchedWindow& touchedWindow = mTempTouchState.windows.itemAt(i);
        addWindowTargetLocked(touchedWindow.window, touchedWindow.targetFlags,
                touchedWindow.pointerIds);
    }

    // Drop the outside touch window since we will not care about them in the next iteration.
    mTempTouchState.removeOutsideTouchWindows();

Failed:
    // Check injection permission once and for all.
    if (injectionPermission == INJECTION_PERMISSION_UNKNOWN) {
        if (checkInjectionPermission(NULL, entry->injectionState)) {
            injectionPermission = INJECTION_PERMISSION_GRANTED;
        } else {
            injectionPermission = INJECTION_PERMISSION_DENIED;
        }
    }

    // Update final pieces of touch state if the injector had permission.
    if (injectionPermission == INJECTION_PERMISSION_GRANTED) {
        if (maskedAction == AMOTION_EVENT_ACTION_UP
                || maskedAction == AMOTION_EVENT_ACTION_CANCEL) {
            // All pointers up or canceled.
            mTempTouchState.reset();
        } else if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
            // First pointer went down.
            if (mTouchState.down) {
#if DEBUG_FOCUS
                LOGD("Pointer down received while already down.");
#endif
            }
        } else if (maskedAction == AMOTION_EVENT_ACTION_POINTER_UP) {
            // One pointer went up.
            if (isSplit) {
                int32_t pointerIndex = getMotionEventActionPointerIndex(action);
                uint32_t pointerId = entry->pointerIds[pointerIndex];

                for (size_t i = 0; i < mTempTouchState.windows.size(); ) {
                    TouchedWindow& touchedWindow = mTempTouchState.windows.editItemAt(i);
                    if (touchedWindow.targetFlags & InputTarget::FLAG_SPLIT) {
                        touchedWindow.pointerIds.clearBit(pointerId);
                        if (touchedWindow.pointerIds.isEmpty()) {
                            mTempTouchState.windows.removeAt(i);
                            continue;
                        }
                    }
                    i += 1;
                }
            }
        }

        // Save changes to touch state.
        mTouchState.copyFrom(mTempTouchState);
    } else {
#if DEBUG_FOCUS
        LOGD("Not updating touch focus because injection was denied.");
#endif
    }

Unresponsive:
    // Reset temporary touch state to ensure we release unnecessary references to input channels.
    mTempTouchState.reset();

    nsecs_t timeSpentWaitingForApplication = getTimeSpentWaitingForApplicationLocked(currentTime);
    updateDispatchStatisticsLocked(currentTime, entry,
            injectionResult, timeSpentWaitingForApplication);
#if DEBUG_FOCUS
    LOGD("findTouchedWindow finished: injectionResult=%d, injectionPermission=%d, "
            "timeSpentWaitingForApplication=%0.1fms",
            injectionResult, injectionPermission, timeSpentWaitingForApplication / 1000000.0);
#endif
    return injectionResult;
}

void InputDispatcher::addWindowTargetLocked(const InputWindow* window, int32_t targetFlags,
        BitSet32 pointerIds) {
    mCurrentInputTargets.push();

    InputTarget& target = mCurrentInputTargets.editTop();
    target.inputChannel = window->inputChannel;
    target.flags = targetFlags;
    target.xOffset = - window->frameLeft;
    target.yOffset = - window->frameTop;
    target.pointerIds = pointerIds;
}

void InputDispatcher::addMonitoringTargetsLocked() {
    for (size_t i = 0; i < mMonitoringChannels.size(); i++) {
        mCurrentInputTargets.push();

        InputTarget& target = mCurrentInputTargets.editTop();
        target.inputChannel = mMonitoringChannels[i];
        target.flags = 0;
        target.xOffset = 0;
        target.yOffset = 0;
    }
}

bool InputDispatcher::checkInjectionPermission(const InputWindow* window,
        const InjectionState* injectionState) {
    if (injectionState
            && (window == NULL || window->ownerUid != injectionState->injectorUid)
            && !hasInjectionPermission(injectionState->injectorPid, injectionState->injectorUid)) {
        if (window) {
            LOGW("Permission denied: injecting event from pid %d uid %d to window "
                    "with input channel %s owned by uid %d",
                    injectionState->injectorPid, injectionState->injectorUid,
                    window->inputChannel->getName().string(),
                    window->ownerUid);
        } else {
            LOGW("Permission denied: injecting event from pid %d uid %d",
                    injectionState->injectorPid, injectionState->injectorUid);
        }
        return false;
    }
    return true;
}

bool InputDispatcher::isWindowObscuredAtPointLocked(
        const InputWindow* window, int32_t x, int32_t y) const {
    size_t numWindows = mWindows.size();
    for (size_t i = 0; i < numWindows; i++) {
        const InputWindow* other = & mWindows.itemAt(i);
        if (other == window) {
            break;
        }
        if (other->visible && ! other->isTrustedOverlay() && other->frameContainsPoint(x, y)) {
            return true;
        }
    }
    return false;
}

bool InputDispatcher::isWindowFinishedWithPreviousInputLocked(const InputWindow* window) {
    ssize_t connectionIndex = getConnectionIndexLocked(window->inputChannel);
    if (connectionIndex >= 0) {
        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        return connection->outboundQueue.isEmpty();
    } else {
        return true;
    }
}

String8 InputDispatcher::getApplicationWindowLabelLocked(const InputApplication* application,
        const InputWindow* window) {
    if (application) {
        if (window) {
            String8 label(application->name);
            label.append(" - ");
            label.append(window->name);
            return label;
        } else {
            return application->name;
        }
    } else if (window) {
        return window->name;
    } else {
        return String8("<unknown application or window>");
    }
}

void InputDispatcher::pokeUserActivityLocked(const EventEntry* eventEntry) {
    int32_t eventType = POWER_MANAGER_BUTTON_EVENT;
    switch (eventEntry->type) {
    case EventEntry::TYPE_MOTION: {
        const MotionEntry* motionEntry = static_cast<const MotionEntry*>(eventEntry);
        if (motionEntry->action == AMOTION_EVENT_ACTION_CANCEL) {
            return;
        }

        if (motionEntry->source & AINPUT_SOURCE_CLASS_POINTER) {
            switch (motionEntry->action) {
            case AMOTION_EVENT_ACTION_DOWN:
                eventType = POWER_MANAGER_TOUCH_EVENT;
                break;
            case AMOTION_EVENT_ACTION_UP:
                eventType = POWER_MANAGER_TOUCH_UP_EVENT;
                break;
            default:
                if (motionEntry->eventTime - motionEntry->downTime < LONG_TOUCH_DELAY) {
                    eventType = POWER_MANAGER_TOUCH_EVENT;
                } else {
                    eventType = POWER_MANAGER_LONG_TOUCH_EVENT;
                }
                break;
            }
        }
        break;
    }
    case EventEntry::TYPE_KEY: {
        const KeyEntry* keyEntry = static_cast<const KeyEntry*>(eventEntry);
        if (keyEntry->flags & AKEY_EVENT_FLAG_CANCELED) {
            return;
        }
        break;
    }
    }

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doPokeUserActivityLockedInterruptible);
    commandEntry->eventTime = eventEntry->eventTime;
    commandEntry->userActivityEventType = eventType;
}

void InputDispatcher::prepareDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, EventEntry* eventEntry, const InputTarget* inputTarget,
        bool resumeWithAppendedMotionSample) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ prepareDispatchCycle - flags=%d, "
            "xOffset=%f, yOffset=%f, "
            "windowType=%d, pointerIds=0x%x, "
            "resumeWithAppendedMotionSample=%s",
            connection->getInputChannelName(), inputTarget->flags,
            inputTarget->xOffset, inputTarget->yOffset,
            inputTarget->windowType, inputTarget->pointerIds.value,
            toString(resumeWithAppendedMotionSample));
#endif

    // Make sure we are never called for streaming when splitting across multiple windows.
    bool isSplit = inputTarget->flags & InputTarget::FLAG_SPLIT;
    assert(! (resumeWithAppendedMotionSample && isSplit));

    // Skip this event if the connection status is not normal.
    // We don't want to enqueue additional outbound events if the connection is broken.
    if (connection->status != Connection::STATUS_NORMAL) {
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Dropping event because the channel status is %s",
                connection->getInputChannelName(), connection->getStatusLabel());
#endif
        return;
    }

    // Split a motion event if needed.
    if (isSplit) {
        assert(eventEntry->type == EventEntry::TYPE_MOTION);

        MotionEntry* originalMotionEntry = static_cast<MotionEntry*>(eventEntry);
        if (inputTarget->pointerIds.count() != originalMotionEntry->pointerCount) {
            MotionEntry* splitMotionEntry = splitMotionEvent(
                    originalMotionEntry, inputTarget->pointerIds);
#if DEBUG_FOCUS
            LOGD("channel '%s' ~ Split motion event.",
                    connection->getInputChannelName());
            logOutboundMotionDetailsLocked("  ", splitMotionEntry);
#endif
            eventEntry = splitMotionEntry;
        }
    }

    // Resume the dispatch cycle with a freshly appended motion sample.
    // First we check that the last dispatch entry in the outbound queue is for the same
    // motion event to which we appended the motion sample.  If we find such a dispatch
    // entry, and if it is currently in progress then we try to stream the new sample.
    bool wasEmpty = connection->outboundQueue.isEmpty();

    if (! wasEmpty && resumeWithAppendedMotionSample) {
        DispatchEntry* motionEventDispatchEntry =
                connection->findQueuedDispatchEntryForEvent(eventEntry);
        if (motionEventDispatchEntry) {
            // If the dispatch entry is not in progress, then we must be busy dispatching an
            // earlier event.  Not a problem, the motion event is on the outbound queue and will
            // be dispatched later.
            if (! motionEventDispatchEntry->inProgress) {
#if DEBUG_BATCHING
                LOGD("channel '%s' ~ Not streaming because the motion event has "
                        "not yet been dispatched.  "
                        "(Waiting for earlier events to be consumed.)",
                        connection->getInputChannelName());
#endif
                return;
            }

            // If the dispatch entry is in progress but it already has a tail of pending
            // motion samples, then it must mean that the shared memory buffer filled up.
            // Not a problem, when this dispatch cycle is finished, we will eventually start
            // a new dispatch cycle to process the tail and that tail includes the newly
            // appended motion sample.
            if (motionEventDispatchEntry->tailMotionSample) {
#if DEBUG_BATCHING
                LOGD("channel '%s' ~ Not streaming because no new samples can "
                        "be appended to the motion event in this dispatch cycle.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName());
#endif
                return;
            }

            // The dispatch entry is in progress and is still potentially open for streaming.
            // Try to stream the new motion sample.  This might fail if the consumer has already
            // consumed the motion event (or if the channel is broken).
            MotionEntry* motionEntry = static_cast<MotionEntry*>(eventEntry);
            MotionSample* appendedMotionSample = motionEntry->lastSample;
            status_t status = connection->inputPublisher.appendMotionSample(
                    appendedMotionSample->eventTime, appendedMotionSample->pointerCoords);
            if (status == OK) {
#if DEBUG_BATCHING
                LOGD("channel '%s' ~ Successfully streamed new motion sample.",
                        connection->getInputChannelName());
#endif
                return;
            }

#if DEBUG_BATCHING
            if (status == NO_MEMORY) {
                LOGD("channel '%s' ~ Could not append motion sample to currently "
                        "dispatched move event because the shared memory buffer is full.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName());
            } else if (status == status_t(FAILED_TRANSACTION)) {
                LOGD("channel '%s' ~ Could not append motion sample to currently "
                        "dispatched move event because the event has already been consumed.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName());
            } else {
                LOGD("channel '%s' ~ Could not append motion sample to currently "
                        "dispatched move event due to an error, status=%d.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName(), status);
            }
#endif
            // Failed to stream.  Start a new tail of pending motion samples to dispatch
            // in the next cycle.
            motionEventDispatchEntry->tailMotionSample = appendedMotionSample;
            return;
        }
    }

    // This is a new event.
    // Enqueue a new dispatch entry onto the outbound queue for this connection.
    DispatchEntry* dispatchEntry = mAllocator.obtainDispatchEntry(eventEntry, // increments ref
            inputTarget->flags, inputTarget->xOffset, inputTarget->yOffset);
    if (dispatchEntry->hasForegroundTarget()) {
        incrementPendingForegroundDispatchesLocked(eventEntry);
    }

    // Handle the case where we could not stream a new motion sample because the consumer has
    // already consumed the motion event (otherwise the corresponding dispatch entry would
    // still be in the outbound queue for this connection).  We set the head motion sample
    // to the list starting with the newly appended motion sample.
    if (resumeWithAppendedMotionSample) {
#if DEBUG_BATCHING
        LOGD("channel '%s' ~ Preparing a new dispatch cycle for additional motion samples "
                "that cannot be streamed because the motion event has already been consumed.",
                connection->getInputChannelName());
#endif
        MotionSample* appendedMotionSample = static_cast<MotionEntry*>(eventEntry)->lastSample;
        dispatchEntry->headMotionSample = appendedMotionSample;
    }

    // Enqueue the dispatch entry.
    connection->outboundQueue.enqueueAtTail(dispatchEntry);

    // If the outbound queue was previously empty, start the dispatch cycle going.
    if (wasEmpty) {
        activateConnectionLocked(connection.get());
        startDispatchCycleLocked(currentTime, connection);
    }
}

void InputDispatcher::startDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ startDispatchCycle",
            connection->getInputChannelName());
#endif

    assert(connection->status == Connection::STATUS_NORMAL);
    assert(! connection->outboundQueue.isEmpty());

    DispatchEntry* dispatchEntry = connection->outboundQueue.headSentinel.next;
    assert(! dispatchEntry->inProgress);

    // Mark the dispatch entry as in progress.
    dispatchEntry->inProgress = true;

    // Update the connection's input state.
    EventEntry* eventEntry = dispatchEntry->eventEntry;
    InputState::Consistency consistency = connection->inputState.trackEvent(eventEntry);

#if FILTER_INPUT_EVENTS
    // Filter out inconsistent sequences of input events.
    // The input system may drop or inject events in a way that could violate implicit
    // invariants on input state and potentially cause an application to crash
    // or think that a key or pointer is stuck down.  Technically we make no guarantees
    // of consistency but it would be nice to improve on this where possible.
    // XXX: This code is a proof of concept only.  Not ready for prime time.
    if (consistency == InputState::TOLERABLE) {
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Sending an event that is inconsistent with the connection's "
                "current input state but that is likely to be tolerated by the application.",
                connection->getInputChannelName());
#endif
    } else if (consistency == InputState::BROKEN) {
        LOGI("channel '%s' ~ Dropping an event that is inconsistent with the connection's "
                "current input state and that is likely to cause the application to crash.",
                connection->getInputChannelName());
        startNextDispatchCycleLocked(currentTime, connection);
        return;
    }
#endif

    // Publish the event.
    status_t status;
    switch (eventEntry->type) {
    case EventEntry::TYPE_KEY: {
        KeyEntry* keyEntry = static_cast<KeyEntry*>(eventEntry);

        // Apply target flags.
        int32_t action = keyEntry->action;
        int32_t flags = keyEntry->flags;

        // Publish the key event.
        status = connection->inputPublisher.publishKeyEvent(keyEntry->deviceId, keyEntry->source,
                action, flags, keyEntry->keyCode, keyEntry->scanCode,
                keyEntry->metaState, keyEntry->repeatCount, keyEntry->downTime,
                keyEntry->eventTime);

        if (status) {
            LOGE("channel '%s' ~ Could not publish key event, "
                    "status=%d", connection->getInputChannelName(), status);
            abortBrokenDispatchCycleLocked(currentTime, connection);
            return;
        }
        break;
    }

    case EventEntry::TYPE_MOTION: {
        MotionEntry* motionEntry = static_cast<MotionEntry*>(eventEntry);

        // Apply target flags.
        int32_t action = motionEntry->action;
        int32_t flags = motionEntry->flags;
        if (dispatchEntry->targetFlags & InputTarget::FLAG_OUTSIDE) {
            action = AMOTION_EVENT_ACTION_OUTSIDE;
        }
        if (dispatchEntry->targetFlags & InputTarget::FLAG_WINDOW_IS_OBSCURED) {
            flags |= AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED;
        }

        // If headMotionSample is non-NULL, then it points to the first new sample that we
        // were unable to dispatch during the previous cycle so we resume dispatching from
        // that point in the list of motion samples.
        // Otherwise, we just start from the first sample of the motion event.
        MotionSample* firstMotionSample = dispatchEntry->headMotionSample;
        if (! firstMotionSample) {
            firstMotionSample = & motionEntry->firstSample;
        }

        // Set the X and Y offset depending on the input source.
        float xOffset, yOffset;
        if (motionEntry->source & AINPUT_SOURCE_CLASS_POINTER) {
            xOffset = dispatchEntry->xOffset;
            yOffset = dispatchEntry->yOffset;
        } else {
            xOffset = 0.0f;
            yOffset = 0.0f;
        }

        // Publish the motion event and the first motion sample.
        status = connection->inputPublisher.publishMotionEvent(motionEntry->deviceId,
                motionEntry->source, action, flags, motionEntry->edgeFlags, motionEntry->metaState,
                xOffset, yOffset,
                motionEntry->xPrecision, motionEntry->yPrecision,
                motionEntry->downTime, firstMotionSample->eventTime,
                motionEntry->pointerCount, motionEntry->pointerIds,
                firstMotionSample->pointerCoords);

        if (status) {
            LOGE("channel '%s' ~ Could not publish motion event, "
                    "status=%d", connection->getInputChannelName(), status);
            abortBrokenDispatchCycleLocked(currentTime, connection);
            return;
        }

        // Append additional motion samples.
        MotionSample* nextMotionSample = firstMotionSample->next;
        for (; nextMotionSample != NULL; nextMotionSample = nextMotionSample->next) {
            status = connection->inputPublisher.appendMotionSample(
                    nextMotionSample->eventTime, nextMotionSample->pointerCoords);
            if (status == NO_MEMORY) {
#if DEBUG_DISPATCH_CYCLE
                    LOGD("channel '%s' ~ Shared memory buffer full.  Some motion samples will "
                            "be sent in the next dispatch cycle.",
                            connection->getInputChannelName());
#endif
                break;
            }
            if (status != OK) {
                LOGE("channel '%s' ~ Could not append motion sample "
                        "for a reason other than out of memory, status=%d",
                        connection->getInputChannelName(), status);
                abortBrokenDispatchCycleLocked(currentTime, connection);
                return;
            }
        }

        // Remember the next motion sample that we could not dispatch, in case we ran out
        // of space in the shared memory buffer.
        dispatchEntry->tailMotionSample = nextMotionSample;
        break;
    }

    default: {
        assert(false);
    }
    }

    // Send the dispatch signal.
    status = connection->inputPublisher.sendDispatchSignal();
    if (status) {
        LOGE("channel '%s' ~ Could not send dispatch signal, status=%d",
                connection->getInputChannelName(), status);
        abortBrokenDispatchCycleLocked(currentTime, connection);
        return;
    }

    // Record information about the newly started dispatch cycle.
    connection->lastEventTime = eventEntry->eventTime;
    connection->lastDispatchTime = currentTime;

    // Notify other system components.
    onDispatchCycleStartedLocked(currentTime, connection);
}

void InputDispatcher::finishDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ finishDispatchCycle - %01.1fms since event, "
            "%01.1fms since dispatch",
            connection->getInputChannelName(),
            connection->getEventLatencyMillis(currentTime),
            connection->getDispatchLatencyMillis(currentTime));
#endif

    if (connection->status == Connection::STATUS_BROKEN
            || connection->status == Connection::STATUS_ZOMBIE) {
        return;
    }

    // Notify other system components.
    onDispatchCycleFinishedLocked(currentTime, connection);

    // Reset the publisher since the event has been consumed.
    // We do this now so that the publisher can release some of its internal resources
    // while waiting for the next dispatch cycle to begin.
    status_t status = connection->inputPublisher.reset();
    if (status) {
        LOGE("channel '%s' ~ Could not reset publisher, status=%d",
                connection->getInputChannelName(), status);
        abortBrokenDispatchCycleLocked(currentTime, connection);
        return;
    }

    startNextDispatchCycleLocked(currentTime, connection);
}

void InputDispatcher::startNextDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
    // Start the next dispatch cycle for this connection.
    while (! connection->outboundQueue.isEmpty()) {
        DispatchEntry* dispatchEntry = connection->outboundQueue.headSentinel.next;
        if (dispatchEntry->inProgress) {
             // Finish or resume current event in progress.
            if (dispatchEntry->tailMotionSample) {
                // We have a tail of undispatched motion samples.
                // Reuse the same DispatchEntry and start a new cycle.
                dispatchEntry->inProgress = false;
                dispatchEntry->headMotionSample = dispatchEntry->tailMotionSample;
                dispatchEntry->tailMotionSample = NULL;
                startDispatchCycleLocked(currentTime, connection);
                return;
            }
            // Finished.
            connection->outboundQueue.dequeueAtHead();
            if (dispatchEntry->hasForegroundTarget()) {
                decrementPendingForegroundDispatchesLocked(dispatchEntry->eventEntry);
            }
            mAllocator.releaseDispatchEntry(dispatchEntry);
        } else {
            // If the head is not in progress, then we must have already dequeued the in
            // progress event, which means we actually aborted it.
            // So just start the next event for this connection.
            startDispatchCycleLocked(currentTime, connection);
            return;
        }
    }

    // Outbound queue is empty, deactivate the connection.
    deactivateConnectionLocked(connection.get());
}

void InputDispatcher::abortBrokenDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ abortBrokenDispatchCycle - broken=%s",
            connection->getInputChannelName(), toString(broken));
#endif

    // Clear the outbound queue.
    drainOutboundQueueLocked(connection.get());

    // The connection appears to be unrecoverably broken.
    // Ignore already broken or zombie connections.
    if (connection->status == Connection::STATUS_NORMAL) {
        connection->status = Connection::STATUS_BROKEN;

        // Notify other system components.
        onDispatchCycleBrokenLocked(currentTime, connection);
    }
}

void InputDispatcher::drainOutboundQueueLocked(Connection* connection) {
    while (! connection->outboundQueue.isEmpty()) {
        DispatchEntry* dispatchEntry = connection->outboundQueue.dequeueAtHead();
        if (dispatchEntry->hasForegroundTarget()) {
            decrementPendingForegroundDispatchesLocked(dispatchEntry->eventEntry);
        }
        mAllocator.releaseDispatchEntry(dispatchEntry);
    }

    deactivateConnectionLocked(connection);
}

int InputDispatcher::handleReceiveCallback(int receiveFd, int events, void* data) {
    InputDispatcher* d = static_cast<InputDispatcher*>(data);

    { // acquire lock
        AutoMutex _l(d->mLock);

        ssize_t connectionIndex = d->mConnectionsByReceiveFd.indexOfKey(receiveFd);
        if (connectionIndex < 0) {
            LOGE("Received spurious receive callback for unknown input channel.  "
                    "fd=%d, events=0x%x", receiveFd, events);
            return 0; // remove the callback
        }

        nsecs_t currentTime = now();

        sp<Connection> connection = d->mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
            LOGE("channel '%s' ~ Consumer closed input channel or an error occurred.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            d->abortBrokenDispatchCycleLocked(currentTime, connection);
            d->runCommandsLockedInterruptible();
            return 0; // remove the callback
        }

        if (! (events & ALOOPER_EVENT_INPUT)) {
            LOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            return 1;
        }

        status_t status = connection->inputPublisher.receiveFinishedSignal();
        if (status) {
            LOGE("channel '%s' ~ Failed to receive finished signal.  status=%d",
                    connection->getInputChannelName(), status);
            d->abortBrokenDispatchCycleLocked(currentTime, connection);
            d->runCommandsLockedInterruptible();
            return 0; // remove the callback
        }

        d->finishDispatchCycleLocked(currentTime, connection);
        d->runCommandsLockedInterruptible();
        return 1;
    } // release lock
}

void InputDispatcher::synthesizeCancelationEventsForAllConnectionsLocked(
        InputState::CancelationOptions options, const char* reason) {
    for (size_t i = 0; i < mConnectionsByReceiveFd.size(); i++) {
        synthesizeCancelationEventsForConnectionLocked(
                mConnectionsByReceiveFd.valueAt(i), options, reason);
    }
}

void InputDispatcher::synthesizeCancelationEventsForInputChannelLocked(
        const sp<InputChannel>& channel, InputState::CancelationOptions options,
        const char* reason) {
    ssize_t index = getConnectionIndexLocked(channel);
    if (index >= 0) {
        synthesizeCancelationEventsForConnectionLocked(
                mConnectionsByReceiveFd.valueAt(index), options, reason);
    }
}

void InputDispatcher::synthesizeCancelationEventsForConnectionLocked(
        const sp<Connection>& connection, InputState::CancelationOptions options,
        const char* reason) {
    nsecs_t currentTime = now();

    mTempCancelationEvents.clear();
    connection->inputState.synthesizeCancelationEvents(currentTime, & mAllocator,
            mTempCancelationEvents, options);

    if (! mTempCancelationEvents.isEmpty()
            && connection->status != Connection::STATUS_BROKEN) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
        LOGD("channel '%s' ~ Synthesized %d cancelation events to bring channel back in sync "
                "with reality: %s, options=%d.",
                connection->getInputChannelName(), mTempCancelationEvents.size(), reason, options);
#endif
        for (size_t i = 0; i < mTempCancelationEvents.size(); i++) {
            EventEntry* cancelationEventEntry = mTempCancelationEvents.itemAt(i);
            switch (cancelationEventEntry->type) {
            case EventEntry::TYPE_KEY:
                logOutboundKeyDetailsLocked("cancel - ",
                        static_cast<KeyEntry*>(cancelationEventEntry));
                break;
            case EventEntry::TYPE_MOTION:
                logOutboundMotionDetailsLocked("cancel - ",
                        static_cast<MotionEntry*>(cancelationEventEntry));
                break;
            }

            int32_t xOffset, yOffset;
            const InputWindow* window = getWindowLocked(connection->inputChannel);
            if (window) {
                xOffset = -window->frameLeft;
                yOffset = -window->frameTop;
            } else {
                xOffset = 0;
                yOffset = 0;
            }

            DispatchEntry* cancelationDispatchEntry =
                    mAllocator.obtainDispatchEntry(cancelationEventEntry, // increments ref
                    0, xOffset, yOffset);
            connection->outboundQueue.enqueueAtTail(cancelationDispatchEntry);

            mAllocator.releaseEventEntry(cancelationEventEntry);
        }

        if (!connection->outboundQueue.headSentinel.next->inProgress) {
            startDispatchCycleLocked(currentTime, connection);
        }
    }
}

InputDispatcher::MotionEntry*
InputDispatcher::splitMotionEvent(const MotionEntry* originalMotionEntry, BitSet32 pointerIds) {
    assert(pointerIds.value != 0);

    uint32_t splitPointerIndexMap[MAX_POINTERS];
    int32_t splitPointerIds[MAX_POINTERS];
    PointerCoords splitPointerCoords[MAX_POINTERS];

    uint32_t originalPointerCount = originalMotionEntry->pointerCount;
    uint32_t splitPointerCount = 0;

    for (uint32_t originalPointerIndex = 0; originalPointerIndex < originalPointerCount;
            originalPointerIndex++) {
        int32_t pointerId = uint32_t(originalMotionEntry->pointerIds[originalPointerIndex]);
        if (pointerIds.hasBit(pointerId)) {
            splitPointerIndexMap[splitPointerCount] = originalPointerIndex;
            splitPointerIds[splitPointerCount] = pointerId;
            splitPointerCoords[splitPointerCount] =
                    originalMotionEntry->firstSample.pointerCoords[originalPointerIndex];
            splitPointerCount += 1;
        }
    }
    assert(splitPointerCount == pointerIds.count());

    int32_t action = originalMotionEntry->action;
    int32_t maskedAction = action & AMOTION_EVENT_ACTION_MASK;
    if (maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN
            || maskedAction == AMOTION_EVENT_ACTION_POINTER_UP) {
        int32_t originalPointerIndex = getMotionEventActionPointerIndex(action);
        int32_t pointerId = originalMotionEntry->pointerIds[originalPointerIndex];
        if (pointerIds.hasBit(pointerId)) {
            if (pointerIds.count() == 1) {
                // The first/last pointer went down/up.
                action = maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN
                        ? AMOTION_EVENT_ACTION_DOWN : AMOTION_EVENT_ACTION_UP;
            } else {
                // A secondary pointer went down/up.
                uint32_t splitPointerIndex = 0;
                while (pointerId != splitPointerIds[splitPointerIndex]) {
                    splitPointerIndex += 1;
                }
                action = maskedAction | (splitPointerIndex
                        << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT);
            }
        } else {
            // An unrelated pointer changed.
            action = AMOTION_EVENT_ACTION_MOVE;
        }
    }

    MotionEntry* splitMotionEntry = mAllocator.obtainMotionEntry(
            originalMotionEntry->eventTime,
            originalMotionEntry->deviceId,
            originalMotionEntry->source,
            originalMotionEntry->policyFlags,
            action,
            originalMotionEntry->flags,
            originalMotionEntry->metaState,
            originalMotionEntry->edgeFlags,
            originalMotionEntry->xPrecision,
            originalMotionEntry->yPrecision,
            originalMotionEntry->downTime,
            splitPointerCount, splitPointerIds, splitPointerCoords);

    for (MotionSample* originalMotionSample = originalMotionEntry->firstSample.next;
            originalMotionSample != NULL; originalMotionSample = originalMotionSample->next) {
        for (uint32_t splitPointerIndex = 0; splitPointerIndex < splitPointerCount;
                splitPointerIndex++) {
            uint32_t originalPointerIndex = splitPointerIndexMap[splitPointerIndex];
            splitPointerCoords[splitPointerIndex] =
                    originalMotionSample->pointerCoords[originalPointerIndex];
        }

        mAllocator.appendMotionSample(splitMotionEntry, originalMotionSample->eventTime,
                splitPointerCoords);
    }

    return splitMotionEntry;
}

void InputDispatcher::notifyConfigurationChanged(nsecs_t eventTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyConfigurationChanged - eventTime=%lld", eventTime);
#endif

    bool needWake;
    { // acquire lock
        AutoMutex _l(mLock);

        ConfigurationChangedEntry* newEntry = mAllocator.obtainConfigurationChangedEntry(eventTime);
        needWake = enqueueInboundEventLocked(newEntry);
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

void InputDispatcher::notifyKey(nsecs_t eventTime, int32_t deviceId, int32_t source,
        uint32_t policyFlags, int32_t action, int32_t flags,
        int32_t keyCode, int32_t scanCode, int32_t metaState, nsecs_t downTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyKey - eventTime=%lld, deviceId=0x%x, source=0x%x, policyFlags=0x%x, action=0x%x, "
            "flags=0x%x, keyCode=0x%x, scanCode=0x%x, metaState=0x%x, downTime=%lld",
            eventTime, deviceId, source, policyFlags, action, flags,
            keyCode, scanCode, metaState, downTime);
#endif
    if (! validateKeyEvent(action)) {
        return;
    }

    /* According to http://source.android.com/porting/keymaps_keyboard_input.html
     * Key definitions: Key definitions follow the syntax key SCANCODE KEYCODE [FLAGS...],
     * where SCANCODE is a number, KEYCODE is defined in your specific keylayout file
     * (android.keylayout.xxx), and potential FLAGS are defined as follows:
     *     SHIFT: While pressed, the shift key modifier is set
     *     ALT: While pressed, the alt key modifier is set
     *     CAPS: While pressed, the caps lock key modifier is set
     *     Since KeyEvent.java doesn't check if Cap lock is ON and we don't have a
     *     modifer state for cap lock, we will not support it.
     */
    if (policyFlags & POLICY_FLAG_ALT) {
        metaState |= AMETA_ALT_ON | AMETA_ALT_LEFT_ON;
    }
    if (policyFlags & POLICY_FLAG_ALT_GR) {
        metaState |= AMETA_ALT_ON | AMETA_ALT_RIGHT_ON;
    }
    if (policyFlags & POLICY_FLAG_SHIFT) {
        metaState |= AMETA_SHIFT_ON | AMETA_SHIFT_LEFT_ON;
    }

    policyFlags |= POLICY_FLAG_TRUSTED;
    mPolicy->interceptKeyBeforeQueueing(eventTime, deviceId, action, /*byref*/ flags,
            keyCode, scanCode, /*byref*/ policyFlags);

    bool needWake;
    { // acquire lock
        AutoMutex _l(mLock);

        int32_t repeatCount = 0;
        KeyEntry* newEntry = mAllocator.obtainKeyEntry(eventTime,
                deviceId, source, policyFlags, action, flags, keyCode, scanCode,
                metaState, repeatCount, downTime);

        needWake = enqueueInboundEventLocked(newEntry);
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

void InputDispatcher::notifyMotion(nsecs_t eventTime, int32_t deviceId, int32_t source,
        uint32_t policyFlags, int32_t action, int32_t flags, int32_t metaState, int32_t edgeFlags,
        uint32_t pointerCount, const int32_t* pointerIds, const PointerCoords* pointerCoords,
        float xPrecision, float yPrecision, nsecs_t downTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyMotion - eventTime=%lld, deviceId=0x%x, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, metaState=0x%x, edgeFlags=0x%x, "
            "xPrecision=%f, yPrecision=%f, downTime=%lld",
            eventTime, deviceId, source, policyFlags, action, flags, metaState, edgeFlags,
            xPrecision, yPrecision, downTime);
    for (uint32_t i = 0; i < pointerCount; i++) {
        LOGD("  Pointer %d: id=%d, x=%f, y=%f, pressure=%f, size=%f, "
                "touchMajor=%f, touchMinor=%f, toolMajor=%f, toolMinor=%f, "
                "orientation=%f",
                i, pointerIds[i], pointerCoords[i].x, pointerCoords[i].y,
                pointerCoords[i].pressure, pointerCoords[i].size,
                pointerCoords[i].touchMajor, pointerCoords[i].touchMinor,
                pointerCoords[i].toolMajor, pointerCoords[i].toolMinor,
                pointerCoords[i].orientation);
    }
#endif
    if (! validateMotionEvent(action, pointerCount, pointerIds)) {
        return;
    }

    policyFlags |= POLICY_FLAG_TRUSTED;
    mPolicy->interceptGenericBeforeQueueing(eventTime, /*byref*/ policyFlags);

    bool needWake;
    { // acquire lock
        AutoMutex _l(mLock);

        // Attempt batching and streaming of move events.
        if (action == AMOTION_EVENT_ACTION_MOVE) {
            // BATCHING CASE
            //
            // Try to append a move sample to the tail of the inbound queue for this device.
            // Give up if we encounter a non-move motion event for this device since that
            // means we cannot append any new samples until a new motion event has started.
            for (EventEntry* entry = mInboundQueue.tailSentinel.prev;
                    entry != & mInboundQueue.headSentinel; entry = entry->prev) {
                if (entry->type != EventEntry::TYPE_MOTION) {
                    // Keep looking for motion events.
                    continue;
                }

                MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
                if (motionEntry->deviceId != deviceId) {
                    // Keep looking for this device.
                    continue;
                }

                if (motionEntry->action != AMOTION_EVENT_ACTION_MOVE
                        || motionEntry->pointerCount != pointerCount
                        || motionEntry->isInjected()) {
                    // Last motion event in the queue for this device is not compatible for
                    // appending new samples.  Stop here.
                    goto NoBatchingOrStreaming;
                }

                // The last motion event is a move and is compatible for appending.
                // Do the batching magic.
                mAllocator.appendMotionSample(motionEntry, eventTime, pointerCoords);
#if DEBUG_BATCHING
                LOGD("Appended motion sample onto batch for most recent "
                        "motion event for this device in the inbound queue.");
#endif
                return; // done!
            }

            // STREAMING CASE
            //
            // There is no pending motion event (of any kind) for this device in the inbound queue.
            // Search the outbound queue for the current foreground targets to find a dispatched
            // motion event that is still in progress.  If found, then, appen the new sample to
            // that event and push it out to all current targets.  The logic in
            // prepareDispatchCycleLocked takes care of the case where some targets may
            // already have consumed the motion event by starting a new dispatch cycle if needed.
            if (mCurrentInputTargetsValid) {
                for (size_t i = 0; i < mCurrentInputTargets.size(); i++) {
                    const InputTarget& inputTarget = mCurrentInputTargets[i];
                    if ((inputTarget.flags & InputTarget::FLAG_FOREGROUND) == 0) {
                        // Skip non-foreground targets.  We only want to stream if there is at
                        // least one foreground target whose dispatch is still in progress.
                        continue;
                    }

                    ssize_t connectionIndex = getConnectionIndexLocked(inputTarget.inputChannel);
                    if (connectionIndex < 0) {
                        // Connection must no longer be valid.
                        continue;
                    }

                    sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
                    if (connection->outboundQueue.isEmpty()) {
                        // This foreground target has an empty outbound queue.
                        continue;
                    }

                    DispatchEntry* dispatchEntry = connection->outboundQueue.headSentinel.next;
                    if (! dispatchEntry->inProgress
                            || dispatchEntry->eventEntry->type != EventEntry::TYPE_MOTION
                            || dispatchEntry->isSplit()) {
                        // No motion event is being dispatched, or it is being split across
                        // windows in which case we cannot stream.
                        continue;
                    }

                    MotionEntry* motionEntry = static_cast<MotionEntry*>(
                            dispatchEntry->eventEntry);
                    if (motionEntry->action != AMOTION_EVENT_ACTION_MOVE
                            || motionEntry->deviceId != deviceId
                            || motionEntry->pointerCount != pointerCount
                            || motionEntry->isInjected()) {
                        // The motion event is not compatible with this move.
                        continue;
                    }

                    // Hurray!  This foreground target is currently dispatching a move event
                    // that we can stream onto.  Append the motion sample and resume dispatch.
                    mAllocator.appendMotionSample(motionEntry, eventTime, pointerCoords);
#if DEBUG_BATCHING
                    LOGD("Appended motion sample onto batch for most recently dispatched "
                            "motion event for this device in the outbound queues.  "
                            "Attempting to stream the motion sample.");
#endif
                    nsecs_t currentTime = now();
                    dispatchEventToCurrentInputTargetsLocked(currentTime, motionEntry,
                            true /*resumeWithAppendedMotionSample*/);

                    runCommandsLockedInterruptible();
                    return; // done!
                }
            }

NoBatchingOrStreaming:;
        }

        // Just enqueue a new motion event.
        MotionEntry* newEntry = mAllocator.obtainMotionEntry(eventTime,
                deviceId, source, policyFlags, action, flags, metaState, edgeFlags,
                xPrecision, yPrecision, downTime,
                pointerCount, pointerIds, pointerCoords);

        needWake = enqueueInboundEventLocked(newEntry);
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

void InputDispatcher::notifySwitch(nsecs_t when, int32_t switchCode, int32_t switchValue,
        uint32_t policyFlags) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifySwitch - switchCode=%d, switchValue=%d, policyFlags=0x%x",
            switchCode, switchValue, policyFlags);
#endif

    policyFlags |= POLICY_FLAG_TRUSTED;
    mPolicy->notifySwitch(when, switchCode, switchValue, policyFlags);
}

int32_t InputDispatcher::injectInputEvent(const InputEvent* event,
        int32_t injectorPid, int32_t injectorUid, int32_t syncMode, int32_t timeoutMillis) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("injectInputEvent - eventType=%d, injectorPid=%d, injectorUid=%d, "
            "syncMode=%d, timeoutMillis=%d",
            event->getType(), injectorPid, injectorUid, syncMode, timeoutMillis);
#endif

    nsecs_t endTime = now() + milliseconds_to_nanoseconds(timeoutMillis);

    uint32_t policyFlags = POLICY_FLAG_INJECTED;
    if (hasInjectionPermission(injectorPid, injectorUid)) {
        policyFlags |= POLICY_FLAG_TRUSTED;
    }

    EventEntry* injectedEntry;
    switch (event->getType()) {
    case AINPUT_EVENT_TYPE_KEY: {
        const KeyEvent* keyEvent = static_cast<const KeyEvent*>(event);
        int32_t action = keyEvent->getAction();
        if (! validateKeyEvent(action)) {
            return INPUT_EVENT_INJECTION_FAILED;
        }

        nsecs_t eventTime = keyEvent->getEventTime();
        int32_t deviceId = keyEvent->getDeviceId();
        int32_t flags = keyEvent->getFlags();
        int32_t keyCode = keyEvent->getKeyCode();
        int32_t scanCode = keyEvent->getScanCode();
        mPolicy->interceptKeyBeforeQueueing(eventTime, deviceId, action, /*byref*/ flags,
                keyCode, scanCode, /*byref*/ policyFlags);

        mLock.lock();
        injectedEntry = mAllocator.obtainKeyEntry(eventTime, deviceId, keyEvent->getSource(),
                policyFlags, action, flags, keyCode, scanCode, keyEvent->getMetaState(),
                keyEvent->getRepeatCount(), keyEvent->getDownTime());
        break;
    }

    case AINPUT_EVENT_TYPE_MOTION: {
        const MotionEvent* motionEvent = static_cast<const MotionEvent*>(event);
        int32_t action = motionEvent->getAction();
        size_t pointerCount = motionEvent->getPointerCount();
        const int32_t* pointerIds = motionEvent->getPointerIds();
        if (! validateMotionEvent(action, pointerCount, pointerIds)) {
            return INPUT_EVENT_INJECTION_FAILED;
        }

        nsecs_t eventTime = motionEvent->getEventTime();
        mPolicy->interceptGenericBeforeQueueing(eventTime, /*byref*/ policyFlags);

        mLock.lock();
        const nsecs_t* sampleEventTimes = motionEvent->getSampleEventTimes();
        const PointerCoords* samplePointerCoords = motionEvent->getSamplePointerCoords();
        MotionEntry* motionEntry = mAllocator.obtainMotionEntry(*sampleEventTimes,
                motionEvent->getDeviceId(), motionEvent->getSource(), policyFlags,
                action, motionEvent->getFlags(),
                motionEvent->getMetaState(), motionEvent->getEdgeFlags(),
                motionEvent->getXPrecision(), motionEvent->getYPrecision(),
                motionEvent->getDownTime(), uint32_t(pointerCount),
                pointerIds, samplePointerCoords);
        for (size_t i = motionEvent->getHistorySize(); i > 0; i--) {
            sampleEventTimes += 1;
            samplePointerCoords += pointerCount;
            mAllocator.appendMotionSample(motionEntry, *sampleEventTimes, samplePointerCoords);
        }
        injectedEntry = motionEntry;
        break;
    }

    default:
        LOGW("Cannot inject event of type %d", event->getType());
        return INPUT_EVENT_INJECTION_FAILED;
    }

    InjectionState* injectionState = mAllocator.obtainInjectionState(injectorPid, injectorUid);
    if (syncMode == INPUT_EVENT_INJECTION_SYNC_NONE) {
        injectionState->injectionIsAsync = true;
    }

    injectionState->refCount += 1;
    injectedEntry->injectionState = injectionState;

    bool needWake = enqueueInboundEventLocked(injectedEntry);
    mLock.unlock();

    if (needWake) {
        mLooper->wake();
    }

    int32_t injectionResult;
    { // acquire lock
        AutoMutex _l(mLock);

        if (syncMode == INPUT_EVENT_INJECTION_SYNC_NONE) {
            injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
        } else {
            for (;;) {
                injectionResult = injectionState->injectionResult;
                if (injectionResult != INPUT_EVENT_INJECTION_PENDING) {
                    break;
                }

                nsecs_t remainingTimeout = endTime - now();
                if (remainingTimeout <= 0) {
#if DEBUG_INJECTION
                    LOGD("injectInputEvent - Timed out waiting for injection result "
                            "to become available.");
#endif
                    injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                    break;
                }

                mInjectionResultAvailableCondition.waitRelative(mLock, remainingTimeout);
            }

            if (injectionResult == INPUT_EVENT_INJECTION_SUCCEEDED
                    && syncMode == INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISHED) {
                while (injectionState->pendingForegroundDispatches != 0) {
#if DEBUG_INJECTION
                    LOGD("injectInputEvent - Waiting for %d pending foreground dispatches.",
                            injectionState->pendingForegroundDispatches);
#endif
                    nsecs_t remainingTimeout = endTime - now();
                    if (remainingTimeout <= 0) {
#if DEBUG_INJECTION
                    LOGD("injectInputEvent - Timed out waiting for pending foreground "
                            "dispatches to finish.");
#endif
                        injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                        break;
                    }

                    mInjectionSyncFinishedCondition.waitRelative(mLock, remainingTimeout);
                }
            }
        }

        mAllocator.releaseInjectionState(injectionState);
    } // release lock

#if DEBUG_INJECTION
    LOGD("injectInputEvent - Finished with result %d.  "
            "injectorPid=%d, injectorUid=%d",
            injectionResult, injectorPid, injectorUid);
#endif

    return injectionResult;
}

bool InputDispatcher::hasInjectionPermission(int32_t injectorPid, int32_t injectorUid) {
    return injectorUid == 0
            || mPolicy->checkInjectEventsPermissionNonReentrant(injectorPid, injectorUid);
}

void InputDispatcher::setInjectionResultLocked(EventEntry* entry, int32_t injectionResult) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState) {
#if DEBUG_INJECTION
        LOGD("Setting input event injection result to %d.  "
                "injectorPid=%d, injectorUid=%d",
                 injectionResult, injectionState->injectorPid, injectionState->injectorUid);
#endif

        if (injectionState->injectionIsAsync) {
            // Log the outcome since the injector did not wait for the injection result.
            switch (injectionResult) {
            case INPUT_EVENT_INJECTION_SUCCEEDED:
                LOGV("Asynchronous input event injection succeeded.");
                break;
            case INPUT_EVENT_INJECTION_FAILED:
                LOGW("Asynchronous input event injection failed.");
                break;
            case INPUT_EVENT_INJECTION_PERMISSION_DENIED:
                LOGW("Asynchronous input event injection permission denied.");
                break;
            case INPUT_EVENT_INJECTION_TIMED_OUT:
                LOGW("Asynchronous input event injection timed out.");
                break;
            }
        }

        injectionState->injectionResult = injectionResult;
        mInjectionResultAvailableCondition.broadcast();
    }
}

void InputDispatcher::incrementPendingForegroundDispatchesLocked(EventEntry* entry) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState) {
        injectionState->pendingForegroundDispatches += 1;
    }
}

void InputDispatcher::decrementPendingForegroundDispatchesLocked(EventEntry* entry) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState) {
        injectionState->pendingForegroundDispatches -= 1;

        if (injectionState->pendingForegroundDispatches == 0) {
            mInjectionSyncFinishedCondition.broadcast();
        }
    }
}

const InputWindow* InputDispatcher::getWindowLocked(const sp<InputChannel>& inputChannel) {
    for (size_t i = 0; i < mWindows.size(); i++) {
        const InputWindow* window = & mWindows[i];
        if (window->inputChannel == inputChannel) {
            return window;
        }
    }
    return NULL;
}

void InputDispatcher::setInputWindows(const Vector<InputWindow>& inputWindows) {
#if DEBUG_FOCUS
    LOGD("setInputWindows");
#endif
    { // acquire lock
        AutoMutex _l(mLock);

        // Clear old window pointers.
        sp<InputChannel> oldFocusedWindowChannel;
        if (mFocusedWindow) {
            oldFocusedWindowChannel = mFocusedWindow->inputChannel;
            mFocusedWindow = NULL;
        }

        mWindows.clear();

        // Loop over new windows and rebuild the necessary window pointers for
        // tracking focus and touch.
        mWindows.appendVector(inputWindows);

        size_t numWindows = mWindows.size();
        for (size_t i = 0; i < numWindows; i++) {
            const InputWindow* window = & mWindows.itemAt(i);
            if (window->hasFocus) {
                mFocusedWindow = window;
                break;
            }
        }

        if (oldFocusedWindowChannel != NULL) {
            if (!mFocusedWindow || oldFocusedWindowChannel != mFocusedWindow->inputChannel) {
#if DEBUG_FOCUS
                LOGD("Focus left window: %s",
                        oldFocusedWindowChannel->getName().string());
#endif
                synthesizeCancelationEventsForInputChannelLocked(oldFocusedWindowChannel,
                        InputState::CANCEL_NON_POINTER_EVENTS, "focus left window");
                oldFocusedWindowChannel.clear();
            }
        }
        if (mFocusedWindow && oldFocusedWindowChannel == NULL) {
#if DEBUG_FOCUS
            LOGD("Focus entered window: %s",
                    mFocusedWindow->inputChannel->getName().string());
#endif
        }

        for (size_t i = 0; i < mTouchState.windows.size(); ) {
            TouchedWindow& touchedWindow = mTouchState.windows.editItemAt(i);
            const InputWindow* window = getWindowLocked(touchedWindow.channel);
            if (window) {
                touchedWindow.window = window;
                i += 1;
            } else {
#if DEBUG_FOCUS
                LOGD("Touched window was removed: %s", touchedWindow.channel->getName().string());
#endif
                synthesizeCancelationEventsForInputChannelLocked(touchedWindow.channel,
                        InputState::CANCEL_POINTER_EVENTS, "touched window was removed");
                mTouchState.windows.removeAt(i);
            }
        }

#if DEBUG_FOCUS
        //logDispatchStateLocked();
#endif
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
}

void InputDispatcher::setFocusedApplication(const InputApplication* inputApplication) {
#if DEBUG_FOCUS
    LOGD("setFocusedApplication");
#endif
    { // acquire lock
        AutoMutex _l(mLock);

        releaseFocusedApplicationLocked();

        if (inputApplication) {
            mFocusedApplicationStorage = *inputApplication;
            mFocusedApplication = & mFocusedApplicationStorage;
        }

#if DEBUG_FOCUS
        //logDispatchStateLocked();
#endif
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
}

void InputDispatcher::releaseFocusedApplicationLocked() {
    if (mFocusedApplication) {
        mFocusedApplication = NULL;
        mFocusedApplicationStorage.handle.clear();
    }
}

void InputDispatcher::setInputDispatchMode(bool enabled, bool frozen) {
#if DEBUG_FOCUS
    LOGD("setInputDispatchMode: enabled=%d, frozen=%d", enabled, frozen);
#endif

    bool changed;
    { // acquire lock
        AutoMutex _l(mLock);

        if (mDispatchEnabled != enabled || mDispatchFrozen != frozen) {
            if (mDispatchFrozen && !frozen) {
                resetANRTimeoutsLocked();
            }

            if (mDispatchEnabled && !enabled) {
                resetAndDropEverythingLocked("dispatcher is being disabled");
            }

            mDispatchEnabled = enabled;
            mDispatchFrozen = frozen;
            changed = true;
        } else {
            changed = false;
        }

#if DEBUG_FOCUS
        //logDispatchStateLocked();
#endif
    } // release lock

    if (changed) {
        // Wake up poll loop since it may need to make new input dispatching choices.
        mLooper->wake();
    }
}

void InputDispatcher::resetAndDropEverythingLocked(const char* reason) {
#if DEBUG_FOCUS
    LOGD("Resetting and dropping all events (%s).", reason);
#endif

    synthesizeCancelationEventsForAllConnectionsLocked(InputState::CANCEL_ALL_EVENTS, reason);

    resetKeyRepeatLocked();
    releasePendingEventLocked();
    drainInboundQueueLocked();
    resetTargetsLocked();

    mTouchState.reset();
}

void InputDispatcher::logDispatchStateLocked() {
    String8 dump;
    dumpDispatchStateLocked(dump);

    char* text = dump.lockBuffer(dump.size());
    char* start = text;
    while (*start != '\0') {
        char* end = strchr(start, '\n');
        if (*end == '\n') {
            *(end++) = '\0';
        }
        LOGD("%s", start);
        start = end;
    }
}

void InputDispatcher::dumpDispatchStateLocked(String8& dump) {
    dump.appendFormat(INDENT "DispatchEnabled: %d\n", mDispatchEnabled);
    dump.appendFormat(INDENT "DispatchFrozen: %d\n", mDispatchFrozen);

    if (mFocusedApplication) {
        dump.appendFormat(INDENT "FocusedApplication: name='%s', dispatchingTimeout=%0.3fms\n",
                mFocusedApplication->name.string(),
                mFocusedApplication->dispatchingTimeout / 1000000.0);
    } else {
        dump.append(INDENT "FocusedApplication: <null>\n");
    }
    dump.appendFormat(INDENT "FocusedWindow: name='%s'\n",
            mFocusedWindow != NULL ? mFocusedWindow->name.string() : "<null>");

    dump.appendFormat(INDENT "TouchDown: %s\n", toString(mTouchState.down));
    dump.appendFormat(INDENT "TouchSplit: %s\n", toString(mTouchState.split));
    if (!mTouchState.windows.isEmpty()) {
        dump.append(INDENT "TouchedWindows:\n");
        for (size_t i = 0; i < mTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTouchState.windows[i];
            dump.appendFormat(INDENT2 "%d: name='%s', pointerIds=0x%0x, targetFlags=0x%x\n",
                    i, touchedWindow.window->name.string(), touchedWindow.pointerIds.value,
                    touchedWindow.targetFlags);
        }
    } else {
        dump.append(INDENT "TouchedWindows: <none>\n");
    }

    if (!mWindows.isEmpty()) {
        dump.append(INDENT "Windows:\n");
        for (size_t i = 0; i < mWindows.size(); i++) {
            const InputWindow& window = mWindows[i];
            dump.appendFormat(INDENT2 "%d: name='%s', paused=%s, hasFocus=%s, hasWallpaper=%s, "
                    "visible=%s, canReceiveKeys=%s, flags=0x%08x, type=0x%08x, layer=%d, "
                    "frame=[%d,%d][%d,%d], "
                    "visibleFrame=[%d,%d][%d,%d], "
                    "touchableArea=[%d,%d][%d,%d], "
                    "ownerPid=%d, ownerUid=%d, dispatchingTimeout=%0.3fms\n",
                    i, window.name.string(),
                    toString(window.paused),
                    toString(window.hasFocus),
                    toString(window.hasWallpaper),
                    toString(window.visible),
                    toString(window.canReceiveKeys),
                    window.layoutParamsFlags, window.layoutParamsType,
                    window.layer,
                    window.frameLeft, window.frameTop,
                    window.frameRight, window.frameBottom,
                    window.visibleFrameLeft, window.visibleFrameTop,
                    window.visibleFrameRight, window.visibleFrameBottom,
                    window.touchableAreaLeft, window.touchableAreaTop,
                    window.touchableAreaRight, window.touchableAreaBottom,
                    window.ownerPid, window.ownerUid,
                    window.dispatchingTimeout / 1000000.0);
        }
    } else {
        dump.append(INDENT "Windows: <none>\n");
    }

    if (!mMonitoringChannels.isEmpty()) {
        dump.append(INDENT "MonitoringChannels:\n");
        for (size_t i = 0; i < mMonitoringChannels.size(); i++) {
            const sp<InputChannel>& channel = mMonitoringChannels[i];
            dump.appendFormat(INDENT2 "%d: '%s'\n", i, channel->getName().string());
        }
    } else {
        dump.append(INDENT "MonitoringChannels: <none>\n");
    }

    dump.appendFormat(INDENT "InboundQueue: length=%u\n", mInboundQueue.count());

    if (!mActiveConnections.isEmpty()) {
        dump.append(INDENT "ActiveConnections:\n");
        for (size_t i = 0; i < mActiveConnections.size(); i++) {
            const Connection* connection = mActiveConnections[i];
            dump.appendFormat(INDENT2 "%d: '%s', status=%s, outboundQueueLength=%u"
                    "inputState.isNeutral=%s\n",
                    i, connection->getInputChannelName(), connection->getStatusLabel(),
                    connection->outboundQueue.count(),
                    toString(connection->inputState.isNeutral()));
        }
    } else {
        dump.append(INDENT "ActiveConnections: <none>\n");
    }

    if (isAppSwitchPendingLocked()) {
        dump.appendFormat(INDENT "AppSwitch: pending, due in %01.1fms\n",
                (mAppSwitchDueTime - now()) / 1000000.0);
    } else {
        dump.append(INDENT "AppSwitch: not pending\n");
    }
}

status_t InputDispatcher::registerInputChannel(const sp<InputChannel>& inputChannel, bool monitor) {
#if DEBUG_REGISTRATION
    LOGD("channel '%s' ~ registerInputChannel - monitor=%s", inputChannel->getName().string(),
            toString(monitor));
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        if (getConnectionIndexLocked(inputChannel) >= 0) {
            LOGW("Attempted to register already registered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        sp<Connection> connection = new Connection(inputChannel);
        status_t status = connection->initialize();
        if (status) {
            LOGE("Failed to initialize input publisher for input channel '%s', status=%d",
                    inputChannel->getName().string(), status);
            return status;
        }

        int32_t receiveFd = inputChannel->getReceivePipeFd();
        mConnectionsByReceiveFd.add(receiveFd, connection);

        if (monitor) {
            mMonitoringChannels.push(inputChannel);
        }

        mLooper->addFd(receiveFd, 0, ALOOPER_EVENT_INPUT, handleReceiveCallback, this);

        runCommandsLockedInterruptible();
    } // release lock
    return OK;
}

status_t InputDispatcher::unregisterInputChannel(const sp<InputChannel>& inputChannel) {
#if DEBUG_REGISTRATION
    LOGD("channel '%s' ~ unregisterInputChannel", inputChannel->getName().string());
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        ssize_t connectionIndex = getConnectionIndexLocked(inputChannel);
        if (connectionIndex < 0) {
            LOGW("Attempted to unregister already unregistered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        mConnectionsByReceiveFd.removeItemsAt(connectionIndex);

        connection->status = Connection::STATUS_ZOMBIE;

        for (size_t i = 0; i < mMonitoringChannels.size(); i++) {
            if (mMonitoringChannels[i] == inputChannel) {
                mMonitoringChannels.removeAt(i);
                break;
            }
        }

        mLooper->removeFd(inputChannel->getReceivePipeFd());

        nsecs_t currentTime = now();
        abortBrokenDispatchCycleLocked(currentTime, connection);

        runCommandsLockedInterruptible();
    } // release lock

    // Wake the poll loop because removing the connection may have changed the current
    // synchronization state.
    mLooper->wake();
    return OK;
}

ssize_t InputDispatcher::getConnectionIndexLocked(const sp<InputChannel>& inputChannel) {
    ssize_t connectionIndex = mConnectionsByReceiveFd.indexOfKey(inputChannel->getReceivePipeFd());
    if (connectionIndex >= 0) {
        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (connection->inputChannel.get() == inputChannel.get()) {
            return connectionIndex;
        }
    }

    return -1;
}

void InputDispatcher::activateConnectionLocked(Connection* connection) {
    for (size_t i = 0; i < mActiveConnections.size(); i++) {
        if (mActiveConnections.itemAt(i) == connection) {
            return;
        }
    }
    mActiveConnections.add(connection);
}

void InputDispatcher::deactivateConnectionLocked(Connection* connection) {
    for (size_t i = 0; i < mActiveConnections.size(); i++) {
        if (mActiveConnections.itemAt(i) == connection) {
            mActiveConnections.removeAt(i);
            return;
        }
    }
}

void InputDispatcher::onDispatchCycleStartedLocked(
        nsecs_t currentTime, const sp<Connection>& connection) {
}

void InputDispatcher::onDispatchCycleFinishedLocked(
        nsecs_t currentTime, const sp<Connection>& connection) {
}

void InputDispatcher::onDispatchCycleBrokenLocked(
        nsecs_t currentTime, const sp<Connection>& connection) {
    LOGE("channel '%s' ~ Channel is unrecoverably broken and will be disposed!",
            connection->getInputChannelName());

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyInputChannelBrokenLockedInterruptible);
    commandEntry->connection = connection;
}

void InputDispatcher::onANRLocked(
        nsecs_t currentTime, const InputApplication* application, const InputWindow* window,
        nsecs_t eventTime, nsecs_t waitStartTime) {
    LOGI("Application is not responding: %s.  "
            "%01.1fms since event, %01.1fms since wait started",
            getApplicationWindowLabelLocked(application, window).string(),
            (currentTime - eventTime) / 1000000.0,
            (currentTime - waitStartTime) / 1000000.0);

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyANRLockedInterruptible);
    if (application) {
        commandEntry->inputApplicationHandle = application->handle;
    }
    if (window) {
        commandEntry->inputChannel = window->inputChannel;
    }
}

void InputDispatcher::doNotifyConfigurationChangedInterruptible(
        CommandEntry* commandEntry) {
    mLock.unlock();

    mPolicy->notifyConfigurationChanged(commandEntry->eventTime);

    mLock.lock();
}

void InputDispatcher::doNotifyInputChannelBrokenLockedInterruptible(
        CommandEntry* commandEntry) {
    sp<Connection> connection = commandEntry->connection;

    if (connection->status != Connection::STATUS_ZOMBIE) {
        mLock.unlock();

        mPolicy->notifyInputChannelBroken(connection->inputChannel);

        mLock.lock();
    }
}

void InputDispatcher::doNotifyANRLockedInterruptible(
        CommandEntry* commandEntry) {
    mLock.unlock();

    nsecs_t newTimeout = mPolicy->notifyANR(
            commandEntry->inputApplicationHandle, commandEntry->inputChannel);

    mLock.lock();

    resumeAfterTargetsNotReadyTimeoutLocked(newTimeout, commandEntry->inputChannel);
}

void InputDispatcher::doInterceptKeyBeforeDispatchingLockedInterruptible(
        CommandEntry* commandEntry) {
    KeyEntry* entry = commandEntry->keyEntry;
    mReusableKeyEvent.initialize(entry->deviceId, entry->source, entry->action, entry->flags,
            entry->keyCode, entry->scanCode, entry->metaState, entry->repeatCount,
            entry->downTime, entry->eventTime);

    mLock.unlock();

    bool consumed = mPolicy->interceptKeyBeforeDispatching(commandEntry->inputChannel,
            & mReusableKeyEvent, entry->policyFlags);

    mLock.lock();

    entry->interceptKeyResult = consumed
            ? KeyEntry::INTERCEPT_KEY_RESULT_SKIP
            : KeyEntry::INTERCEPT_KEY_RESULT_CONTINUE;
    mAllocator.releaseKeyEntry(entry);
}

void InputDispatcher::doPokeUserActivityLockedInterruptible(CommandEntry* commandEntry) {
    mLock.unlock();

    mPolicy->pokeUserActivity(commandEntry->eventTime, commandEntry->userActivityEventType);

    mLock.lock();
}

void InputDispatcher::updateDispatchStatisticsLocked(nsecs_t currentTime, const EventEntry* entry,
        int32_t injectionResult, nsecs_t timeSpentWaitingForApplication) {
    // TODO Write some statistics about how long we spend waiting.
}

void InputDispatcher::dump(String8& dump) {
    dump.append("Input Dispatcher State:\n");
    dumpDispatchStateLocked(dump);
}


// --- InputDispatcher::Queue ---

template <typename T>
uint32_t InputDispatcher::Queue<T>::count() const {
    uint32_t result = 0;
    for (const T* entry = headSentinel.next; entry != & tailSentinel; entry = entry->next) {
        result += 1;
    }
    return result;
}


// --- InputDispatcher::Allocator ---

InputDispatcher::Allocator::Allocator() {
}

InputDispatcher::InjectionState*
InputDispatcher::Allocator::obtainInjectionState(int32_t injectorPid, int32_t injectorUid) {
    InjectionState* injectionState = mInjectionStatePool.alloc();
    injectionState->refCount = 1;
    injectionState->injectorPid = injectorPid;
    injectionState->injectorUid = injectorUid;
    injectionState->injectionIsAsync = false;
    injectionState->injectionResult = INPUT_EVENT_INJECTION_PENDING;
    injectionState->pendingForegroundDispatches = 0;
    return injectionState;
}

void InputDispatcher::Allocator::initializeEventEntry(EventEntry* entry, int32_t type,
        nsecs_t eventTime, uint32_t policyFlags) {
    entry->type = type;
    entry->refCount = 1;
    entry->dispatchInProgress = false;
    entry->eventTime = eventTime;
    entry->policyFlags = policyFlags;
    entry->injectionState = NULL;
}

void InputDispatcher::Allocator::releaseEventEntryInjectionState(EventEntry* entry) {
    if (entry->injectionState) {
        releaseInjectionState(entry->injectionState);
        entry->injectionState = NULL;
    }
}

InputDispatcher::ConfigurationChangedEntry*
InputDispatcher::Allocator::obtainConfigurationChangedEntry(nsecs_t eventTime) {
    ConfigurationChangedEntry* entry = mConfigurationChangeEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_CONFIGURATION_CHANGED, eventTime, 0);
    return entry;
}

InputDispatcher::KeyEntry* InputDispatcher::Allocator::obtainKeyEntry(nsecs_t eventTime,
        int32_t deviceId, int32_t source, uint32_t policyFlags, int32_t action,
        int32_t flags, int32_t keyCode, int32_t scanCode, int32_t metaState,
        int32_t repeatCount, nsecs_t downTime) {
    KeyEntry* entry = mKeyEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_KEY, eventTime, policyFlags);

    entry->deviceId = deviceId;
    entry->source = source;
    entry->action = action;
    entry->flags = flags;
    entry->keyCode = keyCode;
    entry->scanCode = scanCode;
    entry->metaState = metaState;
    entry->repeatCount = repeatCount;
    entry->downTime = downTime;
    entry->syntheticRepeat = false;
    entry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN;
    return entry;
}

InputDispatcher::MotionEntry* InputDispatcher::Allocator::obtainMotionEntry(nsecs_t eventTime,
        int32_t deviceId, int32_t source, uint32_t policyFlags, int32_t action, int32_t flags,
        int32_t metaState, int32_t edgeFlags, float xPrecision, float yPrecision,
        nsecs_t downTime, uint32_t pointerCount,
        const int32_t* pointerIds, const PointerCoords* pointerCoords) {
    MotionEntry* entry = mMotionEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_MOTION, eventTime, policyFlags);

    entry->eventTime = eventTime;
    entry->deviceId = deviceId;
    entry->source = source;
    entry->action = action;
    entry->flags = flags;
    entry->metaState = metaState;
    entry->edgeFlags = edgeFlags;
    entry->xPrecision = xPrecision;
    entry->yPrecision = yPrecision;
    entry->downTime = downTime;
    entry->pointerCount = pointerCount;
    entry->firstSample.eventTime = eventTime;
    entry->firstSample.next = NULL;
    entry->lastSample = & entry->firstSample;
    for (uint32_t i = 0; i < pointerCount; i++) {
        entry->pointerIds[i] = pointerIds[i];
        entry->firstSample.pointerCoords[i] = pointerCoords[i];
    }
    return entry;
}

InputDispatcher::DispatchEntry* InputDispatcher::Allocator::obtainDispatchEntry(
        EventEntry* eventEntry,
        int32_t targetFlags, float xOffset, float yOffset) {
    DispatchEntry* entry = mDispatchEntryPool.alloc();
    entry->eventEntry = eventEntry;
    eventEntry->refCount += 1;
    entry->targetFlags = targetFlags;
    entry->xOffset = xOffset;
    entry->yOffset = yOffset;
    entry->inProgress = false;
    entry->headMotionSample = NULL;
    entry->tailMotionSample = NULL;
    return entry;
}

InputDispatcher::CommandEntry* InputDispatcher::Allocator::obtainCommandEntry(Command command) {
    CommandEntry* entry = mCommandEntryPool.alloc();
    entry->command = command;
    return entry;
}

void InputDispatcher::Allocator::releaseInjectionState(InjectionState* injectionState) {
    injectionState->refCount -= 1;
    if (injectionState->refCount == 0) {
        mInjectionStatePool.free(injectionState);
    } else {
        assert(injectionState->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseEventEntry(EventEntry* entry) {
    switch (entry->type) {
    case EventEntry::TYPE_CONFIGURATION_CHANGED:
        releaseConfigurationChangedEntry(static_cast<ConfigurationChangedEntry*>(entry));
        break;
    case EventEntry::TYPE_KEY:
        releaseKeyEntry(static_cast<KeyEntry*>(entry));
        break;
    case EventEntry::TYPE_MOTION:
        releaseMotionEntry(static_cast<MotionEntry*>(entry));
        break;
    default:
        assert(false);
        break;
    }
}

void InputDispatcher::Allocator::releaseConfigurationChangedEntry(
        ConfigurationChangedEntry* entry) {
    entry->refCount -= 1;
    if (entry->refCount == 0) {
        releaseEventEntryInjectionState(entry);
        mConfigurationChangeEntryPool.free(entry);
    } else {
        assert(entry->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseKeyEntry(KeyEntry* entry) {
    entry->refCount -= 1;
    if (entry->refCount == 0) {
        releaseEventEntryInjectionState(entry);
        mKeyEntryPool.free(entry);
    } else {
        assert(entry->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseMotionEntry(MotionEntry* entry) {
    entry->refCount -= 1;
    if (entry->refCount == 0) {
        releaseEventEntryInjectionState(entry);
        for (MotionSample* sample = entry->firstSample.next; sample != NULL; ) {
            MotionSample* next = sample->next;
            mMotionSamplePool.free(sample);
            sample = next;
        }
        mMotionEntryPool.free(entry);
    } else {
        assert(entry->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseDispatchEntry(DispatchEntry* entry) {
    releaseEventEntry(entry->eventEntry);
    mDispatchEntryPool.free(entry);
}

void InputDispatcher::Allocator::releaseCommandEntry(CommandEntry* entry) {
    mCommandEntryPool.free(entry);
}

void InputDispatcher::Allocator::appendMotionSample(MotionEntry* motionEntry,
        nsecs_t eventTime, const PointerCoords* pointerCoords) {
    MotionSample* sample = mMotionSamplePool.alloc();
    sample->eventTime = eventTime;
    uint32_t pointerCount = motionEntry->pointerCount;
    for (uint32_t i = 0; i < pointerCount; i++) {
        sample->pointerCoords[i] = pointerCoords[i];
    }

    sample->next = NULL;
    motionEntry->lastSample->next = sample;
    motionEntry->lastSample = sample;
}

void InputDispatcher::Allocator::recycleKeyEntry(KeyEntry* keyEntry) {
    releaseEventEntryInjectionState(keyEntry);

    keyEntry->dispatchInProgress = false;
    keyEntry->syntheticRepeat = false;
    keyEntry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN;
}


// --- InputDispatcher::MotionEntry ---

uint32_t InputDispatcher::MotionEntry::countSamples() const {
    uint32_t count = 1;
    for (MotionSample* sample = firstSample.next; sample != NULL; sample = sample->next) {
        count += 1;
    }
    return count;
}


// --- InputDispatcher::InputState ---

InputDispatcher::InputState::InputState() {
}

InputDispatcher::InputState::~InputState() {
}

bool InputDispatcher::InputState::isNeutral() const {
    return mKeyMementos.isEmpty() && mMotionMementos.isEmpty();
}

InputDispatcher::InputState::Consistency InputDispatcher::InputState::trackEvent(
        const EventEntry* entry) {
    switch (entry->type) {
    case EventEntry::TYPE_KEY:
        return trackKey(static_cast<const KeyEntry*>(entry));

    case EventEntry::TYPE_MOTION:
        return trackMotion(static_cast<const MotionEntry*>(entry));

    default:
        return CONSISTENT;
    }
}

InputDispatcher::InputState::Consistency InputDispatcher::InputState::trackKey(
        const KeyEntry* entry) {
    int32_t action = entry->action;
    for (size_t i = 0; i < mKeyMementos.size(); i++) {
        KeyMemento& memento = mKeyMementos.editItemAt(i);
        if (memento.deviceId == entry->deviceId
                && memento.source == entry->source
                && memento.keyCode == entry->keyCode
                && memento.scanCode == entry->scanCode) {
            switch (action) {
            case AKEY_EVENT_ACTION_UP:
                mKeyMementos.removeAt(i);
                return CONSISTENT;

            case AKEY_EVENT_ACTION_DOWN:
                return TOLERABLE;

            default:
                return BROKEN;
            }
        }
    }

    switch (action) {
    case AKEY_EVENT_ACTION_DOWN: {
        mKeyMementos.push();
        KeyMemento& memento = mKeyMementos.editTop();
        memento.deviceId = entry->deviceId;
        memento.source = entry->source;
        memento.keyCode = entry->keyCode;
        memento.scanCode = entry->scanCode;
        memento.downTime = entry->downTime;
        return CONSISTENT;
    }

    default:
        return BROKEN;
    }
}

InputDispatcher::InputState::Consistency InputDispatcher::InputState::trackMotion(
        const MotionEntry* entry) {
    int32_t action = entry->action & AMOTION_EVENT_ACTION_MASK;
    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        MotionMemento& memento = mMotionMementos.editItemAt(i);
        if (memento.deviceId == entry->deviceId
                && memento.source == entry->source) {
            switch (action) {
            case AMOTION_EVENT_ACTION_UP:
            case AMOTION_EVENT_ACTION_CANCEL:
                mMotionMementos.removeAt(i);
                return CONSISTENT;

            case AMOTION_EVENT_ACTION_DOWN:
                return TOLERABLE;

            case AMOTION_EVENT_ACTION_POINTER_DOWN:
                if (entry->pointerCount == memento.pointerCount + 1) {
                    memento.setPointers(entry);
                    return CONSISTENT;
                }
                return BROKEN;

            case AMOTION_EVENT_ACTION_POINTER_UP:
                if (entry->pointerCount == memento.pointerCount - 1) {
                    memento.setPointers(entry);
                    return CONSISTENT;
                }
                return BROKEN;

            case AMOTION_EVENT_ACTION_MOVE:
                if (entry->pointerCount == memento.pointerCount) {
                    return CONSISTENT;
                }
                return BROKEN;

            default:
                return BROKEN;
            }
        }
    }

    switch (action) {
    case AMOTION_EVENT_ACTION_DOWN: {
        mMotionMementos.push();
        MotionMemento& memento = mMotionMementos.editTop();
        memento.deviceId = entry->deviceId;
        memento.source = entry->source;
        memento.xPrecision = entry->xPrecision;
        memento.yPrecision = entry->yPrecision;
        memento.downTime = entry->downTime;
        memento.setPointers(entry);
        return CONSISTENT;
    }

    default:
        return BROKEN;
    }
}

void InputDispatcher::InputState::MotionMemento::setPointers(const MotionEntry* entry) {
    pointerCount = entry->pointerCount;
    for (uint32_t i = 0; i < entry->pointerCount; i++) {
        pointerIds[i] = entry->pointerIds[i];
        pointerCoords[i] = entry->lastSample->pointerCoords[i];
    }
}

void InputDispatcher::InputState::synthesizeCancelationEvents(nsecs_t currentTime,
        Allocator* allocator, Vector<EventEntry*>& outEvents,
        CancelationOptions options) {
    for (size_t i = 0; i < mKeyMementos.size(); ) {
        const KeyMemento& memento = mKeyMementos.itemAt(i);
        if (shouldCancelEvent(memento.source, options)) {
            outEvents.push(allocator->obtainKeyEntry(currentTime,
                    memento.deviceId, memento.source, 0,
                    AKEY_EVENT_ACTION_UP, AKEY_EVENT_FLAG_CANCELED,
                    memento.keyCode, memento.scanCode, 0, 0, memento.downTime));
            mKeyMementos.removeAt(i);
        } else {
            i += 1;
        }
    }

    for (size_t i = 0; i < mMotionMementos.size(); ) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (shouldCancelEvent(memento.source, options)) {
            outEvents.push(allocator->obtainMotionEntry(currentTime,
                    memento.deviceId, memento.source, 0,
                    AMOTION_EVENT_ACTION_CANCEL, 0, 0, 0,
                    memento.xPrecision, memento.yPrecision, memento.downTime,
                    memento.pointerCount, memento.pointerIds, memento.pointerCoords));
            mMotionMementos.removeAt(i);
        } else {
            i += 1;
        }
    }
}

void InputDispatcher::InputState::clear() {
    mKeyMementos.clear();
    mMotionMementos.clear();
}

bool InputDispatcher::InputState::shouldCancelEvent(int32_t eventSource,
        CancelationOptions options) {
    switch (options) {
    case CANCEL_POINTER_EVENTS:
        return eventSource & AINPUT_SOURCE_CLASS_POINTER;
    case CANCEL_NON_POINTER_EVENTS:
        return !(eventSource & AINPUT_SOURCE_CLASS_POINTER);
    default:
        return true;
    }
}


// --- InputDispatcher::Connection ---

InputDispatcher::Connection::Connection(const sp<InputChannel>& inputChannel) :
        status(STATUS_NORMAL), inputChannel(inputChannel), inputPublisher(inputChannel),
        lastEventTime(LONG_LONG_MAX), lastDispatchTime(LONG_LONG_MAX) {
}

InputDispatcher::Connection::~Connection() {
}

status_t InputDispatcher::Connection::initialize() {
    return inputPublisher.initialize();
}

const char* InputDispatcher::Connection::getStatusLabel() const {
    switch (status) {
    case STATUS_NORMAL:
        return "NORMAL";

    case STATUS_BROKEN:
        return "BROKEN";

    case STATUS_ZOMBIE:
        return "ZOMBIE";

    default:
        return "UNKNOWN";
    }
}

InputDispatcher::DispatchEntry* InputDispatcher::Connection::findQueuedDispatchEntryForEvent(
        const EventEntry* eventEntry) const {
    for (DispatchEntry* dispatchEntry = outboundQueue.tailSentinel.prev;
            dispatchEntry != & outboundQueue.headSentinel; dispatchEntry = dispatchEntry->prev) {
        if (dispatchEntry->eventEntry == eventEntry) {
            return dispatchEntry;
        }
    }
    return NULL;
}


// --- InputDispatcher::CommandEntry ---

InputDispatcher::CommandEntry::CommandEntry() :
    keyEntry(NULL) {
}

InputDispatcher::CommandEntry::~CommandEntry() {
}


// --- InputDispatcher::TouchState ---

InputDispatcher::TouchState::TouchState() :
    down(false), split(false) {
}

InputDispatcher::TouchState::~TouchState() {
}

void InputDispatcher::TouchState::reset() {
    down = false;
    split = false;
    windows.clear();
}

void InputDispatcher::TouchState::copyFrom(const TouchState& other) {
    down = other.down;
    split = other.split;
    windows.clear();
    windows.appendVector(other.windows);
}

void InputDispatcher::TouchState::addOrUpdateWindow(const InputWindow* window,
        int32_t targetFlags, BitSet32 pointerIds) {
    if (targetFlags & InputTarget::FLAG_SPLIT) {
        split = true;
    }

    for (size_t i = 0; i < windows.size(); i++) {
        TouchedWindow& touchedWindow = windows.editItemAt(i);
        if (touchedWindow.window == window) {
            touchedWindow.targetFlags |= targetFlags;
            touchedWindow.pointerIds.value |= pointerIds.value;
            return;
        }
    }

    windows.push();

    TouchedWindow& touchedWindow = windows.editTop();
    touchedWindow.window = window;
    touchedWindow.targetFlags = targetFlags;
    touchedWindow.pointerIds = pointerIds;
    touchedWindow.channel = window->inputChannel;
}

void InputDispatcher::TouchState::removeOutsideTouchWindows() {
    for (size_t i = 0 ; i < windows.size(); ) {
        if (windows[i].targetFlags & InputTarget::FLAG_OUTSIDE) {
            windows.removeAt(i);
        } else {
            i += 1;
        }
    }
}

const InputWindow* InputDispatcher::TouchState::getFirstForegroundWindow() {
    for (size_t i = 0; i < windows.size(); i++) {
        if (windows[i].targetFlags & InputTarget::FLAG_FOREGROUND) {
            return windows[i].window;
        }
    }
    return NULL;
}


// --- InputDispatcherThread ---

InputDispatcherThread::InputDispatcherThread(const sp<InputDispatcherInterface>& dispatcher) :
        Thread(/*canCallJava*/ true), mDispatcher(dispatcher) {
}

InputDispatcherThread::~InputDispatcherThread() {
}

bool InputDispatcherThread::threadLoop() {
    mDispatcher->dispatchOnce();
    return true;
}

} // namespace android
