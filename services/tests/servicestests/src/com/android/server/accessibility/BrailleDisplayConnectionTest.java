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
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.BrailleDisplayController;
import android.accessibilityservice.IBrailleDisplayController;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.testing.DexmakerShareClassLoaderRule;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.HexDump;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Tests for internal details of {@link BrailleDisplayConnection}.
 *
 * <p>Prefer adding new tests in CTS where possible.
 */
@RunWith(Enclosed.class)
public class BrailleDisplayConnectionTest {

    public static class ScannerTest {
        private static final Path NULL_PATH = Path.of("/dev/null");

        private BrailleDisplayConnection mBrailleDisplayConnection;
        @Mock
        private BrailleDisplayConnection.NativeInterface mNativeInterface;
        @Mock
        private AccessibilityServiceConnection mServiceConnection;

        @Rule
        public final Expect expect = Expect.create();

        private Context mContext;

        // To mock package-private class
        @Rule
        public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
                new DexmakerShareClassLoaderRule();

        @Before
        public void setup() {
            MockitoAnnotations.initMocks(this);
            mContext = InstrumentationRegistry.getInstrumentation().getContext();
            when(mServiceConnection.isConnectedLocked()).thenReturn(true);
            mBrailleDisplayConnection =
                    spy(new BrailleDisplayConnection(new Object(), mServiceConnection));
        }

        @Test
        public void defaultNativeScanner_getHidrawNodePaths_returnsHidrawPaths() throws Exception {
            File testDir = mContext.getFilesDir();
            Path hidrawNode0 = Path.of(testDir.getPath(), "hidraw0");
            Path hidrawNode1 = Path.of(testDir.getPath(), "hidraw1");
            Path otherDevice = Path.of(testDir.getPath(), "otherDevice");
            Path[] nodePaths = {hidrawNode0, hidrawNode1, otherDevice};
            try {
                for (Path node : nodePaths) {
                    assertThat(node.toFile().createNewFile()).isTrue();
                }

                BrailleDisplayConnection.BrailleDisplayScanner scanner =
                        BrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

                assertThat(scanner.getHidrawNodePaths(testDir.toPath()))
                        .containsExactly(hidrawNode0, hidrawNode1);
            } finally {
                for (Path node : nodePaths) {
                    node.toFile().delete();
                }
            }
        }

        @Test
        public void defaultNativeScanner_getReportDescriptor_returnsDescriptor() {
            int descriptorSize = 4;
            byte[] descriptor = {0xB, 0xE, 0xE, 0xF};
            when(mNativeInterface.getHidrawDescSize(anyInt())).thenReturn(descriptorSize);
            when(mNativeInterface.getHidrawDesc(anyInt(), eq(descriptorSize))).thenReturn(
                    descriptor);

            BrailleDisplayConnection.BrailleDisplayScanner scanner =
                    BrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

            assertThat(scanner.getDeviceReportDescriptor(NULL_PATH)).isEqualTo(descriptor);
        }

        @Test
        public void defaultNativeScanner_getReportDescriptor_invalidSize_returnsNull() {
            when(mNativeInterface.getHidrawDescSize(anyInt())).thenReturn(0);

            BrailleDisplayConnection.BrailleDisplayScanner scanner =
                    BrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

            assertThat(scanner.getDeviceReportDescriptor(NULL_PATH)).isNull();
        }

        @Test
        public void defaultNativeScanner_getUniqueId_returnsUniq() {
            String macAddress = "12:34:56:78";
            when(mNativeInterface.getHidrawUniq(anyInt())).thenReturn(macAddress);

            BrailleDisplayConnection.BrailleDisplayScanner scanner =
                    BrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

            assertThat(scanner.getUniqueId(NULL_PATH)).isEqualTo(macAddress);
        }

        @Test
        public void defaultNativeScanner_getDeviceBusType_busUsb() {
            when(mNativeInterface.getHidrawBusType(anyInt()))
                    .thenReturn(BrailleDisplayConnection.BUS_USB);

            BrailleDisplayConnection.BrailleDisplayScanner scanner =
                    BrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

            assertThat(scanner.getDeviceBusType(NULL_PATH))
                    .isEqualTo(BrailleDisplayConnection.BUS_USB);
        }

        @Test
        public void defaultNativeScanner_getDeviceBusType_busBluetooth() {
            when(mNativeInterface.getHidrawBusType(anyInt()))
                    .thenReturn(BrailleDisplayConnection.BUS_BLUETOOTH);

            BrailleDisplayConnection.BrailleDisplayScanner scanner =
                    BrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

            assertThat(scanner.getDeviceBusType(NULL_PATH))
                    .isEqualTo(BrailleDisplayConnection.BUS_BLUETOOTH);
        }

