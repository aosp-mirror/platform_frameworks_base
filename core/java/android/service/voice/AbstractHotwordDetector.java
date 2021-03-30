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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.IVoiceInteractionManagerService;

/** Base implementation of {@link HotwordDetector}. */
abstract class AbstractHotwordDetector implements HotwordDetector {
    private static final String TAG = AbstractHotwordDetector.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final IVoiceInteractionManagerService mManagerService;
    private final Handler mHandler;
    private final HotwordDetector.Callback mCallback;

    AbstractHotwordDetector(
            IVoiceInteractionManagerService managerService,
            HotwordDetector.Callback callback) {
        mManagerService = managerService;
        // TODO: this needs to be supplied from above
        mHandler = new Handler(Looper.getMainLooper());
        mCallback = callback;
    }

    /**
     * Detect hotword from an externally supplied stream of data.
     *
     * @return a writeable file descriptor that clients can start writing data in the given format.
     * In order to stop detection, clients can close the given stream.
     */
    @Nullable
    @Override
    public boolean startRecognition(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @Nullable PersistableBundle options) {
        if (DEBUG) {
            Slog.i(TAG, "#recognizeHotword");
        }

        // TODO: consider closing existing session.

        try {
            mManagerService.startListeningFromExternalSource(
                    audioStream,
                    audioFormat,
                    options,
                    new BinderCallback(mHandler, mCallback));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        return true;
    }

    private static class BinderCallback
            extends IMicrophoneHotwordDetectionVoiceInteractionCallback.Stub {
        private final Handler mHandler;
        // TODO: these need to be weak references.
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
                @Nullable ParcelFileDescriptor audioStreamIgnored) {
            mHandler.sendMessage(obtainMessage(
                    HotwordDetector.Callback::onDetected,
                    mCallback,
                    new AlwaysOnHotwordDetector.EventPayload(audioFormat, hotwordDetectedResult)));
        }
    }
}
