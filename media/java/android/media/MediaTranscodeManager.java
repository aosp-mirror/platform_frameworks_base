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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 MediaTranscodeManager provides an interface to the system's media transcoding service and can be
 used to transcode media files, e.g. transcoding a video from HEVC to AVC.

 <h3>Transcoding Types</h3>
 <h4>Video Transcoding</h4>
 When transcoding a video file, the video file could be of any of the following types:
 <ul>
 <li> Video file with single video track. </li>
 <li> Video file with multiple video track. </li>
 <li> Video file with multiple video tracks and audio tracks. </li>
 <li> Video file with video/audio tracks and metadata track. Note that metadata track will be passed
 through only if it could be recognized by {@link MediaExtractor}.
 TODO(hkuang): Finalize the metadata track behavior. </li>
 </ul>
 <p class=note>
 Note that currently only support transcoding video file in mp4 format.

 <h3>Transcoding Request</h3>
 <p>
 To transcode a media file, first create a {@link TranscodingRequest} through its builder class
 {@link TranscodingRequest.Builder}. Transcode requests are then enqueue to the manager through
 {@link MediaTranscodeManager#enqueueTranscodingRequest(
         TranscodingRequest, Executor,OnTranscodingFinishedListener)}
 TranscodeRequest are processed based on client process's priority and request priority. When a
 transcode operation is completed the caller is notified via its
 {@link OnTranscodingFinishedListener}.
 In the meantime the caller may use the returned TranscodingJob object to cancel or check the status
 of a specific transcode operation.
 <p>
 Here is an example where <code>Builder</code> is used to specify all parameters

 <pre class=prettyprint>
 TranscodingRequest request =
     new TranscodingRequest.Builder()
         .setSourceUri(srcUri)
         .setDestinationUri(dstUri)
         .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
         .setPriority(REALTIME)
         .setVideoTrackFormat(videoFormat)
         .build();
 }</pre>

 TODO(hkuang): Add architecture diagram showing the transcoding service and api.
 TODO(hkuang): Add sample code when API is settled.
 TODO(hkuang): Clarify whether multiple video tracks is supported or not.
 TODO(hkuang): Clarify whether image/audio transcoding is supported or not.
 TODO(hkuang): Clarify what will happen if there is unrecognized track in the source.
 TODO(hkuang): Clarify whether supports scaling.
 TODO(hkuang): Clarify whether supports framerate conversion.
 @hide
 */
public final class MediaTranscodeManager {
    private static final String TAG = "MediaTranscodeManager";

    /**
     * Default transcoding type.
     * @hide
     */
    public static final int TRANSCODING_TYPE_UNKNOWN = 0;

    /**
     * TRANSCODING_TYPE_VIDEO indicates that client wants to perform transcoding on a video file.
     * <p>Note that currently only support transcoding video file in mp4 format.
     */
    public static final int TRANSCODING_TYPE_VIDEO = 1;

    /**
     * TRANSCODING_TYPE_IMAGE indicates that client wants to perform transcoding on an image file.
     * @hide
     */
    public static final int TRANSCODING_TYPE_IMAGE = 2;

