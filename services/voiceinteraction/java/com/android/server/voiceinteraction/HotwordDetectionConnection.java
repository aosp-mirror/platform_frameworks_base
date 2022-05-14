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

package com.android.server.voiceinteraction;

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.service.voice.HotwordDetectionService.AUDIO_SOURCE_EXTERNAL;
import static android.service.voice.HotwordDetectionService.AUDIO_SOURCE_MICROPHONE;
import static android.service.voice.HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS;
import static android.service.voice.HotwordDetectionService.INITIALIZATION_STATUS_UNKNOWN;
import static android.service.voice.HotwordDetectionService.KEY_INITIALIZATION_STATUS;

import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_ERROR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_NO_VALUE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_OVER_MAX_CUSTOM_VALUE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_RESTARTED__REASON__AUDIO_SERVICE_DIED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_RESTARTED__REASON__SCHEDULE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_UPDATE_STATE_AFTER_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__ON_CONNECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE_FAIL;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_UPDATE_STATE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__NORMAL_DETECTOR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_SECURITY_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_UNEXPECTED_CALLBACK;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__KEYPHRASE_TRIGGER;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED_FROM_RESTART;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECT_UNEXPECTED_CALLBACK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.Intent;
import android.content.PermissionChecker;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.media.AudioManagerInternal;
import android.media.permission.Identity;
import android.media.permission.PermissionUtil;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SharedMemory;
import android.provider.DeviceConfig;
import android.service.voice.HotwordDetectedResult;
import android.service.voice.HotwordDetectionService;
import android.service.voice.HotwordDetector;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.IDspHotwordDetectionCallback;
import android.service.voice.IHotwordDetectionService;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.VoiceInteractionManagerInternal.HotwordDetectionServiceIdentity;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.view.contentcapture.IContentCaptureManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.server.LocalServices;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * A class that provides the communication with the HotwordDetectionService.
 */
final class HotwordDetectionConnection {
    private static final String TAG = "HotwordDetectionConnection";
    static final boolean DEBUG = false;

    private static final String KEY_RESTART_PERIOD_IN_SECONDS = "restart_period_in_seconds";
    // TODO: These constants need to be refined.
    private static final long VALIDATION_TIMEOUT_MILLIS = 4000;
    private static final long MAX_UPDATE_TIMEOUT_MILLIS = 30000;
    private static final Duration MAX_UPDATE_TIMEOUT_DURATION =
            Duration.ofMillis(MAX_UPDATE_TIMEOUT_MILLIS);
    private static final long RESET_DEBUG_HOTWORD_LOGGING_TIMEOUT_MILLIS = 60 * 60 * 1000; // 1 hour
    private static final int MAX_ISOLATED_PROCESS_NUMBER = 10;

    // The error codes are used for onError callback
    private static final int HOTWORD_DETECTION_SERVICE_DIED = -1;
    private static final int CALLBACK_ONDETECTED_GOT_SECURITY_EXCEPTION = -2;

    // Hotword metrics
    private static final int METRICS_INIT_UNKNOWN_TIMEOUT =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_TIMEOUT;
    private static final int METRICS_INIT_UNKNOWN_NO_VALUE =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_NO_VALUE;
    private static final int METRICS_INIT_UNKNOWN_OVER_MAX_CUSTOM_VALUE =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_OVER_MAX_CUSTOM_VALUE;
    private static final int METRICS_INIT_CALLBACK_STATE_ERROR =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_ERROR;
    private static final int METRICS_INIT_CALLBACK_STATE_SUCCESS =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_SUCCESS;

    private static final int METRICS_KEYPHRASE_TRIGGERED_DETECT_SECURITY_EXCEPTION =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_SECURITY_EXCEPTION;
    private static final int METRICS_KEYPHRASE_TRIGGERED_DETECT_UNEXPECTED_CALLBACK =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_UNEXPECTED_CALLBACK;
    private static final int METRICS_KEYPHRASE_TRIGGERED_REJECT_UNEXPECTED_CALLBACK =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECT_UNEXPECTED_CALLBACK;

    private final Executor mAudioCopyExecutor = Executors.newCachedThreadPool();
    // TODO: This may need to be a Handler(looper)
    private final ScheduledExecutorService mScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor();
    @Nullable private final ScheduledFuture<?> mCancellationTaskFuture;
    private final AtomicBoolean mUpdateStateAfterStartFinished = new AtomicBoolean(false);
    private final IBinder.DeathRecipient mAudioServerDeathRecipient = this::audioServerDied;
    private final @NonNull ServiceConnectionFactory mServiceConnectionFactory;
    private final IHotwordRecognitionStatusCallback mCallback;
    private final int mDetectorType;
    /**
     * Time after which each HotwordDetectionService process is stopped and replaced by a new one.
     * 0 indicates no restarts.
     */
    private final int mReStartPeriodSeconds;

    final Object mLock;
    final int mVoiceInteractionServiceUid;
    final ComponentName mDetectionComponentName;
    final int mUser;
    final Context mContext;

