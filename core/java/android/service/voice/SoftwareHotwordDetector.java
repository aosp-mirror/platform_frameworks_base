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

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.infra.AndroidFuture;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * Manages hotword detection not relying on a specific hardware.
 *
 * <p>On devices where DSP is available it's strongly recommended to use
 * {@link AlwaysOnHotwordDetector}.
 *
 * @hide
 **/
class SoftwareHotwordDetector extends AbstractDetector {
    private static final String TAG = SoftwareHotwordDetector.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final IVoiceInteractionManagerService mManagerService;
    private final HotwordDetector.Callback mCallback;
    private final AudioFormat mAudioFormat;
    private final Executor mExecutor;
    private final String mAttributionTag;

    SoftwareHotwordDetector(
            IVoiceInteractionManagerService managerService,
            AudioFormat audioFormat,
            Executor executor,
            HotwordDetector.Callback callback,
            String attributionTag) {
        super(managerService, executor, callback);

        mManagerService = managerService;
        mAudioFormat = audioFormat;
        mCallback = callback;
        mExecutor = executor != null ? executor : new HandlerExecutor(
                new Handler(Looper.getMainLooper()));
        mAttributionTag = attributionTag;
    }

    @Override
    void initialize(@Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory) {
        initAndVerifyDetector(options, sharedMemory,
                new InitializationStateListener(mExecutor, mCallback),
                DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE, mAttributionTag);
    }

    void onDetectorRemoteException() {
        Binder.withCleanCallingIdentity(() -> mExecutor.execute(() ->
                mCallback.onFailure(new HotwordDetectionServiceFailure(
                HotwordDetectionServiceFailure.ERROR_CODE_REMOTE_EXCEPTION,
                "Detector remote exception occurs"))));
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
            mManagerService.startListeningFromMic(mAudioFormat,
                    new BinderCallback(mExecutor, mCallback));
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
        try {
            stopRecognition();
        } catch (Exception e) {
            Log.i(TAG, "failed to stopRecognition in destroy", e);
        }
        maybeCloseExistingSession();
        super.destroy();
    }

    /**
     * @hide
     */
    @Override
    public boolean isUsingSandboxedDetectionService() {
        return true;
    }

    private void maybeCloseExistingSession() {
        // TODO: needs to be synchronized.
        // TODO: implement this
    }

    private static class BinderCallback
            extends IMicrophoneHotwordDetectionVoiceInteractionCallback.Stub {
        // TODO: this needs to be a weak reference.
        private final HotwordDetector.Callback mCallback;
        private final Executor mExecutor;

        BinderCallback(Executor executor, HotwordDetector.Callback callback) {
            this.mCallback = callback;
            this.mExecutor = executor;
        }

        /** Called when the detected result is valid. */
        @Override
        public void onDetected(
                @Nullable HotwordDetectedResult hotwordDetectedResult,
                @Nullable AudioFormat audioFormat,
                @Nullable ParcelFileDescriptor audioStream) {
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                mCallback.onDetected(new AlwaysOnHotwordDetector.EventPayload.Builder()
                        .setCaptureAudioFormat(audioFormat)
                        .setAudioStream(audioStream)
                        .setHotwordDetectedResult(hotwordDetectedResult)
                        .build());
            }));
        }

        /** Called when the detection fails due to an error. */
        @Override
        public void onHotwordDetectionServiceFailure(
                HotwordDetectionServiceFailure hotwordDetectionServiceFailure) {
            Slog.v(TAG, "BinderCallback#onHotwordDetectionServiceFailure:"
                    + hotwordDetectionServiceFailure);
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                if (hotwordDetectionServiceFailure != null) {
                    mCallback.onFailure(hotwordDetectionServiceFailure);
                } else {
                    mCallback.onUnknownFailure("Error data is null");
                }
            }));
        }

        @Override
        public void onRejected(@Nullable HotwordRejectedResult result) {
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                mCallback.onRejected(
                        result != null ? result : new HotwordRejectedResult.Builder().build());
            }));
        }
    }

    private static class InitializationStateListener
            extends IHotwordRecognitionStatusCallback.Stub {
        private final HotwordDetector.Callback mCallback;
        private final Executor mExecutor;

        InitializationStateListener(Executor executor, HotwordDetector.Callback callback) {
            this.mCallback = callback;
            this.mExecutor = executor;
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
        public void onKeyphraseDetectedFromExternalSource(HotwordDetectedResult result) {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onKeyphraseDetectedFromExternalSource event");
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
        public void onHotwordDetectionServiceFailure(
                HotwordDetectionServiceFailure hotwordDetectionServiceFailure)
                throws RemoteException {
            Slog.v(TAG, "onHotwordDetectionServiceFailure: " + hotwordDetectionServiceFailure);
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                if (hotwordDetectionServiceFailure != null) {
                    mCallback.onFailure(hotwordDetectionServiceFailure);
                } else {
                    mCallback.onUnknownFailure("Error data is null");
                }
            }));
        }

        @Override
        public void onVisualQueryDetectionServiceFailure(
                VisualQueryDetectionServiceFailure visualQueryDetectionServiceFailure)
                throws RemoteException {
            // It should never be called here.
            Slog.w(TAG, "onVisualQueryDetectionServiceFailure: "
                    + visualQueryDetectionServiceFailure);
        }

        @Override
        public void onSoundTriggerFailure(SoundTriggerFailure onSoundTriggerFailure)
                throws RemoteException {
            // It should never be called here.
            Slog.wtf(TAG, "Unexpected STFailure in software detector: " + onSoundTriggerFailure);
        }

        @Override
        public void onUnknownFailure(String errorMessage) throws RemoteException {
            Slog.v(TAG, "onUnknownFailure: " + errorMessage);
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                mCallback.onUnknownFailure(
                        !TextUtils.isEmpty(errorMessage) ? errorMessage : "Error data is null");
            }));
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
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> mCallback.onHotwordDetectionServiceInitialized(status)));
        }

        @Override
        public void onProcessRestarted() throws RemoteException {
            Slog.v(TAG, "onProcessRestarted()");
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> mCallback.onHotwordDetectionServiceRestarted()));
        }

        @Override
        public void onOpenFile(String filename, AndroidFuture future) throws RemoteException {
            throw new UnsupportedOperationException("Hotword cannot access files from the disk.");
        }
    }

    /** @hide */
    @Override
    public void dump(String prefix, PrintWriter pw) {
        // TODO: implement this
    }
}
