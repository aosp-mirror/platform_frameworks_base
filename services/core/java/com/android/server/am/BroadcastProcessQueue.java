/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static com.android.internal.util.Preconditions.checkState;
import static com.android.server.am.BroadcastRecord.deliveryStateToString;
import static com.android.server.am.BroadcastRecord.isReceiverEquals;

import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.IndentingPrintWriter;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;

import dalvik.annotation.optimization.NeverCompile;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;

/**
 * Queue of pending {@link BroadcastRecord} entries intended for delivery to a
 * specific process.
 * <p>
 * Each queue has a concept of being "runnable at" a particular time in the
 * future, which supports arbitrarily pausing or delaying delivery on a
 * per-process basis.
 * <p>
 * Internally each queue consists of a pending broadcasts which are waiting to
 * be dispatched, and a single active broadcast which is currently being
 * dispatched.
 * <p>
 * This entire class is marked as {@code NotThreadSafe} since it's the
 * responsibility of the caller to always interact with a relevant lock held.
 */
// @NotThreadSafe
class BroadcastProcessQueue {
    static final boolean VERBOSE = false;
    final @NonNull BroadcastConstants constants;
    final @NonNull String processName;
    final int uid;

    /**
     * Linked list connection to another process under this {@link #uid} which
     * has a different {@link #processName}.
     */
    @Nullable BroadcastProcessQueue processNameNext;

    /**
     * Linked list connections to runnable process with lower and higher
     * {@link #getRunnableAt()} times.
     */
    @Nullable BroadcastProcessQueue runnableAtNext;
    @Nullable BroadcastProcessQueue runnableAtPrev;

    /**
     * Currently known details about the target process; typically undefined
     * when the process isn't actively running.
     */
    @Nullable ProcessRecord app;

    /**
     * Track name to use for {@link Trace} events, defined as part of upgrading
     * into a running slot.
     */
    @Nullable String runningTraceTrackName;

    /**
     * Flag indicating if this process should be OOM adjusted, defined as part
     * of upgrading into a running slot.
     */
    boolean runningOomAdjusted;

    /**
     * True if a timer has been started against this queue.
     */
    private boolean mTimeoutScheduled;

    /**
     * Snapshotted value of {@link ProcessRecord#getCpuDelayTime()}, typically
     * used when deciding if we should extend the soft ANR timeout.
     *
     * Required when Flags.anrTimerServiceEnabled is false.
     */
    long lastCpuDelayTime;

     /**
     * Snapshotted value of {@link ProcessStateRecord#getCurProcState()} before
     * dispatching the current broadcast to the receiver in this process.
     */
    int lastProcessState;

    /**
     * Ordered collection of broadcasts that are waiting to be dispatched to
     * this process, as a pair of {@link BroadcastRecord} and the index into
     * {@link BroadcastRecord#receivers} that represents the receiver.
     */
    private final ArrayDeque<SomeArgs> mPending = new ArrayDeque<>();

    /**
     * Ordered collection of "urgent" broadcasts that are waiting to be
     * dispatched to this process, in the same representation as
     * {@link #mPending}.
     */
    private final ArrayDeque<SomeArgs> mPendingUrgent = new ArrayDeque<>(4);

    /**
     * Ordered collection of "offload" broadcasts that are waiting to be
     * dispatched to this process, in the same representation as
     * {@link #mPending}.
     */
    private final ArrayDeque<SomeArgs> mPendingOffload = new ArrayDeque<>(4);

    /**
     * Broadcast actively being dispatched to this process.
     */
    private @Nullable BroadcastRecord mActive;

    /**
     * Receiver actively being dispatched to in this process. This is an index
     * into the {@link BroadcastRecord#receivers} list of {@link #mActive}.
     */
    private int mActiveIndex;

    /**
     * True if the broadcast actively being dispatched to this process was re-enqueued previously.
     */
    private boolean mActiveReEnqueued;

    /**
     * Count of {@link #mActive} broadcasts that have been dispatched since this
     * queue was last idle.
     */
    private int mActiveCountSinceIdle;

    /**
     * Count of {@link #mActive} broadcasts with assumed delivery that have been dispatched
     * since this queue was last idle.
     */
    private int mActiveAssumedDeliveryCountSinceIdle;

    /**
     * Flag indicating that the currently active broadcast is being dispatched
     * was scheduled via a cold start.
     */
    private boolean mActiveViaColdStart;

    /**
     * Flag indicating that the currently active broadcast is being dispatched
     * to a package that was in the stopped state.
     */
    private boolean mActiveWasStopped;

    /**
     * Number of consecutive urgent broadcasts that have been dispatched
     * since the last non-urgent dispatch.
     */
    private int mActiveCountConsecutiveUrgent;

    /**
     * Number of consecutive normal broadcasts that have been dispatched
     * since the last offload dispatch.
     */
    private int mActiveCountConsecutiveNormal;

    /**
     * Count of pending broadcasts of these various flavors.
     */
    private int mCountEnqueued;
    private int mCountDeferred;
    private int mCountForeground;
    private int mCountForegroundDeferred;
    private int mCountOrdered;
    private int mCountAlarm;
    private int mCountPrioritized;
    private int mCountPrioritizedDeferred;
    private int mCountInteractive;
    private int mCountResultTo;
    private int mCountInstrumented;
    private int mCountManifest;

    private int mCountPrioritizeEarliestRequests;

    private @UptimeMillisLong long mRunnableAt = Long.MAX_VALUE;
    private @Reason int mRunnableAtReason = REASON_EMPTY;
    private boolean mRunnableAtInvalidated;

    /**
     * Last state applied by {@link #updateDeferredStates}, used to quickly
     * determine if a state transition is occurring.
     */
    private boolean mLastDeferredStates;

    private boolean mUidForeground;
    private boolean mProcessFreezable;
    private boolean mProcessInstrumented;
    private boolean mProcessPersistent;

    private String mCachedToString;
    private String mCachedToShortString;

    /**
     * The duration by which any broadcasts to this process need to be delayed
     */
    private long mForcedDelayedDurationMs;

    public BroadcastProcessQueue(@NonNull BroadcastConstants constants,
            @NonNull String processName, int uid) {
        this.constants = Objects.requireNonNull(constants);
        this.processName = Objects.requireNonNull(processName);
        this.uid = uid;
    }

    private @NonNull ArrayDeque<SomeArgs> getQueueForBroadcast(@NonNull BroadcastRecord record) {
        if (record.isUrgent()) {
            return mPendingUrgent;
        } else if (record.isOffload()) {
            return mPendingOffload;
        } else {
            return mPending;
        }
    }

