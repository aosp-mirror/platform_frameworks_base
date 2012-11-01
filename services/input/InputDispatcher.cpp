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

#define LOG_TAG "InputDispatcher"
#define ATRACE_TAG ATRACE_TAG_INPUT

//#define LOG_NDEBUG 0

// Log detailed debug messages about each inbound event notification to the dispatcher.
#define DEBUG_INBOUND_EVENT_DETAILS 0

// Log detailed debug messages about each outbound event processed by the dispatcher.
#define DEBUG_OUTBOUND_EVENT_DETAILS 0

// Log debug messages about the dispatch cycle.
#define DEBUG_DISPATCH_CYCLE 0

// Log debug messages about registrations.
#define DEBUG_REGISTRATION 0

// Log debug messages about input event injection.
#define DEBUG_INJECTION 0

// Log debug messages about input focus tracking.
#define DEBUG_FOCUS 0

// Log debug messages about the app switch latency optimization.
#define DEBUG_APP_SWITCH 0

// Log debug messages about hover events.
#define DEBUG_HOVER 0

#include "InputDispatcher.h"

#include <utils/Trace.h>
#include <cutils/log.h>
#include <androidfw/PowerManager.h>

#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>
#include <time.h>

#define INDENT "  "
#define INDENT2 "    "
#define INDENT3 "      "
#define INDENT4 "        "

namespace android {

// Default input dispatching timeout if there is no focused application or paused window
// from which to determine an appropriate dispatching timeout.
const nsecs_t DEFAULT_INPUT_DISPATCHING_TIMEOUT = 5000 * 1000000LL; // 5 sec

// Amount of time to allow for all pending events to be processed when an app switch
// key is on the way.  This is used to preempt input dispatch and drop input events
// when an application takes too long to respond and the user has pressed an app switch key.
const nsecs_t APP_SWITCH_TIMEOUT = 500 * 1000000LL; // 0.5sec

// Amount of time to allow for an event to be dispatched (measured since its eventTime)
// before considering it stale and dropping it.
const nsecs_t STALE_EVENT_TIMEOUT = 10000 * 1000000LL; // 10sec

// Amount of time to allow touch events to be streamed out to a connection before requiring
// that the first event be finished.  This value extends the ANR timeout by the specified
// amount.  For example, if streaming is allowed to get ahead by one second relative to the
// queue of waiting unfinished events, then ANRs will similarly be delayed by one second.
const nsecs_t STREAM_AHEAD_EVENT_TIMEOUT = 500 * 1000000LL; // 0.5sec

// Log a warning when an event takes longer than this to process, even if an ANR does not occur.
const nsecs_t SLOW_EVENT_PROCESSING_WARNING_TIMEOUT = 2000 * 1000000LL; // 2sec


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
        ALOGE("Key event has invalid action code 0x%x", action);
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
    case AMOTION_EVENT_ACTION_HOVER_ENTER:
    case AMOTION_EVENT_ACTION_HOVER_MOVE:
    case AMOTION_EVENT_ACTION_HOVER_EXIT:
    case AMOTION_EVENT_ACTION_SCROLL:
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
        const PointerProperties* pointerProperties) {
    if (! isValidMotionAction(action, pointerCount)) {
        ALOGE("Motion event has invalid action code 0x%x", action);
        return false;
    }
    if (pointerCount < 1 || pointerCount > MAX_POINTERS) {
        ALOGE("Motion event has invalid pointer count %d; value must be between 1 and %d.",
                pointerCount, MAX_POINTERS);
        return false;
    }
    BitSet32 pointerIdBits;
    for (size_t i = 0; i < pointerCount; i++) {
        int32_t id = pointerProperties[i].id;
        if (id < 0 || id > MAX_POINTER_ID) {
            ALOGE("Motion event has invalid pointer id %d; value must be between 0 and %d",
                    id, MAX_POINTER_ID);
            return false;
        }
        if (pointerIdBits.hasBit(id)) {
            ALOGE("Motion event has duplicate pointer id %d", id);
            return false;
        }
        pointerIdBits.markBit(id);
    }
    return true;
}

static bool isMainDisplay(int32_t displayId) {
    return displayId == ADISPLAY_ID_DEFAULT || displayId == ADISPLAY_ID_NONE;
}

static void dumpRegion(String8& dump, const SkRegion& region) {
    if (region.isEmpty()) {
        dump.append("<empty>");
        return;
    }

    bool first = true;
    for (SkRegion::Iterator it(region); !it.done(); it.next()) {
        if (first) {
            first = false;
        } else {
            dump.append("|");
        }
        const SkIRect& rect = it.rect();
        dump.appendFormat("[%d,%d][%d,%d]", rect.fLeft, rect.fTop, rect.fRight, rect.fBottom);
    }
}


// --- InputDispatcher ---

InputDispatcher::InputDispatcher(const sp<InputDispatcherPolicyInterface>& policy) :
    mPolicy(policy),
    mPendingEvent(NULL), mAppSwitchSawKeyDown(false), mAppSwitchDueTime(LONG_LONG_MAX),
    mNextUnblockedEvent(NULL),
    mDispatchEnabled(false), mDispatchFrozen(false), mInputFilterEnabled(false),
    mInputTargetWaitCause(INPUT_TARGET_WAIT_CAUSE_NONE) {
    mLooper = new Looper(false);

    mKeyRepeatState.lastKeyEntry = NULL;

    policy->getDispatcherConfiguration(&mConfig);
}

InputDispatcher::~InputDispatcher() {
    { // acquire lock
        AutoMutex _l(mLock);

        resetKeyRepeatLocked();
        releasePendingEventLocked();
        drainInboundQueueLocked();
    }

    while (mConnectionsByFd.size() != 0) {
        unregisterInputChannel(mConnectionsByFd.valueAt(0)->inputChannel);
    }
}

void InputDispatcher::dispatchOnce() {
    nsecs_t nextWakeupTime = LONG_LONG_MAX;
    { // acquire lock
        AutoMutex _l(mLock);
        mDispatcherIsAliveCondition.broadcast();

        // Run a dispatch loop if there are no pending commands.
        // The dispatch loop might enqueue commands to run afterwards.
        if (!haveCommandsLocked()) {
            dispatchOnceInnerLocked(&nextWakeupTime);
        }

        // Run all pending commands if there are any.
        // If any commands were run then force the next poll to wake up immediately.
        if (runCommandsLockedInterruptible()) {
            nextWakeupTime = LONG_LONG_MIN;
        }
    } // release lock

    // Wait for callback or timeout or wake.  (make sure we round up, not down)
    nsecs_t currentTime = now();
    int timeoutMillis = toMillisecondTimeoutDelay(currentTime, nextWakeupTime);
    mLooper->pollOnce(timeoutMillis);
}

void InputDispatcher::dispatchOnceInnerLocked(nsecs_t* nextWakeupTime) {
    nsecs_t currentTime = now();

    // Reset the key repeat timer whenever we disallow key events, even if the next event
    // is not a key.  This is to ensure that we abort a key repeat if the device is just coming
    // out of sleep.
    if (!mPolicy->isKeyRepeatEnabled()) {
        resetKeyRepeatLocked();
    }

    // If dispatching is frozen, do not process timeouts or try to deliver any new events.
    if (mDispatchFrozen) {
#if DEBUG_FOCUS
        ALOGD("Dispatch frozen.  Waiting some more.");
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
                    mPendingEvent = synthesizeKeyRepeatLocked(currentTime);
                } else {
                    if (mKeyRepeatState.nextRepeatTime < *nextWakeupTime) {
                        *nextWakeupTime = mKeyRepeatState.nextRepeatTime;
                    }
                }
            }

            // Nothing to do if there is no pending event.
            if (!mPendingEvent) {
                return;
            }
        } else {
            // Inbound queue has at least one entry.
            mPendingEvent = mInboundQueue.dequeueAtHead();
            traceInboundQueueLengthLocked();
        }

        // Poke user activity for this event.
        if (mPendingEvent->policyFlags & POLICY_FLAG_PASS_TO_USER) {
            pokeUserActivityLocked(mPendingEvent);
        }

        // Get ready to dispatch the event.
        resetANRTimeoutsLocked();
    }

    // Now we have an event to dispatch.
    // All events are eventually dequeued and processed this way, even if we intend to drop them.
    ALOG_ASSERT(mPendingEvent != NULL);
    bool done = false;
    DropReason dropReason = DROP_REASON_NOT_DROPPED;
    if (!(mPendingEvent->policyFlags & POLICY_FLAG_PASS_TO_USER)) {
        dropReason = DROP_REASON_POLICY;
    } else if (!mDispatchEnabled) {
        dropReason = DROP_REASON_DISABLED;
    }

    if (mNextUnblockedEvent == mPendingEvent) {
        mNextUnblockedEvent = NULL;
    }

    switch (mPendingEvent->type) {
    case EventEntry::TYPE_CONFIGURATION_CHANGED: {
        ConfigurationChangedEntry* typedEntry =
                static_cast<ConfigurationChangedEntry*>(mPendingEvent);
        done = dispatchConfigurationChangedLocked(currentTime, typedEntry);
        dropReason = DROP_REASON_NOT_DROPPED; // configuration changes are never dropped
        break;
    }

    case EventEntry::TYPE_DEVICE_RESET: {
        DeviceResetEntry* typedEntry =
                static_cast<DeviceResetEntry*>(mPendingEvent);
        done = dispatchDeviceResetLocked(currentTime, typedEntry);
        dropReason = DROP_REASON_NOT_DROPPED; // device resets are never dropped
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
        if (dropReason == DROP_REASON_NOT_DROPPED
                && isStaleEventLocked(currentTime, typedEntry)) {
            dropReason = DROP_REASON_STALE;
        }
        if (dropReason == DROP_REASON_NOT_DROPPED && mNextUnblockedEvent) {
            dropReason = DROP_REASON_BLOCKED;
        }
        done = dispatchKeyLocked(currentTime, typedEntry, &dropReason, nextWakeupTime);
        break;
    }

    case EventEntry::TYPE_MOTION: {
        MotionEntry* typedEntry = static_cast<MotionEntry*>(mPendingEvent);
        if (dropReason == DROP_REASON_NOT_DROPPED && isAppSwitchDue) {
            dropReason = DROP_REASON_APP_SWITCH;
        }
        if (dropReason == DROP_REASON_NOT_DROPPED
                && isStaleEventLocked(currentTime, typedEntry)) {
            dropReason = DROP_REASON_STALE;
        }
        if (dropReason == DROP_REASON_NOT_DROPPED && mNextUnblockedEvent) {
            dropReason = DROP_REASON_BLOCKED;
        }
        done = dispatchMotionLocked(currentTime, typedEntry,
                &dropReason, nextWakeupTime);
        break;
    }

    default:
        ALOG_ASSERT(false);
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
    traceInboundQueueLengthLocked();

    switch (entry->type) {
    case EventEntry::TYPE_KEY: {
        // Optimize app switch latency.
        // If the application takes too long to catch up then we drop all events preceding
        // the app switch key.
        KeyEntry* keyEntry = static_cast<KeyEntry*>(entry);
        if (isAppSwitchKeyEventLocked(keyEntry)) {
            if (keyEntry->action == AKEY_EVENT_ACTION_DOWN) {
                mAppSwitchSawKeyDown = true;
            } else if (keyEntry->action == AKEY_EVENT_ACTION_UP) {
                if (mAppSwitchSawKeyDown) {
#if DEBUG_APP_SWITCH
                    ALOGD("App switch is pending!");
#endif
                    mAppSwitchDueTime = keyEntry->eventTime + APP_SWITCH_TIMEOUT;
                    mAppSwitchSawKeyDown = false;
                    needWake = true;
                }
            }
        }
        break;
    }

    case EventEntry::TYPE_MOTION: {
        // Optimize case where the current application is unresponsive and the user
        // decides to touch a window in a different application.
        // If the application takes too long to catch up then we drop all events preceding
        // the touch into the other window.
        MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
        if (motionEntry->action == AMOTION_EVENT_ACTION_DOWN
                && (motionEntry->source & AINPUT_SOURCE_CLASS_POINTER)
                && mInputTargetWaitCause == INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY
                && mInputTargetWaitApplicationHandle != NULL) {
            int32_t displayId = motionEntry->displayId;
            int32_t x = int32_t(motionEntry->pointerCoords[0].
                    getAxisValue(AMOTION_EVENT_AXIS_X));
            int32_t y = int32_t(motionEntry->pointerCoords[0].
                    getAxisValue(AMOTION_EVENT_AXIS_Y));
            sp<InputWindowHandle> touchedWindowHandle = findTouchedWindowAtLocked(displayId, x, y);
            if (touchedWindowHandle != NULL
                    && touchedWindowHandle->inputApplicationHandle
                            != mInputTargetWaitApplicationHandle) {
                // User touched a different application than the one we are waiting on.
                // Flag the event, and start pruning the input queue.
                mNextUnblockedEvent = motionEntry;
                needWake = true;
            }
        }
        break;
    }
    }

    return needWake;
}

sp<InputWindowHandle> InputDispatcher::findTouchedWindowAtLocked(int32_t displayId,
        int32_t x, int32_t y) {
    // Traverse windows from front to back to find touched window.
    size_t numWindows = mWindowHandles.size();
    for (size_t i = 0; i < numWindows; i++) {
        sp<InputWindowHandle> windowHandle = mWindowHandles.itemAt(i);
        const InputWindowInfo* windowInfo = windowHandle->getInfo();
        if (windowInfo->displayId == displayId) {
            int32_t flags = windowInfo->layoutParamsFlags;

            if (windowInfo->visible) {
                if (!(flags & InputWindowInfo::FLAG_NOT_TOUCHABLE)) {
                    bool isTouchModal = (flags & (InputWindowInfo::FLAG_NOT_FOCUSABLE
                            | InputWindowInfo::FLAG_NOT_TOUCH_MODAL)) == 0;
                    if (isTouchModal || windowInfo->touchableRegionContainsPoint(x, y)) {
                        // Found window.
                        return windowHandle;
                    }
                }
            }

            if (flags & InputWindowInfo::FLAG_SYSTEM_ERROR) {
                // Error window is on top but not visible, so touch is dropped.
                return NULL;
            }
        }
    }
    return NULL;
}

void InputDispatcher::dropInboundEventLocked(EventEntry* entry, DropReason dropReason) {
    const char* reason;
    switch (dropReason) {
    case DROP_REASON_POLICY:
#if DEBUG_INBOUND_EVENT_DETAILS
        ALOGD("Dropped event because policy consumed it.");
#endif
        reason = "inbound event was dropped because the policy consumed it";
        break;
    case DROP_REASON_DISABLED:
        ALOGI("Dropped event because input dispatch is disabled.");
        reason = "inbound event was dropped because input dispatch is disabled";
        break;
    case DROP_REASON_APP_SWITCH:
        ALOGI("Dropped event because of pending overdue app switch.");
        reason = "inbound event was dropped because of pending overdue app switch";
        break;
    case DROP_REASON_BLOCKED:
        ALOGI("Dropped event because the current application is not responding and the user "
                "has started interacting with a different application.");
        reason = "inbound event was dropped because the current application is not responding "
                "and the user has started interacting with a different application";
        break;
    case DROP_REASON_STALE:
        ALOGI("Dropped event because it is stale.");
        reason = "inbound event was dropped because it is stale";
        break;
    default:
        ALOG_ASSERT(false);
        return;
    }

    switch (entry->type) {
    case EventEntry::TYPE_KEY: {
        CancelationOptions options(CancelationOptions::CANCEL_NON_POINTER_EVENTS, reason);
        synthesizeCancelationEventsForAllConnectionsLocked(options);
        break;
    }
    case EventEntry::TYPE_MOTION: {
        MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
        if (motionEntry->source & AINPUT_SOURCE_CLASS_POINTER) {
            CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS, reason);
            synthesizeCancelationEventsForAllConnectionsLocked(options);
        } else {
            CancelationOptions options(CancelationOptions::CANCEL_NON_POINTER_EVENTS, reason);
            synthesizeCancelationEventsForAllConnectionsLocked(options);
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
        ALOGD("App switch has arrived.");
    } else {
        ALOGD("App switch was abandoned.");
    }
#endif
}

bool InputDispatcher::isStaleEventLocked(nsecs_t currentTime, EventEntry* entry) {
    return currentTime - entry->eventTime >= STALE_EVENT_TIMEOUT;
}

bool InputDispatcher::haveCommandsLocked() const {
    return !mCommandQueue.isEmpty();
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
        delete commandEntry;
    } while (! mCommandQueue.isEmpty());
    return true;
}

InputDispatcher::CommandEntry* InputDispatcher::postCommandLocked(Command command) {
    CommandEntry* commandEntry = new CommandEntry(command);
    mCommandQueue.enqueueAtTail(commandEntry);
    return commandEntry;
}

void InputDispatcher::drainInboundQueueLocked() {
    while (! mInboundQueue.isEmpty()) {
        EventEntry* entry = mInboundQueue.dequeueAtHead();
        releaseInboundEventLocked(entry);
    }
    traceInboundQueueLengthLocked();
}

void InputDispatcher::releasePendingEventLocked() {
    if (mPendingEvent) {
        resetANRTimeoutsLocked();
        releaseInboundEventLocked(mPendingEvent);
        mPendingEvent = NULL;
    }
}

void InputDispatcher::releaseInboundEventLocked(EventEntry* entry) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState && injectionState->injectionResult == INPUT_EVENT_INJECTION_PENDING) {
#if DEBUG_DISPATCH_CYCLE
        ALOGD("Injected inbound event was dropped.");
#endif
        setInjectionResultLocked(entry, INPUT_EVENT_INJECTION_FAILED);
    }
    if (entry == mNextUnblockedEvent) {
        mNextUnblockedEvent = NULL;
    }
    entry->release();
}

void InputDispatcher::resetKeyRepeatLocked() {
    if (mKeyRepeatState.lastKeyEntry) {
        mKeyRepeatState.lastKeyEntry->release();
        mKeyRepeatState.lastKeyEntry = NULL;
    }
}

InputDispatcher::KeyEntry* InputDispatcher::synthesizeKeyRepeatLocked(nsecs_t currentTime) {
    KeyEntry* entry = mKeyRepeatState.lastKeyEntry;

    // Reuse the repeated key entry if it is otherwise unreferenced.
    uint32_t policyFlags = (entry->policyFlags & POLICY_FLAG_RAW_MASK)
            | POLICY_FLAG_PASS_TO_USER | POLICY_FLAG_TRUSTED;
    if (entry->refCount == 1) {
        entry->recycle();
        entry->eventTime = currentTime;
        entry->policyFlags = policyFlags;
        entry->repeatCount += 1;
    } else {
        KeyEntry* newEntry = new KeyEntry(currentTime,
                entry->deviceId, entry->source, policyFlags,
                entry->action, entry->flags, entry->keyCode, entry->scanCode,
                entry->metaState, entry->repeatCount + 1, entry->downTime);

        mKeyRepeatState.lastKeyEntry = newEntry;
        entry->release();

        entry = newEntry;
    }
    entry->syntheticRepeat = true;

    // Increment reference count since we keep a reference to the event in
    // mKeyRepeatState.lastKeyEntry in addition to the one we return.
    entry->refCount += 1;

    mKeyRepeatState.nextRepeatTime = currentTime + mConfig.keyRepeatDelay;
    return entry;
}

