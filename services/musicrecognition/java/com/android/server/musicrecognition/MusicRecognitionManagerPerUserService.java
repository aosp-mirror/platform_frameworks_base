/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.musicrecognition;

import static android.Manifest.permission.RECORD_AUDIO;
import static android.media.musicrecognition.MusicRecognitionManager.RECOGNITION_FAILED_AUDIO_UNAVAILABLE;
import static android.media.musicrecognition.MusicRecognitionManager.RECOGNITION_FAILED_SERVICE_KILLED;
import static android.media.musicrecognition.MusicRecognitionManager.RECOGNITION_FAILED_SERVICE_UNAVAILABLE;
import static android.media.musicrecognition.MusicRecognitionManager.RecognitionFailureCode;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioRecord;
import android.media.MediaMetadata;
import android.media.musicrecognition.IMusicRecognitionManagerCallback;
import android.media.musicrecognition.IMusicRecognitionServiceCallback;
import android.media.musicrecognition.RecognitionRequest;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractPerUserSystemService;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;


/**
 * Handles per-user requests received by
 * {@link MusicRecognitionManagerService}. Opens an audio stream from the
 * dsp and writes it into a pipe to {@link RemoteMusicRecognitionService}.
 */
public final class MusicRecognitionManagerPerUserService extends
        AbstractPerUserSystemService<MusicRecognitionManagerPerUserService,
                MusicRecognitionManagerService>
        implements RemoteMusicRecognitionService.Callbacks {

    private static final String TAG = MusicRecognitionManagerPerUserService.class.getSimpleName();
    private static final String MUSIC_RECOGNITION_MANAGER_ATTRIBUTION_TAG =
            "MusicRecognitionManagerService";
    private static final String KEY_MUSIC_RECOGNITION_SERVICE_ATTRIBUTION_TAG =
            "android.media.musicrecognition.attributiontag";

    // Number of bytes per sample of audio (which is a short).
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int MAX_STREAMING_SECONDS = 24;

    @Nullable
    @GuardedBy("mLock")
    private RemoteMusicRecognitionService mRemoteService;
    private final AppOpsManager mAppOpsManager;
    private final String mAttributionMessage;

    // Service info of the remote MusicRecognitionService (which the audio gets forwarded to).
    private ServiceInfo mServiceInfo;
    private CompletableFuture<String> mAttributionTagFuture;

    MusicRecognitionManagerPerUserService(
            @NonNull MusicRecognitionManagerService primary,
            @NonNull Object lock, int userId) {
        super(primary, lock, userId);

        // When attributing audio-access, this establishes that audio access is performed by
        // MusicRecognitionManager (on behalf of the receiving service, whose attribution tag,
        // provided by mAttributionTagFuture, is used for the actual calls to startProxyOp(...).
        mAppOpsManager = getContext().createAttributionContext(
            MUSIC_RECOGNITION_MANAGER_ATTRIBUTION_TAG).getSystemService(AppOpsManager.class);
        mAttributionMessage = String.format("MusicRecognitionManager.invokedByUid.%s", userId);
        mAttributionTagFuture = null;
        mServiceInfo = null;
    }

    @NonNull
    @GuardedBy("mLock")
    @Override
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        ServiceInfo si;
        try {
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        if (!Manifest.permission.BIND_MUSIC_RECOGNITION_SERVICE.equals(si.permission)) {
            Slog.w(TAG, "MusicRecognitionService from '" + si.packageName
                    + "' does not require permission "
                    + Manifest.permission.BIND_MUSIC_RECOGNITION_SERVICE);
            throw new SecurityException("Service does not require permission "
                    + Manifest.permission.BIND_MUSIC_RECOGNITION_SERVICE);
        }
        // TODO(b/158194857): check process which owns the service has RECORD_AUDIO permission. How?
        return si;
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteMusicRecognitionService ensureRemoteServiceLocked(
            IMusicRecognitionManagerCallback clientCallback) {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "ensureRemoteServiceLocked(): not set");
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);

            mRemoteService = new RemoteMusicRecognitionService(getContext(),
                    serviceComponent, mUserId, this,
                    new MusicRecognitionServiceCallback(clientCallback),
                    mMaster.isBindInstantServiceAllowed(),
                    mMaster.verbose);

            try {
                mServiceInfo =
                        getContext().getPackageManager().getServiceInfo(
                                mRemoteService.getComponentName(), PackageManager.GET_META_DATA);
                mAttributionTagFuture = mRemoteService.getAttributionTag();
                Slog.i(TAG, "Remote service bound: " + mRemoteService.getComponentName());
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "Service was not found.", e);
            }
        }

        return mRemoteService;
    }

    /**
     * Read audio from the given capture session using an AudioRecord and writes it to a
     * ParcelFileDescriptor.
     */
    @GuardedBy("mLock")
    public void beginRecognitionLocked(
            @NonNull RecognitionRequest recognitionRequest,
            @NonNull IBinder callback) {
        IMusicRecognitionManagerCallback clientCallback =
                IMusicRecognitionManagerCallback.Stub.asInterface(callback);
        mRemoteService = ensureRemoteServiceLocked(clientCallback);
        if (mRemoteService == null) {
            try {
                clientCallback.onRecognitionFailed(
                        RECOGNITION_FAILED_SERVICE_UNAVAILABLE);
            } catch (RemoteException e) {
                // Ignored.
            }
            return;
        }

        Pair<ParcelFileDescriptor, ParcelFileDescriptor> clientPipe = createPipe();
        if (clientPipe == null) {
            try {
                clientCallback.onRecognitionFailed(
                        RECOGNITION_FAILED_AUDIO_UNAVAILABLE);
            } catch (RemoteException ignored) {
                // Ignored.
            }
            return;
        }
        ParcelFileDescriptor audioSink = clientPipe.second;
        ParcelFileDescriptor clientRead = clientPipe.first;

        mAttributionTagFuture.thenAcceptAsync(
                tag -> {
                    streamAudio(tag, recognitionRequest, clientCallback, audioSink);
                }, mMaster.mExecutorService);

        // Send the pipe down to the lookup service while we write to it asynchronously.
        mRemoteService.onAudioStreamStarted(clientRead, recognitionRequest.getAudioFormat());
    }

    /**
     * Streams audio based on given request to the given audioSink. Notifies callback of errors.
     *
     * @param recognitionRequest the recognition request specifying audio parameters.
     * @param clientCallback the callback to notify on errors.
     * @param audioSink the sink to which to stream audio to.
     */
    private void streamAudio(@Nullable String attributionTag,
            @NonNull RecognitionRequest recognitionRequest,
            IMusicRecognitionManagerCallback clientCallback,
            ParcelFileDescriptor audioSink) {
        int maxAudioLengthSeconds = Math.min(recognitionRequest.getMaxAudioLengthSeconds(),
                MAX_STREAMING_SECONDS);
        if (maxAudioLengthSeconds <= 0) {
            // TODO(b/192992319): A request to stream 0s of audio can be used to initialize the
            //  music recognition service implementation, hence not reporting an error here.
            // The TODO for Android T is to move this functionality into an init() API call.
            Slog.i(TAG, "No audio requested. Closing stream.");
            try {
                audioSink.close();
                clientCallback.onAudioStreamClosed();
            } catch (IOException e) {
                Slog.e(TAG, "Problem closing stream.", e);
            } catch (RemoteException ignored) {
                // Ignored.
            }
            return;
        }

        try {
            startRecordAudioOp(attributionTag);
        } catch (SecurityException e) {
            // A security exception can occur if the MusicRecognitionService (receiving the audio)
            // does not (or does no longer) hold the necessary permissions to record audio.
            Slog.e(TAG, "RECORD_AUDIO op not permitted on behalf of "
                    + mServiceInfo.getComponentName(), e);
            try {
                clientCallback.onRecognitionFailed(
                        RECOGNITION_FAILED_AUDIO_UNAVAILABLE);
            } catch (RemoteException ignored) {
                // Ignored.
            }
            return;
        }

        AudioRecord audioRecord = createAudioRecord(recognitionRequest, maxAudioLengthSeconds);
        try (OutputStream fos =
                     new ParcelFileDescriptor.AutoCloseOutputStream(audioSink)) {
            streamAudio(recognitionRequest, maxAudioLengthSeconds, audioRecord, fos);
        } catch (IOException e) {
            Slog.e(TAG, "Audio streaming stopped.", e);
        } finally {
            audioRecord.release();
            finishRecordAudioOp(attributionTag);
            try {
                clientCallback.onAudioStreamClosed();
            } catch (RemoteException ignored) {
                // Ignored.
            }
        }
    }

    /** Performs the actual streaming from audioRecord into outputStream. **/
    private void streamAudio(@NonNull RecognitionRequest recognitionRequest,
            int maxAudioLengthSeconds, AudioRecord audioRecord, OutputStream outputStream)
            throws IOException {
        int halfSecondBufferSize =
                audioRecord.getBufferSizeInFrames() / maxAudioLengthSeconds;
        byte[] byteBuffer = new byte[halfSecondBufferSize];
        int bytesRead = 0;
        int totalBytesRead = 0;
        int ignoreBytes =
                recognitionRequest.getIgnoreBeginningFrames() * BYTES_PER_SAMPLE;
        audioRecord.startRecording();
        while (bytesRead >= 0 && totalBytesRead
                < audioRecord.getBufferSizeInFrames() * BYTES_PER_SAMPLE
                && mRemoteService != null) {
            bytesRead = audioRecord.read(byteBuffer, 0, byteBuffer.length);
            if (bytesRead > 0) {
                totalBytesRead += bytesRead;
                // If we are ignoring the first x bytes, update that counter.
                if (ignoreBytes > 0) {
                    ignoreBytes -= bytesRead;
                    // If we've dipped negative, we've skipped through all ignored bytes
                    // and then some.  Write out the bytes we shouldn't have skipped.
                    if (ignoreBytes < 0) {
                        outputStream.write(byteBuffer, bytesRead + ignoreBytes, -ignoreBytes);
                    }
                } else {
                    outputStream.write(byteBuffer);
                }
            }
        }
        Slog.i(TAG,
                String.format("Streamed %s bytes from audio record", totalBytesRead));
    }

    /**
     * Callback invoked by {@link android.service.musicrecognition.MusicRecognitionService} to pass
     * back the music search result.
     */
    final class MusicRecognitionServiceCallback extends
            IMusicRecognitionServiceCallback.Stub {

        private final IMusicRecognitionManagerCallback mClientCallback;

        private MusicRecognitionServiceCallback(IMusicRecognitionManagerCallback clientCallback) {
            mClientCallback = clientCallback;
        }

        @Override
        public void onRecognitionSucceeded(MediaMetadata result, Bundle extras) {
            try {
                sanitizeBundle(extras);
                mClientCallback.onRecognitionSucceeded(result, extras);
            } catch (RemoteException ignored) {
                // Ignored.
            }
            destroyService();
        }

        @Override
        public void onRecognitionFailed(@RecognitionFailureCode int failureCode) {
            try {
                mClientCallback.onRecognitionFailed(failureCode);
            } catch (RemoteException ignored) {
                // Ignored.
            }
            destroyService();
        }

        private IMusicRecognitionManagerCallback getClientCallback() {
            return mClientCallback;
        }
    }

    @Override
    public void onServiceDied(@NonNull RemoteMusicRecognitionService service) {
        try {
            service.getServerCallback().getClientCallback().onRecognitionFailed(
                    RECOGNITION_FAILED_SERVICE_KILLED);
        } catch (RemoteException e) {
            // Ignored.
        }
        Slog.w(TAG, "remote service died: " + service);
        destroyService();
    }

    @GuardedBy("mLock")
    private void destroyService() {
        synchronized (mLock) {
            if (mRemoteService != null) {
                mRemoteService.destroy();
                mRemoteService = null;
            }
        }
    }

    /**
     * Tracks that the RECORD_AUDIO operation started (attributes it to the service receiving the
     * audio).
     */
    private void startRecordAudioOp(@Nullable String attributionTag) {
        int status = mAppOpsManager.startProxyOp(
                Objects.requireNonNull(AppOpsManager.permissionToOp(RECORD_AUDIO)),
                mServiceInfo.applicationInfo.uid,
                mServiceInfo.packageName,
                attributionTag,
                mAttributionMessage);
        // The above should already throw a SecurityException. This is just a fallback.
        if (status != AppOpsManager.MODE_ALLOWED) {
            throw new SecurityException(String.format(
                    "Failed to obtain RECORD_AUDIO permission (status: %d) for "
                    + "receiving service: %s", status, mServiceInfo.getComponentName()));
        }
        Slog.i(TAG, String.format(
                "Starting audio streaming. Attributing to %s (%d) with tag '%s'",
                mServiceInfo.packageName, mServiceInfo.applicationInfo.uid, attributionTag));
    }


    /** Tracks that the RECORD_AUDIO operation finished. */
    private void finishRecordAudioOp(@Nullable String attributionTag) {
        mAppOpsManager.finishProxyOp(
                Objects.requireNonNull(AppOpsManager.permissionToOp(RECORD_AUDIO)),
                mServiceInfo.applicationInfo.uid,
                mServiceInfo.packageName,
                attributionTag);
    }

    /** Establishes an audio stream from the DSP audio source. */
    private static AudioRecord createAudioRecord(
            @NonNull RecognitionRequest recognitionRequest,
            int maxAudioLengthSeconds) {
        int sampleRate = recognitionRequest.getAudioFormat().getSampleRate();
        int bufferSize = getBufferSizeInBytes(sampleRate, maxAudioLengthSeconds);
        return new AudioRecord(recognitionRequest.getAudioAttributes(),
                recognitionRequest.getAudioFormat(), bufferSize,
                recognitionRequest.getCaptureSession());
    }

    /**
     * Returns the number of bytes required to store {@code bufferLengthSeconds} of audio sampled at
     * {@code sampleRate} Hz, using the format returned by DSP audio capture.
     */
    private static int getBufferSizeInBytes(int sampleRate, int bufferLengthSeconds) {
        return BYTES_PER_SAMPLE * sampleRate * bufferLengthSeconds;
    }

    private static Pair<ParcelFileDescriptor, ParcelFileDescriptor> createPipe() {
        ParcelFileDescriptor[] fileDescriptors;
        try {
            fileDescriptors = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to create audio stream pipe", e);
            return null;
        }

        if (fileDescriptors.length != 2) {
            Slog.e(TAG, "Failed to create audio stream pipe, "
                    + "unexpected number of file descriptors");
            return null;
        }

        if (!fileDescriptors[0].getFileDescriptor().valid()
                || !fileDescriptors[1].getFileDescriptor().valid()) {
            Slog.e(TAG, "Failed to create audio stream pipe, didn't "
                    + "receive a pair of valid file descriptors.");
            return null;
        }

        return Pair.create(fileDescriptors[0], fileDescriptors[1]);
    }

    /** Removes remote objects from the bundle. */
    private static void sanitizeBundle(@Nullable Bundle bundle) {
        if (bundle == null) {
            return;
        }

        for (String key : bundle.keySet()) {
            Object o = bundle.get(key);

            if (o instanceof Bundle) {
                sanitizeBundle((Bundle) o);
            } else if (o instanceof IBinder || o instanceof ParcelFileDescriptor) {
                bundle.remove(key);
            }
        }
    }
}
