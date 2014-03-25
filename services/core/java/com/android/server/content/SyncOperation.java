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
import android.content.SyncRequest;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Pair;

/**
 * Value type that represents a sync operation.
 * TODO: This is the class to flesh out with all the scheduling data - metered/unmetered,
 * transfer-size, etc.
 * {@hide}
 */
public class SyncOperation implements Comparable {
    public static final int REASON_BACKGROUND_DATA_SETTINGS_CHANGED = -1;
    public static final int REASON_ACCOUNTS_UPDATED = -2;
    public static final int REASON_SERVICE_CHANGED = -3;
    public static final int REASON_PERIODIC = -4;
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

    /** Account info to identify a SyncAdapter registered with the system. */
    public final Account account;
    /** Authority info to identify a SyncAdapter registered with the system. */
    public final String authority;
    /** Service to which this operation will bind to perform the sync. */
    public final ComponentName service;
    public final int userId;
    public final int reason;
    public int syncSource;
    public final boolean allowParallelSyncs;
    public Bundle extras;
    public final String key;
    /** Internal boolean to avoid reading a bundle everytime we want to compare operations. */
    private final boolean expedited;
    public SyncStorageEngine.PendingOperation pendingOperation;
    /** Elapsed real time in millis at which to run this sync. */
    public long latestRunTime;
    /** Set by the SyncManager in order to delay retries. */
    public Long backoff;
    /** Specified by the adapter to delay subsequent sync operations. */
    public long delayUntil;
    /**
     * Elapsed real time in millis when this sync will be run.
     * Depends on max(backoff, latestRunTime, and delayUntil).
     */
    public long effectiveRunTime;
    /** Amount of time before {@link #effectiveRunTime} from which this sync can run. */
    public long flexTime;

    public SyncOperation(Account account, int userId, int reason, int source, String authority,
            Bundle extras, long runTimeFromNow, long flexTime, long backoff,
            long delayUntil, boolean allowParallelSyncs) {
        this.service = null;
        this.account = account;
        this.authority = authority;
        this.userId = userId;
        this.reason = reason;
        this.syncSource = source;
        this.allowParallelSyncs = allowParallelSyncs;
        this.extras = new Bundle(extras);
        cleanBundle(this.extras);
        this.delayUntil = delayUntil;
        this.backoff = backoff;
        final long now = SystemClock.elapsedRealtime();
        // Checks the extras bundle. Must occur after we set the internal bundle.
        if (runTimeFromNow < 0) {
            // Sanity check: Will always be true.
            if (!this.extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)) {
                this.extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            }
            this.expedited = true;
            this.latestRunTime = now;
            this.flexTime = 0;
        } else {
            this.extras.remove(ContentResolver.SYNC_EXTRAS_EXPEDITED);
            this.expedited = false;
            this.latestRunTime = now + runTimeFromNow;
            this.flexTime = flexTime;
        }
        updateEffectiveRunTime();
        this.key = toKey();
    }

    /** Only used to immediately reschedule a sync. */
    SyncOperation(SyncOperation other) {
        this.service = other.service;
        this.account = other.account;
        this.authority = other.authority;
        this.userId = other.userId;
        this.reason = other.reason;
        this.syncSource = other.syncSource;
        this.extras = new Bundle(other.extras);
        this.expedited = other.expedited;
        this.latestRunTime = SystemClock.elapsedRealtime();
        this.flexTime = 0L;
        this.backoff = other.backoff;
        this.allowParallelSyncs = other.allowParallelSyncs;
        this.updateEffectiveRunTime();
        this.key = toKey();
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

        // Remove Config data.
        bundle.remove(ContentResolver.SYNC_EXTRAS_EXPECTED_UPLOAD);
        bundle.remove(ContentResolver.SYNC_EXTRAS_EXPECTED_DOWNLOAD);
    }

    private void removeFalseExtra(Bundle bundle, String extraName) {
        if (!bundle.getBoolean(extraName, false)) {
            bundle.remove(extraName);
        }
    }

    @Override
    public String toString() {
        return dump(null, true);
    }

    public String dump(PackageManager pm, boolean useOneLine) {
        StringBuilder sb = new StringBuilder()
                .append(account.name)
                .append(" u")
                .append(userId).append(" (")
                .append(account.type)
                .append(")")
                .append(", ")
                .append(authority)
                .append(", ")
                .append(SyncStorageEngine.SOURCES[syncSource])
                .append(", latestRunTime ")
                .append(latestRunTime);
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

    public boolean isMeteredDisallowed() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_DISALLOW_METERED, false);
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

    /** Changed in V3. */
    private String toKey() {
        StringBuilder sb = new StringBuilder();
        if (service == null) {
            sb.append("authority: ").append(authority);
            sb.append(" account {name=" + account.name + ", user=" + userId + ", type=" + account.type
                    + "}");
        } else {
            sb.append("service {package=" )
                .append(service.getPackageName())
                .append(" user=")
                .append(userId)
                .append(", class=")
                .append(service.getClassName())
                .append("}");
        }
        sb.append(" extras: ");
        extrasToStringBuilder(extras, sb);
        return sb.toString();
    }

    public static void extrasToStringBuilder(Bundle bundle, StringBuilder sb) {
        sb.append("[");
        for (String key : bundle.keySet()) {
            sb.append(key).append("=").append(bundle.get(key)).append(" ");
        }
        sb.append("]");
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
}
