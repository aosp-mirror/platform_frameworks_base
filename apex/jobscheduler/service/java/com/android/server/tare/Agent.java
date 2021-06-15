/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.server.tare.EconomicPolicy.REGULATION_BASIC_INCOME;
import static com.android.server.tare.EconomicPolicy.REGULATION_BIRTHRIGHT;
import static com.android.server.tare.EconomicPolicy.REGULATION_WEALTH_RECLAMATION;
import static com.android.server.tare.EconomicPolicy.TYPE_ACTION;
import static com.android.server.tare.EconomicPolicy.TYPE_REWARD;
import static com.android.server.tare.EconomicPolicy.eventToString;
import static com.android.server.tare.EconomicPolicy.getEventType;
import static com.android.server.tare.TareUtils.narcToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.usage.AppStandbyInternal;

import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Other half of the IRS. The agent handles the nitty gritty details, interacting directly with
 * ledgers, carrying out specific events such as wealth reclamation, granting initial balances or
 * replenishing balances, and tracking ongoing events.
 */
class Agent {
    private static final String TAG = "TARE-" + Agent.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    /**
     * The minimum amount of time an app must not have been used by the user before we start
     * regularly reclaiming ARCs from it.
     */
    private static final long MIN_UNUSED_TIME_MS = 3 * 24 * HOUR_IN_MILLIS;
    /**
     * The maximum amount of time we'll keep a transaction around for.
     * For now, only keep transactions we actually have a use for. We can increase it if we want
     * to use older transactions or provide older transactions to apps.
     */
    private static final long MAX_TRANSACTION_AGE_MS = 24 * HOUR_IN_MILLIS;

    private static final String ALARM_TAG_LEDGER_CLEANUP = "*tare.ledger_cleanup*";

    private final Object mLock;
    private final CompleteEconomicPolicy mCompleteEconomicPolicy;
    private final Handler mHandler;
    private final InternalResourceService mIrs;

    private final AppStandbyInternal mAppStandbyInternal;

    @GuardedBy("mLock")
    private final SparseArrayMap<String, Ledger> mLedgers = new SparseArrayMap<>();

    @GuardedBy("mLock")
    private long mCurrentNarcsInCirculation;

    /**
     * Listener to track and manage when we remove old transactions from ledgers.
     */
    @GuardedBy("mLock")
    private final LedgerCleanupAlarmListener mLedgerCleanupAlarmListener =
            new LedgerCleanupAlarmListener();

    private static final int MSG_CLEAN_LEDGER = 1;
    private static final int MSG_SET_ALARMS = 2;

    Agent(@NonNull InternalResourceService irs,
            @NonNull CompleteEconomicPolicy completeEconomicPolicy) {
        mLock = irs.getLock();
        mIrs = irs;
        mCompleteEconomicPolicy = completeEconomicPolicy;
        mHandler = new AgentHandler(TareHandlerThread.get().getLooper());
        mAppStandbyInternal = LocalServices.getService(AppStandbyInternal.class);
    }

    @GuardedBy("mLock")
    @NonNull
    private Ledger getLedgerLocked(final int userId, @NonNull final String pkgName) {
        Ledger ledger = mLedgers.get(userId, pkgName);
        if (ledger == null) {
            // TODO: load from disk
            ledger = new Ledger();
            mLedgers.add(userId, pkgName, ledger);
        }
        return ledger;
    }

    /** Get an app's current balance, factoring in any currently ongoing events. */
    @GuardedBy("mLock")
    long getBalanceLocked(final int userId, @NonNull final String pkgName) {
        final Ledger ledger = getLedgerLocked(userId, pkgName);
        long balance = ledger.getCurrentBalance();
        // TODO: add ongoing events
        return balance;
    }

