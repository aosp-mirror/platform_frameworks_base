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
import android.app.job.JobInfo;
import android.content.pm.PackageManager;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Slog;

/**
 * Value type that represents a sync operation.
 * This holds all information related to a sync operation - both one off and periodic.
 * Data stored in this is used to schedule a job with the JobScheduler.
 * {@hide}
 */
public class SyncOperation {
    public static final String TAG = "SyncManager";

    /**
     * This is used in the {@link #sourcePeriodicId} field if the operation is not initiated by a failed
     * periodic sync.
     */
    public static final int NO_JOB_ID = -1;

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

    /** Identifying info for the target for this operation. */
    public final SyncStorageEngine.EndPoint target;
    public final int owningUid;
    public final String owningPackage;
    /** Why this sync was kicked off. {@link #REASON_NAMES} */
    public final int reason;
    /** Where this sync was initiated. */
    public final int syncSource;
    public final boolean allowParallelSyncs;
    public final Bundle extras;
    public final boolean isPeriodic;
    /** jobId of the periodic SyncOperation that initiated this one */
    public final int sourcePeriodicId;
    /** Operations are considered duplicates if keys are equal */
    public final String key;

    /** Poll frequency of periodic sync in milliseconds */
    public final long periodMillis;
    /** Flex time of periodic sync in milliseconds */
    public final long flexMillis;
    /** Descriptive string key for this operation */
    public String wakeLockName;
    /**
     * Used when duplicate pending syncs are present. The one with the lowest expectedRuntime
     * is kept, others are discarded.
     */
    public long expectedRuntime;

    /** Stores the number of times this sync operation failed and had to be retried. */
    int retries;

    /** jobId of the JobScheduler job corresponding to this sync */
    public int jobId;

    public SyncOperation(Account account, int userId, int owningUid, String owningPackage,
                         int reason, int source, String provider, Bundle extras,
                         boolean allowParallelSyncs) {
        this(new SyncStorageEngine.EndPoint(account, provider, userId), owningUid, owningPackage,
                reason, source, extras, allowParallelSyncs);
    }

    private SyncOperation(SyncStorageEngine.EndPoint info, int owningUid, String owningPackage,
                          int reason, int source, Bundle extras, boolean allowParallelSyncs) {
        this(info, owningUid, owningPackage, reason, source, extras, allowParallelSyncs, false,
                NO_JOB_ID, 0, 0);
    }

    public SyncOperation(SyncOperation op, long periodMillis, long flexMillis) {
        this(op.target, op.owningUid, op.owningPackage, op.reason, op.syncSource,
                new Bundle(op.extras), op.allowParallelSyncs, op.isPeriodic, op.sourcePeriodicId,
                periodMillis, flexMillis);
    }

    public SyncOperation(SyncStorageEngine.EndPoint info, int owningUid, String owningPackage,
                         int reason, int source, Bundle extras, boolean allowParallelSyncs,
                         boolean isPeriodic, int sourcePeriodicId, long periodMillis,
                         long flexMillis) {
        this.target = info;
        this.owningUid = owningUid;
        this.owningPackage = owningPackage;
        this.reason = reason;
        this.syncSource = source;
        this.extras = new Bundle(extras);
        this.allowParallelSyncs = allowParallelSyncs;
        this.isPeriodic = isPeriodic;
        this.sourcePeriodicId = sourcePeriodicId;
        this.periodMillis = periodMillis;
        this.flexMillis = flexMillis;
        this.jobId = NO_JOB_ID;
        this.key = toKey();
    }

    /* Get a one off sync operation instance from a periodic sync. */
    public SyncOperation createOneTimeSyncOperation() {
        if (!isPeriodic) {
            return null;
        }
        SyncOperation op = new SyncOperation(target, owningUid, owningPackage, reason, syncSource,
                new Bundle(extras), allowParallelSyncs, false, jobId /* sourcePeriodicId */,
                periodMillis, flexMillis);
        return op;
    }

    public SyncOperation(SyncOperation other) {
        target = other.target;
        owningUid = other.owningUid;
        owningPackage = other.owningPackage;
        reason = other.reason;
        syncSource = other.syncSource;
        allowParallelSyncs = other.allowParallelSyncs;
        extras = new Bundle(other.extras);
        wakeLockName = other.wakeLockName();
        isPeriodic = other.isPeriodic;
        sourcePeriodicId = other.sourcePeriodicId;
        periodMillis = other.periodMillis;
        flexMillis = other.flexMillis;
        this.key = other.key;
    }

