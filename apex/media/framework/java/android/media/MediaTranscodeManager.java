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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.Os;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
         TranscodingRequest, Executor, OnTranscodingFinishedListener)}
 TranscodeRequest are processed based on client process's priority and request priority. When a
 transcode operation is completed the caller is notified via its
 {@link OnTranscodingFinishedListener}.
 In the meantime the caller may use the returned TranscodingSession object to cancel or check the
 status of a specific transcode operation.
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
@SystemApi
public final class MediaTranscodeManager {
    private static final String TAG = "MediaTranscodeManager";

    /** Maximum number of retry to connect to the service. */
    private static final int CONNECT_SERVICE_RETRY_COUNT = 100;

    /** Interval between trying to reconnect to the service. */
    private static final int INTERVAL_CONNECT_SERVICE_RETRY_MS = 40;

    /** Default bpp(bits-per-pixel) to use for calculating default bitrate. */
    private static final float BPP = 0.25f;

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
     * PRIORITY_REALTIME indicates that the transcoding request is time-critical and that the
     * client wants the transcoding result as soon as possible.
     * <p> Set PRIORITY_REALTIME only if the transcoding is time-critical as it will involve
     * performance penalty due to resource reallocation to prioritize the sessions with higher
     * priority.
     * TODO(hkuang): Add more description of this when priority is finalized.
     */
    public static final int PRIORITY_REALTIME = 1;