    @GuardedBy("mLock")
    void noteInstantaneousEventLocked(final int userId, @NonNull final String pkgName,
            final int eventId, @Nullable String tag) {
        final long now = System.currentTimeMillis();
        final Ledger ledger = getLedgerLocked(userId, pkgName);

        final int eventType = getEventType(eventId);
        switch (eventType) {
            case TYPE_ACTION:
                final long actionCost =
                        mCompleteEconomicPolicy.getCostOfAction(eventId, userId, pkgName);

                recordTransactionLocked(userId, pkgName, ledger,
                        new Ledger.Transaction(now, now, eventId, tag, -actionCost));
                break;

            case TYPE_REWARD:
                final EconomicPolicy.Reward reward = mCompleteEconomicPolicy.getReward(eventId);
                if (reward != null) {
                    final long rewardSum = ledger.get24HourSum(eventId, now);
                    final long rewardVal = Math.max(0,
                            Math.min(reward.maxDailyReward - rewardSum, reward.instantReward));
                    recordTransactionLocked(userId, pkgName, ledger,
                            new Ledger.Transaction(now, now, eventId, tag, rewardVal));
                }
                break;

            default:
                Slog.w(TAG, "Unsupported event type: " + eventType);
        }
    }

    @GuardedBy("mLock")
    private void recordTransactionLocked(final int userId, @NonNull final String pkgName,
            @NonNull Ledger ledger, @NonNull Ledger.Transaction transaction) {
        final long maxCirculationAllowed = mIrs.getMaxCirculationLocked();
        final long newArcsInCirculation = mCurrentNarcsInCirculation + transaction.delta;
        if (transaction.delta > 0 && newArcsInCirculation > maxCirculationAllowed) {
            final long newDelta = maxCirculationAllowed - mCurrentNarcsInCirculation;
            Slog.i(TAG, "Would result in too many credits in circulation. Decreasing transaction "
                    + eventToString(transaction.eventId)
                    + (transaction.tag == null ? "" : ":" + transaction.tag)
                    + " for <" + userId + ">" + pkgName + " by " + (transaction.delta - newDelta));
            transaction = new Ledger.Transaction(
                    transaction.startTimeMs, transaction.endTimeMs,
                    transaction.eventId, transaction.tag, newDelta);
        }
        final long originalBalance = ledger.getCurrentBalance();
        if (transaction.delta > 0
                && originalBalance + transaction.delta
                > mCompleteEconomicPolicy.getMaxSatiatedBalance()) {
            final long newDelta = mCompleteEconomicPolicy.getMaxSatiatedBalance() - originalBalance;
            Slog.i(TAG, "Would result in becoming too rich. Decreasing transaction "
                    + eventToString(transaction.eventId)
                    + (transaction.tag == null ? "" : ":" + transaction.tag)
                    + " for <" + userId + ">" + pkgName + " by " + (transaction.delta - newDelta));
            transaction = new Ledger.Transaction(
                    transaction.startTimeMs, transaction.endTimeMs,
                    transaction.eventId, transaction.tag, newDelta);
        }
        ledger.recordTransaction(transaction);
        mCurrentNarcsInCirculation += transaction.delta;
        if (!mLedgerCleanupAlarmListener.hasAlarmScheduledLocked(userId, pkgName)) {
            // The earliest transaction won't change until we clean up the ledger, so no point
            // continuing to reschedule an existing cleanup.
            final long cleanupAlarmElapsed = SystemClock.elapsedRealtime() + MAX_TRANSACTION_AGE_MS
                    - (System.currentTimeMillis() - ledger.getEarliestTransaction().endTimeMs);
            mLedgerCleanupAlarmListener.addAlarmLocked(userId, pkgName, cleanupAlarmElapsed);
        }
        // TODO: save changes to disk in a background thread
        final long newBalance = ledger.getCurrentBalance();
        if (originalBalance <= 0 && newBalance > 0) {
            mIrs.postSolvencyChanged(userId, pkgName, true);
        } else if (originalBalance > 0 && newBalance <= 0) {
            mIrs.postSolvencyChanged(userId, pkgName, false);
        }
    }

