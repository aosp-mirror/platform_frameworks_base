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

#include <cutils/log.h>
#include <ui/InputDispatcher.h>

#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>

namespace android {

// TODO, this needs to be somewhere else, perhaps in the policy
static inline bool isMovementKey(int32_t keyCode) {
    return keyCode == AKEYCODE_DPAD_UP
            || keyCode == AKEYCODE_DPAD_DOWN
            || keyCode == AKEYCODE_DPAD_LEFT
            || keyCode == AKEYCODE_DPAD_RIGHT;
}

static inline nsecs_t now() {
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

// --- InputDispatcher ---

InputDispatcher::InputDispatcher(const sp<InputDispatcherPolicyInterface>& policy) :
    mPolicy(policy) {
    mPollLoop = new PollLoop(false);

    mInboundQueue.head.refCount = -1;
    mInboundQueue.head.type = EventEntry::TYPE_SENTINEL;
    mInboundQueue.head.eventTime = LONG_LONG_MIN;

    mInboundQueue.tail.refCount = -1;
    mInboundQueue.tail.type = EventEntry::TYPE_SENTINEL;
    mInboundQueue.tail.eventTime = LONG_LONG_MAX;

    mKeyRepeatState.lastKeyEntry = NULL;

    mCurrentInputTargetsValid = false;
}

InputDispatcher::~InputDispatcher() {
    resetKeyRepeatLocked();

    while (mConnectionsByReceiveFd.size() != 0) {
        unregisterInputChannel(mConnectionsByReceiveFd.valueAt(0)->inputChannel);
    }

    for (EventEntry* entry = mInboundQueue.head.next; entry != & mInboundQueue.tail; ) {
        EventEntry* next = entry->next;
        mAllocator.releaseEventEntry(next);
        entry = next;
    }
}

void InputDispatcher::dispatchOnce() {
    nsecs_t keyRepeatTimeout = mPolicy->getKeyRepeatTimeout();

    bool skipPoll = false;
    nsecs_t currentTime;
    nsecs_t nextWakeupTime = LONG_LONG_MAX;
    { // acquire lock
        AutoMutex _l(mLock);
        currentTime = now();

        // Reset the key repeat timer whenever we disallow key events, even if the next event
        // is not a key.  This is to ensure that we abort a key repeat if the device is just coming
        // out of sleep.
        // XXX we should handle resetting input state coming out of sleep more generally elsewhere
        if (keyRepeatTimeout < 0) {
            resetKeyRepeatLocked();
        }

        // Detect and process timeouts for all connections and determine if there are any
        // synchronous event dispatches pending.  This step is entirely non-interruptible.
        bool hasPendingSyncTarget = false;
        size_t activeConnectionCount = mActiveConnections.size();
        for (size_t i = 0; i < activeConnectionCount; i++) {
            Connection* connection = mActiveConnections.itemAt(i);

            if (connection->hasPendingSyncTarget()) {
                hasPendingSyncTarget = true;
            }

            nsecs_t connectionTimeoutTime  = connection->nextTimeoutTime;
            if (connectionTimeoutTime <= currentTime) {
                mTimedOutConnections.add(connection);
            } else if (connectionTimeoutTime < nextWakeupTime) {
                nextWakeupTime = connectionTimeoutTime;
            }
        }

        size_t timedOutConnectionCount = mTimedOutConnections.size();
        for (size_t i = 0; i < timedOutConnectionCount; i++) {
            Connection* connection = mTimedOutConnections.itemAt(i);
            timeoutDispatchCycleLocked(currentTime, connection);
            skipPoll = true;
        }
        mTimedOutConnections.clear();

        // If we don't have a pending sync target, then we can begin delivering a new event.
        // (Otherwise we wait for dispatch to complete for that target.)
        if (! hasPendingSyncTarget) {
            if (mInboundQueue.isEmpty()) {
                if (mKeyRepeatState.lastKeyEntry) {
                    if (currentTime >= mKeyRepeatState.nextRepeatTime) {
                        processKeyRepeatLockedInterruptible(currentTime, keyRepeatTimeout);
                        skipPoll = true;
                    } else {
                        if (mKeyRepeatState.nextRepeatTime < nextWakeupTime) {
                            nextWakeupTime = mKeyRepeatState.nextRepeatTime;
                        }
                    }
                }
            } else {
                // Inbound queue has at least one entry.
                // Start processing it but leave it on the queue until later so that the
                // input reader can keep appending samples onto a motion event between the
                // time we started processing it and the time we finally enqueue dispatch
                // entries for it.
                EventEntry* entry = mInboundQueue.head.next;

                switch (entry->type) {
                case EventEntry::TYPE_CONFIGURATION_CHANGED: {
                    ConfigurationChangedEntry* typedEntry =
                            static_cast<ConfigurationChangedEntry*>(entry);
                    processConfigurationChangedLockedInterruptible(currentTime, typedEntry);
                    break;
                }

                case EventEntry::TYPE_KEY: {
                    KeyEntry* typedEntry = static_cast<KeyEntry*>(entry);
                    processKeyLockedInterruptible(currentTime, typedEntry, keyRepeatTimeout);
                    break;
                }

                case EventEntry::TYPE_MOTION: {
                    MotionEntry* typedEntry = static_cast<MotionEntry*>(entry);
                    processMotionLockedInterruptible(currentTime, typedEntry);
                    break;
                }

                default:
                    assert(false);
                    break;
                }

                // Dequeue and release the event entry that we just processed.
                mInboundQueue.dequeue(entry);
                mAllocator.releaseEventEntry(entry);
                skipPoll = true;
            }
        }

        // Run any deferred commands.
        skipPoll |= runCommandsLockedInterruptible();

        // Wake up synchronization waiters, if needed.
        if (isFullySynchronizedLocked()) {
            mFullySynchronizedCondition.broadcast();
        }
    } // release lock

    // If we dispatched anything, don't poll just now.  Wait for the next iteration.
    // Contents may have shifted during flight.
    if (skipPoll) {
        return;
    }

    // Wait for callback or timeout or wake.
    nsecs_t timeout = nanoseconds_to_milliseconds(nextWakeupTime - currentTime);
    int32_t timeoutMillis = timeout > INT_MAX ? -1 : timeout > 0 ? int32_t(timeout) : 0;
    mPollLoop->pollOnce(timeoutMillis);
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

void InputDispatcher::processConfigurationChangedLockedInterruptible(
        nsecs_t currentTime, ConfigurationChangedEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("processConfigurationChanged - eventTime=%lld", entry->eventTime);
#endif

    // Reset key repeating in case a keyboard device was added or removed or something.
    resetKeyRepeatLocked();

    mLock.unlock();

    mPolicy->notifyConfigurationChanged(entry->eventTime);

    mLock.lock();
}

void InputDispatcher::processKeyLockedInterruptible(
        nsecs_t currentTime, KeyEntry* entry, nsecs_t keyRepeatTimeout) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("processKey - eventTime=%lld, deviceId=0x%x, source=0x%x, policyFlags=0x%x, action=0x%x, "
            "flags=0x%x, keyCode=0x%x, scanCode=0x%x, metaState=0x%x, downTime=%lld",
            entry->eventTime, entry->deviceId, entry->source, entry->policyFlags, entry->action,
            entry->flags, entry->keyCode, entry->scanCode, entry->metaState,
            entry->downTime);
#endif

    if (entry->action == AKEY_EVENT_ACTION_DOWN && ! entry->isInjected()) {
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
    } else {
        resetKeyRepeatLocked();
    }

    identifyInputTargetsAndDispatchKeyLockedInterruptible(currentTime, entry);
}

void InputDispatcher::processKeyRepeatLockedInterruptible(
        nsecs_t currentTime, nsecs_t keyRepeatTimeout) {
    KeyEntry* entry = mKeyRepeatState.lastKeyEntry;

    // Search the inbound queue for a key up corresponding to this device.
    // It doesn't make sense to generate a key repeat event if the key is already up.
    for (EventEntry* queuedEntry = mInboundQueue.head.next;
            queuedEntry != & mInboundQueue.tail; queuedEntry = entry->next) {
        if (queuedEntry->type == EventEntry::TYPE_KEY) {
            KeyEntry* queuedKeyEntry = static_cast<KeyEntry*>(queuedEntry);
            if (queuedKeyEntry->deviceId == entry->deviceId
                    && entry->action == AKEY_EVENT_ACTION_UP) {
                resetKeyRepeatLocked();
                return;
            }
        }
    }

    // Synthesize a key repeat after the repeat timeout expired.
    // Reuse the repeated key entry if it is otherwise unreferenced.
    uint32_t policyFlags = entry->policyFlags & POLICY_FLAG_RAW_MASK;
    if (entry->refCount == 1) {
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

    if (entry->repeatCount == 1) {
        entry->flags |= AKEY_EVENT_FLAG_LONG_PRESS;
    }

    mKeyRepeatState.nextRepeatTime = currentTime + keyRepeatTimeout;

#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("processKeyRepeat - eventTime=%lld, deviceId=0x%x, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, keyCode=0x%x, scanCode=0x%x, metaState=0x%x, "
            "repeatCount=%d, downTime=%lld",
            entry->eventTime, entry->deviceId, entry->source, entry->policyFlags,
            entry->action, entry->flags, entry->keyCode, entry->scanCode, entry->metaState,
            entry->repeatCount, entry->downTime);
#endif

    identifyInputTargetsAndDispatchKeyLockedInterruptible(currentTime, entry);
}

void InputDispatcher::processMotionLockedInterruptible(
        nsecs_t currentTime, MotionEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("processMotion - eventTime=%lld, deviceId=0x%x, source=0x%x, policyFlags=0x%x, action=0x%x, "
            "metaState=0x%x, edgeFlags=0x%x, xPrecision=%f, yPrecision=%f, downTime=%lld",
            entry->eventTime, entry->deviceId, entry->source, entry->policyFlags, entry->action,
            entry->metaState, entry->edgeFlags, entry->xPrecision, entry->yPrecision,
            entry->downTime);

    // Print the most recent sample that we have available, this may change due to batching.
    size_t sampleCount = 1;
    MotionSample* sample = & entry->firstSample;
    for (; sample->next != NULL; sample = sample->next) {
        sampleCount += 1;
    }
    for (uint32_t i = 0; i < entry->pointerCount; i++) {
        LOGD("  Pointer %d: id=%d, x=%f, y=%f, pressure=%f, size=%f",
                i, entry->pointerIds[i],
                sample->pointerCoords[i].x,
                sample->pointerCoords[i].y,
                sample->pointerCoords[i].pressure,
                sample->pointerCoords[i].size);
    }

    // Keep in mind that due to batching, it is possible for the number of samples actually
    // dispatched to change before the application finally consumed them.
    if (entry->action == AMOTION_EVENT_ACTION_MOVE) {
        LOGD("  ... Total movement samples currently batched %d ...", sampleCount);
    }
#endif

    identifyInputTargetsAndDispatchMotionLockedInterruptible(currentTime, entry);
}

void InputDispatcher::identifyInputTargetsAndDispatchKeyLockedInterruptible(
        nsecs_t currentTime, KeyEntry* entry) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("identifyInputTargetsAndDispatchKey");
#endif

    entry->dispatchInProgress = true;
    mCurrentInputTargetsValid = false;
    mLock.unlock();

    mReusableKeyEvent.initialize(entry->deviceId, entry->source, entry->action, entry->flags,
            entry->keyCode, entry->scanCode, entry->metaState, entry->repeatCount,
            entry->downTime, entry->eventTime);

    mCurrentInputTargets.clear();
    int32_t injectionResult = mPolicy->waitForKeyEventTargets(& mReusableKeyEvent,
            entry->policyFlags, entry->injectorPid, entry->injectorUid,
            mCurrentInputTargets);

    mLock.lock();
    mCurrentInputTargetsValid = true;

    setInjectionResultLocked(entry, injectionResult);

    if (injectionResult == INPUT_EVENT_INJECTION_SUCCEEDED) {
        dispatchEventToCurrentInputTargetsLocked(currentTime, entry, false);
    }
}

void InputDispatcher::identifyInputTargetsAndDispatchMotionLockedInterruptible(
        nsecs_t currentTime, MotionEntry* entry) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("identifyInputTargetsAndDispatchMotion");
#endif

