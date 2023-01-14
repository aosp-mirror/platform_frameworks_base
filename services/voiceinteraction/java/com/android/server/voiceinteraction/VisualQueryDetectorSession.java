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
import android.os.SharedMemory;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;

import java.io.PrintWriter;
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
    }

    @Override
    @SuppressWarnings("GuardedBy")
    void informRestartProcessLocked() {
        Slog.v(TAG, "informRestartProcessLocked");
        mUpdateStateAfterStartFinished.set(false);
        //TODO(b/261783819): Starts detection in VisualQueryDetectionService.
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


