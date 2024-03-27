/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.service.voice.VisualQueryDetectionServiceFailure.ERROR_CODE_ILLEGAL_ATTENTION_STATE;
import static android.service.voice.VisualQueryDetectionServiceFailure.ERROR_CODE_ILLEGAL_STREAMING_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.provider.Settings;
import android.service.voice.IDetectorSessionVisualQueryDetectionCallback;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.ISandboxedDetectionService;
import android.service.voice.IVisualQueryDetectionVoiceInteractionCallback;
import android.service.voice.VisualQueryAttentionResult;
import android.service.voice.VisualQueryDetectedResult;
import android.service.voice.VisualQueryDetectionServiceFailure;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVisualQueryDetectionAttentionListener;
import com.android.server.voiceinteraction.VoiceInteractionManagerServiceImpl.DetectorRemoteExceptionListener;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * A class that provides visual query detector to communicate with the {@link
 * android.service.voice.VisualQueryDetectionService}.
 *
 * This class can handle the visual query detection whose detector is created by using
 * {@link android.service.voice.VoiceInteractionService#createVisualQueryDetector(PersistableBundle
 * ,SharedMemory, HotwordDetector.Callback)}.
 */
final class VisualQueryDetectorSession extends DetectorSession {

    private static final String TAG = "VisualQueryDetectorSession";

    private static final String VISUAL_QUERY_DETECTION_AUDIO_OP_MESSAGE =
            "Providing query detection result from VisualQueryDetectionService to "
                    + "VoiceInteractionService";

    private static final String VISUAL_QUERY_DETECTION_CAMERA_OP_MESSAGE =
            "Providing query detection result from VisualQueryDetectionService to "
                    + "VoiceInteractionService";
    private IVisualQueryDetectionAttentionListener mAttentionListener;
    private boolean mEgressingData;
    private boolean mQueryStreaming;
    private boolean mEnableAccessibilityDataEgress;

