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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

/**
 * Implemented by an app that wants to offer music search lookups. The system will start the
 * service and stream up to 16 seconds of audio over the given file descriptor.
 *
 * @hide
 */
@SystemApi
public abstract class MusicRecognitionService extends Service {

    private static final String TAG = MusicRecognitionService.class.getSimpleName();

    /** Callback for the result of the remote search. */
    public interface Callback {
        /**
         * Call this method to pass back a successful search result.
         *
         * @param result successful result of the search
         * @param extras extra data to be supplied back to the caller. Note that all executable
         *               parameters and file descriptors would be removed from the supplied bundle
         */
        void onRecognitionSucceeded(@NonNull MediaMetadata result,
                @SuppressLint("NullableCollection")
                @Nullable Bundle extras);

        /**
         * Call this method if the search does not find a result on an error occurred.
         */
        void onRecognitionFailed(@MusicRecognitionManager.RecognitionFailureCode int failureCode);
    }

    /**
     * Action used to start this service.
     *
     * @hide
     */
    public static final String ACTION_MUSIC_SEARCH_LOOKUP =
            "android.service.musicrecognition.MUSIC_RECOGNITION";

    private Handler mHandler;
    private final IMusicRecognitionService mServiceInterface =
            new IMusicRecognitionService.Stub() {
                @Override
                public void onAudioStreamStarted(ParcelFileDescriptor fd,
                        AudioFormat audioFormat,
                        IMusicRecognitionServiceCallback callback) {
                    mHandler.sendMessage(
                            obtainMessage(MusicRecognitionService.this::onRecognize, fd,
                                    audioFormat,
                                    new Callback() {
                                        @Override
                                        public void onRecognitionSucceeded(
                                                @NonNull MediaMetadata result,
                                                @Nullable Bundle extras) {
                                            try {
                                                callback.onRecognitionSucceeded(result, extras);
                                            } catch (RemoteException e) {
                                                throw e.rethrowFromSystemServer();
                                            }
                                        }

                                        @Override
                                        public void onRecognitionFailed(int failureCode) {
                                            try {
                                                callback.onRecognitionFailed(failureCode);
                                            } catch (RemoteException e) {
                                                throw e.rethrowFromSystemServer();
                                            }
                                        }
                                    }));
                }

                @Override
                public void getAttributionTag(
                        IMusicRecognitionAttributionTagCallback callback) throws RemoteException {
                    String tag = MusicRecognitionService.this.getAttributionTag();
                    callback.onAttributionTag(tag);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    /**
     * Read audio from this stream. You must invoke the callback whether the music is recognized or
     * not.
     *
     * @param stream containing music to be recognized. Close when you are finished.
     * @param audioFormat describes sample rate, channels and endianness of the stream
     * @param callback to invoke after lookup is finished. Must always be called.
     */
    public abstract void onRecognize(@NonNull ParcelFileDescriptor stream,
            @NonNull AudioFormat audioFormat,
            @NonNull Callback callback);

    /**
     * @hide
     */
    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        if (ACTION_MUSIC_SEARCH_LOOKUP.equals(intent.getAction())) {
            return mServiceInterface.asBinder();
        }
        Log.w(TAG,
                "Tried to bind to wrong intent (should be " + ACTION_MUSIC_SEARCH_LOOKUP + ": "
                        + intent);
        return null;
    }
}
