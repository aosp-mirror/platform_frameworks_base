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

import static android.media.musicrecognition.MusicRecognitionManager.RECOGNITION_FAILED_SERVICE_KILLED;
import static android.media.musicrecognition.MusicRecognitionManager.RECOGNITION_FAILED_SERVICE_UNAVAILABLE;
import static android.media.musicrecognition.MusicRecognitionManager.RecognitionFailureCode;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
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
    // Number of bytes per sample of audio (which is a short).
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int MAX_STREAMING_SECONDS = 24;

    @Nullable
    @GuardedBy("mLock")
    private RemoteMusicRecognitionService mRemoteService;

    private MusicRecognitionServiceCallback mRemoteServiceCallback =
            new MusicRecognitionServiceCallback();
    private IMusicRecognitionManagerCallback mCallback;

    MusicRecognitionManagerPerUserService(
            @NonNull MusicRecognitionManagerService primary,
            @NonNull Object lock, int userId) {
        super(primary, lock, userId);
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
    private RemoteMusicRecognitionService ensureRemoteServiceLocked() {
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
                    mRemoteServiceCallback, mMaster.isBindInstantServiceAllowed(),
                    mMaster.verbose);
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
        int maxAudioLengthSeconds = Math.min(recognitionRequest.getMaxAudioLengthSeconds(),
                MAX_STREAMING_SECONDS);
        mCallback = IMusicRecognitionManagerCallback.Stub.asInterface(callback);
        AudioRecord audioRecord = createAudioRecord(recognitionRequest, maxAudioLengthSeconds);

        mRemoteService = ensureRemoteServiceLocked();
        if (mRemoteService == null) {
            try {
                mCallback.onRecognitionFailed(
                        RECOGNITION_FAILED_SERVICE_UNAVAILABLE);
            } catch (RemoteException e) {
                // Ignored.
            }
            return;
        }

        Pair<ParcelFileDescriptor, ParcelFileDescriptor> clientPipe = createPipe();
        if (clientPipe == null) {
            try {
                mCallback.onAudioStreamClosed();
            } catch (RemoteException ignored) {
                // Ignored.
            }
            return;
        }
        ParcelFileDescriptor audioSink = clientPipe.second;
        ParcelFileDescriptor clientRead = clientPipe.first;

        mMaster.mExecutorService.execute(() -> {
            try (OutputStream fos =
                        new ParcelFileDescriptor.AutoCloseOutputStream(audioSink)) {
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
                                fos.write(byteBuffer, bytesRead + ignoreBytes, -ignoreBytes);
                            }
                        } else {
                            fos.write(byteBuffer);
                        }
                    }
                }
                Slog.i(TAG, String.format("Streamed %s bytes from audio record", totalBytesRead));
            } catch (IOException e) {
                Slog.e(TAG, "Audio streaming stopped.", e);
            } finally {
                audioRecord.release();
                try {
                    mCallback.onAudioStreamClosed();
                } catch (RemoteException ignored) {
                    // Ignored.
                }

            }
        });
        // Send the pipe down to the lookup service while we write to it asynchronously.
        mRemoteService.writeAudioToPipe(clientRead, recognitionRequest.getAudioFormat());
    }

    /**
     * Callback invoked by {@link android.service.musicrecognition.MusicRecognitionService} to pass
     * back the music search result.
     */
    private final class MusicRecognitionServiceCallback extends
            IMusicRecognitionServiceCallback.Stub {
        @Override
        public void onRecognitionSucceeded(MediaMetadata result, Bundle extras) {
            try {
                sanitizeBundle(extras);
                mCallback.onRecognitionSucceeded(result, extras);
            } catch (RemoteException ignored) {
                // Ignored.
            }
            destroyService();
        }

        @Override
        public void onRecognitionFailed(@RecognitionFailureCode int failureCode) {
            try {
                mCallback.onRecognitionFailed(failureCode);
            } catch (RemoteException ignored) {
                // Ignored.
            }
            destroyService();
        }
    }

    @Override
    public void onServiceDied(@NonNull RemoteMusicRecognitionService service) {
        try {
            mCallback.onRecognitionFailed(RECOGNITION_FAILED_SERVICE_KILLED);
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
