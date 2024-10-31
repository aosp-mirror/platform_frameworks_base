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

package com.android.server.broadcastradio.hal1;

import android.annotation.NonNull;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;

import com.android.server.broadcastradio.RadioServiceUserController;
import com.android.server.utils.Slogf;

import java.util.List;
import java.util.Objects;

public class BroadcastRadioService {

    private static final String TAG = "BcRadio1Srv";

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext = nativeInit();
    private final RadioServiceUserController mUserController;

    private final Object mLock = new Object();

    @Override
    protected void finalize() throws Throwable {
        nativeFinalize(mNativeContext);
        super.finalize();
    }

    private native long nativeInit();
    private native void nativeFinalize(long nativeContext);
    private native List<RadioManager.ModuleProperties> nativeLoadModules(long nativeContext);
    private native Tuner nativeOpenTuner(long nativeContext, int moduleId,
            RadioManager.BandConfig config, boolean withAudio, ITunerCallback callback);

    public BroadcastRadioService(RadioServiceUserController userController) {
        mUserController = Objects.requireNonNull(userController, "User controller can not be null");
    }

    public @NonNull List<RadioManager.ModuleProperties> loadModules() {
        synchronized (mLock) {
            return Objects.requireNonNull(nativeLoadModules(mNativeContext));
        }
    }

    public ITuner openTuner(int moduleId, RadioManager.BandConfig bandConfig,
            boolean withAudio, ITunerCallback callback) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.e(TAG, "Cannot open tuner on HAL 1.x client for non-current user");
            throw new IllegalStateException("Cannot open tuner for non-current user");
        }
        synchronized (mLock) {
            return nativeOpenTuner(mNativeContext, moduleId, bandConfig, withAudio, callback);
        }
    }
}
