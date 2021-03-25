/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioAttributes;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.voice.HotwordDetectionService;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.IDspHotwordDetectionCallback;
import android.service.voice.IHotwordDetectionService;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.infra.ServiceConnector;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A class that provides the communication with the HotwordDetectionService.
 */
final class HotwordDetectionConnection {
    private static final String TAG = "HotwordDetectionConnection";
    // TODO (b/177502877): Set the Debug flag to false before shipping.
    private static final boolean DEBUG = true;

    // Number of bytes per sample of audio (which is a short).
    private static final int BYTES_PER_SAMPLE = 2;
    // TODO: These constants need to be refined.
    private static final long VALIDATION_TIMEOUT_MILLIS = 3000;
    private static final long VOICE_INTERACTION_TIMEOUT_TO_OPEN_MIC_MILLIS = 2000;
    private static final int MAX_STREAMING_SECONDS = 10;

    private final Executor mAudioCopyExecutor = Executors.newCachedThreadPool();
    // TODO: This may need to be a Handler(looper)
    private final ScheduledExecutorService mScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor();

    final Object mLock;
    final ComponentName mDetectionComponentName;
    final int mUser;
    final Context mContext;
    final @NonNull ServiceConnector<IHotwordDetectionService> mRemoteHotwordDetectionService;
    boolean mBound;

    HotwordDetectionConnection(Object lock, Context context, ComponentName serviceName,
            int userId, boolean bindInstantServiceAllowed, @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory) {
        mLock = lock;
        mContext = context;
        mDetectionComponentName = serviceName;
        mUser = userId;
        final Intent intent = new Intent(HotwordDetectionService.SERVICE_INTERFACE);
        intent.setComponent(mDetectionComponentName);

        mRemoteHotwordDetectionService = new ServiceConnector.Impl<IHotwordDetectionService>(
                mContext, intent, bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0, mUser,
                IHotwordDetectionService.Stub::asInterface) {
            @Override // from ServiceConnector.Impl
            protected void onServiceConnectionStatusChanged(IHotwordDetectionService service,
                    boolean connected) {
                synchronized (mLock) {
                    mBound = connected;
                    if (connected) {
                        try {
                            service.setConfig(options, sharedMemory);
                        } catch (RemoteException e) {
                            // TODO: (b/181842909) Report an error to voice interactor
                            Slog.w(TAG, "Failed to setConfig for HotwordDetectionService", e);
                        }
                    }
                }
            }

            @Override
            protected long getAutoDisconnectTimeoutMs() {
                return -1;
            }
        };
        mRemoteHotwordDetectionService.connect();
    }

    private boolean isBound() {
        synchronized (mLock) {
            return mBound;
        }
    }

    void cancelLocked() {
        if (DEBUG) {
            Slog.d(TAG, "cancelLocked");
        }
        if (mBound) {
            mRemoteHotwordDetectionService.unbind();
            mBound = false;
        }
    }

    void setConfigLocked(PersistableBundle options, SharedMemory sharedMemory) {
        mRemoteHotwordDetectionService.run(
                service -> service.setConfig(options, sharedMemory));
    }

