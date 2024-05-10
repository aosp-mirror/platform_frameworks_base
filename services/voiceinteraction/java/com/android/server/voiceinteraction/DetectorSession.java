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

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.LOG_COMPAT_CHANGE;
import static android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG;
import static android.Manifest.permission.RECEIVE_SANDBOXED_DETECTION_TRAINING_DATA;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.service.attention.AttentionService.PROXIMITY_UNKNOWN;
import static android.service.voice.HotwordDetectionService.AUDIO_SOURCE_EXTERNAL;
import static android.service.voice.HotwordDetectionService.ENABLE_PROXIMITY_RESULT;
import static android.service.voice.HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS;
import static android.service.voice.HotwordDetectionService.INITIALIZATION_STATUS_UNKNOWN;
import static android.service.voice.HotwordDetectionService.KEY_INITIALIZATION_STATUS;
import static android.service.voice.HotwordDetectionServiceFailure.ERROR_CODE_COPY_AUDIO_DATA_FAILURE;
import static android.service.voice.HotwordDetectionServiceFailure.ERROR_CODE_ON_TRAINING_DATA_EGRESS_LIMIT_EXCEEDED;
import static android.service.voice.HotwordDetectionServiceFailure.ERROR_CODE_ON_TRAINING_DATA_SECURITY_EXCEPTION;

