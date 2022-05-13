/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.security.keystore2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.security.KeyStoreSecurityLevel;
import android.system.keystore2.Authorization;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.math.BigInteger;
import java.util.Base64;

@RunWith(AndroidJUnit4.class)
public class AndroidKeyStoreEdECPublicKeyTest {
    private static KeyDescriptor descriptor() {
        final KeyDescriptor keyDescriptor = new KeyDescriptor();
        keyDescriptor.alias = "key";
        keyDescriptor.blob = null;
        keyDescriptor.domain = Domain.APP;
        keyDescriptor.nspace = -1;
        return keyDescriptor;
    }

    private static KeyMetadata metadata(byte[] cert, byte[] certChain) {
        KeyMetadata metadata = new KeyMetadata();
        metadata.authorizations = new Authorization[0];
        metadata.certificate = cert;
        metadata.certificateChain = certChain;
        metadata.key = descriptor();
        metadata.modificationTimeMs = 0;
        metadata.keySecurityLevel = 1;
        return metadata;
    }

    @Mock
    private KeyStoreSecurityLevel mKeystoreSecurityLevel;

    private static class EdECTestVector {
        public final byte[] encodedKeyBytes;
        public final boolean isOdd;
        public final BigInteger yValue;

        EdECTestVector(String b64KeyBytes, boolean isOdd, String yValue) {
            this.encodedKeyBytes = Base64.getDecoder().decode(b64KeyBytes);
            this.isOdd = isOdd;
            this.yValue = new BigInteger(yValue);
        }
    }

    private static final EdECTestVector[] ED_EC_TEST_VECTORS = new EdECTestVector[]{
            new EdECTestVector("MCowBQYDK2VwAyEADE+wvQqNHxaERPhAZ0rCFlgFbfWLs/YonPXdSTw0VSo=",
                    false,
                    "19147682157189290216699341180089409126316261024914226007941553249095116672780"
                    ),
            new EdECTestVector("MCowBQYDK2VwAyEA/0E1IRNzGj85Ot/TPeXqifkqTkdk4voleH0hIq59D9w=",
                    true,
                    "41640152188550647350742178040529506688513911269563908889464821205156322689535"
                    ),
            new EdECTestVector("MCowBQYDK2VwAyEAunOvGuenetl9GQSXGVo5L3RIr4OOIpFIv/Zre8qTc/8=",
                    true,
                    "57647939198144376128225770417635248407428273266444593100194116168980378907578"
                    ),
            new EdECTestVector("MCowBQYDK2VwAyEA2hHqaZ5IolswN1Yd58Y4hzhmUMCCqc4PW5A/SFLmTX8=",
                    false,
                    "57581368614046789120409806291852629847774713088410311752049592044694364885466"
                    ),
    };

    @Test
    public void testParsingOfValidKeys() {
        for (EdECTestVector testVector : ED_EC_TEST_VECTORS) {
            AndroidKeyStoreEdECPublicKey pkey = new AndroidKeyStoreEdECPublicKey(descriptor(),
                    metadata(null, null), "EdDSA", mKeystoreSecurityLevel,
                    testVector.encodedKeyBytes);

            assertEquals(pkey.getPoint().isXOdd(), testVector.isOdd);
            assertEquals(pkey.getPoint().getY(), testVector.yValue);
        }
    }

    @Test
    public void testFailedParsingOfKeysWithDifferentOid() {
        final byte[] testVectorWithIncorrectOid = Base64.getDecoder().decode(
                "MCowBQYDLGVwAyEADE+wvQqNHxaERPhAZ0rCFlgFbfWLs/YonPXdSTw0VSo=");
        assertThrows("OID should be unrecognized", IllegalArgumentException.class,
                () -> new AndroidKeyStoreEdECPublicKey(descriptor(), metadata(null, null), "EdDSA",
                        mKeystoreSecurityLevel, testVectorWithIncorrectOid));
    }

    @Test
    public void testFailedParsingOfKeysWithWrongSize() {
        final byte[] testVectorWithIncorrectKeySize = Base64.getDecoder().decode(
        "MCwwBQYDK2VwAyMADE+wvQqNHxaERPhAZ0rCFlgFbfWLs/YonPXdSTw0VSrOzg==");
        assertThrows("Key length should be invalid", IllegalArgumentException.class,
                () -> new AndroidKeyStoreEdECPublicKey(descriptor(), metadata(null, null), "EdDSA",
                        mKeystoreSecurityLevel, testVectorWithIncorrectKeySize));
    }
}