bool InputDispatcher::dispatchConfigurationChangedLocked(
        nsecs_t currentTime, ConfigurationChangedEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    ALOGD("dispatchConfigurationChanged - eventTime=%lld", entry->eventTime);
#endif

    // Reset key repeating in case a keyboard device was added or removed or something.
    resetKeyRepeatLocked();

    // Enqueue a command to run outside the lock to tell the policy that the configuration changed.
    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyConfigurationChangedInterruptible);
    commandEntry->eventTime = entry->eventTime;
    return true;
}

bool InputDispatcher::dispatchDeviceResetLocked(
        nsecs_t currentTime, DeviceResetEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    ALOGD("dispatchDeviceReset - eventTime=%lld, deviceId=%d", entry->eventTime, entry->deviceId);
#endif

    CancelationOptions options(CancelationOptions::CANCEL_ALL_EVENTS,
            "device was reset");
    options.deviceId = entry->deviceId;
    synthesizeCancelationEventsForAllConnectionsLocked(options);
    return true;
}

bool InputDispatcher::dispatchKeyLocked(nsecs_t currentTime, KeyEntry* entry,
        DropReason* dropReason, nsecs_t* nextWakeupTime) {
    // Preprocessing.
    if (! entry->dispatchInProgress) {
        if (entry->repeatCount == 0
                && entry->action == AKEY_EVENT_ACTION_DOWN
                && (entry->policyFlags & POLICY_FLAG_TRUSTED)
                && (!(entry->policyFlags & POLICY_FLAG_DISABLE_KEY_REPEAT))) {
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
                mKeyRepeatState.nextRepeatTime = entry->eventTime + mConfig.keyRepeatTimeout;
            }
            mKeyRepeatState.lastKeyEntry = entry;
            entry->refCount += 1;
        } else if (! entry->syntheticRepeat) {
            resetKeyRepeatLocked();
        }

        if (entry->repeatCount == 1) {
            entry->flags |= AKEY_EVENT_FLAG_LONG_PRESS;
        } else {
            entry->flags &= ~AKEY_EVENT_FLAG_LONG_PRESS;
        }

        entry->dispatchInProgress = true;

        logOutboundKeyDetailsLocked("dispatchKey - ", entry);
    }

    // Handle case where the policy asked us to try again later last time.
    if (entry->interceptKeyResult == KeyEntry::INTERCEPT_KEY_RESULT_TRY_AGAIN_LATER) {
        if (currentTime < entry->interceptKeyWakeupTime) {
            if (entry->interceptKeyWakeupTime < *nextWakeupTime) {
                *nextWakeupTime = entry->interceptKeyWakeupTime;
            }
            return false; // wait until next wakeup
        }
        entry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN;
        entry->interceptKeyWakeupTime = 0;
    }

    // Give the policy a chance to intercept the key.
    if (entry->interceptKeyResult == KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN) {
        if (entry->policyFlags & POLICY_FLAG_PASS_TO_USER) {
            CommandEntry* commandEntry = postCommandLocked(
                    & InputDispatcher::doInterceptKeyBeforeDispatchingLockedInterruptible);
            if (mFocusedWindowHandle != NULL) {
                commandEntry->inputWindowHandle = mFocusedWindowHandle;
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
        setInjectionResultLocked(entry, *dropReason == DROP_REASON_POLICY
                ? INPUT_EVENT_INJECTION_SUCCEEDED : INPUT_EVENT_INJECTION_FAILED);
        return true;
    }

    // Identify targets.
    Vector<InputTarget> inputTargets;
    int32_t injectionResult = findFocusedWindowTargetsLocked(currentTime,
            entry, inputTargets, nextWakeupTime);
    if (injectionResult == INPUT_EVENT_INJECTION_PENDING) {
        return false;
    }

    setInjectionResultLocked(entry, injectionResult);
    if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
        return true;
    }

    addMonitoringTargetsLocked(inputTargets);

    // Dispatch the key.
    dispatchEventLocked(currentTime, entry, inputTargets);
    return true;
}

void InputDispatcher::logOutboundKeyDetailsLocked(const char* prefix, const KeyEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    ALOGD("%seventTime=%lld, deviceId=%d, source=0x%x, policyFlags=0x%x, "
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

        logOutboundMotionDetailsLocked("dispatchMotion - ", entry);
    }

    // Clean up if dropping the event.
    if (*dropReason != DROP_REASON_NOT_DROPPED) {
        setInjectionResultLocked(entry, *dropReason == DROP_REASON_POLICY
                ? INPUT_EVENT_INJECTION_SUCCEEDED : INPUT_EVENT_INJECTION_FAILED);
        return true;
    }

    bool isPointerEvent = entry->source & AINPUT_SOURCE_CLASS_POINTER;

    // Identify targets.
    Vector<InputTarget> inputTargets;

    bool conflictingPointerActions = false;
    int32_t injectionResult;
    if (isPointerEvent) {
        // Pointer event.  (eg. touchscreen)
        injectionResult = findTouchedWindowTargetsLocked(currentTime,
                entry, inputTargets, nextWakeupTime, &conflictingPointerActions);
    } else {
        // Non touch event.  (eg. trackball)
        injectionResult = findFocusedWindowTargetsLocked(currentTime,
                entry, inputTargets, nextWakeupTime);
    }
    if (injectionResult == INPUT_EVENT_INJECTION_PENDING) {
        return false;
    }

    setInjectionResultLocked(entry, injectionResult);
    if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
        return true;
    }

    // TODO: support sending secondary display events to input monitors
    if (isMainDisplay(entry->displayId)) {
        addMonitoringTargetsLocked(inputTargets);
    }

    // Dispatch the motion.
    if (conflictingPointerActions) {
        CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS,
                "conflicting pointer actions");
        synthesizeCancelationEventsForAllConnectionsLocked(options);
    }
    dispatchEventLocked(currentTime, entry, inputTargets);
    return true;
}


void InputDispatcher::logOutboundMotionDetailsLocked(const char* prefix, const MotionEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    ALOGD("%seventTime=%lld, deviceId=%d, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, "
            "metaState=0x%x, buttonState=0x%x, "
            "edgeFlags=0x%x, xPrecision=%f, yPrecision=%f, downTime=%lld",
            prefix,
            entry->eventTime, entry->deviceId, entry->source, entry->policyFlags,
            entry->action, entry->flags,
            entry->metaState, entry->buttonState,
            entry->edgeFlags, entry->xPrecision, entry->yPrecision,
            entry->downTime);

    for (uint32_t i = 0; i < entry->pointerCount; i++) {
        ALOGD("  Pointer %d: id=%d, toolType=%d, "
                "x=%f, y=%f, pressure=%f, size=%f, "
                "touchMajor=%f, touchMinor=%f, toolMajor=%f, toolMinor=%f, "
                "orientation=%f",
                i, entry->pointerProperties[i].id,
                entry->pointerProperties[i].toolType,
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_X),
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_Y),
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_PRESSURE),
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_SIZE),
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR),
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR),
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR),
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR),
                entry->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION));
    }
#endif
}

void InputDispatcher::dispatchEventLocked(nsecs_t currentTime,
        EventEntry* eventEntry, const Vector<InputTarget>& inputTargets) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("dispatchEventToCurrentInputTargets");
#endif

    ALOG_ASSERT(eventEntry->dispatchInProgress); // should already have been set to true

    pokeUserActivityLocked(eventEntry);

    for (size_t i = 0; i < inputTargets.size(); i++) {
        const InputTarget& inputTarget = inputTargets.itemAt(i);

        ssize_t connectionIndex = getConnectionIndexLocked(inputTarget.inputChannel);
        if (connectionIndex >= 0) {
            sp<Connection> connection = mConnectionsByFd.valueAt(connectionIndex);
            prepareDispatchCycleLocked(currentTime, connection, eventEntry, &inputTarget);
        } else {
#if DEBUG_FOCUS
            ALOGD("Dropping event delivery to target with channel '%s' because it "
                    "is no longer registered with the input dispatcher.",
                    inputTarget.inputChannel->getName().string());
#endif
        }
    }
}

int32_t InputDispatcher::handleTargetsNotReadyLocked(nsecs_t currentTime,
        const EventEntry* entry,
        const sp<InputApplicationHandle>& applicationHandle,
        const sp<InputWindowHandle>& windowHandle,
        nsecs_t* nextWakeupTime, const char* reason) {
    if (applicationHandle == NULL && windowHandle == NULL) {
        if (mInputTargetWaitCause != INPUT_TARGET_WAIT_CAUSE_SYSTEM_NOT_READY) {
#if DEBUG_FOCUS
            ALOGD("Waiting for system to become ready for input.  Reason: %s", reason);
#endif
            mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_SYSTEM_NOT_READY;
            mInputTargetWaitStartTime = currentTime;
            mInputTargetWaitTimeoutTime = LONG_LONG_MAX;
            mInputTargetWaitTimeoutExpired = false;
            mInputTargetWaitApplicationHandle.clear();
        }
    } else {
        if (mInputTargetWaitCause != INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY) {
#if DEBUG_FOCUS
            ALOGD("Waiting for application to become ready for input: %s.  Reason: %s",
                    getApplicationWindowLabelLocked(applicationHandle, windowHandle).string(),
                    reason);
#endif
            nsecs_t timeout;
            if (windowHandle != NULL) {
                timeout = windowHandle->getDispatchingTimeout(DEFAULT_INPUT_DISPATCHING_TIMEOUT);
            } else if (applicationHandle != NULL) {
                timeout = applicationHandle->getDispatchingTimeout(
                        DEFAULT_INPUT_DISPATCHING_TIMEOUT);
            } else {
                timeout = DEFAULT_INPUT_DISPATCHING_TIMEOUT;
            }

            mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY;
            mInputTargetWaitStartTime = currentTime;
            mInputTargetWaitTimeoutTime = currentTime + timeout;
            mInputTargetWaitTimeoutExpired = false;
            mInputTargetWaitApplicationHandle.clear();

            if (windowHandle != NULL) {
                mInputTargetWaitApplicationHandle = windowHandle->inputApplicationHandle;
            }
            if (mInputTargetWaitApplicationHandle == NULL && applicationHandle != NULL) {
                mInputTargetWaitApplicationHandle = applicationHandle;
            }
        }
    }

    if (mInputTargetWaitTimeoutExpired) {
        return INPUT_EVENT_INJECTION_TIMED_OUT;
    }

    if (currentTime >= mInputTargetWaitTimeoutTime) {
        onANRLocked(currentTime, applicationHandle, windowHandle,
                entry->eventTime, mInputTargetWaitStartTime, reason);

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

        // Input state will not be realistic.  Mark it out of sync.
        if (inputChannel.get()) {
            ssize_t connectionIndex = getConnectionIndexLocked(inputChannel);
            if (connectionIndex >= 0) {
                sp<Connection> connection = mConnectionsByFd.valueAt(connectionIndex);
                sp<InputWindowHandle> windowHandle = connection->inputWindowHandle;

                if (windowHandle != NULL) {
                    mTouchState.removeWindow(windowHandle);
                }

                if (connection->status == Connection::STATUS_NORMAL) {
                    CancelationOptions options(CancelationOptions::CANCEL_ALL_EVENTS,
                            "application not responding");
                    synthesizeCancelationEventsForConnectionLocked(connection, options);
                }
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
        ALOGD("Resetting ANR timeouts.");
#endif

    // Reset input target wait timeout.
    mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_NONE;
    mInputTargetWaitApplicationHandle.clear();
}

int32_t InputDispatcher::findFocusedWindowTargetsLocked(nsecs_t currentTime,
        const EventEntry* entry, Vector<InputTarget>& inputTargets, nsecs_t* nextWakeupTime) {
    int32_t injectionResult;

    // If there is no currently focused window and no focused application
    // then drop the event.
    if (mFocusedWindowHandle == NULL) {
        if (mFocusedApplicationHandle != NULL) {
            injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                    mFocusedApplicationHandle, NULL, nextWakeupTime,
                    "Waiting because no window has focus but there is a "
                    "focused application that may eventually add a window "
                    "when it finishes starting up.");
            goto Unresponsive;
        }

        ALOGI("Dropping event because there is no focused window or focused application.");
        injectionResult = INPUT_EVENT_INJECTION_FAILED;
        goto Failed;
    }

    // Check permissions.
    if (! checkInjectionPermission(mFocusedWindowHandle, entry->injectionState)) {
        injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
        goto Failed;
    }

    // If the currently focused window is paused then keep waiting.
    if (mFocusedWindowHandle->getInfo()->paused) {
        injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                mFocusedApplicationHandle, mFocusedWindowHandle, nextWakeupTime,
                "Waiting because the focused window is paused.");
        goto Unresponsive;
    }

    // If the currently focused window is still working on previous events then keep waiting.
    if (!isWindowReadyForMoreInputLocked(currentTime, mFocusedWindowHandle, entry)) {
        injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                mFocusedApplicationHandle, mFocusedWindowHandle, nextWakeupTime,
                "Waiting because the focused window has not finished "
                "processing the input events that were previously delivered to it.");
        goto Unresponsive;
    }

    // Success!  Output targets.
    injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
    addWindowTargetLocked(mFocusedWindowHandle,
            InputTarget::FLAG_FOREGROUND | InputTarget::FLAG_DISPATCH_AS_IS, BitSet32(0),
            inputTargets);

    // Done.
Failed:
Unresponsive:
    nsecs_t timeSpentWaitingForApplication = getTimeSpentWaitingForApplicationLocked(currentTime);
    updateDispatchStatisticsLocked(currentTime, entry,
            injectionResult, timeSpentWaitingForApplication);
#if DEBUG_FOCUS
    ALOGD("findFocusedWindow finished: injectionResult=%d, "
            "timeSpentWaitingForApplication=%0.1fms",
            injectionResult, timeSpentWaitingForApplication / 1000000.0);
#endif
    return injectionResult;
}

