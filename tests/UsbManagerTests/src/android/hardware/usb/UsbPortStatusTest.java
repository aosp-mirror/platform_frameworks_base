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

import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_DEVICE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_HOST;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_NONE;
import static android.hardware.usb.UsbPortStatus.MODE_NONE;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_NONE;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SINK;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SOURCE;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.usb.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link android.hardware.usb.UsbPortStatus} */
@RunWith(TestParameterInjector.class)
public class UsbPortStatusTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_IS_PD_COMPLIANT_API)
    public void testIsPdCompliant(
            @TestParameter boolean isSinkDeviceRoleSupported,
            @TestParameter boolean isSinkHostRoleSupported,
            @TestParameter boolean isSourceDeviceRoleSupported,
            @TestParameter boolean isSourceHostRoleSupported) {
        int supportedRoleCombinations = getSupportedRoleCombinations(
                isSinkDeviceRoleSupported,
                isSinkHostRoleSupported,
                isSourceDeviceRoleSupported,
                isSourceHostRoleSupported);
        UsbPortStatus usbPortStatus = new UsbPortStatus(
                MODE_NONE,
                POWER_ROLE_NONE,
                DATA_ROLE_NONE,
                supportedRoleCombinations,
                CONTAMINANT_PROTECTION_NONE,
                CONTAMINANT_DETECTION_NOT_SUPPORTED);
        boolean expectedResult = isSinkDeviceRoleSupported
                && isSinkHostRoleSupported
                && isSourceDeviceRoleSupported
                && isSourceHostRoleSupported;

        assertThat(usbPortStatus.isPdCompliant()).isEqualTo(expectedResult);
    }

    private int getSupportedRoleCombinations(
            boolean isSinkDeviceRoleSupported,
            boolean isSinkHostRoleSupported,
            boolean isSourceDeviceRoleSupported,
            boolean isSourceHostRoleSupported) {
        int result = UsbPort.combineRolesAsBit(POWER_ROLE_NONE, DATA_ROLE_NONE);

        if (isSinkDeviceRoleSupported) {
            result |= UsbPort.combineRolesAsBit(POWER_ROLE_SINK, DATA_ROLE_DEVICE);
        }
        if (isSinkHostRoleSupported) {
            result |= UsbPort.combineRolesAsBit(POWER_ROLE_SINK, DATA_ROLE_HOST);
        }
        if (isSourceDeviceRoleSupported) {
            result |= UsbPort.combineRolesAsBit(POWER_ROLE_SOURCE, DATA_ROLE_DEVICE);
        }
        if (isSourceHostRoleSupported) {
            result |= UsbPort.combineRolesAsBit(POWER_ROLE_SOURCE, DATA_ROLE_HOST);
        }

        return result;
    }
}
