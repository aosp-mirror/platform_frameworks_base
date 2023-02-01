/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_PROCESS_RESTARTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_REJECTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED_FROM_RESTART;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.permission.Identity;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.voice.HotwordDetectedResult;
import android.service.voice.HotwordDetectionService;
import android.service.voice.HotwordDetector;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.IDspHotwordDetectionCallback;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class that provides Dsp trusted hotword detector to communicate with the {@link
 * HotwordDetectionService}.
 *
 * This class can handle the hotword detection which detector is created by using
 * {@link android.service.voice.VoiceInteractionService#createAlwaysOnHotwordDetector(String,
 * Locale, PersistableBundle, SharedMemory, AlwaysOnHotwordDetector.Callback)}.
 */
final class DspTrustedHotwordDetectorSession extends DetectorSession {
    private static final String TAG = "DspTrustedHotwordDetectorSession";

    // The validation timeout value is 3 seconds for onDetect of DSP trigger event.
    private static final long VALIDATION_TIMEOUT_MILLIS = 3000;
    // Write the onDetect timeout metric when it takes more time than MAX_VALIDATION_TIMEOUT_MILLIS.
    private static final long MAX_VALIDATION_TIMEOUT_MILLIS = 4000;

    @GuardedBy("mLock")
    private ScheduledFuture<?> mCancellationKeyPhraseDetectionFuture;

    @GuardedBy("mLock")
    private boolean mValidatingDspTrigger = false;
    @GuardedBy("mLock")
    private HotwordRejectedResult mLastHotwordRejectedResult = null;

    DspTrustedHotwordDetectorSession(
            @NonNull HotwordDetectionConnection.ServiceConnection remoteHotwordDetectionService,
            @NonNull Object lock, @NonNull Context context, @NonNull IBinder token,
            @NonNull IHotwordRecognitionStatusCallback callback, int voiceInteractionServiceUid,
            Identity voiceInteractorIdentity,
            @NonNull ScheduledExecutorService scheduledExecutorService, boolean logging) {
        super(remoteHotwordDetectionService, lock, context, token, callback,
                voiceInteractionServiceUid, voiceInteractorIdentity, scheduledExecutorService,
                logging);
    }