    entry->dispatchInProgress = true;
    mCurrentInputTargetsValid = false;
    mLock.unlock();

    mReusableMotionEvent.initialize(entry->deviceId, entry->source, entry->action,
            entry->edgeFlags, entry->metaState,
            0, 0, entry->xPrecision, entry->yPrecision,
            entry->downTime, entry->eventTime, entry->pointerCount, entry->pointerIds,
            entry->firstSample.pointerCoords);

    mCurrentInputTargets.clear();
    int32_t injectionResult = mPolicy->waitForMotionEventTargets(& mReusableMotionEvent,
            entry->policyFlags, entry->injectorPid, entry->injectorUid,
            mCurrentInputTargets);

    mLock.lock();
    mCurrentInputTargetsValid = true;

    setInjectionResultLocked(entry, injectionResult);

    if (injectionResult == INPUT_EVENT_INJECTION_SUCCEEDED) {
        dispatchEventToCurrentInputTargetsLocked(currentTime, entry, false);
    }
}

void InputDispatcher::dispatchEventToCurrentInputTargetsLocked(nsecs_t currentTime,
        EventEntry* eventEntry, bool resumeWithAppendedMotionSample) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("dispatchEventToCurrentInputTargets - "
            "resumeWithAppendedMotionSample=%s",
            resumeWithAppendedMotionSample ? "true" : "false");