    private void detectFromDspSource(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
            IHotwordRecognitionStatusCallback externalCallback) {
        if (DEBUG) {
            Slog.d(TAG, "detectFromDspSource");
        }

        AudioRecord record = createAudioRecord(recognitionEvent);

        Pair<ParcelFileDescriptor, ParcelFileDescriptor> clientPipe = createPipe();

        if (clientPipe == null) {
            // Error.
            // Need to propagate as unknown error or something?
            return;
        }
        ParcelFileDescriptor audioSink = clientPipe.second;
        ParcelFileDescriptor clientRead = clientPipe.first;

        record.startRecording();

        mAudioCopyExecutor.execute(() -> {
            try (OutputStream fos =
                         new ParcelFileDescriptor.AutoCloseOutputStream(audioSink)) {
                byte[] buffer = new byte[1024];

                while (true) {
                    int bytesRead = record.read(buffer, 0, 1024);

                    if (bytesRead < 0) {
                        break;
                    }

                    fos.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed supplying audio data to validator", e);
            }
        });

        Runnable cancellingJob = () -> {
            record.stop();
            bestEffortCloseFileDescriptor(audioSink);
            // TODO: consider calling externalCallback.onRejected(ERROR_TIMEOUT).
        };

        ScheduledFuture<?> cancelingFuture =
                mScheduledExecutorService.schedule(
                        cancellingJob, VALIDATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        // TODO: consider making this a non-anonymous class.
        IDspHotwordDetectionCallback internalCallback = new IDspHotwordDetectionCallback.Stub() {
            @Override
            public void onDetected() throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onDetected");
                }
                bestEffortCloseFileDescriptor(audioSink);
                cancelingFuture.cancel(true);

                // Give 2 more seconds for the interactor to start consuming the mic. If it fails to
                // do so under the given time, we'll force-close the mic to make sure resources are
                // freed up.
                // TODO: consider modelling these 2 seconds in the API.
                mScheduledExecutorService.schedule(
                        cancellingJob,
                        VOICE_INTERACTION_TIMEOUT_TO_OPEN_MIC_MILLIS,
                        TimeUnit.MILLISECONDS);

                externalCallback.onKeyphraseDetected(recognitionEvent);
            }

            @Override
            public void onRejected(HotwordRejectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onRejected");
                }
                cancelingFuture.cancel(true);
                externalCallback.onRejected(result);
            }
        };

        mRemoteHotwordDetectionService.run(
                service -> service.detectFromDspSource(
                        clientRead,
                        recognitionEvent.getCaptureFormat(),
                        VALIDATION_TIMEOUT_MILLIS,
                        internalCallback));
        bestEffortCloseFileDescriptor(clientRead);
    }

    static final class SoundTriggerCallback extends IRecognitionStatusCallback.Stub {
        private SoundTrigger.KeyphraseRecognitionEvent mRecognitionEvent;
        private final HotwordDetectionConnection mHotwordDetectionConnection;
        private final IHotwordRecognitionStatusCallback mExternalCallback;

        SoundTriggerCallback(IHotwordRecognitionStatusCallback callback,
                HotwordDetectionConnection connection) {
            mHotwordDetectionConnection = connection;
            mExternalCallback = callback;
        }

        @Override
        public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent)
                throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "onKeyphraseDetected recognitionEvent : " + recognitionEvent);
            }
            final boolean useHotwordDetectionService = mHotwordDetectionConnection != null
                    && mHotwordDetectionConnection.isBound();
            if (useHotwordDetectionService) {
                mRecognitionEvent = recognitionEvent;
                mHotwordDetectionConnection.detectFromDspSource(
                        recognitionEvent, mExternalCallback);
            } else {
                mExternalCallback.onKeyphraseDetected(recognitionEvent);
            }
        }

        @Override
        public void onGenericSoundTriggerDetected(
                SoundTrigger.GenericRecognitionEvent recognitionEvent)
                throws RemoteException {
            mExternalCallback.onGenericSoundTriggerDetected(recognitionEvent);
        }

        @Override
        public void onError(int status) throws RemoteException {
            mExternalCallback.onError(status);
        }

        @Override
        public void onRecognitionPaused() throws RemoteException {
            mExternalCallback.onRecognitionPaused();
        }

        @Override
        public void onRecognitionResumed() throws RemoteException {
            mExternalCallback.onRecognitionResumed();
        }
    }

    // TODO: figure out if we need to let the client configure some of the parameters.
    private static AudioRecord createAudioRecord(
            @NonNull SoundTrigger.KeyphraseRecognitionEvent recognitionEvent) {
        int sampleRate = recognitionEvent.getCaptureFormat().getSampleRate();
        return new AudioRecord(
                new AudioAttributes.Builder()
                        .setInternalCapturePreset(MediaRecorder.AudioSource.HOTWORD).build(),
                recognitionEvent.getCaptureFormat(),
                getBufferSizeInBytes(sampleRate, MAX_STREAMING_SECONDS),
                recognitionEvent.getCaptureSession());
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

        return Pair.create(fileDescriptors[0], fileDescriptors[1]);
    }

    private static void bestEffortCloseFileDescriptor(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (IOException e) {
            if (DEBUG) {
                Slog.w(TAG, "Failed closing file descriptor", e);
            }
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mBound="); pw.println(mBound);
    }
};
