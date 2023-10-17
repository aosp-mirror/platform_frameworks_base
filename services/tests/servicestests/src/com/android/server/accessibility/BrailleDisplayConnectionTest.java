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

package com.android.server.accessibility;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.accessibilityservice.BrailleDisplayController;
import android.os.Bundle;
import android.testing.DexmakerShareClassLoaderRule;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.List;

/**
 * Tests for internal details of {@link BrailleDisplayConnection}.
 *
 * <p>Prefer adding new tests in CTS where possible.
 */
public class BrailleDisplayConnectionTest {
    private static final Path NULL_PATH = Path.of("/dev/null");

    private BrailleDisplayConnection mBrailleDisplayConnection;
    @Mock
    private BrailleDisplayConnection.NativeInterface mNativeInterface;
    @Mock
    private AccessibilityServiceConnection mServiceConnection;

    @Rule
    public final Expect expect = Expect.create();

    // To mock package-private class
    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mBrailleDisplayConnection = new BrailleDisplayConnection(new Object(), mServiceConnection);
    }

    @Test
    public void defaultNativeScanner_getReportDescriptor_returnsDescriptor() {
        int descriptorSize = 4;
        byte[] descriptor = {0xB, 0xE, 0xE, 0xF};
        when(mNativeInterface.getHidrawDescSize(anyInt())).thenReturn(descriptorSize);
        when(mNativeInterface.getHidrawDesc(anyInt(), eq(descriptorSize))).thenReturn(descriptor);

        BrailleDisplayConnection.BrailleDisplayScanner scanner =
                mBrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

        assertThat(scanner.getDeviceReportDescriptor(NULL_PATH)).isEqualTo(descriptor);
    }

    @Test
    public void defaultNativeScanner_getReportDescriptor_invalidSize_returnsNull() {
        when(mNativeInterface.getHidrawDescSize(anyInt())).thenReturn(0);

        BrailleDisplayConnection.BrailleDisplayScanner scanner =
                mBrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

        assertThat(scanner.getDeviceReportDescriptor(NULL_PATH)).isNull();
    }

    @Test
    public void defaultNativeScanner_getUniqueId_returnsUniq() {
        String macAddress = "12:34:56:78";
        when(mNativeInterface.getHidrawUniq(anyInt())).thenReturn(macAddress);

        BrailleDisplayConnection.BrailleDisplayScanner scanner =
                mBrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

        assertThat(scanner.getUniqueId(NULL_PATH)).isEqualTo(macAddress);
    }

    @Test
    public void defaultNativeScanner_getDeviceBusType_busUsb() {
        when(mNativeInterface.getHidrawBusType(anyInt()))
                .thenReturn(BrailleDisplayConnection.BUS_USB);

        BrailleDisplayConnection.BrailleDisplayScanner scanner =
                mBrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

        assertThat(scanner.getDeviceBusType(NULL_PATH))
                .isEqualTo(BrailleDisplayConnection.BUS_USB);
    }

    @Test
    public void defaultNativeScanner_getDeviceBusType_busBluetooth() {
        when(mNativeInterface.getHidrawBusType(anyInt()))
                .thenReturn(BrailleDisplayConnection.BUS_BLUETOOTH);

        BrailleDisplayConnection.BrailleDisplayScanner scanner =
                mBrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

        assertThat(scanner.getDeviceBusType(NULL_PATH))
                .isEqualTo(BrailleDisplayConnection.BUS_BLUETOOTH);
    }

    // BrailleDisplayConnection#setTestData() is used to enable CTS testing with
    // test Braille display data, but its own implementation should also be tested
    // so that issues in this helper don't cause confusing failures in CTS.
    @Test
    public void setTestData_scannerReturnsTestData() {
        Bundle bd1 = new Bundle(), bd2 = new Bundle();

        Path path1 = Path.of("/dev/path1"), path2 = Path.of("/dev/path2");
        bd1.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_HIDRAW_PATH, path1.toString());
        bd2.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_HIDRAW_PATH, path2.toString());
        byte[] desc1 = {0xB, 0xE}, desc2 = {0xE, 0xF};
        bd1.putByteArray(BrailleDisplayController.TEST_BRAILLE_DISPLAY_DESCRIPTOR, desc1);
        bd2.putByteArray(BrailleDisplayController.TEST_BRAILLE_DISPLAY_DESCRIPTOR, desc2);
        String uniq1 = "uniq1", uniq2 = "uniq2";
        bd1.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_UNIQUE_ID, uniq1);
        bd2.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_UNIQUE_ID, uniq2);
        int bus1 = BrailleDisplayConnection.BUS_USB, bus2 = BrailleDisplayConnection.BUS_BLUETOOTH;
        bd1.putBoolean(BrailleDisplayController.TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH,
                bus1 == BrailleDisplayConnection.BUS_BLUETOOTH);
        bd2.putBoolean(BrailleDisplayController.TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH,
                bus2 == BrailleDisplayConnection.BUS_BLUETOOTH);

        BrailleDisplayConnection.BrailleDisplayScanner scanner =
                mBrailleDisplayConnection.setTestData(List.of(bd1, bd2));

        expect.that(scanner.getHidrawNodePaths()).containsExactly(path1, path2);
        expect.that(scanner.getDeviceReportDescriptor(path1)).isEqualTo(desc1);
        expect.that(scanner.getDeviceReportDescriptor(path2)).isEqualTo(desc2);
        expect.that(scanner.getUniqueId(path1)).isEqualTo(uniq1);
        expect.that(scanner.getUniqueId(path2)).isEqualTo(uniq2);
        expect.that(scanner.getDeviceBusType(path1)).isEqualTo(bus1);
        expect.that(scanner.getDeviceBusType(path2)).isEqualTo(bus2);
    }
}
