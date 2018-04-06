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

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.test.mock.MockContext;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.system.Os;

import com.android.server.IpSecService;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link IpSecManager}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class IpSecManagerTest {

    private static final int TEST_UDP_ENCAP_PORT = 34567;
    private static final int DROID_SPI = 0xD1201D;
    private static final int DUMMY_RESOURCE_ID = 0x1234;

    private static final InetAddress GOOGLE_DNS_4;
    private static final String VTI_INTF_NAME = "ipsec_test";
    private static final InetAddress VTI_LOCAL_ADDRESS;
    private static final LinkAddress VTI_INNER_ADDRESS = new LinkAddress("10.0.1.1/24");

    static {
        try {
            // Google Public DNS Addresses;
            GOOGLE_DNS_4 = InetAddress.getByName("8.8.8.8");
            VTI_LOCAL_ADDRESS = InetAddress.getByName("8.8.4.4");
        } catch (UnknownHostException e) {
            throw new RuntimeException("Could not resolve DNS Addresses", e);
        }
    }

    private IpSecService mMockIpSecService;
    private IpSecManager mIpSecManager;
    private MockContext mMockContext = new MockContext() {
        @Override
        public String getOpPackageName() {
            return "fooPackage";
        }
    };

    @Before
    public void setUp() throws Exception {
        mMockIpSecService = mock(IpSecService.class);
        mIpSecManager = new IpSecManager(mMockContext, mMockIpSecService);
    }

    /*
     * Allocate a specific SPI
     * Close SPIs
     */
    @Test
    public void testAllocSpi() throws Exception {
        IpSecSpiResponse spiResp =
                new IpSecSpiResponse(IpSecManager.Status.OK, DUMMY_RESOURCE_ID, DROID_SPI);
        when(mMockIpSecService.allocateSecurityParameterIndex(
                        eq(GOOGLE_DNS_4.getHostAddress()),
                        eq(DROID_SPI),
                        anyObject()))
                .thenReturn(spiResp);

        IpSecManager.SecurityParameterIndex droidSpi =
                mIpSecManager.allocateSecurityParameterIndex(GOOGLE_DNS_4, DROID_SPI);
        assertEquals(DROID_SPI, droidSpi.getSpi());

        droidSpi.close();

        verify(mMockIpSecService).releaseSecurityParameterIndex(DUMMY_RESOURCE_ID);
    }

    @Test
    public void testAllocRandomSpi() throws Exception {
        IpSecSpiResponse spiResp =
                new IpSecSpiResponse(IpSecManager.Status.OK, DUMMY_RESOURCE_ID, DROID_SPI);
        when(mMockIpSecService.allocateSecurityParameterIndex(
                        eq(GOOGLE_DNS_4.getHostAddress()),
                        eq(IpSecManager.INVALID_SECURITY_PARAMETER_INDEX),
                        anyObject()))
                .thenReturn(spiResp);

        IpSecManager.SecurityParameterIndex randomSpi =
                mIpSecManager.allocateSecurityParameterIndex(GOOGLE_DNS_4);

        assertEquals(DROID_SPI, randomSpi.getSpi());

        randomSpi.close();

        verify(mMockIpSecService).releaseSecurityParameterIndex(DUMMY_RESOURCE_ID);
    }

    /*
     * Throws resource unavailable exception
     */
    @Test
    public void testAllocSpiResUnavailableException() throws Exception {
        IpSecSpiResponse spiResp =
                new IpSecSpiResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE, 0, 0);
        when(mMockIpSecService.allocateSecurityParameterIndex(
                        anyString(), anyInt(), anyObject()))
                .thenReturn(spiResp);

        try {
            mIpSecManager.allocateSecurityParameterIndex(GOOGLE_DNS_4);
            fail("ResourceUnavailableException was not thrown");
        } catch (IpSecManager.ResourceUnavailableException e) {
        }
    }

    /*
     * Throws spi unavailable exception
     */
    @Test
    public void testAllocSpiSpiUnavailableException() throws Exception {
        IpSecSpiResponse spiResp = new IpSecSpiResponse(IpSecManager.Status.SPI_UNAVAILABLE, 0, 0);
        when(mMockIpSecService.allocateSecurityParameterIndex(
                        anyString(), anyInt(), anyObject()))
                .thenReturn(spiResp);

        try {
            mIpSecManager.allocateSecurityParameterIndex(GOOGLE_DNS_4);
            fail("ResourceUnavailableException was not thrown");
        } catch (IpSecManager.ResourceUnavailableException e) {
        }
    }

    /*
     * Should throw exception when request spi 0 in IpSecManager
     */
    @Test
    public void testRequestAllocInvalidSpi() throws Exception {
        try {
            mIpSecManager.allocateSecurityParameterIndex(GOOGLE_DNS_4, 0);
            fail("Able to allocate invalid spi");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testOpenEncapsulationSocket() throws Exception {
        IpSecUdpEncapResponse udpEncapResp =
                new IpSecUdpEncapResponse(
                        IpSecManager.Status.OK,
                        DUMMY_RESOURCE_ID,
                        TEST_UDP_ENCAP_PORT,
                        Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP));
        when(mMockIpSecService.openUdpEncapsulationSocket(eq(TEST_UDP_ENCAP_PORT), anyObject()))
                .thenReturn(udpEncapResp);

        IpSecManager.UdpEncapsulationSocket encapSocket =
                mIpSecManager.openUdpEncapsulationSocket(TEST_UDP_ENCAP_PORT);
        assertNotNull(encapSocket.getFileDescriptor());
        assertEquals(TEST_UDP_ENCAP_PORT, encapSocket.getPort());

        encapSocket.close();

        verify(mMockIpSecService).closeUdpEncapsulationSocket(DUMMY_RESOURCE_ID);
    }

    @Test
    public void testOpenEncapsulationSocketOnRandomPort() throws Exception {
        IpSecUdpEncapResponse udpEncapResp =
                new IpSecUdpEncapResponse(
                        IpSecManager.Status.OK,
                        DUMMY_RESOURCE_ID,
                        TEST_UDP_ENCAP_PORT,
                        Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP));

        when(mMockIpSecService.openUdpEncapsulationSocket(eq(0), anyObject()))
                .thenReturn(udpEncapResp);

        IpSecManager.UdpEncapsulationSocket encapSocket =
                mIpSecManager.openUdpEncapsulationSocket();

        assertNotNull(encapSocket.getFileDescriptor());
        assertEquals(TEST_UDP_ENCAP_PORT, encapSocket.getPort());

        encapSocket.close();

        verify(mMockIpSecService).closeUdpEncapsulationSocket(DUMMY_RESOURCE_ID);
    }

    @Test
    public void testOpenEncapsulationSocketWithInvalidPort() throws Exception {
        try {
            mIpSecManager.openUdpEncapsulationSocket(IpSecManager.INVALID_SECURITY_PARAMETER_INDEX);
            fail("IllegalArgumentException was not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    // TODO: add test when applicable transform builder interface is available

    private IpSecManager.IpSecTunnelInterface createAndValidateVti(int resourceId, String intfName)
            throws Exception {
        IpSecTunnelInterfaceResponse dummyResponse =
                new IpSecTunnelInterfaceResponse(IpSecManager.Status.OK, resourceId, intfName);
        when(mMockIpSecService.createTunnelInterface(
                eq(VTI_LOCAL_ADDRESS.getHostAddress()), eq(GOOGLE_DNS_4.getHostAddress()),
                anyObject(), anyObject(), anyString()))
                        .thenReturn(dummyResponse);

        IpSecManager.IpSecTunnelInterface tunnelIntf = mIpSecManager.createIpSecTunnelInterface(
                VTI_LOCAL_ADDRESS, GOOGLE_DNS_4, mock(Network.class));

        assertNotNull(tunnelIntf);
        return tunnelIntf;
    }

    @Test
    public void testCreateVti() throws Exception {
        IpSecManager.IpSecTunnelInterface tunnelIntf =
                createAndValidateVti(DUMMY_RESOURCE_ID, VTI_INTF_NAME);

        assertEquals(VTI_INTF_NAME, tunnelIntf.getInterfaceName());

        tunnelIntf.close();
        verify(mMockIpSecService).deleteTunnelInterface(eq(DUMMY_RESOURCE_ID), anyString());
    }

    @Test
    public void testAddRemoveAddressesFromVti() throws Exception {
        IpSecManager.IpSecTunnelInterface tunnelIntf =
                createAndValidateVti(DUMMY_RESOURCE_ID, VTI_INTF_NAME);

        tunnelIntf.addAddress(VTI_INNER_ADDRESS.getAddress(),
                VTI_INNER_ADDRESS.getPrefixLength());
        verify(mMockIpSecService)
                .addAddressToTunnelInterface(
                        eq(DUMMY_RESOURCE_ID), eq(VTI_INNER_ADDRESS), anyString());

        tunnelIntf.removeAddress(VTI_INNER_ADDRESS.getAddress(),
                VTI_INNER_ADDRESS.getPrefixLength());
        verify(mMockIpSecService)
                .addAddressToTunnelInterface(
                        eq(DUMMY_RESOURCE_ID), eq(VTI_INNER_ADDRESS), anyString());
    }
}
