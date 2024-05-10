/*
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
package com.android.coretests.apps.bstatstestapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class TestActivity extends Activity {
    private static final String TAG = TestActivity.class.getSimpleName();

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Log.d(TAG, "onCreate called");
        notifyActivityLaunched();
    }

    private void notifyActivityLaunched() {
        Common.notifyLaunched(getIntent(), mReceiver.asBinder(), TAG);
    }

    @Override
    public void finish() {
        super.finish();
        Log.d(TAG, "finish called");
    }

    private BaseCmdReceiver mReceiver = new BaseCmdReceiver() {
        @Override
        public void doSomeWork(int durationMs) {
            Common.doSomeWork(durationMs);
        }

        @Override
        public void finishHost() {
            if (!isFinishing()) {
                finish();
            }
        }
    };
}