    @SuppressWarnings("GuardedBy")
    void detectFromDspSourceLocked(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
            IHotwordRecognitionStatusCallback externalCallback) {
        if (DEBUG) {
            Slog.d(TAG, "detectFromDspSourceLocked");
        }

        AtomicBoolean timeoutDetected = new AtomicBoolean(false);
        // TODO: consider making this a non-anonymous class.
        IDspHotwordDetectionCallback internalCallback = new IDspHotwordDetectionCallback.Stub() {
            @Override
            public void onDetected(HotwordDetectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onDetected");
                }
                synchronized (mLock) {
                    if (mCancellationKeyPhraseDetectionFuture != null) {
                        mCancellationKeyPhraseDetectionFuture.cancel(true);
                    }
                    if (timeoutDetected.get()) {
                        return;
                    }
                    HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                            HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED,
                            mVoiceInteractionServiceUid);
                    if (!mValidatingDspTrigger) {
                        Slog.i(TAG, "Ignoring #onDetected due to a process restart or previous"
                                + " #onRejected result = " + mLastHotwordRejectedResult);
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_UNEXPECTED_CALLBACK,
                                mVoiceInteractionServiceUid);
                        return;
                    }
                    mValidatingDspTrigger = false;
                    try {
                        enforcePermissionsForDataDelivery();
                        enforceExtraKeyphraseIdNotLeaked(result, recognitionEvent);
                    } catch (SecurityException e) {
                        Slog.i(TAG, "Ignoring #onDetected due to a SecurityException", e);
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_SECURITY_EXCEPTION,
                                mVoiceInteractionServiceUid);
                        externalCallback.onError(CALLBACK_ONDETECTED_GOT_SECURITY_EXCEPTION);
                        return;
                    }
                    saveProximityValueToBundle(result);
                    HotwordDetectedResult newResult;
                    try {
                        newResult = mHotwordAudioStreamCopier.startCopyingAudioStreams(result);
                    } catch (IOException e) {
                        externalCallback.onError(CALLBACK_ONDETECTED_STREAM_COPY_ERROR);
                        return;
                    }
                    externalCallback.onKeyphraseDetected(recognitionEvent, newResult);
                    Slog.i(TAG, "Egressed " + HotwordDetectedResult.getUsageSize(newResult)
                            + " bits from hotword trusted process");
                    if (mDebugHotwordLogging) {
                        Slog.i(TAG, "Egressed detected result: " + newResult);
                    }
                }
            }

            @Override
            public void onRejected(HotwordRejectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onRejected");
                }
                synchronized (mLock) {
                    if (mCancellationKeyPhraseDetectionFuture != null) {
                        mCancellationKeyPhraseDetectionFuture.cancel(true);
                    }
                    if (timeoutDetected.get()) {
                        return;
                    }
                    HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                            HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED,
                            mVoiceInteractionServiceUid);
                    if (!mValidatingDspTrigger) {
                        Slog.i(TAG, "Ignoring #onRejected due to a process restart");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                                METRICS_KEYPHRASE_TRIGGERED_REJECT_UNEXPECTED_CALLBACK,
                                mVoiceInteractionServiceUid);
                        return;
                    }
                    mValidatingDspTrigger = false;
                    externalCallback.onRejected(result);
                    mLastHotwordRejectedResult = result;
                    if (mDebugHotwordLogging && result != null) {
                        Slog.i(TAG, "Egressed rejected result: " + result);
                    }
                }
            }
        };

        mValidatingDspTrigger = true;
        mLastHotwordRejectedResult = null;
        mRemoteDetectionService.run(service -> {
            // We use the VALIDATION_TIMEOUT_MILLIS to inform that the client needs to invoke
            // the callback before timeout value. In order to reduce the latency impact between
            // server side and client side, we need to use another timeout value
            // MAX_VALIDATION_TIMEOUT_MILLIS to monitor it.
            mCancellationKeyPhraseDetectionFuture = mScheduledExecutorService.schedule(
                    () -> {
                        // TODO: avoid allocate every time
                        timeoutDetected.set(true);
                        Slog.w(TAG, "Timed out on #detectFromDspSource");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                                HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_TIMEOUT,
                                mVoiceInteractionServiceUid);
                        try {
                            externalCallback.onError(CALLBACK_DETECT_TIMEOUT);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to report onError status: ", e);
                            HotwordMetricsLogger.writeDetectorEvent(
                                    HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                                    mVoiceInteractionServiceUid);
                        }
                    },
                    MAX_VALIDATION_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
            service.detectFromDspSource(
                    recognitionEvent,
                    recognitionEvent.getCaptureFormat(),
                    VALIDATION_TIMEOUT_MILLIS,
                    internalCallback);
        });
    }

    @Override
    @SuppressWarnings("GuardedBy")
    void informRestartProcessLocked() {
        // TODO(b/244598068): Check HotwordAudioStreamManager first
        Slog.v(TAG, "informRestartProcessLocked");
        if (mValidatingDspTrigger) {
            // We're restarting the process while it's processing a DSP trigger, so report a
            // rejection. This also allows the Interactor to startRecognition again
            try {
                mCallback.onRejected(new HotwordRejectedResult.Builder().build());
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED_FROM_RESTART,
                        mVoiceInteractionServiceUid);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call #rejected");
                HotwordMetricsLogger.writeDetectorEvent(
                        HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                        HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_REJECTED_EXCEPTION,
                        mVoiceInteractionServiceUid);
            }
            mValidatingDspTrigger = false;
        }
        mUpdateStateAfterStartFinished.set(false);

        try {
            mCallback.onProcessRestarted();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to communicate #onProcessRestarted", e);
            HotwordMetricsLogger.writeDetectorEvent(
                    HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP,
                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_PROCESS_RESTARTED_EXCEPTION,
                    mVoiceInteractionServiceUid);
        }

        mPerformingExternalSourceHotwordDetection = false;
        closeExternalAudioStreamLocked("process restarted");
    }

    @SuppressWarnings("GuardedBy")
    public void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
        pw.print(prefix); pw.print("mValidatingDspTrigger="); pw.println(mValidatingDspTrigger);
    }
}
