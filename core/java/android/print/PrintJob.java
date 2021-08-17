/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.print;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Objects;

/**
 * This class represents a print job from the perspective of an
 * application. It contains behavior methods for performing operations
 * on it as well as methods for querying its state. A snapshot of the
 * print job state is represented by the {@link PrintJobInfo} class.
 * The state of a print job may change over time. An application receives
 * instances of this class when creating a print job or querying for
 * its print jobs.
 */
public final class PrintJob {

    private final @NonNull PrintManager mPrintManager;

    private @NonNull PrintJobInfo mCachedInfo;

    PrintJob(@NonNull PrintJobInfo info, @NonNull PrintManager printManager) {
        mCachedInfo = info;
        mPrintManager = printManager;
    }

    /**
     * Gets the unique print job id.
     *
     * @return The id.
     */
    public @Nullable PrintJobId getId() {
        return mCachedInfo.getId();
    }

    /**
     * Gets the {@link PrintJobInfo} that describes this job.
     * <p>
     * <strong>Node:</strong>The returned info object is a snapshot of the
     * current print job state. Every call to this method returns a fresh
     * info object that reflects the current print job state.
     * </p>
     *
     * @return The print job info.
     */
    public @NonNull PrintJobInfo getInfo() {
        if (isInImmutableState()) {
            return mCachedInfo;
        }
        PrintJobInfo info = mPrintManager.getPrintJobInfo(mCachedInfo.getId());
        if (info != null) {
            mCachedInfo = info;
        }
        return mCachedInfo;
    }

    /**
     * Cancels this print job. You can request cancellation of a
     * queued, started, blocked, or failed print job.
     *
     * @see #isQueued()
     * @see #isStarted()
     * @see #isBlocked()
     * @see #isFailed()
     */
    public void cancel() {
        final int state = getInfo().getState();
        if (state == PrintJobInfo.STATE_QUEUED
                || state == PrintJobInfo.STATE_STARTED
                || state == PrintJobInfo.STATE_BLOCKED
                || state == PrintJobInfo.STATE_FAILED) {
            mPrintManager.cancelPrintJob(mCachedInfo.getId());
        }
    }

    /**
     * Restarts this print job. You can request restart of a failed
     * print job.
     *
     * @see #isFailed()
     */
    public void restart() {
        if (isFailed()) {
            mPrintManager.restartPrintJob(mCachedInfo.getId());
        }
    }

    /**
     * Gets whether this print job is queued. Such a print job is
     * ready to be printed. You can request a cancellation via
     * {@link #cancel()}.
     *
     * @return Whether the print job is queued.
     *
     * @see #cancel()
     */
    public boolean isQueued() {
        return getInfo().getState() == PrintJobInfo.STATE_QUEUED;
    }

    /**
     * Gets whether this print job is started. Such a print job is
     * being printed. You can request a cancellation via
     * {@link #cancel()}.
     *
     * @return Whether the print job is started.
     *
     * @see #cancel()
     */
    public boolean isStarted() {
        return getInfo().getState() == PrintJobInfo.STATE_STARTED;
    }

    /**
     * Gets whether this print job is blocked. Such a print job is halted
     * due to an abnormal condition. You can request a cancellation via
     * {@link #cancel()}.
     *
     * @return Whether the print job is blocked.
     *
     * @see #cancel()
     */
    public boolean isBlocked() {
        return getInfo().getState() == PrintJobInfo.STATE_BLOCKED;
    }

    /**
     * Gets whether this print job is completed. Such a print job
     * is successfully printed. You can neither cancel nor restart
     * such a print job.
     *
     * @return Whether the print job is completed.
     */
    public boolean isCompleted() {
        return getInfo().getState() == PrintJobInfo.STATE_COMPLETED;
    }

    /**
     * Gets whether this print job is failed. Such a print job is
     * not successfully printed due to an error. You can request
     * a restart via {@link #restart()} or cancel via {@link #cancel()}.
     *
     * @return Whether the print job is failed.
     *
     * @see #restart()
     * @see #cancel()
     */
    public boolean isFailed() {
        return getInfo().getState() == PrintJobInfo.STATE_FAILED;
    }

    /**
     * Gets whether this print job is cancelled. Such a print job was
     * cancelled as a result of a user request. This is a final state.
     * You cannot restart such a print job.
     *
     * @return Whether the print job is cancelled.
     */
    public boolean isCancelled() {
        return getInfo().getState() == PrintJobInfo.STATE_CANCELED;
    }

    private boolean isInImmutableState() {
        final int state = mCachedInfo.getState();
        return state == PrintJobInfo.STATE_COMPLETED
                || state == PrintJobInfo.STATE_CANCELED;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PrintJob other = (PrintJob) obj;
        return Objects.equals(mCachedInfo.getId(), other.mCachedInfo.getId());
    }

    @Override
    public int hashCode() {
        PrintJobId printJobId = mCachedInfo.getId();

        if (printJobId == null) {
            return 0;
        } else {
            return printJobId.hashCode();
        }
    }
}
