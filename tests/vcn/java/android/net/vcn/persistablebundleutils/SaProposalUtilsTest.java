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

import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeSaProposal;
import android.net.ipsec.ike.SaProposal;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SaProposalUtilsTest {
    /** Package private so that IkeSessionParamsUtilsTest can use it */
    static IkeSaProposal buildTestIkeSaProposal() {
        return new IkeSaProposal.Builder()
                .addEncryptionAlgorithm(
                        SaProposal.ENCRYPTION_ALGORITHM_3DES, SaProposal.KEY_LEN_UNUSED)
                .addEncryptionAlgorithm(
                        SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_128)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC)
                .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256)
                .addDhGroup(SaProposal.DH_GROUP_1024_BIT_MODP)
                .addDhGroup(SaProposal.DH_GROUP_3072_BIT_MODP)
                .build();
    }

    @Test
    public void testPersistableBundleEncodeDecodeIsLosslessIkeProposal() throws Exception {
        final IkeSaProposal proposal = buildTestIkeSaProposal();

        final PersistableBundle bundle = IkeSaProposalUtils.toPersistableBundle(proposal);
        final SaProposal resultProposal = IkeSaProposalUtils.fromPersistableBundle(bundle);

        assertEquals(proposal, resultProposal);
    }

    /** Package private so that TunnelModeChildSessionParamsUtilsTest can use it */
    static ChildSaProposal buildTestChildSaProposal() {
        return new ChildSaProposal.Builder()
                .addEncryptionAlgorithm(
                        SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12, SaProposal.KEY_LEN_AES_128)
                .addEncryptionAlgorithm(
                        SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12, SaProposal.KEY_LEN_AES_192)
                .addDhGroup(SaProposal.DH_GROUP_1024_BIT_MODP)
                .addDhGroup(SaProposal.DH_GROUP_4096_BIT_MODP)
                .build();
    }

    @Test
    public void testPersistableBundleEncodeDecodeIsLosslessChildProposal() throws Exception {
        final ChildSaProposal proposal = buildTestChildSaProposal();

        final PersistableBundle bundle = ChildSaProposalUtils.toPersistableBundle(proposal);
        final SaProposal resultProposal = ChildSaProposalUtils.fromPersistableBundle(bundle);

        assertEquals(proposal, resultProposal);
    }
}
