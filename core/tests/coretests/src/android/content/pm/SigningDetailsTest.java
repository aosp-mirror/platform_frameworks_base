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
package android.content.pm;

import static android.content.pm.PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageParser.SigningDetails;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.PublicKey;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SigningDetailsTest {
    private static final String FIRST_SIGNATURE = "1234";
    private static final String SECOND_SIGNATURE = "5678";
    private static final String THIRD_SIGNATURE = "9abc";

    @Test
    public void hasAncestor_multipleSignersInLineageWithAncestor_returnsTrue() throws Exception {
        SigningDetails twoSignersInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE, THIRD_SIGNATURE);
        SigningDetails oneSignerInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE);

        boolean result = twoSignersInLineageDetails.hasAncestor(oneSignerInLineageDetails);

        assertTrue(result);
    }

    @Test
    public void hasAncestor_oneSignerInLineageAgainstMultipleSignersInLineage_returnsFalse()
            throws Exception {
        SigningDetails twoSignersInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE, THIRD_SIGNATURE);
        SigningDetails oneSignerInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE);

        boolean result = oneSignerInLineageDetails.hasAncestor(twoSignersInLineageDetails);

        assertFalse(result);
    }

    @Test
    public void hasAncestor_multipleSignersInLineageAgainstSelf_returnsFalse() throws Exception {
        SigningDetails twoSignersInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE, THIRD_SIGNATURE);

        boolean result = twoSignersInLineageDetails.hasAncestor(twoSignersInLineageDetails);

        assertFalse(result);
    }

    @Test
    public void hasAncestor_oneSignerInLineageWithAncestor_returnsTrue() throws Exception {
        SigningDetails twoSignersInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE, THIRD_SIGNATURE);
        SigningDetails oneSignerDetails = createSigningDetails(FIRST_SIGNATURE);

        boolean result = twoSignersInLineageDetails.hasAncestor(oneSignerDetails);

        assertTrue(result);
    }

    @Test
    public void hasAncestor_singleSignerAgainstLineage_returnsFalse() throws Exception {
        SigningDetails oneSignerDetails = createSigningDetails(FIRST_SIGNATURE);
        SigningDetails twoSignersInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE, THIRD_SIGNATURE);

        boolean result = oneSignerDetails.hasAncestor(twoSignersInLineageDetails);

        assertFalse(result);
    }

    @Test
    public void hasAncestor_multipleSigners_returnsFalse() throws Exception {
        SigningDetails twoSignersDetails = createSigningDetails(FIRST_SIGNATURE, SECOND_SIGNATURE);
        SigningDetails twoSignersInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE, THIRD_SIGNATURE);

        boolean result1 = twoSignersInLineageDetails.hasAncestor(twoSignersDetails);
        boolean result2 = twoSignersDetails.hasAncestor(twoSignersInLineageDetails);

        assertFalse(result1);
        assertFalse(result2);
    }

    @Test
    public void hasAncestor_unknownDetails_returnsFalse() throws Exception {
        SigningDetails unknownDetails = SigningDetails.UNKNOWN;
        SigningDetails twoSignersInLineageDetails = createSigningDetailsWithLineage(FIRST_SIGNATURE,
                SECOND_SIGNATURE, THIRD_SIGNATURE);

        boolean result1 = twoSignersInLineageDetails.hasAncestor(unknownDetails);
        boolean result2 = unknownDetails.hasAncestor(twoSignersInLineageDetails);

        assertFalse(result1);
        assertFalse(result2);
    }

    private SigningDetails createSigningDetailsWithLineage(String... signers) {
        Signature[] signingHistory = new Signature[signers.length];
        for (int i = 0; i < signers.length; i++) {
            signingHistory[i] = new Signature(signers[i]);
        }
        Signature[] currentSignature = new Signature[]{signingHistory[signers.length - 1]};
        // TODO: Since the PublicKey ArraySet is not used by any of the tests a generic empty Set
        // works for now, but if this is needed in the future consider creating mock PublicKeys that
        // can respond as required for the method under test.
        ArraySet<PublicKey> publicKeys = new ArraySet<>();
        return new SigningDetails(currentSignature, SIGNING_BLOCK_V3, publicKeys, signingHistory);
    }

    private SigningDetails createSigningDetails(String... signers) {
        Signature[] currentSignatures = new Signature[signers.length];
        for (int i = 0; i < signers.length; i++) {
            currentSignatures[i] = new Signature(signers[i]);
        }
        // TODO: Similar to above when tests are added that require this it should be updated to use
        // mocked PublicKeys.
        ArraySet<PublicKey> publicKeys = new ArraySet<>();
        return new SigningDetails(currentSignatures, SIGNING_BLOCK_V3, publicKeys, null);
    }
}