#endif

    assert(eventEntry->dispatchInProgress); // should already have been set to true

    for (size_t i = 0; i < mCurrentInputTargets.size(); i++) {
        const InputTarget& inputTarget = mCurrentInputTargets.itemAt(i);

        ssize_t connectionIndex = mConnectionsByReceiveFd.indexOfKey(
                inputTarget.inputChannel->getReceivePipeFd());
        if (connectionIndex >= 0) {
            sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
            prepareDispatchCycleLocked(currentTime, connection, eventEntry, & inputTarget,
                    resumeWithAppendedMotionSample);
        } else {
            LOGW("Framework requested delivery of an input event to channel '%s' but it "
                    "is not registered with the input dispatcher.",
                    inputTarget.inputChannel->getName().string());
        }
    }
}

void InputDispatcher::prepareDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, EventEntry* eventEntry, const InputTarget* inputTarget,
        bool resumeWithAppendedMotionSample) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ prepareDispatchCycle - flags=%d, timeout=%lldns, "
            "xOffset=%f, yOffset=%f, resumeWithAppendedMotionSample=%s",
            connection->getInputChannelName(), inputTarget->flags, inputTarget->timeout,
            inputTarget->xOffset, inputTarget->yOffset,
            resumeWithAppendedMotionSample ? "true" : "false");