int32_t InputDispatcher::findTouchedWindowTargetsLocked(nsecs_t currentTime,
        const MotionEntry* entry, Vector<InputTarget>& inputTargets, nsecs_t* nextWakeupTime,
        bool* outConflictingPointerActions) {
    enum InjectionPermission {
        INJECTION_PERMISSION_UNKNOWN,
        INJECTION_PERMISSION_GRANTED,
        INJECTION_PERMISSION_DENIED
    };

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

    int32_t displayId = entry->displayId;
    int32_t action = entry->action;
    int32_t maskedAction = action & AMOTION_EVENT_ACTION_MASK;

    // Update the touch state as needed based on the properties of the touch event.
    int32_t injectionResult = INPUT_EVENT_INJECTION_PENDING;
    InjectionPermission injectionPermission = INJECTION_PERMISSION_UNKNOWN;
    sp<InputWindowHandle> newHoverWindowHandle;

    bool isSplit = mTouchState.split;
    bool switchedDevice = mTouchState.deviceId >= 0 && mTouchState.displayId >= 0
            && (mTouchState.deviceId != entry->deviceId
                    || mTouchState.source != entry->source
                    || mTouchState.displayId != displayId);
    bool isHoverAction = (maskedAction == AMOTION_EVENT_ACTION_HOVER_MOVE
            || maskedAction == AMOTION_EVENT_ACTION_HOVER_ENTER
            || maskedAction == AMOTION_EVENT_ACTION_HOVER_EXIT);
    bool newGesture = (maskedAction == AMOTION_EVENT_ACTION_DOWN
            || maskedAction == AMOTION_EVENT_ACTION_SCROLL
            || isHoverAction);
    bool wrongDevice = false;
    if (newGesture) {
        bool down = maskedAction == AMOTION_EVENT_ACTION_DOWN;
        if (switchedDevice && mTouchState.down && !down) {
#if DEBUG_FOCUS
            ALOGD("Dropping event because a pointer for a different device is already down.");
#endif
            mTempTouchState.copyFrom(mTouchState);
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            switchedDevice = false;
            wrongDevice = true;
            goto Failed;
        }
        mTempTouchState.reset();
        mTempTouchState.down = down;
        mTempTouchState.deviceId = entry->deviceId;
        mTempTouchState.source = entry->source;
        mTempTouchState.displayId = displayId;
        isSplit = false;
    } else {
        mTempTouchState.copyFrom(mTouchState);
    }

    if (newGesture || (isSplit && maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN)) {
        /* Case 1: New splittable pointer going down, or need target for hover or scroll. */

        int32_t pointerIndex = getMotionEventActionPointerIndex(action);
        int32_t x = int32_t(entry->pointerCoords[pointerIndex].
                getAxisValue(AMOTION_EVENT_AXIS_X));
        int32_t y = int32_t(entry->pointerCoords[pointerIndex].
                getAxisValue(AMOTION_EVENT_AXIS_Y));
        sp<InputWindowHandle> newTouchedWindowHandle;
        sp<InputWindowHandle> topErrorWindowHandle;
        bool isTouchModal = false;

        // Traverse windows from front to back to find touched window and outside targets.
        size_t numWindows = mWindowHandles.size();
        for (size_t i = 0; i < numWindows; i++) {
            sp<InputWindowHandle> windowHandle = mWindowHandles.itemAt(i);
            const InputWindowInfo* windowInfo = windowHandle->getInfo();
            if (windowInfo->displayId != displayId) {
                continue; // wrong display
            }

            int32_t flags = windowInfo->layoutParamsFlags;
            if (flags & InputWindowInfo::FLAG_SYSTEM_ERROR) {
                if (topErrorWindowHandle == NULL) {
                    topErrorWindowHandle = windowHandle;
                }
            }

            if (windowInfo->visible) {
                if (! (flags & InputWindowInfo::FLAG_NOT_TOUCHABLE)) {
                    isTouchModal = (flags & (InputWindowInfo::FLAG_NOT_FOCUSABLE
                            | InputWindowInfo::FLAG_NOT_TOUCH_MODAL)) == 0;
                    if (isTouchModal || windowInfo->touchableRegionContainsPoint(x, y)) {
                        if (! screenWasOff
                                || (flags & InputWindowInfo::FLAG_TOUCHABLE_WHEN_WAKING)) {
                            newTouchedWindowHandle = windowHandle;
                        }
                        break; // found touched window, exit window loop
                    }
                }

                if (maskedAction == AMOTION_EVENT_ACTION_DOWN
                        && (flags & InputWindowInfo::FLAG_WATCH_OUTSIDE_TOUCH)) {
                    int32_t outsideTargetFlags = InputTarget::FLAG_DISPATCH_AS_OUTSIDE;
                    if (isWindowObscuredAtPointLocked(windowHandle, x, y)) {
                        outsideTargetFlags |= InputTarget::FLAG_WINDOW_IS_OBSCURED;
                    }

                    mTempTouchState.addOrUpdateWindow(
                            windowHandle, outsideTargetFlags, BitSet32(0));
                }
            }
        }

        // If there is an error window but it is not taking focus (typically because
        // it is invisible) then wait for it.  Any other focused window may in
        // fact be in ANR state.
        if (topErrorWindowHandle != NULL && newTouchedWindowHandle != topErrorWindowHandle) {
            injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                    NULL, NULL, nextWakeupTime,
                    "Waiting because a system error window is about to be displayed.");
            injectionPermission = INJECTION_PERMISSION_UNKNOWN;
            goto Unresponsive;
        }

        // Figure out whether splitting will be allowed for this window.
        if (newTouchedWindowHandle != NULL
                && newTouchedWindowHandle->getInfo()->supportsSplitTouch()) {
            // New window supports splitting.
            isSplit = true;
        } else if (isSplit) {
            // New window does not support splitting but we have already split events.
            // Ignore the new window.
            newTouchedWindowHandle = NULL;
        }

        // Handle the case where we did not find a window.
        if (newTouchedWindowHandle == NULL) {
            // Try to assign the pointer to the first foreground window we find, if there is one.
            newTouchedWindowHandle = mTempTouchState.getFirstForegroundWindowHandle();
            if (newTouchedWindowHandle == NULL) {
                // There is no touched window.  If this is an initial down event
                // then wait for a window to appear that will handle the touch.  This is
                // to ensure that we report an ANR in the case where an application has started
                // but not yet put up a window and the user is starting to get impatient.
                if (maskedAction == AMOTION_EVENT_ACTION_DOWN
                        && mFocusedApplicationHandle != NULL) {
                    injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                            mFocusedApplicationHandle, NULL, nextWakeupTime,
                            "Waiting because there is no touchable window that can "
                            "handle the event but there is focused application that may "
                            "eventually add a new window when it finishes starting up.");
                    goto Unresponsive;
                }

                ALOGI("Dropping event because there is no touched window.");
                injectionResult = INPUT_EVENT_INJECTION_FAILED;
                goto Failed;
            }
        }

        // Set target flags.
        int32_t targetFlags = InputTarget::FLAG_FOREGROUND | InputTarget::FLAG_DISPATCH_AS_IS;
        if (isSplit) {
            targetFlags |= InputTarget::FLAG_SPLIT;
        }
        if (isWindowObscuredAtPointLocked(newTouchedWindowHandle, x, y)) {
            targetFlags |= InputTarget::FLAG_WINDOW_IS_OBSCURED;
        }

        // Update hover state.
        if (isHoverAction) {
            newHoverWindowHandle = newTouchedWindowHandle;
        } else if (maskedAction == AMOTION_EVENT_ACTION_SCROLL) {
            newHoverWindowHandle = mLastHoverWindowHandle;
        }

        // Update the temporary touch state.
        BitSet32 pointerIds;
        if (isSplit) {
            uint32_t pointerId = entry->pointerProperties[pointerIndex].id;
            pointerIds.markBit(pointerId);
        }
        mTempTouchState.addOrUpdateWindow(newTouchedWindowHandle, targetFlags, pointerIds);
    } else {
        /* Case 2: Pointer move, up, cancel or non-splittable pointer down. */

        // If the pointer is not currently down, then ignore the event.
        if (! mTempTouchState.down) {
#if DEBUG_FOCUS
            ALOGD("Dropping event because the pointer is not down or we previously "
                    "dropped the pointer down event.");
#endif
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            goto Failed;
        }

        // Check whether touches should slip outside of the current foreground window.
        if (maskedAction == AMOTION_EVENT_ACTION_MOVE
                && entry->pointerCount == 1
                && mTempTouchState.isSlippery()) {
            int32_t x = int32_t(entry->pointerCoords[0].getAxisValue(AMOTION_EVENT_AXIS_X));
            int32_t y = int32_t(entry->pointerCoords[0].getAxisValue(AMOTION_EVENT_AXIS_Y));

            sp<InputWindowHandle> oldTouchedWindowHandle =
                    mTempTouchState.getFirstForegroundWindowHandle();
            sp<InputWindowHandle> newTouchedWindowHandle =
                    findTouchedWindowAtLocked(displayId, x, y);
            if (oldTouchedWindowHandle != newTouchedWindowHandle
                    && newTouchedWindowHandle != NULL) {
#if DEBUG_FOCUS
                ALOGD("Touch is slipping out of window %s into window %s.",
                        oldTouchedWindowHandle->getName().string(),
                        newTouchedWindowHandle->getName().string());
#endif
                // Make a slippery exit from the old window.
                mTempTouchState.addOrUpdateWindow(oldTouchedWindowHandle,
                        InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT, BitSet32(0));

                // Make a slippery entrance into the new window.
                if (newTouchedWindowHandle->getInfo()->supportsSplitTouch()) {
                    isSplit = true;
                }

                int32_t targetFlags = InputTarget::FLAG_FOREGROUND
                        | InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER;
                if (isSplit) {
                    targetFlags |= InputTarget::FLAG_SPLIT;
                }
                if (isWindowObscuredAtPointLocked(newTouchedWindowHandle, x, y)) {
                    targetFlags |= InputTarget::FLAG_WINDOW_IS_OBSCURED;
                }

                BitSet32 pointerIds;
                if (isSplit) {
                    pointerIds.markBit(entry->pointerProperties[0].id);
                }
                mTempTouchState.addOrUpdateWindow(newTouchedWindowHandle, targetFlags, pointerIds);
            }
        }
    }

    if (newHoverWindowHandle != mLastHoverWindowHandle) {
        // Let the previous window know that the hover sequence is over.
        if (mLastHoverWindowHandle != NULL) {
#if DEBUG_HOVER
            ALOGD("Sending hover exit event to window %s.",
                    mLastHoverWindowHandle->getName().string());
#endif
            mTempTouchState.addOrUpdateWindow(mLastHoverWindowHandle,
                    InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT, BitSet32(0));
        }

        // Let the new window know that the hover sequence is starting.
        if (newHoverWindowHandle != NULL) {
#if DEBUG_HOVER
            ALOGD("Sending hover enter event to window %s.",
                    newHoverWindowHandle->getName().string());
#endif
            mTempTouchState.addOrUpdateWindow(newHoverWindowHandle,
                    InputTarget::FLAG_DISPATCH_AS_HOVER_ENTER, BitSet32(0));
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
                if (! checkInjectionPermission(touchedWindow.windowHandle,
                        entry->injectionState)) {
                    injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
                    injectionPermission = INJECTION_PERMISSION_DENIED;
                    goto Failed;
                }
            }
        }
        if (! haveForegroundWindow) {
#if DEBUG_FOCUS
            ALOGD("Dropping event because there is no touched foreground window to receive it.");
#endif
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            goto Failed;
        }

        // Permission granted to injection into all touched foreground windows.
        injectionPermission = INJECTION_PERMISSION_GRANTED;
    }

    // Check whether windows listening for outside touches are owned by the same UID. If it is
    // set the policy flag that we will not reveal coordinate information to this window.
    if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
        sp<InputWindowHandle> foregroundWindowHandle =
                mTempTouchState.getFirstForegroundWindowHandle();
        const int32_t foregroundWindowUid = foregroundWindowHandle->getInfo()->ownerUid;
        for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTempTouchState.windows[i];
            if (touchedWindow.targetFlags & InputTarget::FLAG_DISPATCH_AS_OUTSIDE) {
                sp<InputWindowHandle> inputWindowHandle = touchedWindow.windowHandle;
                if (inputWindowHandle->getInfo()->ownerUid != foregroundWindowUid) {
                    mTempTouchState.addOrUpdateWindow(inputWindowHandle,
                            InputTarget::FLAG_ZERO_COORDS, BitSet32(0));
                }
            }
        }
    }

    // Ensure all touched foreground windows are ready for new input.
    for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
        const TouchedWindow& touchedWindow = mTempTouchState.windows[i];
        if (touchedWindow.targetFlags & InputTarget::FLAG_FOREGROUND) {
            // If the touched window is paused then keep waiting.
            if (touchedWindow.windowHandle->getInfo()->paused) {
                injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                        NULL, touchedWindow.windowHandle, nextWakeupTime,
                        "Waiting because the touched window is paused.");
                goto Unresponsive;
            }

            // If the touched window is still working on previous events then keep waiting.
            if (!isWindowReadyForMoreInputLocked(currentTime, touchedWindow.windowHandle, entry)) {
                injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                        NULL, touchedWindow.windowHandle, nextWakeupTime,
                        "Waiting because the touched window has not finished "
                        "processing the input events that were previously delivered to it.");
                goto Unresponsive;
            }
        }
    }

    // If this is the first pointer going down and the touched window has a wallpaper
    // then also add the touched wallpaper windows so they are locked in for the duration
    // of the touch gesture.
    // We do not collect wallpapers during HOVER_MOVE or SCROLL because the wallpaper
    // engine only supports touch events.  We would need to add a mechanism similar
    // to View.onGenericMotionEvent to enable wallpapers to handle these events.
    if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
        sp<InputWindowHandle> foregroundWindowHandle =
                mTempTouchState.getFirstForegroundWindowHandle();
        if (foregroundWindowHandle->getInfo()->hasWallpaper) {
            for (size_t i = 0; i < mWindowHandles.size(); i++) {
                sp<InputWindowHandle> windowHandle = mWindowHandles.itemAt(i);
                const InputWindowInfo* info = windowHandle->getInfo();
                if (info->displayId == displayId
                        && windowHandle->getInfo()->layoutParamsType
                                == InputWindowInfo::TYPE_WALLPAPER) {
                    mTempTouchState.addOrUpdateWindow(windowHandle,
                            InputTarget::FLAG_WINDOW_IS_OBSCURED
                                    | InputTarget::FLAG_DISPATCH_AS_IS,
                            BitSet32(0));
                }
            }
        }
    }

    // Success!  Output targets.
    injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;

    for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
        const TouchedWindow& touchedWindow = mTempTouchState.windows.itemAt(i);
        addWindowTargetLocked(touchedWindow.windowHandle, touchedWindow.targetFlags,
                touchedWindow.pointerIds, inputTargets);
    }

    // Drop the outside or hover touch windows since we will not care about them
    // in the next iteration.
    mTempTouchState.filterNonAsIsTouchWindows();

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
        if (!wrongDevice) {
            if (switchedDevice) {
#if DEBUG_FOCUS
                ALOGD("Conflicting pointer actions: Switched to a different device.");
#endif
                *outConflictingPointerActions = true;
            }

            if (isHoverAction) {
                // Started hovering, therefore no longer down.
                if (mTouchState.down) {
#if DEBUG_FOCUS
                    ALOGD("Conflicting pointer actions: Hover received while pointer was down.");
#endif
                    *outConflictingPointerActions = true;
                }
                mTouchState.reset();
                if (maskedAction == AMOTION_EVENT_ACTION_HOVER_ENTER
                        || maskedAction == AMOTION_EVENT_ACTION_HOVER_MOVE) {
                    mTouchState.deviceId = entry->deviceId;
                    mTouchState.source = entry->source;
                    mTouchState.displayId = displayId;
                }
            } else if (maskedAction == AMOTION_EVENT_ACTION_UP
                    || maskedAction == AMOTION_EVENT_ACTION_CANCEL) {
                // All pointers up or canceled.
                mTouchState.reset();
            } else if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
                // First pointer went down.
                if (mTouchState.down) {
#if DEBUG_FOCUS
                    ALOGD("Conflicting pointer actions: Down received while already down.");
#endif
                    *outConflictingPointerActions = true;
                }
                mTouchState.copyFrom(mTempTouchState);
            } else if (maskedAction == AMOTION_EVENT_ACTION_POINTER_UP) {
                // One pointer went up.
                if (isSplit) {
                    int32_t pointerIndex = getMotionEventActionPointerIndex(action);
                    uint32_t pointerId = entry->pointerProperties[pointerIndex].id;

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
                mTouchState.copyFrom(mTempTouchState);
            } else if (maskedAction == AMOTION_EVENT_ACTION_SCROLL) {
                // Discard temporary touch state since it was only valid for this action.
            } else {
                // Save changes to touch state as-is for all other actions.
                mTouchState.copyFrom(mTempTouchState);
            }

            // Update hover state.
            mLastHoverWindowHandle = newHoverWindowHandle;
        }
    } else {
#if DEBUG_FOCUS
        ALOGD("Not updating touch focus because injection was denied.");
#endif
    }

Unresponsive:
    // Reset temporary touch state to ensure we release unnecessary references to input channels.
    mTempTouchState.reset();

    nsecs_t timeSpentWaitingForApplication = getTimeSpentWaitingForApplicationLocked(currentTime);
    updateDispatchStatisticsLocked(currentTime, entry,
            injectionResult, timeSpentWaitingForApplication);
#if DEBUG_FOCUS
    ALOGD("findTouchedWindow finished: injectionResult=%d, injectionPermission=%d, "
            "timeSpentWaitingForApplication=%0.1fms",
            injectionResult, injectionPermission, timeSpentWaitingForApplication / 1000000.0);
#endif
    return injectionResult;
}

void InputDispatcher::addWindowTargetLocked(const sp<InputWindowHandle>& windowHandle,
        int32_t targetFlags, BitSet32 pointerIds, Vector<InputTarget>& inputTargets) {
    inputTargets.push();

    const InputWindowInfo* windowInfo = windowHandle->getInfo();
    InputTarget& target = inputTargets.editTop();
    target.inputChannel = windowInfo->inputChannel;
    target.flags = targetFlags;
    target.xOffset = - windowInfo->frameLeft;
    target.yOffset = - windowInfo->frameTop;
    target.scaleFactor = windowInfo->scaleFactor;
    target.pointerIds = pointerIds;
}

void InputDispatcher::addMonitoringTargetsLocked(Vector<InputTarget>& inputTargets) {
    for (size_t i = 0; i < mMonitoringChannels.size(); i++) {
        inputTargets.push();

        InputTarget& target = inputTargets.editTop();
        target.inputChannel = mMonitoringChannels[i];
        target.flags = InputTarget::FLAG_DISPATCH_AS_IS;
        target.xOffset = 0;
        target.yOffset = 0;
        target.pointerIds.clear();
        target.scaleFactor = 1.0f;
    }
}

bool InputDispatcher::checkInjectionPermission(const sp<InputWindowHandle>& windowHandle,
        const InjectionState* injectionState) {
    if (injectionState
            && (windowHandle == NULL
                    || windowHandle->getInfo()->ownerUid != injectionState->injectorUid)
            && !hasInjectionPermission(injectionState->injectorPid, injectionState->injectorUid)) {
        if (windowHandle != NULL) {
            ALOGW("Permission denied: injecting event from pid %d uid %d to window %s "
                    "owned by uid %d",
                    injectionState->injectorPid, injectionState->injectorUid,
                    windowHandle->getName().string(),
                    windowHandle->getInfo()->ownerUid);
        } else {
            ALOGW("Permission denied: injecting event from pid %d uid %d",
                    injectionState->injectorPid, injectionState->injectorUid);
        }
        return false;
    }
    return true;
}

bool InputDispatcher::isWindowObscuredAtPointLocked(
        const sp<InputWindowHandle>& windowHandle, int32_t x, int32_t y) const {
    int32_t displayId = windowHandle->getInfo()->displayId;
    size_t numWindows = mWindowHandles.size();
    for (size_t i = 0; i < numWindows; i++) {
        sp<InputWindowHandle> otherHandle = mWindowHandles.itemAt(i);
        if (otherHandle == windowHandle) {
            break;
        }

        const InputWindowInfo* otherInfo = otherHandle->getInfo();
        if (otherInfo->displayId == displayId
                && otherInfo->visible && !otherInfo->isTrustedOverlay()
                && otherInfo->frameContainsPoint(x, y)) {
            return true;
        }
    }
    return false;
}

bool InputDispatcher::isWindowReadyForMoreInputLocked(nsecs_t currentTime,
        const sp<InputWindowHandle>& windowHandle, const EventEntry* eventEntry) {
    ssize_t connectionIndex = getConnectionIndexLocked(windowHandle->getInputChannel());
    if (connectionIndex >= 0) {
        sp<Connection> connection = mConnectionsByFd.valueAt(connectionIndex);
        if (connection->inputPublisherBlocked) {
            return false;
        }
        if (eventEntry->type == EventEntry::TYPE_KEY) {
            // If the event is a key event, then we must wait for all previous events to
            // complete before delivering it because previous events may have the
            // side-effect of transferring focus to a different window and we want to
            // ensure that the following keys are sent to the new window.
            //
            // Suppose the user touches a button in a window then immediately presses "A".
            // If the button causes a pop-up window to appear then we want to ensure that
            // the "A" key is delivered to the new pop-up window.  This is because users
            // often anticipate pending UI changes when typing on a keyboard.
            // To obtain this behavior, we must serialize key events with respect to all
            // prior input events.
            return connection->outboundQueue.isEmpty()
                    && connection->waitQueue.isEmpty();
        }
        // Touch events can always be sent to a window immediately because the user intended
        // to touch whatever was visible at the time.  Even if focus changes or a new
        // window appears moments later, the touch event was meant to be delivered to
        // whatever window happened to be on screen at the time.
        //
        // Generic motion events, such as trackball or joystick events are a little trickier.
        // Like key events, generic motion events are delivered to the focused window.
        // Unlike key events, generic motion events don't tend to transfer focus to other
        // windows and it is not important for them to be serialized.  So we prefer to deliver
        // generic motion events as soon as possible to improve efficiency and reduce lag
        // through batching.
        //
        // The one case where we pause input event delivery is when the wait queue is piling
        // up with lots of events because the application is not responding.
        // This condition ensures that ANRs are detected reliably.
        if (!connection->waitQueue.isEmpty()
                && currentTime >= connection->waitQueue.head->eventEntry->eventTime
                        + STREAM_AHEAD_EVENT_TIMEOUT) {
            return false;
        }
    }
    return true;
}

String8 InputDispatcher::getApplicationWindowLabelLocked(
        const sp<InputApplicationHandle>& applicationHandle,
        const sp<InputWindowHandle>& windowHandle) {
    if (applicationHandle != NULL) {
        if (windowHandle != NULL) {
            String8 label(applicationHandle->getName());
            label.append(" - ");
            label.append(windowHandle->getName());
            return label;
        } else {
            return applicationHandle->getName();
        }
    } else if (windowHandle != NULL) {
        return windowHandle->getName();
    } else {
        return String8("<unknown application or window>");
    }
}

