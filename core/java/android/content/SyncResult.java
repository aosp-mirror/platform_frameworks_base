/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class is used to communicate the results of a sync operation to the SyncManager.
 * Based on the values here the SyncManager will determine the disposition of the
 * sync and whether or not a new sync operation needs to be scheduled in the future.
 *
 */
public final class SyncResult implements Parcelable {
    /**
     * Used to indicate that the SyncAdapter is already performing a sync operation, though
     * not necessarily for the requested account and authority and that it wasn't able to
     * process this request. The SyncManager will reschedule the request to run later.
     */
    public final boolean syncAlreadyInProgress;

    /**
     * Used to indicate that the SyncAdapter determined that it would need to issue
     * too many delete operations to the server in order to satisfy the request
     * (as defined by the SyncAdapter). The SyncManager will record
     * that the sync request failed and will cause a System Notification to be created
     * asking the user what they want to do about this. It will give the user a chance to
     * choose between (1) go ahead even with those deletes, (2) revert the deletes,
     * or (3) take no action. If the user decides (1) or (2) the SyncManager will issue another
     * sync request with either {@link ContentResolver#SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS}
     * or {@link ContentResolver#SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS} set in the extras.
     * It is then up to the SyncAdapter to decide how to honor that request.
     */
    public boolean tooManyDeletions;

    /**
     * Used to indicate that the SyncAdapter experienced a hard error due to trying the same
     * operation too many times (as defined by the SyncAdapter). The SyncManager will record
     * that the sync request failed and it will not reschedule the request.
     */
    public boolean tooManyRetries;

    /**
     * Used to indicate that the SyncAdapter experienced a hard error due to an error it
     * received from interacting with the storage layer. The SyncManager will record that
     * the sync request failed and it will not reschedule the request.
     */
    public boolean databaseError;

    /**
     * If set the SyncManager will request an immediate sync with the same Account and authority
     * (but empty extras Bundle) as was used in the sync request.
     */
    public boolean fullSyncRequested;

    /**
     * This field is ignored by the SyncManager.
     */
    public boolean partialSyncUnavailable;

    /**
     * This field is ignored by the SyncManager.
     */
    public boolean moreRecordsToGet;

    /**
     * Used to indicate to the SyncManager that future sync requests that match the request's
     * Account and authority should be delayed until a time in seconds since Java epoch.
     *
     * <p>For example, if you want to delay the next sync for at least 5 minutes, then:
     * <pre>
     * result.delayUntil = (System.currentTimeMillis() / 1000) + 5 * 60;
     * </pre>
     *
     * <p>By default, when a sync fails, the system retries later with an exponential back-off
     * with the system default initial delay time, which always wins over {@link #delayUntil} --
     * i.e. if the system back-off time is larger than {@link #delayUntil}, {@link #delayUntil}
     * will essentially be ignored.
     */
    public long delayUntil;

    /**
     * Used to hold extras statistics about the sync operation. Some of these indicate that
     * the sync request resulted in a hard or soft error, others are for purely informational
     * purposes.
     */
    public final SyncStats stats;

    /**
     * This instance of a SyncResult is returned by the SyncAdapter in response to a
     * sync request when a sync is already underway. The SyncManager will reschedule the
     * sync request to try again later.
     */
    public static final SyncResult ALREADY_IN_PROGRESS;

    static {
        ALREADY_IN_PROGRESS = new SyncResult(true);
    }

    /**
     * Create a "clean" SyncResult. If this is returned without any changes then the
     * SyncManager will consider the sync to have completed successfully. The various fields
     * can be set by the SyncAdapter in order to give the SyncManager more information as to
     * the disposition of the sync.
     * <p>
     * The errors are classified into two broad categories: hard errors and soft errors.
     * Soft errors are retried with exponential backoff. Hard errors are not retried (except
     * when the hard error is for a {@link ContentResolver#SYNC_EXTRAS_UPLOAD} request,
     * in which the request is retryed without the {@link ContentResolver#SYNC_EXTRAS_UPLOAD}
     * extra set). The SyncManager checks the type of error by calling
     * {@link SyncResult#hasHardError()} and  {@link SyncResult#hasSoftError()}. If both are
     * true then the SyncManager treats it as a hard error, not a soft error.
     */
    public SyncResult() {
        this(false);
    }

    /**
     * Internal helper for creating a clean SyncResult or one that indicated that
     * a sync is already in progress.
     * @param syncAlreadyInProgress if true then set the {@link #syncAlreadyInProgress} flag
     */
    private SyncResult(boolean syncAlreadyInProgress) {
        this.syncAlreadyInProgress = syncAlreadyInProgress;
        this.tooManyDeletions = false;
        this.tooManyRetries = false;
        this.fullSyncRequested = false;
        this.partialSyncUnavailable = false;
        this.moreRecordsToGet = false;
        this.delayUntil = 0;
        this.stats = new SyncStats();
    }

    private SyncResult(Parcel parcel) {
        syncAlreadyInProgress = parcel.readInt() != 0;
        tooManyDeletions = parcel.readInt() != 0;
        tooManyRetries = parcel.readInt() != 0;
        databaseError = parcel.readInt() != 0;
        fullSyncRequested = parcel.readInt() != 0;
        partialSyncUnavailable = parcel.readInt() != 0;
        moreRecordsToGet = parcel.readInt() != 0;
        delayUntil = parcel.readLong();
        stats = new SyncStats(parcel);
    }

