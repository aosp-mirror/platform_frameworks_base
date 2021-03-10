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

package android.net.vcn;

import static android.net.ipsec.ike.SaProposal.DH_GROUP_2048_BIT_MODP;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeSaProposal;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.SaProposal;
import android.net.ipsec.ike.TunnelModeChildSessionParams;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnControlPlaneIkeConfigTest {
    private static final IkeSessionParams IKE_PARAMS;
    private static final TunnelModeChildSessionParams CHILD_PARAMS;

    static {
        IkeSaProposal ikeProposal =
                new IkeSaProposal.Builder()
                        .addEncryptionAlgorithm(
                                ENCRYPTION_ALGORITHM_AES_GCM_12, SaProposal.KEY_LEN_AES_128)
                        .addDhGroup(DH_GROUP_2048_BIT_MODP)
                        .addPseudorandomFunction(PSEUDORANDOM_FUNCTION_AES128_XCBC)
                        .build();

        Context mockContext = mock(Context.class);
        ConnectivityManager mockConnectManager = mock(ConnectivityManager.class);
        doReturn(mockConnectManager)
                .when(mockContext)
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        doReturn(mock(Network.class)).when(mockConnectManager).getActiveNetwork();

        final String serverHostname = "192.0.2.100";
        final String testLocalId = "test.client.com";
        final String testRemoteId = "test.server.com";
        final byte[] psk = "psk".getBytes();

        IKE_PARAMS =
                new IkeSessionParams.Builder(mockContext)
                        .setServerHostname(serverHostname)
                        .addSaProposal(ikeProposal)
                        .setLocalIdentification(new IkeFqdnIdentification(testLocalId))
                        .setRemoteIdentification(new IkeFqdnIdentification(testRemoteId))
                        .setAuthPsk(psk)
                        .build();

        ChildSaProposal childProposal =
                new ChildSaProposal.Builder()
                        .addEncryptionAlgorithm(
                                ENCRYPTION_ALGORITHM_AES_GCM_12, SaProposal.KEY_LEN_AES_128)
                        .build();
        CHILD_PARAMS =
                new TunnelModeChildSessionParams.Builder().addSaProposal(childProposal).build();
    }

    // Package private for use in VcnGatewayConnectionConfigTest
    static VcnControlPlaneIkeConfig buildTestConfig() {
        return new VcnControlPlaneIkeConfig(IKE_PARAMS, CHILD_PARAMS);
    }

    @Test
    public void testGetters() {
        final VcnControlPlaneIkeConfig config = buildTestConfig();
        assertEquals(IKE_PARAMS, config.getIkeSessionParams());
        assertEquals(CHILD_PARAMS, config.getChildSessionParams());
    }

    @Test
    public void testPersistableBundle() {
        final VcnControlPlaneIkeConfig config = buildTestConfig();

        assertEquals(config, new VcnControlPlaneIkeConfig(config.toPersistableBundle()));
    }

    @Test
    public void testConstructConfigWithoutIkeParams() {
        try {
            new VcnControlPlaneIkeConfig(null, CHILD_PARAMS);
            fail("Expect to fail because ikeParams was null");
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testBuilderConfigWithoutChildParams() {
        try {
            new VcnControlPlaneIkeConfig(IKE_PARAMS, null);
            fail("Expect to fail because childParams was null");
        } catch (NullPointerException expected) {
        }
    }
}
