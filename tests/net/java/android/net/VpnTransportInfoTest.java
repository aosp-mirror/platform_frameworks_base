/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net;

import static android.net.NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;
import static android.net.NetworkCapabilities.REDACT_NONE;

import static com.android.testutils.ParcelUtils.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VpnTransportInfoTest {

    @Test
    public void testParceling() {
        VpnTransportInfo v = new VpnTransportInfo(VpnManager.TYPE_VPN_PLATFORM, "12345");
        assertParcelSane(v, 2 /* fieldCount */);
    }

    @Test
    public void testEqualsAndHashCode() {
        String session1 = "12345";
        String session2 = "6789";
        VpnTransportInfo v11 = new VpnTransportInfo(VpnManager.TYPE_VPN_PLATFORM, session1);
        VpnTransportInfo v12 = new VpnTransportInfo(VpnManager.TYPE_VPN_SERVICE, session1);
        VpnTransportInfo v13 = new VpnTransportInfo(VpnManager.TYPE_VPN_PLATFORM, session1);
        VpnTransportInfo v14 = new VpnTransportInfo(VpnManager.TYPE_VPN_LEGACY, session1);
        VpnTransportInfo v15 = new VpnTransportInfo(VpnManager.TYPE_VPN_OEM, session1);
        VpnTransportInfo v21 = new VpnTransportInfo(VpnManager.TYPE_VPN_LEGACY, session2);

        VpnTransportInfo v31 = v11.makeCopy(REDACT_FOR_NETWORK_SETTINGS);
        VpnTransportInfo v32 = v13.makeCopy(REDACT_FOR_NETWORK_SETTINGS);

        assertNotEquals(v11, v12);
        assertNotEquals(v13, v14);
        assertNotEquals(v14, v15);
        assertNotEquals(v14, v21);

        assertEquals(v11, v13);
        assertEquals(v31, v32);
        assertEquals(v11.hashCode(), v13.hashCode());
        assertEquals(REDACT_FOR_NETWORK_SETTINGS, v32.getApplicableRedactions());
        assertEquals(session1, v15.makeCopy(REDACT_NONE).sessionId);
    }
}