    /**
     * Convenience method for determining if the SyncResult indicates that a hard error
     * occurred. See {@link #SyncResult()} for an explanation of what the SyncManager does
     * when it sees a hard error.
     * <p>
     * A hard error is indicated when any of the following is true:
     * <ul>
     * <li> {@link SyncStats#numParseExceptions} > 0
     * <li> {@link SyncStats#numConflictDetectedExceptions} > 0
     * <li> {@link SyncStats#numAuthExceptions} > 0
     * <li> {@link #tooManyDeletions}
     * <li> {@link #tooManyRetries}
     * <li> {@link #databaseError}
     * @return true if a hard error is indicated
     */
    public boolean hasHardError() {
        return stats.numParseExceptions > 0
                || stats.numConflictDetectedExceptions > 0
                || stats.numAuthExceptions > 0
                || tooManyDeletions
                || tooManyRetries
                || databaseError;
    }

    /**
     * Convenience method for determining if the SyncResult indicates that a soft error
     * occurred. See {@link #SyncResult()} for an explanation of what the SyncManager does
     * when it sees a soft error.
     * <p>
     * A soft error is indicated when any of the following is true:
     * <ul>
     * <li> {@link SyncStats#numIoExceptions} > 0
     * <li> {@link #syncAlreadyInProgress}
     * </ul>
     * @return true if a soft error is indicated
     */
    public boolean hasSoftError() {
        return syncAlreadyInProgress || stats.numIoExceptions > 0;
    }

    /**
     * A convenience method for determining of the SyncResult indicates that an error occurred.
     * @return true if either a soft or hard error occurred
     */
    public boolean hasError() {
        return hasSoftError() || hasHardError();
    }

    /**
     * Convenience method for determining if the Sync should be rescheduled after failing for some
     * reason.
     * @return true if the SyncManager should reschedule this sync.
     */
    public boolean madeSomeProgress() {
        return ((stats.numDeletes > 0) && !tooManyDeletions)
                || stats.numInserts > 0
                || stats.numUpdates > 0;
    }

    /**
     * Clears the SyncResult to a clean state. Throws an {@link UnsupportedOperationException}
     * if this is called when {@link #syncAlreadyInProgress} is set.
     */
    public void clear() {
        if (syncAlreadyInProgress) {
            throw new UnsupportedOperationException(
                    "you are not allowed to clear the ALREADY_IN_PROGRESS SyncStats");
        }
        tooManyDeletions = false;
        tooManyRetries = false;
        databaseError = false;
        fullSyncRequested = false;
        partialSyncUnavailable = false;
        moreRecordsToGet = false;
        delayUntil = 0;
        stats.clear();
    }

    public static final @android.annotation.NonNull Creator<SyncResult> CREATOR = new Creator<SyncResult>() {
        public SyncResult createFromParcel(Parcel in) {
            return new SyncResult(in);
        }

        public SyncResult[] newArray(int size) {
            return new SyncResult[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(syncAlreadyInProgress ? 1 : 0);
        parcel.writeInt(tooManyDeletions ? 1 : 0);
        parcel.writeInt(tooManyRetries ? 1 : 0);
        parcel.writeInt(databaseError ? 1 : 0);
        parcel.writeInt(fullSyncRequested ? 1 : 0);
        parcel.writeInt(partialSyncUnavailable ? 1 : 0);
        parcel.writeInt(moreRecordsToGet ? 1 : 0);
        parcel.writeLong(delayUntil);
        stats.writeToParcel(parcel, flags);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SyncResult:");
        if (syncAlreadyInProgress) {
            sb.append(" syncAlreadyInProgress: ").append(syncAlreadyInProgress);
        }
        if (tooManyDeletions) sb.append(" tooManyDeletions: ").append(tooManyDeletions);
        if (tooManyRetries) sb.append(" tooManyRetries: ").append(tooManyRetries);
        if (databaseError) sb.append(" databaseError: ").append(databaseError);
        if (fullSyncRequested) sb.append(" fullSyncRequested: ").append(fullSyncRequested);
        if (partialSyncUnavailable) {
            sb.append(" partialSyncUnavailable: ").append(partialSyncUnavailable);
        }
        if (moreRecordsToGet) sb.append(" moreRecordsToGet: ").append(moreRecordsToGet);
        if (delayUntil > 0) sb.append(" delayUntil: ").append(delayUntil);
        sb.append(stats);
        return sb.toString();
    }

    /**
     * Generates a debugging string indicating the status.
     * The string consist of a sequence of code letter followed by the count.
     * Code letters are f - fullSyncRequested, r - partialSyncUnavailable,
     * X - hardError, e - numParseExceptions, c - numConflictDetectedExceptions,
     * a - numAuthExceptions, D - tooManyDeletions, R - tooManyRetries,
     * b - databaseError, x - softError, l - syncAlreadyInProgress,
     * I - numIoExceptions
     * @return debugging string.
     */
    public String toDebugString() {
        StringBuilder sb = new StringBuilder();

        if (fullSyncRequested) {
            sb.append("f1");
        }
        if (partialSyncUnavailable) {
            sb.append("r1");
        }
        if (hasHardError()) {
            sb.append("X1");
        }
        if (stats.numParseExceptions > 0) {
            sb.append("e").append(stats.numParseExceptions);
        }
        if (stats.numConflictDetectedExceptions > 0) {
            sb.append("c").append(stats.numConflictDetectedExceptions);
        }
        if (stats.numAuthExceptions > 0) {
            sb.append("a").append(stats.numAuthExceptions);
        }
        if (tooManyDeletions) {
            sb.append("D1");
        }
        if (tooManyRetries) {
            sb.append("R1");
        }
        if (databaseError) {
            sb.append("b1");
        }
        if (hasSoftError()) {
            sb.append("x1");
        }
        if (syncAlreadyInProgress) {
            sb.append("l1");
        }
        if (stats.numIoExceptions > 0) {
            sb.append("I").append(stats.numIoExceptions);
        }
        return sb.toString();
    }
}
