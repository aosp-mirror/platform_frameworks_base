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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ReceiverInfo;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.os.Bundle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A batch of receiver instructions. This includes a list of finish requests and a list of
 * receivers.  The instructions are for a single queue.  It is constructed and consumed in a single
 * call to {@link BroadcastQueueModernImpl#scheduleReceiverWarmLocked}.  The list size is bounded by
 * {@link BroadcastConstants#MAX_BROADCAST_BATCH_SIZE}.  Because this class is ephemeral and its use
 * is bounded, it is pre-allocated to avoid allocating new objects every time it is used.
 *
 * The {@link #single} methods support the use of this class in {@link BroadcastQueueImpl}.  These
 * methods simplify the use of {@link IApplicationThread#scheduleReceiverList} as a replacement
 * for scheduleReceiver and scheduleRegisteredReceiver.
 *
 * This class is designed to be allocated once and constantly reused (call {@link #reset} between
 * uses).  Objects needed by the instance are kept in a pool - all object allocation occurs when
 * the instance is created, and no allocation occurs thereafter.  However, if the variable
 * mDeepReceiverCopy is set true (it is false by default) then the method {@link #receivers} always
 * returns newly allocated objects.  This is required for mock testing.
 *
 * This class is not thread-safe.  Instances must be protected by the caller.
 * @hide
 */
final class BroadcastReceiverBatch {

    /**
     * If this is true then receivers() returns a deep copy of the ReceiveInfo array.  If this is
     * false, receivers() returns a reference to the array.  A deep copy is needed only for the
     * broadcast queue mocking tests.
     */
    @VisibleForTesting
    boolean mDeepReceiverCopy = false;

    /**
     * A private pool implementation this class.
     */
    private static class Pool<T> {
        final int size;
        final ArrayList<T> pool;
        int next;
        Pool(int n, @NonNull Class<T> c) {
            size = n;
            pool = new ArrayList<>(size);
            try {
                for (int i = 0; i < size; i++) {
                    pool.add(c.getDeclaredConstructor().newInstance());
                }
            } catch (Exception e) {
                // This class is only used locally.  Turn any exceptions into something fatal.
                throw new RuntimeException(e);
            }
        }
        T next() {
            return pool.get(next++);
        }
        void reset() {
            next = 0;
        }
    }

    /**
     * The information needed to finish off a receiver.  This is valid only in the context of
     * a queue.
     */
    static class FinishInfo {
        BroadcastRecord r = null;
        int index = 0;
        int deliveryState = 0;
        String reason = null;
        FinishInfo set(@Nullable BroadcastRecord r, int index,
                int deliveryState, @Nullable String reason) {
            this.r = r;
            this.index = index;
            this.deliveryState = deliveryState;
            this.reason = reason;
            return this;
        }
    }

    /**
     * The information needed to recreate a receiver info.  The broadcast record can be null if the
     * caller does not expect to need it later.
     */
    static class ReceiverCookie {
        BroadcastRecord r = null;
        int index = 0;
        ReceiverCookie set(@Nullable BroadcastRecord r, int index) {
            this.r = r;
            this.index = index;
            return this;
        }
    }

    // The object pools.
    final int mSize;
    private final Pool<ReceiverInfo> receiverPool;
    private final Pool<FinishInfo> finishPool;
    private final Pool<ReceiverCookie> cookiePool;

    // The accumulated data.  The receivers should be an ArrayList to be directly compatible
    // with scheduleReceiverList().  The receivers array is not final because a new array must
    // be created for every new call to scheduleReceiverList().
    private final ArrayList<ReceiverInfo> mReceivers;
    private final ArrayList<ReceiverCookie> mCookies;
    private final ArrayList<FinishInfo> mFinished;
    // The list of finish records to complete if the binder succeeds or fails.
    private final ArrayList<FinishInfo> mSuccess;

    BroadcastReceiverBatch(int size) {
        mSize = size;
        mReceivers = new ArrayList<>(mSize);
        mCookies = new ArrayList<>(mSize);
        mFinished = new ArrayList<>(mSize);
        mSuccess = new ArrayList<>(mSize);

        receiverPool = new Pool<>(mSize, ReceiverInfo.class);
        finishPool = new Pool<>(mSize, FinishInfo.class);
        cookiePool = new Pool<>(mSize, ReceiverCookie.class);
        mStats = new Statistics(mSize);
        reset();
    }

    void reset() {
        mReceivers.clear();
        mCookies.clear();
        mFinished.clear();
        mSuccess.clear();

        receiverPool.reset();
        finishPool.reset();
        cookiePool.reset();
    }

    void finish(@Nullable BroadcastRecord r, int index,
            int deliveryState, @Nullable String reason) {
        mFinished.add(finishPool.next().set(r, index, deliveryState, reason));
    }
    void success(@Nullable BroadcastRecord r, int index,
            int deliveryState, @Nullable String reason) {
        mSuccess.add(finishPool.next().set(r, index, deliveryState, reason));
    }
    // Add a ReceiverInfo for a registered receiver.
    void schedule(@Nullable IIntentReceiver receiver, Intent intent,
            int resultCode, @Nullable String data, @Nullable Bundle extras, boolean ordered,
            boolean sticky, boolean assumeDelivered, int sendingUser, int processState,
            @Nullable BroadcastRecord r, int index) {
        ReceiverInfo ri = new ReceiverInfo();
        ri.intent = intent;
        ri.data = data;
        ri.extras = extras;
        ri.assumeDelivered = assumeDelivered;
        ri.sendingUser = sendingUser;
        ri.processState = processState;
        ri.resultCode = resultCode;
        ri.registered = true;
        ri.receiver = receiver;
        ri.ordered = ordered;
        ri.sticky = sticky;
        mReceivers.add(ri);
        mCookies.add(cookiePool.next().set(r, index));
    }
    // Add a ReceiverInfo for a manifest receiver.
    void schedule(@Nullable Intent intent, @Nullable ActivityInfo activityInfo,
            @Nullable CompatibilityInfo compatInfo, int resultCode, @Nullable String data,
            @Nullable Bundle extras, boolean sync, boolean assumeDelivered, int sendingUser,
            int processState, @Nullable BroadcastRecord r, int index) {
        ReceiverInfo ri = new ReceiverInfo();
        ri.intent = intent;
        ri.data = data;
        ri.extras = extras;
        ri.assumeDelivered = assumeDelivered;
        ri.sendingUser = sendingUser;
        ri.processState = processState;
        ri.resultCode = resultCode;
        ri.registered = false;
        ri.activityInfo = activityInfo;
        ri.compatInfo = compatInfo;
        ri.sync = sync;
        mReceivers.add(ri);
        mCookies.add(cookiePool.next().set(r, index));
    }

    /**
     * Two convenience functions for dispatching a single receiver.  The functions start with a
     * reset.  Then they create the ReceiverInfo array and return it.  Statistics are not
     * collected.
     */
    ArrayList<ReceiverInfo> registeredReceiver(@Nullable IIntentReceiver receiver,
            @Nullable Intent intent, int resultCode, @Nullable String data,
            @Nullable Bundle extras, boolean ordered, boolean sticky, boolean assumeDelivered,
            int sendingUser, int processState) {
        reset();
        schedule(receiver, intent, resultCode, data, extras, ordered, sticky, assumeDelivered,
                sendingUser, processState, null, 0);
        return receivers();
    }

    ArrayList<ReceiverInfo> manifestReceiver(@Nullable Intent intent,
            @Nullable ActivityInfo activityInfo, @Nullable CompatibilityInfo compatInfo,
            int resultCode, @Nullable String data, @Nullable Bundle extras, boolean sync,
            boolean assumeDelivered, int sendingUser, int processState) {
        reset();
        schedule(intent, activityInfo, compatInfo, resultCode, data, extras, sync, assumeDelivered,
                sendingUser, processState, null, 0);
        return receivers();
    }

    // Return true if the batch is full.  Adding any more entries will throw an exception.
    boolean isFull() {
        return (mFinished.size() + mReceivers.size()) >= mSize;
    }
    int finishCount() {
        return mFinished.size() + mSuccess.size();
    }
    int receiverCount() {
        return mReceivers.size();
    }

    /**
     * Create a deep copy of the receiver list.  This is only for testing which is confused when
     * objects are reused.
     */
    private ArrayList<ReceiverInfo> copyReceiverInfo() {
        ArrayList<ReceiverInfo> copy = new ArrayList<>();
        for (int i = 0; i < mReceivers.size(); i++) {
            final ReceiverInfo r = mReceivers.get(i);
            final ReceiverInfo n = new ReceiverInfo();
            n.intent = r.intent;
            n.data = r.data;
            n.extras = r.extras;
            n.assumeDelivered = r.assumeDelivered;
            n.sendingUser = r.sendingUser;
            n.processState = r.processState;
            n.resultCode = r.resultCode;
            n.registered = r.registered;
            n.receiver = r.receiver;
            n.ordered = r.ordered;
            n.sticky = r.sticky;
            n.activityInfo = r.activityInfo;
            n.compatInfo = r.compatInfo;
            n.sync = r.sync;
            copy.add(n);
        }
        return copy;
    }

    /**
     * Accessors for the accumulated instructions.  The important accessor is receivers(), since
     * it can be modified to return a deep copy of the mReceivers array.
     */
    @NonNull
    ArrayList<ReceiverInfo> receivers() {
        if (!mDeepReceiverCopy) {
            return mReceivers;
        } else {
            return copyReceiverInfo();
        }
    }
    @NonNull
    ArrayList<ReceiverCookie> cookies() {
        return mCookies;
    }
    @NonNull
    ArrayList<FinishInfo> finished() {
        return mFinished;
    }
    @NonNull
    ArrayList<FinishInfo> success() {
        return mSuccess;
    }

    /**
     * A simple POD for statistics.  The parameter is the size of the BroadcastReceiverBatch.
     */
    static class Statistics {
        final int[] finish;
        final int[] local;
        final int[] remote;
        Statistics(int size) {
            finish = new int[size+1];
            local = new int[size+1];
            remote = new int[size+1];
        }
    }

    private final Statistics mStats;

    /**
     * A unique counter that identifies individual transmission groups.  This is only used for
     * debugging.  It is used to determine which receivers were sent in the same batch, and in
     * which order.  This is static to distinguish between batches across all queues in the
     * system.
     */
    private static final AtomicInteger sTransmitGroup = new AtomicInteger(0);

    /**
     * Record statistics for this batch of instructions.  This updates the local statistics and it
     * updates the transmitGroup and transmitOrder fields of the BroadcastRecords being
     * dispatched.
     */
    void recordBatch(boolean local) {
        final int group = sTransmitGroup.addAndGet(1);
        for (int i = 0; i < cookies().size(); i++) {
            final var cookie = cookies().get(i);
            cookie.r.transmitGroup[cookie.index] = group;
            cookie.r.transmitOrder[cookie.index] = i;
        }
        mStats.finish[finishCount()]++;
        if (local) {
            mStats.local[receiverCount()]++;
        } else {
            mStats.remote[receiverCount()]++;
        }
    }

    @NonNull
    Statistics getStatistics() {
        return mStats;
    }
}
