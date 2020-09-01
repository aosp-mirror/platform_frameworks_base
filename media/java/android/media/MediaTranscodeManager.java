/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.media;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MediaTranscodeManager provides an interface to the system's media transcode service.
 * Transcode requests are put in a queue and processed in order. When a transcode operation is
 * completed the caller is notified via its OnTranscodingFinishedListener. In the meantime the
 * caller may use the returned TranscodingJob object to cancel or check the status of a specific
 * transcode operation.
 * The currently supported media types are video and still images.
 *
 * TODO(lnilsson): Add sample code when API is settled.
 *
 * @hide
 */
public final class MediaTranscodeManager {
    private static final String TAG = "MediaTranscodeManager";

    // Invalid ID passed from native means the request was never enqueued.
    private static final long ID_INVALID = -1;

    // Events passed from native.
    private static final int EVENT_JOB_STARTED = 1;
    private static final int EVENT_JOB_PROGRESSED = 2;
    private static final int EVENT_JOB_FINISHED = 3;

    @IntDef(prefix = { "EVENT_" }, value = {
            EVENT_JOB_STARTED,
            EVENT_JOB_PROGRESSED,
            EVENT_JOB_FINISHED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Event {}

    private static MediaTranscodeManager sMediaTranscodeManager;
    private final ConcurrentMap<Long, TranscodingJob> mPendingTranscodingJobs =
            new ConcurrentHashMap<>();
    private final Context mContext;

    /**
     * Listener that gets notified when a transcoding operation has finished.
     * This listener gets notified regardless of how the operation finished. It is up to the
     * listener implementation to check the result and take appropriate action.
     */
    @FunctionalInterface
    public interface OnTranscodingFinishedListener {
        /**
         * Called when the transcoding operation has finished. The receiver may use the
         * TranscodingJob to check the result, i.e. whether the operation succeeded, was canceled or
         * if an error occurred.
         * @param transcodingJob The TranscodingJob instance for the finished transcoding operation.
         */
        void onTranscodingFinished(@NonNull TranscodingJob transcodingJob);
    }

    /**
     * Class describing a transcode operation to be performed. The caller uses this class to
     * configure a transcoding operation that can then be enqueued using MediaTranscodeManager.
     */
    public static final class TranscodingRequest {
        private Uri mSrcUri;
        private Uri mDstUri;
        private MediaFormat mDstFormat;

        private TranscodingRequest(Builder b) {
            mSrcUri = b.mSrcUri;
            mDstUri = b.mDstUri;
            mDstFormat = b.mDstFormat;
        }

        /** TranscodingRequest builder class. */
        public static class Builder {
            private Uri mSrcUri;
            private Uri mDstUri;
            private MediaFormat mDstFormat;

            /**
             * Specifies the source media file.
             * @param uri Content uri for the source media file.
             * @return The builder instance.
             */
            public Builder setSourceUri(Uri uri) {
                mSrcUri = uri;
                return this;
            }

            /**
             * Specifies the destination media file.
             * @param uri Content uri for the destination media file.
             * @return The builder instance.
             */
            public Builder setDestinationUri(Uri uri) {
                mDstUri = uri;
                return this;
            }

            /**
             * Specifies the media format of the transcoded media file.
             * @param dstFormat MediaFormat containing the desired destination format.
             * @return The builder instance.
             */
            public Builder setDestinationFormat(MediaFormat dstFormat) {
                mDstFormat = dstFormat;
                return this;
            }

            /**
             * Builds a new TranscodingRequest with the configuration set on this builder.
             * @return A new TranscodingRequest.
             */
            public TranscodingRequest build() {
                return new TranscodingRequest(this);
            }
        }
    }

    /**
     * Handle to an enqueued transcoding operation. An instance of this class represents a single
     * enqueued transcoding operation. The caller can use that instance to query the status or
     * progress, and to get the result once the operation has completed.
     */
    public static final class TranscodingJob {
        /** The job is enqueued but not yet running. */
        public static final int STATUS_PENDING = 1;
        /** The job is currently running. */
        public static final int STATUS_RUNNING = 2;
        /** The job is finished. */
        public static final int STATUS_FINISHED = 3;

        @IntDef(prefix = { "STATUS_" }, value = {
                STATUS_PENDING,
                STATUS_RUNNING,
                STATUS_FINISHED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Status {}

        /** The job does not have a result yet. */
        public static final int RESULT_NONE = 1;
        /** The job completed successfully. */
        public static final int RESULT_SUCCESS = 2;
        /** The job encountered an error while running. */
        public static final int RESULT_ERROR = 3;
        /** The job was canceled by the caller. */
        public static final int RESULT_CANCELED = 4;

        @IntDef(prefix = { "RESULT_" }, value = {
                RESULT_NONE,
                RESULT_SUCCESS,
                RESULT_ERROR,
                RESULT_CANCELED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Result {}

        /** Listener that gets notified when the progress changes. */
        @FunctionalInterface
        public interface OnProgressChangedListener {

            /**
             * Called when the progress changes. The progress is between 0 and 1, where 0 means
             * that the job has not yet started and 1 means that it has finished.
             * @param progress The new progress.
             */
            void onProgressChanged(float progress);
        }

        private final Executor mExecutor;
        private final OnTranscodingFinishedListener mListener;
        private final ReentrantLock mStatusChangeLock = new ReentrantLock();
        private Executor mProgressChangedExecutor;
        private OnProgressChangedListener mProgressChangedListener;
        private long mID;
        private float mProgress = 0.0f;
        private @Status int mStatus = STATUS_PENDING;
        private @Result int mResult = RESULT_NONE;

        private TranscodingJob(long id, @NonNull @CallbackExecutor Executor executor,
                @NonNull OnTranscodingFinishedListener listener) {
            mID = id;
            mExecutor = executor;
            mListener = listener;
        }

        /**
         * Set a progress listener.
         * @param listener The progress listener.
         */
        public void setOnProgressChangedListener(@NonNull @CallbackExecutor Executor executor,
                @Nullable OnProgressChangedListener listener) {
            mProgressChangedExecutor = executor;
            mProgressChangedListener = listener;
        }

        /**
         * Cancels the transcoding job and notify the listener. If the job happened to finish before
         * being canceled this call is effectively a no-op and will not update the result in that
         * case.
         */
        public void cancel() {
            setJobFinished(RESULT_CANCELED);
            sMediaTranscodeManager.native_cancelTranscodingRequest(mID);
        }

        /**
         * Gets the progress of the transcoding job. The progress is between 0 and 1, where 0 means
         * that the job has not yet started and 1 means that it is finished.
         * @return The progress.
         */
        public float getProgress() {
            return mProgress;
        }

        /**
         * Gets the status of the transcoding job.
         * @return The status.
         */
        public @Status int getStatus() {
            return mStatus;
        }

        /**
         * Gets the result of the transcoding job.
         * @return The result.
         */
        public @Result int getResult() {
            return mResult;
        }

        private void setJobStarted() {
            mStatus = STATUS_RUNNING;
        }

        private void setJobProgress(float newProgress) {
            mProgress = newProgress;

            // Notify listener.
            OnProgressChangedListener onProgressChangedListener = mProgressChangedListener;
            if (onProgressChangedListener != null) {
                mProgressChangedExecutor.execute(
                        () -> onProgressChangedListener.onProgressChanged(mProgress));
            }
        }

        private void setJobFinished(int result) {
            boolean doNotifyListener = false;

            // Prevent conflicting simultaneous status updates from native (finished) and from the
            // caller (cancel).
            try {
                mStatusChangeLock.lock();
                if (mStatus != STATUS_FINISHED) {
                    mStatus = STATUS_FINISHED;
                    mResult = result;
                    doNotifyListener = true;
                }
            } finally {
                mStatusChangeLock.unlock();
            }

            if (doNotifyListener) {
                mExecutor.execute(() -> mListener.onTranscodingFinished(this));
            }
        }

        private void processJobEvent(@Event int event, int arg) {
            switch (event) {
                case EVENT_JOB_STARTED:
                    setJobStarted();
                    break;
                case EVENT_JOB_PROGRESSED:
                    setJobProgress((float) arg / 100);
                    break;
                case EVENT_JOB_FINISHED:
                    setJobFinished(arg);
                    break;
                default:
                    Log.e(TAG, "Unsupported event: " + event);
                    break;
            }
        }
    }

    // Initializes the native library.
    private static native void native_init();
    // Requests a new job ID from the native service.
    private native long native_requestUniqueJobID();
    // Enqueues a transcoding request to the native service.
    private native boolean native_enqueueTranscodingRequest(
            long id, @NonNull TranscodingRequest transcodingRequest, @NonNull Context context);
    // Cancels an enqueued transcoding request.
    private native void native_cancelTranscodingRequest(long id);

    // Private constructor.
    private MediaTranscodeManager(@NonNull Context context) {
        mContext = context;
    }

    // Events posted from the native service.
    @SuppressWarnings("unused")
    private void postEventFromNative(@Event int event, long id, int arg) {
        Log.d(TAG, String.format("postEventFromNative. Event %d, ID %d, arg %d", event, id, arg));

        TranscodingJob transcodingJob = mPendingTranscodingJobs.get(id);

        // Job IDs are added to the tracking set before the job is enqueued so it should never
        // be null unless the service misbehaves.
        if (transcodingJob == null) {
            Log.e(TAG, "No matching transcode job found for id " + id);
            return;
        }

        transcodingJob.processJobEvent(event, arg);
    }

    /**
     * Gets the MediaTranscodeManager singleton instance.
     * @param context The application context.
     * @return the {@link MediaTranscodeManager} singleton instance.
     */
    public static MediaTranscodeManager getInstance(@NonNull Context context) {
        Preconditions.checkNotNull(context);
        synchronized (MediaTranscodeManager.class) {
            if (sMediaTranscodeManager == null) {
                sMediaTranscodeManager = new MediaTranscodeManager(context.getApplicationContext());
            }
            return sMediaTranscodeManager;
        }
    }

    /**
     * Enqueues a TranscodingRequest for execution.
     * @param transcodingRequest The TranscodingRequest to enqueue.
     * @param listenerExecutor Executor on which the listener is notified.
     * @param listener Listener to get notified when the transcoding job is finished.
     * @return A TranscodingJob for this operation.
     */
    public @Nullable TranscodingJob enqueueTranscodingRequest(
            @NonNull TranscodingRequest transcodingRequest,
            @NonNull @CallbackExecutor Executor listenerExecutor,
            @NonNull OnTranscodingFinishedListener listener) {
        Log.i(TAG, "enqueueTranscodingRequest called.");
        Preconditions.checkNotNull(transcodingRequest);
        Preconditions.checkNotNull(listenerExecutor);
        Preconditions.checkNotNull(listener);

        // Reserve a job ID.
        long jobID = native_requestUniqueJobID();
        if (jobID == ID_INVALID) {
            return null;
        }

        // Add the job to the tracking set.
        TranscodingJob transcodingJob = new TranscodingJob(jobID, listenerExecutor, listener);
        mPendingTranscodingJobs.put(jobID, transcodingJob);

        // Enqueue the request with the native service.
        boolean enqueued = native_enqueueTranscodingRequest(jobID, transcodingRequest, mContext);
        if (!enqueued) {
            mPendingTranscodingJobs.remove(jobID);
            return null;
        }

        return transcodingJob;
    }

    static {
        System.loadLibrary("media_jni");
        native_init();
    }
}
