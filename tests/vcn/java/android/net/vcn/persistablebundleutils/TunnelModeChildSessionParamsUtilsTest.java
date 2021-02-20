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
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TunnelModeChildSessionParamsUtilsTest {
    private TunnelModeChildSessionParams.Builder createBuilderMinimum() {
        final ChildSaProposal saProposal = SaProposalUtilsTest.buildTestChildSaProposal();
        return new TunnelModeChildSessionParams.Builder().addSaProposal(saProposal);
    }

    private static void verifyPersistableBundleEncodeDecodeIsLossless(
            TunnelModeChildSessionParams params) {
        final PersistableBundle bundle =
                TunnelModeChildSessionParamsUtils.toPersistableBundle(params);
        final TunnelModeChildSessionParams result =
                TunnelModeChildSessionParamsUtils.fromPersistableBundle(bundle);

        assertEquals(params, result);
    }

    @Test
    public void testMinimumParamsEncodeDecodeIsLossless() throws Exception {
        final TunnelModeChildSessionParams sessionParams = createBuilderMinimum().build();
        verifyPersistableBundleEncodeDecodeIsLossless(sessionParams);
    }

    @Test
    public void testSetTsEncodeDecodeIsLossless() throws Exception {
        final IkeTrafficSelector tsInbound =
                new IkeTrafficSelector(
                        16,
                        65520,
                        InetAddresses.parseNumericAddress("192.0.2.100"),
                        InetAddresses.parseNumericAddress("192.0.2.101"));
        final IkeTrafficSelector tsOutbound =
                new IkeTrafficSelector(
                        32,
                        256,
                        InetAddresses.parseNumericAddress("192.0.2.200"),
                        InetAddresses.parseNumericAddress("192.0.2.255"));

        final TunnelModeChildSessionParams sessionParams =
                createBuilderMinimum()
                        .addInboundTrafficSelectors(tsInbound)
                        .addOutboundTrafficSelectors(tsOutbound)
                        .build();
        verifyPersistableBundleEncodeDecodeIsLossless(sessionParams);
    }

    @Test
    public void testSetLifetimesEncodeDecodeIsLossless() throws Exception {
        final int hardLifetime = (int) TimeUnit.HOURS.toSeconds(3L);
        final int softLifetime = (int) TimeUnit.HOURS.toSeconds(1L);

        final TunnelModeChildSessionParams sessionParams =
                createBuilderMinimum().setLifetimeSeconds(hardLifetime, softLifetime).build();
        verifyPersistableBundleEncodeDecodeIsLossless(sessionParams);
    }
}
