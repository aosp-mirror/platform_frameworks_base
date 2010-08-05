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
    public int syncSource;
    public String authority;
    public Bundle extras;
    public final String key;
    public long earliestRunTime;
    public boolean expedited;
    public SyncStorageEngine.PendingOperation pendingOperation;

    public SyncOperation(Account account, int source, String authority, Bundle extras,
            long delayInMs) {
        this.account = account;
        this.syncSource = source;
        this.authority = authority;
        this.extras = new Bundle(extras);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_UPLOAD);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_MANUAL);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_EXPEDITED);
        removeFalseExtra(ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS);
        final long now = SystemClock.elapsedRealtime();
        if (delayInMs < 0) {
            this.expedited = true;
            this.earliestRunTime = now;
        } else {
            this.expedited = false;
            this.earliestRunTime = now + delayInMs;
        }
        this.key = toKey();
    }

    private void removeFalseExtra(String extraName) {
        if (!extras.getBoolean(extraName, false)) {
            extras.remove(extraName);
        }
    }

    SyncOperation(SyncOperation other) {
        this.account = other.account;
        this.syncSource = other.syncSource;
        this.authority = other.authority;
        this.extras = new Bundle(other.extras);
        this.expedited = other.expedited;
        this.earliestRunTime = SystemClock.elapsedRealtime();
        this.key = toKey();
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("authority: ").append(authority);
        sb.append(" account: ").append(account);
        sb.append(" extras: ");
        extrasToStringBuilder(extras, sb, false /* asKey */);
        sb.append(" syncSource: ").append(syncSource);
        sb.append(" when: ").append(earliestRunTime);
        sb.append(" expedited: ").append(expedited);
        return sb.toString();
    }

    private String toKey() {
        StringBuilder sb = new StringBuilder();
        sb.append("authority: ").append(authority);
	sb.append(" account {name=" + account.name + ", type=" + account.type + "}");
        sb.append(" extras: ");
        extrasToStringBuilder(extras, sb, true /* asKey */);
        return sb.toString();
    }

    public static void extrasToStringBuilder(Bundle bundle, StringBuilder sb, boolean asKey) {
        sb.append("[");
        for (String key : bundle.keySet()) {
            // if we are writing this as a key don't consider whether this
            // is an initialization sync or not when computing the key since
            // we set this flag appropriately when dispatching the sync request.
            if (asKey && ContentResolver.SYNC_EXTRAS_INITIALIZE.equals(key)) {
                continue;
            }
            sb.append(key).append("=").append(bundle.get(key)).append(" ");
        }
        sb.append("]");
    }

    public int compareTo(Object o) {
        SyncOperation other = (SyncOperation)o;
        if (earliestRunTime == other.earliestRunTime) {
            return 0;
        }
        return (earliestRunTime < other.earliestRunTime) ? -1 : 1;
    }
}
