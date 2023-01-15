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

import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_RESTARTED__REASON__AUDIO_SERVICE_DIED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_RESTARTED__REASON__SCHEDULE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__ON_CONNECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__ON_DISCONNECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE_FAIL;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__NORMAL_DETECTOR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__KEYPHRASE_TRIGGER;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__SERVICE_CRASH;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.media.AudioManagerInternal;
import android.media.permission.Identity;
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
import android.service.voice.HotwordDetectionService;
import android.service.voice.HotwordDetector;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.ISandboxedDetectionService;
import android.service.voice.IVisualQueryDetectionVoiceInteractionCallback;
import android.service.voice.VisualQueryDetectionService;
import android.service.voice.VoiceInteractionManagerInternal.HotwordDetectionServiceIdentity;
import android.speech.IRecognitionServiceManager;
import android.util.Slog;
import android.util.SparseArray;
import android.view.contentcapture.IContentCaptureManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.infra.ServiceConnector;
import com.android.server.LocalServices;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A class that provides the communication with the {@link HotwordDetectionService} and
 * {@link VisualQueryDetectionService}.
 */
final class HotwordDetectionConnection {
    private static final String TAG = "HotwordDetectionConnection";
    static final boolean DEBUG = false;

    private static final String KEY_RESTART_PERIOD_IN_SECONDS = "restart_period_in_seconds";
    private static final long RESET_DEBUG_HOTWORD_LOGGING_TIMEOUT_MILLIS = 60 * 60 * 1000; // 1 hour
    private static final int MAX_ISOLATED_PROCESS_NUMBER = 10;

    // TODO: This may need to be a Handler(looper)
    private final ScheduledExecutorService mScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor();
    @Nullable private final ScheduledFuture<?> mCancellationTaskFuture;
    private final IBinder.DeathRecipient mAudioServerDeathRecipient = this::audioServerDied;
    @NonNull private final ServiceConnectionFactory mHotwordDetectionServiceConnectionFactory;
    @NonNull private final ServiceConnectionFactory mVisualQueryDetectionServiceConnectionFactory;
    private int mDetectorType;
    /**
     * Time after which each HotwordDetectionService process is stopped and replaced by a new one.
     * 0 indicates no restarts.
     */
    private final int mReStartPeriodSeconds;

    final Object mLock;
    final int mVoiceInteractionServiceUid;
    final ComponentName mHotwordDetectionComponentName;
    final ComponentName mVisualQueryDetectionComponentName;
    final int mUser;
    final Context mContext;
    volatile HotwordDetectionServiceIdentity mIdentity;
    //TODO: add similar identity for visual query service for the use of content capturing
    private Instant mLastRestartInstant;

    private ScheduledFuture<?> mDebugHotwordLoggingTimeoutFuture = null;

    /** Identity used for attributing app ops when delivering data to the Interactor. */
    @GuardedBy("mLock")
    @Nullable
    private final Identity mVoiceInteractorIdentity;
    @NonNull private ServiceConnection mRemoteHotwordDetectionService;
    @NonNull private ServiceConnection mRemoteVisualQueryDetectionService;
    private IBinder mAudioFlinger;
    @GuardedBy("mLock")
    private boolean mDebugHotwordLogging = false;

    /**
     * For multiple detectors feature, we only support one AlwaysOnHotwordDetector and one
     * SoftwareHotwordDetector at the same time. We use SparseArray with detector type as the key
     * to record the detectors.
     */
    @GuardedBy("mLock")
    private final SparseArray<DetectorSession> mDetectorSessions =
            new SparseArray<>();

