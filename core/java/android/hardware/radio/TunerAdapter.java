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

package android.hardware.radio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the RadioTuner interface by forwarding calls to radio service.
 */
class TunerAdapter extends RadioTuner {
    private static final String TAG = "radio.TunerAdapter";

    @NonNull private final ITuner mTuner;

    TunerAdapter(ITuner tuner) {
        if (tuner == null) {
            throw new NullPointerException();
        }
        mTuner = tuner;
    }

    @Override
    public void close() {
        // TODO(b/36863239): forward to mTuner
        Log.w(TAG, "Close call not implemented");
    }

    @Override
    public int setConfiguration(RadioManager.BandConfig config) {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int getConfiguration(RadioManager.BandConfig[] config) {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int setMute(boolean mute) {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean getMute() {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int step(int direction, boolean skipSubChannel) {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int scan(int direction, boolean skipSubChannel) {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int tune(int channel, int subChannel) {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int cancel() {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int getProgramInformation(RadioManager.ProgramInfo[] info) {
        if (info == null || info.length != 1) {
            throw new IllegalArgumentException("The argument must be an array of length 1");
        }
        try {
            return mTuner.getProgramInformation(info);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean startBackgroundScan() {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public @NonNull List<RadioManager.ProgramInfo> getProgramList(@Nullable String filter) {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean isAnalogForced() {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void setAnalogForced(boolean isForced) {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean isAntennaConnected() {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean hasControl() {
        // TODO(b/36863239): forward to mTuner
        throw new RuntimeException("Not implemented");
    }
}