        @Test
        public void defaultNativeScanner_getName_returnsName() {
            String name = "My Braille Display";
            when(mNativeInterface.getHidrawName(anyInt())).thenReturn(name);

            BrailleDisplayConnection.BrailleDisplayScanner scanner =
                    BrailleDisplayConnection.getDefaultNativeScanner(mNativeInterface);

            assertThat(scanner.getName(NULL_PATH)).isEqualTo(name);
        }

        @Test
        public void write_bypassesServiceSideCheckWithLargeBuffer_disconnects() {
            Mockito.doNothing().when(mBrailleDisplayConnection).disconnect();
            mBrailleDisplayConnection.write(
                    new byte[IBinder.getSuggestedMaxIpcSizeBytes() * 2]);

            verify(mBrailleDisplayConnection).disconnect();
        }

        @Test
        public void write_notConnected_throwsIllegalStateException() {
            when(mServiceConnection.isConnectedLocked()).thenReturn(false);

            assertThrows(IllegalStateException.class,
                    () -> mBrailleDisplayConnection.write(new byte[1]));
        }

        @Test
        public void write_unableToCreateWriteStream_disconnects() {
            Mockito.doNothing().when(mBrailleDisplayConnection).disconnect();
            // mBrailleDisplayConnection#connectLocked was never called so the
            // connection's mHidrawNode is still null. This will throw an exception
            // when attempting to create FileOutputStream on the node.
            mBrailleDisplayConnection.write(new byte[1]);

            verify(mBrailleDisplayConnection).disconnect();
        }

        @Test
        public void connect_unableToGetUniq_usesNameFallback() throws Exception {
            try {
                IBrailleDisplayController controller =
                        Mockito.mock(IBrailleDisplayController.class);
                final Path path = Path.of("/dev/null");
                final String macAddress = "00:11:22:33:AA:BB";
                final String name = "My Braille Display";
                final byte[] descriptor = {0x05, 0x41};
                Bundle bd = new Bundle();
                bd.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_HIDRAW_PATH,
                        path.toString());
                bd.putByteArray(BrailleDisplayController.TEST_BRAILLE_DISPLAY_DESCRIPTOR,
                        descriptor);
                bd.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_NAME, name);
                bd.putBoolean(BrailleDisplayController.TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH, true);
                bd.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_UNIQUE_ID, null);
                BrailleDisplayConnection.BrailleDisplayScanner scanner =
                        mBrailleDisplayConnection.setTestData(List.of(bd));
                // Validate that the test data is set up correctly before attempting connection:
                assertThat(scanner.getUniqueId(path)).isNull();
                assertThat(scanner.getName(path)).isEqualTo(name);

                mBrailleDisplayConnection.connectLocked(
                        macAddress, name, BrailleDisplayConnection.BUS_BLUETOOTH, controller);