    /** @hide */
    @IntDef(prefix = {"TRANSCODING_TYPE_"}, value = {
            TRANSCODING_TYPE_UNKNOWN,
            TRANSCODING_TYPE_VIDEO,
            TRANSCODING_TYPE_IMAGE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TranscodingType {}

    /**
     * Default value.
     * @hide
     */
    public static final int PRIORITY_UNKNOWN = 0;
    /**
     * PRIORITY_REALTIME indicates that the transcoding request is time-critical and that the client
     * wants the transcoding result as soon as possible.
     * <p> Set PRIORITY_REALTIME only if the transcoding is time-critical as it will involve
     * performance penalty due to resource reallocation to prioritize the jobs with higher priority.
     * TODO(hkuang): Add more description of this when priority is finalized.
     */
    public static final int PRIORITY_REALTIME = 1;

    /**
     * PRIORITY_OFFLINE indicates the transcoding is not time-critical and the client does not need
     * the transcoding result as soon as possible.
     * <p>Jobs with PRIORITY_OFFLINE will be scheduled behind PRIORITY_REALTIME. Always set to
     * PRIORITY_OFFLINE if client does not need the result as soon as possible and could accept
     * delay of the transcoding result.
     * TODO(hkuang): Add more description of this when priority is finalized.
     */
    public static final int PRIORITY_OFFLINE = 2;

    /** @hide */
    @IntDef(prefix = {"PRIORITY_"}, value = {
            PRIORITY_UNKNOWN,
            PRIORITY_REALTIME,
            PRIORITY_OFFLINE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TranscodingPriority {}

    // Invalid ID passed from native means the request was never enqueued.
    private static final long ID_INVALID = -1;

    // Events passed from native.
    private static final int EVENT_JOB_STARTED = 1;
    private static final int EVENT_JOB_PROGRESSED = 2;
    private static final int EVENT_JOB_FINISHED = 3;

    /** @hide */
    @IntDef(prefix = {"EVENT_"}, value = {
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

    /* Private constructor. */
    private MediaTranscodeManager(@NonNull Context context) {
        mContext = context;
    }

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
         *
         * @param transcodingJob The TranscodingJob instance for the finished transcoding operation.
         */
        void onTranscodingFinished(@NonNull TranscodingJob transcodingJob);
    }

    public static final class TranscodingRequest {
        /** Uri of the source media file. */
        private @NonNull Uri mSourceUri;

        /** Uri of the destination media file. */
        private @NonNull Uri mDestinationUri;

        /** Type of the transcoding. */
        private @TranscodingType int mType = TRANSCODING_TYPE_UNKNOWN;

        /** Priority of the transcoding. */
        private @TranscodingPriority int mPriority = PRIORITY_UNKNOWN;

        /**
         * Desired output video format of the destination file.
         * <p> If this is null, source file's video track will be passed through and copied to the
         * destination file.
         * <p>
         */
        private @Nullable MediaFormat mVideoTrackFormat = null;

        /**
         * Desired output audio format of the destination file.
         * <p> If this is null, source file's audio track will be passed through and copied to the
         * destination file.
         * @hide
         */
        private @Nullable MediaFormat mAudioTrackFormat = null;

        /**
         * Desired image format for the destination file.
         * <p> If this is null, source file's image track will be passed through and copied to the
         * destination file.
         * @hide
         */
        private @Nullable MediaFormat mImageFormat = null;

        private TranscodingRequest(Builder b) {
            mSourceUri = b.mSourceUri;
            mDestinationUri = b.mDestinationUri;
            mPriority = b.mPriority;
            mType = b.mType;
            mVideoTrackFormat = b.mVideoTrackFormat;
            mAudioTrackFormat = b.mAudioTrackFormat;
            mImageFormat = b.mImageFormat;
        }

        /** Return the type of the transcoding. */
        @TranscodingType
        int getType() {
            return mType;
        }

        /** Return source uri of the transcoding. */
        @NonNull
        Uri getSourceUri() {
            return mSourceUri;
        }

        /** Return destination uri of the transcoding. */
        @NonNull
        Uri getDestinationUri() {
            return mDestinationUri;
        }

        /** Return priority of the transcoding. */
        @TranscodingPriority
        int getPriority() {
            return mPriority;
        }

        /**
         * Return the video track format of the transcoding.
         * This will be null is the transcoding is not for video transcoding.
         */
        MediaFormat getVideoTrackFormat() {
            return mVideoTrackFormat;
        }

        /**
         * Builder class for {@link TranscodingRequest} objects.
         * Use this class to configure and create a <code>TranscodingRequest</code> instance.
         */
        public static class Builder {
            private @NonNull Uri mSourceUri;
            private @NonNull Uri mDestinationUri;
            private @TranscodingType int mType = TRANSCODING_TYPE_UNKNOWN;
            private @TranscodingPriority int mPriority = PRIORITY_UNKNOWN;

            private @Nullable MediaFormat mVideoTrackFormat;
            private @Nullable MediaFormat mAudioTrackFormat;
            private @Nullable MediaFormat mImageFormat;

            /**
             * Specifies the uri of source media file.
             *
             * @param sourceUri Content uri for the source media file.
             * @return The same builder instance.
             * @throws IllegalArgumentException if Uri is null or empty.
             */
            @NonNull
            public Builder setSourceUri(@NonNull Uri sourceUri) throws IllegalArgumentException {
                if (sourceUri == null || Uri.EMPTY.equals(sourceUri)) {
                    throw new IllegalArgumentException(
                            "You must specify a non-empty source Uri.");
                }
                mSourceUri = sourceUri;
                return this;
            }

            /**
             * Specifies the uri of the destination media file.
             *
             * @param destinationUri Content uri for the destination media file.
             * @return The same builder instance.
             * @throws IllegalArgumentException if Uri is null or empty.
             */
            @NonNull
            public Builder setDestinationUri(@NonNull Uri destinationUri)
                    throws IllegalArgumentException {
                if (destinationUri == null || Uri.EMPTY.equals(destinationUri)) {
                    throw new IllegalArgumentException(
                            "You must specify a non-empty destination Uri.");
                }
                mDestinationUri = destinationUri;
                return this;
            }

            /**
             * Specifies the priority of the transcoding.
             *
             * @param priority Must be one of the {@code PRIORITY_*}
             * @return The same builder instance.
             * @throws IllegalArgumentException if flags is invalid.
             */
            @NonNull
            public Builder setPriority(@TranscodingPriority int priority)
                    throws IllegalArgumentException {
                if (priority != PRIORITY_OFFLINE && priority != PRIORITY_REALTIME) {
                    throw new IllegalArgumentException("Invalid priority: " + priority);
                }
                mPriority = priority;
                return this;
            }

            /**
             * Specifies the type of transcoding.
             * <p> Clients must provide the source and destination that corresponds to the
             * transcoding type.
             *
             * @param type Must be one of the {@code TRANSCODING_TYPE_*}
             * @return The same builder instance.
             * @throws IllegalArgumentException if flags is invalid.
             */
            @NonNull
            public Builder setType(@TranscodingType int type)
                    throws IllegalArgumentException {
                if (type != TRANSCODING_TYPE_VIDEO && type != TRANSCODING_TYPE_IMAGE) {
                    throw new IllegalArgumentException("Invalid transcoding type");
                }
                mType = type;
                return this;
            }

            /**
             * Specifies the desired video track format in the destination media file.
             * <p>Client could only specify the settings that matters to them, e.g. codec format or
             * bitrate. And by default, transcoding will preserve the original video's
             * settings(bitrate, framerate, resolution) if not provided.
             * <p>Note that some settings may silently fail to apply if the device does not
             * support them.
             * TODO(hkuang): Add MediaTranscodeUtil to help client generate transcoding setting.
             * TODO(hkuang): Add MediaTranscodeUtil to check if the setting is valid.
             *
             * @param videoFormat MediaFormat containing the settings that client wants override in
             *                    the original video's video track.
             * @return The same builder instance.
             * @throws IllegalArgumentException if videoFormat is invalid.
             */
            @NonNull
            public Builder setVideoTrackFormat(@NonNull MediaFormat videoFormat)
                    throws IllegalArgumentException {
                if (videoFormat == null) {
                    throw new IllegalArgumentException("videoFormat must not be null");
                }

                // Check if the MediaFormat is for video by looking at the MIME type.
                String mime = videoFormat.containsKey(MediaFormat.KEY_MIME)
                        ? videoFormat.getString(MediaFormat.KEY_MIME) : null;
                if (mime == null || !mime.startsWith("video/")) {
                    throw new IllegalArgumentException("Invalid video format: wrong mime type");
                }

                mVideoTrackFormat = videoFormat;
                return this;
            }

            /**
             * @return a new {@link TranscodingRequest} instance successfully initialized with all
             *     the parameters set on this <code>Builder</code>.
             * @throws UnsupportedOperationException if the parameters set on the
             *         <code>Builder</code> were incompatible, or if they are not supported by the
             *         device.
             */
            @NonNull
            public TranscodingRequest build() throws UnsupportedOperationException {
                if (mSourceUri == null) {
                    throw new UnsupportedOperationException("Source URI must not be null");
                }

                if (mDestinationUri == null) {
                    throw new UnsupportedOperationException("Destination URI must not be null");
                }

                if (mPriority == PRIORITY_UNKNOWN) {
                    throw new UnsupportedOperationException("Must specify transcoding priority");
                }

                // Only support video transcoding now.
                if (mType != TRANSCODING_TYPE_VIDEO) {
                    throw new UnsupportedOperationException("Only supports video transcoding now");
                }

                // Must provide video track format for video transcoding.
                if (mType == TRANSCODING_TYPE_VIDEO && mVideoTrackFormat == null) {
                    throw new UnsupportedOperationException(
                            "Must provide video track format for video transcoding");
                }

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

    /** Initializes the native library. */
    private static native void native_init();

    /** Requests a new job ID from the native service. */
    private native long native_requestUniqueJobID();

    /** Enqueues a transcoding request to the native service. */
    private native boolean native_enqueueTranscodingRequest(
            long id, @NonNull TranscodingRequest transcodingRequest, @NonNull Context context);

    /** Cancels an enqueued transcoding request. */
    private native void native_cancelTranscodingRequest(long id);

    /** Events posted from the native service. */
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
     *
     * @param context The application context.
     * @return the {@link MediaTranscodeManager} singleton instance.
     */
    public static MediaTranscodeManager getInstance(@NonNull Context context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (MediaTranscodeManager.class) {
            if (sMediaTranscodeManager == null) {
                sMediaTranscodeManager = new MediaTranscodeManager(context.getApplicationContext());
            }
            return sMediaTranscodeManager;
        }
    }

    /**
     * Enqueues a TranscodingRequest for execution.
     * <p> Upon successfully accepting the request, MediaTranscodeManager will return a
     * {@link TranscodingJob} to the client. Client should use {@link TranscodingJob} to track the
     * progress and get the result.
     *
     * @param transcodingRequest The TranscodingRequest to enqueue.
     * @param listenerExecutor   Executor on which the listener is notified.
     * @param listener           Listener to get notified when the transcoding job is finished.
     * @return A TranscodingJob for this operation.
     * @throws UnsupportedOperationException if the result could not be fulfilled.
     */
    @NonNull
    public TranscodingJob enqueueTranscodingRequest(
            @NonNull TranscodingRequest transcodingRequest,
            @NonNull @CallbackExecutor Executor listenerExecutor,
            @NonNull OnTranscodingFinishedListener listener) throws UnsupportedOperationException {
        Log.i(TAG, "enqueueTranscodingRequest called.");
        Objects.requireNonNull(transcodingRequest, "transcodingRequest must not be null");
        Objects.requireNonNull(listenerExecutor, "listenerExecutor must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        // Reserve a job ID.
        // TODO(hkuang): Remove this.
        long jobID = native_requestUniqueJobID();
        if (jobID == ID_INVALID) {
            throw new UnsupportedOperationException("Transcoding request could not be fulfilled");
        }

        // Add the job to the tracking set.
        TranscodingJob transcodingJob = new TranscodingJob(jobID, listenerExecutor, listener);
        mPendingTranscodingJobs.put(jobID, transcodingJob);

        // Enqueue the request with the native service.
        boolean enqueued = native_enqueueTranscodingRequest(jobID, transcodingRequest, mContext);
        if (!enqueued) {
            mPendingTranscodingJobs.remove(jobID);
            throw new UnsupportedOperationException("Transcoding request could not be fulfilled");
        }

        return transcodingJob;
    }

    static {
        System.loadLibrary("mediatranscodemanager_jni");
        native_init();
    }
}