#endif

    // Skip this event if the connection status is not normal.
    // We don't want to queue outbound events at all if the connection is broken or
    // not responding.
    if (connection->status != Connection::STATUS_NORMAL) {
        LOGV("channel '%s' ~ Dropping event because the channel status is %s",
                connection->getStatusLabel());
        return;
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
            MotionSample* appendedMotionSample = static_cast<MotionEntry*>(eventEntry)->lastSample;
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
    DispatchEntry* dispatchEntry = mAllocator.obtainDispatchEntry(eventEntry); // increments ref
    dispatchEntry->targetFlags = inputTarget->flags;
    dispatchEntry->xOffset = inputTarget->xOffset;
    dispatchEntry->yOffset = inputTarget->yOffset;
    dispatchEntry->timeout = inputTarget->timeout;
    dispatchEntry->inProgress = false;
    dispatchEntry->headMotionSample = NULL;
    dispatchEntry->tailMotionSample = NULL;

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

    DispatchEntry* dispatchEntry = connection->outboundQueue.head.next;
    assert(! dispatchEntry->inProgress);

    // TODO throttle successive ACTION_MOVE motion events for the same device
    //      possible implementation could set a brief poll timeout here and resume starting the
    //      dispatch cycle when elapsed

    // Publish the event.
    status_t status;
    switch (dispatchEntry->eventEntry->type) {
    case EventEntry::TYPE_KEY: {
        KeyEntry* keyEntry = static_cast<KeyEntry*>(dispatchEntry->eventEntry);

        // Apply target flags.
        int32_t action = keyEntry->action;
        int32_t flags = keyEntry->flags;
        if (dispatchEntry->targetFlags & InputTarget::FLAG_CANCEL) {
            flags |= AKEY_EVENT_FLAG_CANCELED;
        }

        // Publish the key event.
        status = connection->inputPublisher.publishKeyEvent(keyEntry->deviceId, keyEntry->source,
                action, flags, keyEntry->keyCode, keyEntry->scanCode,
                keyEntry->metaState, keyEntry->repeatCount, keyEntry->downTime,
                keyEntry->eventTime);

        if (status) {
            LOGE("channel '%s' ~ Could not publish key event, "
                    "status=%d", connection->getInputChannelName(), status);
            abortDispatchCycleLocked(currentTime, connection, true /*broken*/);
            return;
        }
        break;
    }

    case EventEntry::TYPE_MOTION: {
        MotionEntry* motionEntry = static_cast<MotionEntry*>(dispatchEntry->eventEntry);

        // Apply target flags.
        int32_t action = motionEntry->action;
        if (dispatchEntry->targetFlags & InputTarget::FLAG_OUTSIDE) {
            action = AMOTION_EVENT_ACTION_OUTSIDE;
        }
        if (dispatchEntry->targetFlags & InputTarget::FLAG_CANCEL) {
            action = AMOTION_EVENT_ACTION_CANCEL;
        }

        // If headMotionSample is non-NULL, then it points to the first new sample that we
        // were unable to dispatch during the previous cycle so we resume dispatching from
        // that point in the list of motion samples.
        // Otherwise, we just start from the first sample of the motion event.
        MotionSample* firstMotionSample = dispatchEntry->headMotionSample;
        if (! firstMotionSample) {
            firstMotionSample = & motionEntry->firstSample;
        }

        // Publish the motion event and the first motion sample.
        status = connection->inputPublisher.publishMotionEvent(motionEntry->deviceId,
                motionEntry->source, action, motionEntry->edgeFlags, motionEntry->metaState,
                dispatchEntry->xOffset, dispatchEntry->yOffset,
                motionEntry->xPrecision, motionEntry->yPrecision,
                motionEntry->downTime, firstMotionSample->eventTime,
                motionEntry->pointerCount, motionEntry->pointerIds,
                firstMotionSample->pointerCoords);

        if (status) {
            LOGE("channel '%s' ~ Could not publish motion event, "
                    "status=%d", connection->getInputChannelName(), status);
            abortDispatchCycleLocked(currentTime, connection, true /*broken*/);
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
                abortDispatchCycleLocked(currentTime, connection, true /*broken*/);
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
        abortDispatchCycleLocked(currentTime, connection, true /*broken*/);
        return;
    }

    // Record information about the newly started dispatch cycle.
    dispatchEntry->inProgress = true;

    connection->lastEventTime = dispatchEntry->eventEntry->eventTime;
    connection->lastDispatchTime = currentTime;

    nsecs_t timeout = dispatchEntry->timeout;
    connection->setNextTimeoutTime(currentTime, timeout);

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

    // Clear the pending timeout.
    connection->nextTimeoutTime = LONG_LONG_MAX;

    if (connection->status == Connection::STATUS_NOT_RESPONDING) {
        // Recovering from an ANR.
        connection->status = Connection::STATUS_NORMAL;

        // Notify other system components.
        onDispatchCycleFinishedLocked(currentTime, connection, true /*recoveredFromANR*/);
    } else {
        // Normal finish.  Not much to do here.

        // Notify other system components.
        onDispatchCycleFinishedLocked(currentTime, connection, false /*recoveredFromANR*/);
    }

    // Reset the publisher since the event has been consumed.
    // We do this now so that the publisher can release some of its internal resources
    // while waiting for the next dispatch cycle to begin.
    status_t status = connection->inputPublisher.reset();
    if (status) {
        LOGE("channel '%s' ~ Could not reset publisher, status=%d",
                connection->getInputChannelName(), status);
        abortDispatchCycleLocked(currentTime, connection, true /*broken*/);
        return;
    }

    // Start the next dispatch cycle for this connection.
    while (! connection->outboundQueue.isEmpty()) {
        DispatchEntry* dispatchEntry = connection->outboundQueue.head.next;
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
            mAllocator.releaseDispatchEntry(dispatchEntry);
        } else {
            // If the head is not in progress, then we must have already dequeued the in
            // progress event, which means we actually aborted it (due to ANR).
            // So just start the next event for this connection.
            startDispatchCycleLocked(currentTime, connection);
            return;
        }
    }

    // Outbound queue is empty, deactivate the connection.
    deactivateConnectionLocked(connection.get());
}

void InputDispatcher::timeoutDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ timeoutDispatchCycle",
            connection->getInputChannelName());
#endif

    if (connection->status != Connection::STATUS_NORMAL) {
        return;
    }

    // Enter the not responding state.
    connection->status = Connection::STATUS_NOT_RESPONDING;
    connection->lastANRTime = currentTime;

    // Notify other system components.
    // This enqueues a command which will eventually either call
    // resumeAfterTimeoutDispatchCycleLocked or abortDispatchCycleLocked.
    onDispatchCycleANRLocked(currentTime, connection);
}

void InputDispatcher::resumeAfterTimeoutDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, nsecs_t newTimeout) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ resumeAfterTimeoutDispatchCycleLocked",
            connection->getInputChannelName());
#endif

    if (connection->status != Connection::STATUS_NOT_RESPONDING) {
        return;
    }

    // Resume normal dispatch.
    connection->status = Connection::STATUS_NORMAL;
    connection->setNextTimeoutTime(currentTime, newTimeout);
}

void InputDispatcher::abortDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, bool broken) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ abortDispatchCycle - broken=%s",
            connection->getInputChannelName(), broken ? "true" : "false");
#endif

    // Clear the pending timeout.
    connection->nextTimeoutTime = LONG_LONG_MAX;

    // Clear the outbound queue.
    if (! connection->outboundQueue.isEmpty()) {
        do {
            DispatchEntry* dispatchEntry = connection->outboundQueue.dequeueAtHead();
            mAllocator.releaseDispatchEntry(dispatchEntry);
        } while (! connection->outboundQueue.isEmpty());

        deactivateConnectionLocked(connection.get());
    }

    // Handle the case where the connection appears to be unrecoverably broken.
    // Ignore already broken or zombie connections.
    if (broken) {
        if (connection->status == Connection::STATUS_NORMAL
                || connection->status == Connection::STATUS_NOT_RESPONDING) {
            connection->status = Connection::STATUS_BROKEN;

            // Notify other system components.
            onDispatchCycleBrokenLocked(currentTime, connection);
        }
    }
}

