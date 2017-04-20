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

package com.android.server.radio;

import android.content.Context;
import android.hardware.radio.IRadioService;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.util.Slog;

import com.android.server.SystemService;

public class RadioService extends SystemService {
    // TODO(b/36863239): rename to RadioService when native service goes away
    private static final String TAG = "RadioServiceJava";

    private final RadioServiceImpl mServiceImpl = new RadioServiceImpl();

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext = nativeInit();

    public RadioService(Context context) {
        super(context);
    }

    @Override
    protected void finalize() throws Throwable {
        nativeFinalize(mNativeContext);
        super.finalize();
    }

    private native long nativeInit();
    private native void nativeFinalize(long nativeContext);
    private native Tuner nativeOpenTuner(long nativeContext, int moduleId,
            RadioManager.BandConfig config, boolean withAudio, ITunerCallback callback);

    @Override
    public void onStart() {
        publishBinderService(Context.RADIO_SERVICE, mServiceImpl);
        Slog.v(TAG, "RadioService started");
    }

    private class RadioServiceImpl extends IRadioService.Stub {
        @Override
        public ITuner openTuner(int moduleId, RadioManager.BandConfig bandConfig,
                boolean withAudio, ITunerCallback callback) {
            // TODO(b/36863239): add death monitoring for binder
            if (callback == null) {
                throw new IllegalArgumentException("Callback must not be empty");
            }
            return nativeOpenTuner(mNativeContext, moduleId, bandConfig, withAudio, callback);
        }
    }
}
