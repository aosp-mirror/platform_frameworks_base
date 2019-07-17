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

package android.net;

import static com.android.testutils.ParcelUtilsKt.assertParcelSane;
import static com.android.testutils.ParcelUtilsKt.assertParcelingIsLossless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link IpSecConfig}. */
@SmallTest
@RunWith(JUnit4.class)
public class IpSecConfigTest {

    @Test
    public void testDefaults() throws Exception {
        IpSecConfig c = new IpSecConfig();
        assertEquals(IpSecTransform.MODE_TRANSPORT, c.getMode());
        assertEquals("", c.getSourceAddress());
        assertEquals("", c.getDestinationAddress());
        assertNull(c.getNetwork());
        assertEquals(IpSecTransform.ENCAP_NONE, c.getEncapType());
        assertEquals(IpSecManager.INVALID_RESOURCE_ID, c.getEncapSocketResourceId());
        assertEquals(0, c.getEncapRemotePort());
        assertEquals(0, c.getNattKeepaliveInterval());
        assertNull(c.getEncryption());
        assertNull(c.getAuthentication());
        assertEquals(IpSecManager.INVALID_RESOURCE_ID, c.getSpiResourceId());
        assertEquals(0, c.getXfrmInterfaceId());
    }

    private IpSecConfig getSampleConfig() {
        IpSecConfig c = new IpSecConfig();
        c.setMode(IpSecTransform.MODE_TUNNEL);
        c.setSourceAddress("0.0.0.0");
        c.setDestinationAddress("1.2.3.4");
        c.setSpiResourceId(1984);
        c.setEncryption(
                new IpSecAlgorithm(
                        IpSecAlgorithm.CRYPT_AES_CBC,
                        new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF}));
        c.setAuthentication(
                new IpSecAlgorithm(
                        IpSecAlgorithm.AUTH_HMAC_MD5,
                        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF, 0},
                        128));
        c.setAuthenticatedEncryption(
                new IpSecAlgorithm(
                        IpSecAlgorithm.AUTH_CRYPT_AES_GCM,
                        new byte[] {
                            1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF, 0, 1, 2, 3, 4
                        },
                        128));
        c.setEncapType(android.system.OsConstants.UDP_ENCAP_ESPINUDP);
        c.setEncapSocketResourceId(7);
        c.setEncapRemotePort(22);
        c.setNattKeepaliveInterval(42);
        c.setMarkValue(12);
        c.setMarkMask(23);
        c.setXfrmInterfaceId(34);

        return c;
    }

    @Test
    public void testCopyConstructor() {
        IpSecConfig original = getSampleConfig();
        IpSecConfig copy = new IpSecConfig(original);

        assertEquals(original, copy);
        assertNotSame(original, copy);
    }

    @Test
    public void testParcelUnparcel() {
        assertParcelingIsLossless(new IpSecConfig());

        IpSecConfig c = getSampleConfig();
        assertParcelSane(c, 15);
    }
}