    //TODO(b/261783819): Determines actual functionalities, e.g., startRecognition etc.
    VisualQueryDetectorSession(
            @NonNull HotwordDetectionConnection.ServiceConnection remoteService,
            @NonNull Object lock, @NonNull Context context, @NonNull IBinder token,
            @NonNull IHotwordRecognitionStatusCallback callback, int voiceInteractionServiceUid,
            Identity voiceInteractorIdentity,
            @NonNull ScheduledExecutorService scheduledExecutorService, boolean logging,
            @NonNull DetectorRemoteExceptionListener listener, int userId) {
        super(remoteService, lock, context, token, callback,
                voiceInteractionServiceUid, voiceInteractorIdentity, scheduledExecutorService,
                logging, listener, userId);
        mEgressingData = false;
        mQueryStreaming = false;
        mAttentionListener = null;
        mEnableAccessibilityDataEgress = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.VISUAL_QUERY_ACCESSIBILITY_DETECTION_ENABLED, 0,
                mUserId) == 1;
        // TODO: handle notify RemoteException to client
    }

    @Override
    @SuppressWarnings("GuardedBy")
    void informRestartProcessLocked() {
        Slog.v(TAG, "informRestartProcessLocked");
        mUpdateStateAfterStartFinished.set(false);
        try {
            mCallback.onProcessRestarted();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to communicate #onProcessRestarted", e);
            notifyOnDetectorRemoteException();
        }
    }

    void setVisualQueryDetectionAttentionListenerLocked(
            @Nullable IVisualQueryDetectionAttentionListener listener) {
        mAttentionListener = listener;
    }

    @SuppressWarnings("GuardedBy")
    boolean startPerceivingLocked(IVisualQueryDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startPerceivingLocked");
        }

        IDetectorSessionVisualQueryDetectionCallback internalCallback =
                new IDetectorSessionVisualQueryDetectionCallback.Stub(){

            @Override
            public void onAttentionGained(VisualQueryAttentionResult attentionResult) {
                Slog.v(TAG, "BinderCallback#onAttentionGained");
                synchronized (mLock) {
                    mEgressingData = true;
                    if (mAttentionListener == null) {
                        return;
                    }
                    try {
                        mAttentionListener.onAttentionGained(attentionResult);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering attention gained event.", e);
                        try {
                            callback.onVisualQueryDetectionServiceFailure(
                                    new VisualQueryDetectionServiceFailure(
                                            ERROR_CODE_ILLEGAL_ATTENTION_STATE,
                                            "Attention listener fails to switch to GAINED state."));
                        } catch (RemoteException ex) {
                            Slog.v(TAG, "Fail to call onVisualQueryDetectionServiceFailure");
                        }
                    }
                }
            }

            @Override
            public void onAttentionLost(int interactionIntention) {
                Slog.v(TAG, "BinderCallback#onAttentionLost");
                synchronized (mLock) {
                    mEgressingData = false;
                    if (mAttentionListener == null) {
                        return;
                    }
                    try {
                        mAttentionListener.onAttentionLost(interactionIntention);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering attention lost event.", e);
                        try {
                            callback.onVisualQueryDetectionServiceFailure(
                                    new VisualQueryDetectionServiceFailure(
                                            ERROR_CODE_ILLEGAL_ATTENTION_STATE,
                                            "Attention listener fails to switch to LOST state."));
                        } catch (RemoteException ex) {
                            Slog.v(TAG, "Fail to call onVisualQueryDetectionServiceFailure");
                        }
                    }
                }
            }

            @Override
            public void onQueryDetected(@NonNull String partialQuery) throws RemoteException {
                Slog.v(TAG, "BinderCallback#onQueryDetected");
                synchronized (mLock) {
                    Objects.requireNonNull(partialQuery);
                    if (!mEgressingData) {
                        Slog.v(TAG, "Query should not be egressed within the unattention state.");
                        callback.onVisualQueryDetectionServiceFailure(
                                new VisualQueryDetectionServiceFailure(
                                        ERROR_CODE_ILLEGAL_STREAMING_STATE,
                                        "Cannot stream queries without attention signals."));
                        return;
                    }
                    try {
                        enforcePermissionsForVisualQueryDelivery(RECORD_AUDIO, OP_RECORD_AUDIO,
                                VISUAL_QUERY_DETECTION_AUDIO_OP_MESSAGE);
                    } catch (SecurityException e) {
                        Slog.w(TAG, "Ignoring #onQueryDetected due to a SecurityException", e);
                        try {
                            callback.onVisualQueryDetectionServiceFailure(
                                    new VisualQueryDetectionServiceFailure(
                                            ERROR_CODE_ILLEGAL_STREAMING_STATE,
                                            "Cannot stream queries without audio permission."));
                        } catch (RemoteException e1) {
                            notifyOnDetectorRemoteException();
                            throw e1;
                        }
                        return;
                    }
                    mQueryStreaming = true;
                    callback.onQueryDetected(partialQuery);
                    Slog.i(TAG, "Egressed from visual query detection process.");
                }
            }

            @Override
            public void onResultDetected(@NonNull VisualQueryDetectedResult partialResult)
                    throws RemoteException {
                Slog.v(TAG, "BinderCallback#onResultDetected");
                synchronized (mLock) {
                    Objects.requireNonNull(partialResult);
                    if (!mEgressingData) {
                        Slog.v(TAG, "Result should not be egressed within the unattention state.");
                        callback.onVisualQueryDetectionServiceFailure(
                                new VisualQueryDetectionServiceFailure(
                                        ERROR_CODE_ILLEGAL_STREAMING_STATE,
                                        "Cannot stream results without attention signals."));
                        return;
                    }
                    if (!checkDetectedResultDataLocked(partialResult)) {
                        Slog.v(TAG, "Accessibility data can be egressed only when the "
                                        + "isAccessibilityDetectionEnabled() is true.");
                        callback.onVisualQueryDetectionServiceFailure(
                                new VisualQueryDetectionServiceFailure(
                                        ERROR_CODE_ILLEGAL_STREAMING_STATE,
                                        "Cannot stream accessibility data without "
                                                + "enabling the setting."));
                        return;
                    }

                    // Show camera icon if visual only accessibility data egresses
                    if (partialResult.getAccessibilityDetectionData() != null) {
                        try {
                            enforcePermissionsForVisualQueryDelivery(CAMERA, OP_CAMERA,
                                    VISUAL_QUERY_DETECTION_CAMERA_OP_MESSAGE);
                        } catch (SecurityException e) {
                            Slog.w(TAG, "Ignoring #onQueryDetected due to a SecurityException", e);
                            try {
                                callback.onVisualQueryDetectionServiceFailure(
                                        new VisualQueryDetectionServiceFailure(
                                                ERROR_CODE_ILLEGAL_STREAMING_STATE,
                                                "Cannot stream visual only accessibility data "
                                                        + "without camera permission."));
                            } catch (RemoteException e1) {
                                notifyOnDetectorRemoteException();
                                throw e1;
                            }
                            return;
                        }
                    }

                    // Show microphone icon if text query egresses
                    if (!partialResult.getPartialQuery().isEmpty()) {
                        try {
                            enforcePermissionsForVisualQueryDelivery(RECORD_AUDIO, OP_RECORD_AUDIO,
                                    VISUAL_QUERY_DETECTION_AUDIO_OP_MESSAGE);
                        } catch (SecurityException e) {
                            Slog.w(TAG, "Ignoring #onQueryDetected due to a SecurityException", e);
                            try {
                                callback.onVisualQueryDetectionServiceFailure(
                                        new VisualQueryDetectionServiceFailure(
                                                ERROR_CODE_ILLEGAL_STREAMING_STATE,
                                                "Cannot stream queries without audio permission."));
                            } catch (RemoteException e1) {
                                notifyOnDetectorRemoteException();
                                throw e1;
                            }
                            return;
                        }
                    }

                    mQueryStreaming = true;
                    callback.onResultDetected(partialResult);
                    Slog.i(TAG, "Egressed from visual query detection process.");
                }
            }

            @Override
            public void onQueryFinished() throws RemoteException {
                Slog.v(TAG, "BinderCallback#onQueryFinished");
                synchronized (mLock) {
                    if (!mQueryStreaming) {
                        Slog.v(TAG, "Query streaming state signal FINISHED is block since there is"
                                + " no active query being streamed.");
                        callback.onVisualQueryDetectionServiceFailure(
                                new VisualQueryDetectionServiceFailure(
                                        ERROR_CODE_ILLEGAL_STREAMING_STATE,
                                        "Cannot send FINISHED signal with no query streamed."));
                        return;
                    }
                    callback.onQueryFinished();
                    mQueryStreaming = false;
                }
            }

            @Override
            public void onQueryRejected() throws RemoteException {
                Slog.v(TAG, "BinderCallback#onQueryRejected");
                synchronized (mLock) {
                    if (!mQueryStreaming) {
                        Slog.v(TAG, "Query streaming state signal REJECTED is block since there is"
                                + " no active query being streamed.");
                        callback.onVisualQueryDetectionServiceFailure(
                                new VisualQueryDetectionServiceFailure(
                                        ERROR_CODE_ILLEGAL_STREAMING_STATE,
                                        "Cannot send REJECTED signal with no query streamed."));
                        return;
                    }
                    callback.onQueryRejected();
                    mQueryStreaming = false;
                }
            }

            @SuppressWarnings("GuardedBy")
            private boolean checkDetectedResultDataLocked(VisualQueryDetectedResult result) {
                return result.getAccessibilityDetectionData() == null
                        || mEnableAccessibilityDataEgress;
            }
        };
        return mRemoteDetectionService.run(
                service -> service.detectWithVisualSignals(internalCallback));
    }

    @SuppressWarnings("GuardedBy")
    boolean stopPerceivingLocked() {
        if (DEBUG) {
            Slog.d(TAG, "stopPerceivingLocked");
        }
        return mRemoteDetectionService.run(ISandboxedDetectionService::stopDetection);
    }

    @Override
     void startListeningFromExternalSourceLocked(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback)
             throws UnsupportedOperationException {
        throw new UnsupportedOperationException("HotwordDetectionService method"
                + " should not be called from VisualQueryDetectorSession.");
    }

    void updateAccessibilityEgressStateLocked(boolean enable) {
        if (DEBUG) {
            Slog.d(TAG, "updateAccessibilityEgressStateLocked");
        }
        mEnableAccessibilityDataEgress = enable;
    }

    void enforcePermissionsForVisualQueryDelivery(String permission, int op, String msg) {
        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                enforcePermissionForDataDelivery(mContext, mVoiceInteractorIdentity,
                        permission, msg);
                mAppOpsManager.noteOpNoThrow(
                        op, mVoiceInteractorIdentity.uid,
                        mVoiceInteractorIdentity.packageName,
                        mVoiceInteractorIdentity.attributionTag,
                        msg);
            }
        });
    }

    @SuppressWarnings("GuardedBy")
    public void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
        pw.print(prefix);
    }
}


