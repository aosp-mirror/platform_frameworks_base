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

import static android.service.voice.HotwordDetectionService.AUDIO_SOURCE_MICROPHONE;
import static android.service.voice.HotwordDetectionServiceFailure.ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION;
import static android.service.voice.HotwordDetectionServiceFailure.ERROR_CODE_ON_DETECTED_STREAM_COPY_FAILURE;

import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_DETECTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_PROCESS_RESTARTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__START_SOFTWARE_DETECTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED;

import android.annotation.NonNull;
import android.content.Context;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.voice.HotwordDetectedResult;
import android.service.voice.HotwordDetectionService;
import android.service.voice.HotwordDetectionServiceFailure;
import android.service.voice.HotwordDetector;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.HotwordTrainingData;
import android.service.voice.IDspHotwordDetectionCallback;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.ISandboxedDetectionService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.server.voiceinteraction.VoiceInteractionManagerServiceImpl.DetectorRemoteExceptionListener;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ScheduledExecutorService;

/**
 * A class that provides software trusted hotword detector to communicate with the {@link
 * HotwordDetectionService}.
 *
 * This class can handle the hotword detection which detector is created by using
 * {@link android.service.voice.VoiceInteractionService#createHotwordDetector(PersistableBundle,
 * SharedMemory, HotwordDetector.Callback)}.
 */
final class SoftwareTrustedHotwordDetectorSession extends DetectorSession {
    private static final String TAG = "SoftwareTrustedHotwordDetectorSession";

    private IMicrophoneHotwordDetectionVoiceInteractionCallback mSoftwareCallback;
    @GuardedBy("mLock")
    private boolean mPerformingSoftwareHotwordDetection;

    SoftwareTrustedHotwordDetectorSession(
            @NonNull HotwordDetectionConnection.ServiceConnection remoteHotwordDetectionService,
            @NonNull Object lock, @NonNull Context context, @NonNull IBinder token,
            @NonNull IHotwordRecognitionStatusCallback callback, int voiceInteractionServiceUid,
            Identity voiceInteractorIdentity,
            @NonNull ScheduledExecutorService scheduledExecutorService, boolean logging,
            @NonNull DetectorRemoteExceptionListener listener) {
        super(remoteHotwordDetectionService, lock, context, token, callback,
                voiceInteractionServiceUid, voiceInteractorIdentity, scheduledExecutorService,
                logging, listener);
    }

    @SuppressWarnings("GuardedBy")
    void startListeningFromMicLocked(
            AudioFormat audioFormat,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromMicLocked");
        }
        mSoftwareCallback = callback;

        if (mPerformingSoftwareHotwordDetection) {
            Slog.i(TAG, "Hotword validation is already in progress, ignoring.");
            return;
        }
        mPerformingSoftwareHotwordDetection = true;

