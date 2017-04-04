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
import android.hardware.radio.RadioManager;
import android.util.Slog;

import com.android.server.SystemService;

public class RadioService extends SystemService {
    // TODO(b/36863239): rename to RadioService when native service goes away
    private static final String TAG = "RadioServiceJava";

    public RadioService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.RADIO_SERVICE, new RadioServiceImpl());
        Slog.v(TAG, "RadioService started");
    }

    private static class RadioServiceImpl extends IRadioService.Stub {
        @Override
        public ITuner openTuner() {
            Slog.d(TAG, "openTuner()");
            return new TunerImpl();
        }
    }

    private static class TunerImpl extends ITuner.Stub {
        @Override
        public int getProgramInformation(RadioManager.ProgramInfo[] infoOut) {
            Slog.d(TAG, "getProgramInformation()");
            return RadioManager.STATUS_INVALID_OPERATION;
        }
    }
}