    /**
     * Enqueue the given broadcast to be dispatched to this process at some
     * future point in time. The target receiver is indicated by the given index
     * into {@link BroadcastRecord#receivers}.
     * <p>
     * If the broadcast is marked as {@link BroadcastRecord#isReplacePending()},
     * then this call will replace any pending dispatch; otherwise it will
     * enqueue as a normal broadcast.
     * <p>
     * When defined, this receiver is considered "blocked" until at least the
     * given count of other receivers have reached a terminal state; typically
     * used for ordered broadcasts and priority traunches.
     *
     * @return the existing broadcast record in the queue that was replaced with a newer broadcast
     *         sent with {@link Intent#FLAG_RECEIVER_REPLACE_PENDING} or {@code null} if there
     *         wasn't any broadcast that was replaced.
     */
    @Nullable
    public BroadcastRecord enqueueOrReplaceBroadcast(@NonNull BroadcastRecord record,
            int recordIndex, @NonNull BroadcastConsumer deferredStatesApplyConsumer) {
        // Ignore FLAG_RECEIVER_REPLACE_PENDING if the sender specified the policy using the
        // BroadcastOptions delivery group APIs.
        if (record.isReplacePending()
                && record.getDeliveryGroupPolicy() == BroadcastOptions.DELIVERY_GROUP_POLICY_ALL) {
            final BroadcastRecord replacedBroadcastRecord = replaceBroadcast(record, recordIndex);
            if (replacedBroadcastRecord != null) {
                return replacedBroadcastRecord;
            }
        }

        // Caller isn't interested in replacing, or we didn't find any pending
        // item to replace above, so enqueue as a new broadcast
        SomeArgs newBroadcastArgs = SomeArgs.obtain();
        newBroadcastArgs.arg1 = record;
        newBroadcastArgs.argi1 = recordIndex;

        // Cross-broadcast prioritization policy:  some broadcasts might warrant being
        // issued ahead of others that are already pending, for example if this new
        // broadcast is in a different delivery class or is tied to a direct user interaction
        // with implicit responsiveness expectations.
        getQueueForBroadcast(record).addLast(newBroadcastArgs);
        onBroadcastEnqueued(record, recordIndex);

        // When updateDeferredStates() has already applied a deferred state to
        // all pending items, apply to this new broadcast too
        if (mLastDeferredStates && shouldBeDeferred()
                && (record.getDeliveryState(recordIndex) == BroadcastRecord.DELIVERY_PENDING)) {
            deferredStatesApplyConsumer.accept(record, recordIndex);
        }
        return null;
    }

    /**
     * Re-enqueue the active broadcast so that it can be made active and delivered again. In order
     * to keep its previous position same to avoid issues with reordering, insert it at the head
     * of the queue.
     *
     * Callers are responsible for clearing the active broadcast by calling
     * {@link #makeActiveIdle()} after re-enqueuing it.
     */
    public void reEnqueueActiveBroadcast() {
        final BroadcastRecord record = getActive();
        final int recordIndex = getActiveIndex();

        final SomeArgs broadcastArgs = SomeArgs.obtain();
        broadcastArgs.arg1 = record;
        broadcastArgs.argi1 = recordIndex;
        broadcastArgs.argi2 = 1;
        getQueueForBroadcast(record).addFirst(broadcastArgs);
        onBroadcastEnqueued(record, recordIndex);
    }

    /**
     * Searches from newest to oldest in the pending broadcast queues, and at the first matching
     * pending broadcast it finds, replaces it in-place and returns -- does not attempt to handle
     * "duplicate" broadcasts in the queue.
     *
     * @return the existing broadcast record in the queue that was replaced with a newer broadcast
     *         sent with {@link Intent#FLAG_RECEIVER_REPLACE_PENDING} or {@code null} if there
     *         wasn't any broadcast that was replaced.
     */
    @Nullable
    private BroadcastRecord replaceBroadcast(@NonNull BroadcastRecord record, int recordIndex) {
        final ArrayDeque<SomeArgs> queue = getQueueForBroadcast(record);
        return replaceBroadcastInQueue(queue, record, recordIndex);
    }