    volatile HotwordDetectionServiceIdentity mIdentity;
    private IMicrophoneHotwordDetectionVoiceInteractionCallback mSoftwareCallback;
    private Instant mLastRestartInstant;

    private ScheduledFuture<?> mCancellationKeyPhraseDetectionFuture;
    private ScheduledFuture<?> mDebugHotwordLoggingTimeoutFuture = null;

    /** Identity used for attributing app ops when delivering data to the Interactor. */
    @GuardedBy("mLock")
    @Nullable
    private final Identity mVoiceInteractorIdentity;
    @GuardedBy("mLock")
    private ParcelFileDescriptor mCurrentAudioSink;
    @GuardedBy("mLock")
    private boolean mValidatingDspTrigger = false;
    @GuardedBy("mLock")
    private boolean mPerformingSoftwareHotwordDetection;
    private @NonNull ServiceConnection mRemoteHotwordDetectionService;
    private IBinder mAudioFlinger;
    private boolean mDebugHotwordLogging = false;

    HotwordDetectionConnection(Object lock, Context context, int voiceInteractionServiceUid,
            Identity voiceInteractorIdentity, ComponentName serviceName, int userId,
            boolean bindInstantServiceAllowed, @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull IHotwordRecognitionStatusCallback callback, int detectorType) {
        if (callback == null) {
            Slog.w(TAG, "Callback is null while creating connection");
            throw new IllegalArgumentException("Callback is null while creating connection");
        }
        mLock = lock;
        mContext = context;
        mVoiceInteractionServiceUid = voiceInteractionServiceUid;
        mVoiceInteractorIdentity = voiceInteractorIdentity;
        mDetectionComponentName = serviceName;
        mUser = userId;
        mCallback = callback;
        mDetectorType = detectorType;
        mReStartPeriodSeconds = DeviceConfig.getInt(DeviceConfig.NAMESPACE_VOICE_INTERACTION,
                KEY_RESTART_PERIOD_IN_SECONDS, 0);
        final Intent intent = new Intent(HotwordDetectionService.SERVICE_INTERFACE);
        intent.setComponent(mDetectionComponentName);
        initAudioFlingerLocked();

        mServiceConnectionFactory = new ServiceConnectionFactory(intent, bindInstantServiceAllowed);

        mRemoteHotwordDetectionService = mServiceConnectionFactory.createLocked();

        mLastRestartInstant = Instant.now();
        updateStateAfterProcessStart(options, sharedMemory);

        if (mReStartPeriodSeconds <= 0) {
            mCancellationTaskFuture = null;
        } else {
            // TODO: we need to be smarter here, e.g. schedule it a bit more often,
            //  but wait until the current session is closed.
            mCancellationTaskFuture = mScheduledExecutorService.scheduleAtFixedRate(() -> {
                Slog.v(TAG, "Time to restart the process, TTL has passed");
                synchronized (mLock) {
                    restartProcessLocked();
                    HotwordMetricsLogger.writeServiceRestartEvent(mDetectorType,
                            HOTWORD_DETECTION_SERVICE_RESTARTED__REASON__SCHEDULE);
                }
            }, mReStartPeriodSeconds, mReStartPeriodSeconds, TimeUnit.SECONDS);
        }
    }

    private void initAudioFlingerLocked() {
        if (DEBUG) {
            Slog.d(TAG, "initAudioFlingerLocked");
        }
        mAudioFlinger = ServiceManager.waitForService("media.audio_flinger");
        if (mAudioFlinger == null) {
            throw new IllegalStateException("Service media.audio_flinger wasn't found.");
        }
        if (DEBUG) {
            Slog.d(TAG, "Obtained audio_flinger binder.");
        }
        try {
            mAudioFlinger.linkToDeath(mAudioServerDeathRecipient, /* flags= */ 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Audio server died before we registered a DeathRecipient; retrying init.",
                    e);
            initAudioFlingerLocked();
        }
    }

    private void audioServerDied() {
        Slog.w(TAG, "Audio server died; restarting the HotwordDetectionService.");
        synchronized (mLock) {
            // TODO: Check if this needs to be scheduled on a different thread.
            initAudioFlingerLocked();
            // We restart the process instead of simply sending over the new binder, to avoid race
            // conditions with audio reading in the service.
            restartProcessLocked();
            HotwordMetricsLogger.writeServiceRestartEvent(mDetectorType,
                    HOTWORD_DETECTION_SERVICE_RESTARTED__REASON__AUDIO_SERVICE_DIED);
        }
    }

