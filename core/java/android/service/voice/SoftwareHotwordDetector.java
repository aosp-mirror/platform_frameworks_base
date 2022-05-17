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

package android.service.voice;

import static android.Manifest.permission.RECORD_AUDIO;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceInteractionManagerService;

import java.io.PrintWriter;

/**
 * Manages hotword detection not relying on a specific hardware.
 *
 * <p>On devices where DSP is available it's strongly recommended to use
 * {@link AlwaysOnHotwordDetector}.
 *
 * @hide
 **/
class SoftwareHotwordDetector extends AbstractHotwordDetector {
    private static final String TAG = SoftwareHotwordDetector.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final IVoiceInteractionManagerService mManagerService;
    private final HotwordDetector.Callback mCallback;
    private final AudioFormat mAudioFormat;
    private final Handler mHandler;

    SoftwareHotwordDetector(
            IVoiceInteractionManagerService managerService,
            AudioFormat audioFormat,
            PersistableBundle options,
            SharedMemory sharedMemory,
            HotwordDetector.Callback callback) {
        super(managerService, callback, DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE);

        mManagerService = managerService;
        mAudioFormat = audioFormat;
        mCallback = callback;
        mHandler = new Handler(Looper.getMainLooper());
        updateStateLocked(options, sharedMemory,
                new InitializationStateListener(mHandler, mCallback),
                DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE);
    }

    @RequiresPermission(RECORD_AUDIO)
    @Override
    public boolean startRecognition() {
        if (DEBUG) {
            Slog.i(TAG, "#startRecognition");
        }
        throwIfDetectorIsNoLongerActive();
        maybeCloseExistingSession();

        try {
            mManagerService.startListeningFromMic(
                    mAudioFormat, new BinderCallback(mHandler, mCallback));
        } catch (SecurityException e) {
            Slog.e(TAG, "startRecognition failed: " + e);
            return false;
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        return true;
    }

    /** TODO: stopRecognition */
    @RequiresPermission(RECORD_AUDIO)
    @Override
    public boolean stopRecognition() {
        if (DEBUG) {
            Slog.i(TAG, "#stopRecognition");
        }
        throwIfDetectorIsNoLongerActive();

        try {
            mManagerService.stopListeningFromMic();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        return true;
    }

    @Override
    public void destroy() {
        stopRecognition();
        maybeCloseExistingSession();

        try {
            mManagerService.shutdownHotwordDetectionService();
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
        super.destroy();
    }

    private void maybeCloseExistingSession() {
        // TODO: needs to be synchronized.
        // TODO: implement this
    }

    private static class BinderCallback
            extends IMicrophoneHotwordDetectionVoiceInteractionCallback.Stub {
        private final Handler mHandler;
        // TODO: this needs to be a weak reference.
        private final HotwordDetector.Callback mCallback;

        BinderCallback(Handler handler, HotwordDetector.Callback callback) {
            this.mHandler = handler;
            this.mCallback = callback;
        }

        /** Called when the detected result is valid. */
        @Override
        public void onDetected(
                @Nullable HotwordDetectedResult hotwordDetectedResult,
                @Nullable AudioFormat audioFormat,
                @Nullable ParcelFileDescriptor audioStream) {
            mHandler.sendMessage(obtainMessage(
                    HotwordDetector.Callback::onDetected,
                    mCallback,
                    new AlwaysOnHotwordDetector.EventPayload.Builder()
                            .setCaptureAudioFormat(audioFormat)
                            .setAudioStream(audioStream)
                            .setHotwordDetectedResult(hotwordDetectedResult)
                            .build()));
        }

        /** Called when the detection fails due to an error. */
        @Override
        public void onError() {
            Slog.v(TAG, "BinderCallback#onError");
            mHandler.sendMessage(obtainMessage(
                    HotwordDetector.Callback::onError,
                    mCallback));
        }
    }

    private static class InitializationStateListener
            extends IHotwordRecognitionStatusCallback.Stub {
        private final Handler mHandler;
        private final HotwordDetector.Callback mCallback;

        InitializationStateListener(Handler handler, HotwordDetector.Callback callback) {
            this.mHandler = handler;
            this.mCallback = callback;
        }

        @Override
        public void onKeyphraseDetected(
                SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
                HotwordDetectedResult result) {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onKeyphraseDetected event");
            }
        }

        @Override
        public void onGenericSoundTriggerDetected(
                SoundTrigger.GenericRecognitionEvent recognitionEvent) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onGenericSoundTriggerDetected event");
            }
        }

        @Override
        public void onRejected(HotwordRejectedResult result) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onRejected event");
            }
        }

        @Override
        public void onError(int status) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onError (" + status + ") event");
            }
        }

        @Override
        public void onRecognitionPaused() throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onRecognitionPaused event");
            }
        }

        @Override
        public void onRecognitionResumed() throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onRecognitionResumed event");
            }
        }

        @Override
        public void onStatusReported(int status) {
            Slog.v(TAG, "onStatusReported" + (DEBUG ? "(" + status + ")" : ""));
            mHandler.sendMessage(obtainMessage(
                    HotwordDetector.Callback::onHotwordDetectionServiceInitialized,
                    mCallback,
                    status));
        }

        @Override
        public void onProcessRestarted() throws RemoteException {
            Slog.v(TAG, "onProcessRestarted()");
            mHandler.sendMessage(obtainMessage(
                    HotwordDetector.Callback::onHotwordDetectionServiceRestarted,
                    mCallback));
        }
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        // TODO: implement this
    }
}
