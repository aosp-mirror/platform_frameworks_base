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

package com.android.tests.servicecrashtest;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.provider.Settings;
import android.test.InstrumentationTestCase;

import com.android.compatibility.common.util.SystemUtil;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ServiceCrashTest extends InstrumentationTestCase {

    private static final String TAG = ServiceCrashTest.class.getSimpleName();

    private String mResetConstants = "foo=bar";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mResetConstants = Settings.Global.getString(
                getInstrumentation().getContext().getContentResolver(),
                Settings.Global.ACTIVITY_MANAGER_CONSTANTS);
        setAMConstants("service_crash_restart_duration=5000,service_crash_max_retry=4");
    }

    @Override
    protected void tearDown() throws Exception {
        // Reset the activity manager constants
        setAMConstants(mResetConstants);
        super.tearDown();
    }

    private void setAMConstants(String value) throws IOException {
        // Set the activity manager constants
        if (value == null) {
            SystemUtil.runShellCommand(getInstrumentation(),
                    "settings delete global activity_manager_constants");
        } else {
            SystemUtil.runShellCommand(getInstrumentation(), "settings put global "
                    + "activity_manager_constants " + value);
        }
    }

    public void testCrashQuickly() throws RemoteException {
        Context ctx = getInstrumentation().getContext();
        // Start the activity, which will bind the crashing service
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.setClass(ctx, MainActivity.class);
        ctx.startActivity(intent);
        try {
            assertTrue(MainActivity.sBindingDiedLatch.await(200, TimeUnit.SECONDS));
        } catch (InterruptedException ie) {
        }
    }
}