bool InputDispatcher::handleReceiveCallback(int receiveFd, int events, void* data) {
    InputDispatcher* d = static_cast<InputDispatcher*>(data);

    { // acquire lock
        AutoMutex _l(d->mLock);

        ssize_t connectionIndex = d->mConnectionsByReceiveFd.indexOfKey(receiveFd);
        if (connectionIndex < 0) {
            LOGE("Received spurious receive callback for unknown input channel.  "
                    "fd=%d, events=0x%x", receiveFd, events);
            return false; // remove the callback
        }

        nsecs_t currentTime = now();

        sp<Connection> connection = d->mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (events & (POLLERR | POLLHUP | POLLNVAL)) {
            LOGE("channel '%s' ~ Consumer closed input channel or an error occurred.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            d->abortDispatchCycleLocked(currentTime, connection, true /*broken*/);
            d->runCommandsLockedInterruptible();
            return false; // remove the callback
        }

        if (! (events & POLLIN)) {
            LOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            return true;
        }

        status_t status = connection->inputPublisher.receiveFinishedSignal();
        if (status) {
            LOGE("channel '%s' ~ Failed to receive finished signal.  status=%d",
                    connection->getInputChannelName(), status);
            d->abortDispatchCycleLocked(currentTime, connection, true /*broken*/);
            d->runCommandsLockedInterruptible();
            return false; // remove the callback
        }

        d->finishDispatchCycleLocked(currentTime, connection);
        d->runCommandsLockedInterruptible();
        return true;
    } // release lock
}

void InputDispatcher::notifyConfigurationChanged(nsecs_t eventTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyConfigurationChanged - eventTime=%lld", eventTime);
#endif

    bool wasEmpty;
    { // acquire lock
        AutoMutex _l(mLock);

        ConfigurationChangedEntry* newEntry = mAllocator.obtainConfigurationChangedEntry(eventTime);

        wasEmpty = mInboundQueue.isEmpty();
        mInboundQueue.enqueueAtTail(newEntry);
    } // release lock

    if (wasEmpty) {
        mPollLoop->wake();
    }
}

void InputDispatcher::notifyAppSwitchComing(nsecs_t eventTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyAppSwitchComing - eventTime=%lld", eventTime);
#endif

    // Remove movement keys from the queue from most recent to least recent, stopping at the
    // first non-movement key.
    // TODO: Include a detailed description of why we do this...

    { // acquire lock
        AutoMutex _l(mLock);

        for (EventEntry* entry = mInboundQueue.tail.prev; entry != & mInboundQueue.head; ) {
            EventEntry* prev = entry->prev;

            if (entry->type == EventEntry::TYPE_KEY) {
                KeyEntry* keyEntry = static_cast<KeyEntry*>(entry);
                if (isMovementKey(keyEntry->keyCode)) {
                    LOGV("Dropping movement key during app switch: keyCode=%d, action=%d",
                            keyEntry->keyCode, keyEntry->action);
                    mInboundQueue.dequeue(keyEntry);

                    setInjectionResultLocked(entry, INPUT_EVENT_INJECTION_FAILED);

                    mAllocator.releaseKeyEntry(keyEntry);
                } else {
                    // stop at last non-movement key
                    break;
                }
            }

            entry = prev;
        }
    } // release lock
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

    bool wasEmpty;
    { // acquire lock
        AutoMutex _l(mLock);

        int32_t repeatCount = 0;
        KeyEntry* newEntry = mAllocator.obtainKeyEntry(eventTime,
                deviceId, source, policyFlags, action, flags, keyCode, scanCode,
                metaState, repeatCount, downTime);

        wasEmpty = mInboundQueue.isEmpty();
        mInboundQueue.enqueueAtTail(newEntry);
    } // release lock

    if (wasEmpty) {
        mPollLoop->wake();
    }
}

void InputDispatcher::notifyMotion(nsecs_t eventTime, int32_t deviceId, int32_t source,
        uint32_t policyFlags, int32_t action, int32_t metaState, int32_t edgeFlags,
        uint32_t pointerCount, const int32_t* pointerIds, const PointerCoords* pointerCoords,
        float xPrecision, float yPrecision, nsecs_t downTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyMotion - eventTime=%lld, deviceId=0x%x, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, metaState=0x%x, edgeFlags=0x%x, xPrecision=%f, yPrecision=%f, "
            "downTime=%lld",
            eventTime, deviceId, source, policyFlags, action, metaState, edgeFlags,
            xPrecision, yPrecision, downTime);
    for (uint32_t i = 0; i < pointerCount; i++) {
        LOGD("  Pointer %d: id=%d, x=%f, y=%f, pressure=%f, size=%f",
                i, pointerIds[i], pointerCoords[i].x, pointerCoords[i].y,
                pointerCoords[i].pressure, pointerCoords[i].size);
    }
#endif

    bool wasEmpty;
    { // acquire lock
        AutoMutex _l(mLock);

        // Attempt batching and streaming of move events.
        if (action == AMOTION_EVENT_ACTION_MOVE) {
            // BATCHING CASE
            //
            // Try to append a move sample to the tail of the inbound queue for this device.
            // Give up if we encounter a non-move motion event for this device since that
            // means we cannot append any new samples until a new motion event has started.
            for (EventEntry* entry = mInboundQueue.tail.prev;
                    entry != & mInboundQueue.head; entry = entry->prev) {
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

                // Sanity check for special case because dispatch is interruptible.
                // The dispatch logic is partially interruptible and releases its lock while
                // identifying targets.  However, as soon as the targets have been identified,
                // the dispatcher proceeds to write a dispatch entry into all relevant outbound
                // queues and then promptly removes the motion entry from the queue.
                //
                // Consequently, we should never observe the case where the inbound queue contains
                // an in-progress motion entry unless the current input targets are invalid
                // (currently being computed).  Check for this!
                assert(! (motionEntry->dispatchInProgress && mCurrentInputTargetsValid));

                return; // done!
            }

            // STREAMING CASE
            //
            // There is no pending motion event (of any kind) for this device in the inbound queue.
            // Search the outbound queues for a synchronously dispatched motion event for this
            // device.  If found, then we append the new sample to that event and then try to
            // push it out to all current targets.  It is possible that some targets will already
            // have consumed the motion event.  This case is automatically handled by the
            // logic in prepareDispatchCycleLocked by tracking where resumption takes place.
            //
            // The reason we look for a synchronously dispatched motion event is because we
            // want to be sure that no other motion events have been dispatched since the move.
            // It's also convenient because it means that the input targets are still valid.
            // This code could be improved to support streaming of asynchronously dispatched
            // motion events (which might be significantly more efficient) but it may become
            // a little more complicated as a result.
            //
            // Note: This code crucially depends on the invariant that an outbound queue always
            //       contains at most one synchronous event and it is always last (but it might
            //       not be first!).
            if (mCurrentInputTargetsValid) {
                for (size_t i = 0; i < mActiveConnections.size(); i++) {
                    Connection* connection = mActiveConnections.itemAt(i);
                    if (! connection->outboundQueue.isEmpty()) {
                        DispatchEntry* dispatchEntry = connection->outboundQueue.tail.prev;
                        if (dispatchEntry->targetFlags & InputTarget::FLAG_SYNC) {
                            if (dispatchEntry->eventEntry->type != EventEntry::TYPE_MOTION) {
                                goto NoBatchingOrStreaming;
                            }

                            MotionEntry* syncedMotionEntry = static_cast<MotionEntry*>(
                                    dispatchEntry->eventEntry);
                            if (syncedMotionEntry->action != AMOTION_EVENT_ACTION_MOVE
                                    || syncedMotionEntry->deviceId != deviceId
                                    || syncedMotionEntry->pointerCount != pointerCount
                                    || syncedMotionEntry->isInjected()) {
                                goto NoBatchingOrStreaming;
                            }

                            // Found synced move entry.  Append sample and resume dispatch.
                            mAllocator.appendMotionSample(syncedMotionEntry, eventTime,
                                    pointerCoords);
    #if DEBUG_BATCHING
                            LOGD("Appended motion sample onto batch for most recent synchronously "
                                    "dispatched motion event for this device in the outbound queues.");
    #endif
                            nsecs_t currentTime = now();
                            dispatchEventToCurrentInputTargetsLocked(currentTime, syncedMotionEntry,
                                    true /*resumeWithAppendedMotionSample*/);

                            runCommandsLockedInterruptible();
                            return; // done!
                        }
                    }
                }
            }

NoBatchingOrStreaming:;
        }

        // Just enqueue a new motion event.
        MotionEntry* newEntry = mAllocator.obtainMotionEntry(eventTime,
                deviceId, source, policyFlags, action, metaState, edgeFlags,
                xPrecision, yPrecision, downTime,
                pointerCount, pointerIds, pointerCoords);

        wasEmpty = mInboundQueue.isEmpty();
        mInboundQueue.enqueueAtTail(newEntry);
    } // release lock

    if (wasEmpty) {
        mPollLoop->wake();
    }
}

