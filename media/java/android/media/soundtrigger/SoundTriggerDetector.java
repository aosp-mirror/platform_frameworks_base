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
import static android.hardware.soundtrigger.SoundTrigger.STATUS_OK;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.ISoundTriggerService;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
@SystemApi
public final class SoundTriggerDetector {
    private static final boolean DBG = false;
    private static final String TAG = "SoundTriggerDetector";

    private static final int MSG_AVAILABILITY_CHANGED = 1;
    private static final int MSG_SOUND_TRIGGER_DETECTED = 2;
    private static final int MSG_DETECTION_ERROR = 3;
    private static final int MSG_DETECTION_PAUSE = 4;
    private static final int MSG_DETECTION_RESUME = 5;

    private final Object mLock = new Object();

    private final ISoundTriggerService mSoundTriggerService;
    private final UUID mSoundModelId;
    private final Callback mCallback;
    private final Handler mHandler;
    private final RecognitionCallback mRecognitionCallback;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {
                RECOGNITION_FLAG_NONE,
                RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO,
                RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS
            })
    public @interface RecognitionFlags {}

    /**
     * Empty flag for {@link #startRecognition(int)}.
     *
     *  @hide
     */
    public static final int RECOGNITION_FLAG_NONE = 0;

    /**
     * Recognition flag for {@link #startRecognition(int)} that indicates
     * whether the trigger audio for hotword needs to be captured.
     */
    public static final int RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO = 0x1;

    /**
     * Recognition flag for {@link #startRecognition(int)} that indicates
     * whether the recognition should keep going on even after the
     * model triggers.
     * If this flag is specified, it's possible to get multiple
     * triggers after a call to {@link #startRecognition(int)}, if the model
     * triggers multiple times.
     * When this isn't specified, the default behavior is to stop recognition once the
     * trigger happenss, till the caller starts recognition again.
     */
    public static final int RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS = 0x2;

    /**
     * Additional payload for {@link Callback#onDetected}.
     */
    public static class EventPayload {
        private final boolean mTriggerAvailable;

        // Indicates if {@code captureSession} can be used to continue capturing more audio
        // from the DSP hardware.
        private final boolean mCaptureAvailable;
        // The session to use when attempting to capture more audio from the DSP hardware.
        private final int mCaptureSession;
        private final AudioFormat mAudioFormat;
        // Raw data associated with the event.
        // This is the audio that triggered the keyphrase if {@code isTriggerAudio} is true.
        private final byte[] mData;

        private EventPayload(boolean triggerAvailable, boolean captureAvailable,
                AudioFormat audioFormat, int captureSession, byte[] data) {
            mTriggerAvailable = triggerAvailable;
            mCaptureAvailable = captureAvailable;
            mCaptureSession = captureSession;
            mAudioFormat = audioFormat;
            mData = data;
        }

        /**
         * Gets the format of the audio obtained using {@link #getTriggerAudio()}.
         * May be null if there's no audio present.
         */
        @Nullable
        public AudioFormat getCaptureAudioFormat() {
            return mAudioFormat;
        }

        /**
         * Gets the raw audio that triggered the detector.
         * This may be null if the trigger audio isn't available.
         * If non-null, the format of the audio can be obtained by calling
         * {@link #getCaptureAudioFormat()}.
         *
         * @see AlwaysOnHotwordDetector#RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
         */
        @Nullable
        public byte[] getTriggerAudio() {
            if (mTriggerAvailable) {
                return mData;
            } else {
                return null;
            }
        }

        /**
         * Gets the opaque data passed from the detection engine for the event.
         * This may be null if it was not populated by the engine, or if the data is known to
         * contain the trigger audio.
         *
         * @see #getTriggerAudio
         *
         * @hide
         */
        @Nullable
        public byte[] getData() {
            if (!mTriggerAvailable) {
                return mData;
            } else {
                return null;
            }
        }

        /**
         * Gets the session ID to start a capture from the DSP.
         * This may be null if streaming capture isn't possible.
         * If non-null, the format of the audio that can be captured can be
         * obtained using {@link #getCaptureAudioFormat()}.
         *
         * TODO: Candidate for Public API when the API to start capture with a session ID
         * is made public.
         *
         * TODO: Add this to {@link #getCaptureAudioFormat()}:
         * "Gets the format of the audio obtained using {@link #getTriggerAudio()}
         * or {@link #getCaptureSession()}. May be null if no audio can be obtained
         * for either the trigger or a streaming session."
         *
         * TODO: Should this return a known invalid value instead?
         *
         * @hide
         */
        @Nullable
        public Integer getCaptureSession() {
            if (mCaptureAvailable) {
                return mCaptureSession;
            } else {
                return null;
            }
        }
    }

    public static abstract class Callback {
        /**
         * Called when the availability of the sound model changes.
         */
        public abstract void onAvailabilityChanged(int status);

        /**
         * Called when the sound model has triggered (such as when it matched a
         * given sound pattern).
         */
        public abstract void onDetected(@NonNull EventPayload eventPayload);

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
            mHandler = new MyHandler();
        } else {
            mHandler = new MyHandler(handler.getLooper());
        }
        mRecognitionCallback = new RecognitionCallback();
    }

    /**
     * Starts recognition on the associated sound model. Result is indicated via the
     * {@link Callback}.
     * @return Indicates whether the call succeeded or not.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    public boolean startRecognition(@RecognitionFlags int recognitionFlags) {
        if (DBG) {
            Slog.d(TAG, "startRecognition()");
        }
        boolean captureTriggerAudio =
                (recognitionFlags & RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO) != 0;

        boolean allowMultipleTriggers =
                (recognitionFlags & RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS) != 0;
        int status = STATUS_OK;
        try {
            status = mSoundTriggerService.startRecognition(new ParcelUuid(mSoundModelId),
                    mRecognitionCallback, new RecognitionConfig(captureTriggerAudio,
                        allowMultipleTriggers, null, null));
        } catch (RemoteException e) {
            return false;
        }
        return status == STATUS_OK;
    }

    /**
     * Stops recognition for the associated model.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    public boolean stopRecognition() {
        int status = STATUS_OK;
        try {
            status = mSoundTriggerService.stopRecognition(new ParcelUuid(mSoundModelId),
                    mRecognitionCallback);
        } catch (RemoteException e) {
            return false;
        }
        return status == STATUS_OK;
    }

    /**
     * @hide
     */
    public void dump(String prefix, PrintWriter pw) {
        synchronized (mLock) {
            // TODO: Dump useful debug information.
        }
    }

    /**
     * Callback that handles events from the lower sound trigger layer.
     *
     * Note that these callbacks will be called synchronously from the SoundTriggerService
     * layer and thus should do minimal work (such as sending a message on a handler to do
     * the real work).
     * @hide
     */
    private class RecognitionCallback extends IRecognitionStatusCallback.Stub {

        /**
         * @hide
         */
        @Override
        public void onGenericSoundTriggerDetected(SoundTrigger.GenericRecognitionEvent event) {
            Slog.d(TAG, "onGenericSoundTriggerDetected()" + event);
            Message.obtain(mHandler,
                    MSG_SOUND_TRIGGER_DETECTED,
                    new EventPayload(event.triggerInData, event.captureAvailable,
                            event.captureFormat, event.captureSession, event.data))
                    .sendToTarget();
        }

        @Override
        public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent event) {
            Slog.e(TAG, "Ignoring onKeyphraseDetected() called for " + event);
        }

        /**
         * @hide
         */
        @Override
        public void onError(int status) {
            Slog.d(TAG, "onError()" + status);
            mHandler.sendEmptyMessage(MSG_DETECTION_ERROR);
        }

        /**
         * @hide
         */
        @Override
        public void onRecognitionPaused() {
            Slog.d(TAG, "onRecognitionPaused()");
            mHandler.sendEmptyMessage(MSG_DETECTION_PAUSE);
        }

        /**
         * @hide
         */
        @Override
        public void onRecognitionResumed() {
            Slog.d(TAG, "onRecognitionResumed()");
            mHandler.sendEmptyMessage(MSG_DETECTION_RESUME);
        }
    }

    private class MyHandler extends Handler {

        MyHandler() {
            super();
        }

        MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (mCallback == null) {
                  Slog.w(TAG, "Received message: " + msg.what + " for NULL callback.");
                  return;
            }
            switch (msg.what) {
                case MSG_SOUND_TRIGGER_DETECTED:
                    mCallback.onDetected((EventPayload) msg.obj);
                    break;
                case MSG_DETECTION_ERROR:
                    mCallback.onError();
                    break;
                case MSG_DETECTION_PAUSE:
                    mCallback.onRecognitionPaused();
                    break;
                case MSG_DETECTION_RESUME:
                    mCallback.onRecognitionResumed();
                    break;
                default:
                    super.handleMessage(msg);

            }
        }
    }
}
