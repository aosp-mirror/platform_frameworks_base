/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.os.Parcel;
import android.test.AndroidTestCase;

import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ContainerEncryptionParamsTest extends AndroidTestCase {
    private static final String ENC_ALGORITHM = "AES/CBC/PKCS7Padding";

    private static final byte[] IV_BYTES = "FOOBAR".getBytes();

    private static final IvParameterSpec ENC_PARAMS = new IvParameterSpec(IV_BYTES);

    private static final byte[] ENC_KEY_BYTES = "abcd1234wxyz7890".getBytes();

    private static final SecretKey ENC_KEY = new SecretKeySpec(ENC_KEY_BYTES, "RAW");

    private static final String MAC_ALGORITHM = "HMAC-SHA1";

    private static final byte[] MAC_KEY_BYTES = "4wxyzabcd1237890".getBytes();

    private static final SecretKey MAC_KEY = new SecretKeySpec(MAC_KEY_BYTES, "RAW");

    private static final byte[] MAC_TAG = "faketag".getBytes();

    private static final int AUTHENTICATED_START = 5;

    private static final int ENCRYPTED_START = 11;

    private static final int DATA_END = 19;

    public void testParcel() throws Exception {
        ContainerEncryptionParams expected = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        Parcel parcel = Parcel.obtain();
        expected.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        ContainerEncryptionParams actual = ContainerEncryptionParams.CREATOR
                .createFromParcel(parcel);

        assertEquals(ENC_ALGORITHM, actual.getEncryptionAlgorithm());

        if (!(actual.getEncryptionSpec() instanceof IvParameterSpec)) {
            fail("encryption parameters should be IvParameterSpec");
        } else {
            IvParameterSpec actualParams = (IvParameterSpec) actual.getEncryptionSpec();
            assertTrue(Arrays.equals(IV_BYTES, actualParams.getIV()));
        }

        assertEquals(ENC_KEY, actual.getEncryptionKey());

        assertEquals(MAC_ALGORITHM, actual.getMacAlgorithm());

        assertNull(actual.getMacSpec());

        assertEquals(MAC_KEY, actual.getMacKey());

        assertTrue(Arrays.equals(MAC_TAG, actual.getMacTag()));

        assertEquals(AUTHENTICATED_START, actual.getAuthenticatedDataStart());

        assertEquals(ENCRYPTED_START, actual.getEncryptedDataStart());

        assertEquals(DATA_END, actual.getDataEnd());
    }

    public void testEquals_Success() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertEquals(params1, params2);
    }

    public void testEquals_EncAlgo_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(new String(
                "AES-256/CBC/PKCS7Padding"), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_EncParams_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec("BLAHBLAH".getBytes()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_EncKey_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec("BLAHBLAH".getBytes(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_MacAlgo_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), "BLAHBLAH", null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_MacKey_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec("FAKE_MAC_KEY".getBytes(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_MacTag_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), "broken".getBytes(),
                AUTHENTICATED_START, ENCRYPTED_START, DATA_END);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_AuthenticatedStart_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START - 1,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_EncryptedStart_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START - 1, DATA_END);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_DataEnd_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END + 1);

        assertFalse(params1.equals(params2));
    }

    public void testHashCode_Success() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertEquals(params1.hashCode(), params2.hashCode());
    }

    public void testHashCode_EncAlgo_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(new String(
                "AES-256/CBC/PKCS7Padding"), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_EncParams_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec("BLAHBLAH".getBytes()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_EncKey_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec("BLAHBLAH".getBytes(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_MacAlgo_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), "BLAHBLAH", null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_MacKey_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec("FAKE_MAC_KEY".getBytes(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_MacTag_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), "broken".getBytes(),
                AUTHENTICATED_START, ENCRYPTED_START, DATA_END);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_AuthenticatedStart_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START - 1,
                ENCRYPTED_START, DATA_END);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_EncryptedStart_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START - 1, DATA_END);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_DataEnd_Failure() throws Exception {
        ContainerEncryptionParams params1 = new ContainerEncryptionParams(ENC_ALGORITHM,
                ENC_PARAMS, ENC_KEY, MAC_ALGORITHM, null, MAC_KEY, MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END);

        ContainerEncryptionParams params2 = new ContainerEncryptionParams(
                new String(ENC_ALGORITHM), new IvParameterSpec(IV_BYTES.clone()),
                new SecretKeySpec(ENC_KEY_BYTES.clone(), "RAW"), new String(MAC_ALGORITHM), null,
                new SecretKeySpec(MAC_KEY_BYTES.clone(), "RAW"), MAC_TAG, AUTHENTICATED_START,
                ENCRYPTED_START, DATA_END + 1);

        assertFalse(params1.hashCode() == params2.hashCode());
    }
}
