/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.content;

import android.accounts.Account;
import android.content.pm.PackageManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/**
 * Value type that represents a sync operation.
 * TODO: This is the class to flesh out with all the scheduling data - metered/unmetered,
 * transfer-size, etc.
 * {@hide}
 */
public class SyncOperation implements Comparable {
    public static final String TAG = "SyncManager";

    public static final int REASON_BACKGROUND_DATA_SETTINGS_CHANGED = -1;
    public static final int REASON_ACCOUNTS_UPDATED = -2;
    public static final int REASON_SERVICE_CHANGED = -3;
    public static final int REASON_PERIODIC = -4;
    /** Sync started because it has just been set to isSyncable. */
    public static final int REASON_IS_SYNCABLE = -5;
    /** Sync started because it has just been set to sync automatically. */
    public static final int REASON_SYNC_AUTO = -6;
    /** Sync started because master sync automatically has been set to true. */
    public static final int REASON_MASTER_SYNC_AUTO = -7;
    public static final int REASON_USER_START = -8;

    private static String[] REASON_NAMES = new String[] {
            "DataSettingsChanged",
            "AccountsUpdated",
            "ServiceChanged",
            "Periodic",
            "IsSyncable",
            "AutoSync",
            "MasterSyncAuto",
            "UserStart",
    };

    public static final int SYNC_TARGET_UNKNOWN = 0;
    public static final int SYNC_TARGET_ADAPTER = 1;
    public static final int SYNC_TARGET_SERVICE = 2;

    /** Identifying info for the target for this operation. */
    public final SyncStorageEngine.EndPoint target;
    /** Why this sync was kicked off. {@link #REASON_NAMES} */
    public final int reason;
    /** Where this sync was initiated. */
    public final int syncSource;
    public final boolean allowParallelSyncs;
    public final String key;
    /** Internal boolean to avoid reading a bundle everytime we want to compare operations. */
    private final boolean expedited;
    public Bundle extras;
    /** Bare-bones version of this operation that is persisted across reboots. */
    public SyncStorageEngine.PendingOperation pendingOperation;
    /** Elapsed real time in millis at which to run this sync. */
    public long latestRunTime;
    /** Set by the SyncManager in order to delay retries. */
    public long backoff;
    /** Specified by the adapter to delay subsequent sync operations. */
    public long delayUntil;
    /**
     * Elapsed real time in millis when this sync will be run.
     * Depends on max(backoff, latestRunTime, and delayUntil).
     */
    public long effectiveRunTime;
    /** Amount of time before {@link #effectiveRunTime} from which this sync can run. */
    public long flexTime;

    /** Descriptive string key for this operation */
    public String wakeLockName;

    public SyncOperation(Account account, int userId, int reason, int source, String provider,
            Bundle extras, long runTimeFromNow, long flexTime, long backoff,
            long delayUntil, boolean allowParallelSyncs) {
        this(new SyncStorageEngine.EndPoint(account, provider, userId),
                reason, source, extras, runTimeFromNow, flexTime, backoff, delayUntil,
                allowParallelSyncs);
    }

    public SyncOperation(ComponentName service, int userId, int reason, int source,
            Bundle extras, long runTimeFromNow, long flexTime, long backoff,
            long delayUntil) {
        this(new SyncStorageEngine.EndPoint(service, userId), reason, source, extras,
                runTimeFromNow, flexTime, backoff, delayUntil, true /* allowParallelSyncs */);
    }

