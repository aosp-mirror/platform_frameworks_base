/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.soundtrigger;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.ISoundTriggerService;

import java.io.PrintWriter;
import java.util.UUID;

/**
 * A class that allows interaction with the actual sound trigger detection on the system.
 * Sound trigger detection refers to a detectors that match generic sound patterns that are
 * not voice-based. The voice-based recognition models should utilize the {@link
 * VoiceInteractionService} instead. Access to this class is protected by a permission
 * granted only to system or privileged apps.
 *
 * @hide
 */
public final class SoundTriggerDetector {
    private static final boolean DBG = false;
    private static final String TAG = "SoundTriggerDetector";

    private final Object mLock = new Object();

    private final ISoundTriggerService mSoundTriggerService;
    private final UUID mSoundModelId;
    private final Callback mCallback;
    private final Handler mHandler;
    private final RecognitionCallback mRecognitionCallback;

    public abstract class Callback {
        /**
         * Called when the availability of the sound model changes.
         */
        public abstract void onAvailabilityChanged(int status);

        /**
         * Called when the sound model has triggered (such as when it matched a
         * given sound pattern).
         */
        public abstract void onDetected();

        /**
         *  Called when the detection fails due to an error.
         */
        public abstract void onError();

        /**
         * Called when the recognition is paused temporarily for some reason.
         * This is an informational callback, and the clients shouldn't be doing anything here
         * except showing an indication on their UI if they have to.
         */
        public abstract void onRecognitionPaused();

        /**
         * Called when the recognition is resumed after it was temporarily paused.
         * This is an informational callback, and the clients shouldn't be doing anything here
         * except showing an indication on their UI if they have to.
         */
        public abstract void onRecognitionResumed();
    }

    /**
     * This class should be constructed by the {@link SoundTriggerManager}.
     * @hide
     */
    SoundTriggerDetector(ISoundTriggerService soundTriggerService, UUID soundModelId,
            @NonNull Callback callback, @Nullable Handler handler) {
        mSoundTriggerService = soundTriggerService;
        mSoundModelId = soundModelId;
        mCallback = callback;
        if (handler == null) {
            mHandler = new Handler();
        } else {
            mHandler = handler;
        }
        mRecognitionCallback = new RecognitionCallback();
    }

    /**
     * Starts recognition on the associated sound model. Result is indicated via the
     * {@link Callback}.
     * @return Indicates whether the call succeeded or not.
     */
    public boolean startRecognition() {
        if (DBG) {
            Slog.d(TAG, "startRecognition()");
        }
        try {
            mSoundTriggerService.startRecognition(new ParcelUuid(mSoundModelId),
                    mRecognitionCallback);
        } catch (RemoteException e) {
            return false;
        }
        return true;
    }

    /**
     * Stops recognition for the associated model.
     */
    public boolean stopRecognition() {
        try {
            mSoundTriggerService.stopRecognition(new ParcelUuid(mSoundModelId),
                    mRecognitionCallback);
        } catch (RemoteException e) {
            return false;
        }
        return true;
    }

    public void dump(String prefix, PrintWriter pw) {
        synchronized (mLock) {
            // TODO: Dump useful debug information.
        }
    }

    /**
     * Callback that handles events from the lower sound trigger layer.
     * @hide
     */
    private static class RecognitionCallback extends
            IRecognitionStatusCallback.Stub {

        /**
         * @hide
         */
        @Override
        public void onDetected(SoundTrigger.RecognitionEvent event) {
            Slog.e(TAG, "onDetected()" + event);
        }

        /**
         * @hide
         */
        @Override
        public void onError(int status) {
            Slog.e(TAG, "onError()" + status);
        }

        /**
         * @hide
         */
        @Override
        public void onRecognitionPaused() {
            Slog.e(TAG, "onRecognitionPaused()");
        }

        /**
         * @hide
         */
        @Override
        public void onRecognitionResumed() {
            Slog.e(TAG, "onRecognitionResumed()");
        }
    }
}