void InputDispatcher::pokeUserActivityLocked(const EventEntry* eventEntry) {
    if (mFocusedWindowHandle != NULL) {
        const InputWindowInfo* info = mFocusedWindowHandle->getInfo();
        if (info->inputFeatures & InputWindowInfo::INPUT_FEATURE_DISABLE_USER_ACTIVITY) {
#if DEBUG_DISPATCH_CYCLE
            ALOGD("Not poking user activity: disabled by window '%s'.", info->name.string());
#endif
            return;
        }
    }

    int32_t eventType = USER_ACTIVITY_EVENT_OTHER;
    switch (eventEntry->type) {
    case EventEntry::TYPE_MOTION: {
        const MotionEntry* motionEntry = static_cast<const MotionEntry*>(eventEntry);
        if (motionEntry->action == AMOTION_EVENT_ACTION_CANCEL) {
            return;
        }

        if (MotionEvent::isTouchEvent(motionEntry->source, motionEntry->action)) {
            eventType = USER_ACTIVITY_EVENT_TOUCH;
        }
        break;
    }
    case EventEntry::TYPE_KEY: {
        const KeyEntry* keyEntry = static_cast<const KeyEntry*>(eventEntry);
        if (keyEntry->flags & AKEY_EVENT_FLAG_CANCELED) {
            return;
        }
        eventType = USER_ACTIVITY_EVENT_BUTTON;
        break;
    }
    }

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doPokeUserActivityLockedInterruptible);
    commandEntry->eventTime = eventEntry->eventTime;
    commandEntry->userActivityEventType = eventType;
}

void InputDispatcher::prepareDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, EventEntry* eventEntry, const InputTarget* inputTarget) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ prepareDispatchCycle - flags=0x%08x, "
            "xOffset=%f, yOffset=%f, scaleFactor=%f, "
            "pointerIds=0x%x",
            connection->getInputChannelName(), inputTarget->flags,
            inputTarget->xOffset, inputTarget->yOffset,
            inputTarget->scaleFactor, inputTarget->pointerIds.value);
#endif

    // Skip this event if the connection status is not normal.
    // We don't want to enqueue additional outbound events if the connection is broken.
    if (connection->status != Connection::STATUS_NORMAL) {
#if DEBUG_DISPATCH_CYCLE
        ALOGD("channel '%s' ~ Dropping event because the channel status is %s",
                connection->getInputChannelName(), connection->getStatusLabel());
#endif
        return;
    }

    // Split a motion event if needed.
    if (inputTarget->flags & InputTarget::FLAG_SPLIT) {
        ALOG_ASSERT(eventEntry->type == EventEntry::TYPE_MOTION);

        MotionEntry* originalMotionEntry = static_cast<MotionEntry*>(eventEntry);
        if (inputTarget->pointerIds.count() != originalMotionEntry->pointerCount) {
            MotionEntry* splitMotionEntry = splitMotionEvent(
                    originalMotionEntry, inputTarget->pointerIds);
            if (!splitMotionEntry) {
                return; // split event was dropped
            }
#if DEBUG_FOCUS
            ALOGD("channel '%s' ~ Split motion event.",
                    connection->getInputChannelName());
            logOutboundMotionDetailsLocked("  ", splitMotionEntry);
#endif
            enqueueDispatchEntriesLocked(currentTime, connection,
                    splitMotionEntry, inputTarget);
            splitMotionEntry->release();
            return;
        }
    }

    // Not splitting.  Enqueue dispatch entries for the event as is.
    enqueueDispatchEntriesLocked(currentTime, connection, eventEntry, inputTarget);
}

void InputDispatcher::enqueueDispatchEntriesLocked(nsecs_t currentTime,
        const sp<Connection>& connection, EventEntry* eventEntry, const InputTarget* inputTarget) {
    bool wasEmpty = connection->outboundQueue.isEmpty();

    // Enqueue dispatch entries for the requested modes.
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            InputTarget::FLAG_DISPATCH_AS_OUTSIDE);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            InputTarget::FLAG_DISPATCH_AS_HOVER_ENTER);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            InputTarget::FLAG_DISPATCH_AS_IS);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER);

    // If the outbound queue was previously empty, start the dispatch cycle going.
    if (wasEmpty && !connection->outboundQueue.isEmpty()) {
        startDispatchCycleLocked(currentTime, connection);
    }
}

void InputDispatcher::enqueueDispatchEntryLocked(
        const sp<Connection>& connection, EventEntry* eventEntry, const InputTarget* inputTarget,
        int32_t dispatchMode) {
    int32_t inputTargetFlags = inputTarget->flags;
    if (!(inputTargetFlags & dispatchMode)) {
        return;
    }
    inputTargetFlags = (inputTargetFlags & ~InputTarget::FLAG_DISPATCH_MASK) | dispatchMode;

    // This is a new event.
    // Enqueue a new dispatch entry onto the outbound queue for this connection.
    DispatchEntry* dispatchEntry = new DispatchEntry(eventEntry, // increments ref
            inputTargetFlags, inputTarget->xOffset, inputTarget->yOffset,
            inputTarget->scaleFactor);

    // Apply target flags and update the connection's input state.
    switch (eventEntry->type) {
    case EventEntry::TYPE_KEY: {
        KeyEntry* keyEntry = static_cast<KeyEntry*>(eventEntry);
        dispatchEntry->resolvedAction = keyEntry->action;
        dispatchEntry->resolvedFlags = keyEntry->flags;

        if (!connection->inputState.trackKey(keyEntry,
                dispatchEntry->resolvedAction, dispatchEntry->resolvedFlags)) {
#if DEBUG_DISPATCH_CYCLE
            ALOGD("channel '%s' ~ enqueueDispatchEntryLocked: skipping inconsistent key event",
                    connection->getInputChannelName());
#endif
            delete dispatchEntry;
            return; // skip the inconsistent event
        }
        break;
    }

    case EventEntry::TYPE_MOTION: {
        MotionEntry* motionEntry = static_cast<MotionEntry*>(eventEntry);
        if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_OUTSIDE) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_OUTSIDE;
        } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_HOVER_EXIT;
        } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_HOVER_ENTER) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_HOVER_ENTER;
        } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_CANCEL;
        } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_DOWN;
        } else {
            dispatchEntry->resolvedAction = motionEntry->action;
        }
        if (dispatchEntry->resolvedAction == AMOTION_EVENT_ACTION_HOVER_MOVE
                && !connection->inputState.isHovering(
                        motionEntry->deviceId, motionEntry->source, motionEntry->displayId)) {
#if DEBUG_DISPATCH_CYCLE
        ALOGD("channel '%s' ~ enqueueDispatchEntryLocked: filling in missing hover enter event",
                connection->getInputChannelName());
#endif
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_HOVER_ENTER;
        }

        dispatchEntry->resolvedFlags = motionEntry->flags;
        if (dispatchEntry->targetFlags & InputTarget::FLAG_WINDOW_IS_OBSCURED) {
            dispatchEntry->resolvedFlags |= AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED;
        }

        if (!connection->inputState.trackMotion(motionEntry,
                dispatchEntry->resolvedAction, dispatchEntry->resolvedFlags)) {
#if DEBUG_DISPATCH_CYCLE
            ALOGD("channel '%s' ~ enqueueDispatchEntryLocked: skipping inconsistent motion event",
                    connection->getInputChannelName());
#endif
            delete dispatchEntry;
            return; // skip the inconsistent event
        }
        break;
    }
    }

    // Remember that we are waiting for this dispatch to complete.
    if (dispatchEntry->hasForegroundTarget()) {
        incrementPendingForegroundDispatchesLocked(eventEntry);
    }

    // Enqueue the dispatch entry.
    connection->outboundQueue.enqueueAtTail(dispatchEntry);
    traceOutboundQueueLengthLocked(connection);
}

void InputDispatcher::startDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ startDispatchCycle",
            connection->getInputChannelName());
#endif

    while (connection->status == Connection::STATUS_NORMAL
            && !connection->outboundQueue.isEmpty()) {
        DispatchEntry* dispatchEntry = connection->outboundQueue.head;
        dispatchEntry->deliveryTime = currentTime;

        // Publish the event.
        status_t status;
        EventEntry* eventEntry = dispatchEntry->eventEntry;
        switch (eventEntry->type) {
        case EventEntry::TYPE_KEY: {
            KeyEntry* keyEntry = static_cast<KeyEntry*>(eventEntry);

            // Publish the key event.
            status = connection->inputPublisher.publishKeyEvent(dispatchEntry->seq,
                    keyEntry->deviceId, keyEntry->source,
                    dispatchEntry->resolvedAction, dispatchEntry->resolvedFlags,
                    keyEntry->keyCode, keyEntry->scanCode,
                    keyEntry->metaState, keyEntry->repeatCount, keyEntry->downTime,
                    keyEntry->eventTime);
            break;
        }

        case EventEntry::TYPE_MOTION: {
            MotionEntry* motionEntry = static_cast<MotionEntry*>(eventEntry);

            PointerCoords scaledCoords[MAX_POINTERS];
            const PointerCoords* usingCoords = motionEntry->pointerCoords;

            // Set the X and Y offset depending on the input source.
            float xOffset, yOffset, scaleFactor;
            if ((motionEntry->source & AINPUT_SOURCE_CLASS_POINTER)
                    && !(dispatchEntry->targetFlags & InputTarget::FLAG_ZERO_COORDS)) {
                scaleFactor = dispatchEntry->scaleFactor;
                xOffset = dispatchEntry->xOffset * scaleFactor;
                yOffset = dispatchEntry->yOffset * scaleFactor;
                if (scaleFactor != 1.0f) {
                    for (size_t i = 0; i < motionEntry->pointerCount; i++) {
                        scaledCoords[i] = motionEntry->pointerCoords[i];
                        scaledCoords[i].scale(scaleFactor);
                    }
                    usingCoords = scaledCoords;
                }
            } else {
                xOffset = 0.0f;
                yOffset = 0.0f;
                scaleFactor = 1.0f;

                // We don't want the dispatch target to know.
                if (dispatchEntry->targetFlags & InputTarget::FLAG_ZERO_COORDS) {
                    for (size_t i = 0; i < motionEntry->pointerCount; i++) {
                        scaledCoords[i].clear();
                    }
                    usingCoords = scaledCoords;
                }
            }

            // Publish the motion event.
            status = connection->inputPublisher.publishMotionEvent(dispatchEntry->seq,
                    motionEntry->deviceId, motionEntry->source,
                    dispatchEntry->resolvedAction, dispatchEntry->resolvedFlags,
                    motionEntry->edgeFlags, motionEntry->metaState, motionEntry->buttonState,
                    xOffset, yOffset,
                    motionEntry->xPrecision, motionEntry->yPrecision,
                    motionEntry->downTime, motionEntry->eventTime,
                    motionEntry->pointerCount, motionEntry->pointerProperties,
                    usingCoords);
            break;
        }

        default:
            ALOG_ASSERT(false);
            return;
        }

        // Check the result.
        if (status) {
            if (status == WOULD_BLOCK) {
                if (connection->waitQueue.isEmpty()) {
                    ALOGE("channel '%s' ~ Could not publish event because the pipe is full. "
                            "This is unexpected because the wait queue is empty, so the pipe "
                            "should be empty and we shouldn't have any problems writing an "
                            "event to it, status=%d", connection->getInputChannelName(), status);
                    abortBrokenDispatchCycleLocked(currentTime, connection, true /*notify*/);
                } else {
                    // Pipe is full and we are waiting for the app to finish process some events
                    // before sending more events to it.
#if DEBUG_DISPATCH_CYCLE
                    ALOGD("channel '%s' ~ Could not publish event because the pipe is full, "
                            "waiting for the application to catch up",
                            connection->getInputChannelName());
#endif
                    connection->inputPublisherBlocked = true;
                }
            } else {
                ALOGE("channel '%s' ~ Could not publish event due to an unexpected error, "
                        "status=%d", connection->getInputChannelName(), status);
                abortBrokenDispatchCycleLocked(currentTime, connection, true /*notify*/);
            }
            return;
        }

        // Re-enqueue the event on the wait queue.
        connection->outboundQueue.dequeue(dispatchEntry);
        traceOutboundQueueLengthLocked(connection);
        connection->waitQueue.enqueueAtTail(dispatchEntry);
        traceWaitQueueLengthLocked(connection);
    }
}

void InputDispatcher::finishDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, uint32_t seq, bool handled) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ finishDispatchCycle - seq=%u, handled=%s",
            connection->getInputChannelName(), seq, toString(handled));
#endif

    connection->inputPublisherBlocked = false;

    if (connection->status == Connection::STATUS_BROKEN
            || connection->status == Connection::STATUS_ZOMBIE) {
        return;
    }

    // Notify other system components and prepare to start the next dispatch cycle.
    onDispatchCycleFinishedLocked(currentTime, connection, seq, handled);
}

void InputDispatcher::abortBrokenDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, bool notify) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ abortBrokenDispatchCycle - notify=%s",
            connection->getInputChannelName(), toString(notify));
#endif

    // Clear the dispatch queues.
    drainDispatchQueueLocked(&connection->outboundQueue);
    traceOutboundQueueLengthLocked(connection);
    drainDispatchQueueLocked(&connection->waitQueue);
    traceWaitQueueLengthLocked(connection);

    // The connection appears to be unrecoverably broken.
    // Ignore already broken or zombie connections.
    if (connection->status == Connection::STATUS_NORMAL) {
        connection->status = Connection::STATUS_BROKEN;

        if (notify) {
            // Notify other system components.
            onDispatchCycleBrokenLocked(currentTime, connection);
        }
    }
}

void InputDispatcher::drainDispatchQueueLocked(Queue<DispatchEntry>* queue) {
    while (!queue->isEmpty()) {
        DispatchEntry* dispatchEntry = queue->dequeueAtHead();
        releaseDispatchEntryLocked(dispatchEntry);
    }
}

void InputDispatcher::releaseDispatchEntryLocked(DispatchEntry* dispatchEntry) {
    if (dispatchEntry->hasForegroundTarget()) {
        decrementPendingForegroundDispatchesLocked(dispatchEntry->eventEntry);
    }
    delete dispatchEntry;
}

int InputDispatcher::handleReceiveCallback(int fd, int events, void* data) {
    InputDispatcher* d = static_cast<InputDispatcher*>(data);

    { // acquire lock
        AutoMutex _l(d->mLock);

        ssize_t connectionIndex = d->mConnectionsByFd.indexOfKey(fd);
        if (connectionIndex < 0) {
            ALOGE("Received spurious receive callback for unknown input channel.  "
                    "fd=%d, events=0x%x", fd, events);
            return 0; // remove the callback
        }

        bool notify;
        sp<Connection> connection = d->mConnectionsByFd.valueAt(connectionIndex);
        if (!(events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP))) {
            if (!(events & ALOOPER_EVENT_INPUT)) {
                ALOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                        "events=0x%x", connection->getInputChannelName(), events);
                return 1;
            }

            nsecs_t currentTime = now();
            bool gotOne = false;
            status_t status;
            for (;;) {
                uint32_t seq;
                bool handled;
                status = connection->inputPublisher.receiveFinishedSignal(&seq, &handled);
                if (status) {
                    break;
                }
                d->finishDispatchCycleLocked(currentTime, connection, seq, handled);
                gotOne = true;
            }
            if (gotOne) {
                d->runCommandsLockedInterruptible();
                if (status == WOULD_BLOCK) {
                    return 1;
                }
            }

            notify = status != DEAD_OBJECT || !connection->monitor;
            if (notify) {
                ALOGE("channel '%s' ~ Failed to receive finished signal.  status=%d",
                        connection->getInputChannelName(), status);
            }
        } else {
            // Monitor channels are never explicitly unregistered.
            // We do it automatically when the remote endpoint is closed so don't warn
            // about them.
            notify = !connection->monitor;
            if (notify) {
                ALOGW("channel '%s' ~ Consumer closed input channel or an error occurred.  "
                        "events=0x%x", connection->getInputChannelName(), events);
            }
        }

        // Unregister the channel.
        d->unregisterInputChannelLocked(connection->inputChannel, notify);
        return 0; // remove the callback
    } // release lock
}

void InputDispatcher::synthesizeCancelationEventsForAllConnectionsLocked(
        const CancelationOptions& options) {
    for (size_t i = 0; i < mConnectionsByFd.size(); i++) {
        synthesizeCancelationEventsForConnectionLocked(
                mConnectionsByFd.valueAt(i), options);
    }
}

void InputDispatcher::synthesizeCancelationEventsForInputChannelLocked(
        const sp<InputChannel>& channel, const CancelationOptions& options) {
    ssize_t index = getConnectionIndexLocked(channel);
    if (index >= 0) {
        synthesizeCancelationEventsForConnectionLocked(
                mConnectionsByFd.valueAt(index), options);
    }
}

void InputDispatcher::synthesizeCancelationEventsForConnectionLocked(
        const sp<Connection>& connection, const CancelationOptions& options) {
    if (connection->status == Connection::STATUS_BROKEN) {
        return;
    }

    nsecs_t currentTime = now();

    Vector<EventEntry*> cancelationEvents;
    connection->inputState.synthesizeCancelationEvents(currentTime,
            cancelationEvents, options);

    if (!cancelationEvents.isEmpty()) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
        ALOGD("channel '%s' ~ Synthesized %d cancelation events to bring channel back in sync "
                "with reality: %s, mode=%d.",
                connection->getInputChannelName(), cancelationEvents.size(),
                options.reason, options.mode);
#endif
        for (size_t i = 0; i < cancelationEvents.size(); i++) {
            EventEntry* cancelationEventEntry = cancelationEvents.itemAt(i);
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

            InputTarget target;
            sp<InputWindowHandle> windowHandle = getWindowHandleLocked(connection->inputChannel);
            if (windowHandle != NULL) {
                const InputWindowInfo* windowInfo = windowHandle->getInfo();
                target.xOffset = -windowInfo->frameLeft;
                target.yOffset = -windowInfo->frameTop;
                target.scaleFactor = windowInfo->scaleFactor;
            } else {
                target.xOffset = 0;
                target.yOffset = 0;
                target.scaleFactor = 1.0f;
            }
            target.inputChannel = connection->inputChannel;
            target.flags = InputTarget::FLAG_DISPATCH_AS_IS;

            enqueueDispatchEntryLocked(connection, cancelationEventEntry, // increments ref
                    &target, InputTarget::FLAG_DISPATCH_AS_IS);

            cancelationEventEntry->release();
        }

        startDispatchCycleLocked(currentTime, connection);
    }
}

