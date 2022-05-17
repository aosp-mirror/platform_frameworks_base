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

package android.companion;

import android.os.ParcelUuid;
import android.test.InstrumentationTestCase;

public class BluetoothDeviceFilterUtilsTest extends InstrumentationTestCase {
    private static final String TAG = "BluetoothDeviceFilterUtilsTest";

    private final ParcelUuid mServiceUuid =
            ParcelUuid.fromString("F0FFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF");
    private final ParcelUuid mNonMatchingDeviceUuid =
            ParcelUuid.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF");
    private final ParcelUuid mMatchingDeviceUuid =
            ParcelUuid.fromString("F0FFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF");
    private final ParcelUuid mMaskUuid =
            ParcelUuid.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF");
    private final ParcelUuid mMatchingMaskUuid =
            ParcelUuid.fromString("F0FFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    public void testUuidsMaskedEquals() {
        assertFalse(BluetoothDeviceFilterUtils.uuidsMaskedEquals(
                mNonMatchingDeviceUuid.getUuid(),
                mServiceUuid.getUuid(),
                mMaskUuid.getUuid()));

        assertTrue(BluetoothDeviceFilterUtils.uuidsMaskedEquals(
                mMatchingDeviceUuid.getUuid(),
                mServiceUuid.getUuid(),
                mMaskUuid.getUuid()));

        assertTrue(BluetoothDeviceFilterUtils.uuidsMaskedEquals(
                mNonMatchingDeviceUuid.getUuid(),
                mServiceUuid.getUuid(),
                mMatchingMaskUuid.getUuid()));
    }
}