    private void updateStateAfterProcessStart(
            PersistableBundle options, SharedMemory sharedMemory) {
        if (DEBUG) {
            Slog.d(TAG, "updateStateAfterProcessStart");
        }
        mRemoteHotwordDetectionService.postAsync(service -> {
            AndroidFuture<Void> future = new AndroidFuture<>();
            IRemoteCallback statusCallback = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle bundle) throws RemoteException {
                    if (DEBUG) {
                        Slog.d(TAG, "updateState finish");
                    }
                    future.complete(null);
                    if (mUpdateStateAfterStartFinished.getAndSet(true)) {
                        Slog.w(TAG, "call callback after timeout");
                        HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                                HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_UPDATE_STATE_AFTER_TIMEOUT,
                                mVoiceInteractionServiceUid);
                        return;
                    }
                    Pair<Integer, Integer> statusResultPair = getInitStatusAndMetricsResult(bundle);
                    int status = statusResultPair.first;
                    int initResultMetricsResult = statusResultPair.second;
                    try {
                        mCallback.onStatusReported(status);
                        HotwordMetricsLogger.writeServiceInitResultEvent(mDetectorType,
                                initResultMetricsResult);
                    } catch (RemoteException e) {
                        // TODO: Add a new atom for RemoteException case, the error doesn't very
                        // correct here
                        Slog.w(TAG, "Failed to report initialization status: " + e);
                        HotwordMetricsLogger.writeServiceInitResultEvent(mDetectorType,
                                METRICS_INIT_CALLBACK_STATE_ERROR);
                    }
                }
            };
            try {
                service.updateState(options, sharedMemory, statusCallback);
                HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                        HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_UPDATE_STATE,
                        mVoiceInteractionServiceUid);
            } catch (RemoteException e) {
                // TODO: (b/181842909) Report an error to voice interactor
                Slog.w(TAG, "Failed to updateState for HotwordDetectionService", e);
            }
            return future;
        }).orTimeout(MAX_UPDATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((res, err) -> {
                    if (err instanceof TimeoutException) {
                        Slog.w(TAG, "updateState timed out");
                        if (mUpdateStateAfterStartFinished.getAndSet(true)) {
                            return;
                        }
                        try {
                            mCallback.onStatusReported(INITIALIZATION_STATUS_UNKNOWN);
                            HotwordMetricsLogger.writeServiceInitResultEvent(mDetectorType,
                                    METRICS_INIT_UNKNOWN_TIMEOUT);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to report initialization status UNKNOWN", e);
                            HotwordMetricsLogger.writeServiceInitResultEvent(mDetectorType,
                                    METRICS_INIT_CALLBACK_STATE_ERROR);
                        }
                    } else if (err != null) {
                        Slog.w(TAG, "Failed to update state: " + err);
                    } else {
                        // NOTE: so far we don't need to take any action.
                    }
                });
    }

    private static Pair<Integer, Integer> getInitStatusAndMetricsResult(Bundle bundle) {
        if (bundle == null) {
            return new Pair<>(INITIALIZATION_STATUS_UNKNOWN, METRICS_INIT_UNKNOWN_NO_VALUE);
        }
        int status = bundle.getInt(KEY_INITIALIZATION_STATUS, INITIALIZATION_STATUS_UNKNOWN);
        if (status > HotwordDetectionService.getMaxCustomInitializationStatus()) {
            return new Pair<>(INITIALIZATION_STATUS_UNKNOWN,
                    status == INITIALIZATION_STATUS_UNKNOWN
                    ? METRICS_INIT_UNKNOWN_NO_VALUE
                    : METRICS_INIT_UNKNOWN_OVER_MAX_CUSTOM_VALUE);
        }
        // TODO: should guard against negative here
        int metricsResult = status == INITIALIZATION_STATUS_SUCCESS
                ? METRICS_INIT_CALLBACK_STATE_SUCCESS
                : METRICS_INIT_CALLBACK_STATE_ERROR;
        return new Pair<>(status, metricsResult);
    }

    private boolean isBound() {
        synchronized (mLock) {
            return mRemoteHotwordDetectionService.isBound();
        }
    }

    void cancelLocked() {
        Slog.v(TAG, "cancelLocked");
        clearDebugHotwordLoggingTimeoutLocked();
        mDebugHotwordLogging = false;
        mRemoteHotwordDetectionService.unbind();
        LocalServices.getService(PermissionManagerServiceInternal.class)
                .setHotwordDetectionServiceProvider(null);
        if (mIdentity != null) {
            removeServiceUidForAudioPolicy(mIdentity.getIsolatedUid());
        }
        mIdentity = null;
        if (mCancellationTaskFuture != null) {
            mCancellationTaskFuture.cancel(/* may interrupt */ true);
        }
        if (mAudioFlinger != null) {
            mAudioFlinger.unlinkToDeath(mAudioServerDeathRecipient, /* flags= */ 0);
        }
    }

    void updateStateLocked(PersistableBundle options, SharedMemory sharedMemory) {
        // Prevent doing the init late, so restart is handled equally to a clean process start.
        // TODO(b/191742511): this logic needs a test
        if (!mUpdateStateAfterStartFinished.get()
                && Instant.now().minus(MAX_UPDATE_TIMEOUT_DURATION).isBefore(mLastRestartInstant)) {
            Slog.v(TAG, "call updateStateAfterProcessStart");
            updateStateAfterProcessStart(options, sharedMemory);
        } else {
            mRemoteHotwordDetectionService.run(
                    service -> service.updateState(options, sharedMemory, null /* callback */));
        }
    }

    void startListeningFromMic(
            AudioFormat audioFormat,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromMic");
        }
        mSoftwareCallback = callback;

        synchronized (mLock) {
            if (mPerformingSoftwareHotwordDetection) {
                Slog.i(TAG, "Hotword validation is already in progress, ignoring.");
                return;
            }
            mPerformingSoftwareHotwordDetection = true;

            startListeningFromMicLocked();
        }
    }

    private void startListeningFromMicLocked() {
        // TODO: consider making this a non-anonymous class.
        IDspHotwordDetectionCallback internalCallback = new IDspHotwordDetectionCallback.Stub() {
            @Override
            public void onDetected(HotwordDetectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onDetected");
                }
                synchronized (mLock) {
                    if (!mPerformingSoftwareHotwordDetection) {
                        Slog.i(TAG, "Hotword detection has already completed");
                        return;
                    }
                    mPerformingSoftwareHotwordDetection = false;
                    try {
                        enforcePermissionsForDataDelivery();
                    } catch (SecurityException e) {
                        mSoftwareCallback.onError();
                        return;
                    }
                    mSoftwareCallback.onDetected(result, null, null);
                    if (result != null) {
                        Slog.i(TAG, "Egressed " + HotwordDetectedResult.getUsageSize(result)
                                + " bits from hotword trusted process");
                        if (mDebugHotwordLogging) {
                            Slog.i(TAG, "Egressed detected result: " + result);
                        }
                    }
                }
            }

            @Override
            public void onRejected(HotwordRejectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.wtf(TAG, "onRejected");
                }
                // onRejected isn't allowed here, and we are not expecting it.
            }
        };

        mRemoteHotwordDetectionService.run(
                service -> service.detectFromMicrophoneSource(
                        null,
                        AUDIO_SOURCE_MICROPHONE,
                        null,
                        null,
                        internalCallback));
    }

    public void startListeningFromExternalSource(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromExternalSource");
        }

        handleExternalSourceHotwordDetection(
                audioStream,
                audioFormat,
                options,
                callback);
    }

    void stopListening() {
        if (DEBUG) {
            Slog.d(TAG, "stopListening");
        }
        synchronized (mLock) {
            stopListeningLocked();
        }
    }

    private void stopListeningLocked() {
        if (!mPerformingSoftwareHotwordDetection) {
            Slog.i(TAG, "Hotword detection is not running");
            return;
        }
        mPerformingSoftwareHotwordDetection = false;

        mRemoteHotwordDetectionService.run(IHotwordDetectionService::stopDetection);

        if (mCurrentAudioSink != null) {
            Slog.i(TAG, "Closing audio stream to hotword detector: stopping requested");
            bestEffortClose(mCurrentAudioSink);
        }
        mCurrentAudioSink = null;
    }

    void triggerHardwareRecognitionEventForTestLocked(
            SoundTrigger.KeyphraseRecognitionEvent event,
            IHotwordRecognitionStatusCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "triggerHardwareRecognitionEventForTestLocked");
        }
        detectFromDspSourceForTest(event, callback);
    }

    private void detectFromDspSourceForTest(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
            IHotwordRecognitionStatusCallback externalCallback) {
        Slog.v(TAG, "detectFromDspSourceForTest");
        IDspHotwordDetectionCallback internalCallback = new IDspHotwordDetectionCallback.Stub() {
            @Override
            public void onDetected(HotwordDetectedResult result) throws RemoteException {
                Slog.v(TAG, "onDetected");
                synchronized (mLock) {
                    if (!mValidatingDspTrigger) {
                        Slog.i(TAG, "Ignored hotword detected since trigger has been handled");
                        return;
                    }
                    mValidatingDspTrigger = false;
                    try {
                        enforcePermissionsForDataDelivery();
                    } catch (SecurityException e) {
                        externalCallback.onError(CALLBACK_ONDETECTED_GOT_SECURITY_EXCEPTION);
                        return;
                    }
                    externalCallback.onKeyphraseDetected(recognitionEvent, result);
                    if (result != null) {
                        Slog.i(TAG, "Egressed " + HotwordDetectedResult.getUsageSize(result)
                                + " bits from hotword trusted process");
                        if (mDebugHotwordLogging) {
                            Slog.i(TAG, "Egressed detected result: " + result);
                        }
                    }
                }
            }

            @Override
            public void onRejected(HotwordRejectedResult result) throws RemoteException {
                Slog.v(TAG, "onRejected");
                synchronized (mLock) {
                    if (mValidatingDspTrigger) {
                        mValidatingDspTrigger = false;
                        externalCallback.onRejected(result);
                        if (mDebugHotwordLogging && result != null) {
                            Slog.i(TAG, "Egressed rejected result: " + result);
                        }
                    } else {
                        Slog.i(TAG, "Ignored hotword rejected since trigger has been handled");
                    }
                }
            }
        };

        synchronized (mLock) {
            mValidatingDspTrigger = true;
            mRemoteHotwordDetectionService.run(
                    service -> service.detectFromDspSource(
                            recognitionEvent,
                            recognitionEvent.getCaptureFormat(),
                            VALIDATION_TIMEOUT_MILLIS,
                            internalCallback));
        }
    }

    private void detectFromDspSource(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
            IHotwordRecognitionStatusCallback externalCallback) {
        if (DEBUG) {
            Slog.d(TAG, "detectFromDspSource");
        }

        // TODO: consider making this a non-anonymous class.
        IDspHotwordDetectionCallback internalCallback = new IDspHotwordDetectionCallback.Stub() {
            @Override
            public void onDetected(HotwordDetectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onDetected");
                }
                synchronized (mLock) {
                    // TODO: If the dsp trigger comes in after the timeout, we will log both events.
                    // Because we don't enforce the timeout yet. We should add some synchronizations
                    // within the runnable to prevent the race condition to log both events.
                    if (mCancellationKeyPhraseDetectionFuture != null) {
                        mCancellationKeyPhraseDetectionFuture.cancel(true);
                    }
                    HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                            mDetectorType,
                            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED);
                    if (!mValidatingDspTrigger) {
                        Slog.i(TAG, "Ignoring #onDetected due to a process restart");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                mDetectorType,
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_UNEXPECTED_CALLBACK);
                        return;
                    }
                    mValidatingDspTrigger = false;
                    try {
                        enforcePermissionsForDataDelivery();
                    } catch (SecurityException e) {
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                mDetectorType,
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_SECURITY_EXCEPTION);
                        externalCallback.onError(CALLBACK_ONDETECTED_GOT_SECURITY_EXCEPTION);
                        return;
                    }
                    externalCallback.onKeyphraseDetected(recognitionEvent, result);
                    if (result != null) {
                        Slog.i(TAG, "Egressed " + HotwordDetectedResult.getUsageSize(result)
                                + " bits from hotword trusted process");
                        if (mDebugHotwordLogging) {
                            Slog.i(TAG, "Egressed detected result: " + result);
                        }
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
                    HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                            mDetectorType,
                            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED);
                    if (!mValidatingDspTrigger) {
                        Slog.i(TAG, "Ignoring #onRejected due to a process restart");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                mDetectorType,
                                METRICS_KEYPHRASE_TRIGGERED_REJECT_UNEXPECTED_CALLBACK);
                        return;
                    }
                    mValidatingDspTrigger = false;
                    externalCallback.onRejected(result);
                    if (mDebugHotwordLogging && result != null) {
                        Slog.i(TAG, "Egressed rejected result: " + result);
                    }
                }
            }
        };

        synchronized (mLock) {
            mValidatingDspTrigger = true;
            mRemoteHotwordDetectionService.run(
                    service -> {
                        // TODO: avoid allocate every time
                        mCancellationKeyPhraseDetectionFuture = mScheduledExecutorService.schedule(
                                () -> HotwordMetricsLogger
                                        .writeKeyphraseTriggerEvent(mDetectorType,
                                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_TIMEOUT),
                                VALIDATION_TIMEOUT_MILLIS,
                                TimeUnit.MILLISECONDS);
                        service.detectFromDspSource(
                                recognitionEvent,
                                recognitionEvent.getCaptureFormat(),
                                VALIDATION_TIMEOUT_MILLIS,
                                internalCallback);
                    });
        }
    }

    void forceRestart() {
        Slog.v(TAG, "Requested to restart the service internally. Performing the restart");
        synchronized (mLock) {
            restartProcessLocked();
        }
    }

    void setDebugHotwordLoggingLocked(boolean logging) {
        Slog.v(TAG, "setDebugHotwordLoggingLocked: " + logging);
        clearDebugHotwordLoggingTimeoutLocked();
        mDebugHotwordLogging = logging;

        if (logging) {
            // Reset mDebugHotwordLogging to false after one hour
            mDebugHotwordLoggingTimeoutFuture = mScheduledExecutorService.schedule(() -> {
                Slog.v(TAG, "Timeout to reset mDebugHotwordLogging to false");
                synchronized (mLock) {
                    mDebugHotwordLogging = false;
                }
            }, RESET_DEBUG_HOTWORD_LOGGING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void clearDebugHotwordLoggingTimeoutLocked() {
        if (mDebugHotwordLoggingTimeoutFuture != null) {
            mDebugHotwordLoggingTimeoutFuture.cancel(/* mayInterruptIfRunning= */true);
            mDebugHotwordLoggingTimeoutFuture = null;
        }
    }

    private void restartProcessLocked() {
        Slog.v(TAG, "Restarting hotword detection process");
        ServiceConnection oldConnection = mRemoteHotwordDetectionService;
        HotwordDetectionServiceIdentity previousIdentity = mIdentity;

        // TODO(volnov): this can be done after connect() has been successful.
        if (mValidatingDspTrigger) {
            // We're restarting the process while it's processing a DSP trigger, so report a
            // rejection. This also allows the Interactor to startReco again
            try {
                mCallback.onRejected(new HotwordRejectedResult.Builder().build());
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        mDetectorType,
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED_FROM_RESTART);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call #rejected");
            }
            mValidatingDspTrigger = false;
        }

        mUpdateStateAfterStartFinished.set(false);
        mLastRestartInstant = Instant.now();

        // Recreate connection to reset the cache.
        mRemoteHotwordDetectionService = mServiceConnectionFactory.createLocked();

        Slog.v(TAG, "Started the new process, issuing #onProcessRestarted");
        try {
            mCallback.onProcessRestarted();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to communicate #onProcessRestarted", e);
        }

        // Restart listening from microphone if the hotword process has been restarted.
        if (mPerformingSoftwareHotwordDetection) {
            Slog.i(TAG, "Process restarted: calling startRecognition() again");
            startListeningFromMicLocked();
        }

        if (mCurrentAudioSink != null) {
            Slog.i(TAG, "Closing external audio stream to hotword detector: process restarted");
            bestEffortClose(mCurrentAudioSink);
            mCurrentAudioSink = null;
        }

        if (DEBUG) {
            Slog.i(TAG, "#onProcessRestarted called, unbinding from the old process");
        }
        oldConnection.ignoreConnectionStatusEvents();
        oldConnection.unbind();
        if (previousIdentity != null) {
            removeServiceUidForAudioPolicy(previousIdentity.getIsolatedUid());
        }
    }

    static final class SoundTriggerCallback extends IRecognitionStatusCallback.Stub {
        private SoundTrigger.KeyphraseRecognitionEvent mRecognitionEvent;
        private final HotwordDetectionConnection mHotwordDetectionConnection;
        private final IHotwordRecognitionStatusCallback mExternalCallback;

        SoundTriggerCallback(IHotwordRecognitionStatusCallback callback,
                HotwordDetectionConnection connection) {
            mHotwordDetectionConnection = connection;
            mExternalCallback = callback;
        }

        @Override
        public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent)
                throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "onKeyphraseDetected recognitionEvent : " + recognitionEvent);
            }
            final boolean useHotwordDetectionService = mHotwordDetectionConnection != null;
            if (useHotwordDetectionService) {
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP,
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__KEYPHRASE_TRIGGER);
                mRecognitionEvent = recognitionEvent;
                mHotwordDetectionConnection.detectFromDspSource(
                        recognitionEvent, mExternalCallback);
            } else {
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__NORMAL_DETECTOR,
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__KEYPHRASE_TRIGGER);
                mExternalCallback.onKeyphraseDetected(recognitionEvent, null);
            }
        }

        @Override
        public void onGenericSoundTriggerDetected(
                SoundTrigger.GenericRecognitionEvent recognitionEvent)
                throws RemoteException {
            mExternalCallback.onGenericSoundTriggerDetected(recognitionEvent);
        }

        @Override
        public void onError(int status) throws RemoteException {
            mExternalCallback.onError(status);
        }

        @Override
        public void onRecognitionPaused() throws RemoteException {
            mExternalCallback.onRecognitionPaused();
        }

        @Override
        public void onRecognitionResumed() throws RemoteException {
            mExternalCallback.onRecognitionResumed();
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mReStartPeriodSeconds="); pw.println(mReStartPeriodSeconds);
        pw.print(prefix);
        pw.print("mBound=" + mRemoteHotwordDetectionService.isBound());
        pw.print(", mValidatingDspTrigger=" + mValidatingDspTrigger);
        pw.print(", mPerformingSoftwareHotwordDetection=" + mPerformingSoftwareHotwordDetection);
        pw.print(", mRestartCount=" + mServiceConnectionFactory.mRestartCount);
        pw.print(", mLastRestartInstant=" + mLastRestartInstant);
        pw.println(", mDetectorType=" + HotwordDetector.detectorTypeToString(mDetectorType));
    }

    private void handleExternalSourceHotwordDetection(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "#handleExternalSourceHotwordDetection");
        }
        InputStream audioSource = new ParcelFileDescriptor.AutoCloseInputStream(audioStream);

        Pair<ParcelFileDescriptor, ParcelFileDescriptor> clientPipe = createPipe();
        if (clientPipe == null) {
            // TODO: Need to propagate as unknown error or something?
            return;
        }
        ParcelFileDescriptor serviceAudioSink = clientPipe.second;
        ParcelFileDescriptor serviceAudioSource = clientPipe.first;

        synchronized (mLock) {
            mCurrentAudioSink = serviceAudioSink;
        }

        mAudioCopyExecutor.execute(() -> {
            try (InputStream source = audioSource;
                 OutputStream fos =
                         new ParcelFileDescriptor.AutoCloseOutputStream(serviceAudioSink)) {

                byte[] buffer = new byte[1024];
                while (true) {
                    int bytesRead = source.read(buffer, 0, 1024);

                    if (bytesRead < 0) {
                        break;
                    }

                    // TODO: First write to ring buffer to make sure we don't lose data if the next
                    // statement fails.
                    // ringBuffer.append(buffer, bytesRead);
                    fos.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed supplying audio data to validator", e);
            } finally {
                synchronized (mLock) {
                    mCurrentAudioSink = null;
                }
            }
        });

        // TODO: handle cancellations well
        // TODO: what if we cancelled and started a new one?
        mRemoteHotwordDetectionService.run(
                service -> service.detectFromMicrophoneSource(
                        serviceAudioSource,
                        // TODO: consider making a proxy callback + copy of audio format
                        AUDIO_SOURCE_EXTERNAL,
                        audioFormat,
                        options,
                        new IDspHotwordDetectionCallback.Stub() {
                            @Override
                            public void onRejected(HotwordRejectedResult result)
                                    throws RemoteException {
                                bestEffortClose(serviceAudioSink);
                                bestEffortClose(serviceAudioSource);
                                bestEffortClose(audioSource);

                                if (mDebugHotwordLogging && result != null) {
                                    Slog.i(TAG, "Egressed rejected result: " + result);
                                }
                                // TODO: Propagate the HotwordRejectedResult.
                            }

                            @Override
                            public void onDetected(HotwordDetectedResult triggerResult)
                                    throws RemoteException {
                                bestEffortClose(serviceAudioSink);
                                bestEffortClose(serviceAudioSource);
                                try {
                                    enforcePermissionsForDataDelivery();
                                } catch (SecurityException e) {
                                    bestEffortClose(audioSource);
                                    callback.onError();
                                    return;
                                }
                                callback.onDetected(triggerResult, null /* audioFormat */,
                                        null /* audioStream */);
                                if (triggerResult != null) {
                                    Slog.i(TAG, "Egressed " + HotwordDetectedResult.getUsageSize(
                                            triggerResult) + " bits from hotword trusted process");
                                    if (mDebugHotwordLogging) {
                                        Slog.i(TAG, "Egressed detected result: " + triggerResult);
                                    }
                                }
                                // TODO: Add a delay before closing.
                                bestEffortClose(audioSource);
                            }
                        }));
    }

    private class ServiceConnectionFactory {
        private final Intent mIntent;
        private final int mBindingFlags;

        private int mRestartCount = 0;

        ServiceConnectionFactory(@NonNull Intent intent, boolean bindInstantServiceAllowed) {
            mIntent = intent;
            mBindingFlags = bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0;
        }

        ServiceConnection createLocked() {
            ServiceConnection connection =
                    new ServiceConnection(mContext, mIntent, mBindingFlags, mUser,
                            IHotwordDetectionService.Stub::asInterface,
                            mRestartCount++ % MAX_ISOLATED_PROCESS_NUMBER);
            connection.connect();

            updateAudioFlinger(connection, mAudioFlinger);
            updateContentCaptureManager(connection);
            updateServiceIdentity(connection);
            return connection;
        }
    }

    private class ServiceConnection extends ServiceConnector.Impl<IHotwordDetectionService> {
        private final Object mLock = new Object();

        private final Intent mIntent;
        private final int mBindingFlags;
        private final int mInstanceNumber;

        private boolean mRespectServiceConnectionStatusChanged = true;
        private boolean mIsBound = false;
        private boolean mIsLoggedFirstConnect = false;

        ServiceConnection(@NonNull Context context,
                @NonNull Intent intent, int bindingFlags, int userId,
                @Nullable Function<IBinder, IHotwordDetectionService> binderAsInterface,
                int instanceNumber) {
            super(context, intent, bindingFlags, userId, binderAsInterface);
            this.mIntent = intent;
            this.mBindingFlags = bindingFlags;
            this.mInstanceNumber = instanceNumber;
        }

        @Override // from ServiceConnector.Impl
        protected void onServiceConnectionStatusChanged(IHotwordDetectionService service,
                boolean connected) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceConnectionStatusChanged connected = " + connected);
            }
            synchronized (mLock) {
                if (!mRespectServiceConnectionStatusChanged) {
                    Slog.v(TAG, "Ignored onServiceConnectionStatusChanged event");
                    return;
                }
                mIsBound = connected;
                if (connected && !mIsLoggedFirstConnect) {
                    mIsLoggedFirstConnect = true;
                    HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                            HOTWORD_DETECTOR_EVENTS__EVENT__ON_CONNECTED,
                            mVoiceInteractionServiceUid);
                }
            }
        }

        @Override
        protected long getAutoDisconnectTimeoutMs() {
            return -1;
        }

        @Override
        public void binderDied() {
            super.binderDied();
            synchronized (mLock) {
                if (!mRespectServiceConnectionStatusChanged) {
                    Slog.v(TAG, "Ignored #binderDied event");
                    return;
                }

                Slog.w(TAG, "binderDied");
                try {
                    mCallback.onError(HOTWORD_DETECTION_SERVICE_DIED);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to report onError status: " + e);
                }
            }
        }

        @Override
        protected boolean bindService(
                @NonNull android.content.ServiceConnection serviceConnection) {
            try {
                HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                        HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE,
                        mVoiceInteractionServiceUid);
                boolean bindResult = mContext.bindIsolatedService(
                        mIntent,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE | mBindingFlags,
                        "hotword_detector_" + mInstanceNumber,
                        mExecutor,
                        serviceConnection);
                if (!bindResult) {
                    HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                            HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE_FAIL,
                            mVoiceInteractionServiceUid);
                }
                return bindResult;
            } catch (IllegalArgumentException e) {
                HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                        HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE_FAIL,
                        mVoiceInteractionServiceUid);
                Slog.wtf(TAG, "Can't bind to the hotword detection service!", e);
                return false;
            }
        }

        boolean isBound() {
            synchronized (mLock) {
                return mIsBound;
            }
        }

        void ignoreConnectionStatusEvents() {
            synchronized (mLock) {
                mRespectServiceConnectionStatusChanged = false;
            }
        }
    }

    private static Pair<ParcelFileDescriptor, ParcelFileDescriptor> createPipe() {
        ParcelFileDescriptor[] fileDescriptors;
        try {
            fileDescriptors = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to create audio stream pipe", e);
            return null;
        }

        return Pair.create(fileDescriptors[0], fileDescriptors[1]);
    }

    private static void updateAudioFlinger(ServiceConnection connection, IBinder audioFlinger) {
        // TODO: Consider using a proxy that limits the exposed API surface.
        connection.run(service -> service.updateAudioFlinger(audioFlinger));
    }

    private static void updateContentCaptureManager(ServiceConnection connection) {
        IBinder b = ServiceManager
                .getService(Context.CONTENT_CAPTURE_MANAGER_SERVICE);
        IContentCaptureManager binderService = IContentCaptureManager.Stub.asInterface(b);
        connection.run(
                service -> service.updateContentCaptureManager(binderService,
                        new ContentCaptureOptions(null)));
    }

    private void updateServiceIdentity(ServiceConnection connection) {
        connection.run(service -> service.ping(new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle bundle) throws RemoteException {
                // TODO: Exit if the service has been unbound already (though there's a very low
                // chance this happens).
                if (DEBUG) {
                    Slog.d(TAG, "updating hotword UID " + Binder.getCallingUid());
                }
                // TODO: Have the provider point to the current state stored in
                // VoiceInteractionManagerServiceImpl.
                final int uid = Binder.getCallingUid();
                LocalServices.getService(PermissionManagerServiceInternal.class)
                        .setHotwordDetectionServiceProvider(() -> uid);
                mIdentity = new HotwordDetectionServiceIdentity(uid, mVoiceInteractionServiceUid);
                addServiceUidForAudioPolicy(uid);
            }
        }));
    }

    private void addServiceUidForAudioPolicy(int uid) {
        mScheduledExecutorService.execute(() -> {
            AudioManagerInternal audioManager =
                    LocalServices.getService(AudioManagerInternal.class);
            if (audioManager != null) {
                audioManager.addAssistantServiceUid(uid);
            }
        });
    }

    private void removeServiceUidForAudioPolicy(int uid) {
        mScheduledExecutorService.execute(() -> {
            AudioManagerInternal audioManager =
                    LocalServices.getService(AudioManagerInternal.class);
            if (audioManager != null) {
                audioManager.removeAssistantServiceUid(uid);
            }
        });
    }

    private static void bestEffortClose(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            if (DEBUG) {
                Slog.w(TAG, "Failed closing", e);
            }
        }
    }

    // TODO: Share this code with SoundTriggerMiddlewarePermission.
    private void enforcePermissionsForDataDelivery() {
        Binder.withCleanCallingIdentity(() -> {
            // Hack to make sure we show the mic privacy-indicator since the Trusted Hotword
            // requirement isn't being enforced for now. Normally, we would note the HOTWORD op here
            // instead.
            enforcePermissionForDataDelivery(mContext, mVoiceInteractorIdentity,
                    RECORD_AUDIO, OP_MESSAGE);

            enforcePermissionForDataDelivery(mContext, mVoiceInteractorIdentity,
                    CAPTURE_AUDIO_HOTWORD, OP_MESSAGE);
        });
    }

    /**
     * Throws a {@link SecurityException} iff the given identity has given permission to receive
     * data.
     *
     * @param context    A {@link Context}, used for permission checks.
     * @param identity   The identity to check.
     * @param permission The identifier of the permission we want to check.
     * @param reason     The reason why we're requesting the permission, for auditing purposes.
     */
    private static void enforcePermissionForDataDelivery(@NonNull Context context,
            @NonNull Identity identity,
            @NonNull String permission, @NonNull String reason) {
        final int status = PermissionUtil.checkPermissionForDataDelivery(context, identity,
                permission, reason);
        if (status != PermissionChecker.PERMISSION_GRANTED) {
            throw new SecurityException(
                    TextUtils.formatSimple("Failed to obtain permission %s for identity %s",
                            permission,
                            SoundTriggerSessionPermissionsDecorator.toString(identity)));
        }
    }

    private static final String OP_MESSAGE =
            "Providing hotword detection result to VoiceInteractionService";
};