InputDispatcher::MotionEntry*
InputDispatcher::splitMotionEvent(const MotionEntry* originalMotionEntry, BitSet32 pointerIds) {
    ALOG_ASSERT(pointerIds.value != 0);

    uint32_t splitPointerIndexMap[MAX_POINTERS];
    PointerProperties splitPointerProperties[MAX_POINTERS];
    PointerCoords splitPointerCoords[MAX_POINTERS];

    uint32_t originalPointerCount = originalMotionEntry->pointerCount;
    uint32_t splitPointerCount = 0;

    for (uint32_t originalPointerIndex = 0; originalPointerIndex < originalPointerCount;
            originalPointerIndex++) {
        const PointerProperties& pointerProperties =
                originalMotionEntry->pointerProperties[originalPointerIndex];
        uint32_t pointerId = uint32_t(pointerProperties.id);
        if (pointerIds.hasBit(pointerId)) {
            splitPointerIndexMap[splitPointerCount] = originalPointerIndex;
            splitPointerProperties[splitPointerCount].copyFrom(pointerProperties);
            splitPointerCoords[splitPointerCount].copyFrom(
                    originalMotionEntry->pointerCoords[originalPointerIndex]);
            splitPointerCount += 1;
        }
    }

    if (splitPointerCount != pointerIds.count()) {
        // This is bad.  We are missing some of the pointers that we expected to deliver.
        // Most likely this indicates that we received an ACTION_MOVE events that has
        // different pointer ids than we expected based on the previous ACTION_DOWN
        // or ACTION_POINTER_DOWN events that caused us to decide to split the pointers
        // in this way.
        ALOGW("Dropping split motion event because the pointer count is %d but "
                "we expected there to be %d pointers.  This probably means we received "
                "a broken sequence of pointer ids from the input device.",
                splitPointerCount, pointerIds.count());
        return NULL;
    }

    int32_t action = originalMotionEntry->action;
    int32_t maskedAction = action & AMOTION_EVENT_ACTION_MASK;
    if (maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN
            || maskedAction == AMOTION_EVENT_ACTION_POINTER_UP) {
        int32_t originalPointerIndex = getMotionEventActionPointerIndex(action);
        const PointerProperties& pointerProperties =
                originalMotionEntry->pointerProperties[originalPointerIndex];
        uint32_t pointerId = uint32_t(pointerProperties.id);
        if (pointerIds.hasBit(pointerId)) {
            if (pointerIds.count() == 1) {
                // The first/last pointer went down/up.
                action = maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN
                        ? AMOTION_EVENT_ACTION_DOWN : AMOTION_EVENT_ACTION_UP;
            } else {
                // A secondary pointer went down/up.
                uint32_t splitPointerIndex = 0;
                while (pointerId != uint32_t(splitPointerProperties[splitPointerIndex].id)) {
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

    MotionEntry* splitMotionEntry = new MotionEntry(
            originalMotionEntry->eventTime,
            originalMotionEntry->deviceId,
            originalMotionEntry->source,
            originalMotionEntry->policyFlags,
            action,
            originalMotionEntry->flags,
            originalMotionEntry->metaState,
            originalMotionEntry->buttonState,
            originalMotionEntry->edgeFlags,
            originalMotionEntry->xPrecision,
            originalMotionEntry->yPrecision,
            originalMotionEntry->downTime,
            originalMotionEntry->displayId,
            splitPointerCount, splitPointerProperties, splitPointerCoords);

    if (originalMotionEntry->injectionState) {
        splitMotionEntry->injectionState = originalMotionEntry->injectionState;
        splitMotionEntry->injectionState->refCount += 1;
    }

    return splitMotionEntry;
}

void InputDispatcher::notifyConfigurationChanged(const NotifyConfigurationChangedArgs* args) {
#if DEBUG_INBOUND_EVENT_DETAILS
    ALOGD("notifyConfigurationChanged - eventTime=%lld", args->eventTime);
#endif

    bool needWake;
    { // acquire lock
        AutoMutex _l(mLock);

        ConfigurationChangedEntry* newEntry = new ConfigurationChangedEntry(args->eventTime);
        needWake = enqueueInboundEventLocked(newEntry);
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

void InputDispatcher::notifyKey(const NotifyKeyArgs* args) {
#if DEBUG_INBOUND_EVENT_DETAILS
    ALOGD("notifyKey - eventTime=%lld, deviceId=%d, source=0x%x, policyFlags=0x%x, action=0x%x, "
            "flags=0x%x, keyCode=0x%x, scanCode=0x%x, metaState=0x%x, downTime=%lld",
            args->eventTime, args->deviceId, args->source, args->policyFlags,
            args->action, args->flags, args->keyCode, args->scanCode,
            args->metaState, args->downTime);
#endif
    if (!validateKeyEvent(args->action)) {
        return;
    }

    uint32_t policyFlags = args->policyFlags;
    int32_t flags = args->flags;
    int32_t metaState = args->metaState;
    if ((policyFlags & POLICY_FLAG_VIRTUAL) || (flags & AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY)) {
        policyFlags |= POLICY_FLAG_VIRTUAL;
        flags |= AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY;
    }
    if (policyFlags & POLICY_FLAG_ALT) {
        metaState |= AMETA_ALT_ON | AMETA_ALT_LEFT_ON;
    }
    if (policyFlags & POLICY_FLAG_ALT_GR) {
        metaState |= AMETA_ALT_ON | AMETA_ALT_RIGHT_ON;
    }
    if (policyFlags & POLICY_FLAG_SHIFT) {
        metaState |= AMETA_SHIFT_ON | AMETA_SHIFT_LEFT_ON;
    }
    if (policyFlags & POLICY_FLAG_CAPS_LOCK) {
        metaState |= AMETA_CAPS_LOCK_ON;
    }
    if (policyFlags & POLICY_FLAG_FUNCTION) {
        metaState |= AMETA_FUNCTION_ON;
    }

    policyFlags |= POLICY_FLAG_TRUSTED;

    KeyEvent event;
    event.initialize(args->deviceId, args->source, args->action,
            flags, args->keyCode, args->scanCode, metaState, 0,
            args->downTime, args->eventTime);

    mPolicy->interceptKeyBeforeQueueing(&event, /*byref*/ policyFlags);

    if (policyFlags & POLICY_FLAG_WOKE_HERE) {
        flags |= AKEY_EVENT_FLAG_WOKE_HERE;
    }

    bool needWake;
    { // acquire lock
        mLock.lock();

        if (shouldSendKeyToInputFilterLocked(args)) {
            mLock.unlock();

            policyFlags |= POLICY_FLAG_FILTERED;
            if (!mPolicy->filterInputEvent(&event, policyFlags)) {
                return; // event was consumed by the filter
            }

            mLock.lock();
        }

        int32_t repeatCount = 0;
        KeyEntry* newEntry = new KeyEntry(args->eventTime,
                args->deviceId, args->source, policyFlags,
                args->action, flags, args->keyCode, args->scanCode,
                metaState, repeatCount, args->downTime);

        needWake = enqueueInboundEventLocked(newEntry);
        mLock.unlock();
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

bool InputDispatcher::shouldSendKeyToInputFilterLocked(const NotifyKeyArgs* args) {
    return mInputFilterEnabled;
}

void InputDispatcher::notifyMotion(const NotifyMotionArgs* args) {
#if DEBUG_INBOUND_EVENT_DETAILS
    ALOGD("notifyMotion - eventTime=%lld, deviceId=%d, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, metaState=0x%x, buttonState=0x%x, edgeFlags=0x%x, "
            "xPrecision=%f, yPrecision=%f, downTime=%lld",
            args->eventTime, args->deviceId, args->source, args->policyFlags,
            args->action, args->flags, args->metaState, args->buttonState,
            args->edgeFlags, args->xPrecision, args->yPrecision, args->downTime);
    for (uint32_t i = 0; i < args->pointerCount; i++) {
        ALOGD("  Pointer %d: id=%d, toolType=%d, "
                "x=%f, y=%f, pressure=%f, size=%f, "
                "touchMajor=%f, touchMinor=%f, toolMajor=%f, toolMinor=%f, "
                "orientation=%f",
                i, args->pointerProperties[i].id,
                args->pointerProperties[i].toolType,
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_X),
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_Y),
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_PRESSURE),
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_SIZE),
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR),
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR),
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR),
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR),
                args->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION));
    }
