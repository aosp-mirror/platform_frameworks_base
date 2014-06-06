/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.task.controllers;


import android.content.ComponentName;
import android.content.Intent;
import android.test.AndroidTestCase;

import com.android.server.task.StateChangedListener;

import static com.android.server.task.controllers.BatteryController.getForTesting;

import static org.mockito.Mockito.*;

/**
 *
 */
public class BatteryControllerTest extends AndroidTestCase {
    BatteryController mBatteryControllerUnderTest;

    StateChangedListener mStateChangedListenerStub = new StateChangedListener() {
        @Override
        public void onControllerStateChanged() {

        }

        @Override
        public void onRunTaskNow(TaskStatus taskStatus) {

        }
    };
    BatteryController.ChargingTracker mTrackerUnderTest;

    public void setUp() throws Exception {
        mBatteryControllerUnderTest = getForTesting(mStateChangedListenerStub, getTestContext());
        mTrackerUnderTest = mBatteryControllerUnderTest.getTracker();
    }

    public void testSendBatteryChargingIntent() throws Exception {
        Intent batteryConnectedIntent = new Intent(Intent.ACTION_POWER_CONNECTED)
                .setComponent(new ComponentName(getContext(), mTrackerUnderTest.getClass()));
        Intent batteryHealthyIntent = new Intent(Intent.ACTION_BATTERY_OKAY)
                .setComponent(new ComponentName(getContext(), mTrackerUnderTest.getClass()));

        mTrackerUnderTest.onReceiveInternal(batteryConnectedIntent);
        mTrackerUnderTest.onReceiveInternal(batteryHealthyIntent);

        assertTrue(mTrackerUnderTest.isOnStablePower());
    }

}