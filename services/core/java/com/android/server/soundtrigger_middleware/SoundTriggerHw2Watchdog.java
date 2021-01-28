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
import android.hardware.soundtrigger.V2_1.ISoundTriggerHw;
import android.hardware.soundtrigger.V2_3.ModelParameterRange;
import android.hardware.soundtrigger.V2_3.Properties;
import android.hardware.soundtrigger.V2_3.RecognitionConfig;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

/**
 * An {@link ISoundTriggerHw2} decorator that would enforce deadlines on all calls and reboot the
 * HAL whenever they expire.
 */
public class SoundTriggerHw2Watchdog implements ISoundTriggerHw2 {
    private static final long TIMEOUT_MS = 3000;
    private static final String TAG = "SoundTriggerHw2Watchdog";

    private final @NonNull ISoundTriggerHw2 mUnderlying;
    private final @NonNull Timer mTimer;

    public SoundTriggerHw2Watchdog(@NonNull ISoundTriggerHw2 underlying) {
        mUnderlying = Objects.requireNonNull(underlying);
        mTimer = new Timer("SoundTriggerHw2Watchdog");
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
    public int loadSoundModel(ISoundTriggerHw.SoundModel soundModel, ModelCallback callback) {
        try (Watchdog ignore = new Watchdog()) {
            return mUnderlying.loadSoundModel(soundModel, callback);
        }
    }

    @Override
    public int loadPhraseSoundModel(ISoundTriggerHw.PhraseSoundModel soundModel,
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
    public void startRecognition(int modelHandle, RecognitionConfig config) {
        try (Watchdog ignore = new Watchdog()) {
            mUnderlying.startRecognition(modelHandle, config);
        }
    }

    @Override
    public void getModelState(int modelHandle) {
        try (Watchdog ignore = new Watchdog()) {
            mUnderlying.getModelState(modelHandle);
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
    public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) {
        return mUnderlying.linkToDeath(recipient, cookie);
    }

    @Override
    public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) {
        return mUnderlying.unlinkToDeath(recipient);
    }

    @Override
    public String interfaceDescriptor() throws RemoteException {
        return mUnderlying.interfaceDescriptor();
    }

    private static void rebootHal() {
        // This property needs to be defined in an init.rc script and trigger a HAL reboot.
        SystemProperties.set("sys.audio.restart.hal", "1");
    }

    private class Watchdog implements AutoCloseable {
        private final @NonNull
        TimerTask mTask;
        // This exception is used merely for capturing a stack trace at the time of creation.
        private final @NonNull
        Exception mException = new Exception();

        Watchdog() {
            mTask = new TimerTask() {
                @Override
                public void run() {
                    Log.e(TAG, "HAL deadline expired. Rebooting.", mException);
                    rebootHal();
                }
            };
            mTimer.schedule(mTask, TIMEOUT_MS);
        }

        @Override
        public void close() {
            mTask.cancel();
        }
    }
}
