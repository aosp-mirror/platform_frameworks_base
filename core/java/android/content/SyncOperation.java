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

package android.content;

import android.accounts.Account;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Value type that represents a sync operation.
 * @hide
 */
public class SyncOperation implements Comparable {
    public final Account account;
    public final int userId;
    public int syncSource;
    public String authority;
    public final boolean allowParallelSyncs;
    public Bundle extras;
    public final String key;
    public long earliestRunTime;
    public boolean expedited;
    public SyncStorageEngine.PendingOperation pendingOperation;
    public Long backoff;
    public long delayUntil;
    public long effectiveRunTime;

    public SyncOperation(Account account, int userId, int source, String authority, Bundle extras,
            long delayInMs, long backoff, long delayUntil, boolean allowParallelSyncs) {
        this.account = account;
        this.userId = userId;
        this.syncSource = source;
        this.authority = authority;
        this.allowParallelSyncs = allowParallelSyncs;
        this.extras = new Bundle(extras);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_UPLOAD);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_MANUAL);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_EXPEDITED);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS);
        this.delayUntil = delayUntil;
        this.backoff = backoff;
        final long now = SystemClock.elapsedRealtime();
        if (delayInMs < 0) {
            this.expedited = true;
            this.earliestRunTime = now;
        } else {
            this.expedited = false;
            this.earliestRunTime = now + delayInMs;
        }
        updateEffectiveRunTime();
        this.key = toKey();
    }

    private void removeFalseExtra(String extraName) {
        if (!extras.getBoolean(extraName, false)) {
            extras.remove(extraName);
        }
    }

    SyncOperation(SyncOperation other) {
        this.account = other.account;
        this.userId = other.userId;
        this.syncSource = other.syncSource;
        this.authority = other.authority;
        this.extras = new Bundle(other.extras);
        this.expedited = other.expedited;
        this.earliestRunTime = SystemClock.elapsedRealtime();
        this.backoff = other.backoff;
        this.delayUntil = other.delayUntil;
        this.allowParallelSyncs = other.allowParallelSyncs;
        this.updateEffectiveRunTime();
        this.key = toKey();
    }

    public String toString() {
        return dump(true);
    }

    public String dump(boolean useOneLine) {
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
                .append(", earliestRunTime ")
                .append(earliestRunTime);
        if (expedited) {
            sb.append(", EXPEDITED");
        }
        if (!useOneLine && !extras.keySet().isEmpty()) {
            sb.append("\n    ");
            extrasToStringBuilder(extras, sb);
        }
        return sb.toString();
    }

    public boolean isInitialization() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false);
    }

    public boolean isExpedited() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
    }

    public boolean ignoreBackoff() {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false);
    }

    private String toKey() {
        StringBuilder sb = new StringBuilder();
        sb.append("authority: ").append(authority);
        sb.append(" account {name=" + account.name + ", user=" + userId + ", type=" + account.type
                + "}");
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

    public void updateEffectiveRunTime() {
        effectiveRunTime = ignoreBackoff()
                ? earliestRunTime
                : Math.max(
                    Math.max(earliestRunTime, delayUntil),
                    backoff);
    }

    public int compareTo(Object o) {
        SyncOperation other = (SyncOperation)o;

        if (expedited != other.expedited) {
            return expedited ? -1 : 1;
        }

        if (effectiveRunTime == other.effectiveRunTime) {
            return 0;
        }

        return effectiveRunTime < other.effectiveRunTime ? -1 : 1;
    }
}
