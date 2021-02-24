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

package android.net.vcn.persistablebundleutils;

import static org.junit.Assert.assertEquals;

import android.net.InetAddresses;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeSessionParams;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IkeSessionParamsUtilsTest {
    private static IkeSessionParams.Builder createBuilderMinimum() {
        final InetAddress serverAddress = InetAddresses.parseNumericAddress("192.0.2.100");

        return new IkeSessionParams.Builder()
                .setServerHostname(serverAddress.getHostAddress())
                .addSaProposal(SaProposalUtilsTest.buildTestIkeSaProposal())
                .setLocalIdentification(new IkeFqdnIdentification("client.test.android.net"))
                .setRemoteIdentification(new IkeFqdnIdentification("server.test.android.net"))
                .setAuthPsk("psk".getBytes());
    }

    private static void verifyPersistableBundleEncodeDecodeIsLossless(IkeSessionParams params) {
        final PersistableBundle bundle = IkeSessionParamsUtils.toPersistableBundle(params);
        final IkeSessionParams result = IkeSessionParamsUtils.fromPersistableBundle(bundle);

        assertEquals(result, params);
    }

    @Test
    public void testEncodeRecodeParamsWithLifetimes() throws Exception {
        final int hardLifetime = (int) TimeUnit.HOURS.toSeconds(20L);
        final int softLifetime = (int) TimeUnit.HOURS.toSeconds(10L);
        final IkeSessionParams params =
                createBuilderMinimum().setLifetimeSeconds(hardLifetime, softLifetime).build();
        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithDpdDelay() throws Exception {
        final int dpdDelay = (int) TimeUnit.MINUTES.toSeconds(10L);
        final IkeSessionParams params = createBuilderMinimum().setDpdDelaySeconds(dpdDelay).build();

        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithNattKeepalive() throws Exception {
        final int nattKeepAliveDelay = (int) TimeUnit.MINUTES.toSeconds(5L);
        final IkeSessionParams params =
                createBuilderMinimum().setNattKeepAliveDelaySeconds(nattKeepAliveDelay).build();

        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithRetransmissionTimeouts() throws Exception {
        final int[] retransmissionTimeout = new int[] {500, 500, 500, 500, 500, 500};
        final IkeSessionParams params =
                createBuilderMinimum()
                        .setRetransmissionTimeoutsMillis(retransmissionTimeout)
                        .build();

        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithAuthPsk() throws Exception {
        final IkeSessionParams params = createBuilderMinimum().setAuthPsk("psk".getBytes()).build();
        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }
}