    HotwordDetectionConnection(Object lock, Context context, int voiceInteractionServiceUid,
            Identity voiceInteractorIdentity, ComponentName hotwordDetectionServiceName,
            ComponentName visualQueryDetectionServiceName, int userId,
            boolean bindInstantServiceAllowed, int detectorType) {
        mLock = lock;
        mContext = context;
        mVoiceInteractionServiceUid = voiceInteractionServiceUid;
        mVoiceInteractorIdentity = voiceInteractorIdentity;
        mHotwordDetectionComponentName = hotwordDetectionServiceName;
        mVisualQueryDetectionComponentName = visualQueryDetectionServiceName;
        mUser = userId;
        mDetectorType = detectorType;
        mReStartPeriodSeconds = DeviceConfig.getInt(DeviceConfig.NAMESPACE_VOICE_INTERACTION,
                KEY_RESTART_PERIOD_IN_SECONDS, 0);

        final Intent hotwordDetectionServiceIntent =
                new Intent(HotwordDetectionService.SERVICE_INTERFACE);
        hotwordDetectionServiceIntent.setComponent(mHotwordDetectionComponentName);

        final Intent visualQueryDetectionServiceIntent =
                new Intent(VisualQueryDetectionService.SERVICE_INTERFACE);
        visualQueryDetectionServiceIntent.setComponent(mVisualQueryDetectionComponentName);

        initAudioFlingerLocked();

        mHotwordDetectionServiceConnectionFactory =
                new ServiceConnectionFactory(hotwordDetectionServiceIntent,
                        bindInstantServiceAllowed);

        mVisualQueryDetectionServiceConnectionFactory =
                new ServiceConnectionFactory(visualQueryDetectionServiceIntent,
                        bindInstantServiceAllowed);


        mLastRestartInstant = Instant.now();

        if (mReStartPeriodSeconds <= 0) {
            mCancellationTaskFuture = null;
        } else {
            // TODO: we need to be smarter here, e.g. schedule it a bit more often,
            //  but wait until the current session is closed.
            mCancellationTaskFuture = mScheduledExecutorService.scheduleAtFixedRate(() -> {
                Slog.v(TAG, "Time to restart the process, TTL has passed");
                synchronized (mLock) {
                    restartProcessLocked();
                    if (mDetectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                        HotwordMetricsLogger.writeServiceRestartEvent(mDetectorType,
                                HOTWORD_DETECTION_SERVICE_RESTARTED__REASON__SCHEDULE,
                                mVoiceInteractionServiceUid);
                    }
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
            Slog.w(TAG, "Audio server died before we registered a DeathRecipient; "
                            + "retrying init.", e);
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
            if (mDetectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                HotwordMetricsLogger.writeServiceRestartEvent(mDetectorType,
                        HOTWORD_DETECTION_SERVICE_RESTARTED__REASON__AUDIO_SERVICE_DIED,
                        mVoiceInteractionServiceUid);
            }
        }
    }

    @SuppressWarnings("GuardedBy")
    void cancelLocked() {
        Slog.v(TAG, "cancelLocked");
        clearDebugHotwordLoggingTimeoutLocked();
        runForEachDetectorSessionLocked((session) -> {
            session.destroyLocked();
        });
        mDetectorSessions.clear();
        mDebugHotwordLogging = false;
        unbindVisualQueryDetectionService();
        unbindHotwordDetectionService();
        if (mIdentity != null) {
            removeServiceUidForAudioPolicy(mIdentity.getIsolatedUid());
        }
        mIdentity = null;
        if (mCancellationTaskFuture != null) {
            mCancellationTaskFuture.cancel(/* mayInterruptIfRunning= */ true);
        }
        if (mAudioFlinger != null) {
            mAudioFlinger.unlinkToDeath(mAudioServerDeathRecipient, /* flags= */ 0);
        }
    }

    private void unbindVisualQueryDetectionService() {
        if (mRemoteVisualQueryDetectionService != null) {
            mRemoteVisualQueryDetectionService.unbind();
            //TODO: Set visual query detection service provider to null
        }
    }

    private void unbindHotwordDetectionService() {
        if (mRemoteHotwordDetectionService != null) {
            mRemoteHotwordDetectionService.unbind();
            LocalServices.getService(PermissionManagerServiceInternal.class)
                .setHotwordDetectionServiceProvider(null);
        }
    }

    @SuppressWarnings("GuardedBy")
    void updateStateLocked(PersistableBundle options, SharedMemory sharedMemory,
            @NonNull IBinder token) {
        final DetectorSession session = getDetectorSessionByTokenLocked(token);
        if (session == null) {
            Slog.v(TAG, "Not found the detector by token");
            return;
        }
        session.updateStateLocked(options, sharedMemory, mLastRestartInstant);
    }

    /**
     * This method is only used by SoftwareHotwordDetector.
     */
    void startListeningFromMicLocked(
            AudioFormat audioFormat,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromMicLocked");
        }
        // We only support one Dsp trusted hotword detector and one software hotword detector at
        // the same time, so we can reuse original single software trusted hotword mechanism.
        final SoftwareTrustedHotwordDetectorSession session =
                getSoftwareTrustedHotwordDetectorSessionLocked();
        if (session == null) {
            return;
        }
        session.startListeningFromMicLocked(audioFormat, callback);
    }

    /**
     * This method is only used by VisualQueryDetector.
     */
    void startPerceivingLocked(IVisualQueryDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startPerceivingLocked");
        }
        final VisualQueryDetectorSession session = getVisualQueryDetectorSessionLocked();
        if (session == null) {
            return;
        }
        session.startPerceivingLocked(callback);
    }

    /**
     * This method is only used by VisaulQueryDetector.
     */
    void stopPerceivingLocked() {
        if (DEBUG) {
            Slog.d(TAG, "stopPerceivingLocked");
        }
        final VisualQueryDetectorSession session = getVisualQueryDetectorSessionLocked();
        if (session == null) {
            return;
        }
        session.stopPerceivingLocked();
    }

    public void startListeningFromExternalSourceLocked(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            @NonNull IBinder token,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromExternalSourceLocked");
        }
        final DetectorSession session = getDetectorSessionByTokenLocked(token);
        if (session == null) {
            Slog.v(TAG, "Not found the detector by token");
            return;
        }
        session.startListeningFromExternalSourceLocked(audioStream, audioFormat, options, callback);
    }

