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

import java.io.FileDescriptor;
import java.io.IOException;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PrintJobInfo;
import android.util.Log;

/**
 * This class represents a print job from the perspective of a
 * print service. It provides APIs for observing the print job
 * state and performing operations on the print job.
 */
public final class PrintJob {

    private static final String LOG_TAG = "PrintJob";

    private final int mId;

    private final IPrintServiceClient mPrintServiceClient;

    private PrintJobInfo mCachedInfo;

    PrintJob(PrintJobInfo info, IPrintServiceClient client) {
        if (client == null) {
            throw new IllegalStateException("Print serivice not connected!");
        }
        mCachedInfo = info;
        mId = info.getId();
        mPrintServiceClient = client;
    }

    /**
     * Gets the unique print job id.
     *
     * @return The id.
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets the {@link PrintJobInfo} that describes this job.
     * <p>
     * <strong>Node:</strong>The returned info object is a snapshot of the
     * current print job state. Every call to this method returns a fresh
     * info object that reflects the current print jobs state.
     * </p>
     *
     * @return The print job info.
     */
    public PrintJobInfo getInfo() {
        PrintJobInfo info = null;
        try {
            info = mPrintServiceClient.getPrintJob(mId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Couldn't get info for job: " + mId, re);
        }
        if (info != null) {
            mCachedInfo = info;
        }
        return mCachedInfo;
    }

    /**
     * Gets whether this print job is queued. Such a print job is
     * ready to be printed and can be started.
     *
     * @return Whether the print job is queued.
     *
     * @see #start()
     */
    public boolean isQueued() {
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
     * @see #fail()
     */
    public boolean isStarted() {
        return  getInfo().getState() == PrintJobInfo.STATE_STARTED;
    }

    /**
     * Starts the print job. You should call this method if {@link
     * #isQueued()} returns true and you started printing.
     *
     * @return Whether the job as started.
     *
     * @see #isQueued()
     */
    public boolean start() {
        if (isQueued()) {
            return setState(PrintJobInfo.STATE_STARTED);
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
    public boolean complete() {
        if (isStarted()) {
            return setState(PrintJobInfo.STATE_COMPLETED);
        }
        return false;
    }

    /**
     * Fails the print job. You should call this method if {@link
     * #isStarted()} returns true you filed while printing.
     *
     * @return Whether the job as failed.
     *
     * @see #isStarted()
     */
    public boolean fail(CharSequence error) {
        // TODO: Propagate the error message to the UI.
        if (isStarted()) {
            return setState(PrintJobInfo.STATE_FAILED);
        }
        return false;
    }

    /**
     * Cancels the print job. You should call this method if {@link
     * #isStarted()} returns true and you canceled the print job as a
     * response to a call to {@link PrintService#onRequestCancelPrintJob(
     * PrintJob)}.
     *
     * @return Whether the job as canceled.
     *
     * @see #isStarted()
     */
    public boolean cancel() {
        if (isStarted()) {
            return setState(PrintJobInfo.STATE_CANCELED);
        }
        return false;
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
    public boolean setTag(String tag) {
        try {
            return mPrintServiceClient.setPrintJobTag(mId, tag);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting tag for job:" + mId, re);
        }
        return false;
    }

    /**
     * Gets the data associated with this print job. It is a responsibility of
     * the print service to open a stream to the returned file descriptor
     * and fully read the content.
     *
     * @return A file descriptor for reading the data or <code>null</code>.
     */
    public final FileDescriptor getData() {
        ParcelFileDescriptor source = null;
        ParcelFileDescriptor sink = null;
        try {
            ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
            source = fds[0];
            sink = fds[1];
            mPrintServiceClient.writePrintJobData(sink, mId);
            return source.getFileDescriptor();
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "Error calling getting print job data!", ioe);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error calling getting print job data!", re);
        } finally {
            if (sink != null) {
                try {
                    sink.close();
                } catch (IOException ioe) {
                    /* ignore */
                }
            }
        }
        return null;
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
        return (mId == other.mId);
    }

    @Override
    public int hashCode() {
        return mId;
    }

    private boolean setState(int state) {
        // Best effort - update the state of the cached info since
        // we may not be able to re-fetch it later if the job gets
        // removed from the spooler.
        mCachedInfo.setState(state);
        try {
            return mPrintServiceClient.setPrintJobState(mId, state);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting the state of job:" + mId, re);
        }
        return false;
    }
}