    /**
     * Reclaim a percentage of unused ARCs from every app that hasn't been used recently. The
     * reclamation will not reduce an app's balance below its minimum balance as dictated by the
     * EconomicPolicy.
     *
     * @param percentage A value between 0 and 1 to indicate how much of the unused balance should
     *                   be reclaimed.
     */
    @GuardedBy("mLock")
    void reclaimUnusedAssetsLocked(double percentage) {
        final List<PackageInfo> pkgs = mIrs.getInstalledPackages();
        final long now = System.currentTimeMillis();
        for (int i = 0; i < pkgs.size(); ++i) {
            final int userId = UserHandle.getUserId(pkgs.get(i).applicationInfo.uid);
            final String pkgName = pkgs.get(i).packageName;
            final Ledger ledger = getLedgerLocked(userId, pkgName);
            // AppStandby only counts elapsed time for things like this
            // TODO: should we use clock time instead?
            final long timeSinceLastUsedMs =
                    mAppStandbyInternal.getTimeSinceLastUsedByUser(pkgName, userId);
            if (timeSinceLastUsedMs >= MIN_UNUSED_TIME_MS) {
                // Use a constant floor instead of the scaled floor from the IRS.
                final long minBalance =
                        mCompleteEconomicPolicy.getMinSatiatedBalance(userId, pkgName);
                final long curBalance = ledger.getCurrentBalance();
                long toReclaim = (long) (curBalance * percentage);
                if (curBalance - toReclaim < minBalance) {
                    toReclaim = curBalance - minBalance;
                }
                if (toReclaim > 0) {
                    Slog.i(TAG, "Reclaiming unused wealth! Taking " + toReclaim
                            + " from <" + userId + ">" + pkgName);

                    recordTransactionLocked(userId, pkgName, ledger,
                            new Ledger.Transaction(
                                    now, now, REGULATION_WEALTH_RECLAMATION, null, -toReclaim));
                }
            }
        }
    }

    @GuardedBy("mLock")
    void distributeBasicIncomeLocked(int batteryLevel) {
        List<PackageInfo> pkgs = mIrs.getInstalledPackages();
        final long now = System.currentTimeMillis();
        for (int i = 0; i < pkgs.size(); ++i) {
            final PackageInfo pkgInfo = pkgs.get(i);
            final int userId = UserHandle.getUserId(pkgInfo.applicationInfo.uid);
            final String pkgName = pkgInfo.packageName;
            Ledger ledger = getLedgerLocked(userId, pkgName);
            final long minBalance = mIrs.getMinBalanceLocked(userId, pkgName);
            final double perc = batteryLevel / 100d;
            // TODO: maybe don't give credits to bankrupt apps until battery level >= 50%
            if (ledger.getCurrentBalance() < minBalance) {
                final long shortfall = minBalance - getBalanceLocked(userId, pkgName);
                recordTransactionLocked(userId, pkgName, ledger,
                        new Ledger.Transaction(now, now, REGULATION_BASIC_INCOME,
                                null, (long) (perc * shortfall)));
            }
        }
    }

    /** Give each app an initial balance. */
    @GuardedBy("mLock")
    void grantBirthrightsLocked() {
        UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        final int[] userIds = userManagerInternal.getUserIds();
        for (int userId : userIds) {
            grantBirthrightsLocked(userId);
        }
    }

    @GuardedBy("mLock")
    void grantBirthrightsLocked(final int userId) {
        PackageManager packageManager = mIrs.getContext().getPackageManager();
        List<PackageInfo> pkgs = packageManager.getInstalledPackagesAsUser(0, userId);
        final long maxBirthright =
                mIrs.getMaxCirculationLocked() / mIrs.getInstalledPackages().size();
        final long now = System.currentTimeMillis();

        for (int i = 0; i < pkgs.size(); ++i) {
            final PackageInfo packageInfo = pkgs.get(i);
            final String pkgName = packageInfo.packageName;
            final Ledger ledger = getLedgerLocked(userId, pkgName);
            if (ledger.getCurrentBalance() > 0) {
                // App already got credits somehow. Move along.
                Slog.wtf(TAG, "App " + pkgName + " had credits before economy was set up");
                continue;
            }

            recordTransactionLocked(userId, pkgName, ledger,
                    new Ledger.Transaction(now, now, REGULATION_BIRTHRIGHT, null,
                            Math.min(maxBirthright, mIrs.getMinBalanceLocked(userId, pkgName))));
        }
    }

