/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.utils.quota;

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.server.utils.quota.Uptc.string;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.proto.ProtoOutputStream;
import android.util.quota.QuotaTrackerProto;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;
import com.android.server.utils.AlarmQueue;

/**
 * Base class for trackers that track whether an app has exceeded a count quota.
 *
 * Quotas are applied per userId-package-tag combination (UPTC). Tags can be null.
 *
 * Count and duration limits can be applied at the same time. Each limit is evaluated and
 * controlled independently. If a UPTC reaches one of the limits, it will be considered out
 * of quota until it is below that limit again. Limits are applied according to the category
 * the UPTC is placed in. Categories are basic constructs to apply different limits to
 * different groups of UPTCs. For example, standby buckets can be a set of categories, or
 * foreground & background could be two categories. If every UPTC should have the same limits
 * applied, then only one category is needed.
 *
 * Note: all limits are enforced per category unless explicitly stated otherwise.
 *
 * @hide
 */
abstract class QuotaTracker {
    private static final String TAG = QuotaTracker.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String ALARM_TAG_QUOTA_CHECK = "*" + TAG + ".quota_check*";

    @VisibleForTesting
    static class Injector {
        long getElapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        boolean isAlarmManagerReady() {
            return LocalServices.getService(SystemServiceManager.class).isBootCompleted();
        }
    }

    final Object mLock = new Object();
    final Categorizer mCategorizer;
    @GuardedBy("mLock")
    private final ArraySet<QuotaChangeListener> mQuotaChangeListeners = new ArraySet<>();

    /**
     * Alarm queue to track and manage when each package comes back within quota.
     */
    @GuardedBy("mLock")
    private final InQuotaAlarmQueue mInQuotaAlarmQueue;

    /** "Free quota status" for apps. */
    @GuardedBy("mLock")
    private final SparseArrayMap<String, Boolean> mFreeQuota = new SparseArrayMap<>();

    private final AlarmManager mAlarmManager;
    protected final Context mContext;
    protected final Injector mInjector;

    @GuardedBy("mLock")
    private boolean mIsQuotaFree;