    /**
     * PRIORITY_OFFLINE indicates the transcoding is not time-critical and the client does not need
     * the transcoding result as soon as possible.
     * <p>Sessions with PRIORITY_OFFLINE will be scheduled behind PRIORITY_REALTIME. Always set to
     * PRIORITY_OFFLINE if client does not need the result as soon as possible and could accept
     * delay of the transcoding result.
     * @hide
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
         * TranscodingSession to check the result, i.e. whether the operation succeeded, was
         * canceled or if an error occurred.
         *
         * @param session The TranscodingSession instance for the finished transcoding operation.
         */
        void onTranscodingFinished(@NonNull TranscodingSession session);
    }

    private final Context mContext;
    private ContentResolver mContentResolver;
    private final String mPackageName;
    private final int mPid;
    private final int mUid;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final HashMap<Integer, TranscodingSession> mPendingTranscodingSessions = new HashMap();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    @NonNull private ITranscodingClient mTranscodingClient = null;
    private static MediaTranscodeManager sMediaTranscodeManager;

    private void handleTranscodingFinished(int sessionId, TranscodingResultParcel result) {
        synchronized (mPendingTranscodingSessions) {
            // Gets the session associated with the sessionId and removes it from
            // mPendingTranscodingSessions.
            final TranscodingSession session = mPendingTranscodingSessions.remove(sessionId);

            if (session == null) {
                // This should not happen in reality.
                Log.e(TAG, "Session " + sessionId + " is not in Pendingsessions");
                return;
            }

            // Updates the session status and result.
            session.updateStatusAndResult(TranscodingSession.STATUS_FINISHED,
                    TranscodingSession.RESULT_SUCCESS);

            // Notifies client the session is done.
            if (session.mListener != null && session.mListenerExecutor != null) {
                session.mListenerExecutor.execute(
                        () -> session.mListener.onTranscodingFinished(session));
            }
        }
    }

    private void handleTranscodingFailed(int sessionId, int errorCode) {
        synchronized (mPendingTranscodingSessions) {
            // Gets the session associated with the sessionId and removes it from
            // mPendingTranscodingSessions.
            final TranscodingSession session = mPendingTranscodingSessions.remove(sessionId);

            if (session == null) {
                // This should not happen in reality.
                Log.e(TAG, "Session " + sessionId + " is not in Pendingsessions");
                return;
            }

            // Updates the session status and result.
            session.updateStatusAndResult(TranscodingSession.STATUS_FINISHED,
                    TranscodingSession.RESULT_ERROR);

            // Notifies client the session failed.
            if (session.mListener != null && session.mListenerExecutor != null) {
                session.mListenerExecutor.execute(
                        () -> session.mListener.onTranscodingFinished(session));
            }
        }
    }

    private void handleTranscodingProgressUpdate(int sessionId, int newProgress) {
        synchronized (mPendingTranscodingSessions) {
            // Gets the session associated with the sessionId.
            final TranscodingSession session = mPendingTranscodingSessions.get(sessionId);

            if (session == null) {
                // This should not happen in reality.
                Log.e(TAG, "Session " + sessionId + " is not in Pendingsessions");
                return;
            }

            // Updates the session progress.
            session.updateProgress(newProgress);

            // Notifies client the progress update.
            if (session.mProgressUpdateExecutor != null
                    && session.mProgressUpdateListener != null) {
                session.mProgressUpdateExecutor.execute(
                        () -> session.mProgressUpdateListener.onProgressUpdate(session,
                                newProgress));
            }
        }
    }

    private static IMediaTranscodingService getService(boolean retry) {
        int retryCount = !retry ? 1 :  CONNECT_SERVICE_RETRY_COUNT;
        Log.i(TAG, "get service with retry " + retryCount);
        for (int count = 1;  count <= retryCount; count++) {
            Log.d(TAG, "Trying to connect to service. Try count: " + count);
            IMediaTranscodingService service = IMediaTranscodingService.Stub.asInterface(
                    MediaFrameworkInitializer
                    .getMediaServiceManager()
                    .getMediaTranscodingServiceRegisterer()
                    .get());
            if (service != null) {
                return service;
            }
            try {
                // Sleep a bit before retry.
                Thread.sleep(INTERVAL_CONNECT_SERVICE_RETRY_MS);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }

        throw new UnsupportedOperationException("Failed to connect to MediaTranscoding service");
    }

    /*
     * Handle client binder died event.
     * Upon receiving a binder died event of the client, we will do the following:
     * 1) For the session that is running, notify the client that the session is failed with
     *    error code,  so client could choose to retry the session or not.
     *    TODO(hkuang): Add a new error code to signal service died error.
     * 2) For the sessions that is still pending or paused, we will resubmit the session
     *    once we successfully reconnect to the service and register a new client.
     * 3) When trying to connect to the service and register a new client. The service may need time
     *    to reboot or never boot up again. So we will retry for a number of times. If we still
     *    could not connect, we will notify client session failure for the pending and paused
     *    sessions.
     */
    private void onClientDied() {
        synchronized (mLock) {
            mTranscodingClient = null;
        }

        // Delegates the session notification and retry to the executor as it may take some time.
        mExecutor.execute(() -> {
            // List to track the sessions that we want to retry.
            List<TranscodingSession> retrySessions = new ArrayList<TranscodingSession>();

            // First notify the client of session failure for all the running sessions.
            synchronized (mPendingTranscodingSessions) {
                for (Map.Entry<Integer, TranscodingSession> entry :
                        mPendingTranscodingSessions.entrySet()) {
                    TranscodingSession session = entry.getValue();

                    if (session.getStatus() == TranscodingSession.STATUS_RUNNING) {
                        session.updateStatusAndResult(TranscodingSession.STATUS_FINISHED,
                                TranscodingSession.RESULT_ERROR);

                        // Remove the session from pending sessions.
                        mPendingTranscodingSessions.remove(entry.getKey());

                        if (session.mListener != null && session.mListenerExecutor != null) {
                            Log.i(TAG, "Notify client session failed");
                            session.mListenerExecutor.execute(
                                    () -> session.mListener.onTranscodingFinished(session));
                        }
                    } else if (session.getStatus() == TranscodingSession.STATUS_PENDING
                            || session.getStatus() == TranscodingSession.STATUS_PAUSED) {
                        // Add the session to retrySessions to handle them later.
                        retrySessions.add(session);
                    }
                }
            }

            // Try to register with the service once it boots up.
            IMediaTranscodingService service = getService(true /*retry*/);
            boolean haveTranscodingClient = false;
            if (service != null) {
                synchronized (mLock) {
                    mTranscodingClient = registerClient(service);
                    if (mTranscodingClient != null) {
                        haveTranscodingClient = true;
                    }
                }
            }

            for (TranscodingSession session : retrySessions) {
                // Notify the session failure if we fails to connect to the service or fail
                // to retry the session.
                if (!haveTranscodingClient) {
                    // TODO(hkuang): Return correct error code to the client.
                    handleTranscodingFailed(session.getSessionId(), 0 /*unused */);
                }

                try {
                    // Do not set hasRetried for retry initiated by MediaTranscodeManager.
                    session.retryInternal(false /*setHasRetried*/);
                } catch (Exception re) {
                    // TODO(hkuang): Return correct error code to the client.
                    handleTranscodingFailed(session.getSessionId(), 0 /*unused */);
                }
            }
        });
    }

    private void updateStatus(int sessionId, int status) {
        synchronized (mPendingTranscodingSessions) {
            final TranscodingSession session = mPendingTranscodingSessions.get(sessionId);

            if (session == null) {
                // This should not happen in reality.
                Log.e(TAG, "Session " + sessionId + " is not in Pendingsessions");
                return;
            }

            // Updates the session status.
            session.updateStatus(status);
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
                public void onTranscodingStarted(int sessionId) throws RemoteException {
                    updateStatus(sessionId, TranscodingSession.STATUS_RUNNING);
                }

                @Override
                public void onTranscodingPaused(int sessionId) throws RemoteException {
                    updateStatus(sessionId, TranscodingSession.STATUS_PAUSED);
                }

                @Override
                public void onTranscodingResumed(int sessionId) throws RemoteException {
                    updateStatus(sessionId, TranscodingSession.STATUS_RUNNING);
                }

                @Override
                public void onTranscodingFinished(int sessionId, TranscodingResultParcel result)
                        throws RemoteException {
                    handleTranscodingFinished(sessionId, result);
                }

                @Override
                public void onTranscodingFailed(int sessionId, int errorCode)
                        throws RemoteException {
                    handleTranscodingFailed(sessionId, errorCode);
                }

                @Override
                public void onAwaitNumberOfSessionsChanged(int sessionId, int oldAwaitNumber,
                        int newAwaitNumber) throws RemoteException {
                    //TODO(hkuang): Implement this.
                }

                @Override
                public void onProgressUpdate(int sessionId, int newProgress)
                        throws RemoteException {
                    handleTranscodingProgressUpdate(sessionId, newProgress);
                }
            };

    private ITranscodingClient registerClient(IMediaTranscodingService service)
            throws UnsupportedOperationException {
        synchronized (mLock) {
            try {
                // Registers the client with MediaTranscoding service.
                mTranscodingClient = service.registerClient(
                        mTranscodingClientCallback,
                        mPackageName,
                        mPackageName);

                if (mTranscodingClient != null) {
                    mTranscodingClient.asBinder().linkToDeath(() -> onClientDied(), /* flags */ 0);
                }
                return mTranscodingClient;
            } catch (RemoteException re) {
                Log.e(TAG, "Failed to register new client due to exception " + re);
                mTranscodingClient = null;
            }
        }
        throw new UnsupportedOperationException("Failed to register new client");
    }

    /**
     * @hide
     */
    public MediaTranscodeManager(@NonNull Context context) {
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        mPackageName = mContext.getPackageName();
        mUid = Os.getuid();
        mPid = Os.getpid();
        IMediaTranscodingService service = getService(false /*retry*/);
        mTranscodingClient = registerClient(service);
    }

    public static final class TranscodingRequest {
        /** Uri of the source media file. */
        private @NonNull Uri mSourceUri;

        /** Uri of the destination media file. */
        private @NonNull Uri mDestinationUri;

        /** FileDescriptor of the source media file. */
        private @Nullable ParcelFileDescriptor mSourceFileDescriptor;

        /** FileDescriptor of the destination media file. */
        private @Nullable ParcelFileDescriptor mDestinationFileDescriptor;

        /**
         *  The UID of the client that the TranscodingRequest is for. Only privileged caller could
         *  set this Uid as only they could do the transcoding on behalf of the client.
         *  -1 means not available.
         */
        private int mClientUid = -1;

        /**
         *  The Pid of the client that the TranscodingRequest is for. Only privileged caller could
         *  set this Uid as only they could do the transcoding on behalf of the client.
         *  -1 means not available.
         */
        private int mClientPid = -1;

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
            mSourceFileDescriptor = b.mSourceFileDescriptor;
            mDestinationUri = b.mDestinationUri;
            mDestinationFileDescriptor = b.mDestinationFileDescriptor;
            mClientUid = b.mClientUid;
            mClientPid = b.mClientPid;
            mPriority = b.mPriority;
            mType = b.mType;
            mVideoTrackFormat = b.mVideoTrackFormat;
            mAudioTrackFormat = b.mAudioTrackFormat;
            mImageFormat = b.mImageFormat;
            mTestConfig = b.mTestConfig;
        }

        /** Return the type of the transcoding. */
        @TranscodingType
        public int getType() {
            return mType;
        }

        /** Return source uri of the transcoding. */
        @NonNull
        public Uri getSourceUri() {
            return mSourceUri;
        }

        /**
         * Return source file descriptor of the transcoding.
         * This will be null if client has not provided it.
         */
        @Nullable
        public ParcelFileDescriptor getSourceFileDescriptor() {
            return mSourceFileDescriptor;
        }

        /** Return the UID of the client that this request is for. -1 means not available. */
        public int getClientUid() {
            return mClientUid;
        }

        /** Return the PID of the client that this request is for. -1 means not available. */
        public int getClientPid() {
            return mClientPid;
        }

        /** Return destination uri of the transcoding. */
        @NonNull
        public Uri getDestinationUri() {
            return mDestinationUri;
        }

        /**
         * Return destination file descriptor of the transcoding.
         * This will be null if client has not provided it.
         */
        @Nullable
        public ParcelFileDescriptor getDestinationFileDescriptor() {
            return mDestinationFileDescriptor;
        }

        /** Return priority of the transcoding. */
        @TranscodingPriority
        public int getPriority() {
            return mPriority;
        }

        /**
         * Return the video track format of the transcoding.
         * This will be null is the transcoding is not for video transcoding.
         */
        @Nullable
        public MediaFormat getVideoTrackFormat() {
            return mVideoTrackFormat;
        }

        /**
         * Return TestConfig of the transcoding.
         * @hide
         */
        @Nullable
        public TranscodingTestConfig getTestConfig() {
            return mTestConfig;
        }

        /* Writes the TranscodingRequest to a parcel. */
        private TranscodingRequestParcel writeToParcel(@NonNull Context context) {
            TranscodingRequestParcel parcel = new TranscodingRequestParcel();
            parcel.priority = mPriority;
            parcel.transcodingType = mType;
            parcel.sourceFilePath = mSourceUri.toString();
            parcel.sourceFd = mSourceFileDescriptor;
            parcel.destinationFilePath = mDestinationUri.toString();
            parcel.destinationFd = mDestinationFileDescriptor;
            parcel.clientUid = mClientUid;
            parcel.clientPid = mClientPid;
            if (mClientUid < 0) {
                parcel.clientPackageName = context.getPackageName();
            } else {
                String packageName = context.getPackageManager().getNameForUid(mClientUid);
                // PackageName is optional as some uid does not have package name. Set to
                // "Unavailable" string in this case.
                if (packageName == null) {
                    Log.w(TAG, "Failed to find package for uid: " + mClientUid);
                    packageName = "Unavailable";
                }
                parcel.clientPackageName = packageName;
            }
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
        public static final class Builder {
            private @NonNull Uri mSourceUri;
            private @NonNull Uri mDestinationUri;
            private @Nullable ParcelFileDescriptor mSourceFileDescriptor = null;
            private @Nullable ParcelFileDescriptor mDestinationFileDescriptor = null;
            private int mClientUid = -1;
            private int mClientPid = -1;
            private @TranscodingType int mType = TRANSCODING_TYPE_UNKNOWN;
            private @TranscodingPriority int mPriority = PRIORITY_UNKNOWN;
            private @Nullable MediaFormat mVideoTrackFormat;
            private @Nullable MediaFormat mAudioTrackFormat;
            private @Nullable MediaFormat mImageFormat;
            private TranscodingTestConfig mTestConfig;

            /**
             * Specifies the uri of source media file.
             *
             * Client must set the source Uri. If client also provides the source fileDescriptor
             * through is provided by {@link #setSourceFileDescriptor(ParcelFileDescriptor)},
             * TranscodingSession will use the fd instead of calling back to the client to open the
             * sourceUri.
             * @param sourceUri Content uri for the source media file.
             * @return The same builder instance.
             * @throws IllegalArgumentException if Uri is null or empty.
             */
            @NonNull
            public Builder setSourceUri(@NonNull Uri sourceUri) {
                if (sourceUri == null || Uri.EMPTY.equals(sourceUri)) {
                    throw new IllegalArgumentException(
                            "You must specify a non-empty source Uri.");
                }
                mSourceUri = sourceUri;
                return this;
            }

            /**
             * Specifies the fileDescriptor opened from the source media file.
             *
             * This call is optional. If the source fileDescriptor is provided, TranscodingSession
             * will use it directly instead of opening the uri from {@link #setSourceUri(Uri)}. It
             * is client's responsibility to make sure the fileDescriptor is opened from the source
             * uri.
             * @param fileDescriptor a {@link ParcelFileDescriptor} opened from source media file.
             * @return The same builder instance.
             * @throws IllegalArgumentException if fileDescriptor is invalid.
             */
            @NonNull
            public Builder setSourceFileDescriptor(@NonNull ParcelFileDescriptor fileDescriptor) {
                if (fileDescriptor == null || fileDescriptor.getFd() < 0) {
                    throw new IllegalArgumentException(
                            "Invalid source descriptor.");
                }
                mSourceFileDescriptor = fileDescriptor;
                return this;
            }

            /**
             * Specifies the uri of the destination media file.
             *
             * Client must set the destination Uri. If client also provides the destination
             * fileDescriptor through {@link #setDestinationFileDescriptor(ParcelFileDescriptor)},
             * TranscodingSession will use the fd instead of calling back to the client to open the
             * destinationUri.
             * @param destinationUri Content uri for the destination media file.
             * @return The same builder instance.
             * @throws IllegalArgumentException if Uri is null or empty.
             */
            @NonNull
            public Builder setDestinationUri(@NonNull Uri destinationUri) {
                if (destinationUri == null || Uri.EMPTY.equals(destinationUri)) {
                    throw new IllegalArgumentException(
                            "You must specify a non-empty destination Uri.");
                }
                mDestinationUri = destinationUri;
                return this;
            }

            /**
             * Specifies the fileDescriptor opened from the destination media file.
             *
             * This call is optional. If the destination fileDescriptor is provided,
             * TranscodingSession will use it directly instead of opening the uri from
             * {@link #setDestinationUri(Uri)} upon transcoding starts. It is client's
             * responsibility to make sure the fileDescriptor is opened from the destination uri.
             * @param fileDescriptor a {@link ParcelFileDescriptor} opened from destination media
             *                       file.
             * @return The same builder instance.
             * @throws IllegalArgumentException if fileDescriptor is invalid.
             */
            @NonNull
            public Builder setDestinationFileDescriptor(
                    @NonNull ParcelFileDescriptor fileDescriptor) {
                if (fileDescriptor == null || fileDescriptor.getFd() < 0) {
                    throw new IllegalArgumentException(
                            "Invalid destination descriptor.");
                }
                mDestinationFileDescriptor = fileDescriptor;
                return this;
            }

            /**
             * Specify the UID of the client that this request is for.
             * @param uid client Uid.
             * @return The same builder instance.
             * @throws IllegalArgumentException if uid is invalid.
             * TODO(hkuang): Check the permission if it is allowed.
             */
            @NonNull
            public Builder setClientUid(int uid) {
                if (uid < 0) {
                    throw new IllegalArgumentException("Invalid Uid");
                }
                mClientUid = uid;
                return this;
            }

            /**
             * Specify the PID of the client that this request is for.
             * @param pid client Pid.
             * @return The same builder instance.
             * @throws IllegalArgumentException if pid is invalid.
             * TODO(hkuang): Check the permission if it is allowed.
             */
            @NonNull
            public Builder setClientPid(int pid) {
                if (pid < 0) {
                    throw new IllegalArgumentException("Invalid pid");
                }
                mClientPid = pid;
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
            public Builder setPriority(@TranscodingPriority int priority) {
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
            public Builder setType(@TranscodingType int type) {
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
            public Builder setVideoTrackFormat(@NonNull MediaFormat videoFormat) {
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
             * @hide
             */
            @VisibleForTesting
            @NonNull
            public Builder setTestConfig(@NonNull TranscodingTestConfig config) {
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
            public TranscodingRequest build() {
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

        /**
         * Helper class for deciding if transcoding is needed, and if so, the track
         * formats to use.
         */
        public static class MediaFormatResolver {
            private static final int BIT_RATE = 20000000;            // 20Mbps

            private MediaFormat mSrcVideoFormatHint;
            private MediaFormat mSrcAudioFormatHint;
            private ApplicationMediaCapabilities mClientCaps;

            /**
             * Sets the abilities of the client consuming the media. Must be called
             * before {@link #shouldTranscode()} or {@link #resolveVideoFormat()}.
             *
             * @param clientCaps An ApplicationMediaCapabilities object containing the client's
             *                   capabilities.
             * @return the same VideoFormatResolver instance.
             */
            @NonNull
            public MediaFormatResolver setClientCapabilities(
                    @NonNull ApplicationMediaCapabilities clientCaps) {
                mClientCaps = clientCaps;
                return this;
            }

            /**
             * Sets the video format hint about the source. Must be called before
             * {@link #shouldTranscode()} or {@link #resolveVideoFormat()}.
             *
             * @param format A MediaFormat object containing information about the source's
             *               video track format that could affect the transcoding decision.
             *               Such information could include video codec types, color spaces,
             *               whether special format info (eg. slow-motion markers) are present,
             *               etc.. If a particular information is not present, it will not be
             *               used to make the decision.
             * @return the same MediaFormatResolver instance.
             */
            @NonNull
            public MediaFormatResolver setSourceVideoFormatHint(@NonNull MediaFormat format) {
                mSrcVideoFormatHint = format;
                return this;
            }

            /**
             * Sets the audio format hint about the source.
             *
             * @param format A MediaFormat object containing information about the source's
             *               audio track format that could affect the transcoding decision.
             * @return the same MediaFormatResolver instance.
             * @hide
             */
            @NonNull
            public MediaFormatResolver setSourceAudioFormatHint(@NonNull MediaFormat format) {
                mSrcAudioFormatHint = format;
                return this;
            }

            /**
             * Returns whether the source content should be transcoded.
             *
             * @return true if the source should be transcoded.
             * @throws UnsupportedOperationException
             *         if {@link #setClientCapabilities(ApplicationMediaCapabilities)}
             *         or {@link #setSourceVideoFormatHint(MediaFormat)} was not called.
             */
            public boolean shouldTranscode() {
                if (mClientCaps == null) {
                    throw new UnsupportedOperationException(
                            "Client caps must be set!");
                }
                // Video src hint must be provided, audio src hint is not used right now.
                if (mSrcVideoFormatHint == null) {
                    throw new UnsupportedOperationException(
                            "Source video format hint must be set!");
                }

                boolean supportHevc = false;
                try {
                    supportHevc = mClientCaps.isVideoMimeTypeSupported(
                            MediaFormat.MIMETYPE_VIDEO_HEVC);
                } catch (ApplicationMediaCapabilities.FormatNotFoundException ex) {
                    // Set to false if application did not specify.
                    supportHevc = false;
                }
                if (!supportHevc && MediaFormat.MIMETYPE_VIDEO_HEVC.equals(
                        mSrcVideoFormatHint.getString(MediaFormat.KEY_MIME))) {
                    return true;
                }
                // TODO: add more checks as needed below.
                return false;
            }

            /**
             * Retrieves the video track format to be used on
             * {@link Builder#setVideoTrackFormat(MediaFormat)} for this configuration.
             *
             * @return the video track format to be used if transcoding should be performed,
             *         and null otherwise.
             * @throws UnsupportedOperationException
             *         if {@link #setClientCapabilities(ApplicationMediaCapabilities)}
             *         or {@link #setSourceVideoFormatHint(MediaFormat)} was not called.
             */
            @Nullable
            public MediaFormat resolveVideoFormat() {
                if (!shouldTranscode()) {
                    return null;
                }

                MediaFormat videoTrackFormat = new MediaFormat(mSrcVideoFormatHint);
                videoTrackFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);

                int width = mSrcVideoFormatHint.getInteger(MediaFormat.KEY_WIDTH);
                int height = mSrcVideoFormatHint.getInteger(MediaFormat.KEY_HEIGHT);
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException(
                            "Source Width and height must be larger than 0");
                }

                float frameRate = 30.0f; // default to 30fps.
                if (mSrcVideoFormatHint.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    frameRate = mSrcVideoFormatHint.getFloat(MediaFormat.KEY_FRAME_RATE);
                    if (frameRate <= 0) {
                        throw new IllegalArgumentException(
                                "frameRate must be larger than 0");
                    }
                }

                int bitrate = getAVCBitrate(width, height, frameRate);
                videoTrackFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                return videoTrackFormat;
            }

            /**
             * Generate a default bitrate with the fixed bpp(bits-per-pixel) 0.25.
             * This maps to:
             * 1080P@30fps -> 16Mbps
             * 1080P@60fps-> 32Mbps
             * 4K@30fps -> 62Mbps
             */
            private static int getDefaultBitrate(int width, int height, float frameRate) {
                return (int) (width * height * frameRate * BPP);
            }

            /**
             * Query the bitrate from CamcorderProfile. If there are two profiles that match the
             * width/height/framerate, we will use the higher one to get better quality.
             * Return default bitrate if could not find any match profile.
             */
            private static int getAVCBitrate(int width, int height, float frameRate) {
                int bitrate = -1;
                int[] cameraIds = {0, 1};

                // Profiles ordered in decreasing order of preference.
                int[] preferQualities = {
                        CamcorderProfile.QUALITY_2160P,
                        CamcorderProfile.QUALITY_1080P,
                        CamcorderProfile.QUALITY_720P,
                        CamcorderProfile.QUALITY_480P,
                        CamcorderProfile.QUALITY_LOW,
                };

                for (int cameraId : cameraIds) {
                    for (int quality : preferQualities) {
                        // Check if camera id has profile for the quality level.
                        if (!CamcorderProfile.hasProfile(cameraId, quality)) {
                            continue;
                        }
                        CamcorderProfile profile = CamcorderProfile.get(cameraId, quality);
                        // Check the width/height/framerate/codec, also consider portrait case.
                        if (((width == profile.videoFrameWidth
                                && height == profile.videoFrameHeight)
                                || (height == profile.videoFrameWidth
                                && width == profile.videoFrameHeight))
                                && (int) frameRate == profile.videoFrameRate
                                && profile.videoCodec == MediaRecorder.VideoEncoder.H264) {
                            if (bitrate < profile.videoBitRate) {
                                bitrate = profile.videoBitRate;
                            }
                            break;
                        }
                    }
                }

                if (bitrate == -1) {
                    Log.w(TAG, "Failed to find CamcorderProfile for w: " + width + "h: " + height
                            + " fps: "
                            + frameRate);
                    bitrate = getDefaultBitrate(width, height, frameRate);
                }
                Log.d(TAG, "Using bitrate " + bitrate + " for " + width + " " + height + " "
                        + frameRate);
                return bitrate;
            }

            /**
             * Retrieves the audio track format to be used for transcoding.
             *
             * @return the audio track format to be used if transcoding should be performed, and
             *         null otherwise.
             * @throws UnsupportedOperationException
             *         if {@link #setClientCapabilities(ApplicationMediaCapabilities)}
             *         or {@link #setSourceVideoFormatHint(MediaFormat)} was not called.
             * @hide
             */
            @Nullable
            public MediaFormat resolveAudioFormat() {
                if (!shouldTranscode()) {
                    return null;
                }
                // Audio transcoding is not supported yet, always return null.
                return null;
            }
        }
    }

    /**
     * Handle to an enqueued transcoding operation. An instance of this class represents a single
     * enqueued transcoding operation. The caller can use that instance to query the status or
     * progress, and to get the result once the operation has completed.
     */
    public static final class TranscodingSession {
        /** The session is enqueued but not yet running. */
        public static final int STATUS_PENDING = 1;
        /** The session is currently running. */
        public static final int STATUS_RUNNING = 2;
        /** The session is finished. */
        public static final int STATUS_FINISHED = 3;
        /** The session is paused. */
        public static final int STATUS_PAUSED = 4;

        /** @hide */
        @IntDef(prefix = { "STATUS_" }, value = {
                STATUS_PENDING,
                STATUS_RUNNING,
                STATUS_FINISHED,
                STATUS_PAUSED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Status {}

        /** The session does not have a result yet. */
        public static final int RESULT_NONE = 1;
        /** The session completed successfully. */
        public static final int RESULT_SUCCESS = 2;
        /** The session encountered an error while running. */
        public static final int RESULT_ERROR = 3;
        /** The session was canceled by the caller. */
        public static final int RESULT_CANCELED = 4;

        /** @hide */
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
             * where 0 means the session has not yet started and 100 means that it has finished.
             *
             * @param session      The session associated with the progress.
             * @param progress The new progress ranging from 0 ~ 100 inclusive.
             */
            void onProgressUpdate(@NonNull TranscodingSession session,
                    @IntRange(from = 0, to = 100) int progress);
        }

        private final MediaTranscodeManager mManager;
        private Executor mListenerExecutor;
        private OnTranscodingFinishedListener mListener;
        private int mSessionId = -1;
        // Lock for internal state.
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private Executor mProgressUpdateExecutor = null;
        @GuardedBy("mLock")
        private OnProgressUpdateListener mProgressUpdateListener = null;
        @GuardedBy("mLock")
        private int mProgress = 0;
        @GuardedBy("mLock")
        private int mProgressUpdateInterval = 0;
        @GuardedBy("mLock")
        private @Status int mStatus = STATUS_PENDING;
        @GuardedBy("mLock")
        private @Result int mResult = RESULT_NONE;
        @GuardedBy("mLock")
        private boolean mHasRetried = false;
        // The original request that associated with this session.
        private final TranscodingRequest mRequest;

        private TranscodingSession(
                @NonNull MediaTranscodeManager manager,
                @NonNull TranscodingRequest request,
                @NonNull TranscodingSessionParcel parcel,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull OnTranscodingFinishedListener listener) {
            Objects.requireNonNull(manager, "manager must not be null");
            Objects.requireNonNull(parcel, "parcel must not be null");
            Objects.requireNonNull(executor, "listenerExecutor must not be null");
            Objects.requireNonNull(listener, "listener must not be null");
            mManager = manager;
            mSessionId = parcel.sessionId;
            mListenerExecutor = executor;
            mListener = listener;
            mRequest = request;
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
        public void setOnProgressUpdateListener(
                int minProgressUpdateInterval,
                @NonNull @CallbackExecutor Executor executor,
                @Nullable OnProgressUpdateListener listener) {
            synchronized (mLock) {
                Objects.requireNonNull(executor, "listenerExecutor must not be null");
                Objects.requireNonNull(listener, "listener must not be null");
                mProgressUpdateExecutor = executor;
                mProgressUpdateListener = listener;
            }
        }

        private void updateStatusAndResult(@Status int sessionStatus,
                @Result int sessionResult) {
            synchronized (mLock) {
                mStatus = sessionStatus;
                mResult = sessionResult;
            }
        }

        /**
         * Resubmit the transcoding session to the service.
         * Note that only the session that fails or gets cancelled could be retried and each session
         * could be retried only once. After that, Client need to enqueue a new request if they want
         * to try again.
         *
         * @throws MediaTranscodingException.ServiceNotAvailableException if the service
         *         is temporarily unavailable due to internal service rebooting. Client could retry
         *         again after receiving this exception.
         * @throws UnsupportedOperationException if the retry could not be fulfilled.
         * @hide
         */
        public void retry() throws MediaTranscodingException.ServiceNotAvailableException {
            retryInternal(true /*setHasRetried*/);
        }

        // TODO(hkuang): Add more test for it.
        private void retryInternal(boolean setHasRetried)
                throws MediaTranscodingException.ServiceNotAvailableException {
            synchronized (mLock) {
                if (mStatus == STATUS_PENDING || mStatus == STATUS_RUNNING) {
                    throw new UnsupportedOperationException(
                            "Failed to retry as session is in processing");
                }

                if (mHasRetried) {
                    throw new UnsupportedOperationException("Session has been retried already");
                }

                // Get the client interface.
                ITranscodingClient client = mManager.getTranscodingClient();
                if (client == null) {
                    throw new MediaTranscodingException.ServiceNotAvailableException(
                            "Service rebooting. Try again later");
                }

                synchronized (mManager.mPendingTranscodingSessions) {
                    try {
                        // Submits the request to MediaTranscoding service.
                        TranscodingSessionParcel sessionParcel = new TranscodingSessionParcel();
                        if (!client.submitRequest(mRequest.writeToParcel(mManager.mContext),
                                                  sessionParcel)) {
                            mHasRetried = true;
                            throw new UnsupportedOperationException("Failed to enqueue request");
                        }

                        // Replace the old session id wit the new one.
                        mSessionId = sessionParcel.sessionId;
                        // Adds the new session back into pending sessions.
                        mManager.mPendingTranscodingSessions.put(mSessionId, this);
                    } catch (RemoteException re) {
                        throw new MediaTranscodingException.ServiceNotAvailableException(
                                "Failed to resubmit request to Transcoding service");
                    }
                    mStatus = STATUS_PENDING;
                    mHasRetried = setHasRetried ? true : false;
                }
            }
        }

        /**
         * Cancels the transcoding session and notify the listener.
         * If the session happened to finish before being canceled this call is effectively a no-op
         * and will not update the result in that case.
         */
        public void cancel() {
            synchronized (mLock) {
                // Check if the session is finished already.
                if (mStatus != STATUS_FINISHED) {
                    try {
                        ITranscodingClient client = mManager.getTranscodingClient();
                        // The client may be gone.
                        if (client != null) {
                            client.cancelSession(mSessionId);
                        }
                    } catch (RemoteException re) {
                        //TODO(hkuang): Find out what to do if failing to cancel the session.
                        Log.e(TAG, "Failed to cancel the session due to exception:  " + re);
                    }
                    mStatus = STATUS_FINISHED;
                    mResult = RESULT_CANCELED;

                    // Notifies client the session is canceled.
                    mListenerExecutor.execute(() -> mListener.onTranscodingFinished(this));
                }
            }
        }

        /**
         * Gets the progress of the transcoding session. The progress is between 0 and 100, where 0
         * means that the session has not yet started and 100 means that it is finished. For the
         * cancelled session, the progress will be the last updated progress before it is cancelled.
         * @return The progress.
         */
        @IntRange(from = 0, to = 100)
        public int getProgress() {
            synchronized (mLock) {
                return mProgress;
            }
        }

        /**
         * Gets the status of the transcoding session.
         * @return The status.
         */
        public @Status int getStatus() {
            synchronized (mLock) {
                return mStatus;
            }
        }

        /**
         * Gets sessionId of the transcoding session.
         * @return session id.
         */
        public int getSessionId() {
            return mSessionId;
        }

        /**
         * Gets the result of the transcoding session.
         * @return The result.
         */
        public @Result int getResult() {
            synchronized (mLock) {
                return mResult;
            }
        }

        @Override
        public String toString() {
            String result;
            String status;

            switch (mResult) {
                case RESULT_NONE:
                    result = "RESULT_NONE";
                    break;
                case RESULT_SUCCESS:
                    result = "RESULT_SUCCESS";
                    break;
                case RESULT_ERROR:
                    result = "RESULT_ERROR";
                    break;
                case RESULT_CANCELED:
                    result = "RESULT_CANCELED";
                    break;
                default:
                    result = String.valueOf(mResult);
                    break;
            }

            switch (mStatus) {
                case STATUS_PENDING:
                    status = "STATUS_PENDING";
                    break;
                case STATUS_PAUSED:
                    status = "STATUS_PAUSED";
                    break;
                case STATUS_RUNNING:
                    status = "STATUS_RUNNING";
                    break;
                case STATUS_FINISHED:
                    status = "STATUS_FINISHED";
                    break;
                default:
                    status = String.valueOf(mStatus);
                    break;
            }
            return String.format(" session: {id: %d, status: %s, result: %s, progress: %d}",
                    mSessionId, status, result, mProgress);
        }

        private void updateProgress(int newProgress) {
            synchronized (mLock) {
                mProgress = newProgress;
            }
        }

        private void updateStatus(int newStatus) {
            synchronized (mLock) {
                mStatus = newStatus;
            }
        }
    }

    private ITranscodingClient getTranscodingClient() {
        synchronized (mLock) {
            return mTranscodingClient;
        }
    }

    /**
     * Enqueues a TranscodingRequest for execution.
     * <p> Upon successfully accepting the request, MediaTranscodeManager will return a
     * {@link TranscodingSession} to the client. Client should use {@link TranscodingSession} to
     * track the progress and get the result.
     *
     * @param transcodingRequest The TranscodingRequest to enqueue.
     * @param listenerExecutor   Executor on which the listener is notified.
     * @param listener           Listener to get notified when the transcoding session is finished.
     * @return A TranscodingSession for this operation.
     * @throws FileNotFoundException if the source Uri or destination Uri could not be opened.
     * @throws UnsupportedOperationException if the request could not be fulfilled.
     * @throws MediaTranscodingException.ServiceNotAvailableException if the service
     *         is temporarily unavailable due to internal service rebooting. Client could retry
     *         again after receiving this exception.
     */
    @NonNull
    public TranscodingSession enqueueRequest(
            @NonNull TranscodingRequest transcodingRequest,
            @NonNull @CallbackExecutor Executor listenerExecutor,
            @NonNull OnTranscodingFinishedListener listener)
            throws FileNotFoundException,
            MediaTranscodingException.ServiceNotAvailableException {
        Log.i(TAG, "enqueueRequest called.");
        Objects.requireNonNull(transcodingRequest, "transcodingRequest must not be null");
        Objects.requireNonNull(listenerExecutor, "listenerExecutor must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        // Converts the request to TranscodingRequestParcel.
        TranscodingRequestParcel requestParcel = transcodingRequest.writeToParcel(mContext);

        Log.i(TAG, "Getting transcoding request " + transcodingRequest.getSourceUri());

        // Submits the request to MediaTranscoding service.
        try {
            TranscodingSessionParcel sessionParcel = new TranscodingSessionParcel();
            // Synchronizes the access to mPendingTranscodingSessions to make sure the session Id is
            // inserted in the mPendingTranscodingSessions in the callback handler.
            synchronized (mPendingTranscodingSessions) {
                synchronized (mLock) {
                    if (mTranscodingClient == null) {
                        // Try to register with the service again.
                        IMediaTranscodingService service = getService(false /*retry*/);
                        mTranscodingClient = registerClient(service);
                        // If still fails, throws an exception to tell client to try later.
                        if (mTranscodingClient == null) {
                            throw new MediaTranscodingException.ServiceNotAvailableException(
                                    "Service rebooting. Try again later");
                        }
                    }

                    if (!mTranscodingClient.submitRequest(requestParcel, sessionParcel)) {
                        throw new UnsupportedOperationException("Failed to enqueue request");
                    }
                }

                // Wraps the TranscodingSessionParcel into a TranscodingSession and returns it to
                // client for tracking.
                TranscodingSession session = new TranscodingSession(this, transcodingRequest,
                        sessionParcel,
                        listenerExecutor,
                        listener);

                // Adds the new session into pending sessions.
                mPendingTranscodingSessions.put(session.getSessionId(), session);
                return session;
            }
        } catch (RemoteException | ServiceSpecificException ex) {
            throw new UnsupportedOperationException(
                    "Failed to submit request to Transcoding service");
        }
    }
}
