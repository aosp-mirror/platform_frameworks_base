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
import android.os.UserHandle;
import android.util.IndentingPrintWriter;

import com.android.internal.os.SomeArgs;

import java.util.ArrayDeque;

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
 */
class BroadcastProcessQueue implements Comparable<BroadcastProcessQueue> {
    /**
     * Default delay to apply to background broadcasts, giving a chance for
     * debouncing of rapidly changing events.
     */
    // TODO: shift hard-coded defaults to BroadcastConstants
    private static final long DELAY_DEFAULT_MILLIS = 10_000;

    /**
     * Default delay to apply to broadcasts targeting cached applications.
     */
    // TODO: shift hard-coded defaults to BroadcastConstants
    private static final long DELAY_CACHED_MILLIS = 30_000;

    final @NonNull String processName;
    final int uid;

    /**
     * Linked list connection to another process under this {@link #uid} which
     * has a different {@link #processName}.
     */
    @Nullable BroadcastProcessQueue next;

    /**
     * Currently known details about the target process; typically undefined
     * when the process isn't actively running.
     */
    @Nullable ProcessRecord app;

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

    private int mCountForeground;
    private int mCountOrdered;
    private int mCountAlarm;

    private @UptimeMillisLong long mRunnableAt = Long.MAX_VALUE;
    private boolean mRunnableAtInvalidated;

    private boolean mProcessCached;

    public BroadcastProcessQueue(@NonNull String processName, int uid) {
        this.processName = processName;
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
     * Set the currently active broadcast to the next pending broadcast.
     */
    public void makeActiveNextPending() {
        // TODO: what if the next broadcast isn't runnable yet?
        checkState(isRunnable(), "isRunnable");
        final SomeArgs next = mPending.removeFirst();
        mActive = (BroadcastRecord) next.arg1;
        mActiveIndex = next.argi1;
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
    }

    public void setActiveDeliveryState(int deliveryState) {
        checkState(isActive(), "isActive");
        mActive.setDeliveryState(mActiveIndex, deliveryState);
    }

    public @NonNull BroadcastRecord getActive() {
        checkState(isActive(), "isActive");
        return mActive;
    }

    public @NonNull Object getActiveReceiver() {
        checkState(isActive(), "isActive");
        return mActive.receivers.get(mActiveIndex);
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

    private void invalidateRunnableAt() {
        mRunnableAtInvalidated = true;
    }

    /**
     * Update {@link #getRunnableAt()} if it's currently invalidated.
     */
    private void updateRunnableAt() {
        final SomeArgs next = mPending.peekFirst();
        if (next != null) {
            final long runnableAt = ((BroadcastRecord) next.arg1).enqueueTime;
            if (mCountForeground > 0) {
                mRunnableAt = runnableAt;
            } else if (mCountOrdered > 0) {
                mRunnableAt = runnableAt;
            } else if (mCountAlarm > 0) {
                mRunnableAt = runnableAt;
            } else if (mProcessCached) {
                mRunnableAt = runnableAt + DELAY_CACHED_MILLIS;
            } else {
                mRunnableAt = runnableAt + DELAY_DEFAULT_MILLIS;
            }
        } else {
            mRunnableAt = Long.MAX_VALUE;
        }
    }

    @Override
    public int compareTo(BroadcastProcessQueue o) {
        if (mRunnableAtInvalidated) updateRunnableAt();
        if (o.mRunnableAtInvalidated) o.updateRunnableAt();
        return Long.compare(mRunnableAt, o.mRunnableAt);
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