#endif
    if (!validateMotionEvent(args->action, args->pointerCount, args->pointerProperties)) {
        return;
    }

    uint32_t policyFlags = args->policyFlags;
    policyFlags |= POLICY_FLAG_TRUSTED;
    mPolicy->interceptMotionBeforeQueueing(args->eventTime, /*byref*/ policyFlags);

    bool needWake;
    { // acquire lock
        mLock.lock();

        if (shouldSendMotionToInputFilterLocked(args)) {
            mLock.unlock();

            MotionEvent event;
            event.initialize(args->deviceId, args->source, args->action, args->flags,
                    args->edgeFlags, args->metaState, args->buttonState, 0, 0,
                    args->xPrecision, args->yPrecision,
                    args->downTime, args->eventTime,
                    args->pointerCount, args->pointerProperties, args->pointerCoords);

            policyFlags |= POLICY_FLAG_FILTERED;
            if (!mPolicy->filterInputEvent(&event, policyFlags)) {
                return; // event was consumed by the filter
            }

            mLock.lock();
        }

        // Just enqueue a new motion event.
        MotionEntry* newEntry = new MotionEntry(args->eventTime,
                args->deviceId, args->source, policyFlags,
                args->action, args->flags, args->metaState, args->buttonState,
                args->edgeFlags, args->xPrecision, args->yPrecision, args->downTime,
                args->displayId,
                args->pointerCount, args->pointerProperties, args->pointerCoords);

        needWake = enqueueInboundEventLocked(newEntry);
        mLock.unlock();
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

bool InputDispatcher::shouldSendMotionToInputFilterLocked(const NotifyMotionArgs* args) {
    // TODO: support sending secondary display events to input filter
    return mInputFilterEnabled && isMainDisplay(args->displayId);
}

void InputDispatcher::notifySwitch(const NotifySwitchArgs* args) {
#if DEBUG_INBOUND_EVENT_DETAILS
    ALOGD("notifySwitch - eventTime=%lld, policyFlags=0x%x, switchValues=0x%08x, switchMask=0x%08x",
            args->eventTime, args->policyFlags,
            args->switchValues, args->switchMask);
#endif

    uint32_t policyFlags = args->policyFlags;
    policyFlags |= POLICY_FLAG_TRUSTED;
    mPolicy->notifySwitch(args->eventTime,
            args->switchValues, args->switchMask, policyFlags);
}

void InputDispatcher::notifyDeviceReset(const NotifyDeviceResetArgs* args) {
#if DEBUG_INBOUND_EVENT_DETAILS
    ALOGD("notifyDeviceReset - eventTime=%lld, deviceId=%d",
            args->eventTime, args->deviceId);
#endif

    bool needWake;
    { // acquire lock
        AutoMutex _l(mLock);

        DeviceResetEntry* newEntry = new DeviceResetEntry(args->eventTime, args->deviceId);
        needWake = enqueueInboundEventLocked(newEntry);
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

int32_t InputDispatcher::injectInputEvent(const InputEvent* event,
        int32_t injectorPid, int32_t injectorUid, int32_t syncMode, int32_t timeoutMillis,
        uint32_t policyFlags) {
#if DEBUG_INBOUND_EVENT_DETAILS
    ALOGD("injectInputEvent - eventType=%d, injectorPid=%d, injectorUid=%d, "
            "syncMode=%d, timeoutMillis=%d, policyFlags=0x%08x",
            event->getType(), injectorPid, injectorUid, syncMode, timeoutMillis, policyFlags);
#endif

    nsecs_t endTime = now() + milliseconds_to_nanoseconds(timeoutMillis);

    policyFlags |= POLICY_FLAG_INJECTED;
    if (hasInjectionPermission(injectorPid, injectorUid)) {
        policyFlags |= POLICY_FLAG_TRUSTED;
    }

    EventEntry* firstInjectedEntry;
    EventEntry* lastInjectedEntry;
    switch (event->getType()) {
    case AINPUT_EVENT_TYPE_KEY: {
        const KeyEvent* keyEvent = static_cast<const KeyEvent*>(event);
        int32_t action = keyEvent->getAction();
        if (! validateKeyEvent(action)) {
            return INPUT_EVENT_INJECTION_FAILED;
        }

        int32_t flags = keyEvent->getFlags();
        if (flags & AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY) {
            policyFlags |= POLICY_FLAG_VIRTUAL;
        }

        if (!(policyFlags & POLICY_FLAG_FILTERED)) {
            mPolicy->interceptKeyBeforeQueueing(keyEvent, /*byref*/ policyFlags);
        }

        if (policyFlags & POLICY_FLAG_WOKE_HERE) {
            flags |= AKEY_EVENT_FLAG_WOKE_HERE;
        }

        mLock.lock();
        firstInjectedEntry = new KeyEntry(keyEvent->getEventTime(),
                keyEvent->getDeviceId(), keyEvent->getSource(),
                policyFlags, action, flags,
                keyEvent->getKeyCode(), keyEvent->getScanCode(), keyEvent->getMetaState(),
                keyEvent->getRepeatCount(), keyEvent->getDownTime());
        lastInjectedEntry = firstInjectedEntry;
        break;
    }

    case AINPUT_EVENT_TYPE_MOTION: {
        const MotionEvent* motionEvent = static_cast<const MotionEvent*>(event);
        int32_t displayId = ADISPLAY_ID_DEFAULT;
        int32_t action = motionEvent->getAction();
        size_t pointerCount = motionEvent->getPointerCount();
        const PointerProperties* pointerProperties = motionEvent->getPointerProperties();
        if (! validateMotionEvent(action, pointerCount, pointerProperties)) {
            return INPUT_EVENT_INJECTION_FAILED;
        }

        if (!(policyFlags & POLICY_FLAG_FILTERED)) {
            nsecs_t eventTime = motionEvent->getEventTime();
            mPolicy->interceptMotionBeforeQueueing(eventTime, /*byref*/ policyFlags);
        }

        mLock.lock();
        const nsecs_t* sampleEventTimes = motionEvent->getSampleEventTimes();
        const PointerCoords* samplePointerCoords = motionEvent->getSamplePointerCoords();
        firstInjectedEntry = new MotionEntry(*sampleEventTimes,
                motionEvent->getDeviceId(), motionEvent->getSource(), policyFlags,
                action, motionEvent->getFlags(),
                motionEvent->getMetaState(), motionEvent->getButtonState(),
                motionEvent->getEdgeFlags(),
                motionEvent->getXPrecision(), motionEvent->getYPrecision(),
                motionEvent->getDownTime(), displayId,
                uint32_t(pointerCount), pointerProperties, samplePointerCoords);
        lastInjectedEntry = firstInjectedEntry;
        for (size_t i = motionEvent->getHistorySize(); i > 0; i--) {
            sampleEventTimes += 1;
            samplePointerCoords += pointerCount;
            MotionEntry* nextInjectedEntry = new MotionEntry(*sampleEventTimes,
                    motionEvent->getDeviceId(), motionEvent->getSource(), policyFlags,
                    action, motionEvent->getFlags(),
                    motionEvent->getMetaState(), motionEvent->getButtonState(),
                    motionEvent->getEdgeFlags(),
                    motionEvent->getXPrecision(), motionEvent->getYPrecision(),
                    motionEvent->getDownTime(), displayId,
                    uint32_t(pointerCount), pointerProperties, samplePointerCoords);
            lastInjectedEntry->next = nextInjectedEntry;
            lastInjectedEntry = nextInjectedEntry;
        }
        break;
    }

    default:
        ALOGW("Cannot inject event of type %d", event->getType());
        return INPUT_EVENT_INJECTION_FAILED;
    }

    InjectionState* injectionState = new InjectionState(injectorPid, injectorUid);
    if (syncMode == INPUT_EVENT_INJECTION_SYNC_NONE) {
        injectionState->injectionIsAsync = true;
    }

    injectionState->refCount += 1;
    lastInjectedEntry->injectionState = injectionState;

    bool needWake = false;
    for (EventEntry* entry = firstInjectedEntry; entry != NULL; ) {
        EventEntry* nextEntry = entry->next;
        needWake |= enqueueInboundEventLocked(entry);
        entry = nextEntry;
    }

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
                    ALOGD("injectInputEvent - Timed out waiting for injection result "
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
                    ALOGD("injectInputEvent - Waiting for %d pending foreground dispatches.",
                            injectionState->pendingForegroundDispatches);
#endif
                    nsecs_t remainingTimeout = endTime - now();
                    if (remainingTimeout <= 0) {
#if DEBUG_INJECTION
                    ALOGD("injectInputEvent - Timed out waiting for pending foreground "
                            "dispatches to finish.");
#endif
                        injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                        break;
                    }

                    mInjectionSyncFinishedCondition.waitRelative(mLock, remainingTimeout);
                }
            }
        }

        injectionState->release();
    } // release lock

#if DEBUG_INJECTION
    ALOGD("injectInputEvent - Finished with result %d.  "
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
        ALOGD("Setting input event injection result to %d.  "
                "injectorPid=%d, injectorUid=%d",
                 injectionResult, injectionState->injectorPid, injectionState->injectorUid);
#endif

        if (injectionState->injectionIsAsync
                && !(entry->policyFlags & POLICY_FLAG_FILTERED)) {
            // Log the outcome since the injector did not wait for the injection result.
            switch (injectionResult) {
            case INPUT_EVENT_INJECTION_SUCCEEDED:
                ALOGV("Asynchronous input event injection succeeded.");
                break;
            case INPUT_EVENT_INJECTION_FAILED:
                ALOGW("Asynchronous input event injection failed.");
                break;
            case INPUT_EVENT_INJECTION_PERMISSION_DENIED:
                ALOGW("Asynchronous input event injection permission denied.");
                break;
            case INPUT_EVENT_INJECTION_TIMED_OUT:
                ALOGW("Asynchronous input event injection timed out.");
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

sp<InputWindowHandle> InputDispatcher::getWindowHandleLocked(
        const sp<InputChannel>& inputChannel) const {
    size_t numWindows = mWindowHandles.size();
    for (size_t i = 0; i < numWindows; i++) {
        const sp<InputWindowHandle>& windowHandle = mWindowHandles.itemAt(i);
        if (windowHandle->getInputChannel() == inputChannel) {
            return windowHandle;
        }
    }
    return NULL;
}

bool InputDispatcher::hasWindowHandleLocked(
        const sp<InputWindowHandle>& windowHandle) const {
    size_t numWindows = mWindowHandles.size();
    for (size_t i = 0; i < numWindows; i++) {
        if (mWindowHandles.itemAt(i) == windowHandle) {
            return true;
        }
    }
    return false;
}

void InputDispatcher::setInputWindows(const Vector<sp<InputWindowHandle> >& inputWindowHandles) {
#if DEBUG_FOCUS
    ALOGD("setInputWindows");
#endif
    { // acquire lock
        AutoMutex _l(mLock);

        Vector<sp<InputWindowHandle> > oldWindowHandles = mWindowHandles;
        mWindowHandles = inputWindowHandles;

        sp<InputWindowHandle> newFocusedWindowHandle;
        bool foundHoveredWindow = false;
        for (size_t i = 0; i < mWindowHandles.size(); i++) {
            const sp<InputWindowHandle>& windowHandle = mWindowHandles.itemAt(i);
            if (!windowHandle->updateInfo() || windowHandle->getInputChannel() == NULL) {
                mWindowHandles.removeAt(i--);
                continue;
            }
            if (windowHandle->getInfo()->hasFocus) {
                newFocusedWindowHandle = windowHandle;
            }
            if (windowHandle == mLastHoverWindowHandle) {
                foundHoveredWindow = true;
            }
        }

        if (!foundHoveredWindow) {
            mLastHoverWindowHandle = NULL;
        }

        if (mFocusedWindowHandle != newFocusedWindowHandle) {
            if (mFocusedWindowHandle != NULL) {
#if DEBUG_FOCUS
                ALOGD("Focus left window: %s",
                        mFocusedWindowHandle->getName().string());
#endif
                sp<InputChannel> focusedInputChannel = mFocusedWindowHandle->getInputChannel();
                if (focusedInputChannel != NULL) {
                    CancelationOptions options(CancelationOptions::CANCEL_NON_POINTER_EVENTS,
                            "focus left window");
                    synthesizeCancelationEventsForInputChannelLocked(
                            focusedInputChannel, options);
                }
            }
            if (newFocusedWindowHandle != NULL) {
#if DEBUG_FOCUS
                ALOGD("Focus entered window: %s",
                        newFocusedWindowHandle->getName().string());
#endif
            }
            mFocusedWindowHandle = newFocusedWindowHandle;
        }

        for (size_t i = 0; i < mTouchState.windows.size(); i++) {
            TouchedWindow& touchedWindow = mTouchState.windows.editItemAt(i);
            if (!hasWindowHandleLocked(touchedWindow.windowHandle)) {
#if DEBUG_FOCUS
                ALOGD("Touched window was removed: %s",
                        touchedWindow.windowHandle->getName().string());
#endif
                sp<InputChannel> touchedInputChannel =
                        touchedWindow.windowHandle->getInputChannel();
                if (touchedInputChannel != NULL) {
                    CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS,
                            "touched window was removed");
                    synthesizeCancelationEventsForInputChannelLocked(
                            touchedInputChannel, options);
                }
                mTouchState.windows.removeAt(i--);
            }
        }

        // Release information for windows that are no longer present.
        // This ensures that unused input channels are released promptly.
        // Otherwise, they might stick around until the window handle is destroyed
        // which might not happen until the next GC.
        for (size_t i = 0; i < oldWindowHandles.size(); i++) {
            const sp<InputWindowHandle>& oldWindowHandle = oldWindowHandles.itemAt(i);
            if (!hasWindowHandleLocked(oldWindowHandle)) {
#if DEBUG_FOCUS
                ALOGD("Window went away: %s", oldWindowHandle->getName().string());
#endif
                oldWindowHandle->releaseInfo();
            }
        }
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
}

void InputDispatcher::setFocusedApplication(
        const sp<InputApplicationHandle>& inputApplicationHandle) {
#if DEBUG_FOCUS
    ALOGD("setFocusedApplication");
#endif
    { // acquire lock
        AutoMutex _l(mLock);

        if (inputApplicationHandle != NULL && inputApplicationHandle->updateInfo()) {
            if (mFocusedApplicationHandle != inputApplicationHandle) {
                if (mFocusedApplicationHandle != NULL) {
                    resetANRTimeoutsLocked();
                    mFocusedApplicationHandle->releaseInfo();
                }
                mFocusedApplicationHandle = inputApplicationHandle;
            }
        } else if (mFocusedApplicationHandle != NULL) {
            resetANRTimeoutsLocked();
            mFocusedApplicationHandle->releaseInfo();
            mFocusedApplicationHandle.clear();
        }

#if DEBUG_FOCUS
        //logDispatchStateLocked();
#endif
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
}

void InputDispatcher::setInputDispatchMode(bool enabled, bool frozen) {
#if DEBUG_FOCUS
    ALOGD("setInputDispatchMode: enabled=%d, frozen=%d", enabled, frozen);
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

void InputDispatcher::setInputFilterEnabled(bool enabled) {
#if DEBUG_FOCUS
    ALOGD("setInputFilterEnabled: enabled=%d", enabled);
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        if (mInputFilterEnabled == enabled) {
            return;
        }

        mInputFilterEnabled = enabled;
        resetAndDropEverythingLocked("input filter is being enabled or disabled");
    } // release lock

    // Wake up poll loop since there might be work to do to drop everything.
    mLooper->wake();
}

bool InputDispatcher::transferTouchFocus(const sp<InputChannel>& fromChannel,
        const sp<InputChannel>& toChannel) {
#if DEBUG_FOCUS
    ALOGD("transferTouchFocus: fromChannel=%s, toChannel=%s",
            fromChannel->getName().string(), toChannel->getName().string());
#endif
    { // acquire lock
        AutoMutex _l(mLock);

        sp<InputWindowHandle> fromWindowHandle = getWindowHandleLocked(fromChannel);
        sp<InputWindowHandle> toWindowHandle = getWindowHandleLocked(toChannel);
        if (fromWindowHandle == NULL || toWindowHandle == NULL) {
#if DEBUG_FOCUS
            ALOGD("Cannot transfer focus because from or to window not found.");
#endif
            return false;
        }
        if (fromWindowHandle == toWindowHandle) {
#if DEBUG_FOCUS
            ALOGD("Trivial transfer to same window.");
#endif
            return true;
        }
        if (fromWindowHandle->getInfo()->displayId != toWindowHandle->getInfo()->displayId) {
#if DEBUG_FOCUS
            ALOGD("Cannot transfer focus because windows are on different displays.");
#endif
            return false;
        }

        bool found = false;
        for (size_t i = 0; i < mTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTouchState.windows[i];
            if (touchedWindow.windowHandle == fromWindowHandle) {
                int32_t oldTargetFlags = touchedWindow.targetFlags;
                BitSet32 pointerIds = touchedWindow.pointerIds;

                mTouchState.windows.removeAt(i);

                int32_t newTargetFlags = oldTargetFlags
                        & (InputTarget::FLAG_FOREGROUND
                                | InputTarget::FLAG_SPLIT | InputTarget::FLAG_DISPATCH_AS_IS);
                mTouchState.addOrUpdateWindow(toWindowHandle, newTargetFlags, pointerIds);

                found = true;
                break;
            }
        }

        if (! found) {
#if DEBUG_FOCUS
            ALOGD("Focus transfer failed because from window did not have focus.");
#endif
            return false;
        }

        ssize_t fromConnectionIndex = getConnectionIndexLocked(fromChannel);
        ssize_t toConnectionIndex = getConnectionIndexLocked(toChannel);
        if (fromConnectionIndex >= 0 && toConnectionIndex >= 0) {
            sp<Connection> fromConnection = mConnectionsByFd.valueAt(fromConnectionIndex);
            sp<Connection> toConnection = mConnectionsByFd.valueAt(toConnectionIndex);

            fromConnection->inputState.copyPointerStateTo(toConnection->inputState);
            CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS,
                    "transferring touch focus from this window to another window");
            synthesizeCancelationEventsForConnectionLocked(fromConnection, options);
        }

#if DEBUG_FOCUS
        logDispatchStateLocked();
#endif
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
    return true;
}

void InputDispatcher::resetAndDropEverythingLocked(const char* reason) {
#if DEBUG_FOCUS
    ALOGD("Resetting and dropping all events (%s).", reason);
#endif

    CancelationOptions options(CancelationOptions::CANCEL_ALL_EVENTS, reason);
    synthesizeCancelationEventsForAllConnectionsLocked(options);

    resetKeyRepeatLocked();
    releasePendingEventLocked();
    drainInboundQueueLocked();
    resetANRTimeoutsLocked();

    mTouchState.reset();
    mLastHoverWindowHandle.clear();
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
        ALOGD("%s", start);
        start = end;
    }
}

void InputDispatcher::dumpDispatchStateLocked(String8& dump) {
    dump.appendFormat(INDENT "DispatchEnabled: %d\n", mDispatchEnabled);
    dump.appendFormat(INDENT "DispatchFrozen: %d\n", mDispatchFrozen);

    if (mFocusedApplicationHandle != NULL) {
        dump.appendFormat(INDENT "FocusedApplication: name='%s', dispatchingTimeout=%0.3fms\n",
                mFocusedApplicationHandle->getName().string(),
                mFocusedApplicationHandle->getDispatchingTimeout(
                        DEFAULT_INPUT_DISPATCHING_TIMEOUT) / 1000000.0);
    } else {
        dump.append(INDENT "FocusedApplication: <null>\n");
    }
    dump.appendFormat(INDENT "FocusedWindow: name='%s'\n",
            mFocusedWindowHandle != NULL ? mFocusedWindowHandle->getName().string() : "<null>");

    dump.appendFormat(INDENT "TouchDown: %s\n", toString(mTouchState.down));
    dump.appendFormat(INDENT "TouchSplit: %s\n", toString(mTouchState.split));
    dump.appendFormat(INDENT "TouchDeviceId: %d\n", mTouchState.deviceId);
    dump.appendFormat(INDENT "TouchSource: 0x%08x\n", mTouchState.source);
    dump.appendFormat(INDENT "TouchDisplayId: %d\n", mTouchState.displayId);
    if (!mTouchState.windows.isEmpty()) {
        dump.append(INDENT "TouchedWindows:\n");
        for (size_t i = 0; i < mTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTouchState.windows[i];
            dump.appendFormat(INDENT2 "%d: name='%s', pointerIds=0x%0x, targetFlags=0x%x\n",
                    i, touchedWindow.windowHandle->getName().string(),
                    touchedWindow.pointerIds.value,
                    touchedWindow.targetFlags);
        }
    } else {
        dump.append(INDENT "TouchedWindows: <none>\n");
    }

    if (!mWindowHandles.isEmpty()) {
        dump.append(INDENT "Windows:\n");
        for (size_t i = 0; i < mWindowHandles.size(); i++) {
            const sp<InputWindowHandle>& windowHandle = mWindowHandles.itemAt(i);
            const InputWindowInfo* windowInfo = windowHandle->getInfo();

            dump.appendFormat(INDENT2 "%d: name='%s', displayId=%d, "
                    "paused=%s, hasFocus=%s, hasWallpaper=%s, "
                    "visible=%s, canReceiveKeys=%s, flags=0x%08x, type=0x%08x, layer=%d, "
                    "frame=[%d,%d][%d,%d], scale=%f, "
                    "touchableRegion=",
                    i, windowInfo->name.string(), windowInfo->displayId,
                    toString(windowInfo->paused),
                    toString(windowInfo->hasFocus),
                    toString(windowInfo->hasWallpaper),
                    toString(windowInfo->visible),
                    toString(windowInfo->canReceiveKeys),
                    windowInfo->layoutParamsFlags, windowInfo->layoutParamsType,
                    windowInfo->layer,
                    windowInfo->frameLeft, windowInfo->frameTop,
                    windowInfo->frameRight, windowInfo->frameBottom,
                    windowInfo->scaleFactor);
            dumpRegion(dump, windowInfo->touchableRegion);
            dump.appendFormat(", inputFeatures=0x%08x", windowInfo->inputFeatures);
            dump.appendFormat(", ownerPid=%d, ownerUid=%d, dispatchingTimeout=%0.3fms\n",
                    windowInfo->ownerPid, windowInfo->ownerUid,
                    windowInfo->dispatchingTimeout / 1000000.0);
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

    nsecs_t currentTime = now();

    if (!mInboundQueue.isEmpty()) {
        dump.appendFormat(INDENT "InboundQueue: length=%u\n", mInboundQueue.count());
        for (EventEntry* entry = mInboundQueue.head; entry; entry = entry->next) {
            dump.append(INDENT2);
            entry->appendDescription(dump);
            dump.appendFormat(", age=%0.1fms\n",
                    (currentTime - entry->eventTime) * 0.000001f);
        }
    } else {
        dump.append(INDENT "InboundQueue: <empty>\n");
    }

    if (!mConnectionsByFd.isEmpty()) {
        dump.append(INDENT "Connections:\n");
        for (size_t i = 0; i < mConnectionsByFd.size(); i++) {
            const sp<Connection>& connection = mConnectionsByFd.valueAt(i);
            dump.appendFormat(INDENT2 "%d: channelName='%s', windowName='%s', "
                    "status=%s, monitor=%s, inputPublisherBlocked=%s\n",
                    i, connection->getInputChannelName(), connection->getWindowName(),
                    connection->getStatusLabel(), toString(connection->monitor),
                    toString(connection->inputPublisherBlocked));

            if (!connection->outboundQueue.isEmpty()) {
                dump.appendFormat(INDENT3 "OutboundQueue: length=%u\n",
                        connection->outboundQueue.count());
                for (DispatchEntry* entry = connection->outboundQueue.head; entry;
                        entry = entry->next) {
                    dump.append(INDENT4);
                    entry->eventEntry->appendDescription(dump);
                    dump.appendFormat(", targetFlags=0x%08x, resolvedAction=%d, age=%0.1fms\n",
                            entry->targetFlags, entry->resolvedAction,
                            (currentTime - entry->eventEntry->eventTime) * 0.000001f);
                }
            } else {
                dump.append(INDENT3 "OutboundQueue: <empty>\n");
            }

            if (!connection->waitQueue.isEmpty()) {
                dump.appendFormat(INDENT3 "WaitQueue: length=%u\n",
                        connection->waitQueue.count());
                for (DispatchEntry* entry = connection->waitQueue.head; entry;
                        entry = entry->next) {
                    dump.append(INDENT4);
                    entry->eventEntry->appendDescription(dump);
                    dump.appendFormat(", targetFlags=0x%08x, resolvedAction=%d, "
                            "age=%0.1fms, wait=%0.1fms\n",
                            entry->targetFlags, entry->resolvedAction,
                            (currentTime - entry->eventEntry->eventTime) * 0.000001f,
                            (currentTime - entry->deliveryTime) * 0.000001f);
                }
            } else {
                dump.append(INDENT3 "WaitQueue: <empty>\n");
            }
        }
    } else {
        dump.append(INDENT "Connections: <none>\n");
    }

    if (isAppSwitchPendingLocked()) {
        dump.appendFormat(INDENT "AppSwitch: pending, due in %0.1fms\n",
                (mAppSwitchDueTime - now()) / 1000000.0);
    } else {
        dump.append(INDENT "AppSwitch: not pending\n");
    }

    dump.append(INDENT "Configuration:\n");
    dump.appendFormat(INDENT2 "KeyRepeatDelay: %0.1fms\n",
            mConfig.keyRepeatDelay * 0.000001f);
    dump.appendFormat(INDENT2 "KeyRepeatTimeout: %0.1fms\n",
            mConfig.keyRepeatTimeout * 0.000001f);
}

status_t InputDispatcher::registerInputChannel(const sp<InputChannel>& inputChannel,
        const sp<InputWindowHandle>& inputWindowHandle, bool monitor) {
#if DEBUG_REGISTRATION
    ALOGD("channel '%s' ~ registerInputChannel - monitor=%s", inputChannel->getName().string(),
            toString(monitor));
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        if (getConnectionIndexLocked(inputChannel) >= 0) {
            ALOGW("Attempted to register already registered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        sp<Connection> connection = new Connection(inputChannel, inputWindowHandle, monitor);

        int fd = inputChannel->getFd();
        mConnectionsByFd.add(fd, connection);

        if (monitor) {
            mMonitoringChannels.push(inputChannel);
        }

        mLooper->addFd(fd, 0, ALOOPER_EVENT_INPUT, handleReceiveCallback, this);
    } // release lock

    // Wake the looper because some connections have changed.
    mLooper->wake();
    return OK;
}

status_t InputDispatcher::unregisterInputChannel(const sp<InputChannel>& inputChannel) {
#if DEBUG_REGISTRATION
    ALOGD("channel '%s' ~ unregisterInputChannel", inputChannel->getName().string());
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        status_t status = unregisterInputChannelLocked(inputChannel, false /*notify*/);
        if (status) {
            return status;
        }
    } // release lock

    // Wake the poll loop because removing the connection may have changed the current
    // synchronization state.
    mLooper->wake();
    return OK;
}

status_t InputDispatcher::unregisterInputChannelLocked(const sp<InputChannel>& inputChannel,
        bool notify) {
    ssize_t connectionIndex = getConnectionIndexLocked(inputChannel);
    if (connectionIndex < 0) {
        ALOGW("Attempted to unregister already unregistered input channel '%s'",
                inputChannel->getName().string());
        return BAD_VALUE;
    }

    sp<Connection> connection = mConnectionsByFd.valueAt(connectionIndex);
    mConnectionsByFd.removeItemsAt(connectionIndex);

    if (connection->monitor) {
        removeMonitorChannelLocked(inputChannel);
    }

    mLooper->removeFd(inputChannel->getFd());

    nsecs_t currentTime = now();
    abortBrokenDispatchCycleLocked(currentTime, connection, notify);

    connection->status = Connection::STATUS_ZOMBIE;
    return OK;
}

void InputDispatcher::removeMonitorChannelLocked(const sp<InputChannel>& inputChannel) {
    for (size_t i = 0; i < mMonitoringChannels.size(); i++) {
         if (mMonitoringChannels[i] == inputChannel) {
             mMonitoringChannels.removeAt(i);
             break;
         }
    }
}

ssize_t InputDispatcher::getConnectionIndexLocked(const sp<InputChannel>& inputChannel) {
    ssize_t connectionIndex = mConnectionsByFd.indexOfKey(inputChannel->getFd());
    if (connectionIndex >= 0) {
        sp<Connection> connection = mConnectionsByFd.valueAt(connectionIndex);
        if (connection->inputChannel.get() == inputChannel.get()) {
            return connectionIndex;
        }
    }

    return -1;
}

void InputDispatcher::onDispatchCycleFinishedLocked(
        nsecs_t currentTime, const sp<Connection>& connection, uint32_t seq, bool handled) {
    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doDispatchCycleFinishedLockedInterruptible);
    commandEntry->connection = connection;
    commandEntry->eventTime = currentTime;
    commandEntry->seq = seq;
    commandEntry->handled = handled;
}

void InputDispatcher::onDispatchCycleBrokenLocked(
        nsecs_t currentTime, const sp<Connection>& connection) {
    ALOGE("channel '%s' ~ Channel is unrecoverably broken and will be disposed!",
            connection->getInputChannelName());

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyInputChannelBrokenLockedInterruptible);
    commandEntry->connection = connection;
}

void InputDispatcher::onANRLocked(
        nsecs_t currentTime, const sp<InputApplicationHandle>& applicationHandle,
        const sp<InputWindowHandle>& windowHandle,
        nsecs_t eventTime, nsecs_t waitStartTime, const char* reason) {
    float dispatchLatency = (currentTime - eventTime) * 0.000001f;
    float waitDuration = (currentTime - waitStartTime) * 0.000001f;
    ALOGI("Application is not responding: %s.  "
            "It has been %0.1fms since event, %0.1fms since wait started.  Reason: %s",
            getApplicationWindowLabelLocked(applicationHandle, windowHandle).string(),
            dispatchLatency, waitDuration, reason);

    // Capture a record of the InputDispatcher state at the time of the ANR.
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    char timestr[64];
    strftime(timestr, sizeof(timestr), "%F %T", &tm);
    mLastANRState.clear();
    mLastANRState.append(INDENT "ANR:\n");
    mLastANRState.appendFormat(INDENT2 "Time: %s\n", timestr);
    mLastANRState.appendFormat(INDENT2 "Window: %s\n",
            getApplicationWindowLabelLocked(applicationHandle, windowHandle).string());
    mLastANRState.appendFormat(INDENT2 "DispatchLatency: %0.1fms\n", dispatchLatency);
    mLastANRState.appendFormat(INDENT2 "WaitDuration: %0.1fms\n", waitDuration);
    mLastANRState.appendFormat(INDENT2 "Reason: %s\n", reason);
    dumpDispatchStateLocked(mLastANRState);

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyANRLockedInterruptible);
    commandEntry->inputApplicationHandle = applicationHandle;
    commandEntry->inputWindowHandle = windowHandle;
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

        mPolicy->notifyInputChannelBroken(connection->inputWindowHandle);

        mLock.lock();
    }
}

void InputDispatcher::doNotifyANRLockedInterruptible(
        CommandEntry* commandEntry) {
    mLock.unlock();

    nsecs_t newTimeout = mPolicy->notifyANR(
            commandEntry->inputApplicationHandle, commandEntry->inputWindowHandle);

    mLock.lock();

    resumeAfterTargetsNotReadyTimeoutLocked(newTimeout,
            commandEntry->inputWindowHandle != NULL
                    ? commandEntry->inputWindowHandle->getInputChannel() : NULL);
}

void InputDispatcher::doInterceptKeyBeforeDispatchingLockedInterruptible(
        CommandEntry* commandEntry) {
    KeyEntry* entry = commandEntry->keyEntry;

    KeyEvent event;
    initializeKeyEvent(&event, entry);

    mLock.unlock();

    nsecs_t delay = mPolicy->interceptKeyBeforeDispatching(commandEntry->inputWindowHandle,
            &event, entry->policyFlags);

    mLock.lock();

    if (delay < 0) {
        entry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_SKIP;
    } else if (!delay) {
        entry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_CONTINUE;
    } else {
        entry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_TRY_AGAIN_LATER;
        entry->interceptKeyWakeupTime = now() + delay;
    }
    entry->release();
}

void InputDispatcher::doDispatchCycleFinishedLockedInterruptible(
        CommandEntry* commandEntry) {
    sp<Connection> connection = commandEntry->connection;
    nsecs_t finishTime = commandEntry->eventTime;
    uint32_t seq = commandEntry->seq;
    bool handled = commandEntry->handled;

    // Handle post-event policy actions.
    DispatchEntry* dispatchEntry = connection->findWaitQueueEntry(seq);
    if (dispatchEntry) {
        nsecs_t eventDuration = finishTime - dispatchEntry->deliveryTime;
        if (eventDuration > SLOW_EVENT_PROCESSING_WARNING_TIMEOUT) {
            String8 msg;
            msg.appendFormat("Window '%s' spent %0.1fms processing the last input event: ",
                    connection->getWindowName(), eventDuration * 0.000001f);
            dispatchEntry->eventEntry->appendDescription(msg);
            ALOGI("%s", msg.string());
        }

        bool restartEvent;
        if (dispatchEntry->eventEntry->type == EventEntry::TYPE_KEY) {
            KeyEntry* keyEntry = static_cast<KeyEntry*>(dispatchEntry->eventEntry);
            restartEvent = afterKeyEventLockedInterruptible(connection,
                    dispatchEntry, keyEntry, handled);
        } else if (dispatchEntry->eventEntry->type == EventEntry::TYPE_MOTION) {
            MotionEntry* motionEntry = static_cast<MotionEntry*>(dispatchEntry->eventEntry);
            restartEvent = afterMotionEventLockedInterruptible(connection,
                    dispatchEntry, motionEntry, handled);
        } else {
            restartEvent = false;
        }

        // Dequeue the event and start the next cycle.
        // Note that because the lock might have been released, it is possible that the
        // contents of the wait queue to have been drained, so we need to double-check
        // a few things.
        if (dispatchEntry == connection->findWaitQueueEntry(seq)) {
            connection->waitQueue.dequeue(dispatchEntry);
            traceWaitQueueLengthLocked(connection);
            if (restartEvent && connection->status == Connection::STATUS_NORMAL) {
                connection->outboundQueue.enqueueAtHead(dispatchEntry);
                traceOutboundQueueLengthLocked(connection);
            } else {
                releaseDispatchEntryLocked(dispatchEntry);
            }
        }

        // Start the next dispatch cycle for this connection.
        startDispatchCycleLocked(now(), connection);
    }
}

bool InputDispatcher::afterKeyEventLockedInterruptible(const sp<Connection>& connection,
        DispatchEntry* dispatchEntry, KeyEntry* keyEntry, bool handled) {
    if (!(keyEntry->flags & AKEY_EVENT_FLAG_FALLBACK)) {
        // Get the fallback key state.
        // Clear it out after dispatching the UP.
        int32_t originalKeyCode = keyEntry->keyCode;
        int32_t fallbackKeyCode = connection->inputState.getFallbackKey(originalKeyCode);
        if (keyEntry->action == AKEY_EVENT_ACTION_UP) {
            connection->inputState.removeFallbackKey(originalKeyCode);
        }

        if (handled || !dispatchEntry->hasForegroundTarget()) {
            // If the application handles the original key for which we previously
            // generated a fallback or if the window is not a foreground window,
            // then cancel the associated fallback key, if any.
            if (fallbackKeyCode != -1) {
                // Dispatch the unhandled key to the policy with the cancel flag.
#if DEBUG_OUTBOUND_EVENT_DETAILS
                ALOGD("Unhandled key event: Asking policy to cancel fallback action.  "
                        "keyCode=%d, action=%d, repeatCount=%d, policyFlags=0x%08x",
                        keyEntry->keyCode, keyEntry->action, keyEntry->repeatCount,
                        keyEntry->policyFlags);
#endif
                KeyEvent event;
                initializeKeyEvent(&event, keyEntry);
                event.setFlags(event.getFlags() | AKEY_EVENT_FLAG_CANCELED);

                mLock.unlock();

                mPolicy->dispatchUnhandledKey(connection->inputWindowHandle,
                        &event, keyEntry->policyFlags, &event);

                mLock.lock();

                // Cancel the fallback key.
                if (fallbackKeyCode != AKEYCODE_UNKNOWN) {
                    CancelationOptions options(CancelationOptions::CANCEL_FALLBACK_EVENTS,
                            "application handled the original non-fallback key "
                            "or is no longer a foreground target, "
                            "canceling previously dispatched fallback key");
                    options.keyCode = fallbackKeyCode;
                    synthesizeCancelationEventsForConnectionLocked(connection, options);
                }
                connection->inputState.removeFallbackKey(originalKeyCode);
            }
        } else {
            // If the application did not handle a non-fallback key, first check
            // that we are in a good state to perform unhandled key event processing
            // Then ask the policy what to do with it.
            bool initialDown = keyEntry->action == AKEY_EVENT_ACTION_DOWN
                    && keyEntry->repeatCount == 0;
            if (fallbackKeyCode == -1 && !initialDown) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
                ALOGD("Unhandled key event: Skipping unhandled key event processing "
                        "since this is not an initial down.  "
                        "keyCode=%d, action=%d, repeatCount=%d, policyFlags=0x%08x",
                        originalKeyCode, keyEntry->action, keyEntry->repeatCount,
                        keyEntry->policyFlags);
#endif
                return false;
            }

            // Dispatch the unhandled key to the policy.
#if DEBUG_OUTBOUND_EVENT_DETAILS
            ALOGD("Unhandled key event: Asking policy to perform fallback action.  "
                    "keyCode=%d, action=%d, repeatCount=%d, policyFlags=0x%08x",
                    keyEntry->keyCode, keyEntry->action, keyEntry->repeatCount,
                    keyEntry->policyFlags);
#endif
            KeyEvent event;
            initializeKeyEvent(&event, keyEntry);

            mLock.unlock();

            bool fallback = mPolicy->dispatchUnhandledKey(connection->inputWindowHandle,
                    &event, keyEntry->policyFlags, &event);

            mLock.lock();

            if (connection->status != Connection::STATUS_NORMAL) {
                connection->inputState.removeFallbackKey(originalKeyCode);
                return false;
            }

            // Latch the fallback keycode for this key on an initial down.
            // The fallback keycode cannot change at any other point in the lifecycle.
            if (initialDown) {
                if (fallback) {
                    fallbackKeyCode = event.getKeyCode();
                } else {
                    fallbackKeyCode = AKEYCODE_UNKNOWN;
                }
                connection->inputState.setFallbackKey(originalKeyCode, fallbackKeyCode);
            }

            ALOG_ASSERT(fallbackKeyCode != -1);

            // Cancel the fallback key if the policy decides not to send it anymore.
            // We will continue to dispatch the key to the policy but we will no
            // longer dispatch a fallback key to the application.
            if (fallbackKeyCode != AKEYCODE_UNKNOWN
                    && (!fallback || fallbackKeyCode != event.getKeyCode())) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
                if (fallback) {
                    ALOGD("Unhandled key event: Policy requested to send key %d"
                            "as a fallback for %d, but on the DOWN it had requested "
                            "to send %d instead.  Fallback canceled.",
                            event.getKeyCode(), originalKeyCode, fallbackKeyCode);
                } else {
                    ALOGD("Unhandled key event: Policy did not request fallback for %d, "
                            "but on the DOWN it had requested to send %d.  "
                            "Fallback canceled.",
                            originalKeyCode, fallbackKeyCode);
                }
#endif

                CancelationOptions options(CancelationOptions::CANCEL_FALLBACK_EVENTS,
                        "canceling fallback, policy no longer desires it");
                options.keyCode = fallbackKeyCode;
                synthesizeCancelationEventsForConnectionLocked(connection, options);

                fallback = false;
                fallbackKeyCode = AKEYCODE_UNKNOWN;
                if (keyEntry->action != AKEY_EVENT_ACTION_UP) {
                    connection->inputState.setFallbackKey(originalKeyCode,
                            fallbackKeyCode);
                }
            }

#if DEBUG_OUTBOUND_EVENT_DETAILS
            {
                String8 msg;
                const KeyedVector<int32_t, int32_t>& fallbackKeys =
                        connection->inputState.getFallbackKeys();
                for (size_t i = 0; i < fallbackKeys.size(); i++) {
                    msg.appendFormat(", %d->%d", fallbackKeys.keyAt(i),
                            fallbackKeys.valueAt(i));
                }
                ALOGD("Unhandled key event: %d currently tracked fallback keys%s.",
                        fallbackKeys.size(), msg.string());
            }
#endif

            if (fallback) {
                // Restart the dispatch cycle using the fallback key.
                keyEntry->eventTime = event.getEventTime();
                keyEntry->deviceId = event.getDeviceId();
                keyEntry->source = event.getSource();
                keyEntry->flags = event.getFlags() | AKEY_EVENT_FLAG_FALLBACK;
                keyEntry->keyCode = fallbackKeyCode;
                keyEntry->scanCode = event.getScanCode();
                keyEntry->metaState = event.getMetaState();
                keyEntry->repeatCount = event.getRepeatCount();
                keyEntry->downTime = event.getDownTime();
                keyEntry->syntheticRepeat = false;

#if DEBUG_OUTBOUND_EVENT_DETAILS
                ALOGD("Unhandled key event: Dispatching fallback key.  "
                        "originalKeyCode=%d, fallbackKeyCode=%d, fallbackMetaState=%08x",
                        originalKeyCode, fallbackKeyCode, keyEntry->metaState);
#endif
                return true; // restart the event
            } else {
#if DEBUG_OUTBOUND_EVENT_DETAILS
                ALOGD("Unhandled key event: No fallback key.");
#endif
            }
        }
    }
    return false;
}