    @GuardedBy("mLock")
    void grantBirthrightLocked(final int userId, @NonNull final String pkgName) {
        final Ledger ledger = getLedgerLocked(userId, pkgName);
        if (ledger.getCurrentBalance() > 0) {
            Slog.wtf(TAG, "App " + pkgName + " had credits as soon as it was installed");
            // App already got credits somehow. Move along.
            return;
        }

        List<PackageInfo> pkgs = mIrs.getInstalledPackages();
        final int numPackages = pkgs.size();
        final long maxBirthright = mIrs.getMaxCirculationLocked() / numPackages;
        final long now = System.currentTimeMillis();

        recordTransactionLocked(userId, pkgName, ledger,
                new Ledger.Transaction(now, now, REGULATION_BIRTHRIGHT, null,
                        Math.min(maxBirthright, mIrs.getMinBalanceLocked(userId, pkgName))));
    }

    @GuardedBy("mLock")
    void onPackageRemovedLocked(final int userId, @NonNull final String pkgName) {
        reclaimAssetsLocked(userId, pkgName);
        mLedgerCleanupAlarmListener.removeAlarmLocked(userId, pkgName);
    }

    /**
     * Reclaims any ARCs granted to the app, making them available to other apps. Also deletes the
     * app's ledger and stops any ongoing event tracking.
     */
    @GuardedBy("mLock")
    private void reclaimAssetsLocked(final int userId, @NonNull final String pkgName) {
        Ledger ledger = getLedgerLocked(userId, pkgName);
        if (ledger.getCurrentBalance() != 0) {
            mCurrentNarcsInCirculation -= ledger.getCurrentBalance();
        }
        // TODO: delete ledger entry from disk
        mLedgers.delete(userId, pkgName);
    }

    @GuardedBy("mLock")
    void onUserRemovedLocked(final int userId, @NonNull final List<String> pkgNames) {
        reclaimAssetsLocked(userId, pkgNames);
        mLedgerCleanupAlarmListener.removeAlarmsLocked(userId);
    }

    @GuardedBy("mLock")
    private void reclaimAssetsLocked(final int userId, @NonNull final List<String> pkgNames) {
        for (int i = 0; i < pkgNames.size(); ++i) {
            reclaimAssetsLocked(userId, pkgNames.get(i));
        }
    }

    /**
     * An {@link AlarmManager.OnAlarmListener} that will queue up all pending alarms and only
     * schedule one alarm for the earliest alarm.
     */
    private abstract class AlarmQueueListener implements AlarmManager.OnAlarmListener {
        final class Package {
            public final String packageName;
            public final int userId;

            Package(int userId, String packageName) {
                this.userId = userId;
                this.packageName = packageName;
            }

            @Override
            public String toString() {
                return "<" + userId + ">" + packageName;
            }

            @Override
            public boolean equals(Object obj) {
                if (obj == null) {
                    return false;
                }
                if (this == obj) {
                    return true;
                }
                if (obj instanceof Package) {
                    Package other = (Package) obj;
                    return userId == other.userId && Objects.equals(packageName, other.packageName);
                } else {
                    return false;
                }
            }

            @Override
            public int hashCode() {
                return packageName.hashCode() + userId;
            }
        }

        class AlarmQueue extends PriorityQueue<Pair<Package, Long>> {
            AlarmQueue() {
                super(1, (o1, o2) -> (int) (o1.second - o2.second));
            }

