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

package android.net.ip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.INetd;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.LinkAddress;
import android.net.util.SharedLog;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class InterfaceControllerTest {
    private static final String TEST_IFACE = "testif";
    private static final String TEST_IPV4_ADDR = "192.168.123.28";
    private static final int TEST_PREFIXLENGTH = 31;

    @Mock private INetd mNetd;
    @Mock private SharedLog mLog;
    @Captor private ArgumentCaptor<InterfaceConfigurationParcel> mConfigCaptor;

    private InterfaceController mController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mController = new InterfaceController(TEST_IFACE, mNetd, mLog);

        doNothing().when(mNetd).interfaceSetCfg(mConfigCaptor.capture());
    }

    @Test
    public void testSetIPv4Address() throws Exception {
        mController.setIPv4Address(
                new LinkAddress(InetAddresses.parseNumericAddress(TEST_IPV4_ADDR),
                        TEST_PREFIXLENGTH));
        verify(mNetd, times(1)).interfaceSetCfg(any());
        final InterfaceConfigurationParcel parcel = mConfigCaptor.getValue();
        assertEquals(TEST_IFACE, parcel.ifName);
        assertEquals(TEST_IPV4_ADDR, parcel.ipv4Addr);
        assertEquals(TEST_PREFIXLENGTH, parcel.prefixLength);
        assertEquals("", parcel.hwAddr);
        assertArrayEquals(new String[0], parcel.flags);
    }

    @Test
    public void testClearIPv4Address() throws Exception {
        mController.clearIPv4Address();
        verify(mNetd, times(1)).interfaceSetCfg(any());
        final InterfaceConfigurationParcel parcel = mConfigCaptor.getValue();
        assertEquals(TEST_IFACE, parcel.ifName);
        assertEquals("0.0.0.0", parcel.ipv4Addr);
        assertEquals(0, parcel.prefixLength);
        assertEquals("", parcel.hwAddr);
        assertArrayEquals(new String[0], parcel.flags);
    }
}