bool InputDispatcher::afterMotionEventLockedInterruptible(const sp<Connection>& connection,
        DispatchEntry* dispatchEntry, MotionEntry* motionEntry, bool handled) {
    return false;
}

void InputDispatcher::doPokeUserActivityLockedInterruptible(CommandEntry* commandEntry) {
    mLock.unlock();

    mPolicy->pokeUserActivity(commandEntry->eventTime, commandEntry->userActivityEventType);

    mLock.lock();
}

void InputDispatcher::initializeKeyEvent(KeyEvent* event, const KeyEntry* entry) {
    event->initialize(entry->deviceId, entry->source, entry->action, entry->flags,
            entry->keyCode, entry->scanCode, entry->metaState, entry->repeatCount,
            entry->downTime, entry->eventTime);
}

void InputDispatcher::updateDispatchStatisticsLocked(nsecs_t currentTime, const EventEntry* entry,
        int32_t injectionResult, nsecs_t timeSpentWaitingForApplication) {
    // TODO Write some statistics about how long we spend waiting.
}

void InputDispatcher::traceInboundQueueLengthLocked() {
    if (ATRACE_ENABLED()) {
        ATRACE_INT("iq", mInboundQueue.count());
    }
}

void InputDispatcher::traceOutboundQueueLengthLocked(const sp<Connection>& connection) {
    if (ATRACE_ENABLED()) {
        char counterName[40];
        snprintf(counterName, sizeof(counterName), "oq:%s", connection->getWindowName());
        ATRACE_INT(counterName, connection->outboundQueue.count());
    }
}

void InputDispatcher::traceWaitQueueLengthLocked(const sp<Connection>& connection) {
    if (ATRACE_ENABLED()) {
        char counterName[40];
        snprintf(counterName, sizeof(counterName), "wq:%s", connection->getWindowName());
        ATRACE_INT(counterName, connection->waitQueue.count());
    }
}

void InputDispatcher::dump(String8& dump) {
    AutoMutex _l(mLock);

    dump.append("Input Dispatcher State:\n");
    dumpDispatchStateLocked(dump);

    if (!mLastANRState.isEmpty()) {
        dump.append("\nInput Dispatcher State at time of last ANR:\n");
        dump.append(mLastANRState);
    }
}

void InputDispatcher::monitor() {
    // Acquire and release the lock to ensure that the dispatcher has not deadlocked.
    mLock.lock();
    mLooper->wake();
    mDispatcherIsAliveCondition.wait(mLock);
    mLock.unlock();
}


// --- InputDispatcher::Queue ---

template <typename T>
uint32_t InputDispatcher::Queue<T>::count() const {
    uint32_t result = 0;
    for (const T* entry = head; entry; entry = entry->next) {
        result += 1;
    }
    return result;
}


// --- InputDispatcher::InjectionState ---

InputDispatcher::InjectionState::InjectionState(int32_t injectorPid, int32_t injectorUid) :
        refCount(1),
        injectorPid(injectorPid), injectorUid(injectorUid),
        injectionResult(INPUT_EVENT_INJECTION_PENDING), injectionIsAsync(false),
        pendingForegroundDispatches(0) {
}

InputDispatcher::InjectionState::~InjectionState() {
}

void InputDispatcher::InjectionState::release() {
    refCount -= 1;
    if (refCount == 0) {
        delete this;
    } else {
        ALOG_ASSERT(refCount > 0);
    }
}


// --- InputDispatcher::EventEntry ---

InputDispatcher::EventEntry::EventEntry(int32_t type, nsecs_t eventTime, uint32_t policyFlags) :
        refCount(1), type(type), eventTime(eventTime), policyFlags(policyFlags),
        injectionState(NULL), dispatchInProgress(false) {
}

InputDispatcher::EventEntry::~EventEntry() {
    releaseInjectionState();
}

void InputDispatcher::EventEntry::release() {
    refCount -= 1;
    if (refCount == 0) {
        delete this;
    } else {
        ALOG_ASSERT(refCount > 0);
    }
}

void InputDispatcher::EventEntry::releaseInjectionState() {
    if (injectionState) {
        injectionState->release();
        injectionState = NULL;
    }
}


// --- InputDispatcher::ConfigurationChangedEntry ---

InputDispatcher::ConfigurationChangedEntry::ConfigurationChangedEntry(nsecs_t eventTime) :
        EventEntry(TYPE_CONFIGURATION_CHANGED, eventTime, 0) {
}

InputDispatcher::ConfigurationChangedEntry::~ConfigurationChangedEntry() {
}

void InputDispatcher::ConfigurationChangedEntry::appendDescription(String8& msg) const {
    msg.append("ConfigurationChangedEvent()");
}


// --- InputDispatcher::DeviceResetEntry ---

InputDispatcher::DeviceResetEntry::DeviceResetEntry(nsecs_t eventTime, int32_t deviceId) :
        EventEntry(TYPE_DEVICE_RESET, eventTime, 0),
        deviceId(deviceId) {
}

InputDispatcher::DeviceResetEntry::~DeviceResetEntry() {
}

void InputDispatcher::DeviceResetEntry::appendDescription(String8& msg) const {
    msg.appendFormat("DeviceResetEvent(deviceId=%d)", deviceId);
}


// --- InputDispatcher::KeyEntry ---

InputDispatcher::KeyEntry::KeyEntry(nsecs_t eventTime,
        int32_t deviceId, uint32_t source, uint32_t policyFlags, int32_t action,
        int32_t flags, int32_t keyCode, int32_t scanCode, int32_t metaState,
        int32_t repeatCount, nsecs_t downTime) :
        EventEntry(TYPE_KEY, eventTime, policyFlags),
        deviceId(deviceId), source(source), action(action), flags(flags),
        keyCode(keyCode), scanCode(scanCode), metaState(metaState),
        repeatCount(repeatCount), downTime(downTime),
        syntheticRepeat(false), interceptKeyResult(KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN),
        interceptKeyWakeupTime(0) {
}

InputDispatcher::KeyEntry::~KeyEntry() {
}

void InputDispatcher::KeyEntry::appendDescription(String8& msg) const {
    msg.appendFormat("KeyEvent(action=%d, deviceId=%d, source=0x%08x)",
            action, deviceId, source);
}