int32_t InputDispatcher::injectInputEvent(const InputEvent* event,
        int32_t injectorPid, int32_t injectorUid, bool sync, int32_t timeoutMillis) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("injectInputEvent - eventType=%d, injectorPid=%d, injectorUid=%d, "
            "sync=%d, timeoutMillis=%d",
            event->getType(), injectorPid, injectorUid, sync, timeoutMillis);
#endif

    nsecs_t endTime = now() + milliseconds_to_nanoseconds(timeoutMillis);

    EventEntry* injectedEntry;
    bool wasEmpty;
    { // acquire lock
        AutoMutex _l(mLock);

        injectedEntry = createEntryFromInputEventLocked(event);
        injectedEntry->refCount += 1;
        injectedEntry->injectorPid = injectorPid;
        injectedEntry->injectorUid = injectorUid;

        wasEmpty = mInboundQueue.isEmpty();
        mInboundQueue.enqueueAtTail(injectedEntry);

    } // release lock

    if (wasEmpty) {
        mPollLoop->wake();
    }

    int32_t injectionResult;
    { // acquire lock
        AutoMutex _l(mLock);

        for (;;) {
            injectionResult = injectedEntry->injectionResult;
            if (injectionResult != INPUT_EVENT_INJECTION_PENDING) {
                break;
            }

            nsecs_t remainingTimeout = endTime - now();
            if (remainingTimeout <= 0) {
                injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                sync = false;
                break;
            }

            mInjectionResultAvailableCondition.waitRelative(mLock, remainingTimeout);
        }

        if (sync) {
            while (! isFullySynchronizedLocked()) {
                nsecs_t remainingTimeout = endTime - now();
                if (remainingTimeout <= 0) {
                    injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                    break;
                }

                mFullySynchronizedCondition.waitRelative(mLock, remainingTimeout);
            }
        }

        mAllocator.releaseEventEntry(injectedEntry);
    } // release lock

    return injectionResult;
}

void InputDispatcher::setInjectionResultLocked(EventEntry* entry, int32_t injectionResult) {
    if (entry->isInjected()) {
#if DEBUG_INJECTION
        LOGD("Setting input event injection result to %d.  "
                "injectorPid=%d, injectorUid=%d",
                 injectionResult, entry->injectorPid, entry->injectorUid);
#endif

        entry->injectionResult = injectionResult;
        mInjectionResultAvailableCondition.broadcast();
    }
}

bool InputDispatcher::isFullySynchronizedLocked() {
    return mInboundQueue.isEmpty() && mActiveConnections.isEmpty();
}

