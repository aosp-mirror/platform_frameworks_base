/**
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.broadcastradio.hal2;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.radio.ITuner;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.util.Slog;

import java.util.List;
import java.util.Map;
import java.util.Objects;

class TunerSession extends ITuner.Stub {
    private static final String TAG = "BcRadio2Srv.session";

    private final Object mLock = new Object();

    private final ITunerSession mHwSession;
    private final TunerCallback mCallback;
    private boolean mIsClosed = false;

    TunerSession(@NonNull ITunerSession hwSession, @NonNull TunerCallback callback) {
        mHwSession = Objects.requireNonNull(hwSession);
        mCallback = Objects.requireNonNull(callback);
    }

    @Override
    public void close() {
        synchronized (mLock) {
            if (mIsClosed) return;
            mIsClosed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return mIsClosed;
    }

    private void checkNotClosedLocked() {
        if (mIsClosed) {
            throw new IllegalStateException("Tuner is closed, no further operations are allowed");
        }
    }

    @Override
    public void setConfiguration(RadioManager.BandConfig config) {}

    @Override
    public RadioManager.BandConfig getConfiguration() {
        return null;
    }

    @Override
    public void setMuted(boolean mute) {}

    @Override
    public boolean isMuted() {
        return false;
    }

    @Override
    public void step(boolean directionDown, boolean skipSubChannel) {}

    @Override
    public void scan(boolean directionDown, boolean skipSubChannel) {}

    @Override
    public void tune(ProgramSelector selector) {}

    @Override
    public void cancel() {}

    @Override
    public void cancelAnnouncement() {}

    @Override
    public RadioManager.ProgramInfo getProgramInformation() {
        return null;
    }

    @Override
    public Bitmap getImage(int id) {
        return null;
    }

    @Override
    public boolean startBackgroundScan() {
        return false;
    }

    @Override
    public List<RadioManager.ProgramInfo> getProgramList(Map vendorFilter) {
        return null;
    }

    @Override
    public boolean isAnalogForced() {
        return false;
    }

    @Override
    public void setAnalogForced(boolean isForced) {}

    @Override
    public Map setParameters(Map parameters) {
        return null;
    }

    @Override
    public Map getParameters(List<String> keys) {
        return null;
    }

    @Override
    public boolean isAntennaConnected() {
        return true;
    }
}
