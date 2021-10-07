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

package android.media.musicrecognition;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * System service that manages music recognition.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.MUSIC_RECOGNITION_SERVICE)
public class MusicRecognitionManager {

    /**
     * Error code provided by RecognitionCallback#onRecognitionFailed()
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"RECOGNITION_FAILED_"},
            value = {RECOGNITION_FAILED_UNKNOWN,
                    RECOGNITION_FAILED_NOT_FOUND,
                    RECOGNITION_FAILED_NO_CONNECTIVITY,
                    RECOGNITION_FAILED_SERVICE_UNAVAILABLE,
                    RECOGNITION_FAILED_SERVICE_KILLED,
                    RECOGNITION_FAILED_TIMEOUT,
                    RECOGNITION_FAILED_AUDIO_UNAVAILABLE})
    public @interface RecognitionFailureCode {
    }

    /** Catchall error code. */
    public static final int RECOGNITION_FAILED_UNKNOWN = -1;
    /** Recognition was performed but no result could be identified. */
    public static final int RECOGNITION_FAILED_NOT_FOUND = 1;
    /** Recognition failed because the server couldn't be reached. */
    public static final int RECOGNITION_FAILED_NO_CONNECTIVITY = 2;
    /**
     * Recognition was not possible because the application which provides it is not available (for
     * example, disabled).
     */
    public static final int RECOGNITION_FAILED_SERVICE_UNAVAILABLE = 3;
    /** Recognition failed because the recognizer was killed. */
    public static final int RECOGNITION_FAILED_SERVICE_KILLED = 5;
    /** Recognition attempt timed out. */
    public static final int RECOGNITION_FAILED_TIMEOUT = 6;
    /** Recognition failed due to an issue with obtaining an audio stream. */
    public static final int RECOGNITION_FAILED_AUDIO_UNAVAILABLE = 7;

    /** Callback interface for the caller of this api. */
    public interface RecognitionCallback {
        /**
         * Should be invoked by receiving app with the result of the search.
         *
         * @param recognitionRequest original request that started the recognition
         * @param result result of the search
         * @param extras extra data to be supplied back to the caller. Note that all
         *               executable parameters and file descriptors would be removed from the
         *               supplied bundle
         */
        void onRecognitionSucceeded(@NonNull RecognitionRequest recognitionRequest,
                @NonNull MediaMetadata result,
                @SuppressLint("NullableCollection")
                @Nullable Bundle extras);

        /**
         * Invoked when the search is not successful (possibly but not necessarily due to error).
         *
         * @param recognitionRequest original request that started the recognition
         * @param failureCode failure code describing reason for failure
         */
        void onRecognitionFailed(@NonNull RecognitionRequest recognitionRequest,
                @RecognitionFailureCode int failureCode);

        /**
         * Invoked by the system once the audio stream is closed either due to error, reaching the
         * limit, or the remote service closing the stream.  Always called per
         * #beingStreamingSearch() invocation.
         */
        void onAudioStreamClosed();
    }

    private final IMusicRecognitionManager mService;

    /** @hide */
    public MusicRecognitionManager(IMusicRecognitionManager service) {
        mService = service;
    }

    /**
     * Constructs an {@link android.media.AudioRecord} from the given parameters and streams the
     * audio bytes to the designated cloud lookup service.  After the lookup is done, the given
     * callback will be invoked by the system with the result or lack thereof.
     *
     * @param recognitionRequest audio parameters for the stream to search
     * @param callbackExecutor where the callback is invoked
     * @param callback invoked when the result is available
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_MUSIC_RECOGNITION)
    public void beginStreamingSearch(
            @NonNull RecognitionRequest recognitionRequest,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull RecognitionCallback callback) {
        try {
            mService.beginRecognition(
                    requireNonNull(recognitionRequest),
                    new MusicRecognitionCallbackWrapper(
                            requireNonNull(recognitionRequest),
                            requireNonNull(callback),
                            requireNonNull(callbackExecutor)));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final class MusicRecognitionCallbackWrapper extends
            IMusicRecognitionManagerCallback.Stub {

        @NonNull
        private final RecognitionRequest mRecognitionRequest;
        @NonNull
        private final RecognitionCallback mCallback;
        @NonNull
        private final Executor mCallbackExecutor;

        MusicRecognitionCallbackWrapper(
                RecognitionRequest recognitionRequest,
                RecognitionCallback callback,
                Executor callbackExecutor) {
            mRecognitionRequest = recognitionRequest;
            mCallback = callback;
            mCallbackExecutor = callbackExecutor;
        }

        @Override
        public void onRecognitionSucceeded(MediaMetadata result, Bundle extras) {
            mCallbackExecutor.execute(
                    () -> mCallback.onRecognitionSucceeded(mRecognitionRequest, result, extras));
        }

        @Override
        public void onRecognitionFailed(@RecognitionFailureCode int failureCode) {
            mCallbackExecutor.execute(
                    () -> mCallback.onRecognitionFailed(mRecognitionRequest, failureCode));
        }

        @Override
        public void onAudioStreamClosed() {
            mCallbackExecutor.execute(mCallback::onAudioStreamClosed);
        }
    }
}