InputDispatcher::EventEntry* InputDispatcher::createEntryFromInputEventLocked(
        const InputEvent* event) {
    switch (event->getType()) {
    case AINPUT_EVENT_TYPE_KEY: {
        const KeyEvent* keyEvent = static_cast<const KeyEvent*>(event);
        uint32_t policyFlags = 0; // XXX consider adding a policy flag to track injected events

        KeyEntry* keyEntry = mAllocator.obtainKeyEntry(keyEvent->getEventTime(),
                keyEvent->getDeviceId(), keyEvent->getSource(), policyFlags,
                keyEvent->getAction(), keyEvent->getFlags(),
                keyEvent->getKeyCode(), keyEvent->getScanCode(), keyEvent->getMetaState(),
                keyEvent->getRepeatCount(), keyEvent->getDownTime());
        return keyEntry;
    }

    case AINPUT_EVENT_TYPE_MOTION: {
        const MotionEvent* motionEvent = static_cast<const MotionEvent*>(event);
        uint32_t policyFlags = 0; // XXX consider adding a policy flag to track injected events

        const nsecs_t* sampleEventTimes = motionEvent->getSampleEventTimes();
        const PointerCoords* samplePointerCoords = motionEvent->getSamplePointerCoords();
        size_t pointerCount = motionEvent->getPointerCount();

        MotionEntry* motionEntry = mAllocator.obtainMotionEntry(*sampleEventTimes,
                motionEvent->getDeviceId(), motionEvent->getSource(), policyFlags,
                motionEvent->getAction(), motionEvent->getMetaState(), motionEvent->getEdgeFlags(),
                motionEvent->getXPrecision(), motionEvent->getYPrecision(),
                motionEvent->getDownTime(), uint32_t(pointerCount),
                motionEvent->getPointerIds(), samplePointerCoords);
        for (size_t i = motionEvent->getHistorySize(); i > 0; i--) {
            sampleEventTimes += 1;
            samplePointerCoords += pointerCount;
            mAllocator.appendMotionSample(motionEntry, *sampleEventTimes, samplePointerCoords);
        }
        return motionEntry;
    }

    default:
        assert(false);
        return NULL;
    }
}

void InputDispatcher::resetKeyRepeatLocked() {
    if (mKeyRepeatState.lastKeyEntry) {
        mAllocator.releaseKeyEntry(mKeyRepeatState.lastKeyEntry);
        mKeyRepeatState.lastKeyEntry = NULL;
    }
}

void InputDispatcher::preemptInputDispatch() {
#if DEBUG_DISPATCH_CYCLE
    LOGD("preemptInputDispatch");
#endif

    bool preemptedOne = false;
    { // acquire lock
        AutoMutex _l(mLock);

        for (size_t i = 0; i < mActiveConnections.size(); i++) {
            Connection* connection = mActiveConnections[i];
            if (connection->hasPendingSyncTarget()) {
#if DEBUG_DISPATCH_CYCLE
                LOGD("channel '%s' ~ Preempted pending synchronous dispatch",
                        connection->getInputChannelName());
#endif
                connection->outboundQueue.tail.prev->targetFlags &= ~ InputTarget::FLAG_SYNC;
                preemptedOne = true;
            }
        }
    } // release lock

    if (preemptedOne) {
        // Wake up the poll loop so it can get a head start dispatching the next event.
        mPollLoop->wake();
    }
}

status_t InputDispatcher::registerInputChannel(const sp<InputChannel>& inputChannel) {
#if DEBUG_REGISTRATION
    LOGD("channel '%s' ~ registerInputChannel", inputChannel->getName().string());
#endif

    int receiveFd;
    { // acquire lock
        AutoMutex _l(mLock);

        receiveFd = inputChannel->getReceivePipeFd();
        if (mConnectionsByReceiveFd.indexOfKey(receiveFd) >= 0) {
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

        mConnectionsByReceiveFd.add(receiveFd, connection);

        runCommandsLockedInterruptible();
    } // release lock

    mPollLoop->setCallback(receiveFd, POLLIN, handleReceiveCallback, this);
    return OK;
}

status_t InputDispatcher::unregisterInputChannel(const sp<InputChannel>& inputChannel) {
#if DEBUG_REGISTRATION
    LOGD("channel '%s' ~ unregisterInputChannel", inputChannel->getName().string());
#endif

    int32_t receiveFd;
    { // acquire lock
        AutoMutex _l(mLock);

        receiveFd = inputChannel->getReceivePipeFd();
        ssize_t connectionIndex = mConnectionsByReceiveFd.indexOfKey(receiveFd);
        if (connectionIndex < 0) {
            LOGW("Attempted to unregister already unregistered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        mConnectionsByReceiveFd.removeItemsAt(connectionIndex);

        connection->status = Connection::STATUS_ZOMBIE;

        nsecs_t currentTime = now();
        abortDispatchCycleLocked(currentTime, connection, true /*broken*/);

        runCommandsLockedInterruptible();
    } // release lock

    mPollLoop->removeCallback(receiveFd);

    // Wake the poll loop because removing the connection may have changed the current
    // synchronization state.
    mPollLoop->wake();
    return OK;
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
        nsecs_t currentTime, const sp<Connection>& connection, bool recoveredFromANR) {
    if (recoveredFromANR) {
        LOGI("channel '%s' ~ Recovered from ANR.  %01.1fms since event, "
                "%01.1fms since dispatch, %01.1fms since ANR",
                connection->getInputChannelName(),
                connection->getEventLatencyMillis(currentTime),
                connection->getDispatchLatencyMillis(currentTime),
                connection->getANRLatencyMillis(currentTime));

        CommandEntry* commandEntry = postCommandLocked(
                & InputDispatcher::doNotifyInputChannelRecoveredFromANRLockedInterruptible);
        commandEntry->connection = connection;
    }
}

void InputDispatcher::onDispatchCycleANRLocked(
        nsecs_t currentTime, const sp<Connection>& connection) {
    LOGI("channel '%s' ~ Not responding!  %01.1fms since event, %01.1fms since dispatch",
            connection->getInputChannelName(),
            connection->getEventLatencyMillis(currentTime),
            connection->getDispatchLatencyMillis(currentTime));

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyInputChannelANRLockedInterruptible);
    commandEntry->connection = connection;
}

void InputDispatcher::onDispatchCycleBrokenLocked(
        nsecs_t currentTime, const sp<Connection>& connection) {
    LOGE("channel '%s' ~ Channel is unrecoverably broken and will be disposed!",
            connection->getInputChannelName());

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyInputChannelBrokenLockedInterruptible);
    commandEntry->connection = connection;
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

void InputDispatcher::doNotifyInputChannelANRLockedInterruptible(
        CommandEntry* commandEntry) {
    sp<Connection> connection = commandEntry->connection;

    if (connection->status != Connection::STATUS_ZOMBIE) {
        mLock.unlock();

        nsecs_t newTimeout;
        bool resume = mPolicy->notifyInputChannelANR(connection->inputChannel, newTimeout);

        mLock.lock();

        nsecs_t currentTime = now();
        if (resume) {
            resumeAfterTimeoutDispatchCycleLocked(currentTime, connection, newTimeout);
        } else {
            abortDispatchCycleLocked(currentTime, connection, false /*(not) broken*/);
        }
    }
}

void InputDispatcher::doNotifyInputChannelRecoveredFromANRLockedInterruptible(
        CommandEntry* commandEntry) {
    sp<Connection> connection = commandEntry->connection;

    if (connection->status != Connection::STATUS_ZOMBIE) {
        mLock.unlock();

        mPolicy->notifyInputChannelRecoveredFromANR(connection->inputChannel);

        mLock.lock();
    }
}


// --- InputDispatcher::Allocator ---

InputDispatcher::Allocator::Allocator() {
}

void InputDispatcher::Allocator::initializeEventEntry(EventEntry* entry, int32_t type,
        nsecs_t eventTime) {
    entry->type = type;
    entry->refCount = 1;
    entry->dispatchInProgress = false;
    entry->eventTime = eventTime;
    entry->injectionResult = INPUT_EVENT_INJECTION_PENDING;
    entry->injectorPid = -1;
    entry->injectorUid = -1;
}

InputDispatcher::ConfigurationChangedEntry*
InputDispatcher::Allocator::obtainConfigurationChangedEntry(nsecs_t eventTime) {
    ConfigurationChangedEntry* entry = mConfigurationChangeEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_CONFIGURATION_CHANGED, eventTime);
    return entry;
}

InputDispatcher::KeyEntry* InputDispatcher::Allocator::obtainKeyEntry(nsecs_t eventTime,
        int32_t deviceId, int32_t source, uint32_t policyFlags, int32_t action,
        int32_t flags, int32_t keyCode, int32_t scanCode, int32_t metaState,
        int32_t repeatCount, nsecs_t downTime) {
    KeyEntry* entry = mKeyEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_KEY, eventTime);

    entry->deviceId = deviceId;
    entry->source = source;
    entry->policyFlags = policyFlags;
    entry->action = action;
    entry->flags = flags;
    entry->keyCode = keyCode;
    entry->scanCode = scanCode;
    entry->metaState = metaState;
    entry->repeatCount = repeatCount;
    entry->downTime = downTime;
    return entry;
}

