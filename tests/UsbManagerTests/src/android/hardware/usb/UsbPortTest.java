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

package android.hardware.usb;

import static android.hardware.usb.UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
import static android.hardware.usb.UsbPortStatus.MODE_NONE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.usb.flags.Flags;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.InstrumentationRegistry;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link android.hardware.usb.UsbPortStatus} */
@RunWith(TestParameterInjector.class)
public class UsbPortTest {

    private IUsbManager mMockUsbService;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private UsbManager mUsbManager;

    @Before
    public void setUp() throws Exception {
        mMockUsbService = mock(IUsbManager.class);
        mUsbManager = new UsbManager(InstrumentationRegistry.getContext(), mMockUsbService);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_IS_MODE_CHANGE_SUPPORTED_API)
    public void testIsModeSupported(@TestParameter boolean isModeChangeSupported)
            throws RemoteException {
        String testPortId = "port-1";
        when(mMockUsbService.isModeChangeSupported(testPortId)).thenReturn(isModeChangeSupported);
        UsbPort usbPort = new UsbPort(
                mUsbManager,
                testPortId,
                MODE_NONE,
                CONTAMINANT_PROTECTION_NONE,
                false /* supportsEnableContaminantPresenceProtection= */ ,
                false /* supportsEnableContaminantPresenceDetection= */);

        assertThat(usbPort.isModeChangeSupported()).isEqualTo(isModeChangeSupported);
    }
}
