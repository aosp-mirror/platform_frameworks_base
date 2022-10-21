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
import static com.android.server.am.BroadcastRecord.isDeliveryStateTerminal;
import static com.android.server.am.BroadcastRecord.isReceiverEquals;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
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
     * Track name to use for {@link Trace} events.
     */
    @Nullable String traceTrackName;

    /**
     * Snapshotted value of {@link ProcessRecord#getCpuDelayTime()}, typically
     * used when deciding if we should extend the soft ANR timeout.
     */
    long lastCpuDelayTime;

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
    private final ArrayDeque<SomeArgs> mPendingUrgent = new ArrayDeque<>();

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
     * When defined, the receiver actively being dispatched into this process
     * was considered "blocked" until at least the given count of other
     * receivers have reached a terminal state; typically used for ordered
     * broadcasts and priority traunches.
     */
    private int mActiveBlockedUntilTerminalCount;

    /**
     * Count of {@link #mActive} broadcasts that have been dispatched since this
     * queue was last idle.
     */
    private int mActiveCountSinceIdle;

    /**
     * Flag indicating that the currently active broadcast is being dispatched
     * was scheduled via a cold start.
     */
    private boolean mActiveViaColdStart;

    /**
     * Count of {@link #mPending} broadcasts of these various flavors.
     */
    private int mCountForeground;
    private int mCountOrdered;
    private int mCountAlarm;
    private int mCountPrioritized;

    private @UptimeMillisLong long mRunnableAt = Long.MAX_VALUE;
    private @Reason int mRunnableAtReason = REASON_EMPTY;
    private boolean mRunnableAtInvalidated;

    private boolean mProcessCached;

    private String mCachedToString;
    private String mCachedToShortString;

    public BroadcastProcessQueue(@NonNull BroadcastConstants constants,
            @NonNull String processName, int uid) {
        this.constants = Objects.requireNonNull(constants);
        this.processName = Objects.requireNonNull(processName);
        this.uid = uid;
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
     */
    public void enqueueOrReplaceBroadcast(@NonNull BroadcastRecord record, int recordIndex,
            int blockedUntilTerminalCount) {
        if (record.isReplacePending()) {
            boolean didReplace = replaceBroadcastInQueue(mPending,
                    record, recordIndex, blockedUntilTerminalCount)
                    || replaceBroadcastInQueue(mPendingUrgent,
                    record, recordIndex, blockedUntilTerminalCount);
            if (didReplace) {
                return;
            }
        }

        // Caller isn't interested in replacing, or we didn't find any pending
        // item to replace above, so enqueue as a new broadcast
        SomeArgs newBroadcastArgs = SomeArgs.obtain();
        newBroadcastArgs.arg1 = record;
        newBroadcastArgs.argi1 = recordIndex;
        newBroadcastArgs.argi2 = blockedUntilTerminalCount;

        // Cross-broadcast prioritization policy:  some broadcasts might warrant being
        // issued ahead of others that are already pending, for example if this new
        // broadcast is in a different delivery class or is tied to a direct user interaction
        // with implicit responsiveness expectations.
        final ArrayDeque<SomeArgs> queue = record.isUrgent() ? mPendingUrgent : mPending;
        queue.addLast(newBroadcastArgs);
        onBroadcastEnqueued(record);
    }

    /**
     * Searches from newest to oldest, and at the first matching pending broadcast
     * it finds, replaces it in-place and returns -- does not attempt to handle
     * "duplicate" broadcasts in the queue.
     * <p>
     * @return {@code true} if it found and replaced an existing record in the queue;
     * {@code false} otherwise.
     */
    private boolean replaceBroadcastInQueue(@NonNull ArrayDeque<SomeArgs> queue,
            @NonNull BroadcastRecord record, int recordIndex,  int blockedUntilTerminalCount) {
        final Iterator<SomeArgs> it = queue.descendingIterator();
        final Object receiver = record.receivers.get(recordIndex);
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            final BroadcastRecord testRecord = (BroadcastRecord) args.arg1;
            final Object testReceiver = testRecord.receivers.get(args.argi1);
            if ((record.callingUid == testRecord.callingUid)
                    && (record.userId == testRecord.userId)
                    && record.intent.filterEquals(testRecord.intent)
                    && isReceiverEquals(receiver, testReceiver)) {
                // Exact match found; perform in-place swap
                args.arg1 = record;
                args.argi1 = recordIndex;
                args.argi2 = blockedUntilTerminalCount;
                onBroadcastDequeued(testRecord);
                onBroadcastEnqueued(record);
                return true;
            }
        }
        return false;
    }

    /**
     * Functional interface that tests a {@link BroadcastRecord} that has been
     * previously enqueued in {@link BroadcastProcessQueue}.
     */
    @FunctionalInterface
    public interface BroadcastPredicate {
        public boolean test(@NonNull BroadcastRecord r, int index);
    }

    /**
     * Functional interface that consumes a {@link BroadcastRecord} that has
     * been previously enqueued in {@link BroadcastProcessQueue}.
     */
    @FunctionalInterface
    public interface BroadcastConsumer {
        public void accept(@NonNull BroadcastRecord r, int index);
    }

    /**
     * Invoke given consumer for any broadcasts matching given predicate. If
     * requested, matching broadcasts will also be removed from this queue.
     * <p>
     * Predicates that choose to remove a broadcast <em>must</em> finish
     * delivery of the matched broadcast, to ensure that situations like ordered
     * broadcasts are handled consistently.
     */
    public boolean forEachMatchingBroadcast(@NonNull BroadcastPredicate predicate,
            @NonNull BroadcastConsumer consumer, boolean andRemove) {
        boolean didSomething = forEachMatchingBroadcastInQueue(mPending,
                predicate, consumer, andRemove);
        didSomething |= forEachMatchingBroadcastInQueue(mPendingUrgent,
                predicate, consumer, andRemove);
        return didSomething;
    }

    private boolean forEachMatchingBroadcastInQueue(@NonNull ArrayDeque<SomeArgs> queue,
            @NonNull BroadcastPredicate predicate, @NonNull BroadcastConsumer consumer,
            boolean andRemove) {
        boolean didSomething = false;
        final Iterator<SomeArgs> it = queue.iterator();
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            final BroadcastRecord record = (BroadcastRecord) args.arg1;
            final int index = args.argi1;
            if (predicate.test(record, index)) {
                consumer.accept(record, index);
                if (andRemove) {
                    args.recycle();
                    it.remove();
                    onBroadcastDequeued(record);
                }
                didSomething = true;
            }
        }
        // TODO: also check any active broadcast once we have a better "nonce"
        // representing each scheduled broadcast to avoid races
        return didSomething;
    }

    /**
     * Update if this process is in the "cached" state, typically signaling that
     * broadcast dispatch should be paused or delayed.
     */
    public void setProcessCached(boolean cached) {
        if (mProcessCached != cached) {
            mProcessCached = cached;
            invalidateRunnableAt();
        }
    }

    /**
     * Return if we know of an actively running "warm" process for this queue.
     */
    public boolean isProcessWarm() {
        return (app != null) && (app.getThread() != null) && !app.isKilled();
    }

    public int getPreferredSchedulingGroupLocked() {
        if (mCountForeground > 0 || mCountOrdered > 0 || mCountAlarm > 0) {
            // We have an important broadcast somewhere down the queue, so
            // boost priority until we drain them all
            return ProcessList.SCHED_GROUP_DEFAULT;
        } else if ((mActive != null)
                && (mActive.isForeground() || mActive.ordered || mActive.alarm)) {
            // We have an important broadcast right now, so boost priority
            return ProcessList.SCHED_GROUP_DEFAULT;
        } else if (!isIdle()) {
            return ProcessList.SCHED_GROUP_BACKGROUND;
        } else {
            return ProcessList.SCHED_GROUP_UNDEFINED;
        }
    }

    /**
     * Count of {@link #mActive} broadcasts that have been dispatched since this
     * queue was last idle.
     */
    public int getActiveCountSinceIdle() {
        return mActiveCountSinceIdle;
    }

    public void setActiveViaColdStart(boolean activeViaColdStart) {
        mActiveViaColdStart = activeViaColdStart;
    }

    public boolean getActiveViaColdStart() {
        return mActiveViaColdStart;
    }

    /**
     * Set the currently active broadcast to the next pending broadcast.
     */
    public void makeActiveNextPending() {
        // TODO: what if the next broadcast isn't runnable yet?
        final SomeArgs next = removeNextBroadcast();
        mActive = (BroadcastRecord) next.arg1;
        mActiveIndex = next.argi1;
        mActiveBlockedUntilTerminalCount = next.argi2;
        mActiveCountSinceIdle++;
        mActiveViaColdStart = false;
        next.recycle();
        onBroadcastDequeued(mActive);
    }

    /**
     * Set the currently running broadcast to be idle.
     */
    public void makeActiveIdle() {
        mActive = null;
        mActiveIndex = 0;
        mActiveBlockedUntilTerminalCount = -1;
        mActiveCountSinceIdle = 0;
        mActiveViaColdStart = false;
        invalidateRunnableAt();
    }

    /**
     * Update summary statistics when the given record has been enqueued.
     */
    private void onBroadcastEnqueued(@NonNull BroadcastRecord record) {
        if (record.isForeground()) {
            mCountForeground++;
        }
        if (record.ordered) {
            mCountOrdered++;
        }
        if (record.alarm) {
            mCountAlarm++;
        }
        if (record.prioritized) {
            mCountPrioritized++;
        }
        invalidateRunnableAt();
    }

    /**
     * Update summary statistics when the given record has been dequeued.
     */
    private void onBroadcastDequeued(@NonNull BroadcastRecord record) {
        if (record.isForeground()) {
            mCountForeground--;
        }
        if (record.ordered) {
            mCountOrdered--;
        }
        if (record.alarm) {
            mCountAlarm--;
        }
        if (record.prioritized) {
            mCountPrioritized--;
        }
        invalidateRunnableAt();
    }

    public void traceProcessStartingBegin() {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                traceTrackName, toShortString() + " starting", hashCode());
    }

    public void traceProcessRunningBegin() {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                traceTrackName, toShortString() + " running", hashCode());
    }

    public void traceProcessEnd() {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                traceTrackName, hashCode());
    }

    public void traceActiveBegin() {
        final int cookie = mActive.receivers.get(mActiveIndex).hashCode();
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                traceTrackName, mActive.toShortString() + " scheduled", cookie);
    }

    public void traceActiveEnd() {
        final int cookie = mActive.receivers.get(mActiveIndex).hashCode();
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                traceTrackName, cookie);
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
        return mPending.isEmpty() && mPendingUrgent.isEmpty();
    }

    public boolean isActive() {
        return mActive != null;
    }

    /**
     * Will thrown an exception if there are no pending broadcasts; relies on
     * {@link #isEmpty()} being false.
     */
    SomeArgs removeNextBroadcast() {
        ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        return queue.removeFirst();
    }

    @Nullable ArrayDeque<SomeArgs> queueForNextBroadcast() {
        if (!mPendingUrgent.isEmpty()) {
            return mPendingUrgent;
        } else if (!mPending.isEmpty()) {
            return mPending;
        }
        return null;
    }

    /**
     * Returns null if there are no pending broadcasts
     */
    @Nullable SomeArgs peekNextBroadcast() {
        ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        return (queue != null) ? queue.peekFirst() : null;
    }

    @VisibleForTesting
    @Nullable BroadcastRecord peekNextBroadcastRecord() {
        ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        return (queue != null) ? (BroadcastRecord) queue.peekFirst().arg1 : null;
    }

    /**
     * Quickly determine if this queue has broadcasts that are still waiting to
     * be delivered at some point in the future.
     */
    public boolean isIdle() {
        return !isActive() && isEmpty();
    }

    /**
     * Quickly determine if this queue has broadcasts enqueued before the given
     * barrier timestamp that are still waiting to be delivered.
     */
    public boolean isBeyondBarrierLocked(@UptimeMillisLong long barrierTime) {
        if (mActive != null) {
            return mActive.enqueueTime > barrierTime;
        }
        final SomeArgs next = mPending.peekFirst();
        final SomeArgs nextUrgent = mPendingUrgent.peekFirst();
        // Empty queue is past any barrier
        final boolean nextLater = next == null
                || ((BroadcastRecord) next.arg1).enqueueTime > barrierTime;
        final boolean nextUrgentLater = nextUrgent == null
                || ((BroadcastRecord) nextUrgent.arg1).enqueueTime > barrierTime;
        return nextLater && nextUrgentLater;
    }

    public boolean isRunnable() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAt != Long.MAX_VALUE;
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
    static final int REASON_CONTAINS_FOREGROUND = 1;
    static final int REASON_CONTAINS_ORDERED = 2;
    static final int REASON_CONTAINS_ALARM = 3;
    static final int REASON_CONTAINS_PRIORITIZED = 4;
    static final int REASON_CACHED = 5;
    static final int REASON_NORMAL = 6;
    static final int REASON_MAX_PENDING = 7;
    static final int REASON_BLOCKED = 8;

    @IntDef(flag = false, prefix = { "REASON_" }, value = {
            REASON_EMPTY,
            REASON_CONTAINS_FOREGROUND,
            REASON_CONTAINS_ORDERED,
            REASON_CONTAINS_ALARM,
            REASON_CONTAINS_PRIORITIZED,
            REASON_CACHED,
            REASON_NORMAL,
            REASON_MAX_PENDING,
            REASON_BLOCKED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reason {}

    static @NonNull String reasonToString(@Reason int reason) {
        switch (reason) {
            case REASON_EMPTY: return "EMPTY";
            case REASON_CONTAINS_FOREGROUND: return "CONTAINS_FOREGROUND";
            case REASON_CONTAINS_ORDERED: return "CONTAINS_ORDERED";
            case REASON_CONTAINS_ALARM: return "CONTAINS_ALARM";
            case REASON_CONTAINS_PRIORITIZED: return "CONTAINS_PRIORITIZED";
            case REASON_CACHED: return "CACHED";
            case REASON_NORMAL: return "NORMAL";
            case REASON_MAX_PENDING: return "MAX_PENDING";
            case REASON_BLOCKED: return "BLOCKED";
            default: return Integer.toString(reason);
        }
    }

    /**
     * Update {@link #getRunnableAt()} if it's currently invalidated.
     */
    private void updateRunnableAt() {
        final SomeArgs next = peekNextBroadcast();
        if (next != null) {
            final BroadcastRecord r = (BroadcastRecord) next.arg1;
            final int index = next.argi1;
            final int blockedUntilTerminalCount = next.argi2;
            final long runnableAt = r.enqueueTime;

            // We might be blocked waiting for other receivers to finish,
            // typically for an ordered broadcast or priority traunches
            if (r.terminalCount < blockedUntilTerminalCount
                    && !isDeliveryStateTerminal(r.getDeliveryState(index))) {
                mRunnableAt = Long.MAX_VALUE;
                mRunnableAtReason = REASON_BLOCKED;
                return;
            }

            // If we have too many broadcasts pending, bypass any delays that
            // might have been applied above to aid draining
            if (mPending.size() + mPendingUrgent.size() >= constants.MAX_PENDING_BROADCASTS) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_MAX_PENDING;
                return;
            }

            if (mCountForeground > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_FOREGROUND;
            } else if (mCountOrdered > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_ORDERED;
            } else if (mCountAlarm > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_ALARM;
            } else if (mCountPrioritized > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_PRIORITIZED;
            } else if (mProcessCached) {
                mRunnableAt = runnableAt + constants.DELAY_CACHED_MILLIS;
                mRunnableAtReason = REASON_CACHED;
            } else {
                mRunnableAt = runnableAt + constants.DELAY_NORMAL_MILLIS;
                mRunnableAtReason = REASON_NORMAL;
            }
        } else {
            mRunnableAt = Long.MAX_VALUE;
            mRunnableAtReason = REASON_EMPTY;
        }
    }

    /**
     * Check overall health, confirming things are in a reasonable state and
     * that we're not wedged.
     */
    public void checkHealthLocked() {
        if (mRunnableAtReason == REASON_BLOCKED) {
            final SomeArgs next = peekNextBroadcast();
            Objects.requireNonNull(next, "peekNextBroadcast");

            // If blocked more than 10 minutes, we're likely wedged
            final BroadcastRecord r = (BroadcastRecord) next.arg1;
            final long waitingTime = SystemClock.uptimeMillis() - r.enqueueTime;
            checkState(waitingTime < (10 * DateUtils.MINUTE_IN_MILLIS), "waitingTime");
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
            if (test.getRunnableAt() >= itemRunnableAt) {
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

    @Override
    public String toString() {
        if (mCachedToString == null) {
            mCachedToString = "BroadcastProcessQueue{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " " + processName + "/" + UserHandle.formatUid(uid) + "}";
        }
        return mCachedToString;
    }

    public String toShortString() {
        if (mCachedToShortString == null) {
            mCachedToShortString = processName + "/" + UserHandle.formatUid(uid);
        }
        return mCachedToShortString;
    }

    @NeverCompile
    public void dumpLocked(@UptimeMillisLong long now, @NonNull IndentingPrintWriter pw) {
        if ((mActive == null) && mPending.isEmpty()) return;

        pw.print(toShortString());
        if (isRunnable()) {
            pw.print(" runnable at ");
            TimeUtils.formatDuration(getRunnableAt(), now, pw);
        } else {
            pw.print(" not runnable");
        }
        pw.print(" because ");
        pw.print(reasonToString(mRunnableAtReason));
        pw.println();
        pw.increaseIndent();
        if (mActive != null) {
            dumpRecord(now, pw, mActive, mActiveIndex, mActiveBlockedUntilTerminalCount);
        }
        for (SomeArgs args : mPending) {
            final BroadcastRecord r = (BroadcastRecord) args.arg1;
            dumpRecord(now, pw, r, args.argi1, args.argi2);
        }
        pw.decreaseIndent();
        pw.println();
    }

    @NeverCompile
    private void dumpRecord(@UptimeMillisLong long now, @NonNull IndentingPrintWriter pw,
            @NonNull BroadcastRecord record, int recordIndex, int blockedUntilTerminalCount) {
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
        if (blockedUntilTerminalCount != -1) {
            pw.print("    blocked until ");
            pw.print(blockedUntilTerminalCount);
            pw.print(", currently at ");
            pw.print(record.terminalCount);
            pw.print(" of ");
            pw.println(record.receivers.size());
        }
    }
}
