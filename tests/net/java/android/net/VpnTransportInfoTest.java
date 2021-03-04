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
        VpnTransportInfo v = new VpnTransportInfo(VpnManager.TYPE_VPN_PLATFORM);
        assertParcelSane(v, 1 /* fieldCount */);
    }

    @Test
    public void testEqualsAndHashCode() {
        VpnTransportInfo v1 = new VpnTransportInfo(VpnManager.TYPE_VPN_PLATFORM);
        VpnTransportInfo v2 = new VpnTransportInfo(VpnManager.TYPE_VPN_SERVICE);
        VpnTransportInfo v3 = new VpnTransportInfo(VpnManager.TYPE_VPN_PLATFORM);
        assertNotEquals(v1, v2);
        assertEquals(v1, v3);
        assertEquals(v1.hashCode(), v3.hashCode());
    }
}