InputDispatcher::MotionEntry* InputDispatcher::Allocator::obtainMotionEntry(nsecs_t eventTime,
        int32_t deviceId, int32_t source, uint32_t policyFlags, int32_t action,
        int32_t metaState, int32_t edgeFlags, float xPrecision, float yPrecision,
        nsecs_t downTime, uint32_t pointerCount,
        const int32_t* pointerIds, const PointerCoords* pointerCoords) {
    MotionEntry* entry = mMotionEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_MOTION, eventTime);

    entry->eventTime = eventTime;
    entry->deviceId = deviceId;
    entry->source = source;
    entry->policyFlags = policyFlags;
    entry->action = action;
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
        EventEntry* eventEntry) {
    DispatchEntry* entry = mDispatchEntryPool.alloc();
    entry->eventEntry = eventEntry;
    eventEntry->refCount += 1;
    return entry;
}

InputDispatcher::CommandEntry* InputDispatcher::Allocator::obtainCommandEntry(Command command) {
    CommandEntry* entry = mCommandEntryPool.alloc();
    entry->command = command;
    return entry;
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
        mConfigurationChangeEntryPool.free(entry);
    } else {
        assert(entry->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseKeyEntry(KeyEntry* entry) {
    entry->refCount -= 1;
    if (entry->refCount == 0) {
        mKeyEntryPool.free(entry);
    } else {
        assert(entry->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseMotionEntry(MotionEntry* entry) {
    entry->refCount -= 1;
    if (entry->refCount == 0) {
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

// --- InputDispatcher::Connection ---

InputDispatcher::Connection::Connection(const sp<InputChannel>& inputChannel) :
        status(STATUS_NORMAL), inputChannel(inputChannel), inputPublisher(inputChannel),
        nextTimeoutTime(LONG_LONG_MAX),
        lastEventTime(LONG_LONG_MAX), lastDispatchTime(LONG_LONG_MAX),
        lastANRTime(LONG_LONG_MAX) {
}

InputDispatcher::Connection::~Connection() {
}

status_t InputDispatcher::Connection::initialize() {
    return inputPublisher.initialize();
}

void InputDispatcher::Connection::setNextTimeoutTime(nsecs_t currentTime, nsecs_t timeout) {
    nextTimeoutTime = (timeout >= 0) ? currentTime + timeout : LONG_LONG_MAX;
}

const char* InputDispatcher::Connection::getStatusLabel() const {
    switch (status) {
    case STATUS_NORMAL:
        return "NORMAL";

    case STATUS_BROKEN:
        return "BROKEN";

    case STATUS_NOT_RESPONDING:
        return "NOT_RESPONDING";

    case STATUS_ZOMBIE:
        return "ZOMBIE";

    default:
        return "UNKNOWN";
    }
}

InputDispatcher::DispatchEntry* InputDispatcher::Connection::findQueuedDispatchEntryForEvent(
        const EventEntry* eventEntry) const {
    for (DispatchEntry* dispatchEntry = outboundQueue.tail.prev;
            dispatchEntry != & outboundQueue.head; dispatchEntry = dispatchEntry->prev) {
        if (dispatchEntry->eventEntry == eventEntry) {
            return dispatchEntry;
        }
    }
    return NULL;
}

// --- InputDispatcher::CommandEntry ---

InputDispatcher::CommandEntry::CommandEntry() {
}

InputDispatcher::CommandEntry::~CommandEntry() {
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
