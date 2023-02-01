/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_NONE;

import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.AlarmManagerInternal;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Set;

/**
 * Manages ordered broadcast delivery, applying policy to mitigate the effects of
 * slow receivers.
 */
public class BroadcastDispatcher {
    private static final String TAG = "BroadcastDispatcher";

    // Deferred broadcasts to one app; times are all uptime time base like
    // other broadcast-related timekeeping
    static class Deferrals {
        final int uid;
        long deferredAt;    // when we started deferring
        long deferredBy;    // how long did we defer by last time?
        long deferUntil;    // when does the next element become deliverable?
        int alarmCount;

        final ArrayList<BroadcastRecord> broadcasts;

        Deferrals(int uid, long now, long backoff, int count) {
            this.uid = uid;
            this.deferredAt = now;
            this.deferredBy = backoff;
            this.deferUntil = now + backoff;
            this.alarmCount = count;
            broadcasts = new ArrayList<>();
        }

        void add(BroadcastRecord br) {
            broadcasts.add(br);
        }

        int size() {
            return broadcasts.size();
        }

        boolean isEmpty() {
            return broadcasts.isEmpty();
        }

        void dumpDebug(ProtoOutputStream proto, long fieldId) {
            for (BroadcastRecord br : broadcasts) {
                br.dumpDebug(proto, fieldId);
            }
        }

