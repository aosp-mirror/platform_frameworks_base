/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tests.sysmem.device;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

/**
 * Critical user journeys used to exercise the system for test, driven from
 * the device.
 */
public class Cujs extends Instrumentation {

    private static final String TAG = "SystemMemoryTest";

    @Override
    public void onCreate(Bundle arguments) {
        start();
    }

    @Override
    public void onStart() {
        // TODO: Exercise the system in more interesting ways.
        // Mostly what matters is that whatever we do here is sustainable: it
        // can be repeated indefinitely on the system without causing
        // problems.  For example, launching activities is fine. Installing
        // applications is only fine if we also uninstall them as part of the
        // test.
        try {
            Log.i(TAG, "Sleeping for 10 seconds...");
            Thread.sleep(10 * 1000);
        } catch (InterruptedException ignored) {
        }

        finish(Activity.RESULT_OK, null);
    }
}