    private SyncOperation(SyncStorageEngine.EndPoint info, int reason, int source, Bundle extras,
            long runTimeFromNow, long flexTime, long backoff, long delayUntil,
            boolean allowParallelSyncs) {
        this.target = info;
        this.reason = reason;
        this.syncSource = source;
        this.extras = new Bundle(extras);
        cleanBundle(this.extras);
        this.delayUntil = delayUntil;
        this.backoff = backoff;
        this.allowParallelSyncs = allowParallelSyncs;
        final long now = SystemClock.elapsedRealtime();
        // Set expedited based on runTimeFromNow. The SyncManager specifies whether the op is
        // expedited (Not done solely based on bundle).
        if (runTimeFromNow < 0) {
            this.expedited = true;
            // Sanity check: Will always be true.
            if (!this.extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)) {
                this.extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            }
            this.latestRunTime = now;
            this.flexTime = 0;
        } else {
            this.expedited = false;
            this.extras.remove(ContentResolver.SYNC_EXTRAS_EXPEDITED);
            this.latestRunTime = now + runTimeFromNow;
            this.flexTime = flexTime;
        }
        updateEffectiveRunTime();
        this.key = toKey(info, this.extras);
    }

    /** Used to reschedule a sync at a new point in time. */
    public SyncOperation(SyncOperation other, long newRunTimeFromNow) {
        this(other.target, other.reason, other.syncSource, new Bundle(other.extras),
                newRunTimeFromNow,
                0L /* In back-off so no flex */,
                other.backoff,
                other.delayUntil,
                other.allowParallelSyncs);
    }

    public boolean matchesAuthority(SyncOperation other) {
        return this.target.matchesSpec(other.target);
    }

    /**
     * Make sure the bundle attached to this SyncOperation doesn't have unnecessary
     * flags set.
     * @param bundle to clean.
     */
    private void cleanBundle(Bundle bundle) {
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_UPLOAD);
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_MANUAL);
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS);
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF);
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY);
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS);
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_EXPEDITED);
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS);
        removeFalseExtra(bundle, ContentResolver.SYNC_EXTRAS_DISALLOW_METERED);
    }

    private void removeFalseExtra(Bundle bundle, String extraName) {
        if (!bundle.getBoolean(extraName, false)) {
            bundle.remove(extraName);
        }
    }

    /**
     * Determine whether if this sync operation is running, the provided operation would conflict
     * with it.
     * Parallel syncs allow multiple accounts to be synced at the same time. 
     */
    public boolean isConflict(SyncOperation toRun) {
        final SyncStorageEngine.EndPoint other = toRun.target;
        if (target.target_provider) {
            return target.account.type.equals(other.account.type)
                    && target.provider.equals(other.provider)
                    && target.userId == other.userId
                    && (!allowParallelSyncs
                            || target.account.name.equals(other.account.name));
        } else {
            // Ops that target a service default to allow parallel syncs, which is handled by the
            // service returning SYNC_IN_PROGRESS if they don't.
            return target.service.equals(other.service) && !allowParallelSyncs;
        }
    }

    @Override
    public String toString() {
        return dump(null, true);
    }

    public String dump(PackageManager pm, boolean useOneLine) {
        StringBuilder sb = new StringBuilder();
        if (target.target_provider) {
            sb.append(target.account.name)
                .append(" u")
                .append(target.userId).append(" (")
                .append(target.account.type)
                .append(")")
                .append(", ")
                .append(target.provider)
                .append(", ");
        } else if (target.target_service) {
            sb.append(target.service.getPackageName())
                .append(" u")
                .append(target.userId).append(" (")
                .append(target.service.getClassName()).append(")")
                .append(", ");
        }
        sb.append(SyncStorageEngine.SOURCES[syncSource])
            .append(", currentRunTime ")
            .append(effectiveRunTime);
        if (expedited) {
            sb.append(", EXPEDITED");
        }
        sb.append(", reason: ");
        sb.append(reasonToString(pm, reason));
        if (!useOneLine && !extras.keySet().isEmpty()) {
            sb.append("\n    ");
            extrasToStringBuilder(extras, sb);
        }
        return sb.toString();
    }

    public static String reasonToString(PackageManager pm, int reason) {
        if (reason >= 0) {
            if (pm != null) {
                final String[] packages = pm.getPackagesForUid(reason);
                if (packages != null && packages.length == 1) {
                    return packages[0];
                }
                final String name = pm.getNameForUid(reason);
                if (name != null) {
                    return name;
                }
                return String.valueOf(reason);
            } else {
                return String.valueOf(reason);
            }
        } else {
            final int index = -reason - 1;
            if (index >= REASON_NAMES.length) {
                return String.valueOf(reason);
            } else {
                return REASON_NAMES[index];
            }
        }
    }

    public boolean isInitialization() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false);
    }

    public boolean isExpedited() {
        return expedited;
    }

    public boolean ignoreBackoff() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false);
    }

    public boolean isNotAllowedOnMetered() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_DISALLOW_METERED, false);
    }

    public boolean isManual() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
    }

    public boolean isIgnoreSettings() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, false);
    }

    /** Changed in V3. */
    public static String toKey(SyncStorageEngine.EndPoint info, Bundle extras) {
        StringBuilder sb = new StringBuilder();
        if (info.target_provider) {
            sb.append("provider: ").append(info.provider);
            sb.append(" account {name=" + info.account.name
                    + ", user="
                    + info.userId
                    + ", type="
                    + info.account.type
                    + "}");
        } else if (info.target_service) {
            sb.append("service {package=" )
                .append(info.service.getPackageName())
                .append(" user=")
                .append(info.userId)
                .append(", class=")
                .append(info.service.getClassName())
                .append("}");
        } else {
            Log.v(TAG, "Converting SyncOperaton to key, invalid target: " + info.toString());
            return "";
        }
        sb.append(" extras: ");
        extrasToStringBuilder(extras, sb);
        return sb.toString();
    }

    private static void extrasToStringBuilder(Bundle bundle, StringBuilder sb) {
        sb.append("[");
        for (String key : bundle.keySet()) {
            sb.append(key).append("=").append(bundle.get(key)).append(" ");
        }
        sb.append("]");
    }

    public String wakeLockName() {
        if (wakeLockName != null) {
            return wakeLockName;
        }
        if (target.target_provider) {
            return (wakeLockName = target.provider
                    + "/" + target.account.type
                    + "/" + target.account.name);
        } else if (target.target_service) {
            return (wakeLockName = target.service.getPackageName()
                    + "/" + target.service.getClassName());
        } else {
            Log.wtf(TAG, "Invalid target getting wakelock name for operation - " + key);
            return null;
        }
    }

    /**
     * Update the effective run time of this Operation based on latestRunTime (specified at
     * creation time of sync), delayUntil (specified by SyncAdapter), or backoff (specified by
     * SyncManager on soft failures).
     */
    public void updateEffectiveRunTime() {
        // Regardless of whether we're in backoff or honouring a delayUntil, we still incorporate
        // the flex time provided by the developer.
        effectiveRunTime = ignoreBackoff() ?
                latestRunTime :
                    Math.max(Math.max(latestRunTime, delayUntil), backoff);
    }

    /**
     * SyncOperations are sorted based on their earliest effective run time.
     * This comparator is used to sort the SyncOps at a given time when
     * deciding which to run, so earliest run time is the best criteria.
     */
    @Override
    public int compareTo(Object o) {
        SyncOperation other = (SyncOperation) o;
        if (expedited != other.expedited) {
            return expedited ? -1 : 1;
        }
        long thisIntervalStart = Math.max(effectiveRunTime - flexTime, 0);
        long otherIntervalStart = Math.max(
            other.effectiveRunTime - other.flexTime, 0);
        if (thisIntervalStart < otherIntervalStart) {
            return -1;
        } else if (otherIntervalStart < thisIntervalStart) {
            return 1;
        } else {
            return 0;
        }
    }

    // TODO: Test this to make sure that casting to object doesn't lose the type info for EventLog.
    public Object[] toEventLog(int event) {
        Object[] logArray = new Object[4];
        logArray[1] = event;
        logArray[2] = syncSource;
        if (target.target_provider) {
            logArray[0] = target.provider;
            logArray[3] = target.account.name.hashCode();
        } else if (target.target_service) {
            logArray[0] = target.service.getPackageName();
            logArray[3] = target.service.hashCode();
        } else {
            Log.wtf(TAG, "sync op with invalid target: " + key);
        }
        return logArray;
    }
}
