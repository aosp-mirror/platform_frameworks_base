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

package android.accessibilityservice;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.hardware.usb.UsbDevice;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.Flags;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

/**
 * Tests for internal details of BrailleDisplayControllerImpl.
 *
 * <p>Prefer adding new tests in CTS where possible.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_BRAILLE_DISPLAY_HID)
public class BrailleDisplayControllerImplTest {
    private static final int TEST_SERVICE_CONNECTION_ID = 123;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private AccessibilityService mAccessibilityService;
    private BrailleDisplayController mBrailleDisplayController;

    @Mock
    private IAccessibilityServiceConnection mAccessibilityServiceConnection;
    @Mock
    private BrailleDisplayController.BrailleDisplayCallback mBrailleDisplayCallback;

    public static class TestAccessibilityService extends AccessibilityService {
        public void onAccessibilityEvent(AccessibilityEvent event) {
        }

        public void onInterrupt() {
        }
    }

    @Before
    public void test() {
        MockitoAnnotations.initMocks(this);
        mAccessibilityService = spy(new TestAccessibilityService());
        doReturn((Executor) Runnable::run).when(mAccessibilityService).getMainExecutor();
        doReturn(TEST_SERVICE_CONNECTION_ID).when(mAccessibilityService).getConnectionId();
        AccessibilityInteractionClient.addConnection(TEST_SERVICE_CONNECTION_ID,
                mAccessibilityServiceConnection, /*initializeCache=*/false);
        mBrailleDisplayController = new BrailleDisplayControllerImpl(
                mAccessibilityService, new Object(), /*isHidrawSupported=*/true);
    }

    // Automated CTS tests only use the BluetoothDevice version of BrailleDisplayController#connect
    // because fake UsbDevice objects cannot be created in CTS. This internal test can mock the
    // UsbDevice object and at least validate that the correct system_server AIDL call is made.
    @Test
    public void connect_withUsbDevice_callsConnectUsbBrailleDisplay() throws Exception {
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);

        mBrailleDisplayController.connect(usbDevice, mBrailleDisplayCallback);

        verify(mAccessibilityServiceConnection).connectUsbBrailleDisplay(eq(usbDevice), any());
    }

    @Test
    public void connect_serviceNotConnected_throwsIllegalStateException() {
        AccessibilityInteractionClient.removeConnection(TEST_SERVICE_CONNECTION_ID);
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);

        assertThrows(IllegalStateException.class,
                () -> mBrailleDisplayController.connect(usbDevice, mBrailleDisplayCallback));
    }

    @Test
    public void connect_HidrawNotSupported_callsOnConnectionFailed() {
        BrailleDisplayController controller = new BrailleDisplayControllerImpl(
                mAccessibilityService, new Object(), /*isHidrawSupported=*/false);
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);

        controller.connect(usbDevice, mBrailleDisplayCallback);

        verify(mBrailleDisplayCallback).onConnectionFailed(
                BrailleDisplayController.BrailleDisplayCallback.FLAG_ERROR_CANNOT_ACCESS);
        verifyZeroInteractions(mAccessibilityServiceConnection);
    }
}