import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_ERROR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_NO_VALUE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_OVER_MAX_CUSTOM_VALUE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__APP_REQUEST_UPDATE_STATE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_STATUS_REPORTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_UPDATE_STATE_AFTER_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALL_UPDATE_STATE_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_DETECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_DETECT_SECURITY_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_REJECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_UPDATE_STATE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__START_EXTERNAL_SOURCE_DETECTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_SECURITY_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_UNEXPECTED_CALLBACK;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECT_UNEXPECTED_CALLBACK;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__TRAINING_DATA;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__TRAINING_DATA_EGRESS_LIMIT_REACHED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__TRAINING_DATA_REMOTE_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__TRAINING_DATA_SECURITY_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_EVENT_EGRESS_SIZE__EVENT_TYPE__HOTWORD_DETECTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_EVENT_EGRESS_SIZE__EVENT_TYPE__HOTWORD_REJECTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_EVENT_EGRESS_SIZE__EVENT_TYPE__HOTWORD_TRAINING_DATA;
import static com.android.server.voiceinteraction.HotwordDetectionConnection.ENFORCE_HOTWORD_PHRASE_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.AppOpsManager;
import android.app.compat.CompatChanges;
import android.attention.AttentionManagerInternal;
import android.content.Context;
import android.content.PermissionChecker;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.media.permission.PermissionUtil;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.voice.HotwordDetectedResult;
import android.service.voice.HotwordDetectionService;
import android.service.voice.HotwordDetectionServiceFailure;
import android.service.voice.HotwordDetector;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.HotwordTrainingData;
import android.service.voice.HotwordTrainingDataLimitEnforcer;
import android.service.voice.IDspHotwordDetectionCallback;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.VisualQueryDetectionServiceFailure;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.policy.AppOpsPolicy;
import com.android.server.voiceinteraction.VoiceInteractionManagerServiceImpl.DetectorRemoteExceptionListener;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class that provides sandboxed detector to communicate with the {@link
 * HotwordDetectionService} and {@link VisualQueryDetectionService}.
 *
 * Trusted hotword detectors such as {@link SoftwareHotwordDetector} and
 * {@link AlwaysOnHotwordDetector} will leverage this class to communitcate with
 * {@link HotwordDetectionService}; similarly, {@link VisualQueryDetector} will communicate with
 * {@link VisualQueryDetectionService}.
 *
 * This class provides the methods to do initialization with the {@link HotwordDetectionService} and
 * {@link VisualQueryDetectionService} handles external source detection for
 * {@link HotwordDetectionService}. It also provides the methods to check if we can egress the data
 * from the {@link HotwordDetectionService} and {@link VisualQueryDetectionService}.
 *
 * The subclass should override the {@link #informRestartProcessLocked()} to handle the trusted
 * process restart.
 */
abstract class DetectorSession {
    private static final String TAG = "DetectorSession";
    static final boolean DEBUG = false;

    private static final String HOTWORD_DETECTION_OP_MESSAGE =
            "Providing hotword detection result to VoiceInteractionService";

    private static final String HOTWORD_TRAINING_DATA_OP_MESSAGE =
            "Providing hotword training data to VoiceInteractionService";

    // The error codes are used for onHotwordDetectionServiceFailure callback.
    // Define these due to lines longer than 100 characters.
    static final int ONDETECTED_GOT_SECURITY_EXCEPTION =
            HotwordDetectionServiceFailure.ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION;
    static final int ONDETECTED_STREAM_COPY_ERROR =
            HotwordDetectionServiceFailure.ERROR_CODE_ON_DETECTED_STREAM_COPY_FAILURE;

    // TODO: These constants need to be refined.
    private static final long MAX_UPDATE_TIMEOUT_MILLIS = 30000;
    private static final long EXTERNAL_HOTWORD_CLEANUP_MILLIS = 2000;
    private static final Duration MAX_UPDATE_TIMEOUT_DURATION =
            Duration.ofMillis(MAX_UPDATE_TIMEOUT_MILLIS);

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

    static final int METRICS_KEYPHRASE_TRIGGERED_DETECT_SECURITY_EXCEPTION =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_SECURITY_EXCEPTION;
    static final int METRICS_KEYPHRASE_TRIGGERED_DETECT_UNEXPECTED_CALLBACK =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_UNEXPECTED_CALLBACK;
    static final int METRICS_KEYPHRASE_TRIGGERED_REJECT_UNEXPECTED_CALLBACK =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECT_UNEXPECTED_CALLBACK;

    private static final int METRICS_EXTERNAL_SOURCE_DETECTED =
            HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_DETECTED;
    private static final int METRICS_EXTERNAL_SOURCE_REJECTED =
            HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_REJECTED;
    private static final int EXTERNAL_SOURCE_DETECT_SECURITY_EXCEPTION =
            HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_DETECT_SECURITY_EXCEPTION;
    private static final int METRICS_CALLBACK_ON_STATUS_REPORTED_EXCEPTION =
            HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_STATUS_REPORTED_EXCEPTION;

    private static final int HOTWORD_EVENT_TYPE_DETECTION =
            HOTWORD_EVENT_EGRESS_SIZE__EVENT_TYPE__HOTWORD_DETECTION;
    private static final int HOTWORD_EVENT_TYPE_REJECTION =
            HOTWORD_EVENT_EGRESS_SIZE__EVENT_TYPE__HOTWORD_REJECTION;
    private static final int HOTWORD_EVENT_TYPE_TRAINING_DATA =
            HOTWORD_EVENT_EGRESS_SIZE__EVENT_TYPE__HOTWORD_TRAINING_DATA;

    private final Executor mAudioCopyExecutor = Executors.newCachedThreadPool();
    // TODO: This may need to be a Handler(looper)
    final ScheduledExecutorService mScheduledExecutorService;
    private final AppOpsManager mAppOpsManager;
    final HotwordAudioStreamCopier mHotwordAudioStreamCopier;
    final AtomicBoolean mUpdateStateAfterStartFinished = new AtomicBoolean(false);
    final IHotwordRecognitionStatusCallback mCallback;

    final Object mLock;
    final int mVoiceInteractionServiceUid;
    final Context mContext;

    @Nullable AttentionManagerInternal mAttentionManagerInternal = null;

    final AttentionManagerInternal.ProximityUpdateCallbackInternal mProximityCallbackInternal =
            this::setProximityValue;

    /** Identity used for attributing app ops when delivering data to the Interactor. */
    @Nullable
    private final Identity mVoiceInteractorIdentity;
    @GuardedBy("mLock")
    ParcelFileDescriptor mCurrentAudioSink;
    @GuardedBy("mLock")
    @NonNull HotwordDetectionConnection.ServiceConnection mRemoteDetectionService;
    boolean mDebugHotwordLogging = false;
    @GuardedBy("mLock")
    private double mProximityMeters = PROXIMITY_UNKNOWN;
    @GuardedBy("mLock")
    private boolean mInitialized = false;
    @GuardedBy("mLock")
    private boolean mDestroyed = false;
    @GuardedBy("mLock")
    boolean mPerformingExternalSourceHotwordDetection;
    @NonNull final IBinder mToken;

    @NonNull DetectorRemoteExceptionListener mRemoteExceptionListener;

    DetectorSession(
            @NonNull HotwordDetectionConnection.ServiceConnection remoteDetectionService,
            @NonNull Object lock, @NonNull Context context, @NonNull IBinder token,
            @NonNull IHotwordRecognitionStatusCallback callback, int voiceInteractionServiceUid,
            Identity voiceInteractorIdentity,
            @NonNull ScheduledExecutorService scheduledExecutorService, boolean logging,
            @NonNull DetectorRemoteExceptionListener listener) {
        mRemoteExceptionListener = listener;
        mRemoteDetectionService = remoteDetectionService;
        mLock = lock;
        mContext = context;
        mToken = token;
        mCallback = callback;
        mVoiceInteractionServiceUid = voiceInteractionServiceUid;
        mVoiceInteractorIdentity = voiceInteractorIdentity;
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        if (getDetectorType() != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
            mHotwordAudioStreamCopier = new HotwordAudioStreamCopier(mAppOpsManager,
                    getDetectorType(),
                    mVoiceInteractorIdentity.uid, mVoiceInteractorIdentity.packageName,
                    mVoiceInteractorIdentity.attributionTag);
        } else {
            mHotwordAudioStreamCopier = null;
        }

        mScheduledExecutorService = scheduledExecutorService;
        mDebugHotwordLogging = logging;

        if (ENABLE_PROXIMITY_RESULT) {
            mAttentionManagerInternal = LocalServices.getService(AttentionManagerInternal.class);
            if (mAttentionManagerInternal != null
                    && mAttentionManagerInternal.isProximitySupported()) {
                mAttentionManagerInternal.onStartProximityUpdates(mProximityCallbackInternal);
            }
        }
    }

    void notifyOnDetectorRemoteException() {
        Slog.d(TAG, "notifyOnDetectorRemoteException: mRemoteExceptionListener="
                + mRemoteExceptionListener);
        if (mRemoteExceptionListener != null) {
            mRemoteExceptionListener.onDetectorRemoteException(mToken, getDetectorType());
        }
    }

    @SuppressWarnings("GuardedBy")
    private void updateStateAfterProcessStartLocked(PersistableBundle options,
            SharedMemory sharedMemory) {
        if (DEBUG) {
            Slog.d(TAG, "updateStateAfterProcessStartLocked");
        }
        AndroidFuture<Void> voidFuture = mRemoteDetectionService.postAsync(service -> {
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
                        if (getDetectorType()
                                != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                            HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_UPDATE_STATE_AFTER_TIMEOUT,
                                mVoiceInteractionServiceUid);
                        }
                        return;
                    }
                    Pair<Integer, Integer> statusResultPair = getInitStatusAndMetricsResult(bundle);
                    int status = statusResultPair.first;
                    int initResultMetricsResult = statusResultPair.second;
                    try {
                        mCallback.onStatusReported(status);
                        if (getDetectorType()
                                != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                            HotwordMetricsLogger.writeServiceInitResultEvent(getDetectorType(),
                                    initResultMetricsResult, mVoiceInteractionServiceUid);
                        }
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to report initialization status: " + e);
                        if (getDetectorType()
                                != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                            HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                    METRICS_CALLBACK_ON_STATUS_REPORTED_EXCEPTION,
                                    mVoiceInteractionServiceUid);
                        }
                        notifyOnDetectorRemoteException();
                    }
                }
            };
            try {
                service.updateState(options, sharedMemory, statusCallback);
                if (getDetectorType() != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                    HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                            HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_UPDATE_STATE,
                            mVoiceInteractionServiceUid);
                }
            } catch (RemoteException e) {
                // TODO: (b/181842909) Report an error to voice interactor
                Slog.w(TAG, "Failed to updateState for HotwordDetectionService", e);
                if (getDetectorType() != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                    HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                            HOTWORD_DETECTOR_EVENTS__EVENT__CALL_UPDATE_STATE_EXCEPTION,
                            mVoiceInteractionServiceUid);
                }
            }
            return future.orTimeout(MAX_UPDATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }).whenComplete((res, err) -> {
            if (err instanceof TimeoutException) {
                Slog.w(TAG, "updateState timed out");
                if (mUpdateStateAfterStartFinished.getAndSet(true)) {
                    return;
                }
                try {
                    mCallback.onStatusReported(INITIALIZATION_STATUS_UNKNOWN);
                    if (getDetectorType() != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                        HotwordMetricsLogger.writeServiceInitResultEvent(getDetectorType(),
                                METRICS_INIT_UNKNOWN_TIMEOUT, mVoiceInteractionServiceUid);
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to report initialization status UNKNOWN", e);
                    if (getDetectorType() != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                METRICS_CALLBACK_ON_STATUS_REPORTED_EXCEPTION,
                                mVoiceInteractionServiceUid);
                    }
                    notifyOnDetectorRemoteException();
                }
            } else if (err != null) {
                Slog.w(TAG, "Failed to update state: " + err);
            }
        });
        if (voidFuture == null) {
            Slog.w(TAG, "Failed to create AndroidFuture");
        }
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

    @SuppressWarnings("GuardedBy")
    void updateStateLocked(PersistableBundle options, SharedMemory sharedMemory,
            Instant lastRestartInstant) {
        if (getDetectorType() != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
            HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                    HOTWORD_DETECTOR_EVENTS__EVENT__APP_REQUEST_UPDATE_STATE,
                    mVoiceInteractionServiceUid);
        }
        // Prevent doing the init late, so restart is handled equally to a clean process start.
        // TODO(b/191742511): this logic needs a test
        if (!mUpdateStateAfterStartFinished.get() && Instant.now().minus(
                MAX_UPDATE_TIMEOUT_DURATION).isBefore(lastRestartInstant)) {
            Slog.v(TAG, "call updateStateAfterProcessStartLocked");
            updateStateAfterProcessStartLocked(options, sharedMemory);
        } else {
            mRemoteDetectionService.run(
                    service -> service.updateState(options, sharedMemory, /* callback= */ null));
        }
    }

    void startListeningFromExternalSourceLocked(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromExternalSourceLocked");
        }

        handleExternalSourceHotwordDetectionLocked(
                audioStream,
                audioFormat,
                options,
                callback);
    }

    @SuppressWarnings("GuardedBy")
    private void handleExternalSourceHotwordDetectionLocked(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "#handleExternalSourceHotwordDetectionLocked");
        }
        if (mPerformingExternalSourceHotwordDetection) {
            Slog.i(TAG, "Hotword validation is already in progress for external source.");
            return;
        }

        InputStream audioSource = new ParcelFileDescriptor.AutoCloseInputStream(audioStream);

        Pair<ParcelFileDescriptor, ParcelFileDescriptor> clientPipe = createPipe();
        if (clientPipe == null) {
            // TODO: Need to propagate as unknown error or something?
            return;
        }
        ParcelFileDescriptor serviceAudioSink = clientPipe.second;
        ParcelFileDescriptor serviceAudioSource = clientPipe.first;

        mCurrentAudioSink = serviceAudioSink;
        mPerformingExternalSourceHotwordDetection = true;

        mAudioCopyExecutor.execute(() -> {
            try (InputStream source = audioSource;
                 OutputStream fos =
                         new ParcelFileDescriptor.AutoCloseOutputStream(serviceAudioSink)) {

                byte[] buffer = new byte[1024];
                while (true) {
                    int bytesRead = source.read(buffer, 0, 1024);

                    if (bytesRead < 0) {
                        Slog.i(TAG, "Reached end of stream for external hotword");
                        break;
                    }

                    // TODO: First write to ring buffer to make sure we don't lose data if the next
                    // statement fails.
                    // ringBuffer.append(buffer, bytesRead);
                    fos.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed supplying audio data to validator", e);

                try {
                    callback.onHotwordDetectionServiceFailure(
                            new HotwordDetectionServiceFailure(ERROR_CODE_COPY_AUDIO_DATA_FAILURE,
                                    "Copy audio data failure for external source detection."));
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to report onHotwordDetectionServiceFailure status: " + ex);
                    if (getDetectorType() != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                                mVoiceInteractionServiceUid);
                    }
                    notifyOnDetectorRemoteException();
                }
            } finally {
                synchronized (mLock) {
                    mPerformingExternalSourceHotwordDetection = false;
                    closeExternalAudioStreamLocked("start external source");
                }
            }
        });

        // TODO: handle cancellations well
        // TODO: what if we cancelled and started a new one?
        mRemoteDetectionService.run(
                service -> {
                    service.detectFromMicrophoneSource(
                            serviceAudioSource,
                            // TODO: consider making a proxy callback + copy of audio format
                            AUDIO_SOURCE_EXTERNAL,
                            audioFormat,
                            options,
                            new IDspHotwordDetectionCallback.Stub() {
                                @Override
                                public void onRejected(HotwordRejectedResult result)
                                        throws RemoteException {
                                    synchronized (mLock) {
                                        mPerformingExternalSourceHotwordDetection = false;
                                        HotwordMetricsLogger.writeDetectorEvent(
                                                getDetectorType(),
                                                METRICS_EXTERNAL_SOURCE_REJECTED,
                                                mVoiceInteractionServiceUid);
                                        mScheduledExecutorService.schedule(
                                                () -> {
                                                    bestEffortClose(serviceAudioSink, audioSource);
                                                },
                                                EXTERNAL_HOTWORD_CLEANUP_MILLIS,
                                                TimeUnit.MILLISECONDS);

                                        try {
                                            callback.onRejected(result);
                                        } catch (RemoteException e) {
                                            notifyOnDetectorRemoteException();
                                            throw e;
                                        }
                                        if (result != null) {
                                            Slog.i(TAG, "Egressed 'hotword rejected result' "
                                                    + "from hotword trusted process");
                                            logEgressSizeStats(result);
                                            if (mDebugHotwordLogging) {
                                                Slog.i(TAG, "Egressed detected result: " + result);
                                            }
                                        }
                                    }
                                }

                                @Override
                                public void onTrainingData(HotwordTrainingData data)
                                        throws RemoteException {
                                    sendTrainingData(new TrainingDataEgressCallback() {
                                        @Override
                                        public void onHotwordDetectionServiceFailure(
                                                HotwordDetectionServiceFailure failure)
                                                throws RemoteException {
                                            callback.onHotwordDetectionServiceFailure(failure);
                                        }

                                        @Override
                                        public void onTrainingData(HotwordTrainingData data)
                                                throws RemoteException {
                                            callback.onTrainingData(data);
                                        }
                                    }, data);
                                }

                                @Override
                                public void onDetected(HotwordDetectedResult triggerResult)
                                        throws RemoteException {
                                    synchronized (mLock) {
                                        mPerformingExternalSourceHotwordDetection = false;
                                        HotwordMetricsLogger.writeDetectorEvent(
                                                getDetectorType(),
                                                METRICS_EXTERNAL_SOURCE_DETECTED,
                                                mVoiceInteractionServiceUid);
                                        mScheduledExecutorService.schedule(
                                                () -> {
                                                    bestEffortClose(serviceAudioSink, audioSource);
                                                },
                                                EXTERNAL_HOTWORD_CLEANUP_MILLIS,
                                                TimeUnit.MILLISECONDS);

                                        try {
                                            enforcePermissionsForDataDelivery();
                                        } catch (SecurityException e) {
                                            Slog.w(TAG, "Ignoring #onDetected due to a "
                                                    + "SecurityException", e);
                                            HotwordMetricsLogger.writeDetectorEvent(
                                                    getDetectorType(),
                                                    EXTERNAL_SOURCE_DETECT_SECURITY_EXCEPTION,
                                                    mVoiceInteractionServiceUid);
                                            try {
                                                callback.onHotwordDetectionServiceFailure(
                                                        new HotwordDetectionServiceFailure(
                                                                ONDETECTED_GOT_SECURITY_EXCEPTION,
                                                                "Security exception occurs in "
                                                                        + "#onDetected method"));
                                            } catch (RemoteException e1) {
                                                notifyOnDetectorRemoteException();
                                                throw e1;
                                            }
                                            return;
                                        }
                                        HotwordDetectedResult newResult;
                                        try {
                                            newResult = mHotwordAudioStreamCopier
                                                    .startCopyingAudioStreams(triggerResult);
                                        } catch (IOException e) {
                                            Slog.w(TAG, "Ignoring #onDetected due to a "
                                                    + "IOException", e);
                                            // TODO: Write event
                                            try {
                                                callback.onHotwordDetectionServiceFailure(
                                                        new HotwordDetectionServiceFailure(
                                                                ONDETECTED_STREAM_COPY_ERROR,
                                                                "Copy audio stream failure."));
                                            } catch (RemoteException e1) {
                                                notifyOnDetectorRemoteException();
                                                throw e1;
                                            }
                                            return;
                                        }
                                        try {
                                            callback.onDetected(newResult, /* audioFormat= */ null,
                                                    /* audioStream= */ null);
                                        } catch (RemoteException e) {
                                            notifyOnDetectorRemoteException();
                                            throw e;
                                        }
                                        Slog.i(TAG, "Egressed "
                                                + HotwordDetectedResult.getUsageSize(newResult)
                                                + " bits from hotword trusted process");
                                        logEgressSizeStats(newResult);
                                        if (mDebugHotwordLogging) {
                                            Slog.i(TAG,
                                                    "Egressed detected result: " + newResult);
                                        }
                                    }
                                }
                            });

                    // A copy of this has been created and passed to the hotword validator
                    bestEffortClose(serviceAudioSource);
                });
        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                HOTWORD_DETECTOR_EVENTS__EVENT__START_EXTERNAL_SOURCE_DETECTION,
                mVoiceInteractionServiceUid);
    }

    void logEgressSizeStats(HotwordTrainingData data) {
        logEgressSizeStats(data, HOTWORD_EVENT_TYPE_TRAINING_DATA);
    }

    void logEgressSizeStats(HotwordDetectedResult data) {
        logEgressSizeStats(data, HOTWORD_EVENT_TYPE_DETECTION);

    }

    void logEgressSizeStats(HotwordRejectedResult data) {
        logEgressSizeStats(data, HOTWORD_EVENT_TYPE_REJECTION);
    }

    /** Logs event size stats for events egressed from trusted hotword detection service. */
    private void logEgressSizeStats(Parcelable data, int eventType) {
        BackgroundThread.getExecutor().execute(() -> {
            Parcel parcel = Parcel.obtain();
            parcel.writeValue(data);
            int dataSizeBytes = parcel.dataSize();
            parcel.recycle();

            HotwordMetricsLogger.writeHotwordDataEgressSize(eventType, dataSizeBytes,
                    getDetectorType(), mVoiceInteractionServiceUid);
        });
    }

    /** Used to send training data.
     *
     * @hide
     */
    interface TrainingDataEgressCallback {
        /** Called to send training data */
        void onTrainingData(HotwordTrainingData trainingData) throws RemoteException;

        /** Called to inform failure to send training data. */
        void onHotwordDetectionServiceFailure(HotwordDetectionServiceFailure failure) throws
                RemoteException;

    }

    /** Default implementation to send training data from {@link HotwordDetectionService}
     *  to {@link HotwordDetector}.
     *
     * <p> Verifies RECEIVE_SANDBOXED_DETECTION_TRAINING_DATA permission has been
     * granted and training data egress is within daily limit.
     *
     * @param callback used to send training data or inform of failures to send training data.
     * @param data training data to egress.
     *
     * @hide
     */
    void sendTrainingData(
            TrainingDataEgressCallback callback, HotwordTrainingData data) throws RemoteException {
        Slog.d(TAG, "onTrainingData()");
        int detectorType = getDetectorType();
        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                detectorType,
                HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__TRAINING_DATA,
                mVoiceInteractionServiceUid);

        // Check training data permission is granted.
        try {
            enforcePermissionForTrainingDataDelivery();
        } catch (SecurityException e) {
            Slog.w(TAG, "Ignoring training data due to a SecurityException", e);
            HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                    detectorType,
                    HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__TRAINING_DATA_SECURITY_EXCEPTION,
                    mVoiceInteractionServiceUid);
            try {
                callback.onHotwordDetectionServiceFailure(
                        new HotwordDetectionServiceFailure(
                                ERROR_CODE_ON_TRAINING_DATA_SECURITY_EXCEPTION,
                                "Security exception occurred"
                                        + "in #onTrainingData method."));
            } catch (RemoteException e1) {
                notifyOnDetectorRemoteException();
                HotwordMetricsLogger.writeDetectorEvent(
                        detectorType,
                        HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                        mVoiceInteractionServiceUid);
                throw e1;
            }
            return;
        }

        // Check whether within daily egress limit.
        boolean withinEgressLimit = HotwordTrainingDataLimitEnforcer.getInstance(mContext)
                                                                    .incrementEgressCount();
        if (!withinEgressLimit) {
            Slog.d(TAG, "Ignoring training data as exceeded egress limit.");
            HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                    detectorType,
                    HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__TRAINING_DATA_EGRESS_LIMIT_REACHED,
                    mVoiceInteractionServiceUid);
            try {
                callback.onHotwordDetectionServiceFailure(
                        new HotwordDetectionServiceFailure(
                                ERROR_CODE_ON_TRAINING_DATA_EGRESS_LIMIT_EXCEEDED,
                                "Training data egress limit exceeded."));
            } catch (RemoteException e) {
                notifyOnDetectorRemoteException();
                HotwordMetricsLogger.writeDetectorEvent(
                        detectorType,
                        HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                        mVoiceInteractionServiceUid);
                throw e;
            }
            return;
        }

        try {
            Slog.i(TAG, "Egressing training data from hotword trusted process.");
            if (mDebugHotwordLogging) {
                Slog.d(TAG, "Egressing hotword training data " + data);
            }
            callback.onTrainingData(data);
        } catch (RemoteException e) {
            notifyOnDetectorRemoteException();
            HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                    detectorType,
                    HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__TRAINING_DATA_REMOTE_EXCEPTION,
                    mVoiceInteractionServiceUid);
            throw e;
        }
        logEgressSizeStats(data);
    }

    void initialize(@Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory) {
        synchronized (mLock) {
            if (mInitialized || mDestroyed) {
                return;
            }
            updateStateAfterProcessStartLocked(options, sharedMemory);
            mInitialized = true;
        }
    }

    @SuppressWarnings("GuardedBy")
    void destroyLocked() {
        mDestroyed = true;
        mDebugHotwordLogging = false;
        mRemoteDetectionService = null;
        mRemoteExceptionListener = null;
        if (mAttentionManagerInternal != null) {
            mAttentionManagerInternal.onStopProximityUpdates(mProximityCallbackInternal);
        }
    }

    void setDebugHotwordLoggingLocked(boolean logging) {
        Slog.v(TAG, "setDebugHotwordLoggingLocked: " + logging);
        mDebugHotwordLogging = logging;
    }

    @SuppressWarnings("GuardedBy")
    void updateRemoteSandboxedDetectionServiceLocked(
            @NonNull HotwordDetectionConnection.ServiceConnection remoteDetectionService) {
        mRemoteDetectionService = remoteDetectionService;
    }

    private void reportErrorGetRemoteException() {
        if (getDetectorType() != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
            HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                    mVoiceInteractionServiceUid);
        }
        notifyOnDetectorRemoteException();
    }

    void reportErrorLocked(@NonNull HotwordDetectionServiceFailure hotwordDetectionServiceFailure) {
        try {
            mCallback.onHotwordDetectionServiceFailure(hotwordDetectionServiceFailure);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to call onHotwordDetectionServiceFailure: " + e);
            reportErrorGetRemoteException();
        }
    }

    void reportErrorLocked(
            @NonNull VisualQueryDetectionServiceFailure visualQueryDetectionServiceFailure) {
        try {
            mCallback.onVisualQueryDetectionServiceFailure(visualQueryDetectionServiceFailure);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to call onVisualQueryDetectionServiceFailure: " + e);
            reportErrorGetRemoteException();
        }
    }

    void reportErrorLocked(@NonNull String errorMessage) {
        try {
            mCallback.onUnknownFailure(errorMessage);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to call onUnknownFailure: " + e);
            reportErrorGetRemoteException();
        }
    }

    /**
     * Called when the trusted process is restarted.
     */
    abstract void informRestartProcessLocked();

    boolean isSameCallback(@Nullable IHotwordRecognitionStatusCallback callback) {
        synchronized (mLock) {
            if (callback == null) {
                return false;
            }
            return mCallback.asBinder().equals(callback.asBinder());
        }
    }

    boolean isSameToken(@NonNull IBinder token) {
        synchronized (mLock) {
            if (token == null) {
                return false;
            }
            return mToken == token;
        }
    }

    boolean isDestroyed() {
        synchronized (mLock) {
            return mDestroyed;
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

    void saveProximityValueToBundle(HotwordDetectedResult result) {
        synchronized (mLock) {
            if (result != null && mProximityMeters != PROXIMITY_UNKNOWN) {
                result.setProximity(mProximityMeters);
            }
        }
    }

    private void setProximityValue(double proximityMeters) {
        synchronized (mLock) {
            mProximityMeters = proximityMeters;
        }
    }

    @SuppressWarnings("GuardedBy")
    void closeExternalAudioStreamLocked(String reason) {
        if (mCurrentAudioSink != null) {
            Slog.i(TAG, "Closing external audio stream to hotword detector: " + reason);
            bestEffortClose(mCurrentAudioSink);
            mCurrentAudioSink = null;
        }
    }

    private static void bestEffortClose(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            bestEffortClose(closeable);
        }
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
    void enforcePermissionsForDataDelivery() {
        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                if (AppOpsPolicy.isHotwordDetectionServiceRequired(mContext.getPackageManager())) {
                    int result = PermissionChecker.checkPermissionForPreflight(
                            mContext, RECORD_AUDIO, /* pid */ -1, mVoiceInteractorIdentity.uid,
                            mVoiceInteractorIdentity.packageName);
                    if (result != PermissionChecker.PERMISSION_GRANTED) {
                        throw new SecurityException(
                                "Failed to obtain permission RECORD_AUDIO for identity "
                                        + mVoiceInteractorIdentity);
                    }
                    int opMode = mAppOpsManager.unsafeCheckOpNoThrow(
                            AppOpsManager.opToPublicName(AppOpsPolicy.getVoiceActivationOp()),
                            mVoiceInteractorIdentity.uid,
                            mVoiceInteractorIdentity.packageName);
                    if (opMode == MODE_DEFAULT || opMode == MODE_ALLOWED) {
                        mAppOpsManager.noteOpNoThrow(
                                AppOpsPolicy.getVoiceActivationOp(),
                                mVoiceInteractorIdentity.uid, mVoiceInteractorIdentity.packageName,
                                mVoiceInteractorIdentity.attributionTag,
                                HOTWORD_DETECTION_OP_MESSAGE);
                    } else {
                        throw new SecurityException(
                                "The app op OP_RECEIVE_SANDBOX_TRIGGER_AUDIO is denied for "
                                        + "identity" + mVoiceInteractorIdentity);
                    }
                } else {
                    enforcePermissionForDataDelivery(mContext, mVoiceInteractorIdentity,
                            RECORD_AUDIO, HOTWORD_DETECTION_OP_MESSAGE);
                }
                enforcePermissionForDataDelivery(mContext, mVoiceInteractorIdentity,
                        CAPTURE_AUDIO_HOTWORD, HOTWORD_DETECTION_OP_MESSAGE);
            }
        });
    }

    /**
     * Enforces permission for training data delivery.
     *
     * <p> Throws a {@link SecurityException} if training data egress permission is not granted.
     */
    void enforcePermissionForTrainingDataDelivery() {
        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                enforcePermissionForDataDelivery(mContext, mVoiceInteractorIdentity,
                        RECEIVE_SANDBOXED_DETECTION_TRAINING_DATA,
                        HOTWORD_TRAINING_DATA_OP_MESSAGE);

                mAppOpsManager.noteOpNoThrow(
                        AppOpsManager.OP_RECEIVE_SANDBOXED_DETECTION_TRAINING_DATA,
                        mVoiceInteractorIdentity.uid, mVoiceInteractorIdentity.packageName,
                        mVoiceInteractorIdentity.attributionTag,
                        HOTWORD_TRAINING_DATA_OP_MESSAGE);
            }
        });
    }

    /**
     * Throws a {@link SecurityException} if the given identity has no permission to receive data.
     *
     * @param context    A {@link Context}, used for permission checks.
     * @param identity   The identity to check.
     * @param permission The identifier of the permission we want to check.
     * @param reason     The reason why we're requesting the permission, for auditing purposes.
     */
    private static void enforcePermissionForDataDelivery(@NonNull Context context,
            @NonNull Identity identity, @NonNull String permission, @NonNull String reason) {
        final int status = PermissionUtil.checkPermissionForDataDelivery(context, identity,
                permission, reason);
        if (status != PermissionChecker.PERMISSION_GRANTED) {
            throw new SecurityException(
                    TextUtils.formatSimple("Failed to obtain permission %s for identity %s",
                            permission,
                            identity));
        }
    }

    @RequiresPermission(allOf = {READ_COMPAT_CHANGE_CONFIG, LOG_COMPAT_CHANGE})
    void enforceExtraKeyphraseIdNotLeaked(HotwordDetectedResult result,
            SoundTrigger.KeyphraseRecognitionEvent recognitionEvent) {
        if (!CompatChanges.isChangeEnabled(ENFORCE_HOTWORD_PHRASE_ID,
                mVoiceInteractionServiceUid)) {
            return;
        }
        // verify the phrase ID in HotwordDetectedResult is not exposing extra phrases
        // the DSP did not detect
        for (SoundTrigger.KeyphraseRecognitionExtra keyphrase : recognitionEvent.keyphraseExtras) {
            if (keyphrase.getKeyphraseId() == result.getHotwordPhraseId()) {
                return;
            }
        }
        throw new SecurityException("Ignoring #onDetected due to trusted service "
                + "sharing a keyphrase ID which the DSP did not detect");
    }

    private int getDetectorType() {
        if (this instanceof DspTrustedHotwordDetectorSession) {
            return HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP;
        } else if (this instanceof SoftwareTrustedHotwordDetectorSession) {
            return HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE;
        } else if (this instanceof VisualQueryDetectorSession) {
            return HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR;
        }
        Slog.v(TAG, "Unexpected detector type");
        return -1;
    }

    @SuppressWarnings("GuardedBy")
    public void dumpLocked(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mCallback="); pw.println(mCallback);
        pw.print(prefix); pw.print("mUpdateStateAfterStartFinished=");
        pw.println(mUpdateStateAfterStartFinished);
        pw.print(prefix); pw.print("mInitialized="); pw.println(mInitialized);
        pw.print(prefix); pw.print("mDestroyed="); pw.println(mDestroyed);
        pw.print(prefix); pw.print("DetectorType=");
        pw.println(HotwordDetector.detectorTypeToString(getDetectorType()));
        pw.print(prefix); pw.print("mPerformingExternalSourceHotwordDetection=");
        pw.println(mPerformingExternalSourceHotwordDetection);
    }
}
