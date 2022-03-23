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
 * limitations under the License.
 */

package android.bluetooth.le;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * Test for Bluetooth LE {@link ScanSettings}.
 */
public class ScanSettingsTest extends TestCase {

    @SmallTest
    public void testCallbackType() {
        ScanSettings.Builder builder = new ScanSettings.Builder();
        builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
        builder.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH);
        builder.setCallbackType(ScanSettings.CALLBACK_TYPE_MATCH_LOST);
        builder.setCallbackType(
                ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST);
        try {
            builder.setCallbackType(
                    ScanSettings.CALLBACK_TYPE_ALL_MATCHES | ScanSettings.CALLBACK_TYPE_MATCH_LOST);
            fail("should have thrown IllegalArgumentException!");
        } catch (IllegalArgumentException e) {
            // nothing to do
        }

        try {
            builder.setCallbackType(
                    ScanSettings.CALLBACK_TYPE_ALL_MATCHES |
                    ScanSettings.CALLBACK_TYPE_FIRST_MATCH);
            fail("should have thrown IllegalArgumentException!");
        } catch (IllegalArgumentException e) {
            // nothing to do
        }

        try {
            builder.setCallbackType(
                    ScanSettings.CALLBACK_TYPE_ALL_MATCHES |
                    ScanSettings.CALLBACK_TYPE_FIRST_MATCH |
                    ScanSettings.CALLBACK_TYPE_MATCH_LOST);
            fail("should have thrown IllegalArgumentException!");
        } catch (IllegalArgumentException e) {
            // nothing to do
        }

    }
}