            boolean contains(@NonNull Package pkg) {
                Pair[] alarms = toArray(new Pair[size()]);
                for (int i = alarms.length - 1; i >= 0; --i) {
                    if (pkg.equals(alarms[i].first)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Remove any instances of the Package from the queue.
             *
             * @return true if an instance was removed, false otherwise.
             */
            boolean remove(@NonNull Package pkg) {
                boolean removed = false;
                Pair[] alarms = toArray(new Pair[size()]);
                for (int i = alarms.length - 1; i >= 0; --i) {
                    if (pkg.equals(alarms[i].first)) {
                        remove(alarms[i]);
                        removed = true;
                    }
                }
                return removed;
            }
        }

        @GuardedBy("mLock")
        private final AlarmQueue mAlarmQueue = new AlarmQueue();
        private final String mAlarmTag;
        /** Whether to use an exact alarm or an inexact alarm. */
        private final boolean mExactAlarm;
        /** The minimum amount of time between check alarms. */
        private final long mMinTimeBetweenAlarmsMs;
        /** The next time the alarm is set to go off, in the elapsed realtime timebase. */
        @GuardedBy("mLock")
        private long mTriggerTimeElapsed = 0;

        protected AlarmQueueListener(@NonNull String alarmTag, boolean exactAlarm,
                long minTimeBetweenAlarmsMs) {
            mAlarmTag = alarmTag;
            mExactAlarm = exactAlarm;
            mMinTimeBetweenAlarmsMs = minTimeBetweenAlarmsMs;
        }

        @GuardedBy("mLock")
        boolean hasAlarmScheduledLocked(int userId, @NonNull String pkgName) {
            final Package pkg = new Package(userId, pkgName);
            return mAlarmQueue.contains(pkg);
        }

        @GuardedBy("mLock")
        void addAlarmLocked(int userId, @NonNull String pkgName, long alarmTimeElapsed) {
            final Package pkg = new Package(userId, pkgName);
            mAlarmQueue.remove(pkg);
            mAlarmQueue.offer(new Pair<>(pkg, alarmTimeElapsed));
            setNextAlarmLocked();
        }

        @GuardedBy("mLock")
        void removeAlarmLocked(@NonNull Package pkg) {
            if (mAlarmQueue.remove(pkg)) {
                setNextAlarmLocked();
            }
        }

        @GuardedBy("mLock")
        void removeAlarmLocked(int userId, @NonNull String packageName) {
            removeAlarmLocked(new Package(userId, packageName));
        }

        @GuardedBy("mLock")
        void removeAlarmsLocked(int userId) {
            boolean removed = false;
            Pair[] alarms = mAlarmQueue.toArray(new Pair[mAlarmQueue.size()]);
            for (int i = alarms.length - 1; i >= 0; --i) {
                final Package pkg = (Package) alarms[i].first;
                if (userId == pkg.userId) {
                    mAlarmQueue.remove(alarms[i]);
                    removed = true;
                }
            }
            if (removed) {
                setNextAlarmLocked();
            }
        }

        /** Sets an alarm with {@link AlarmManager} for the earliest alarm in the queue. */
        @GuardedBy("mLock")
        void setNextAlarmLocked() {
            setNextAlarmLocked(SystemClock.elapsedRealtime());
        }

        /**
         * Sets an alarm with {@link AlarmManager} for the earliest alarm in the queue, using
         * {@code earliestTriggerElapsed} as a floor.
         */
        @GuardedBy("mLock")
        private void setNextAlarmLocked(long earliestTriggerElapsed) {
            if (mAlarmQueue.size() > 0) {
                final Pair<Package, Long> alarm = mAlarmQueue.peek();
                final long nextTriggerTimeElapsed = Math.max(earliestTriggerElapsed, alarm.second);
                // Only schedule the alarm if one of the following is true:
                // 1. There isn't one currently scheduled
                // 2. The new alarm is significantly earlier than the previous alarm. If it's
                // earlier but not significantly so, then we essentially delay the check for some
                // apps by up to a minute.
                // 3. The alarm is after the current alarm.
                if (mTriggerTimeElapsed == 0
                        || nextTriggerTimeElapsed < mTriggerTimeElapsed - MINUTE_IN_MILLIS
                        || mTriggerTimeElapsed < nextTriggerTimeElapsed) {
                    if (DEBUG) {
                        Slog.d(TAG, "Scheduling start alarm at " + nextTriggerTimeElapsed
                                + " for app " + alarm.first);
                    }
                    mHandler.post(() -> {
                        // Never call out to AlarmManager with the lock held. This sits below AM.
                        AlarmManager alarmManager =
                                mIrs.getContext().getSystemService(AlarmManager.class);
                        if (alarmManager != null) {
                            if (mExactAlarm) {
                                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME,
                                        nextTriggerTimeElapsed, mAlarmTag, this, mHandler);
                            } else {
                                alarmManager.setWindow(AlarmManager.ELAPSED_REALTIME,
                                        nextTriggerTimeElapsed, mMinTimeBetweenAlarmsMs / 2,
                                        mAlarmTag, this, mHandler);
                            }
                        } else {
                            mHandler.sendEmptyMessageDelayed(MSG_SET_ALARMS, 30_000);
                        }
                    });
                    mTriggerTimeElapsed = nextTriggerTimeElapsed;
                }
            } else {
                mHandler.post(() -> {
                    // Never call out to AlarmManager with the lock held. This sits below AM.
                    AlarmManager alarmManager =
                            mIrs.getContext().getSystemService(AlarmManager.class);
                    if (alarmManager != null) {
                        // This should only be null at boot time. No concerns around not
                        // cancelling if we get null here.
                        alarmManager.cancel(this);
                    }
                });
                mTriggerTimeElapsed = 0;
            }
        }

        @GuardedBy("mLock")
        protected abstract void processExpiredAlarmLocked(int userId, @NonNull String packageName);

        @Override
        public void onAlarm() {
            synchronized (mLock) {
                final long nowElapsed = SystemClock.elapsedRealtime();
                while (mAlarmQueue.size() > 0) {
                    final Pair<Package, Long> alarm = mAlarmQueue.peek();
                    if (alarm.second <= nowElapsed) {
                        processExpiredAlarmLocked(alarm.first.userId, alarm.first.packageName);
                        mAlarmQueue.remove(alarm);
                    } else {
                        break;
                    }
                }
                setNextAlarmLocked(nowElapsed + mMinTimeBetweenAlarmsMs);
            }
        }

        @GuardedBy("mLock")
        void dumpLocked(IndentingPrintWriter pw) {
            pw.print(mAlarmTag);
            pw.println(" alarms:");
            pw.increaseIndent();

            if (mAlarmQueue.size() == 0) {
                pw.println("NOT WAITING");
            } else {
                Pair[] alarms = mAlarmQueue.toArray(new Pair[mAlarmQueue.size()]);
                for (int i = 0; i < alarms.length; ++i) {
                    final Package pkg = (Package) alarms[i].first;
                    pw.print(pkg);
                    pw.print(": ");
                    pw.print(alarms[i].second);
                    pw.println();
                }
            }

            pw.decreaseIndent();
        }
    }

