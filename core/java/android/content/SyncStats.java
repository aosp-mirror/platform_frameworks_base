/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.os.Parcelable;
import android.os.Parcel;

/**
 * Used to record various statistics about the result of a sync operation. The SyncManager
 * gets access to these via a {@link SyncResult} and uses some of them to determine the
 * disposition of the sync. See {@link SyncResult} for further dicussion on how the
 * SyncManager uses these values.
 */
public class SyncStats implements Parcelable {
    /**
     * The SyncAdapter was unable to authenticate the {@link android.accounts.Account}
     * that was specified in the request. The user needs to take some action to resolve
     * before a future request can expect to succeed. This is considered a hard error.
     */
    public long numAuthExceptions;

    /**
     * The SyncAdapter had a problem, most likely with the network connectivity or a timeout
     * while waiting for a network response. The request may succeed if it is tried again
     * later. This is considered a soft error.
     */
    public long numIoExceptions;

    /**
     * The SyncAdapter had a problem with the data it received from the server or the storage
     * later. This problem will likely repeat if the request is tried again. The problem
     * will need to be cleared up by either the server or the storage layer (likely with help
     * from the user). If the SyncAdapter cleans up the data itself then it typically won't
     * increment this value although it may still do so in order to record that it had to
     * perform some cleanup. E.g., if the SyncAdapter received a bad entry from the server
     * when processing a feed of entries, it may choose to drop the entry and thus make
     * progress and still increment this value just so the SyncAdapter can record that an
     * error occurred. This is considered a hard error.
     */
    public long numParseExceptions;

    /**
     * The SyncAdapter detected that there was an unrecoverable version conflict when it
     * attempted to update or delete a version of a resource on the server. This is expected
     * to clear itself automatically once the new state is retrieved from the server,
     * though it may remain until the user intervenes manually, perhaps by clearing the
     * local storage and starting over frmo scratch. This is considered a hard error.
     */
    public long numConflictDetectedExceptions;

    /**
     * Counter for tracking how many inserts were performed by the sync operation, as defined
     * by the SyncAdapter.
     */
    public long numInserts;

    /**
     * Counter for tracking how many updates were performed by the sync operation, as defined
     * by the SyncAdapter.
     */
    public long numUpdates;

    /**
     * Counter for tracking how many deletes were performed by the sync operation, as defined
     * by the SyncAdapter.
     */
    public long numDeletes;

    /**
     * Counter for tracking how many entries were affected by the sync operation, as defined
     * by the SyncAdapter.
     */
    public long numEntries;

    /**
     * Counter for tracking how many entries, either from the server or the local store, were
     * ignored during the sync operation. This could happen if the SyncAdapter detected some
     * unparsable data but decided to skip it and move on rather than failing immediately.
     */
    public long numSkippedEntries;

    public SyncStats() {
        numAuthExceptions = 0;
        numIoExceptions = 0;
        numParseExceptions = 0;
        numConflictDetectedExceptions = 0;
        numInserts = 0;
        numUpdates = 0;
        numDeletes = 0;
        numEntries = 0;
        numSkippedEntries = 0;
    }

    public SyncStats(Parcel in) {
        numAuthExceptions = in.readLong();
        numIoExceptions = in.readLong();
        numParseExceptions = in.readLong();
        numConflictDetectedExceptions = in.readLong();
        numInserts = in.readLong();
        numUpdates = in.readLong();
        numDeletes = in.readLong();
        numEntries = in.readLong();
        numSkippedEntries = in.readLong();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(" stats [");
        if (numAuthExceptions > 0) sb.append(" numAuthExceptions: ").append(numAuthExceptions);
        if (numIoExceptions > 0) sb.append(" numIoExceptions: ").append(numIoExceptions);
        if (numParseExceptions > 0) sb.append(" numParseExceptions: ").append(numParseExceptions);
        if (numConflictDetectedExceptions > 0)
            sb.append(" numConflictDetectedExceptions: ").append(numConflictDetectedExceptions);
        if (numInserts > 0) sb.append(" numInserts: ").append(numInserts);
        if (numUpdates > 0) sb.append(" numUpdates: ").append(numUpdates);
        if (numDeletes > 0) sb.append(" numDeletes: ").append(numDeletes);
        if (numEntries > 0) sb.append(" numEntries: ").append(numEntries);
        if (numSkippedEntries > 0) sb.append(" numSkippedEntries: ").append(numSkippedEntries);
        sb.append("]");
        return sb.toString();
    }

    /**
     * Reset all the counters to 0.
     */
    public void clear() {
        numAuthExceptions = 0;
        numIoExceptions = 0;
        numParseExceptions = 0;
        numConflictDetectedExceptions = 0;
        numInserts = 0;
        numUpdates = 0;
        numDeletes = 0;
        numEntries = 0;
        numSkippedEntries = 0;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(numAuthExceptions);
        dest.writeLong(numIoExceptions);
        dest.writeLong(numParseExceptions);
        dest.writeLong(numConflictDetectedExceptions);
        dest.writeLong(numInserts);
        dest.writeLong(numUpdates);
        dest.writeLong(numDeletes);
        dest.writeLong(numEntries);
        dest.writeLong(numSkippedEntries);
    }

    public static final @android.annotation.NonNull Creator<SyncStats> CREATOR = new Creator<SyncStats>() {
        public SyncStats createFromParcel(Parcel in) {
            return new SyncStats(in);
        }

        public SyncStats[] newArray(int size) {
            return new SyncStats[size];
        }
    };
}
