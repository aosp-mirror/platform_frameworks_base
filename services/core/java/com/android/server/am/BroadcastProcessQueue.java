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

import static com.android.server.am.BroadcastQueue.checkState;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
import android.os.Trace;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;

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
     * Ordered collection of broadcasts that are waiting to be dispatched to
     * this process, as a pair of {@link BroadcastRecord} and the index into
     * {@link BroadcastRecord#receivers} that represents the receiver.
     */
    private final ArrayDeque<SomeArgs> mPending = new ArrayDeque<>();

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

    private @UptimeMillisLong long mRunnableAt = Long.MAX_VALUE;
    private boolean mRunnableAtInvalidated;

    private boolean mProcessCached;

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
     */
    public void enqueueBroadcast(@NonNull BroadcastRecord record, int recordIndex) {
        // Detect situations where the incoming broadcast should cause us to
        // recalculate when we'll be runnable
        if (mPending.isEmpty()) {
            invalidateRunnableAt();
        }
        if (record.isForeground()) {
            mCountForeground++;
            invalidateRunnableAt();
        }
        if (record.ordered) {
            mCountOrdered++;
            invalidateRunnableAt();
        }
        if (record.alarm) {
            mCountAlarm++;
            invalidateRunnableAt();
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = record;
        args.argi1 = recordIndex;
        mPending.addLast(args);
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
     * Remove any broadcasts matching the given predicate.
     * <p>
     * Predicates that choose to remove a broadcast <em>must</em> finish
     * delivery of the matched broadcast, to ensure that situations like ordered
     * broadcasts are handled consistently.
     */
    public boolean removeMatchingBroadcasts(@NonNull BroadcastPredicate predicate,
            @NonNull BroadcastConsumer consumer) {
        boolean didSomething = false;
        final Iterator<SomeArgs> it = mPending.iterator();
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            final BroadcastRecord record = (BroadcastRecord) args.arg1;
            final int index = args.argi1;
            if (predicate.test(record, index)) {
                consumer.accept(record, index);
                args.recycle();
                it.remove();
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
        checkState(isRunnable(), "isRunnable");
        final SomeArgs next = mPending.removeFirst();
        mActive = (BroadcastRecord) next.arg1;
        mActiveIndex = next.argi1;
        mActiveCountSinceIdle++;
        mActiveViaColdStart = false;
        next.recycle();
        if (mActive.isForeground()) {
            mCountForeground--;
        }
        if (mActive.ordered) {
            mCountOrdered--;
        }
        if (mActive.alarm) {
            mCountAlarm--;
        }
        invalidateRunnableAt();
    }

    /**
     * Set the currently running broadcast to be idle.
     */
    public void makeActiveIdle() {
        mActive = null;
        mActiveIndex = 0;
        mActiveCountSinceIdle = 0;
        mActiveViaColdStart = false;
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
        checkState(isActive(), "isActive");
        return mActive;
    }

    /**
     * Return the index into {@link BroadcastRecord#receivers} of the receiver
     * being actively dispatched in this process.
     */
    public int getActiveIndex() {
        checkState(isActive(), "isActive");
        return mActiveIndex;
    }

    public boolean isEmpty() {
        return (mActive != null) && mPending.isEmpty();
    }

    public boolean isActive() {
        return mActive != null;
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

    public void invalidateRunnableAt() {
        mRunnableAtInvalidated = true;
    }

    /**
     * Update {@link #getRunnableAt()} if it's currently invalidated.
     */
    private void updateRunnableAt() {
        final SomeArgs next = mPending.peekFirst();
        if (next != null) {
            final BroadcastRecord r = (BroadcastRecord) next.arg1;
            final int index = next.argi1;

            // If our next broadcast is ordered, and we're not the next receiver
            // in line, then we're not runnable at all
            if (r.ordered && r.finishedCount != index) {
                mRunnableAt = Long.MAX_VALUE;
                return;
            }

            final long runnableAt = r.enqueueTime;
            if (mCountForeground > 0) {
                mRunnableAt = runnableAt;
            } else if (mCountOrdered > 0) {
                mRunnableAt = runnableAt;
            } else if (mCountAlarm > 0) {
                mRunnableAt = runnableAt;
            } else if (mProcessCached) {
                mRunnableAt = runnableAt + constants.DELAY_CACHED_MILLIS;
            } else {
                mRunnableAt = runnableAt + constants.DELAY_NORMAL_MILLIS;
            }
        } else {
            mRunnableAt = Long.MAX_VALUE;
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
        return "BroadcastProcessQueue{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + processName + "/" + UserHandle.formatUid(uid) + "}";
    }

    public String toShortString() {
        return processName + "/" + UserHandle.formatUid(uid);
    }

    public void dumpLocked(@NonNull IndentingPrintWriter pw) {
        if ((mActive == null) && mPending.isEmpty()) return;

        pw.println(toShortString());
        pw.increaseIndent();
        if (mActive != null) {
            pw.print("🏃 ");
            pw.print(mActive.toShortString());
            pw.print(' ');
            pw.println(mActive.receivers.get(mActiveIndex));
        }
        for (SomeArgs args : mPending) {
            final BroadcastRecord r = (BroadcastRecord) args.arg1;
            pw.print("\u3000 ");
            pw.print(r.toShortString());
            pw.print(' ');
            pw.println(r.receivers.get(args.argi1));
        }
        pw.decreaseIndent();
    }
}