        startListeningFromMicLocked();
    }

    @SuppressWarnings("GuardedBy")
    private void startListeningFromMicLocked() {
        // TODO: consider making this a non-anonymous class.
        IDspHotwordDetectionCallback internalCallback = new IDspHotwordDetectionCallback.Stub() {
            @Override
            public void onDetected(HotwordDetectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onDetected");
                }
                synchronized (mLock) {
                    HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                            HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED,
                            mVoiceInteractionServiceUid);
                    if (!mPerformingSoftwareHotwordDetection) {
                        Slog.i(TAG, "Hotword detection has already completed");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_UNEXPECTED_CALLBACK,
                                mVoiceInteractionServiceUid);
                        return;
                    }
                    mPerformingSoftwareHotwordDetection = false;
                    try {
                        enforcePermissionsForDataDelivery();
                    } catch (SecurityException e) {
                        Slog.w(TAG, "Ignoring #onDetected due to a SecurityException", e);
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_SECURITY_EXCEPTION,
                                mVoiceInteractionServiceUid);
                        try {
                            mSoftwareCallback.onHotwordDetectionServiceFailure(
                                    new HotwordDetectionServiceFailure(
                                            ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION,
                                            "Security exception occurs in #onDetected method."));
                        } catch (RemoteException e1) {
                            notifyOnDetectorRemoteException();
                            HotwordMetricsLogger.writeDetectorEvent(
                                    HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                                    mVoiceInteractionServiceUid);
                            throw e1;
                        }
                        return;
                    }
                    saveProximityValueToBundle(result);
                    HotwordDetectedResult newResult;
                    try {
                        newResult = mHotwordAudioStreamCopier.startCopyingAudioStreams(result);
                    } catch (IOException e) {
                        Slog.w(TAG, "Ignoring #onDetected due to a IOException", e);
                        // TODO: Write event
                        try {
                            mSoftwareCallback.onHotwordDetectionServiceFailure(
                                    new HotwordDetectionServiceFailure(
                                            ERROR_CODE_ON_DETECTED_STREAM_COPY_FAILURE,
                                            "Copy audio stream failure."));
                        } catch (RemoteException e1) {
                            notifyOnDetectorRemoteException();
                            HotwordMetricsLogger.writeDetectorEvent(
                                    HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                                    mVoiceInteractionServiceUid);
                            throw e1;
                        }
                        return;
                    }
                    try {
                        mSoftwareCallback.onDetected(newResult, null, null);
                    } catch (RemoteException e1) {
                        notifyOnDetectorRemoteException();
                        HotwordMetricsLogger.writeDetectorEvent(
                                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                                HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_DETECTED_EXCEPTION,
                                mVoiceInteractionServiceUid);
                        throw e1;
                    }
                    Slog.i(TAG, "Egressed " + HotwordDetectedResult.getUsageSize(newResult)
                            + " bits from hotword trusted process");
                    logEgressSizeStats(newResult);
                    if (mDebugHotwordLogging) {
                        Slog.i(TAG, "Egressed detected result: " + newResult);
                    }
                }
            }

            @Override
            public void onRejected(HotwordRejectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.wtf(TAG, "onRejected");
                }
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED,
                        mVoiceInteractionServiceUid);
                logEgressSizeStats(result);
                // onRejected isn't allowed here, and we are not expecting it.
            }

            public void onTrainingData(HotwordTrainingData data) throws RemoteException {
                sendTrainingData(new TrainingDataEgressCallback() {
                    @Override
                    public void onHotwordDetectionServiceFailure(
                            HotwordDetectionServiceFailure failure) throws RemoteException {
                        mSoftwareCallback.onHotwordDetectionServiceFailure(failure);
                    }

                    @Override
                    public void onTrainingData(HotwordTrainingData data) throws RemoteException {
                        mSoftwareCallback.onTrainingData(data);
                    }
                }, data);
            }
        };

        mRemoteDetectionService.run(
                service -> service.detectFromMicrophoneSource(
                        null,
                        AUDIO_SOURCE_MICROPHONE,
                        null,
                        null,
                        internalCallback));
        HotwordMetricsLogger.writeDetectorEvent(
                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                HOTWORD_DETECTOR_EVENTS__EVENT__START_SOFTWARE_DETECTION,
                mVoiceInteractionServiceUid);
    }

    @SuppressWarnings("GuardedBy")
    void stopListeningFromMicLocked() {
        if (DEBUG) {
            Slog.d(TAG, "stopListeningFromMicLocked");
        }
        if (!mPerformingSoftwareHotwordDetection) {
            Slog.i(TAG, "Hotword detection is not running");
            return;
        }
        mPerformingSoftwareHotwordDetection = false;

        mRemoteDetectionService.run(ISandboxedDetectionService::stopDetection);

        closeExternalAudioStreamLocked("stopping requested");
    }

    @Override
    @SuppressWarnings("GuardedBy")
    void informRestartProcessLocked() {
        // TODO(b/244598068): Check HotwordAudioStreamManager first
        Slog.v(TAG, "informRestartProcessLocked");
        mUpdateStateAfterStartFinished.set(false);

        try {
            mCallback.onProcessRestarted();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to communicate #onProcessRestarted", e);
            HotwordMetricsLogger.writeDetectorEvent(
                    HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE,
                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_PROCESS_RESTARTED_EXCEPTION,
                    mVoiceInteractionServiceUid);
            notifyOnDetectorRemoteException();
        }

        // Restart listening from microphone if the hotword process has been restarted.
        if (mPerformingSoftwareHotwordDetection) {
            Slog.i(TAG, "Process restarted: calling startRecognition() again");
            startListeningFromMicLocked();
        }

        mPerformingExternalSourceHotwordDetection = false;
        closeExternalAudioStreamLocked("process restarted");
    }

    @SuppressWarnings("GuardedBy")
    public void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
        pw.print(prefix); pw.print("mPerformingSoftwareHotwordDetection=");
        pw.println(mPerformingSoftwareHotwordDetection);
    }
}