    /**
     * If QuotaTracker should actively track events and check quota. If false, quota will be free
     * and events will not be tracked.
     */
    @GuardedBy("mLock")
    private boolean mIsEnabled = true;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        private String getPackageName(Intent intent) {
            final Uri uri = intent.getData();
            return uri != null ? uri.getSchemeSpecificPart() : null;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return;
            }
            final String action = intent.getAction();
            if (action == null) {
                Slog.e(TAG, "Received intent with null action");
                return;
            }
            switch (action) {
                case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                    final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    synchronized (mLock) {
                        onAppRemovedLocked(UserHandle.getUserId(uid), getPackageName(intent));
                    }
                    break;
                case Intent.ACTION_USER_REMOVED:
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    synchronized (mLock) {
                        onUserRemovedLocked(userId);
                    }
                    break;
            }
        }
    };

    /** The maximum period any Category can have. */
    @VisibleForTesting
    static final long MAX_WINDOW_SIZE_MS = 30 * 24 * 60 * MINUTE_IN_MILLIS; // 1 month

    /**
     * The minimum time any window size can be. A minimum window size helps to avoid CPU
     * churn/looping in cases where there are registered listeners for when UPTCs go in and out of
     * quota.
     */
    @VisibleForTesting
    static final long MIN_WINDOW_SIZE_MS = 20_000;

    QuotaTracker(@NonNull Context context, @NonNull Categorizer categorizer,
            @NonNull Injector injector) {
        mCategorizer = categorizer;
        mContext = context;
        mInjector = injector;
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        // The operation should be fast enough to put it on the FgThread.
        mInQuotaAlarmQueue = new InQuotaAlarmQueue(mContext, FgThread.getHandler().getLooper());

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null,
                BackgroundThread.getHandler());
        final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, userFilter, null,
                BackgroundThread.getHandler());
    }

    // Exposed API to users.

    /** Remove all saved events from the tracker. */
    public void clear() {
        synchronized (mLock) {
            mInQuotaAlarmQueue.removeAllAlarms();
            mFreeQuota.clear();

            dropEverythingLocked();
        }
    }

    /**
     * @return true if the UPTC is within quota, false otherwise.
     * @throws IllegalStateException if given categorizer returns a Category that's not recognized.
     */
    public boolean isWithinQuota(int userId, @NonNull String packageName, @Nullable String tag) {
        synchronized (mLock) {
            return isWithinQuotaLocked(userId, packageName, tag);
        }
    }

    /**
     * Indicates whether quota is currently free or not for a specific app. If quota is free, any
     * currently ongoing events or instantaneous events won't be counted until quota is no longer
     * free.
     */
    public void setQuotaFree(int userId, @NonNull String packageName, boolean isFree) {
        synchronized (mLock) {
            final boolean wasFree = mFreeQuota.getOrDefault(userId, packageName, Boolean.FALSE);
            if (wasFree != isFree) {
                mFreeQuota.add(userId, packageName, isFree);
                onQuotaFreeChangedLocked(userId, packageName, isFree);
            }
        }
    }

    /** Indicates whether quota is currently free or not for all apps. */
    public void setQuotaFree(boolean isFree) {
        synchronized (mLock) {
            if (mIsQuotaFree == isFree) {
                return;
            }
            mIsQuotaFree = isFree;

            if (!mIsEnabled) {
                return;
            }
            onQuotaFreeChangedLocked(mIsQuotaFree);
        }
        scheduleQuotaCheck();
    }

    /**
     * Register a {@link QuotaChangeListener} to be notified of when apps go in and out of quota.
     */
    public void registerQuotaChangeListener(QuotaChangeListener listener) {
        synchronized (mLock) {
            if (mQuotaChangeListeners.add(listener) && mQuotaChangeListeners.size() == 1) {
                scheduleQuotaCheck();
            }
        }
    }

    /** Unregister the listener from future quota change notifications. */
    public void unregisterQuotaChangeListener(QuotaChangeListener listener) {
        synchronized (mLock) {
            mQuotaChangeListeners.remove(listener);
        }
    }

    // Configuration APIs

    /**
     * Completely enables or disables the quota tracker. If the tracker is disabled, all events and
     * internal tracking data will be dropped.
     */
    public void setEnabled(boolean enable) {
        synchronized (mLock) {
            if (mIsEnabled == enable) {
                return;
            }
            mIsEnabled = enable;

            if (!mIsEnabled) {
                clear();
            }
        }
    }

    // Internal implementation.

    @GuardedBy("mLock")
    boolean isEnabledLocked() {
        return mIsEnabled;
    }

    /** Returns true if global quota is free. */
    @GuardedBy("mLock")
    boolean isQuotaFreeLocked() {
        return mIsQuotaFree;
    }

    /** Returns true if global quota is free or if quota is free for the given userId-package. */
    @GuardedBy("mLock")
    boolean isQuotaFreeLocked(int userId, @NonNull String packageName) {
        return mIsQuotaFree || mFreeQuota.getOrDefault(userId, packageName, Boolean.FALSE);
    }

    /**
     * Returns true only if quota is free for the given userId-package. Global quota is not taken
     * into account.
     */
    @GuardedBy("mLock")
    boolean isIndividualQuotaFreeLocked(int userId, @NonNull String packageName) {
        return mFreeQuota.getOrDefault(userId, packageName, Boolean.FALSE);
    }

    /** The tracker has been disabled. Drop all events and internal tracking data. */
    @GuardedBy("mLock")
    abstract void dropEverythingLocked();

    /** The global free quota status changed. */
    @GuardedBy("mLock")
    abstract void onQuotaFreeChangedLocked(boolean isFree);

    /** The individual free quota status for the userId-package changed. */
    @GuardedBy("mLock")
    abstract void onQuotaFreeChangedLocked(int userId, @NonNull String packageName, boolean isFree);

    /** Get the Handler used by the tracker. This Handler's thread will receive alarm callbacks. */
    @NonNull
    abstract Handler getHandler();

    /** Makes sure to call out to AlarmManager on a separate thread. */
    void scheduleAlarm(@AlarmManager.AlarmType int type, long triggerAtMillis, String tag,
            AlarmManager.OnAlarmListener listener) {
        // We don't know at what level in the lock hierarchy this tracker will be, so make sure to
        // call out to AlarmManager without the lock held. The operation should be fast enough so
        // put it on the FgThread.
        FgThread.getHandler().post(() -> {
            if (mInjector.isAlarmManagerReady()) {
                mAlarmManager.set(type, triggerAtMillis, tag, listener, getHandler());
            } else {
                Slog.w(TAG, "Alarm not scheduled because boot isn't completed");
            }
        });
    }

    /** Makes sure to call out to AlarmManager on a separate thread. */
    void cancelAlarm(AlarmManager.OnAlarmListener listener) {
        // We don't know at what level in the lock hierarchy this tracker will be, so make sure to
        // call out to AlarmManager without the lock held. The operation should be fast enough so
        // put it on the FgThread.
        FgThread.getHandler().post(() -> {
            if (mInjector.isAlarmManagerReady()) {
                mAlarmManager.cancel(listener);
            } else {
                Slog.w(TAG, "Alarm not cancelled because boot isn't completed");
            }
        });
    }

    /** Check the quota status of the specific UPTC. */
    abstract void maybeUpdateQuotaStatus(int userId, @NonNull String packageName,
            @Nullable String tag);

    /** Check the quota status of all UPTCs in case a listener needs to be notified. */
    @GuardedBy("mLock")
    abstract void maybeUpdateAllQuotaStatusLocked();

    /** Schedule a quota check for all apps. */
    void scheduleQuotaCheck() {
        // Using BackgroundThread because of the risk of lock contention.
        BackgroundThread.getHandler().post(() -> {
            synchronized (mLock) {
                if (mQuotaChangeListeners.size() > 0) {
                    maybeUpdateAllQuotaStatusLocked();
                }
            }
        });
    }

    @GuardedBy("mLock")
    abstract void handleRemovedAppLocked(int userId, @NonNull String packageName);

    @GuardedBy("mLock")
    void onAppRemovedLocked(final int userId, @NonNull String packageName) {
        if (packageName == null) {
            Slog.wtf(TAG, "Told app removed but given null package name.");
            return;
        }

        mInQuotaAlarmQueue.removeAlarms(userId, packageName);

        mFreeQuota.delete(userId, packageName);

        handleRemovedAppLocked(userId, packageName);
    }

    @GuardedBy("mLock")
    abstract void handleRemovedUserLocked(int userId);

    @GuardedBy("mLock")
    private void onUserRemovedLocked(int userId) {
        mInQuotaAlarmQueue.removeAlarmsForUserId(userId);
        mFreeQuota.delete(userId);

        handleRemovedUserLocked(userId);
    }

    @GuardedBy("mLock")
    abstract boolean isWithinQuotaLocked(int userId, @NonNull String packageName,
            @Nullable String tag);

    void postQuotaStatusChanged(final int userId, @NonNull final String packageName,
            @Nullable final String tag) {
        BackgroundThread.getHandler().post(() -> {
            final QuotaChangeListener[] listeners;
            synchronized (mLock) {
                // Only notify all listeners if we aren't directing to one listener.
                listeners = mQuotaChangeListeners.toArray(
                        new QuotaChangeListener[mQuotaChangeListeners.size()]);
            }
            for (QuotaChangeListener listener : listeners) {
                listener.onQuotaStateChanged(userId, packageName, tag);
            }
        });
    }

    /**
     * Return the time (in the elapsed realtime timebase) when the UPTC will have quota again. This
     * value is only valid if the UPTC is currently out of quota.
     */
    @GuardedBy("mLock")
    abstract long getInQuotaTimeElapsedLocked(int userId, @NonNull String packageName,
            @Nullable String tag);

    /**
     * Maybe schedule a non-wakeup alarm for the next time this package will have quota to run
     * again. This should only be called if the package is already out of quota.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    void maybeScheduleStartAlarmLocked(final int userId, @NonNull final String packageName,
            @Nullable final String tag) {
        if (mQuotaChangeListeners.size() == 0) {
            // No need to schedule the alarm since we won't do anything when the app gets quota
            // again.
            return;
        }

        final String pkgString = string(userId, packageName, tag);

        if (isWithinQuota(userId, packageName, tag)) {
            // Already in quota. Why was this method called?
            if (DEBUG) {
                Slog.e(TAG, "maybeScheduleStartAlarmLocked called for " + pkgString
                        + " even though it's within quota");
            }
            mInQuotaAlarmQueue.removeAlarmForKey(new Uptc(userId, packageName, tag));
            maybeUpdateQuotaStatus(userId, packageName, tag);
            return;
        }

        mInQuotaAlarmQueue.addAlarm(new Uptc(userId, packageName, tag),
                getInQuotaTimeElapsedLocked(userId, packageName, tag));
    }

    @GuardedBy("mLock")
    void cancelScheduledStartAlarmLocked(final int userId,
            @NonNull final String packageName, @Nullable final String tag) {
        mInQuotaAlarmQueue.removeAlarmForKey(new Uptc(userId, packageName, tag));
    }

    /** Track when UPTCs are expected to come back into quota. */
    private class InQuotaAlarmQueue extends AlarmQueue<Uptc> {
        private InQuotaAlarmQueue(Context context, Looper looper) {
            super(context, looper, ALARM_TAG_QUOTA_CHECK, "In quota", false, 0);
        }

        @Override
        protected boolean isForUser(@NonNull Uptc uptc, int userId) {
            return userId == uptc.userId;
        }

        void removeAlarms(int userId, @NonNull String packageName) {
            removeAlarmsIf((uptc) -> userId == uptc.userId && packageName.equals(uptc.packageName));
        }

        @Override
        protected void processExpiredAlarms(@NonNull ArraySet<Uptc> expired) {
            for (int i = 0; i < expired.size(); ++i) {
                Uptc uptc = expired.valueAt(i);
                getHandler().post(
                        () -> maybeUpdateQuotaStatus(uptc.userId, uptc.packageName, uptc.tag));
            }
        }
    }

    //////////////////////////// DATA DUMP //////////////////////////////

    /** Dump state in text format. */
    public void dump(final IndentingPrintWriter pw) {
        pw.println("QuotaTracker:");
        pw.increaseIndent();

        synchronized (mLock) {
            pw.println("Is enabled: " + mIsEnabled);
            pw.println("Is global quota free: " + mIsQuotaFree);
            pw.println("Current elapsed time: " + mInjector.getElapsedRealtime());
            pw.println();

            pw.println();
            mInQuotaAlarmQueue.dump(pw);

            pw.println();
            pw.println("Per-app free quota:");
            pw.increaseIndent();
            for (int u = 0; u < mFreeQuota.numMaps(); ++u) {
                final int userId = mFreeQuota.keyAt(u);
                for (int p = 0; p < mFreeQuota.numElementsForKey(userId); ++p) {
                    final String pkgName = mFreeQuota.keyAt(u, p);

                    pw.print(string(userId, pkgName, null));
                    pw.print(": ");
                    pw.println(mFreeQuota.get(userId, pkgName));
                }
            }
            pw.decreaseIndent();
        }

        pw.decreaseIndent();
    }

    /**
     * Dump state to proto.
     *
     * @param proto   The ProtoOutputStream to write to.
     * @param fieldId The field ID of the {@link QuotaTrackerProto}.
     */
    public void dump(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        synchronized (mLock) {
            proto.write(QuotaTrackerProto.IS_ENABLED, mIsEnabled);
            proto.write(QuotaTrackerProto.IS_GLOBAL_QUOTA_FREE, mIsQuotaFree);
            proto.write(QuotaTrackerProto.ELAPSED_REALTIME, mInjector.getElapsedRealtime());
        }

        proto.end(token);
    }
}
