/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.SoundModel;
import android.os.IBinder;
import android.util.Slog;

import java.util.Objects;

/**
 * An {@link ISoundTriggerHal} decorator that would enforce deadlines on all calls and reboot the
 * HAL whenever they expire.
 */
public class SoundTriggerHalWatchdog implements ISoundTriggerHal {
    private static final long TIMEOUT_MS = 3000;
    private static final String TAG = "SoundTriggerHalWatchdog";

    private final @NonNull ISoundTriggerHal mUnderlying;
    private final @NonNull UptimeTimer mTimer;

    public SoundTriggerHalWatchdog(@NonNull ISoundTriggerHal underlying) {
        mUnderlying = Objects.requireNonNull(underlying);
        mTimer = new UptimeTimer("SoundTriggerHalWatchdog");
    }

    @Override
    public Properties getProperties() {
        try (Watchdog ignore = new Watchdog()) {
            return mUnderlying.getProperties();
        }
    }

    @Override
    public void registerCallback(GlobalCallback callback) {
        try (Watchdog ignore = new Watchdog()) {
            mUnderlying.registerCallback(callback);
        }
    }

    @Override
    public int loadSoundModel(SoundModel soundModel, ModelCallback callback) {
        try (Watchdog ignore = new Watchdog()) {
            return mUnderlying.loadSoundModel(soundModel, callback);
        }
    }

    @Override
    public int loadPhraseSoundModel(PhraseSoundModel soundModel,
            ModelCallback callback) {
        try (Watchdog ignore = new Watchdog()) {
            return mUnderlying.loadPhraseSoundModel(soundModel, callback);
        }
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        try (Watchdog ignore = new Watchdog()) {
            mUnderlying.unloadSoundModel(modelHandle);
        }
    }

    @Override
    public void stopRecognition(int modelHandle) {
        try (Watchdog ignore = new Watchdog()) {
            mUnderlying.stopRecognition(modelHandle);
        }
    }

    @Override
    public void startRecognition(int modelHandle, int deviceHandle, int ioHandle,
            RecognitionConfig config) {
        try (Watchdog ignore = new Watchdog()) {
            mUnderlying.startRecognition(modelHandle, deviceHandle, ioHandle, config);
        }
    }

    @Override
    public void forceRecognitionEvent(int modelHandle) {
        try (Watchdog ignore = new Watchdog()) {
            mUnderlying.forceRecognitionEvent(modelHandle);
        }
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        try (Watchdog ignore = new Watchdog()) {
            return mUnderlying.getModelParameter(modelHandle, param);
        }
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        try (Watchdog ignore = new Watchdog()) {
            mUnderlying.setModelParameter(modelHandle, param, value);
        }
    }

    @Override
    public ModelParameterRange queryParameter(int modelHandle, int param) {
        try (Watchdog ignore = new Watchdog()) {
            return mUnderlying.queryParameter(modelHandle, param);
        }
    }

    @Override
    public void linkToDeath(IBinder.DeathRecipient recipient) {
        mUnderlying.linkToDeath(recipient);
    }

    @Override
    public void unlinkToDeath(IBinder.DeathRecipient recipient) {
        mUnderlying.unlinkToDeath(recipient);
    }

    @Override
    public String interfaceDescriptor() {
        return mUnderlying.interfaceDescriptor();
    }

    @Override
    public void flushCallbacks() {
        mUnderlying.flushCallbacks();
    }

    @Override
    public void clientAttached(IBinder binder) {
        mUnderlying.clientAttached(binder);
    }

    @Override
    public void clientDetached(IBinder binder) {
        mUnderlying.clientDetached(binder);
    }

    @Override
    public void reboot() {
        mUnderlying.reboot();
    }

    @Override
    public void detach() {
        mUnderlying.detach();
        // We should no longer have any pending calls
        mTimer.quit();
    }

    private class Watchdog implements AutoCloseable {
        private final @NonNull UptimeTimer.Task mTask;
        // This exception is used merely for capturing a stack trace at the time of creation.
        private final @NonNull
        Exception mException = new Exception();

        Watchdog() {
            mTask = mTimer.createTask(() -> {
                Slog.e(TAG, "HAL deadline expired. Rebooting.", mException);
                reboot();
            }, TIMEOUT_MS);
        }

        @Override
        public void close() {
            mTask.cancel();
        }
    }
}
