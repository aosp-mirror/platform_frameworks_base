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

import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

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

        void writeToProto(ProtoOutputStream proto, long fieldId) {
            for (BroadcastRecord br : broadcasts) {
                br.writeToProto(proto, fieldId);
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
            return mCurrentBroadcast == null
                    && mOrderedBroadcasts.isEmpty()
                    && mDeferredBroadcasts.isEmpty()
                    && mAlarmBroadcasts.isEmpty();
        }
    }

    /**
     * Not quite the traditional size() measurement; includes any in-process but
     * not yet retired active outbound broadcast.
     */
    public int totalUndelivered() {
        synchronized (mLock) {
            return mAlarmBroadcasts.size()
                    + mDeferredBroadcasts.size()
                    + mOrderedBroadcasts.size()
                    + (mCurrentBroadcast == null ? 0 : 1);
        }
    }

    // ----------------------------------
    // BroadcastQueue operation support

    void enqueueOrderedBroadcastLocked(BroadcastRecord r) {
        mOrderedBroadcasts.add(r);
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
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        if (mCurrentBroadcast != null) {
            mCurrentBroadcast.writeToProto(proto, fieldId);
        }
        for (Deferrals d : mAlarmBroadcasts) {
            d.writeToProto(proto, fieldId);
        }
        for (BroadcastRecord br : mOrderedBroadcasts) {
            br.writeToProto(proto, fieldId);
        }
        for (Deferrals d : mDeferredBroadcasts) {
            d.writeToProto(proto, fieldId);
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

        BroadcastRecord next = null;
        if (!mAlarmBroadcasts.isEmpty()) {
            next = popLocked(mAlarmBroadcasts);
            if (DEBUG_BROADCAST_DEFERRAL && next != null) {
                Slog.i(TAG, "Next broadcast from alarm targets: " + next);
            }
        }

        if (next == null && !mDeferredBroadcasts.isEmpty()) {
            for (int i = 0; i < mDeferredBroadcasts.size(); i++) {
                Deferrals d = mDeferredBroadcasts.get(i);
                if (now < d.deferUntil) {
                    // No more deferrals due
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

        if (next == null && !mOrderedBroadcasts.isEmpty()) {
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

        return printed;
    }
}