                verify(controller).onConnected(eq(mBrailleDisplayConnection), eq(descriptor));
            } finally {
                mBrailleDisplayConnection.disconnect();
            }
        }

        // BrailleDisplayConnection#setTestData() is used to enable CTS testing with
        // test Braille display data, but its own implementation should also be tested
        // so that issues in this helper don't cause confusing failures in CTS.

        @Test
        public void setTestData_scannerReturnsTestData() {
            Bundle bd1 = new Bundle(), bd2 = new Bundle();

            Path path1 = Path.of("/dev/path1"), path2 = Path.of("/dev/path2");
            bd1.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_HIDRAW_PATH,
                    path1.toString());
            bd2.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_HIDRAW_PATH,
                    path2.toString());
            byte[] desc1 = {0xB, 0xE}, desc2 = {0xE, 0xF};
            bd1.putByteArray(BrailleDisplayController.TEST_BRAILLE_DISPLAY_DESCRIPTOR, desc1);
            bd2.putByteArray(BrailleDisplayController.TEST_BRAILLE_DISPLAY_DESCRIPTOR, desc2);
            String uniq1 = "uniq1", uniq2 = "uniq2";
            bd1.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_UNIQUE_ID, uniq1);
            bd2.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_UNIQUE_ID, uniq2);
            String name1 = "name1", name2 = "name2";
            bd1.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_NAME, name1);
            bd2.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_NAME, name2);
            int bus1 = BrailleDisplayConnection.BUS_USB, bus2 =
                    BrailleDisplayConnection.BUS_BLUETOOTH;
            bd1.putBoolean(BrailleDisplayController.TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH,
                    bus1 == BrailleDisplayConnection.BUS_BLUETOOTH);
            bd2.putBoolean(BrailleDisplayController.TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH,
                    bus2 == BrailleDisplayConnection.BUS_BLUETOOTH);

            BrailleDisplayConnection.BrailleDisplayScanner scanner =
                    mBrailleDisplayConnection.setTestData(List.of(bd1, bd2));

            expect.that(scanner.getHidrawNodePaths(Path.of("/dev"))).containsExactly(path1, path2);
            expect.that(scanner.getDeviceReportDescriptor(path1)).isEqualTo(desc1);
            expect.that(scanner.getDeviceReportDescriptor(path2)).isEqualTo(desc2);
            expect.that(scanner.getUniqueId(path1)).isEqualTo(uniq1);
            expect.that(scanner.getUniqueId(path2)).isEqualTo(uniq2);
            expect.that(scanner.getName(path1)).isEqualTo(name1);
            expect.that(scanner.getName(path2)).isEqualTo(name2);
            expect.that(scanner.getDeviceBusType(path1)).isEqualTo(bus1);
            expect.that(scanner.getDeviceBusType(path2)).isEqualTo(bus2);
        }

        @Test
        public void setTestData_emptyTestData_returnsNullNodePaths() {
            BrailleDisplayConnection.BrailleDisplayScanner scanner =
                    mBrailleDisplayConnection.setTestData(List.of());

            expect.that(scanner.getHidrawNodePaths(Path.of("/dev"))).isNull();
        }
    }

    @RunWith(Parameterized.class)
    public static class BrailleDisplayDescriptorTest {
        @Parameterized.Parameters(name = "{0}")
        public static Collection<Object[]> data() {
            return Arrays.asList(new Object[][]{
                    {"match_BdPage", new byte[]{
                            // Just one item, defines the BD page
                            0x05, 0x41}},
                    {"match_BdPageAfterAnotherPage", new byte[]{
                            // One item defines another page
                            0x05, 0x01,
                            // Next item defines BD page
                            0x05, 0x41}},
                    {"match_BdPageAfterSizeZeroItem", new byte[]{
                            // Size-zero item (last 2 bits are 00)
                            0x00,
                            // Next item defines BD page
                            0x05, 0x41}},
                    {"match_BdPageAfterSizeOneItem", new byte[]{
                            // Size-one item (last 2 bits are 01)
                            0x01, 0x7F,
                            // Next item defines BD page
                            0x05, 0x41}},
                    {"match_BdPageAfterSizeTwoItem", new byte[]{
                            // Size-two item (last 2 bits are 10)
                            0x02, 0x7F, 0x7F,
                            0x05, 0x41}},
                    {"match_BdPageAfterSizeFourItem", new byte[]{
                            // Size-four item (last 2 bits are 11)
                            0x03, 0x7F, 0x7F, 0x7F, 0x7F,
                            0x05, 0x41}},
                    {"match_BdPageInBetweenOtherPages", new byte[]{
                            // One item defines another page
                            0x05, 0x01,
                            // Next item defines BD page
                            0x05, 0x41,
                            // Next item defines another page
                            0x05, 0x02}},
                    {"fail_OtherPage", new byte[]{
                            // Just one item, defines another page
                            0x05, 0x01}},
                    {"fail_BdPageBeforeMissingData", new byte[]{
                            // This item defines BD page
                            0x05, 0x41,
                            // Next item specifies size-one item (last 2 bits are 01) but
                            // that one data byte is missing; this descriptor is malformed.
                            0x01}},
                    {"fail_BdPageWithWrongDataSize", new byte[]{
                            // This item defines a page with two-byte ID 0x41 0x7F, not 0x41.
                            0x06, 0x41, 0x7F}},
                    {"fail_LongItem", new byte[]{
                            // Item has type bits 1111, indicating Long Item.
                            (byte) 0xF0}},
            });
        }


        @Parameterized.Parameter(0)
        public String mTestName;
        @Parameterized.Parameter(1)
        public byte[] mDescriptor;

        @Test
        public void isBrailleDisplay() {
            final boolean expectedMatch = mTestName.startsWith("match_");
            assertWithMessage(
                    "Expected isBrailleDisplay==" + expectedMatch
                            + " for descriptor " + HexDump.toHexString(mDescriptor))
                    .that(BrailleDisplayConnection.isBrailleDisplay(mDescriptor))
                    .isEqualTo(expectedMatch);
        }
    }
}
