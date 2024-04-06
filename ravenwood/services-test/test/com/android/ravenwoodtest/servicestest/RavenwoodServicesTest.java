/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.ravenwoodtest.servicestest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.hardware.SerialManager;
import android.hardware.SerialManagerInternal;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RavenwoodServicesTest {
    private static final String TEST_VIRTUAL_PORT = "virtual:example";

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProcessSystem()
            .setServicesRequired(SerialManager.class)
            .build();

    @Test
    public void testDefined() {
        final SerialManager fromName = (SerialManager)
                mRavenwood.getContext().getSystemService(Context.SERIAL_SERVICE);
        final SerialManager fromClass =
                mRavenwood.getContext().getSystemService(SerialManager.class);
        assertNotNull(fromName);
        assertNotNull(fromClass);
        assertEquals(fromName, fromClass);

        assertNotNull(LocalServices.getService(SerialManagerInternal.class));
    }

    @Test
    public void testSimple() {
        // Verify that we can obtain a manager, and talk to the backend service, and that no
        // serial ports are configured by default
        final SerialManager service = (SerialManager)
                mRavenwood.getContext().getSystemService(Context.SERIAL_SERVICE);
        final String[] ports = service.getSerialPorts();
        assertEquals(0, ports.length);
    }

    @Test
    public void testDriven() {
        final SerialManager service = (SerialManager)
                mRavenwood.getContext().getSystemService(Context.SERIAL_SERVICE);
        final SerialManagerInternal internal = LocalServices.getService(
                SerialManagerInternal.class);

        internal.addVirtualSerialPortForTest(TEST_VIRTUAL_PORT, () -> {
            throw new UnsupportedOperationException(
                    "Needs socketpair() to offer accurate emulation");
        });
        final String[] ports = service.getSerialPorts();
        assertEquals(1, ports.length);
        assertEquals(TEST_VIRTUAL_PORT, ports[0]);
    }
}
