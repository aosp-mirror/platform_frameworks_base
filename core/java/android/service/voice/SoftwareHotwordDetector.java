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
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.util.Slog;

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
    private static final boolean DEBUG = true;

    private final IVoiceInteractionManagerService mManagerService;
    private final HotwordDetector.Callback mCallback;
    private final AudioFormat mAudioFormat;
    private final Handler mHandler;
    private final Object mLock = new Object();

    SoftwareHotwordDetector(
            IVoiceInteractionManagerService managerService,
            AudioFormat audioFormat,
            PersistableBundle options,
            SharedMemory sharedMemory,
            HotwordDetector.Callback callback) {
        super(managerService, callback);

        mManagerService = managerService;
        mAudioFormat = audioFormat;
        mCallback = callback;
        mHandler = new Handler(Looper.getMainLooper());

        try {
            mManagerService.updateState(options, sharedMemory);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(RECORD_AUDIO)
    @Override
    public boolean startRecognition() {
        if (DEBUG) {
            Slog.i(TAG, "#startRecognition");
        }

        maybeCloseExistingSession();

        try {
            mManagerService.startListeningFromMic(
                    mAudioFormat, new BinderCallback(mHandler, mCallback));
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

        try {
            mManagerService.stopListeningFromMic();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        return true;
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

        /** TODO: onDetected */
        @Override
        public void onDetected(
                @Nullable HotwordDetectedResult hotwordDetectedResult,
                @Nullable AudioFormat audioFormat,
                @Nullable ParcelFileDescriptor audioStream) {
            mHandler.sendMessage(obtainMessage(
                    HotwordDetector.Callback::onDetected,
                    mCallback,
                    new AlwaysOnHotwordDetector.EventPayload(
                            audioFormat, hotwordDetectedResult, audioStream)));
        }
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        // TODO: implement this
    }
}
