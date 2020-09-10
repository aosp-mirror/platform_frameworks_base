/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.wifi.p2p;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit test harness for {@link android.net.wifi.p2p.WifiP2pDevice}
 */
@SmallTest
public class WifiP2pDeviceTest {

    /**
     * Compare two p2p devices.
     *
     * @param devA is the first device to be compared
     * @param devB is the second device to be compared
     */
    private void compareWifiP2pDevices(WifiP2pDevice devA, WifiP2pDevice devB) {
        assertEquals(devA.deviceName, devB.deviceName);
        assertEquals(devA.deviceAddress, devB.deviceAddress);
        assertEquals(devA.primaryDeviceType, devB.primaryDeviceType);
        assertEquals(devA.secondaryDeviceType, devB.secondaryDeviceType);
        assertEquals(devA.wpsConfigMethodsSupported, devB.wpsConfigMethodsSupported);
        assertEquals(devA.deviceCapability, devB.deviceCapability);
        assertEquals(devA.groupCapability, devB.groupCapability);
        assertEquals(devA.status, devB.status);
        if (devA.wfdInfo != null) {
            assertEquals(devA.wfdInfo.isEnabled(), devB.wfdInfo.isEnabled());
            assertEquals(devA.wfdInfo.getDeviceInfoHex(), devB.wfdInfo.getDeviceInfoHex());
            assertEquals(devA.wfdInfo.getControlPort(), devB.wfdInfo.getControlPort());
            assertEquals(devA.wfdInfo.getMaxThroughput(), devB.wfdInfo.getMaxThroughput());
        } else {
            assertEquals(devA.wfdInfo, devB.wfdInfo);
        }
    }

    /**
     * Check equals and hashCode consistency
     */
    @Test
    public void testEqualsWithHashCode() throws Exception {
        WifiP2pDevice dev_a = new WifiP2pDevice();
        dev_a.deviceAddress = new String("02:90:4c:a0:92:54");
        WifiP2pDevice dev_b = new WifiP2pDevice();
        dev_b.deviceAddress = new String("02:90:4c:a0:92:54");

        assertTrue(dev_a.equals(dev_b));
        assertEquals(dev_a.hashCode(), dev_b.hashCode());
    }

    /**
     * Check the copy constructor with default values.
     */
    @Test
    public void testCopyConstructorWithDefaultValues() throws Exception {
        WifiP2pDevice device = new WifiP2pDevice();
        WifiP2pDevice copy = new WifiP2pDevice(device);
        compareWifiP2pDevices(device, copy);
    }

    /**
     * Check the copy constructor with updated values.
     */
    @Test
    public void testCopyConstructorWithUpdatedValues() throws Exception {
        WifiP2pDevice device = new WifiP2pDevice();
        device.deviceName = "deviceName";
        device.deviceAddress = "11:22:33:44:55:66";
        device.primaryDeviceType = "primaryDeviceType";
        device.secondaryDeviceType = "secondaryDeviceType";
        device.wpsConfigMethodsSupported = 0x0008;
        device.deviceCapability = 1;
        device.groupCapability = 1;
        device.status = WifiP2pDevice.CONNECTED;
        device.wfdInfo = new WifiP2pWfdInfo();
        WifiP2pDevice copy = new WifiP2pDevice(device);
        compareWifiP2pDevices(device, copy);
    }

    /**
     * Check the copy constructor when the wfdInfo of the source object is null.
     */
    @Test
    public void testCopyConstructorWithNullWfdInfo() throws Exception {
        WifiP2pDevice device = new WifiP2pDevice();
        device.deviceName = "deviceName";
        device.deviceAddress = "11:22:33:44:55:66";
        device.primaryDeviceType = "primaryDeviceType";
        device.secondaryDeviceType = "secondaryDeviceType";
        device.wpsConfigMethodsSupported = 0x0008;
        device.deviceCapability = 1;
        device.groupCapability = 1;
        device.status = WifiP2pDevice.CONNECTED;
        device.wfdInfo = null;
        WifiP2pDevice copy = new WifiP2pDevice(device);
        compareWifiP2pDevices(device, copy);
    }
}
