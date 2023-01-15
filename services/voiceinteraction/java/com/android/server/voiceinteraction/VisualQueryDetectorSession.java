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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.voice.IDetectorSessionVisualQueryDetectionCallback;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.ISandboxedDetectionService;
import android.service.voice.IVisualQueryDetectionVoiceInteractionCallback;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;

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
    private boolean mEgressingData;
    private boolean mQueryStreaming;

    //TODO(b/261783819): Determines actual functionalities, e.g., startRecognition etc.
    VisualQueryDetectorSession(
            @NonNull HotwordDetectionConnection.ServiceConnection remoteService,
            @NonNull Object lock, @NonNull Context context, @NonNull IBinder token,
            @NonNull IHotwordRecognitionStatusCallback callback, int voiceInteractionServiceUid,
            Identity voiceInteractorIdentity,
            @NonNull ScheduledExecutorService scheduledExecutorService, boolean logging) {
        super(remoteService, lock, context, token, callback,
                voiceInteractionServiceUid, voiceInteractorIdentity, scheduledExecutorService,
                logging);
        mEgressingData = false;
        mQueryStreaming = false;
    }

    @Override
    @SuppressWarnings("GuardedBy")
    void informRestartProcessLocked() {
        Slog.v(TAG, "informRestartProcessLocked");
        mUpdateStateAfterStartFinished.set(false);
        //TODO(b/261783819): Starts detection in VisualQueryDetectionService.
    }

    @SuppressWarnings("GuardedBy")
    void startPerceivingLocked(IVisualQueryDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startPerceivingLocked");
        }

        IDetectorSessionVisualQueryDetectionCallback internalCallback =
                new IDetectorSessionVisualQueryDetectionCallback.Stub(){

            @Override
            public void onAttentionGained() {
                Slog.v(TAG, "BinderCallback#onAttentionGained");
                //TODO check to see if there is an active SysUI listener registered
                mEgressingData = true;
            }

            @Override
            public void onAttentionLost() {
                Slog.v(TAG, "BinderCallback#onAttentionLost");
                //TODO check to see if there is an active SysUI listener registered
                mEgressingData = false;
            }

            @Override
            public void onQueryDetected(@NonNull String partialQuery) throws RemoteException {
                Objects.requireNonNull(partialQuery);
                Slog.v(TAG, "BinderCallback#onQueryDetected");
                if (!mEgressingData) {
                    Slog.v(TAG, "Query should not be egressed within the unattention state.");
                    return;
                }
                mQueryStreaming = true;
                callback.onQueryDetected(partialQuery);
                Slog.i(TAG, "Egressed from visual query detection process.");
            }

            @Override
            public void onQueryFinished() throws RemoteException {
                Slog.v(TAG, "BinderCallback#onQueryFinished");
                if (!mQueryStreaming) {
                    Slog.v(TAG, "Query streaming state signal FINISHED is block since there is"
                            + " no active query being streamed.");
                    return;
                }
                callback.onQueryFinished();
                mQueryStreaming = false;
            }

            @Override
            public void onQueryRejected() throws RemoteException {
                Slog.v(TAG, "BinderCallback#onQueryRejected");
                if (!mQueryStreaming) {
                    Slog.v(TAG, "Query streaming state signal REJECTED is block since there is"
                            + " no active query being streamed.");
                    return;
                }
                callback.onQueryRejected();
                mQueryStreaming = false;
            }
        };
        mRemoteDetectionService.run(service -> service.detectWithVisualSignals(internalCallback));
    }

    @SuppressWarnings("GuardedBy")
    void stopPerceivingLocked() {
        if (DEBUG) {
            Slog.d(TAG, "stopPerceivingLocked");
        }
        mRemoteDetectionService.run(ISandboxedDetectionService::stopDetection);
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


    @SuppressWarnings("GuardedBy")
    public void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
        pw.print(prefix);
    }
}