    /** Clean up old transactions from {@link Ledger}s. */
    private class LedgerCleanupAlarmListener extends AlarmQueueListener {
        private LedgerCleanupAlarmListener() {
            // We don't need to run cleanup too frequently.
            super(ALARM_TAG_LEDGER_CLEANUP, false, HOUR_IN_MILLIS);
        }

        @Override
        @GuardedBy("mLock")
        protected void processExpiredAlarmLocked(int userId, @NonNull String packageName) {
            mHandler.obtainMessage(MSG_CLEAN_LEDGER, userId, 0, packageName).sendToTarget();
        }
    }

    private final class AgentHandler extends Handler {
        AgentHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CLEAN_LEDGER: {
                    final int userId = msg.arg1;
                    final String pkgName = (String) msg.obj;
                    synchronized (mLock) {
                        final Ledger ledger = getLedgerLocked(userId, pkgName);
                        ledger.removeOldTransactions(MAX_TRANSACTION_AGE_MS);
                    }
                }
                break;

                case MSG_SET_ALARMS: {
                    synchronized (mLock) {
                        mLedgerCleanupAlarmListener.setNextAlarmLocked();
                    }
                }
                break;
            }
        }
    }

    @GuardedBy("mLock")
    void dumpLocked(IndentingPrintWriter pw) {
        pw.print("Current GDP: ");
        pw.println(narcToString(mCurrentNarcsInCirculation));

        pw.println();
        mLedgerCleanupAlarmListener.dumpLocked(pw);
    }
}
