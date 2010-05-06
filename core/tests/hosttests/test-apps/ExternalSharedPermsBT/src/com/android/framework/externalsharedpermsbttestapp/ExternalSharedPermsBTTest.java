/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.framework.externalsharedpermsbttestapp;

import android.bluetooth.BluetoothAdapter;

import android.test.InstrumentationTestCase;

public class ExternalSharedPermsBTTest extends InstrumentationTestCase
{
    private static final int REQUEST_ENABLE_BT = 2;

    /** The use of bluetooth below is simply to simulate an activity that tries to use bluetooth
     *  upon creation, so we can verify whether permissions are granted and accessible to the
     *  activity once it launches.
     * */
    public void testRunBluetooth()
    {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if ((mBluetoothAdapter != null) && (!mBluetoothAdapter.isEnabled())) {
            mBluetoothAdapter.getName();
        }
    }
}
