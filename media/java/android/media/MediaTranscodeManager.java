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
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.Os;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 {@link MediaTranscodeManager#enqueueRequest(
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

    private static final String MEDIA_TRANSCODING_SERVICE = "media.transcoding";

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

    private final Context mContext;
    private ContentResolver mContentResolver;
    private final String mPackageName;
    private final int mPid;
    private final int mUid;
    private final ExecutorService mCallbackExecutor = Executors.newSingleThreadExecutor();
    private static MediaTranscodeManager sMediaTranscodeManager;
    private final HashMap<Integer, TranscodingJob> mPendingTranscodingJobs = new HashMap();
    @NonNull private ITranscodingClient mTranscodingClient;

    private void handleTranscodingFinished(int jobId, TranscodingResultParcel result) {
        synchronized (mPendingTranscodingJobs) {
            // Gets the job associated with the jobId and removes it from
            // mPendingTranscodingJobs.
            final TranscodingJob job = mPendingTranscodingJobs.remove(jobId);

            if (job == null) {
                // This should not happen in reality.
                Log.e(TAG, "Job " + jobId + " is not in PendingJobs");
                return;
            }

            // Updates the job status and result.
            job.updateStatusAndResult(TranscodingJob.STATUS_FINISHED,
                    TranscodingJob.RESULT_SUCCESS);

            // Notifies client the job is done.
            if (job.mListener != null && job.mListenerExecutor != null) {
                job.mListenerExecutor.execute(() -> job.mListener.onTranscodingFinished(job));
            }
        }
    }

    private void handleTranscodingFailed(int jobId, int errorCodec) {
        synchronized (mPendingTranscodingJobs) {
            // Gets the job associated with the jobId and removes it from
            // mPendingTranscodingJobs.
            final TranscodingJob job = mPendingTranscodingJobs.remove(jobId);

            if (job == null) {
                // This should not happen in reality.
                Log.e(TAG, "Job " + jobId + " is not in PendingJobs");
                return;
            }

            // Updates the job status and result.
            job.updateStatusAndResult(TranscodingJob.STATUS_FINISHED,
                    TranscodingJob.RESULT_ERROR);

            // Notifies client the job is done.
            if (job.mListener != null && job.mListenerExecutor != null) {
                job.mListenerExecutor.execute(() -> job.mListener.onTranscodingFinished(job));
            }
        }
    }

    // Just forwards all the events to the event handler.
    private ITranscodingClientCallback mTranscodingClientCallback =
            new ITranscodingClientCallback.Stub() {
                // TODO(hkuang): Add more unit test to test difference file open mode.
                @Override
                public ParcelFileDescriptor openFileDescriptor(String fileUri, String mode)
                        throws RemoteException {
                    if (!mode.equals("r") && !mode.equals("w") && !mode.equals("rw")) {
                        Log.e(TAG, "Unsupport mode: " + mode);
                        return null;
                    }

                    Uri uri = Uri.parse(fileUri);
                    try {
                        AssetFileDescriptor afd = mContentResolver.openAssetFileDescriptor(uri,
                                mode);
                        if (afd != null) {
                            return afd.getParcelFileDescriptor();
                        }
                    } catch (FileNotFoundException e) {
                        Log.w(TAG, "Cannot find content uri: " + uri, e);
                    } catch (SecurityException e) {
                        Log.w(TAG, "Cannot open content uri: " + uri, e);
                    } catch (Exception e) {
                        Log.w(TAG, "Unknown content uri: " + uri, e);
                    }
                    return null;
                }

                @Override
                public void onTranscodingStarted(int jobId) throws RemoteException {

                }

                @Override
                public void onTranscodingPaused(int jobId) throws RemoteException {

                }

                @Override
                public void onTranscodingResumed(int jobId) throws RemoteException {

                }

                @Override
                public void onTranscodingFinished(int jobId, TranscodingResultParcel result)
                        throws RemoteException {
                    handleTranscodingFinished(jobId, result);
                }

                @Override
                public void onTranscodingFailed(int jobId, int errorCode) throws RemoteException {
                    handleTranscodingFailed(jobId, errorCode);
                }

                @Override
                public void onAwaitNumberOfJobsChanged(int jobId, int oldAwaitNumber,
                        int newAwaitNumber) throws RemoteException {
                    //TODO(hkuang): Implement this.
                }

                @Override
                public void onProgressUpdate(int jobId, int progress) throws RemoteException {
                    //TODO(hkuang): Implement this.
                }
            };

    /* Private constructor. */
    private MediaTranscodeManager(@NonNull Context context,
            IMediaTranscodingService transcodingService) {
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        mPackageName = mContext.getPackageName();
        mPid = Os.getuid();
        mUid = Os.getpid();

        try {
            // Registers the client with MediaTranscoding service.
            mTranscodingClient = transcodingService.registerClient(
                    mTranscodingClientCallback,
                    mPackageName,
                    mPackageName,
                    IMediaTranscodingService.USE_CALLING_UID,
                    IMediaTranscodingService.USE_CALLING_PID);
        } catch (RemoteException re) {
            Log.e(TAG, "Failed to register new client due to exception " + re);
            throw new UnsupportedOperationException("Failed to register new client");
        }
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

        @VisibleForTesting
        private TranscodingTestConfig mTestConfig = null;

        private TranscodingRequest(Builder b) {
            mSourceUri = b.mSourceUri;
            mDestinationUri = b.mDestinationUri;
            mPriority = b.mPriority;
            mType = b.mType;
            mVideoTrackFormat = b.mVideoTrackFormat;
            mAudioTrackFormat = b.mAudioTrackFormat;
            mImageFormat = b.mImageFormat;
            mTestConfig = b.mTestConfig;
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

        /* Writes the TranscodingRequest to a parcel. */
        private TranscodingRequestParcel writeToParcel() {
            TranscodingRequestParcel parcel = new TranscodingRequestParcel();
            // TODO(hkuang): Implement all the fields here to pass to service.
            parcel.priority = mPriority;
            parcel.transcodingType = mType;
            parcel.sourceFilePath = mSourceUri.toString();
            parcel.destinationFilePath = mDestinationUri.toString();
            parcel.requestedVideoTrackFormat = convertToVideoTrackFormat(mVideoTrackFormat);
            if (mTestConfig != null) {
                parcel.isForTesting = true;
                parcel.testConfig = mTestConfig;
            }
            return parcel;
        }

        /* Converts the MediaFormat to TranscodingVideoTrackFormat. */
        private static TranscodingVideoTrackFormat convertToVideoTrackFormat(MediaFormat format) {
            if (format == null) {
                throw new IllegalArgumentException("Invalid MediaFormat");
            }

            TranscodingVideoTrackFormat trackFormat = new TranscodingVideoTrackFormat();

            if (format.containsKey(MediaFormat.KEY_MIME)) {
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) {
                    trackFormat.codecType = TranscodingVideoCodecType.kAvc;
                } else if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)) {
                    trackFormat.codecType = TranscodingVideoCodecType.kHevc;
                } else {
                    throw new UnsupportedOperationException("Only support transcode to avc/hevc");
                }
            }

            if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                int bitrateBps = format.getInteger(MediaFormat.KEY_BIT_RATE);
                if (bitrateBps <= 0) {
                    throw new IllegalArgumentException("Bitrate must be larger than 0");
                }
                trackFormat.bitrateBps = bitrateBps;
            }

            if (format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(
                    MediaFormat.KEY_HEIGHT)) {
                int width = format.getInteger(MediaFormat.KEY_WIDTH);
                int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("Width and height must be larger than 0");
                }
                // TODO(hkuang): Validate the aspect ratio after adding scaling.
                trackFormat.width = width;
                trackFormat.height = height;
            }

            if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                int profile = format.getInteger(MediaFormat.KEY_PROFILE);
                if (profile <= 0) {
                    throw new IllegalArgumentException("Invalid codec profile");
                }
                // TODO(hkuang): Validate the profile according to codec type.
                trackFormat.profile = profile;
            }

            if (format.containsKey(MediaFormat.KEY_LEVEL)) {
                int level = format.getInteger(MediaFormat.KEY_LEVEL);
                if (level <= 0) {
                    throw new IllegalArgumentException("Invalid codec level");
                }
                // TODO(hkuang): Validate the level according to codec type.
                trackFormat.level = level;
            }

            return trackFormat;
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
            private TranscodingTestConfig mTestConfig;

            /**
             * Specifies the uri of source media file.
             *
             * @param sourceUri Content uri for the source media file.
             * @return The same builder instance.
             * @throws IllegalArgumentException if Uri is null or empty.
             */
            // TODO(hkuang): Add documentation on how the app could generate the correct Uri.
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
             * Sets the delay in processing this request.
             * @param config test config.
             * @return The same builder instance.
             */
            @VisibleForTesting
            public Builder setTestConfig(TranscodingTestConfig config) {
                mTestConfig = config;
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
        public interface OnProgressUpdateListener {
            /**
             * Called when the progress changes. The progress is in percentage between 0 and 1,
             * where 0 means that the job has not yet started and 100 means that it has finished.
             * @param progress The new progress ranging from 0 ~ 100 inclusive.
             */
            void onProgressUpdate(int progress);
        }

        private final ITranscodingClient mJobOwner;
        private final Executor mListenerExecutor;
        private final OnTranscodingFinishedListener mListener;
        private int mJobId = -1;
        @GuardedBy("this")
        private Executor mProgressUpdateExecutor = null;
        @GuardedBy("this")
        private OnProgressUpdateListener mProgressUpdateListener = null;
        @GuardedBy("this")
        private int mProgress = 0;
        @GuardedBy("this")
        private int mProgressUpdateInterval = 0;
        @GuardedBy("this")
        private @Status int mStatus = STATUS_PENDING;
        @GuardedBy("this")
        private @Result int mResult = RESULT_NONE;

        private TranscodingJob(
                @NonNull ITranscodingClient jobOwner,
                @NonNull TranscodingJobParcel parcel,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull OnTranscodingFinishedListener listener) {
            Objects.requireNonNull(jobOwner, "JobOwner must not be null");
            Objects.requireNonNull(parcel, "TranscodingJobParcel must not be null");
            Objects.requireNonNull(executor, "listenerExecutor must not be null");
            Objects.requireNonNull(listener, "listener must not be null");
            mJobOwner = jobOwner;
            mJobId = parcel.jobId;
            mListenerExecutor = executor;
            mListener = listener;
        }

        /**
         * Set a progress listener.
         * @param executor The executor on which listener will be invoked.
         * @param listener The progress listener.
         */
        public void setOnProgressUpdateListener(
                @NonNull @CallbackExecutor Executor executor,
                @Nullable OnProgressUpdateListener listener) {
            setOnProgressUpdateListener(
                    0 /* minProgressUpdateInterval */,
                    executor, listener);
        }

        /**
         * Set a progress listener with specified progress update interval.
         * @param minProgressUpdateInterval The minimum interval between each progress update.
         * @param executor The executor on which listener will be invoked.
         * @param listener The progress listener.
         */
        public synchronized void setOnProgressUpdateListener(
                int minProgressUpdateInterval,
                @NonNull @CallbackExecutor Executor executor,
                @Nullable OnProgressUpdateListener listener) {
            Objects.requireNonNull(executor, "listenerExecutor must not be null");
            Objects.requireNonNull(listener, "listener must not be null");
            mProgressUpdateExecutor = executor;
            mProgressUpdateListener = listener;
        }

        private synchronized void updateStatusAndResult(@Status int jobStatus,
                @Result int jobResult) {
            mStatus = jobStatus;
            mResult = jobResult;
        }

        /**
         * Cancels the transcoding job and notify the listener.
         * If the job happened to finish before being canceled this call is effectively a no-op and
         * will not update the result in that case.
         */
        public synchronized void cancel() {
            // Check if the job is finished already.
            if (mStatus != STATUS_FINISHED) {
                try {
                    mJobOwner.cancelJob(mJobId);
                } catch (RemoteException re) {
                    //TODO(hkuang): Find out what to do if failing to cancel the job.
                    Log.e(TAG, "Failed to cancel the job due to exception:  " + re);
                }
                mStatus = STATUS_FINISHED;
                mResult = RESULT_CANCELED;

                // Notifies client the job is canceled.
                mListenerExecutor.execute(() -> mListener.onTranscodingFinished(this));
            }
        }

        /**
         * Gets the progress of the transcoding job. The progress is between 0 and 1, where 0 means
         * that the job has not yet started and 1 means that it is finished.
         * @return The progress.
         */
        public synchronized int getProgress() {
            return mProgress;
        }

        /**
         * Gets the status of the transcoding job.
         * @return The status.
         */
        public synchronized @Status int getStatus() {
            return mStatus;
        }

        /**
         * Gets jobId of the transcoding job.
         * @return job id.
         */
        public int getJobId() {
            return mJobId;
        }

        /**
         * Gets the result of the transcoding job.
         * @return The result.
         */
        public synchronized @Result int getResult() {
            return mResult;
        }

        private void setJobProgress(int newProgress) {
            synchronized (this) {
                mProgress = newProgress;
            }

            // Notify listener.
            OnProgressUpdateListener onProgressUpdateListener = mProgressUpdateListener;
            if (mProgressUpdateListener != null) {
                mProgressUpdateExecutor.execute(
                        () -> onProgressUpdateListener.onProgressUpdate(mProgress));
            }
        }
    }

    /**
     * Gets the MediaTranscodeManager singleton instance.
     *
     * @param context The application context.
     * @return the {@link MediaTranscodeManager} singleton instance.
     * @throws UnsupportedOperationException if failing to acquire the MediaTranscodeManager.
     */
    public static MediaTranscodeManager getInstance(@NonNull Context context) {
        // Acquires the MediaTranscoding service.
        IMediaTranscodingService service = IMediaTranscodingService.Stub.asInterface(
                ServiceManager.getService(MEDIA_TRANSCODING_SERVICE));

        return getInstance(context, service);
    }

    /** Similar as above, but allow injecting transcodingService for testing. */
    @VisibleForTesting
    public static MediaTranscodeManager getInstance(@NonNull Context context,
            IMediaTranscodingService transcodingService) {
        Objects.requireNonNull(context, "context must not be null");

        synchronized (MediaTranscodeManager.class) {
            if (sMediaTranscodeManager == null) {
                sMediaTranscodeManager = new MediaTranscodeManager(context.getApplicationContext(),
                        transcodingService);
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
     * @throws FileNotFoundException if the source Uri or destination Uri could not be opened.
     * @throws UnsupportedOperationException if the request could not be fulfilled.
     */
    @NonNull
    public TranscodingJob enqueueRequest(
            @NonNull TranscodingRequest transcodingRequest,
            @NonNull @CallbackExecutor Executor listenerExecutor,
            @NonNull OnTranscodingFinishedListener listener)
            throws UnsupportedOperationException, FileNotFoundException {
        Log.i(TAG, "enqueueRequest called.");
        Objects.requireNonNull(transcodingRequest, "transcodingRequest must not be null");
        Objects.requireNonNull(listenerExecutor, "listenerExecutor must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        // Converts the request to TranscodingRequestParcel.
        TranscodingRequestParcel requestParcel = transcodingRequest.writeToParcel();

        // Submits the request to MediaTranscoding service.
        try {
            TranscodingJobParcel jobParcel = new TranscodingJobParcel();
            // Synchronizes the access to mPendingTranscodingJobs to make sure the job Id is
            // inserted in the mPendingTranscodingJobs in the callback handler.
            synchronized (mPendingTranscodingJobs) {
                if (!mTranscodingClient.submitRequest(requestParcel, jobParcel)) {
                    throw new UnsupportedOperationException("Failed to enqueue request");
                }

                // Wraps the TranscodingJobParcel into a TranscodingJob and returns it to client for
                // tracking.
                TranscodingJob job = new TranscodingJob(mTranscodingClient, jobParcel,
                        listenerExecutor,
                        listener);

                // Adds the new job into pending jobs.
                mPendingTranscodingJobs.put(job.getJobId(), job);
                return job;
            }
        } catch (RemoteException re) {
            throw new UnsupportedOperationException(
                    "Failed to submit request to Transcoding service");
        }
    }
}