    /**
     * Searches from newest to oldest, and at the first matching pending broadcast
     * it finds, replaces it in-place and returns -- does not attempt to handle
     * "duplicate" broadcasts in the queue.
     *
     * @return the existing broadcast record in the queue that was replaced with a newer broadcast
     *         sent with {@link Intent#FLAG_RECEIVER_REPLACE_PENDING} or {@code null} if there
     *         wasn't any broadcast that was replaced.
     */
    @Nullable
    private BroadcastRecord replaceBroadcastInQueue(@NonNull ArrayDeque<SomeArgs> queue,
            @NonNull BroadcastRecord record, int recordIndex) {
        final Iterator<SomeArgs> it = queue.descendingIterator();
        final Object receiver = record.receivers.get(recordIndex);
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            final BroadcastRecord testRecord = (BroadcastRecord) args.arg1;
            final int testRecordIndex = args.argi1;
            final Object testReceiver = testRecord.receivers.get(testRecordIndex);
            // If we come across the record that's being enqueued in the queue, then that means
            // we already enqueued it for a receiver in this process and trying to insert a new
            // one past this could create priority inversion in the queue, so bail out.
            if (record == testRecord) {
                break;
            }
            if ((record.callingUid == testRecord.callingUid)
                    && (record.userId == testRecord.userId)
                    && record.intent.filterEquals(testRecord.intent)
                    && isReceiverEquals(receiver, testReceiver)
                    && testRecord.allReceiversPending()
                    && record.isMatchingRecord(testRecord)) {
                // Exact match found; perform in-place swap
                args.arg1 = record;
                args.argi1 = recordIndex;
                record.copyEnqueueTimeFrom(testRecord);
                onBroadcastDequeued(testRecord, testRecordIndex);
                onBroadcastEnqueued(record, recordIndex);
                return testRecord;
            }
        }
        return null;
    }

    /**
     * Functional interface that tests a {@link BroadcastRecord} that has been
     * previously enqueued in {@link BroadcastProcessQueue}.
     */
    @FunctionalInterface
    public interface BroadcastPredicate {
        boolean test(@NonNull BroadcastRecord r, int index);
    }

    /**
     * Functional interface that consumes a {@link BroadcastRecord} that has
     * been previously enqueued in {@link BroadcastProcessQueue}.
     */
    @FunctionalInterface
    public interface BroadcastConsumer {
        void accept(@NonNull BroadcastRecord r, int index);
    }

    /**
     * Invoke given consumer for any broadcasts matching given predicate. If
     * requested, matching broadcasts will also be removed from this queue.
     * <p>
     * Predicates that choose to remove a broadcast <em>must</em> finish
     * delivery of the matched broadcast, to ensure that situations like ordered
     * broadcasts are handled consistently.
     *
     * @return if this operation may have changed internal state, indicating
     *         that the caller is responsible for invoking
     *         {@link BroadcastQueueModernImpl#updateRunnableList}
     */
    @CheckResult
    public boolean forEachMatchingBroadcast(@NonNull BroadcastPredicate predicate,
            @NonNull BroadcastConsumer consumer, boolean andRemove) {
        boolean didSomething = false;
        didSomething |= forEachMatchingBroadcastInQueue(mPending,
                predicate, consumer, andRemove);
        didSomething |= forEachMatchingBroadcastInQueue(mPendingUrgent,
                predicate, consumer, andRemove);
        didSomething |= forEachMatchingBroadcastInQueue(mPendingOffload,
                predicate, consumer, andRemove);
        return didSomething;
    }

    @CheckResult
    private boolean forEachMatchingBroadcastInQueue(@NonNull ArrayDeque<SomeArgs> queue,
            @NonNull BroadcastPredicate predicate, @NonNull BroadcastConsumer consumer,
            boolean andRemove) {
        boolean didSomething = false;
        final Iterator<SomeArgs> it = queue.iterator();
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            final BroadcastRecord record = (BroadcastRecord) args.arg1;
            final int recordIndex = args.argi1;
            if (predicate.test(record, recordIndex)) {
                consumer.accept(record, recordIndex);
                if (andRemove) {
                    args.recycle();
                    it.remove();
                    onBroadcastDequeued(record, recordIndex);
                } else {
                    // Even if we're leaving broadcast in queue, it may have
                    // been mutated in such a way to change our runnable time
                    invalidateRunnableAt();
                }
                didSomething = true;
            }
        }
        // TODO: also check any active broadcast once we have a better "nonce"
        // representing each scheduled broadcast to avoid races
        return didSomething;
    }

    /**
     * Update the actively running "warm" process for this process.
     *
     * @return if this operation may have changed internal state, indicating
     *         that the caller is responsible for invoking
     *         {@link BroadcastQueueModernImpl#updateRunnableList}
     */
    @CheckResult
    public boolean setProcessAndUidState(@Nullable ProcessRecord app, boolean uidForeground,
            boolean processFreezable) {
        this.app = app;

        // Since we may have just changed our PID, invalidate cached strings
        mCachedToString = null;
        mCachedToShortString = null;

        boolean didSomething = false;
        if (app != null) {
            didSomething |= setUidForeground(uidForeground);
            didSomething |= setProcessFreezable(processFreezable);
            didSomething |= setProcessInstrumented(app.getActiveInstrumentation() != null);
            didSomething |= setProcessPersistent(app.isPersistent());
        } else {
            didSomething |= setUidForeground(false);
            didSomething |= setProcessFreezable(false);
            didSomething |= setProcessInstrumented(false);
            didSomething |= setProcessPersistent(false);
        }
        return didSomething;
    }

    /**
     * Update if the UID this process is belongs to is in "foreground" state, which signals
     * broadcast dispatch should prioritize delivering broadcasts to this process to minimize any
     * delays in UI updates.
     */
    @CheckResult
    private boolean setUidForeground(boolean uidForeground) {
        if (mUidForeground != uidForeground) {
            mUidForeground = uidForeground;
            invalidateRunnableAt();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Update if this process is in the "freezable" state, typically signaling that
     * broadcast dispatch should be paused or delayed.
     */
    @CheckResult
    private boolean setProcessFreezable(boolean freezable) {
        if (mProcessFreezable != freezable) {
            mProcessFreezable = freezable;
            invalidateRunnableAt();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Update if this process is in the "instrumented" state, typically
     * signaling that broadcast dispatch should bypass all pauses or delays, to
     * avoid holding up test suites.
     */
    @CheckResult
    private boolean setProcessInstrumented(boolean instrumented) {
        if (mProcessInstrumented != instrumented) {
            mProcessInstrumented = instrumented;
            invalidateRunnableAt();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Update if this process is in the "persistent" state, which signals broadcast dispatch should
     * bypass all pauses or delays to prevent the system from becoming out of sync with itself.
     */
    @CheckResult
    private boolean setProcessPersistent(boolean persistent) {
        if (mProcessPersistent != persistent) {
            mProcessPersistent = persistent;
            invalidateRunnableAt();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Return if we know of an actively running "warm" process for this queue.
     */
    public boolean isProcessWarm() {
        return (app != null) && (app.getOnewayThread() != null) && !app.isKilled();
    }

    public int getPreferredSchedulingGroupLocked() {
        if (!isActive()) {
            return ProcessList.SCHED_GROUP_UNDEFINED;
        } else if (mCountForeground > mCountForegroundDeferred) {
            // We have a foreground broadcast somewhere down the queue, so
            // boost priority until we drain them all
            return ProcessList.SCHED_GROUP_DEFAULT;
        } else if ((mActive != null) && mActive.isForeground()) {
            // We have a foreground broadcast right now, so boost priority
            return ProcessList.SCHED_GROUP_DEFAULT;
        } else {
            return ProcessList.SCHED_GROUP_BACKGROUND;
        }
    }

    /**
     * Count of {@link #mActive} broadcasts that have been dispatched since this
     * queue was last idle.
     */
    public int getActiveCountSinceIdle() {
        return mActiveCountSinceIdle;
    }

    /**
     * Count of {@link #mActive} broadcasts with assumed delivery that have been dispatched
     * since this queue was last idle.
     */
    public int getActiveAssumedDeliveryCountSinceIdle() {
        return mActiveAssumedDeliveryCountSinceIdle;
    }

    public void setActiveViaColdStart(boolean activeViaColdStart) {
        mActiveViaColdStart = activeViaColdStart;
    }

    public void setActiveWasStopped(boolean activeWasStopped) {
        mActiveWasStopped = activeWasStopped;
    }

    public boolean getActiveViaColdStart() {
        return mActiveViaColdStart;
    }

    public boolean getActiveWasStopped() {
        return mActiveWasStopped;
    }

    /**
     * Get package name of the first application loaded into this process.
     */
    @Nullable
    public String getPackageName() {
        return app == null ? null : app.getApplicationInfo().packageName;
    }

    /**
     * Set the currently active broadcast to the next pending broadcast.
     */
    public void makeActiveNextPending() {
        // TODO: what if the next broadcast isn't runnable yet?
        final SomeArgs next = removeNextBroadcast();
        mActive = (BroadcastRecord) next.arg1;
        mActiveIndex = next.argi1;
        mActiveReEnqueued = (next.argi2 == 1);
        mActiveCountSinceIdle++;
        mActiveAssumedDeliveryCountSinceIdle +=
                (mActive.isAssumedDelivered(mActiveIndex) ? 1 : 0);
        mActiveViaColdStart = false;
        mActiveWasStopped = false;
        next.recycle();
        onBroadcastDequeued(mActive, mActiveIndex);
    }

    /**
     * Set the currently running broadcast to be idle.
     */
    public void makeActiveIdle() {
        mActive = null;
        mActiveIndex = 0;
        mActiveReEnqueued = false;
        mActiveCountSinceIdle = 0;
        mActiveAssumedDeliveryCountSinceIdle = 0;
        mActiveViaColdStart = false;
        invalidateRunnableAt();
    }

    public boolean wasActiveBroadcastReEnqueued() {
        // If the flag is not enabled, treat as if the broadcast was never re-enqueued.
        if (!Flags.avoidRepeatedBcastReEnqueues()) {
            return false;
        }
        return mActiveReEnqueued;
    }

    /**
     * Update summary statistics when the given record has been enqueued.
     */
    private void onBroadcastEnqueued(@NonNull BroadcastRecord record, int recordIndex) {
        mCountEnqueued++;
        if (record.deferUntilActive) {
            mCountDeferred++;
        }
        if (record.isForeground()) {
            if (record.deferUntilActive) {
                mCountForegroundDeferred++;
            }
            mCountForeground++;
        }
        if (record.ordered) {
            mCountOrdered++;
        }
        if (record.alarm) {
            mCountAlarm++;
        }
        if (record.prioritized) {
            if (record.deferUntilActive) {
                mCountPrioritizedDeferred++;
            }
            mCountPrioritized++;
        }
        if (record.interactive) {
            mCountInteractive++;
        }
        if (record.resultTo != null) {
            mCountResultTo++;
        }
        if (record.callerInstrumented) {
            mCountInstrumented++;
        }
        if (record.receivers.get(recordIndex) instanceof ResolveInfo) {
            mCountManifest++;
        }
        invalidateRunnableAt();
    }

    /**
     * Update summary statistics when the given record has been dequeued.
     */
    private void onBroadcastDequeued(@NonNull BroadcastRecord record, int recordIndex) {
        mCountEnqueued--;
        if (record.deferUntilActive) {
            mCountDeferred--;
        }
        if (record.isForeground()) {
            if (record.deferUntilActive) {
                mCountForegroundDeferred--;
            }
            mCountForeground--;
        }
        if (record.ordered) {
            mCountOrdered--;
        }
        if (record.alarm) {
            mCountAlarm--;
        }
        if (record.prioritized) {
            if (record.deferUntilActive) {
                mCountPrioritizedDeferred--;
            }
            mCountPrioritized--;
        }
        if (record.interactive) {
            mCountInteractive--;
        }
        if (record.resultTo != null) {
            mCountResultTo--;
        }
        if (record.callerInstrumented) {
            mCountInstrumented--;
        }
        if (record.receivers.get(recordIndex) instanceof ResolveInfo) {
            mCountManifest--;
        }
        invalidateRunnableAt();
    }

    public void traceProcessStartingBegin() {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, toShortString() + " starting", hashCode());
    }

    public void traceProcessRunningBegin() {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, toShortString() + " running", hashCode());
    }

    public void traceProcessEnd() {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, hashCode());
    }

    public void traceActiveBegin() {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, mActive.toShortString() + " scheduled", hashCode());
    }

    public void traceActiveEnd() {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, hashCode());
    }

    /**
     * Return the broadcast being actively dispatched in this process.
     */
    public @NonNull BroadcastRecord getActive() {
        return Objects.requireNonNull(mActive);
    }

    /**
     * Return the index into {@link BroadcastRecord#receivers} of the receiver
     * being actively dispatched in this process.
     */
    public int getActiveIndex() {
        Objects.requireNonNull(mActive);
        return mActiveIndex;
    }

    public boolean isEmpty() {
        return mPending.isEmpty() && mPendingUrgent.isEmpty() && mPendingOffload.isEmpty();
    }

    public boolean isActive() {
        return mActive != null;
    }

    /**
     * @return if this operation may have changed internal state, indicating
     *         that the caller is responsible for invoking
     *         {@link BroadcastQueueModernImpl#updateRunnableList}
     */
    @CheckResult
    boolean forceDelayBroadcastDelivery(long delayedDurationMs) {
        if (mForcedDelayedDurationMs != delayedDurationMs) {
            mForcedDelayedDurationMs = delayedDurationMs;
            invalidateRunnableAt();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Will thrown an exception if there are no pending broadcasts; relies on
     * {@link #isEmpty()} being false.
     */
    private @Nullable SomeArgs removeNextBroadcast() {
        final ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        if (queue == mPendingUrgent) {
            mActiveCountConsecutiveUrgent++;
        } else if (queue == mPending) {
            mActiveCountConsecutiveUrgent = 0;
            mActiveCountConsecutiveNormal++;
        } else if (queue == mPendingOffload) {
            mActiveCountConsecutiveUrgent = 0;
            mActiveCountConsecutiveNormal = 0;
        }
        return !isQueueEmpty(queue) ? queue.removeFirst() : null;
    }

    @Nullable ArrayDeque<SomeArgs> queueForNextBroadcast() {
        final ArrayDeque<SomeArgs> nextNormal = queueForNextBroadcast(
                mPending, mPendingOffload,
                mActiveCountConsecutiveNormal, constants.MAX_CONSECUTIVE_NORMAL_DISPATCHES);
        final ArrayDeque<SomeArgs> nextBroadcastQueue = queueForNextBroadcast(
                mPendingUrgent, nextNormal,
                mActiveCountConsecutiveUrgent, constants.MAX_CONSECUTIVE_URGENT_DISPATCHES);
        return nextBroadcastQueue;
    }

    private @Nullable ArrayDeque<SomeArgs> queueForNextBroadcast(
            @Nullable ArrayDeque<SomeArgs> highPriorityQueue,
            @Nullable ArrayDeque<SomeArgs> lowPriorityQueue,
            int consecutiveHighPriorityCount,
            int maxHighPriorityDispatchLimit) {
        // nothing high priority pending, no further decisionmaking
        if (isQueueEmpty(highPriorityQueue)) {
            return lowPriorityQueue;
        }
        // nothing but high priority pending, also no further decisionmaking
        if (isQueueEmpty(lowPriorityQueue)) {
            return highPriorityQueue;
        }

        // Starvation mitigation: although we prioritize high priority queues by default,
        // we allow low priority queues to make steady progress even if broadcasts in
        // high priority queue are arriving faster than they can be dispatched.
        //
        // We do not try to defer to the next broadcast in low priority queues if that broadcast
        // is ordered and still blocked on delivery to other recipients.
        final SomeArgs nextLPArgs = lowPriorityQueue.peekFirst();
        final BroadcastRecord nextLPRecord = (BroadcastRecord) nextLPArgs.arg1;
        final int nextLPRecordIndex = nextLPArgs.argi1;
        final BroadcastRecord nextHPRecord = (BroadcastRecord) highPriorityQueue.peekFirst().arg1;
        final boolean shouldConsiderLPQueue = (mCountPrioritizeEarliestRequests > 0
                || consecutiveHighPriorityCount >= maxHighPriorityDispatchLimit);
        final boolean isLPQueueEligible = shouldConsiderLPQueue
                && nextLPRecord.enqueueTime <= nextHPRecord.enqueueTime
                && !nextLPRecord.isBlocked(nextLPRecordIndex);
        return isLPQueueEligible ? lowPriorityQueue : highPriorityQueue;
    }

    private static boolean isQueueEmpty(@Nullable ArrayDeque<SomeArgs> queue) {
        return (queue == null || queue.isEmpty());
    }

    /**
     * Add a request to prioritize dispatching of broadcasts that have been enqueued the earliest,
     * even if there are urgent broadcasts waiting to be dispatched. This is typically used in
     * case there are callers waiting for "barrier" to be reached.
     *
     * @return if this operation may have changed internal state, indicating
     *         that the caller is responsible for invoking
     *         {@link BroadcastQueueModernImpl#updateRunnableList}
     */
    @CheckResult
    @VisibleForTesting
    boolean addPrioritizeEarliestRequest() {
        if (mCountPrioritizeEarliestRequests == 0) {
            mCountPrioritizeEarliestRequests++;
            invalidateRunnableAt();
            return true;
        } else {
            mCountPrioritizeEarliestRequests++;
            return false;
        }
    }

    /**
     * Remove a request to prioritize dispatching of broadcasts that have been enqueued the
     * earliest, even if there are urgent broadcasts waiting to be dispatched. This is typically
     * used in case there are callers waiting for "barrier" to be reached.
     *
     * <p> Once there are no more remaining requests, the dispatching order reverts back to normal.
     *
     * @return if this operation may have changed internal state, indicating
     *         that the caller is responsible for invoking
     *         {@link BroadcastQueueModernImpl#updateRunnableList}
     */
    @CheckResult
    boolean removePrioritizeEarliestRequest() {
        mCountPrioritizeEarliestRequests--;
        if (mCountPrioritizeEarliestRequests == 0) {
            invalidateRunnableAt();
            return true;
        } else if (mCountPrioritizeEarliestRequests < 0) {
            mCountPrioritizeEarliestRequests = 0;
            return false;
        } else {
            return false;
        }
    }

    /**
     * Returns null if there are no pending broadcasts
     */
    @Nullable SomeArgs peekNextBroadcast() {
        ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        return !isQueueEmpty(queue) ? queue.peekFirst() : null;
    }

    @VisibleForTesting
    @Nullable BroadcastRecord peekNextBroadcastRecord() {
        ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        return !isQueueEmpty(queue) ? (BroadcastRecord) queue.peekFirst().arg1 : null;
    }

    /**
     * Quickly determine if this queue has broadcasts waiting to be delivered to
     * manifest receivers, which indicates we should request an OOM adjust.
     */
    public boolean isPendingManifest() {
        return mCountManifest > 0;
    }

    /**
     * Quickly determine if this queue has ordered broadcasts waiting to be delivered,
     * which indicates we should request an OOM adjust.
     */
    public boolean isPendingOrdered() {
        return mCountOrdered > 0;
    }

    /**
     * Quickly determine if this queue has broadcasts waiting to be delivered for which result is
     * expected from the senders, which indicates we should request an OOM adjust.
     */
    public boolean isPendingResultTo() {
        return mCountResultTo > 0;
    }

    /**
     * Report whether this queue is currently handling an urgent broadcast.
     */
    public boolean isPendingUrgent() {
        BroadcastRecord next = peekNextBroadcastRecord();
        return (next != null) ? next.isUrgent() : false;
    }

    /**
     * Quickly determine if this queue has broadcasts that are still waiting to
     * be delivered at some point in the future.
     */
    public boolean isIdle() {
        return (!isActive() && isEmpty()) || isDeferredUntilActive();
    }

    /**
     * Quickly determine if this queue has non-deferred broadcasts enqueued before the given
     * barrier timestamp that are still waiting to be delivered.
     */
    public boolean isBeyondBarrierLocked(@UptimeMillisLong long barrierTime) {
        final SomeArgs next = mPending.peekFirst();
        final SomeArgs nextUrgent = mPendingUrgent.peekFirst();
        final SomeArgs nextOffload = mPendingOffload.peekFirst();

        // Empty records are always past any barrier
        final boolean activeBeyond = (mActive == null)
                || mActive.enqueueTime > barrierTime;
        final boolean nextBeyond = (next == null)
                || ((BroadcastRecord) next.arg1).enqueueTime > barrierTime;
        final boolean nextUrgentBeyond = (nextUrgent == null)
                || ((BroadcastRecord) nextUrgent.arg1).enqueueTime > barrierTime;
        final boolean nextOffloadBeyond = (nextOffload == null)
                || ((BroadcastRecord) nextOffload.arg1).enqueueTime > barrierTime;

        return (activeBeyond && nextBeyond && nextUrgentBeyond && nextOffloadBeyond)
                || isDeferredUntilActive();
    }

    /**
     * Quickly determine if this queue has non-deferred broadcasts waiting to be dispatched,
     * that match {@code intent}, as defined by {@link Intent#filterEquals(Intent)}.
     */
    public boolean isDispatched(@NonNull Intent intent) {
        final boolean activeDispatched = (mActive == null)
                || (!intent.filterEquals(mActive.intent));
        final boolean dispatched = isDispatchedInQueue(mPending, intent);
        final boolean urgentDispatched = isDispatchedInQueue(mPendingUrgent, intent);
        final boolean offloadDispatched = isDispatchedInQueue(mPendingOffload, intent);

        return (activeDispatched && dispatched && urgentDispatched && offloadDispatched)
                || isDeferredUntilActive();
    }

    /**
     * Quickly determine if the {@code queue} has non-deferred broadcasts waiting to be dispatched,
     * that match {@code intent}, as defined by {@link Intent#filterEquals(Intent)}.
     */
    private boolean isDispatchedInQueue(@NonNull ArrayDeque<SomeArgs> queue,
            @NonNull Intent intent) {
        final Iterator<SomeArgs> it = queue.iterator();
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            if (args == null) {
                return true;
            }
            final BroadcastRecord record = (BroadcastRecord) args.arg1;
            if (intent.filterEquals(record.intent)) {
                return false;
            }
        }
        return true;
    }

    public boolean isRunnable() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAt != Long.MAX_VALUE;
    }

    public boolean isDeferredUntilActive() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAtReason == BroadcastProcessQueue.REASON_CACHED_INFINITE_DEFER;
    }

    public boolean hasDeferredBroadcasts() {
        return (mCountDeferred > 0);
    }

    /**
     * Return time at which this process is considered runnable. This is
     * typically the time at which the next pending broadcast was first
     * enqueued, but it also reflects any pauses or delays that should be
     * applied to the process.
     * <p>
     * Returns {@link Long#MAX_VALUE} when this queue isn't currently runnable,
     * typically when the queue is empty or when paused.
     */
    public @UptimeMillisLong long getRunnableAt() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAt;
    }

    /**
     * Return the "reason" behind the current {@link #getRunnableAt()} value,
     * such as indicating why the queue is being delayed or paused.
     */
    public @Reason int getRunnableAtReason() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAtReason;
    }

    public void invalidateRunnableAt() {
        mRunnableAtInvalidated = true;
    }

    static final int REASON_EMPTY = 0;
    static final int REASON_CACHED = 1;
    static final int REASON_NORMAL = 2;
    static final int REASON_MAX_PENDING = 3;
    static final int REASON_BLOCKED = 4;
    static final int REASON_INSTRUMENTED = 5;
    static final int REASON_PERSISTENT = 6;
    static final int REASON_FORCE_DELAYED = 7;
    static final int REASON_CACHED_INFINITE_DEFER = 8;
    static final int REASON_CONTAINS_FOREGROUND = 10;
    static final int REASON_CONTAINS_ORDERED = 11;
    static final int REASON_CONTAINS_ALARM = 12;
    static final int REASON_CONTAINS_PRIORITIZED = 13;
    static final int REASON_CONTAINS_INTERACTIVE = 14;
    static final int REASON_CONTAINS_RESULT_TO = 15;
    static final int REASON_CONTAINS_INSTRUMENTED = 16;
    static final int REASON_CONTAINS_MANIFEST = 17;
    static final int REASON_FOREGROUND = 18;
    static final int REASON_CORE_UID = 19;
    static final int REASON_TOP_PROCESS = 20;

    @IntDef(flag = false, prefix = { "REASON_" }, value = {
            REASON_EMPTY,
            REASON_CACHED,
            REASON_NORMAL,
            REASON_MAX_PENDING,
            REASON_BLOCKED,
            REASON_INSTRUMENTED,
            REASON_PERSISTENT,
            REASON_FORCE_DELAYED,
            REASON_CACHED_INFINITE_DEFER,
            REASON_CONTAINS_FOREGROUND,
            REASON_CONTAINS_ORDERED,
            REASON_CONTAINS_ALARM,
            REASON_CONTAINS_PRIORITIZED,
            REASON_CONTAINS_INTERACTIVE,
            REASON_CONTAINS_RESULT_TO,
            REASON_CONTAINS_INSTRUMENTED,
            REASON_CONTAINS_MANIFEST,
            REASON_FOREGROUND,
            REASON_CORE_UID,
            REASON_TOP_PROCESS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reason {}

    static @NonNull String reasonToString(@Reason int reason) {
        switch (reason) {
            case REASON_EMPTY: return "EMPTY";
            case REASON_CACHED: return "CACHED";
            case REASON_NORMAL: return "NORMAL";
            case REASON_MAX_PENDING: return "MAX_PENDING";
            case REASON_BLOCKED: return "BLOCKED";
            case REASON_INSTRUMENTED: return "INSTRUMENTED";
            case REASON_PERSISTENT: return "PERSISTENT";
            case REASON_FORCE_DELAYED: return "FORCE_DELAYED";
            case REASON_CACHED_INFINITE_DEFER: return "INFINITE_DEFER";
            case REASON_CONTAINS_FOREGROUND: return "CONTAINS_FOREGROUND";
            case REASON_CONTAINS_ORDERED: return "CONTAINS_ORDERED";
            case REASON_CONTAINS_ALARM: return "CONTAINS_ALARM";
            case REASON_CONTAINS_PRIORITIZED: return "CONTAINS_PRIORITIZED";
            case REASON_CONTAINS_INTERACTIVE: return "CONTAINS_INTERACTIVE";
            case REASON_CONTAINS_RESULT_TO: return "CONTAINS_RESULT_TO";
            case REASON_CONTAINS_INSTRUMENTED: return "CONTAINS_INSTRUMENTED";
            case REASON_CONTAINS_MANIFEST: return "CONTAINS_MANIFEST";
            case REASON_FOREGROUND: return "FOREGROUND";
            case REASON_CORE_UID: return "CORE_UID";
            case REASON_TOP_PROCESS: return "TOP_PROCESS";
            default: return Integer.toString(reason);
        }
    }

    /**
     * Update {@link #getRunnableAt()}, when needed.
     */
    void updateRunnableAt() {
        if (!mRunnableAtInvalidated) return;
        mRunnableAtInvalidated = false;

        final SomeArgs next = peekNextBroadcast();
        if (next != null) {
            final BroadcastRecord r = (BroadcastRecord) next.arg1;
            final int index = next.argi1;
            final long runnableAt = r.enqueueTime;

            if (r.isBlocked(index)) {
                mRunnableAt = Long.MAX_VALUE;
                mRunnableAtReason = REASON_BLOCKED;
                return;
            }

            if (mForcedDelayedDurationMs > 0) {
                mRunnableAt = runnableAt + mForcedDelayedDurationMs;
                mRunnableAtReason = REASON_FORCE_DELAYED;
            } else if (mCountForeground > mCountForegroundDeferred) {
                mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                mRunnableAtReason = REASON_CONTAINS_FOREGROUND;
            } else if (mCountInteractive > 0) {
                mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                mRunnableAtReason = REASON_CONTAINS_INTERACTIVE;
            } else if (mCountInstrumented > 0) {
                mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                mRunnableAtReason = REASON_CONTAINS_INSTRUMENTED;
            } else if (mProcessInstrumented) {
                mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                mRunnableAtReason = REASON_INSTRUMENTED;
            } else if (mUidForeground) {
                mRunnableAt = runnableAt + constants.DELAY_FOREGROUND_PROC_MILLIS;
                mRunnableAtReason = REASON_FOREGROUND;
            } else if (app != null && app.getSetProcState() == ActivityManager.PROCESS_STATE_TOP) {
                // TODO (b/287676625): Use a callback to check when a process goes in and out of
                // the TOP state.
                mRunnableAt = runnableAt + constants.DELAY_FOREGROUND_PROC_MILLIS;
                mRunnableAtReason = REASON_TOP_PROCESS;
            } else if (mProcessPersistent) {
                mRunnableAt = runnableAt + constants.DELAY_PERSISTENT_PROC_MILLIS;
                mRunnableAtReason = REASON_PERSISTENT;
            } else if (mCountOrdered > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_ORDERED;
            } else if (mCountAlarm > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_ALARM;
            } else if (mCountPrioritized > mCountPrioritizedDeferred) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_PRIORITIZED;
            } else if (mCountManifest > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_MANIFEST;
            } else if (mProcessFreezable) {
                if (r.deferUntilActive) {
                    // All enqueued broadcasts are deferrable, defer
                    if (mCountDeferred == mCountEnqueued) {
                        mRunnableAt = Long.MAX_VALUE;
                        mRunnableAtReason = REASON_CACHED_INFINITE_DEFER;
                    } else {
                        // At least one enqueued broadcast isn't deferrable, repick time and reason
                        // for this record. If a later record is not deferrable and is one of these
                        // special cases, one of the cases above would have already caught that.
                        if (r.isForeground()) {
                            mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                            mRunnableAtReason = REASON_CONTAINS_FOREGROUND;
                        } else if (r.prioritized) {
                            mRunnableAt = runnableAt;
                            mRunnableAtReason = REASON_CONTAINS_PRIORITIZED;
                        } else if (r.resultTo != null) {
                            mRunnableAt = runnableAt;
                            mRunnableAtReason = REASON_CONTAINS_RESULT_TO;
                        } else {
                            mRunnableAt = runnableAt + constants.DELAY_CACHED_MILLIS;
                            mRunnableAtReason = REASON_CACHED;
                        }
                    }
                } else {
                    // This record isn't deferrable
                    mRunnableAt = runnableAt + constants.DELAY_CACHED_MILLIS;
                    mRunnableAtReason = REASON_CACHED;
                }
            } else if (mCountResultTo > 0) {
                // All resultTo broadcasts are infinitely deferrable, so if the app
                // is already cached, they'll be deferred on the line above
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_RESULT_TO;
            } else if (UserHandle.isCore(uid)) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CORE_UID;
            } else {
                mRunnableAt = runnableAt + constants.DELAY_NORMAL_MILLIS;
                mRunnableAtReason = REASON_NORMAL;
            }

            // If we have too many broadcasts pending, bypass any delays that
            // might have been applied above to aid draining
            if (mPending.size() + mPendingUrgent.size()
                    + mPendingOffload.size() >= constants.MAX_PENDING_BROADCASTS) {
                mRunnableAt = Math.min(mRunnableAt, runnableAt);
                mRunnableAtReason = REASON_MAX_PENDING;
            }

            if (VERBOSE) {
                Trace.instantForTrack(Trace.TRACE_TAG_ACTIVITY_MANAGER, "BroadcastQueue",
                        ((app != null) ? app.processName : "(null)")
                        + ":" + r.intent.toString() + ":"
                        + r.deferUntilActive
                        + ":" + mRunnableAt + " " + reasonToString(mRunnableAtReason)
                        + ":" + ((app != null) ? app.isCached() : "false"));
            }
        } else {
            mRunnableAt = Long.MAX_VALUE;
            mRunnableAtReason = REASON_EMPTY;
        }
    }

    /**
     * Update {@link BroadcastRecord#DELIVERY_DEFERRED} states of all our
     * pending broadcasts, when needed.
     */
    void updateDeferredStates(@NonNull BroadcastConsumer applyConsumer,
            @NonNull BroadcastConsumer clearConsumer) {
        // When all we have pending is deferred broadcasts, and we're cached,
        // then we want everything to be marked deferred
        final boolean wantDeferredStates = shouldBeDeferred();

        if (mLastDeferredStates != wantDeferredStates) {
            mLastDeferredStates = wantDeferredStates;
            if (wantDeferredStates) {
                forEachMatchingBroadcast((r, i) -> {
                    return (r.getDeliveryState(i) == BroadcastRecord.DELIVERY_PENDING);
                }, applyConsumer, false);
            } else {
                forEachMatchingBroadcast((r, i) -> {
                    return (r.getDeliveryState(i) == BroadcastRecord.DELIVERY_DEFERRED);
                }, clearConsumer, false);
            }
        }
    }

    void clearDeferredStates(@NonNull BroadcastConsumer clearConsumer) {
        if (mLastDeferredStates) {
            mLastDeferredStates = false;
            forEachMatchingBroadcast((r, i) -> {
                return (r.getDeliveryState(i) == BroadcastRecord.DELIVERY_DEFERRED);
            }, clearConsumer, false);
        }
    }

    @VisibleForTesting
    boolean shouldBeDeferred() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAtReason == REASON_CACHED
                || mRunnableAtReason == REASON_CACHED_INFINITE_DEFER;
    }

    /**
     * Check overall health, confirming things are in a reasonable state and
     * that we're not wedged.
     */
    public void assertHealthLocked() {
        // If we're not actively running, we should be sorted into the runnable
        // list, and if we're invalidated then someone likely forgot to invoke
        // updateRunnableList() to re-sort us into place
        if (!isActive()) {
            checkState(!mRunnableAtInvalidated, "mRunnableAtInvalidated");
        }

        assertHealthLocked(mPending);
        assertHealthLocked(mPendingUrgent);
        assertHealthLocked(mPendingOffload);
    }

    private void assertHealthLocked(@NonNull ArrayDeque<SomeArgs> queue) {
        if (queue.isEmpty()) return;

        final Iterator<SomeArgs> it = queue.descendingIterator();
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            final BroadcastRecord record = (BroadcastRecord) args.arg1;
            final int recordIndex = args.argi1;

            if (BroadcastRecord.isDeliveryStateTerminal(record.getDeliveryState(recordIndex))
                    || record.isDeferUntilActive()) {
                continue;
            } else {
                // If waiting more than 10 minutes, we're likely wedged
                final long waitingTime = SystemClock.uptimeMillis() - record.enqueueTime;
                checkState(waitingTime < (10 * DateUtils.MINUTE_IN_MILLIS), "waitingTime");
            }
        }
    }

    /**
     * Insert the given queue into a sorted linked list of "runnable" queues.
     *
     * @param head the current linked list head
     * @param item the queue to insert
     * @return a potentially updated linked list head
     */
    @VisibleForTesting
    static @Nullable BroadcastProcessQueue insertIntoRunnableList(
            @Nullable BroadcastProcessQueue head, @NonNull BroadcastProcessQueue item) {
        if (head == null) {
            return item;
        }
        final long itemRunnableAt = item.getRunnableAt();
        BroadcastProcessQueue test = head;
        BroadcastProcessQueue tail = null;
        while (test != null) {
            if (test.getRunnableAt() > itemRunnableAt) {
                item.runnableAtNext = test;
                item.runnableAtPrev = test.runnableAtPrev;
                if (item.runnableAtNext != null) {
                    item.runnableAtNext.runnableAtPrev = item;
                }
                if (item.runnableAtPrev != null) {
                    item.runnableAtPrev.runnableAtNext = item;
                }
                return (test == head) ? item : head;
            }
            tail = test;
            test = test.runnableAtNext;
        }
        item.runnableAtPrev = tail;
        item.runnableAtPrev.runnableAtNext = item;
        return head;
    }

    /**
     * Remove the given queue from a sorted linked list of "runnable" queues.
     *
     * @param head the current linked list head
     * @param item the queue to remove
     * @return a potentially updated linked list head
     */
    @VisibleForTesting
    static @Nullable BroadcastProcessQueue removeFromRunnableList(
            @Nullable BroadcastProcessQueue head, @NonNull BroadcastProcessQueue item) {
        if (head == item) {
            head = item.runnableAtNext;
        }
        if (item.runnableAtNext != null) {
            item.runnableAtNext.runnableAtPrev = item.runnableAtPrev;
        }
        if (item.runnableAtPrev != null) {
            item.runnableAtPrev.runnableAtNext = item.runnableAtNext;
        }
        item.runnableAtNext = null;
        item.runnableAtPrev = null;
        return head;
    }

    /**
     * Set the timeout flag to indicate that an ANR timer has been started.  A value of true means a
     * timer is running; a value of false means there is no timer running.
     */
    void setTimeoutScheduled(boolean timeoutScheduled) {
        mTimeoutScheduled = timeoutScheduled;
    }

    /**
     * Get the timeout flag
     */
    boolean timeoutScheduled() {
        return mTimeoutScheduled;
    }

    @Override
    public String toString() {
        if (mCachedToString == null) {
            mCachedToString = "BroadcastProcessQueue{" + toShortString() + "}";
        }
        return mCachedToString;
    }

    public String toShortString() {
        if (mCachedToShortString == null) {
            mCachedToShortString = Integer.toHexString(System.identityHashCode(this))
                    + " " + ((app != null) ? app.getPid() : "?") + ":" + processName + "/"
                    + UserHandle.formatUid(uid);
        }
        return mCachedToShortString;
    }

    public String describeStateLocked() {
        return describeStateLocked(SystemClock.uptimeMillis());
    }

    public String describeStateLocked(@UptimeMillisLong long now) {
        final StringBuilder sb = new StringBuilder();
        if (isRunnable()) {
            sb.append("runnable at ");
            TimeUtils.formatDuration(getRunnableAt(), now, sb);
        } else {
            sb.append("not runnable");
        }
        sb.append(" because ");
        sb.append(reasonToString(mRunnableAtReason));
        return sb.toString();
    }

    @NeverCompile
    public void dumpLocked(@UptimeMillisLong long now, @NonNull IndentingPrintWriter pw) {
        if ((mActive == null) && isEmpty()) return;

        pw.print(toShortString());
        pw.print(" ");
        pw.print(describeStateLocked(now));
        pw.println();

        pw.increaseIndent();
        dumpProcessState(pw);
        dumpBroadcastCounts(pw);

        if (mActive != null) {
            dumpRecord("ACTIVE", now, pw, mActive, mActiveIndex);
        }
        for (SomeArgs args : mPendingUrgent) {
            final BroadcastRecord r = (BroadcastRecord) args.arg1;
            dumpRecord("URGENT", now, pw, r, args.argi1);
        }
        for (SomeArgs args : mPending) {
            final BroadcastRecord r = (BroadcastRecord) args.arg1;
            dumpRecord(null, now, pw, r, args.argi1);
        }
        for (SomeArgs args : mPendingOffload) {
            final BroadcastRecord r = (BroadcastRecord) args.arg1;
            dumpRecord("OFFLOAD", now, pw, r, args.argi1);
        }
        pw.decreaseIndent();
        pw.println();
    }

    @NeverCompile
    private void dumpProcessState(@NonNull IndentingPrintWriter pw) {
        final StringBuilder sb = new StringBuilder();
        if (mUidForeground) {
            sb.append("FG");
        }
        if (mProcessFreezable) {
            if (sb.length() > 0) sb.append("|");
            sb.append("FRZ");
        }
        if (mProcessInstrumented) {
            if (sb.length() > 0) sb.append("|");
            sb.append("INSTR");
        }
        if (mProcessPersistent) {
            if (sb.length() > 0) sb.append("|");
            sb.append("PER");
        }
        if (sb.length() > 0) {
            pw.print("state:"); pw.println(sb);
        }
        if (runningOomAdjusted) {
            pw.print("runningOomAdjusted:"); pw.println(runningOomAdjusted);
        }
        if (mActiveReEnqueued) {
            pw.print("activeReEnqueued:"); pw.println(mActiveReEnqueued);
        }
    }

    @NeverCompile
    private void dumpBroadcastCounts(@NonNull IndentingPrintWriter pw) {
        pw.print("e:"); pw.print(mCountEnqueued);
        pw.print(" d:"); pw.print(mCountDeferred);
        pw.print(" f:"); pw.print(mCountForeground);
        pw.print(" fd:"); pw.print(mCountForegroundDeferred);
        pw.print(" o:"); pw.print(mCountOrdered);
        pw.print(" a:"); pw.print(mCountAlarm);
        pw.print(" p:"); pw.print(mCountPrioritized);
        pw.print(" pd:"); pw.print(mCountPrioritizedDeferred);
        pw.print(" int:"); pw.print(mCountInteractive);
        pw.print(" rt:"); pw.print(mCountResultTo);
        pw.print(" ins:"); pw.print(mCountInstrumented);
        pw.print(" m:"); pw.print(mCountManifest);

        pw.print(" csi:"); pw.print(mActiveCountSinceIdle);
        pw.print(" adcsi:"); pw.print(mActiveAssumedDeliveryCountSinceIdle);
        pw.print(" ccu:"); pw.print(mActiveCountConsecutiveUrgent);
        pw.print(" ccn:"); pw.print(mActiveCountConsecutiveNormal);
        pw.println();
    }

    @NeverCompile
    private void dumpRecord(@Nullable String flavor, @UptimeMillisLong long now,
            @NonNull IndentingPrintWriter pw, @NonNull BroadcastRecord record, int recordIndex) {
        TimeUtils.formatDuration(record.enqueueTime, now, pw);
        pw.print(' ');
        pw.println(record.toShortString());
        pw.print("    ");
        final int deliveryState = record.delivery[recordIndex];
        pw.print(deliveryStateToString(deliveryState));
        if (deliveryState == BroadcastRecord.DELIVERY_SCHEDULED) {
            pw.print(" at ");
            TimeUtils.formatDuration(record.scheduledTime[recordIndex], now, pw);
        }
        if (flavor != null) {
            pw.print(' ');
            pw.print(flavor);
        }
        final Object receiver = record.receivers.get(recordIndex);
        if (receiver instanceof BroadcastFilter) {
            final BroadcastFilter filter = (BroadcastFilter) receiver;
            pw.print(" for registered ");
            pw.print(Integer.toHexString(System.identityHashCode(filter)));
        } else /* if (receiver instanceof ResolveInfo) */ {
            final ResolveInfo info = (ResolveInfo) receiver;
            pw.print(" for manifest ");
            pw.print(info.activityInfo.name);
        }
        pw.println();
        final int blockedUntilBeyondCount = record.blockedUntilBeyondCount[recordIndex];
        if (blockedUntilBeyondCount != -1) {
            pw.print("    blocked until ");
            pw.print(blockedUntilBeyondCount);
            pw.print(", currently at ");
            pw.print(record.beyondCount);
            pw.print(" of ");
            pw.println(record.receivers.size());
        }
    }
}
