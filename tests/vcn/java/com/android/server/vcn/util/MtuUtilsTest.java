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

package com.android.server.vcn.util;

import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CBC;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_8;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_AES_256;

import static com.android.net.module.util.NetworkStackConstants.ETHER_MTU;
import static com.android.net.module.util.NetworkStackConstants.IPV6_MIN_MTU;
import static com.android.server.vcn.util.MtuUtils.getMtu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import static java.util.Collections.emptyList;

import android.net.ipsec.ike.ChildSaProposal;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MtuUtilsTest {
    @Test
    public void testUnderlyingMtuZero() {
        assertEquals(
                IPV6_MIN_MTU, getMtu(emptyList(), ETHER_MTU /* maxMtu */, 0 /* underlyingMtu */));
    }

    @Test
    public void testClampsToMaxMtu() {
        assertEquals(0, getMtu(emptyList(), 0 /* maxMtu */, IPV6_MIN_MTU /* underlyingMtu */));
    }

    @Test
    public void testNormalModeAlgorithmLessThanUnderlyingMtu() {
        final List<ChildSaProposal> saProposals =
                Arrays.asList(
                        new ChildSaProposal.Builder()
                                .addEncryptionAlgorithm(
                                        ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_256)
                                .addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                                .build());

        final int actualMtu =
                getMtu(saProposals, ETHER_MTU /* maxMtu */, ETHER_MTU /* underlyingMtu */);
        assertTrue(ETHER_MTU > actualMtu);
    }

    @Test
    public void testCombinedModeAlgorithmLessThanUnderlyingMtu() {
        final List<ChildSaProposal> saProposals =
                Arrays.asList(
                        new ChildSaProposal.Builder()
                                .addEncryptionAlgorithm(
                                        ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_256)
                                .addEncryptionAlgorithm(
                                        ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_256)
                                .addEncryptionAlgorithm(
                                        ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_256)
                                .build());

        final int actualMtu =
                getMtu(saProposals, ETHER_MTU /* maxMtu */, ETHER_MTU /* underlyingMtu */);
        assertTrue(ETHER_MTU > actualMtu);
    }
}
