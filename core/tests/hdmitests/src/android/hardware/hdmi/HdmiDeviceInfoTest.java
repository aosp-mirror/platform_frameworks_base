/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.hdmi;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link HdmiDeviceInfo} */
@Presubmit
@RunWith(JUnit4.class)
@SmallTest
public class HdmiDeviceInfoTest {

    @Test
    public void testEquals() {
        int logicalAddr = 0x00;
        int phyAddr = 0x1000;
        int portId = 1;
        int deviceType = 0;
        int vendorId = 0x123456;
        String displayName = "test device";
        int cecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;
        int powerStatus = HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
        int deviceId = 3;
        int adopterId = 2;

        new EqualsTester()
                .addEqualityGroup(HdmiDeviceInfo.INACTIVE_DEVICE)
                .addEqualityGroup(
                        HdmiDeviceInfo.hardwarePort(phyAddr, portId),
                        HdmiDeviceInfo.hardwarePort(phyAddr, portId))
                .addEqualityGroup(
                        HdmiDeviceInfo.mhlDevice(phyAddr, portId, adopterId, deviceId),
                        HdmiDeviceInfo.mhlDevice(phyAddr, portId, adopterId, deviceId))
                .addEqualityGroup(
                        HdmiDeviceInfo.cecDeviceBuilder()
                                .setLogicalAddress(logicalAddr)
                                .setPhysicalAddress(phyAddr)
                                .setPortId(portId)
                                .setDeviceType(deviceType)
                                .setVendorId(vendorId)
                                .setDisplayName(displayName)
                                .setDevicePowerStatus(powerStatus)
                                .setCecVersion(cecVersion).build(),
                        HdmiDeviceInfo.cecDeviceBuilder()
                                .setLogicalAddress(logicalAddr)
                                .setPhysicalAddress(phyAddr)
                                .setPortId(portId)
                                .setDeviceType(deviceType)
                                .setVendorId(vendorId)
                                .setDisplayName(displayName)
                                .setDevicePowerStatus(powerStatus)
                                .setCecVersion(cecVersion).build())
                .testEquals();
    }
}