    /**
     * This method is only used by SoftwareHotwordDetector.
     */
    void stopListeningFromMicLocked() {
        if (DEBUG) {
            Slog.d(TAG, "stopListeningFromMicLocked");
        }
        final SoftwareTrustedHotwordDetectorSession session =
                getSoftwareTrustedHotwordDetectorSessionLocked();
        if (session == null) {
            return;
        }
        session.stopListeningFromMicLocked();
    }

    void triggerHardwareRecognitionEventForTestLocked(
            SoundTrigger.KeyphraseRecognitionEvent event,
            IHotwordRecognitionStatusCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "triggerHardwareRecognitionEventForTestLocked");
        }
        detectFromDspSource(event, callback);
    }

    private void detectFromDspSource(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
            IHotwordRecognitionStatusCallback externalCallback) {
        if (DEBUG) {
            Slog.d(TAG, "detectFromDspSource");
        }
        // We only support one Dsp trusted hotword detector and one software hotword detector at
        // the same time, so we can reuse original single Dsp trusted hotword mechanism.
        synchronized (mLock) {
            final DspTrustedHotwordDetectorSession session =
                    getDspTrustedHotwordDetectorSessionLocked();
            if (session == null || !session.isSameCallback(externalCallback)) {
                Slog.v(TAG, "Not found the Dsp detector by callback");
                return;
            }
            session.detectFromDspSourceLocked(recognitionEvent, externalCallback);
        }
    }

    void forceRestart() {
        Slog.v(TAG, "Requested to restart the service internally. Performing the restart");
        synchronized (mLock) {
            restartProcessLocked();
        }
    }

    @SuppressWarnings("GuardedBy")
    void setDebugHotwordLoggingLocked(boolean logging) {
        Slog.v(TAG, "setDebugHotwordLoggingLocked: " + logging);
        clearDebugHotwordLoggingTimeoutLocked();
        mDebugHotwordLogging = logging;
        runForEachDetectorSessionLocked((session) -> {
            session.setDebugHotwordLoggingLocked(logging);
        });

        if (logging) {
            // Reset mDebugHotwordLogging to false after one hour
            mDebugHotwordLoggingTimeoutFuture = mScheduledExecutorService.schedule(() -> {
                Slog.v(TAG, "Timeout to reset mDebugHotwordLogging to false");
                synchronized (mLock) {
                    mDebugHotwordLogging = false;
                    runForEachDetectorSessionLocked((session) -> {
                        session.setDebugHotwordLoggingLocked(false);
                    });
                }
            }, RESET_DEBUG_HOTWORD_LOGGING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    void setDetectorType(int detectorType) {
        mDetectorType = detectorType;
    }

    private void clearDebugHotwordLoggingTimeoutLocked() {
        if (mDebugHotwordLoggingTimeoutFuture != null) {
            mDebugHotwordLoggingTimeoutFuture.cancel(/* mayInterruptIfRunning= */ true);
            mDebugHotwordLoggingTimeoutFuture = null;
        }
    }

    @SuppressWarnings("GuardedBy")
    private void restartProcessLocked() {
        // TODO(b/244598068): Check HotwordAudioStreamManager first
        Slog.v(TAG, "Restarting hotword detection process");

        ServiceConnection oldHotwordConnection = mRemoteHotwordDetectionService;
        ServiceConnection oldVisualQueryDetectionConnection = mRemoteVisualQueryDetectionService;
        HotwordDetectionServiceIdentity previousIdentity = mIdentity;
        //TODO: Add previousIdentity for visual query detection service

        mLastRestartInstant = Instant.now();
        // Recreate connection to reset the cache.

        mRemoteHotwordDetectionService = mHotwordDetectionServiceConnectionFactory.createLocked();
        mRemoteVisualQueryDetectionService =
                mVisualQueryDetectionServiceConnectionFactory.createLocked();

        Slog.v(TAG, "Started the new process, dispatching processRestarted to detector");
        runForEachDetectorSessionLocked((session) -> {
            HotwordDetectionConnection.ServiceConnection newRemoteService =
                    (session instanceof VisualQueryDetectorSession)
                            ? mRemoteVisualQueryDetectionService : mRemoteHotwordDetectionService;
            session.updateRemoteSandboxedDetectionServiceLocked(newRemoteService);
            session.informRestartProcessLocked();
        });
        if (DEBUG) {
            Slog.i(TAG, "processRestarted is dispatched done, unbinding from the old process");
        }

        if (oldHotwordConnection != null) {
            oldHotwordConnection.ignoreConnectionStatusEvents();
            oldHotwordConnection.unbind();
        }

        if (oldVisualQueryDetectionConnection != null) {
            oldVisualQueryDetectionConnection.ignoreConnectionStatusEvents();
            oldVisualQueryDetectionConnection.unbind();
        }

        if (previousIdentity != null) {
            removeServiceUidForAudioPolicy(previousIdentity.getIsolatedUid());
        }
    }

    static final class SoundTriggerCallback extends IRecognitionStatusCallback.Stub {
        private final HotwordDetectionConnection mHotwordDetectionConnection;
        private final IHotwordRecognitionStatusCallback mExternalCallback;
        private final int mVoiceInteractionServiceUid;

        SoundTriggerCallback(IHotwordRecognitionStatusCallback callback,
                HotwordDetectionConnection connection, int uid) {
            mHotwordDetectionConnection = connection;
            mExternalCallback = callback;
            mVoiceInteractionServiceUid = uid;
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
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__KEYPHRASE_TRIGGER,
                        mVoiceInteractionServiceUid);
                mHotwordDetectionConnection.detectFromDspSource(
                        recognitionEvent, mExternalCallback);
            } else {
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__NORMAL_DETECTOR,
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__KEYPHRASE_TRIGGER,
                        mVoiceInteractionServiceUid);
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
        synchronized (mLock) {
            pw.print(prefix); pw.print("mReStartPeriodSeconds="); pw.println(mReStartPeriodSeconds);
            pw.print(prefix); pw.print("mBound=");
            pw.println(mRemoteHotwordDetectionService != null
                    && mRemoteHotwordDetectionService.isBound());
            pw.println(mRemoteVisualQueryDetectionService != null
                    && mRemoteHotwordDetectionService != null
                    && mRemoteHotwordDetectionService.isBound());
            pw.print(prefix); pw.print("mRestartCount=");
            pw.println(mHotwordDetectionServiceConnectionFactory.mRestartCount);
            pw.println(mVisualQueryDetectionServiceConnectionFactory.mRestartCount);
            pw.print(prefix); pw.print("mLastRestartInstant="); pw.println(mLastRestartInstant);
            pw.print(prefix); pw.print("mDetectorType=");
            pw.println(HotwordDetector.detectorTypeToString(mDetectorType));
            pw.print(prefix); pw.println("DetectorSession(s)");
            runForEachDetectorSessionLocked((session) -> {
                session.dumpLocked(prefix, pw);
            });
        }
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
                            ISandboxedDetectionService.Stub::asInterface,
                            mRestartCount++ % MAX_ISOLATED_PROCESS_NUMBER);
            connection.connect();

            updateAudioFlinger(connection, mAudioFlinger);
            updateContentCaptureManager(connection);
            updateSpeechService(connection);
            updateServiceIdentity(connection);
            return connection;
        }
    }

    class ServiceConnection extends ServiceConnector.Impl<ISandboxedDetectionService> {
        private final Object mLock = new Object();

        private final Intent mIntent;
        private final int mBindingFlags;
        private final int mInstanceNumber;

        private boolean mRespectServiceConnectionStatusChanged = true;
        private boolean mIsBound = false;
        private boolean mIsLoggedFirstConnect = false;

        ServiceConnection(@NonNull Context context,
                @NonNull Intent serviceIntent, int bindingFlags, int userId,
                @Nullable Function<IBinder, ISandboxedDetectionService> binderAsInterface,
                int instanceNumber) {
            super(context, serviceIntent, bindingFlags, userId, binderAsInterface);
            this.mIntent = serviceIntent;
            this.mBindingFlags = bindingFlags;
            this.mInstanceNumber = instanceNumber;
        }

        @Override // from ServiceConnector.Impl
        protected void onServiceConnectionStatusChanged(ISandboxedDetectionService service,
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

                if (!connected) {
                    if (mDetectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                        HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                                HOTWORD_DETECTOR_EVENTS__EVENT__ON_DISCONNECTED,
                                mVoiceInteractionServiceUid);
                    }
                } else if (!mIsLoggedFirstConnect) {
                    mIsLoggedFirstConnect = true;
                    if (mDetectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                        HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                                HOTWORD_DETECTOR_EVENTS__EVENT__ON_CONNECTED,
                                mVoiceInteractionServiceUid);
                    }
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
            Slog.w(TAG, "binderDied");
            synchronized (mLock) {
                if (!mRespectServiceConnectionStatusChanged) {
                    Slog.v(TAG, "Ignored #binderDied event");
                    return;
                }
            }
            //TODO(b265535257): report error to either service only.
            synchronized (HotwordDetectionConnection.this.mLock) {
                runForEachDetectorSessionLocked((session) -> {
                    session.reportErrorLocked(
                            DetectorSession.HOTWORD_DETECTION_SERVICE_DIED);
                });
            }
            // Can improve to log exit reason if needed
            if (mDetectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        mDetectorType,
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__SERVICE_CRASH,
                        mVoiceInteractionServiceUid);
            }
        }

        @Override
        protected boolean bindService(
                @NonNull android.content.ServiceConnection serviceConnection) {
            try {
                if (mDetectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                    HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                            HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE,
                            mVoiceInteractionServiceUid);
                }
                String instancePrefix =
                        mIntent.getAction().equals(HotwordDetectionService.SERVICE_INTERFACE)
                                ? "hotword_detector_" : "visual_query_detector_";
                boolean bindResult = mContext.bindIsolatedService(
                        mIntent,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE | mBindingFlags,
                        instancePrefix + mInstanceNumber,
                        mExecutor,
                        serviceConnection);
                if (!bindResult) {
                    if (mDetectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                        HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                                HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE_FAIL,
                                mVoiceInteractionServiceUid);
                    }
                }
                return bindResult;
            } catch (IllegalArgumentException e) {
                if (mDetectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
                    HotwordMetricsLogger.writeDetectorEvent(mDetectorType,
                            HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_BIND_SERVICE_FAIL,
                            mVoiceInteractionServiceUid);
                }
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

    @SuppressWarnings("GuardedBy")
    void createDetectorLocked(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull IBinder token,
            @NonNull IHotwordRecognitionStatusCallback callback,
            int detectorType) {
        // We only support one Dsp trusted hotword detector and one software hotword detector at
        // the same time, remove existing one.
        DetectorSession removeSession = mDetectorSessions.get(detectorType);
        if (removeSession != null) {
            removeSession.destroyLocked();
            mDetectorSessions.remove(detectorType);
        }
        final DetectorSession session;
        if (detectorType == HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP) {
            if (mRemoteHotwordDetectionService == null) {
                mRemoteHotwordDetectionService =
                        mHotwordDetectionServiceConnectionFactory.createLocked();
            }
            session = new DspTrustedHotwordDetectorSession(mRemoteHotwordDetectionService,
                    mLock, mContext, token, callback, mVoiceInteractionServiceUid,
                    mVoiceInteractorIdentity, mScheduledExecutorService, mDebugHotwordLogging);
        } else if (detectorType == HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
            if (mRemoteVisualQueryDetectionService == null) {
                mRemoteVisualQueryDetectionService =
                        mVisualQueryDetectionServiceConnectionFactory.createLocked();
            }
            session = new VisualQueryDetectorSession(
                    mRemoteVisualQueryDetectionService, mLock, mContext, token, callback,
                    mVoiceInteractionServiceUid, mVoiceInteractorIdentity,
                    mScheduledExecutorService, mDebugHotwordLogging);
        } else {
            if (mRemoteHotwordDetectionService == null) {
                mRemoteHotwordDetectionService =
                        mHotwordDetectionServiceConnectionFactory.createLocked();
            }
            session = new SoftwareTrustedHotwordDetectorSession(
                    mRemoteHotwordDetectionService, mLock, mContext, token, callback,
                    mVoiceInteractionServiceUid, mVoiceInteractorIdentity,
                    mScheduledExecutorService, mDebugHotwordLogging);
        }
        mDetectorSessions.put(detectorType, session);
        session.initialize(options, sharedMemory);
    }

    @SuppressWarnings("GuardedBy")
    void destroyDetectorLocked(@NonNull IBinder token) {
        final DetectorSession session = getDetectorSessionByTokenLocked(token);
        if (session == null) {
            return;
        }
        session.destroyLocked();
        final int index = mDetectorSessions.indexOfValue(session);
        if (index < 0 || index > mDetectorSessions.size() - 1) {
            return;
        }
        mDetectorSessions.removeAt(index);
        if (session instanceof VisualQueryDetectorSession) {
            unbindVisualQueryDetectionService();
        }
        // Handle case where all hotword detector sessions are destroyed with only the visual
        // detector session left
        if (mDetectorSessions.size() == 1
                && mDetectorSessions.get(0) instanceof VisualQueryDetectorSession) {
            unbindHotwordDetectionService();
        }
    }

    @SuppressWarnings("GuardedBy")
    private DetectorSession getDetectorSessionByTokenLocked(IBinder token) {
        if (token == null) {
            return null;
        }
        for (int i = 0; i < mDetectorSessions.size(); i++) {
            final DetectorSession session = mDetectorSessions.valueAt(i);
            if (!session.isDestroyed() && session.isSameToken(token)) {
                return session;
            }
        }
        return null;
    }

    @SuppressWarnings("GuardedBy")
    private DspTrustedHotwordDetectorSession getDspTrustedHotwordDetectorSessionLocked() {
        final DetectorSession session = mDetectorSessions.get(
                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP);
        if (session == null || session.isDestroyed()) {
            Slog.v(TAG, "Not found the Dsp detector");
            return null;
        }
        return (DspTrustedHotwordDetectorSession) session;
    }

    @SuppressWarnings("GuardedBy")
    private SoftwareTrustedHotwordDetectorSession getSoftwareTrustedHotwordDetectorSessionLocked() {
        final DetectorSession session = mDetectorSessions.get(
                HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE);
        if (session == null || session.isDestroyed()) {
            Slog.v(TAG, "Not found the software detector");
            return null;
        }
        return (SoftwareTrustedHotwordDetectorSession) session;
    }

    @SuppressWarnings("GuardedBy")
    private VisualQueryDetectorSession getVisualQueryDetectorSessionLocked() {
        final DetectorSession session = mDetectorSessions.get(
                HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR);
        if (session == null || session.isDestroyed()) {
            Slog.v(TAG, "Not found the look and talk perceiver");
            return null;
        }
        return (VisualQueryDetectorSession) session;
    }
    private void runForEachDetectorSessionLocked(
            @NonNull Consumer<DetectorSession> action) {
        for (int i = 0; i < mDetectorSessions.size(); i++) {
            DetectorSession session = mDetectorSessions.valueAt(i);
            action.accept(session);
        }
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

    private static void updateSpeechService(ServiceConnection connection) {
        IBinder b = ServiceManager.getService(Context.SPEECH_RECOGNITION_SERVICE);
        IRecognitionServiceManager binderService = IRecognitionServiceManager.Stub.asInterface(b);
        connection.run(service -> {
            service.updateRecognitionServiceManager(binderService);
        });
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
}