    /**
     * All fields are stored in a corresponding key in the persistable bundle.
     *
     * {@link #extras} is a Bundle and can contain parcelable objects. But only the type Account
     * is allowed {@link ContentResolver#validateSyncExtrasBundle(Bundle)} that can't be stored in
     * a PersistableBundle. For every value of type Account with key 'key', we store a
     * PersistableBundle containing account information at key 'ACCOUNT:key'. The Account object
     * can be reconstructed using this.
     *
     * We put a flag with key 'SyncManagerJob', to identify while reconstructing a sync operation
     * from a bundle whether the bundle actually contains information about a sync.
     * @return A persistable bundle containing all information to re-construct the sync operation.
     */
    PersistableBundle toJobInfoExtras() {
        // This will be passed as extras bundle to a JobScheduler job.
        PersistableBundle jobInfoExtras = new PersistableBundle();

        PersistableBundle syncExtrasBundle = new PersistableBundle();
        for (String key: extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof Account) {
                Account account = (Account) value;
                PersistableBundle accountBundle = new PersistableBundle();
                accountBundle.putString("accountName", account.name);
                accountBundle.putString("accountType", account.type);
                // This is stored in jobInfoExtras so that we don't override a user specified
                // sync extra with the same key.
                jobInfoExtras.putPersistableBundle("ACCOUNT:" + key, accountBundle);
            } else if (value instanceof Long) {
                syncExtrasBundle.putLong(key, (Long) value);
            } else if (value instanceof Integer) {
                syncExtrasBundle.putInt(key, (Integer) value);
            } else if (value instanceof Boolean) {
                syncExtrasBundle.putBoolean(key, (Boolean) value);
            } else if (value instanceof Float) {
                syncExtrasBundle.putDouble(key, (double) (float) value);
            } else if (value instanceof Double) {
                syncExtrasBundle.putDouble(key, (Double) value);
            } else if (value instanceof String) {
                syncExtrasBundle.putString(key, (String) value);
            } else if (value == null) {
                syncExtrasBundle.putString(key, null);
            } else {
                Slog.e(TAG, "Unknown extra type.");
            }
        }
        jobInfoExtras.putPersistableBundle("syncExtras", syncExtrasBundle);

        jobInfoExtras.putBoolean("SyncManagerJob", true);