void InputDispatcher::KeyEntry::recycle() {
    releaseInjectionState();

    dispatchInProgress = false;
    syntheticRepeat = false;
    interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN;
    interceptKeyWakeupTime = 0;
}


// --- InputDispatcher::MotionEntry ---

InputDispatcher::MotionEntry::MotionEntry(nsecs_t eventTime,
        int32_t deviceId, uint32_t source, uint32_t policyFlags, int32_t action, int32_t flags,
        int32_t metaState, int32_t buttonState,
        int32_t edgeFlags, float xPrecision, float yPrecision,
        nsecs_t downTime, int32_t displayId, uint32_t pointerCount,
        const PointerProperties* pointerProperties, const PointerCoords* pointerCoords) :
        EventEntry(TYPE_MOTION, eventTime, policyFlags),
        eventTime(eventTime),
        deviceId(deviceId), source(source), action(action), flags(flags),
        metaState(metaState), buttonState(buttonState), edgeFlags(edgeFlags),
        xPrecision(xPrecision), yPrecision(yPrecision),
        downTime(downTime), displayId(displayId), pointerCount(pointerCount) {
    for (uint32_t i = 0; i < pointerCount; i++) {
        this->pointerProperties[i].copyFrom(pointerProperties[i]);
        this->pointerCoords[i].copyFrom(pointerCoords[i]);
    }
}

InputDispatcher::MotionEntry::~MotionEntry() {
}

void InputDispatcher::MotionEntry::appendDescription(String8& msg) const {
    msg.appendFormat("MotionEvent(action=%d, deviceId=%d, source=0x%08x, displayId=%d)",
            action, deviceId, source, displayId);
}


// --- InputDispatcher::DispatchEntry ---

volatile int32_t InputDispatcher::DispatchEntry::sNextSeqAtomic;

InputDispatcher::DispatchEntry::DispatchEntry(EventEntry* eventEntry,
        int32_t targetFlags, float xOffset, float yOffset, float scaleFactor) :
        seq(nextSeq()),
        eventEntry(eventEntry), targetFlags(targetFlags),
        xOffset(xOffset), yOffset(yOffset), scaleFactor(scaleFactor),
        deliveryTime(0), resolvedAction(0), resolvedFlags(0) {
    eventEntry->refCount += 1;
}

InputDispatcher::DispatchEntry::~DispatchEntry() {
    eventEntry->release();
}

uint32_t InputDispatcher::DispatchEntry::nextSeq() {
    // Sequence number 0 is reserved and will never be returned.
    uint32_t seq;
    do {
        seq = android_atomic_inc(&sNextSeqAtomic);
    } while (!seq);
    return seq;
}


// --- InputDispatcher::InputState ---

InputDispatcher::InputState::InputState() {
}

InputDispatcher::InputState::~InputState() {
}

bool InputDispatcher::InputState::isNeutral() const {
    return mKeyMementos.isEmpty() && mMotionMementos.isEmpty();
}

bool InputDispatcher::InputState::isHovering(int32_t deviceId, uint32_t source,
        int32_t displayId) const {
    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (memento.deviceId == deviceId
                && memento.source == source
                && memento.displayId == displayId
                && memento.hovering) {
            return true;
        }
    }
    return false;
}

bool InputDispatcher::InputState::trackKey(const KeyEntry* entry,
        int32_t action, int32_t flags) {
    switch (action) {
    case AKEY_EVENT_ACTION_UP: {
        if (entry->flags & AKEY_EVENT_FLAG_FALLBACK) {
            for (size_t i = 0; i < mFallbackKeys.size(); ) {
                if (mFallbackKeys.valueAt(i) == entry->keyCode) {
                    mFallbackKeys.removeItemsAt(i);
                } else {
                    i += 1;
                }
            }
        }
        ssize_t index = findKeyMemento(entry);
        if (index >= 0) {
            mKeyMementos.removeAt(index);
            return true;
        }
        /* FIXME: We can't just drop the key up event because that prevents creating
         * popup windows that are automatically shown when a key is held and then
         * dismissed when the key is released.  The problem is that the popup will
         * not have received the original key down, so the key up will be considered
         * to be inconsistent with its observed state.  We could perhaps handle this
         * by synthesizing a key down but that will cause other problems.
         *
         * So for now, allow inconsistent key up events to be dispatched.
         *
#if DEBUG_OUTBOUND_EVENT_DETAILS
        ALOGD("Dropping inconsistent key up event: deviceId=%d, source=%08x, "
                "keyCode=%d, scanCode=%d",
                entry->deviceId, entry->source, entry->keyCode, entry->scanCode);
#endif
        return false;
        */
        return true;
    }

    case AKEY_EVENT_ACTION_DOWN: {
        ssize_t index = findKeyMemento(entry);
        if (index >= 0) {
            mKeyMementos.removeAt(index);
        }
        addKeyMemento(entry, flags);
        return true;
    }

    default:
        return true;
    }
}

bool InputDispatcher::InputState::trackMotion(const MotionEntry* entry,
        int32_t action, int32_t flags) {
    int32_t actionMasked = action & AMOTION_EVENT_ACTION_MASK;
    switch (actionMasked) {
    case AMOTION_EVENT_ACTION_UP:
    case AMOTION_EVENT_ACTION_CANCEL: {
        ssize_t index = findMotionMemento(entry, false /*hovering*/);
        if (index >= 0) {
            mMotionMementos.removeAt(index);
            return true;
        }
#if DEBUG_OUTBOUND_EVENT_DETAILS
        ALOGD("Dropping inconsistent motion up or cancel event: deviceId=%d, source=%08x, "
                "actionMasked=%d",
                entry->deviceId, entry->source, actionMasked);
#endif
        return false;
    }

    case AMOTION_EVENT_ACTION_DOWN: {
        ssize_t index = findMotionMemento(entry, false /*hovering*/);
        if (index >= 0) {
            mMotionMementos.removeAt(index);
        }
        addMotionMemento(entry, flags, false /*hovering*/);
        return true;
    }

    case AMOTION_EVENT_ACTION_POINTER_UP:
    case AMOTION_EVENT_ACTION_POINTER_DOWN:
    case AMOTION_EVENT_ACTION_MOVE: {
        ssize_t index = findMotionMemento(entry, false /*hovering*/);
        if (index >= 0) {
            MotionMemento& memento = mMotionMementos.editItemAt(index);
            memento.setPointers(entry);
            return true;
        }
        if (actionMasked == AMOTION_EVENT_ACTION_MOVE
                && (entry->source & (AINPUT_SOURCE_CLASS_JOYSTICK
                        | AINPUT_SOURCE_CLASS_NAVIGATION))) {
            // Joysticks and trackballs can send MOVE events without corresponding DOWN or UP.
            return true;
        }
#if DEBUG_OUTBOUND_EVENT_DETAILS
        ALOGD("Dropping inconsistent motion pointer up/down or move event: "
                "deviceId=%d, source=%08x, actionMasked=%d",
                entry->deviceId, entry->source, actionMasked);
#endif
        return false;
    }

    case AMOTION_EVENT_ACTION_HOVER_EXIT: {
        ssize_t index = findMotionMemento(entry, true /*hovering*/);
        if (index >= 0) {
            mMotionMementos.removeAt(index);
            return true;
        }
#if DEBUG_OUTBOUND_EVENT_DETAILS
        ALOGD("Dropping inconsistent motion hover exit event: deviceId=%d, source=%08x",
                entry->deviceId, entry->source);
#endif
        return false;
    }

    case AMOTION_EVENT_ACTION_HOVER_ENTER:
    case AMOTION_EVENT_ACTION_HOVER_MOVE: {
        ssize_t index = findMotionMemento(entry, true /*hovering*/);
        if (index >= 0) {
            mMotionMementos.removeAt(index);
        }
        addMotionMemento(entry, flags, true /*hovering*/);
        return true;
    }

    default:
        return true;
    }
}

ssize_t InputDispatcher::InputState::findKeyMemento(const KeyEntry* entry) const {
    for (size_t i = 0; i < mKeyMementos.size(); i++) {
        const KeyMemento& memento = mKeyMementos.itemAt(i);
        if (memento.deviceId == entry->deviceId
                && memento.source == entry->source
                && memento.keyCode == entry->keyCode
                && memento.scanCode == entry->scanCode) {
            return i;
        }
    }
    return -1;
}

ssize_t InputDispatcher::InputState::findMotionMemento(const MotionEntry* entry,
        bool hovering) const {
    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (memento.deviceId == entry->deviceId
                && memento.source == entry->source
                && memento.displayId == entry->displayId
                && memento.hovering == hovering) {
            return i;
        }
    }
    return -1;
}

void InputDispatcher::InputState::addKeyMemento(const KeyEntry* entry, int32_t flags) {
    mKeyMementos.push();
    KeyMemento& memento = mKeyMementos.editTop();
    memento.deviceId = entry->deviceId;
    memento.source = entry->source;
    memento.keyCode = entry->keyCode;
    memento.scanCode = entry->scanCode;
    memento.metaState = entry->metaState;
    memento.flags = flags;
    memento.downTime = entry->downTime;
    memento.policyFlags = entry->policyFlags;
}

void InputDispatcher::InputState::addMotionMemento(const MotionEntry* entry,
        int32_t flags, bool hovering) {
    mMotionMementos.push();
    MotionMemento& memento = mMotionMementos.editTop();
    memento.deviceId = entry->deviceId;
    memento.source = entry->source;
    memento.flags = flags;
    memento.xPrecision = entry->xPrecision;
    memento.yPrecision = entry->yPrecision;
    memento.downTime = entry->downTime;
    memento.displayId = entry->displayId;
    memento.setPointers(entry);
    memento.hovering = hovering;
    memento.policyFlags = entry->policyFlags;
}

void InputDispatcher::InputState::MotionMemento::setPointers(const MotionEntry* entry) {
    pointerCount = entry->pointerCount;
    for (uint32_t i = 0; i < entry->pointerCount; i++) {
        pointerProperties[i].copyFrom(entry->pointerProperties[i]);
        pointerCoords[i].copyFrom(entry->pointerCoords[i]);
    }
}

void InputDispatcher::InputState::synthesizeCancelationEvents(nsecs_t currentTime,
        Vector<EventEntry*>& outEvents, const CancelationOptions& options) {
    for (size_t i = 0; i < mKeyMementos.size(); i++) {
        const KeyMemento& memento = mKeyMementos.itemAt(i);
        if (shouldCancelKey(memento, options)) {
            outEvents.push(new KeyEntry(currentTime,
                    memento.deviceId, memento.source, memento.policyFlags,
                    AKEY_EVENT_ACTION_UP, memento.flags | AKEY_EVENT_FLAG_CANCELED,
                    memento.keyCode, memento.scanCode, memento.metaState, 0, memento.downTime));
        }
    }

    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (shouldCancelMotion(memento, options)) {
            outEvents.push(new MotionEntry(currentTime,
                    memento.deviceId, memento.source, memento.policyFlags,
                    memento.hovering
                            ? AMOTION_EVENT_ACTION_HOVER_EXIT
                            : AMOTION_EVENT_ACTION_CANCEL,
                    memento.flags, 0, 0, 0,
                    memento.xPrecision, memento.yPrecision, memento.downTime,
                    memento.displayId,
                    memento.pointerCount, memento.pointerProperties, memento.pointerCoords));
        }
    }
}

void InputDispatcher::InputState::clear() {
    mKeyMementos.clear();
    mMotionMementos.clear();
    mFallbackKeys.clear();
}

void InputDispatcher::InputState::copyPointerStateTo(InputState& other) const {
    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (memento.source & AINPUT_SOURCE_CLASS_POINTER) {
            for (size_t j = 0; j < other.mMotionMementos.size(); ) {
                const MotionMemento& otherMemento = other.mMotionMementos.itemAt(j);
                if (memento.deviceId == otherMemento.deviceId
                        && memento.source == otherMemento.source
                        && memento.displayId == otherMemento.displayId) {
                    other.mMotionMementos.removeAt(j);
                } else {
                    j += 1;
                }
            }
            other.mMotionMementos.push(memento);
        }
    }
}

int32_t InputDispatcher::InputState::getFallbackKey(int32_t originalKeyCode) {
    ssize_t index = mFallbackKeys.indexOfKey(originalKeyCode);
    return index >= 0 ? mFallbackKeys.valueAt(index) : -1;
}

void InputDispatcher::InputState::setFallbackKey(int32_t originalKeyCode,
        int32_t fallbackKeyCode) {
    ssize_t index = mFallbackKeys.indexOfKey(originalKeyCode);
    if (index >= 0) {
        mFallbackKeys.replaceValueAt(index, fallbackKeyCode);
    } else {
        mFallbackKeys.add(originalKeyCode, fallbackKeyCode);
    }
}

void InputDispatcher::InputState::removeFallbackKey(int32_t originalKeyCode) {
    mFallbackKeys.removeItem(originalKeyCode);
}

bool InputDispatcher::InputState::shouldCancelKey(const KeyMemento& memento,
        const CancelationOptions& options) {
    if (options.keyCode != -1 && memento.keyCode != options.keyCode) {
        return false;
    }

    if (options.deviceId != -1 && memento.deviceId != options.deviceId) {
        return false;
    }

    switch (options.mode) {
    case CancelationOptions::CANCEL_ALL_EVENTS:
    case CancelationOptions::CANCEL_NON_POINTER_EVENTS:
        return true;
    case CancelationOptions::CANCEL_FALLBACK_EVENTS:
        return memento.flags & AKEY_EVENT_FLAG_FALLBACK;
    default:
        return false;
    }
}

bool InputDispatcher::InputState::shouldCancelMotion(const MotionMemento& memento,
        const CancelationOptions& options) {
    if (options.deviceId != -1 && memento.deviceId != options.deviceId) {
        return false;
    }

    switch (options.mode) {
    case CancelationOptions::CANCEL_ALL_EVENTS:
        return true;
    case CancelationOptions::CANCEL_POINTER_EVENTS:
        return memento.source & AINPUT_SOURCE_CLASS_POINTER;
    case CancelationOptions::CANCEL_NON_POINTER_EVENTS:
        return !(memento.source & AINPUT_SOURCE_CLASS_POINTER);
    default:
        return false;
    }
}


// --- InputDispatcher::Connection ---

InputDispatcher::Connection::Connection(const sp<InputChannel>& inputChannel,
        const sp<InputWindowHandle>& inputWindowHandle, bool monitor) :
        status(STATUS_NORMAL), inputChannel(inputChannel), inputWindowHandle(inputWindowHandle),
        monitor(monitor),
        inputPublisher(inputChannel), inputPublisherBlocked(false) {
}

InputDispatcher::Connection::~Connection() {
}

const char* InputDispatcher::Connection::getWindowName() const {
    if (inputWindowHandle != NULL) {
        return inputWindowHandle->getName().string();
    }
    if (monitor) {
        return "monitor";
    }
    return "?";
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

InputDispatcher::DispatchEntry* InputDispatcher::Connection::findWaitQueueEntry(uint32_t seq) {
    for (DispatchEntry* entry = waitQueue.head; entry != NULL; entry = entry->next) {
        if (entry->seq == seq) {
            return entry;
        }
    }
    return NULL;
}


// --- InputDispatcher::CommandEntry ---

InputDispatcher::CommandEntry::CommandEntry(Command command) :
    command(command), eventTime(0), keyEntry(NULL), userActivityEventType(0),
    seq(0), handled(false) {
}

InputDispatcher::CommandEntry::~CommandEntry() {
}


// --- InputDispatcher::TouchState ---

InputDispatcher::TouchState::TouchState() :
    down(false), split(false), deviceId(-1), source(0), displayId(-1) {
}

InputDispatcher::TouchState::~TouchState() {
}

void InputDispatcher::TouchState::reset() {
    down = false;
    split = false;
    deviceId = -1;
    source = 0;
    displayId = -1;
    windows.clear();
}

void InputDispatcher::TouchState::copyFrom(const TouchState& other) {
    down = other.down;
    split = other.split;
    deviceId = other.deviceId;
    source = other.source;
    displayId = other.displayId;
    windows = other.windows;
}

void InputDispatcher::TouchState::addOrUpdateWindow(const sp<InputWindowHandle>& windowHandle,
        int32_t targetFlags, BitSet32 pointerIds) {
    if (targetFlags & InputTarget::FLAG_SPLIT) {
        split = true;
    }

    for (size_t i = 0; i < windows.size(); i++) {
        TouchedWindow& touchedWindow = windows.editItemAt(i);
        if (touchedWindow.windowHandle == windowHandle) {
            touchedWindow.targetFlags |= targetFlags;
            if (targetFlags & InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT) {
                touchedWindow.targetFlags &= ~InputTarget::FLAG_DISPATCH_AS_IS;
            }
            touchedWindow.pointerIds.value |= pointerIds.value;
            return;
        }
    }

    windows.push();

    TouchedWindow& touchedWindow = windows.editTop();
    touchedWindow.windowHandle = windowHandle;
    touchedWindow.targetFlags = targetFlags;
    touchedWindow.pointerIds = pointerIds;
}

void InputDispatcher::TouchState::removeWindow(const sp<InputWindowHandle>& windowHandle) {
    for (size_t i = 0; i < windows.size(); i++) {
        if (windows.itemAt(i).windowHandle == windowHandle) {
            windows.removeAt(i);
            return;
        }
    }
}

void InputDispatcher::TouchState::filterNonAsIsTouchWindows() {
    for (size_t i = 0 ; i < windows.size(); ) {
        TouchedWindow& window = windows.editItemAt(i);
        if (window.targetFlags & (InputTarget::FLAG_DISPATCH_AS_IS
                | InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER)) {
            window.targetFlags &= ~InputTarget::FLAG_DISPATCH_MASK;
            window.targetFlags |= InputTarget::FLAG_DISPATCH_AS_IS;
            i += 1;
        } else {
            windows.removeAt(i);
        }
    }
}

sp<InputWindowHandle> InputDispatcher::TouchState::getFirstForegroundWindowHandle() const {
    for (size_t i = 0; i < windows.size(); i++) {
        const TouchedWindow& window = windows.itemAt(i);
        if (window.targetFlags & InputTarget::FLAG_FOREGROUND) {
            return window.windowHandle;
        }
    }
    return NULL;
}

bool InputDispatcher::TouchState::isSlippery() const {
    // Must have exactly one foreground window.
    bool haveSlipperyForegroundWindow = false;
    for (size_t i = 0; i < windows.size(); i++) {
        const TouchedWindow& window = windows.itemAt(i);
        if (window.targetFlags & InputTarget::FLAG_FOREGROUND) {
            if (haveSlipperyForegroundWindow
                    || !(window.windowHandle->getInfo()->layoutParamsFlags
                            & InputWindowInfo::FLAG_SLIPPERY)) {
                return false;
            }
            haveSlipperyForegroundWindow = true;
        }
    }
    return haveSlipperyForegroundWindow;
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
