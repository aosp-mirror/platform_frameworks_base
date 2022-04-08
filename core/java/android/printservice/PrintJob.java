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

package android.printservice;

import android.annotation.FloatRange;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.os.RemoteException;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.util.Preconditions;

/**
 * This class represents a print job from the perspective of a print
 * service. It provides APIs for observing the print job state and
 * performing operations on the print job.
 * <p>
 * <strong>Note: </strong> All methods of this class must be invoked on
 * the main application thread.
 * </p>
 */
public final class PrintJob {

    private static final String LOG_TAG = "PrintJob";

    private final IPrintServiceClient mPrintServiceClient;

    private final PrintDocument mDocument;

    private PrintJobInfo mCachedInfo;

    /** Context that created the object */
    private final Context mContext;

    PrintJob(@NonNull Context context, @NonNull PrintJobInfo jobInfo,
            @NonNull IPrintServiceClient client) {
        mContext = context;
        mCachedInfo = jobInfo;
        mPrintServiceClient = client;
        mDocument = new PrintDocument(mCachedInfo.getId(), client,
                jobInfo.getDocumentInfo());
    }

    /**
     * Gets the unique print job id.
     *
     * @return The id.
     */
    @MainThread
    public PrintJobId getId() {
        PrintService.throwIfNotCalledOnMainThread();
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
    @MainThread
    public @NonNull PrintJobInfo getInfo() {
        PrintService.throwIfNotCalledOnMainThread();
        if (isInImmutableState()) {
            return mCachedInfo;
        }
        PrintJobInfo info = null;
        try {
            info = mPrintServiceClient.getPrintJobInfo(mCachedInfo.getId());
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Couldn't get info for job: " + mCachedInfo.getId(), re);
        }
        if (info != null) {
            mCachedInfo = info;
        }
        return mCachedInfo;
    }

    /**
     * Gets the printed document.
     *
     * @return The document.
     */
    @MainThread
    public @NonNull PrintDocument getDocument() {
        PrintService.throwIfNotCalledOnMainThread();
        return mDocument;
    }

    /**
     * Gets whether this print job is queued. Such a print job is
     * ready to be printed and can be started or cancelled.
     *
     * @return Whether the print job is queued.
     *
     * @see #start()
     * @see #cancel()
     */
    @MainThread
    public boolean isQueued() {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getState() == PrintJobInfo.STATE_QUEUED;
    }

    /**
     * Gets whether this print job is started. Such a print job is
     * being printed and can be completed or canceled or failed.
     *
     * @return Whether the print job is started.
     *
     * @see #complete()
     * @see #cancel()
     * @see #fail(String)
     */
    @MainThread
    public boolean isStarted() {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getState() == PrintJobInfo.STATE_STARTED;
    }

    /**
     * Gets whether this print job is blocked. Such a print job is halted
     * due to an abnormal condition and can be started or canceled or failed.
     *
     * @return Whether the print job is blocked.
     *
     * @see #start()
     * @see #cancel()
     * @see #fail(String)
     */
    @MainThread
    public boolean isBlocked() {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getState() == PrintJobInfo.STATE_BLOCKED;
    }

    /**
     * Gets whether this print job is completed. Such a print job
     * is successfully printed. This is a final state.
     *
     * @return Whether the print job is completed.
     *
     * @see #complete()
     */
    @MainThread
    public boolean isCompleted() {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getState() == PrintJobInfo.STATE_COMPLETED;
    }

    /**
     * Gets whether this print job is failed. Such a print job is
     * not successfully printed due to an error. This is a final state.
     *
     * @return Whether the print job is failed.
     *
     * @see #fail(String)
     */
    @MainThread
    public boolean isFailed() {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getState() == PrintJobInfo.STATE_FAILED;
    }

    /**
     * Gets whether this print job is cancelled. Such a print job was
     * cancelled as a result of a user request. This is a final state.
     *
     * @return Whether the print job is cancelled.
     *
     * @see #cancel()
     */
    @MainThread
    public boolean isCancelled() {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getState() == PrintJobInfo.STATE_CANCELED;
    }

    /**
     * Starts the print job. You should call this method if {@link
     * #isQueued()} or {@link #isBlocked()} returns true and you started
     * resumed printing.
     * <p>
     * This resets the print status to null. Set the new status by using {@link #setStatus}.
     * </p>
     *
     * @return Whether the job was started.
     *
     * @see #isQueued()
     * @see #isBlocked()
     */
    @MainThread
    public boolean start() {
        PrintService.throwIfNotCalledOnMainThread();
        final int state = getInfo().getState();
        if (state == PrintJobInfo.STATE_QUEUED
                || state == PrintJobInfo.STATE_BLOCKED) {
            return setState(PrintJobInfo.STATE_STARTED, null);
        }
        return false;
    }

    /**
     * Blocks the print job. You should call this method if {@link #isStarted()} returns true and
     * you need to block the print job. For example, the user has to add some paper to continue
     * printing. To resume the print job call {@link #start()}. To change the reason call
     * {@link #setStatus(CharSequence)}.
     *
     * @param reason The human readable, short, and translated reason why the print job is blocked.
     * @return Whether the job was blocked.
     *
     * @see #isStarted()
     * @see #isBlocked()
     */
    @MainThread
    public boolean block(@Nullable String reason) {
        PrintService.throwIfNotCalledOnMainThread();
        PrintJobInfo info = getInfo();
        final int state = info.getState();
        if (state == PrintJobInfo.STATE_STARTED || state == PrintJobInfo.STATE_BLOCKED) {
            return setState(PrintJobInfo.STATE_BLOCKED, reason);
        }
        return false;
    }

    /**
     * Completes the print job. You should call this method if {@link
     * #isStarted()} returns true and you are done printing.
     *
     * @return Whether the job as completed.
     *
     * @see #isStarted()
     */
    @MainThread
    public boolean complete() {
        PrintService.throwIfNotCalledOnMainThread();
        if (isStarted()) {
            return setState(PrintJobInfo.STATE_COMPLETED, null);
        }
        return false;
    }

    /**
     * Fails the print job. You should call this method if {@link
     * #isQueued()} or {@link #isStarted()} or {@link #isBlocked()}
     * returns true you failed while printing.
     *
     * @param error The human readable, short, and translated reason
     * for the failure.
     * @return Whether the job was failed.
     *
     * @see #isQueued()
     * @see #isStarted()
     * @see #isBlocked()
     */
    @MainThread
    public boolean fail(@Nullable String error) {
        PrintService.throwIfNotCalledOnMainThread();
        if (!isInImmutableState()) {
            return setState(PrintJobInfo.STATE_FAILED, error);
        }
        return false;
    }

    /**
     * Cancels the print job. You should call this method if {@link
     * #isQueued()} or {@link #isStarted() or #isBlocked()} returns
     * true and you canceled the print job as a response to a call to
     * {@link PrintService#onRequestCancelPrintJob(PrintJob)}.
     *
     * @return Whether the job is canceled.
     *
     * @see #isStarted()
     * @see #isQueued()
     * @see #isBlocked()
     */
    @MainThread
    public boolean cancel() {
        PrintService.throwIfNotCalledOnMainThread();
        if (!isInImmutableState()) {
            return setState(PrintJobInfo.STATE_CANCELED, null);
        }
        return false;
    }

    /**
     * Sets the progress of this print job as a fraction of 1.
     *
     * @param progress The new progress
     */
    @MainThread
    public void setProgress(@FloatRange(from=0.0, to=1.0) float progress) {
        PrintService.throwIfNotCalledOnMainThread();

        try {
            mPrintServiceClient.setProgress(mCachedInfo.getId(), progress);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting progress for job: " + mCachedInfo.getId(), re);
        }
    }

    /**
     * Sets the status of this print job. This should be a human readable, short, and translated
     * description of the current state of the print job.
     * <p />
     * This overrides any previously set status set via {@link #setStatus(CharSequence)},
     * {@link #setStatus(int)}, {@link #block(String)}, or {@link #fail(String)},
     *
     * @param status The new status. If null the status will be empty.
     */
    @MainThread
    public void setStatus(@Nullable CharSequence status) {
        PrintService.throwIfNotCalledOnMainThread();

        try {
            mPrintServiceClient.setStatus(mCachedInfo.getId(), status);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting status for job: " + mCachedInfo.getId(), re);
        }
    }

    /**
     * Sets the status of this print job as a string resource.
     * <p />
     * This overrides any previously set status set via {@link #setStatus(CharSequence)},
     * {@link #setStatus(int)}, {@link #block(String)}, or {@link #fail(String)},
     *
     * @param statusResId The new status as a String resource. If 0 the status will be empty.
     */
    @MainThread
    public void setStatus(@StringRes int statusResId) {
        PrintService.throwIfNotCalledOnMainThread();

        try {
            mPrintServiceClient.setStatusRes(mCachedInfo.getId(), statusResId,
                    mContext.getPackageName());
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting status for job: " + mCachedInfo.getId(), re);
        }
    }

    /**
     * Sets a tag that is valid in the context of a {@link PrintService}
     * and is not interpreted by the system. For example, a print service
     * may set as a tag the key of the print job returned by a remote
     * print server, if the printing is off handed to a cloud based service.
     *
     * @param tag The tag.
     * @return True if the tag was set, false otherwise.
     */
    @MainThread
    public boolean setTag(@NonNull String tag) {
        PrintService.throwIfNotCalledOnMainThread();
        if (isInImmutableState()) {
            return false;
        }
        try {
            return mPrintServiceClient.setPrintJobTag(mCachedInfo.getId(), tag);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting tag for job: " + mCachedInfo.getId(), re);
        }
        return false;
    }

    /**
     * Gets the print job tag.
     *
     * @return The tag or null.
     *
     * @see #setTag(String)
     */
    @MainThread
    public @Nullable String getTag() {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getTag();
    }

    /**
     * Gets the value of an advanced (printer specific) print option.
     *
     * @param key The option key.
     * @return The option value.
     */
    @MainThread
    public String getAdvancedStringOption(String key) {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getAdvancedStringOption(key);
    }

    /**
     * Gets whether this job has a given advanced (printer specific) print
     * option.
     *
     * @param key The option key.
     * @return Whether the option is present.
     */
    @MainThread
    public boolean hasAdvancedOption(String key) {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().hasAdvancedOption(key);
    }

    /**
     * Gets the value of an advanced (printer specific) print option.
     *
     * @param key The option key.
     * @return The option value.
     */
    @MainThread
    public int getAdvancedIntOption(String key) {
        PrintService.throwIfNotCalledOnMainThread();
        return getInfo().getAdvancedIntOption(key);
    }

    @Override
    public boolean equals(Object obj) {
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
        return (mCachedInfo.getId().equals(other.mCachedInfo.getId()));
    }

    @Override
    public int hashCode() {
        return mCachedInfo.getId().hashCode();
    }

    private boolean isInImmutableState() {
        final int state = mCachedInfo.getState();
        return state == PrintJobInfo.STATE_COMPLETED
                || state == PrintJobInfo.STATE_CANCELED
                || state == PrintJobInfo.STATE_FAILED;
    }

    private boolean setState(int state, @Nullable String error) {
        try {
            if (mPrintServiceClient.setPrintJobState(mCachedInfo.getId(), state, error)) {
                // Best effort - update the state of the cached info since
                // we may not be able to re-fetch it later if the job gets
                // removed from the spooler as a result of the state change.
                mCachedInfo.setState(state);
                mCachedInfo.setStatus(error);
                return true;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting the state of job: " + mCachedInfo.getId(), re);
        }
        return false;
    }
}