        jobInfoExtras.putString("provider", target.provider);
        jobInfoExtras.putString("accountName", target.account.name);
        jobInfoExtras.putString("accountType", target.account.type);
        jobInfoExtras.putInt("userId", target.userId);
        jobInfoExtras.putInt("owningUid", owningUid);
        jobInfoExtras.putString("owningPackage", owningPackage);
        jobInfoExtras.putInt("reason", reason);
        jobInfoExtras.putInt("source", syncSource);
        jobInfoExtras.putBoolean("allowParallelSyncs", allowParallelSyncs);
        jobInfoExtras.putInt("jobId", jobId);
        jobInfoExtras.putBoolean("isPeriodic", isPeriodic);
        jobInfoExtras.putInt("sourcePeriodicId", sourcePeriodicId);
        jobInfoExtras.putLong("periodMillis", periodMillis);
        jobInfoExtras.putLong("flexMillis", flexMillis);
        jobInfoExtras.putLong("expectedRuntime", expectedRuntime);
        jobInfoExtras.putInt("retries", retries);
        return jobInfoExtras;
    }

    /**
     * Reconstructs a sync operation from an extras Bundle. Returns null if the bundle doesn't
     * contain a valid sync operation.
     */
    static SyncOperation maybeCreateFromJobExtras(PersistableBundle jobExtras) {
        String accountName, accountType;
        String provider;
        int userId, owningUid;
        String owningPackage;
        int reason, source;
        int initiatedBy;
        Bundle extras;
        boolean allowParallelSyncs, isPeriodic;
        long periodMillis, flexMillis;

        if (!jobExtras.getBoolean("SyncManagerJob", false)) {
            return null;
        }

        accountName = jobExtras.getString("accountName");
        accountType = jobExtras.getString("accountType");
        provider = jobExtras.getString("provider");
        userId = jobExtras.getInt("userId", Integer.MAX_VALUE);
        owningUid = jobExtras.getInt("owningUid");
        owningPackage = jobExtras.getString("owningPackage");
        reason = jobExtras.getInt("reason", Integer.MAX_VALUE);
        source = jobExtras.getInt("source", Integer.MAX_VALUE);
        allowParallelSyncs = jobExtras.getBoolean("allowParallelSyncs", false);
        isPeriodic = jobExtras.getBoolean("isPeriodic", false);
        initiatedBy = jobExtras.getInt("sourcePeriodicId", NO_JOB_ID);
        periodMillis = jobExtras.getLong("periodMillis");
        flexMillis = jobExtras.getLong("flexMillis");
        extras = new Bundle();

        PersistableBundle syncExtras = jobExtras.getPersistableBundle("syncExtras");
        if (syncExtras != null) {
            extras.putAll(syncExtras);
        }

        for (String key: jobExtras.keySet()) {
            if (key!= null && key.startsWith("ACCOUNT:")) {
                String newKey = key.substring(8); // Strip off the 'ACCOUNT:' prefix.
                PersistableBundle accountsBundle = jobExtras.getPersistableBundle(key);
                Account account = new Account(accountsBundle.getString("accountName"),
                        accountsBundle.getString("accountType"));
                extras.putParcelable(newKey, account);
            }
        }

        Account account = new Account(accountName, accountType);
        SyncStorageEngine.EndPoint target =
                new SyncStorageEngine.EndPoint(account, provider, userId);
        SyncOperation op = new SyncOperation(target, owningUid, owningPackage, reason, source,
                extras, allowParallelSyncs, isPeriodic, initiatedBy, periodMillis, flexMillis);
        op.jobId = jobExtras.getInt("jobId");
        op.expectedRuntime = jobExtras.getLong("expectedRuntime");
        op.retries = jobExtras.getInt("retries");
        return op;
    }

    /**
     * Determine whether if this sync operation is running, the provided operation would conflict
     * with it.
     * Parallel syncs allow multiple accounts to be synced at the same time.
     */
    boolean isConflict(SyncOperation toRun) {
        final SyncStorageEngine.EndPoint other = toRun.target;
        return target.account.type.equals(other.account.type)
                && target.provider.equals(other.provider)
                && target.userId == other.userId
                && (!allowParallelSyncs
                || target.account.name.equals(other.account.name));
    }

    boolean isReasonPeriodic() {
        return reason == REASON_PERIODIC;
    }

    boolean matchesPeriodicOperation(SyncOperation other) {
        return target.matchesSpec(other.target)
                && SyncManager.syncExtrasEquals(extras, other.extras, true)
                && periodMillis == other.periodMillis && flexMillis == other.flexMillis;
    }

    boolean isDerivedFromFailedPeriodicSync() {
        return sourcePeriodicId != NO_JOB_ID;
    }

    int findPriority() {
        if (isInitialization()) {
            return JobInfo.PRIORITY_SYNC_INITIALIZATION;
        } else if (isExpedited()) {
            return JobInfo.PRIORITY_SYNC_EXPEDITED;
        }
        return JobInfo.PRIORITY_DEFAULT;
    }

    private String toKey() {
        StringBuilder sb = new StringBuilder();
        sb.append("provider: ").append(target.provider);
        sb.append(" account {name=" + target.account.name
                + ", user="
                + target.userId
                + ", type="
                + target.account.type
                + "}");
        sb.append(" isPeriodic: ").append(isPeriodic);
        sb.append(" period: ").append(periodMillis);
        sb.append(" flex: ").append(flexMillis);
        sb.append(" extras: ");
        extrasToStringBuilder(extras, sb);
        return sb.toString();
    }

    @Override
    public String toString() {
        return dump(null, true);
    }

    String dump(PackageManager pm, boolean useOneLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("JobId: ").append(jobId)
                .append(", ")
                .append(target.account.name)
                .append(" u")
                .append(target.userId).append(" (")
                .append(target.account.type)
                .append(")")
                .append(", ")
                .append(target.provider)
                .append(", ");
        sb.append(SyncStorageEngine.SOURCES[syncSource]);
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)) {
            sb.append(", EXPEDITED");
        }
        sb.append(", reason: ");
        sb.append(reasonToString(pm, reason));
        if (isPeriodic) {
            sb.append(", period: " + periodMillis).append(", flexMillis: " + flexMillis);
        }
        if (!useOneLine) {
            sb.append("\n    ");
            sb.append("owningUid=");
            UserHandle.formatUid(sb, owningUid);
            sb.append(" owningPackage=");
            sb.append(owningPackage);
        }
        if (!useOneLine && !extras.keySet().isEmpty()) {
            sb.append("\n    ");
            extrasToStringBuilder(extras, sb);
        }
        return sb.toString();
    }

    static String reasonToString(PackageManager pm, int reason) {
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

    boolean isInitialization() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false);
    }

    boolean isExpedited() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
    }

    boolean ignoreBackoff() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false);
    }

    boolean isNotAllowedOnMetered() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_DISALLOW_METERED, false);
    }

    boolean isManual() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
    }

    boolean isIgnoreSettings() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, false);
    }

    private static void extrasToStringBuilder(Bundle bundle, StringBuilder sb) {
        sb.append("[");
        for (String key : bundle.keySet()) {
            sb.append(key).append("=").append(bundle.get(key)).append(" ");
        }
        sb.append("]");
    }

    String wakeLockName() {
        if (wakeLockName != null) {
            return wakeLockName;
        }
        return (wakeLockName = target.provider
                + "/" + target.account.type
                + "/" + target.account.name);
    }

    // TODO: Test this to make sure that casting to object doesn't lose the type info for EventLog.
    public Object[] toEventLog(int event) {
        Object[] logArray = new Object[4];
        logArray[1] = event;
        logArray[2] = syncSource;
        logArray[0] = target.provider;
        logArray[3] = target.account.name.hashCode();
        return logArray;
    }
}