        void dumpLocked(Dumper d) {
            for (BroadcastRecord br : broadcasts) {
                d.dump(br);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Deferrals{uid=");
            sb.append(uid);
            sb.append(", deferUntil=");
            sb.append(deferUntil);
            sb.append(", #broadcasts=");
            sb.append(broadcasts.size());
            sb.append("}");
            return sb.toString();
        }
    }

    // Carrying dump formatting state across multiple concatenated datasets
    class Dumper {
        final PrintWriter mPw;
        final String mQueueName;
        final String mDumpPackage;
        final SimpleDateFormat mSdf;
        boolean mPrinted;
        boolean mNeedSep;
        String mHeading;
        String mLabel;
        int mOrdinal;

        Dumper(PrintWriter pw, String queueName, String dumpPackage, SimpleDateFormat sdf) {
            mPw = pw;
            mQueueName = queueName;
            mDumpPackage = dumpPackage;
            mSdf = sdf;

            mPrinted = false;
            mNeedSep = true;
        }

        void setHeading(String heading) {
            mHeading = heading;
            mPrinted = false;
        }

        void setLabel(String label) {
            //"  Active Ordered Broadcast " + mQueueName + " #" + i + ":"
            mLabel = "  " + label + " " + mQueueName + " #";
            mOrdinal = 0;
        }

        boolean didPrint() {
            return mPrinted;
        }

        void dump(BroadcastRecord br) {
            if (mDumpPackage == null || mDumpPackage.equals(br.callerPackage)) {
                if (!mPrinted) {
                    if (mNeedSep) {
                        mPw.println();
                    }
                    mPrinted = true;
                    mNeedSep = true;
                    mPw.println("  " + mHeading + " [" + mQueueName + "]:");
                }
                mPw.println(mLabel + mOrdinal + ":");
                mOrdinal++;

                br.dump(mPw, "    ", mSdf);
            }
        }
    }

    private final Object mLock;
    private final BroadcastQueue mQueue;
    private final BroadcastConstants mConstants;
    private final Handler mHandler;
    private AlarmManagerInternal mAlarm;

    // Current alarm targets; mapping uid -> in-flight alarm count
    final SparseIntArray mAlarmUids = new SparseIntArray();
    final AlarmManagerInternal.InFlightListener mAlarmListener =
            new AlarmManagerInternal.InFlightListener() {
        @Override
        public void broadcastAlarmPending(final int recipientUid) {
            synchronized (mLock) {
                final int newCount = mAlarmUids.get(recipientUid, 0) + 1;
                mAlarmUids.put(recipientUid, newCount);
                // any deferred broadcasts to this app now get fast-tracked
                final int numEntries = mDeferredBroadcasts.size();
                for (int i = 0; i < numEntries; i++) {
                    if (recipientUid == mDeferredBroadcasts.get(i).uid) {
                        Deferrals d = mDeferredBroadcasts.remove(i);
                        mAlarmBroadcasts.add(d);
                        break;
                    }
                }
            }
        }

        @Override
        public void broadcastAlarmComplete(final int recipientUid) {
            synchronized (mLock) {
                final int newCount = mAlarmUids.get(recipientUid, 0) - 1;
                if (newCount >= 0) {
                    mAlarmUids.put(recipientUid, newCount);
                } else {
                    Slog.wtf(TAG, "Undercount of broadcast alarms in flight for " + recipientUid);
                    mAlarmUids.put(recipientUid, 0);
                }

                // No longer an alarm target, so resume ordinary deferral policy
                if (newCount <= 0) {
                    final int numEntries = mAlarmBroadcasts.size();
                    for (int i = 0; i < numEntries; i++) {
                        if (recipientUid == mAlarmBroadcasts.get(i).uid) {
                            Deferrals d = mAlarmBroadcasts.remove(i);
                            insertLocked(mDeferredBroadcasts, d);
                            break;
                        }
                    }
                }
            }
        }
    };

    // Queue recheck operation used to tickle broadcast delivery when appropriate
    final Runnable mScheduleRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (DEBUG_BROADCAST_DEFERRAL) {
                    Slog.v(TAG, "Deferral recheck of pending broadcasts");
                }
                mQueue.scheduleBroadcastsLocked();
                mRecheckScheduled = false;
            }
        }
    };
    private boolean mRecheckScheduled = false;

    // Usual issuance-order outbound queue
    private final ArrayList<BroadcastRecord> mOrderedBroadcasts = new ArrayList<>();
    // General deferrals not holding up alarms
    private final ArrayList<Deferrals> mDeferredBroadcasts = new ArrayList<>();
    // Deferrals that *are* holding up alarms; ordered by alarm dispatch time
    private final ArrayList<Deferrals> mAlarmBroadcasts = new ArrayList<>();

    // Next outbound broadcast, established by getNextBroadcastLocked()
    private BroadcastRecord mCurrentBroadcast;

    // Map userId to its deferred boot completed broadcasts.
    private SparseArray<DeferredBootCompletedBroadcastPerUser> mUser2Deferred = new SparseArray<>();

    /**
     * Deferred LOCKED_BOOT_COMPLETED and BOOT_COMPLETED broadcasts that is sent to a user.
     */
    static class DeferredBootCompletedBroadcastPerUser {
        private int mUserId;
        // UID that has process started at least once, ready to execute LOCKED_BOOT_COMPLETED
        // receivers.
        @VisibleForTesting
        SparseBooleanArray mUidReadyForLockedBootCompletedBroadcast = new SparseBooleanArray();
        // UID that has process started at least once, ready to execute BOOT_COMPLETED receivers.
        @VisibleForTesting
        SparseBooleanArray mUidReadyForBootCompletedBroadcast = new SparseBooleanArray();
        // Map UID to deferred LOCKED_BOOT_COMPLETED broadcasts.
        // LOCKED_BOOT_COMPLETED broadcast receivers are deferred until the first time the uid has
        // any process started.
        @VisibleForTesting
        SparseArray<BroadcastRecord> mDeferredLockedBootCompletedBroadcasts = new SparseArray<>();
        // is the LOCKED_BOOT_COMPLETED broadcast received by the user.
        @VisibleForTesting
        boolean mLockedBootCompletedBroadcastReceived;
        // Map UID to deferred BOOT_COMPLETED broadcasts.
        // BOOT_COMPLETED broadcast receivers are deferred until the first time the uid has any
        // process started.
        @VisibleForTesting
        SparseArray<BroadcastRecord> mDeferredBootCompletedBroadcasts = new SparseArray<>();
        // is the BOOT_COMPLETED broadcast received by the user.
        @VisibleForTesting
        boolean mBootCompletedBroadcastReceived;

        DeferredBootCompletedBroadcastPerUser(int userId) {
            this.mUserId = userId;
        }

        public void updateUidReady(int uid) {
            if (!mLockedBootCompletedBroadcastReceived
                    || mDeferredLockedBootCompletedBroadcasts.size() != 0) {
                mUidReadyForLockedBootCompletedBroadcast.put(uid, true);
            }
            if (!mBootCompletedBroadcastReceived
                    || mDeferredBootCompletedBroadcasts.size() != 0) {
                mUidReadyForBootCompletedBroadcast.put(uid, true);
            }
        }

        public void enqueueBootCompletedBroadcasts(String action,
                SparseArray<BroadcastRecord> deferred) {
            if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                enqueueBootCompletedBroadcasts(deferred, mDeferredLockedBootCompletedBroadcasts,
                        mUidReadyForLockedBootCompletedBroadcast);
                mLockedBootCompletedBroadcastReceived = true;
                if (DEBUG_BROADCAST_DEFERRAL) {
                    dumpBootCompletedBroadcastRecord(mDeferredLockedBootCompletedBroadcasts);
                }
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                enqueueBootCompletedBroadcasts(deferred, mDeferredBootCompletedBroadcasts,
                        mUidReadyForBootCompletedBroadcast);
                mBootCompletedBroadcastReceived = true;
                if (DEBUG_BROADCAST_DEFERRAL) {
                    dumpBootCompletedBroadcastRecord(mDeferredBootCompletedBroadcasts);
                }
            }
        }

        /**
         * Merge UID to BroadcastRecord map into {@link #mDeferredBootCompletedBroadcasts} or
         * {@link #mDeferredLockedBootCompletedBroadcasts}
         * @param from the UID to BroadcastRecord map.
         * @param into The UID to list of BroadcastRecord map.
         */
        private void enqueueBootCompletedBroadcasts(SparseArray<BroadcastRecord> from,
                SparseArray<BroadcastRecord> into, SparseBooleanArray uidReadyForReceiver) {
            // remove unwanted uids from uidReadyForReceiver.
            for (int i = uidReadyForReceiver.size() - 1; i >= 0; i--) {
                if (from.indexOfKey(uidReadyForReceiver.keyAt(i)) < 0) {
                    uidReadyForReceiver.removeAt(i);
                }
            }
            for (int i = 0, size = from.size(); i < size; i++) {
                final int uid = from.keyAt(i);
                into.put(uid, from.valueAt(i));
                if (uidReadyForReceiver.indexOfKey(uid) < 0) {
                    // uid is wanted but not ready.
                    uidReadyForReceiver.put(uid, false);
                }
            }
        }

        public @Nullable BroadcastRecord dequeueDeferredBootCompletedBroadcast(
                boolean isAllUidReady) {
            BroadcastRecord next = dequeueDeferredBootCompletedBroadcast(
                    mDeferredLockedBootCompletedBroadcasts,
                    mUidReadyForLockedBootCompletedBroadcast, isAllUidReady);
            if (next == null) {
                next = dequeueDeferredBootCompletedBroadcast(mDeferredBootCompletedBroadcasts,
                        mUidReadyForBootCompletedBroadcast, isAllUidReady);
            }
            return next;
        }

        private @Nullable BroadcastRecord dequeueDeferredBootCompletedBroadcast(
                SparseArray<BroadcastRecord> uid2br, SparseBooleanArray uidReadyForReceiver,
                boolean isAllUidReady) {
            for (int i = 0, size = uid2br.size(); i < size; i++) {
                final int uid = uid2br.keyAt(i);
                if (isAllUidReady || uidReadyForReceiver.get(uid)) {
                    final BroadcastRecord br = uid2br.valueAt(i);
                    if (DEBUG_BROADCAST_DEFERRAL) {
                        final Object receiver = br.receivers.get(0);
                        if (receiver instanceof BroadcastFilter) {
                            if (DEBUG_BROADCAST_DEFERRAL) {
                                Slog.i(TAG, "getDeferredBootCompletedBroadcast uid:" + uid
                                        + " BroadcastFilter:" + (BroadcastFilter) receiver
                                        + " broadcast:" + br.intent.getAction());
                            }
                        } else /* if (receiver instanceof ResolveInfo) */ {
                            ResolveInfo info = (ResolveInfo) receiver;
                            String packageName = info.activityInfo.applicationInfo.packageName;
                            if (DEBUG_BROADCAST_DEFERRAL) {
                                Slog.i(TAG, "getDeferredBootCompletedBroadcast uid:" + uid
                                        + " packageName:" + packageName
                                        + " broadcast:" + br.intent.getAction());
                            }
                        }
                    }
                    // remove the BroadcastRecord.
                    uid2br.removeAt(i);
                    if (uid2br.size() == 0) {
                        // All deferred receivers are executed, do not need uidReadyForReceiver
                        // any more.
                        uidReadyForReceiver.clear();
                    }
                    return br;
                }
            }
            return null;
        }

        private @Nullable SparseArray<BroadcastRecord> getDeferredList(String action) {
            SparseArray<BroadcastRecord> brs = null;
            if (action.equals(Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
                brs = mDeferredLockedBootCompletedBroadcasts;
            } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                brs = mDeferredBootCompletedBroadcasts;
            }
            return brs;
        }

        /**
         * Return the total number of UIDs in all BroadcastRecord in
         * {@link #mDeferredBootCompletedBroadcasts} or
         * {@link #mDeferredLockedBootCompletedBroadcasts}
         */
        private int getBootCompletedBroadcastsUidsSize(String action) {
            SparseArray<BroadcastRecord> brs = getDeferredList(action);
            return brs != null ? brs.size() : 0;
        }

        /**
         * Return the total number of receivers in all BroadcastRecord in
         * {@link #mDeferredBootCompletedBroadcasts} or
         * {@link #mDeferredLockedBootCompletedBroadcasts}
         */
        private int getBootCompletedBroadcastsReceiversSize(String action) {
            SparseArray<BroadcastRecord> brs = getDeferredList(action);
            if (brs == null) {
                return 0;
            }
            int size = 0;
            for (int i = 0, s = brs.size(); i < s; i++) {
                size += brs.valueAt(i).receivers.size();
            }
            return size;
        }

        public void dump(Dumper dumper, String action) {
            SparseArray<BroadcastRecord> brs = getDeferredList(action);
            if (brs == null) {
                return;
            }
            for (int i = 0, size = brs.size(); i < size; i++) {
                dumper.dump(brs.valueAt(i));
            }
        }

        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            for (int i = 0, size = mDeferredLockedBootCompletedBroadcasts.size(); i < size; i++) {
                mDeferredLockedBootCompletedBroadcasts.valueAt(i).dumpDebug(proto, fieldId);
            }
            for (int i = 0, size = mDeferredBootCompletedBroadcasts.size(); i < size; i++) {
                mDeferredBootCompletedBroadcasts.valueAt(i).dumpDebug(proto, fieldId);
            }
        }

        private void dumpBootCompletedBroadcastRecord(SparseArray<BroadcastRecord> brs) {
            for (int i = 0, size = brs.size(); i < size; i++) {
                final Object receiver = brs.valueAt(i).receivers.get(0);
                String packageName = null;
                if (receiver instanceof BroadcastFilter) {
                    BroadcastFilter recv = (BroadcastFilter) receiver;
                    packageName = recv.receiverList.app.processName;
                } else /* if (receiver instanceof ResolveInfo) */ {
                    ResolveInfo info = (ResolveInfo) receiver;
                    packageName = info.activityInfo.applicationInfo.packageName;
                }
                Slog.i(TAG, "uid:" + brs.keyAt(i)
                        + " packageName:" + packageName
                        + " receivers:" + brs.valueAt(i).receivers.size());
            }
        }
    }

    private DeferredBootCompletedBroadcastPerUser getDeferredPerUser(int userId) {
        if (mUser2Deferred.contains(userId)) {
            return mUser2Deferred.get(userId);
        } else {
            final DeferredBootCompletedBroadcastPerUser temp =
                    new DeferredBootCompletedBroadcastPerUser(userId);
            mUser2Deferred.put(userId, temp);
            return temp;
        }
    }

    /**
     * ActivityManagerService.attachApplication() call this method to notify that the UID is ready
     * to accept deferred LOCKED_BOOT_COMPLETED and BOOT_COMPLETED broadcasts.
     * @param uid
     */
    public void updateUidReadyForBootCompletedBroadcastLocked(int uid) {
        getDeferredPerUser(UserHandle.getUserId(uid)).updateUidReady(uid);
    }

    private @Nullable BroadcastRecord dequeueDeferredBootCompletedBroadcast() {
        final boolean isAllUidReady = (mQueue.mService.mConstants.mDeferBootCompletedBroadcast
                == DEFER_BOOT_COMPLETED_BROADCAST_NONE);
        BroadcastRecord next = null;
        for (int i = 0, size = mUser2Deferred.size(); i < size; i++) {
            next = mUser2Deferred.valueAt(i).dequeueDeferredBootCompletedBroadcast(isAllUidReady);
            if (next != null) {
                break;
            }
        }
        return next;
    }

    /**
     * Constructed & sharing a lock with its associated BroadcastQueue instance
     */
    public BroadcastDispatcher(BroadcastQueue queue, BroadcastConstants constants,
            Handler handler, Object lock) {
        mQueue = queue;
        mConstants = constants;
        mHandler = handler;
        mLock = lock;
    }

    /**
     * Spin up the integration with the alarm manager service; done lazily to manage
     * service availability ordering during boot.
     */
    public void start() {
        // Set up broadcast alarm tracking
        mAlarm = LocalServices.getService(AlarmManagerInternal.class);
        mAlarm.registerInFlightListener(mAlarmListener);
    }

    /**
     * Standard contents-are-empty check
     */
    public boolean isEmpty() {
        synchronized (mLock) {
            return isIdle()
                    && getBootCompletedBroadcastsUidsSize(Intent.ACTION_LOCKED_BOOT_COMPLETED) == 0
                    && getBootCompletedBroadcastsUidsSize(Intent.ACTION_BOOT_COMPLETED) == 0;
        }
    }

    /**
     * Have less check than {@link #isEmpty()}.
     * The dispatcher is considered as idle even with deferred LOCKED_BOOT_COMPLETED/BOOT_COMPLETED
     * broadcasts because those can be deferred until the first time the uid's process is started.
     * @return
     */
    public boolean isIdle() {
        synchronized (mLock) {
            return mCurrentBroadcast == null
                    && mOrderedBroadcasts.isEmpty()
                    && isDeferralsListEmpty(mDeferredBroadcasts)
                    && isDeferralsListEmpty(mAlarmBroadcasts);
        }
    }

    private static int pendingInDeferralsList(ArrayList<Deferrals> list) {
        int pending = 0;
        final int numEntries = list.size();
        for (int i = 0; i < numEntries; i++) {
            pending += list.get(i).size();
        }
        return pending;
    }

    private static boolean isDeferralsListEmpty(ArrayList<Deferrals> list) {
        return pendingInDeferralsList(list) == 0;
    }

    /**
     * Strictly for logging, describe the currently pending contents in a human-
     * readable way
     */
    public String describeStateLocked() {
        final StringBuilder sb = new StringBuilder(128);
        if (mCurrentBroadcast != null) {
            sb.append("1 in flight, ");
        }
        sb.append(mOrderedBroadcasts.size());
        sb.append(" ordered");
        int n = pendingInDeferralsList(mAlarmBroadcasts);
        if (n > 0) {
            sb.append(", ");
            sb.append(n);
            sb.append(" deferrals in alarm recipients");
        }
        n = pendingInDeferralsList(mDeferredBroadcasts);
        if (n > 0) {
            sb.append(", ");
            sb.append(n);
            sb.append(" deferred");
        }
        n = getBootCompletedBroadcastsUidsSize(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        if (n > 0) {
            sb.append(", ");
            sb.append(n);
            sb.append(" deferred LOCKED_BOOT_COMPLETED/");
            sb.append(getBootCompletedBroadcastsReceiversSize(Intent.ACTION_LOCKED_BOOT_COMPLETED));
            sb.append(" receivers");
        }

        n = getBootCompletedBroadcastsUidsSize(Intent.ACTION_BOOT_COMPLETED);
        if (n > 0) {
            sb.append(", ");
            sb.append(n);
            sb.append(" deferred BOOT_COMPLETED/");
            sb.append(getBootCompletedBroadcastsReceiversSize(Intent.ACTION_BOOT_COMPLETED));
            sb.append(" receivers");
        }
        return sb.toString();
    }

    // ----------------------------------
    // BroadcastQueue operation support
    void enqueueOrderedBroadcastLocked(BroadcastRecord r) {
        if (r.receivers == null || r.receivers.isEmpty()) {
            mOrderedBroadcasts.add(r);
            return;
        }

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(r.intent.getAction())) {
            // Create one BroadcastRecord for each UID that can be deferred.
            final SparseArray<BroadcastRecord> deferred =
                    r.splitDeferredBootCompletedBroadcastLocked(mQueue.mService.mInternal,
                            mQueue.mService.mConstants.mDeferBootCompletedBroadcast);
            getDeferredPerUser(r.userId).enqueueBootCompletedBroadcasts(
                    Intent.ACTION_LOCKED_BOOT_COMPLETED, deferred);
            if (!r.receivers.isEmpty()) {
                // The non-deferred receivers.
                mOrderedBroadcasts.add(r);
                return;
            }
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(r.intent.getAction())) {
            // Create one BroadcastRecord for each UID that can be deferred.
            final SparseArray<BroadcastRecord> deferred =
                    r.splitDeferredBootCompletedBroadcastLocked(mQueue.mService.mInternal,
                            mQueue.mService.mConstants.mDeferBootCompletedBroadcast);
            getDeferredPerUser(r.userId).enqueueBootCompletedBroadcasts(
                    Intent.ACTION_BOOT_COMPLETED, deferred);
            if (!r.receivers.isEmpty()) {
                // The non-deferred receivers.
                mOrderedBroadcasts.add(r);
                return;
            }
        } else {
            mOrderedBroadcasts.add(r);
        }
    }

    /**
     * Return the total number of UIDs in all deferred boot completed BroadcastRecord.
     */
    private int getBootCompletedBroadcastsUidsSize(String action) {
        int size = 0;
        for (int i = 0, s = mUser2Deferred.size(); i < s; i++) {
            size += mUser2Deferred.valueAt(i).getBootCompletedBroadcastsUidsSize(action);
        }
        return size;
    }

    /**
     * Return the total number of receivers in all deferred boot completed BroadcastRecord.
     */
    private int getBootCompletedBroadcastsReceiversSize(String action) {
        int size = 0;
        for (int i = 0, s = mUser2Deferred.size(); i < s; i++) {
            size += mUser2Deferred.valueAt(i).getBootCompletedBroadcastsReceiversSize(action);
        }
        return size;
    }

    // Returns the now-replaced broadcast record, or null if none
    BroadcastRecord replaceBroadcastLocked(BroadcastRecord r, String typeForLogging) {
        // Simple case, in the ordinary queue.
        BroadcastRecord old = replaceBroadcastLocked(mOrderedBroadcasts, r, typeForLogging);

        // If we didn't find it, less-simple:  in a deferral queue?
        if (old == null) {
            old = replaceDeferredBroadcastLocked(mAlarmBroadcasts, r, typeForLogging);
        }
        if (old == null) {
            old = replaceDeferredBroadcastLocked(mDeferredBroadcasts, r, typeForLogging);
        }
        return old;
    }

    private BroadcastRecord replaceDeferredBroadcastLocked(ArrayList<Deferrals> list,
            BroadcastRecord r, String typeForLogging) {
        BroadcastRecord old;
        final int numEntries = list.size();
        for (int i = 0; i < numEntries; i++) {
            final Deferrals d = list.get(i);
            old = replaceBroadcastLocked(d.broadcasts, r, typeForLogging);
            if (old != null) {
                return old;
            }
        }
        return null;
    }

    private BroadcastRecord replaceBroadcastLocked(ArrayList<BroadcastRecord> list,
            BroadcastRecord r, String typeForLogging) {
        BroadcastRecord old;
        final Intent intent = r.intent;
        // Any in-flight broadcast has already been popped, and cannot be replaced.
        // (This preserves existing behavior of the replacement API)
        for (int i = list.size() - 1; i >= 0; i--) {
            old = list.get(i);
            if (old.userId == r.userId && intent.filterEquals(old.intent)) {
                if (DEBUG_BROADCAST) {
                    Slog.v(TAG, "***** Replacing " + typeForLogging
                            + " [" + mQueue.mQueueName + "]: " + intent);
                }
                // Clone deferral state too if any
                r.deferred = old.deferred;
                list.set(i, r);
                return old;
            }
        }
        return null;
    }

    boolean cleanupDisabledPackageReceiversLocked(final String packageName,
            Set<String> filterByClasses, final int userId, final boolean doit) {
        // Note: fast short circuits when 'doit' is false, as soon as we hit any
        // "yes we would do something" circumstance
        boolean didSomething = cleanupBroadcastListDisabledReceiversLocked(mOrderedBroadcasts,
                packageName, filterByClasses, userId, doit);
        if (doit || !didSomething) {
            ArrayList<BroadcastRecord> lockedBootCompletedBroadcasts = new ArrayList<>();
            for (int u = 0, usize = mUser2Deferred.size(); u < usize; u++) {
                SparseArray<BroadcastRecord> brs =
                        mUser2Deferred.valueAt(u).mDeferredLockedBootCompletedBroadcasts;
                for (int i = 0, size = brs.size(); i < size; i++) {
                    lockedBootCompletedBroadcasts.add(brs.valueAt(i));
                }
            }
            didSomething = cleanupBroadcastListDisabledReceiversLocked(
                    lockedBootCompletedBroadcasts,
                    packageName, filterByClasses, userId, doit);
        }
        if (doit || !didSomething) {
            ArrayList<BroadcastRecord> bootCompletedBroadcasts = new ArrayList<>();
            for (int u = 0, usize = mUser2Deferred.size(); u < usize; u++) {
                SparseArray<BroadcastRecord> brs =
                        mUser2Deferred.valueAt(u).mDeferredBootCompletedBroadcasts;
                for (int i = 0, size = brs.size(); i < size; i++) {
                    bootCompletedBroadcasts.add(brs.valueAt(i));
                }
            }
            didSomething = cleanupBroadcastListDisabledReceiversLocked(bootCompletedBroadcasts,
                    packageName, filterByClasses, userId, doit);
        }
        if (doit || !didSomething) {
            didSomething |= cleanupDeferralsListDisabledReceiversLocked(mAlarmBroadcasts,
                    packageName, filterByClasses, userId, doit);
        }
        if (doit || !didSomething) {
            didSomething |= cleanupDeferralsListDisabledReceiversLocked(mDeferredBroadcasts,
                    packageName, filterByClasses, userId, doit);
        }
        if ((doit || !didSomething) && mCurrentBroadcast != null) {
            didSomething |= mCurrentBroadcast.cleanupDisabledPackageReceiversLocked(
                    packageName, filterByClasses, userId, doit);
        }

        return didSomething;
    }

    private boolean cleanupDeferralsListDisabledReceiversLocked(ArrayList<Deferrals> list,
            final String packageName, Set<String> filterByClasses, final int userId,
            final boolean doit) {
        boolean didSomething = false;
        for (Deferrals d : list) {
            didSomething = cleanupBroadcastListDisabledReceiversLocked(d.broadcasts,
                    packageName, filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }
        return didSomething;
    }

    private boolean cleanupBroadcastListDisabledReceiversLocked(ArrayList<BroadcastRecord> list,
            final String packageName, Set<String> filterByClasses, final int userId,
            final boolean doit) {
        boolean didSomething = false;
        for (BroadcastRecord br : list) {
            didSomething |= br.cleanupDisabledPackageReceiversLocked(packageName,
                    filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }
        return didSomething;
    }

    /**
     * Standard proto dump entry point
     */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        if (mCurrentBroadcast != null) {
            mCurrentBroadcast.dumpDebug(proto, fieldId);
        }
        for (Deferrals d : mAlarmBroadcasts) {
            d.dumpDebug(proto, fieldId);
        }
        for (BroadcastRecord br : mOrderedBroadcasts) {
            br.dumpDebug(proto, fieldId);
        }
        for (Deferrals d : mDeferredBroadcasts) {
            d.dumpDebug(proto, fieldId);
        }

        for (int i = 0, size = mUser2Deferred.size(); i < size; i++) {
            mUser2Deferred.valueAt(i).dumpDebug(proto, fieldId);
        }
    }

    // ----------------------------------
    // Dispatch & deferral management

    public BroadcastRecord getActiveBroadcastLocked() {
        return mCurrentBroadcast;
    }

    /**
     * If there is a deferred broadcast that is being sent to an alarm target, return
     * that one.  If there's no deferred alarm target broadcast but there is one
     * that has reached the end of its deferral, return that.
     *
     * This stages the broadcast internally until it is retired, and returns that
     * staged record if this is called repeatedly, until retireBroadcast(r) is called.
     */
    public BroadcastRecord getNextBroadcastLocked(final long now) {
        if (mCurrentBroadcast != null) {
            return mCurrentBroadcast;
        }

        final boolean someQueued = !mOrderedBroadcasts.isEmpty();

        BroadcastRecord next = null;

        if (next == null) {
            next = dequeueDeferredBootCompletedBroadcast();
        }

        if (next == null && !mAlarmBroadcasts.isEmpty()) {
            next = popLocked(mAlarmBroadcasts);
            if (DEBUG_BROADCAST_DEFERRAL && next != null) {
                Slog.i(TAG, "Next broadcast from alarm targets: " + next);
            }
        }

        if (next == null && !mDeferredBroadcasts.isEmpty()) {
            // We're going to deliver either:
            // 1. the next "overdue" deferral; or
            // 2. the next ordinary ordered broadcast; *or*
            // 3. the next not-yet-overdue deferral.

            for (int i = 0; i < mDeferredBroadcasts.size(); i++) {
                Deferrals d = mDeferredBroadcasts.get(i);
                if (now < d.deferUntil && someQueued) {
                    // stop looking when we haven't hit the next time-out boundary
                    // but only if we have un-deferred broadcasts waiting,
                    // otherwise we can deliver whatever deferred broadcast
                    // is next available.
                    break;
                }

                if (d.broadcasts.size() > 0) {
                    next = d.broadcasts.remove(0);
                    // apply deferral-interval decay policy and move this uid's
                    // deferred broadcasts down in the delivery queue accordingly
                    mDeferredBroadcasts.remove(i); // already 'd'
                    d.deferredBy = calculateDeferral(d.deferredBy);
                    d.deferUntil += d.deferredBy;
                    insertLocked(mDeferredBroadcasts, d);
                    if (DEBUG_BROADCAST_DEFERRAL) {
                        Slog.i(TAG, "Next broadcast from deferrals " + next
                                + ", deferUntil now " + d.deferUntil);
                    }
                    break;
                }
            }
        }

        if (next == null && someQueued) {
            next = mOrderedBroadcasts.remove(0);
            if (DEBUG_BROADCAST_DEFERRAL) {
                Slog.i(TAG, "Next broadcast from main queue: " + next);
            }
        }

        mCurrentBroadcast = next;
        return next;
    }

    /**
     * Called after the broadcast queue finishes processing the currently
     * active broadcast (obtained by calling getNextBroadcastLocked()).
     */
    public void retireBroadcastLocked(final BroadcastRecord r) {
        // ERROR if 'r' is not the active broadcast
        if (r != mCurrentBroadcast) {
            Slog.wtf(TAG, "Retiring broadcast " + r
                    + " doesn't match current outgoing " + mCurrentBroadcast);
        }
        mCurrentBroadcast = null;
    }

    /**
     * Called prior to broadcast dispatch to check whether the intended
     * recipient is currently subject to deferral policy.
     */
    public boolean isDeferringLocked(final int uid) {
        Deferrals d = findUidLocked(uid);
        if (d != null && d.broadcasts.isEmpty()) {
            // once we've caught up with deferred broadcasts to this uid
            // and time has advanced sufficiently that we wouldn't be
            // deferring newly-enqueued ones, we're back to normal policy.
            if (SystemClock.uptimeMillis() >= d.deferUntil) {
                if (DEBUG_BROADCAST_DEFERRAL) {
                    Slog.i(TAG, "No longer deferring broadcasts to uid " + d.uid);
                }
                removeDeferral(d);
                return false;
            }
        }
        return (d != null);
    }

    /**
     * Defer broadcasts for the given app.  If 'br' is non-null, this also makes
     * sure that broadcast record is enqueued as the next upcoming broadcast for
     * the app.
     */
    public void startDeferring(final int uid) {
        synchronized (mLock) {
            Deferrals d = findUidLocked(uid);

            // If we're not yet tracking this app, set up that bookkeeping
            if (d == null) {
                // Start a new deferral
                final long now = SystemClock.uptimeMillis();
                d = new Deferrals(uid,
                        now,
                        mConstants.DEFERRAL,
                        mAlarmUids.get(uid, 0));
                if (DEBUG_BROADCAST_DEFERRAL) {
                    Slog.i(TAG, "Now deferring broadcasts to " + uid
                            + " until " + d.deferUntil);
                }
                // where it goes depends on whether it is coming into an alarm-related situation
                if (d.alarmCount == 0) {
                    // common case, put it in the ordinary priority queue
                    insertLocked(mDeferredBroadcasts, d);
                    scheduleDeferralCheckLocked(true);
                } else {
                    // alarm-related: strict order-encountered
                    mAlarmBroadcasts.add(d);
                }
            } else {
                // We're already deferring, but something was slow again.  Reset the
                // deferral decay progression.
                d.deferredBy = mConstants.DEFERRAL;
                if (DEBUG_BROADCAST_DEFERRAL) {
                    Slog.i(TAG, "Uid " + uid + " slow again, deferral interval reset to "
                            + d.deferredBy);
                }
            }
        }
    }

    /**
     * Key entry point when a broadcast about to be delivered is instead
     * set aside for deferred delivery
     */
    public void addDeferredBroadcast(final int uid, BroadcastRecord br) {
        if (DEBUG_BROADCAST_DEFERRAL) {
            Slog.i(TAG, "Enqueuing deferred broadcast " + br);
        }
        synchronized (mLock) {
            Deferrals d = findUidLocked(uid);
            if (d == null) {
                Slog.wtf(TAG, "Adding deferred broadcast but not tracking " + uid);
            } else {
                if (br == null) {
                    Slog.wtf(TAG, "Deferring null broadcast to " + uid);
                } else {
                    br.deferred = true;
                    d.add(br);
                }
            }
        }
    }

    /**
     * When there are deferred broadcasts, we need to make sure to recheck the
     * dispatch queue when they come due.  Alarm-sensitive deferrals get dispatched
     * aggressively, so we only need to use the ordinary deferrals timing to figure
     * out when to recheck.
     */
    public void scheduleDeferralCheckLocked(boolean force) {
        if ((force || !mRecheckScheduled) && !mDeferredBroadcasts.isEmpty()) {
            final Deferrals d = mDeferredBroadcasts.get(0);
            if (!d.broadcasts.isEmpty()) {
                mHandler.removeCallbacks(mScheduleRunnable);
                mHandler.postAtTime(mScheduleRunnable, d.deferUntil);
                mRecheckScheduled = true;
                if (DEBUG_BROADCAST_DEFERRAL) {
                    Slog.i(TAG, "Scheduling deferred broadcast recheck at " + d.deferUntil);
                }
            }
        }
    }

    /**
     * Cancel all current deferrals; that is, make all currently-deferred broadcasts
     * immediately deliverable.  Used by the wait-for-broadcast-idle mechanism.
     */
    public void cancelDeferralsLocked() {
        zeroDeferralTimes(mAlarmBroadcasts);
        zeroDeferralTimes(mDeferredBroadcasts);
    }

    private static void zeroDeferralTimes(ArrayList<Deferrals> list) {
        final int num = list.size();
        for (int i = 0; i < num; i++) {
            Deferrals d = list.get(i);
            // Safe to do this in-place because it won't break ordering
            d.deferUntil = d.deferredBy = 0;
        }
    }

    // ----------------------------------

    /**
     * If broadcasts to this uid are being deferred, find the deferrals record about it.
     * @return null if this uid's broadcasts are not being deferred
     */
    private Deferrals findUidLocked(final int uid) {
        // The common case is that they it isn't also an alarm target...
        Deferrals d = findUidLocked(uid, mDeferredBroadcasts);
        // ...but if not there, also check alarm-prioritized deferrals
        if (d == null) {
            d = findUidLocked(uid, mAlarmBroadcasts);
        }
        return d;
    }

    /**
     * Remove the given deferral record from whichever queue it might be in at present
     * @return true if the deferral was in fact found, false if this made no changes
     */
    private boolean removeDeferral(Deferrals d) {
        boolean didRemove = mDeferredBroadcasts.remove(d);
        if (!didRemove) {
            didRemove = mAlarmBroadcasts.remove(d);
        }
        return didRemove;
    }

    /**
     * Find the deferrals record for the given uid in the given list
     */
    private static Deferrals findUidLocked(final int uid, ArrayList<Deferrals> list) {
        final int numElements = list.size();
        for (int i = 0; i < numElements; i++) {
            Deferrals d = list.get(i);
            if (uid == d.uid) {
                return d;
            }
        }
        return null;
    }

    /**
     * Pop the next broadcast record from the head of the given deferrals list,
     * if one exists.
     */
    private static BroadcastRecord popLocked(ArrayList<Deferrals> list) {
        final Deferrals d = list.get(0);
        return d.broadcasts.isEmpty() ? null : d.broadcasts.remove(0);
    }

    /**
     * Insert the given Deferrals into the priority queue, sorted by defer-until milestone
     */
    private static void insertLocked(ArrayList<Deferrals> list, Deferrals d) {
        // Simple linear search is appropriate here because we expect to
        // have very few entries in the deferral lists (i.e. very few badly-
        // behaving apps currently facing deferral)
        int i;
        final int numElements = list.size();
        for (i = 0; i < numElements; i++) {
            if (d.deferUntil < list.get(i).deferUntil) {
                break;
            }
        }
        list.add(i, d);
    }

    /**
     * Calculate a new deferral time based on the previous time.  This should decay
     * toward zero, though a small nonzero floor is an option.
     */
    private long calculateDeferral(long previous) {
        return Math.max(mConstants.DEFERRAL_FLOOR,
                (long) (previous * mConstants.DEFERRAL_DECAY_FACTOR));
    }

    // ----------------------------------

    boolean dumpLocked(PrintWriter pw, String dumpPackage, String queueName,
            SimpleDateFormat sdf) {
        final Dumper dumper = new Dumper(pw, queueName, dumpPackage, sdf);
        boolean printed = false;

        dumper.setHeading("Currently in flight");
        dumper.setLabel("In-Flight Ordered Broadcast");
        if (mCurrentBroadcast != null) {
            dumper.dump(mCurrentBroadcast);
        } else {
            pw.println("  (null)");
        }

        dumper.setHeading("Active ordered broadcasts");
        dumper.setLabel("Active Ordered Broadcast");
        for (Deferrals d : mAlarmBroadcasts) {
            d.dumpLocked(dumper);
        }
        printed |= dumper.didPrint();

        for (BroadcastRecord br : mOrderedBroadcasts) {
            dumper.dump(br);
        }
        printed |= dumper.didPrint();

        dumper.setHeading("Deferred ordered broadcasts");
        dumper.setLabel("Deferred Ordered Broadcast");
        for (Deferrals d : mDeferredBroadcasts) {
            d.dumpLocked(dumper);
        }
        printed |= dumper.didPrint();

        dumper.setHeading("Deferred LOCKED_BOOT_COMPLETED broadcasts");
        dumper.setLabel("Deferred LOCKED_BOOT_COMPLETED Broadcast");
        for (int i = 0, size = mUser2Deferred.size(); i < size; i++) {
            mUser2Deferred.valueAt(i).dump(dumper, Intent.ACTION_LOCKED_BOOT_COMPLETED);
        }
        printed |= dumper.didPrint();

        dumper.setHeading("Deferred BOOT_COMPLETED broadcasts");
        dumper.setLabel("Deferred BOOT_COMPLETED Broadcast");
        for (int i = 0, size = mUser2Deferred.size(); i < size; i++) {
            mUser2Deferred.valueAt(i).dump(dumper, Intent.ACTION_BOOT_COMPLETED);
        }
        printed |= dumper.didPrint();

        return printed;
    }
}
