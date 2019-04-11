/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit test harness for {@link android.net.wifi.p2p.WifiP2pProvDiscEvent}
 */
@SmallTest
public class WifiP2pProvDiscEventTest {

    private static final String DEVICE_ADDRESS = "aa:bb:cc:dd:ee:ff";
    private static final String EVENT_PBC_REQ_STRING = "P2P-PROV-DISC-PBC-REQ";
    private static final String EVENT_PBC_RSP_STRING = "P2P-PROV-DISC-PBC-RESP";
    private static final String EVENT_ENTER_PIN_STRING = "P2P-PROV-DISC-ENTER-PIN";
    private static final String EVENT_SHOW_PIN_STRING = "P2P-PROV-DISC-SHOW-PIN";
    private static final String TEST_PIN = "44490607";

    /**
     * Test parsing PBC request event.
     */
    @Test
    public void testPbcReqEvent() throws Exception {
        WifiP2pProvDiscEvent event =
                new WifiP2pProvDiscEvent(EVENT_PBC_REQ_STRING + " " + DEVICE_ADDRESS);
        assertEquals(WifiP2pProvDiscEvent.PBC_REQ, event.event);
        assertEquals(DEVICE_ADDRESS, event.device.deviceAddress);
    }


    /**
     * Test parsing PBC response event.
     */
    @Test
    public void testPbcRespEvent() throws Exception {
        WifiP2pProvDiscEvent event =
                new WifiP2pProvDiscEvent(EVENT_PBC_RSP_STRING + " " + DEVICE_ADDRESS);
        assertEquals(WifiP2pProvDiscEvent.PBC_RSP, event.event);
        assertEquals(DEVICE_ADDRESS, event.device.deviceAddress);
    }

    /**
     * Test parsing ENTER-PIN event.
     */
    @Test
    public void testEnterPinEvent() throws Exception {
        WifiP2pProvDiscEvent event =
                new WifiP2pProvDiscEvent(EVENT_ENTER_PIN_STRING + " " + DEVICE_ADDRESS);
        assertEquals(WifiP2pProvDiscEvent.ENTER_PIN, event.event);
        assertEquals(DEVICE_ADDRESS, event.device.deviceAddress);
    }

    /**
     * Test parsing SHOW-PIN event.
     */
    @Test
    public void testShowPinEvent() throws Exception {
        WifiP2pProvDiscEvent event =
                new WifiP2pProvDiscEvent(
                        EVENT_SHOW_PIN_STRING + " " + DEVICE_ADDRESS + " " + TEST_PIN);
        assertEquals(WifiP2pProvDiscEvent.SHOW_PIN, event.event);
        assertEquals(DEVICE_ADDRESS, event.device.deviceAddress);
        assertEquals(TEST_PIN, event.pin);
    }

    /**
     * Test parsing malformed input.
     */
    @Test
    public void testMalformedInput() throws Exception {
        try {
            WifiP2pProvDiscEvent event = new WifiP2pProvDiscEvent("OneToken");
            fail("Should throw IllegalArgumentException exception.");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }
    }

    /**
     * Test parsing malformed event.
     */
    @Test
    public void testMalformedEvent() throws Exception {
        try {
            WifiP2pProvDiscEvent event = new WifiP2pProvDiscEvent("XXX " + DEVICE_ADDRESS);
            fail("Should throw IllegalArgumentException exception.");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }
    }
}
