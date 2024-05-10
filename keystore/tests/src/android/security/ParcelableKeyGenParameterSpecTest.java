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

package android.security;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import android.os.Parcel;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.ParcelableKeyGenParameterSpec;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/** Unit tests for {@link ParcelableKeyGenParameterSpec}. */
@RunWith(AndroidJUnit4.class)
public final class ParcelableKeyGenParameterSpecTest {
    static final String ALIAS = "keystore-alias";
    static final String ANOTHER_ALIAS = "another-keystore-alias";
    static final int KEY_PURPOSES = KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY;
    static final int KEYSIZE = 2048;
    static final X500Principal SUBJECT = new X500Principal("CN=subject");
    static final BigInteger SERIAL = new BigInteger("1234567890");
    static final Date NOT_BEFORE = new Date(1511799590);
    static final Date NOT_AFTER = new Date(1511899590);
    static final Date KEY_VALIDITY_START = new Date(1511799591);
    static final Date KEY_VALIDITY_FOR_ORIG_END = new Date(1511799593);
    static final Date KEY_VALIDITY_FOR_CONSUMPTION_END = new Date(1511799594);
    static final String DIGEST = KeyProperties.DIGEST_SHA256;
    static final String ENCRYPTION_PADDING = KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1;
    static final String SIGNATURE_PADDING = KeyProperties.SIGNATURE_PADDING_RSA_PSS;
    static final String BLOCK_MODE = KeyProperties.BLOCK_MODE_CBC;
    static final int USER_AUTHENTICATION_DURATION = 300;
    static final byte[] ATTESTATION_CHALLENGE = new byte[] {'c', 'h'};

    public static KeyGenParameterSpec configureDefaultSpec() {
        return new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES)
                .setNamespace(KeyProperties.NAMESPACE_WIFI)
                .setKeySize(KEYSIZE)
                .setCertificateSubject(SUBJECT)
                .setCertificateSerialNumber(SERIAL)
                .setCertificateNotBefore(NOT_BEFORE)
                .setCertificateNotAfter(NOT_AFTER)
                .setKeyValidityStart(KEY_VALIDITY_START)
                .setKeyValidityForOriginationEnd(KEY_VALIDITY_FOR_ORIG_END)
                .setKeyValidityForConsumptionEnd(KEY_VALIDITY_FOR_CONSUMPTION_END)
                .setDigests(DIGEST)
                .setEncryptionPaddings(ENCRYPTION_PADDING)
                .setSignaturePaddings(SIGNATURE_PADDING)
                .setBlockModes(BLOCK_MODE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(USER_AUTHENTICATION_DURATION)
                .setAttestationChallenge(ATTESTATION_CHALLENGE)
                .setUniqueIdIncluded(true)
                .setUserAuthenticationValidWhileOnBody(true)
                .setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(true)
                .setUserConfirmationRequired(true)
                .setUnlockedDeviceRequired(true)
                .setCriticalToDeviceEncryption(true)
                .build();
    }

    public static void validateSpecValues(KeyGenParameterSpec spec,
            @KeyProperties.Namespace int namespace, String alias) {
        assertThat(spec.getKeystoreAlias(), is(alias));
        assertThat(spec.getPurposes(), is(KEY_PURPOSES));
        assertThat(spec.getNamespace(), is(namespace));
        assertThat(spec.getKeySize(), is(KEYSIZE));
        assertThat(spec.getCertificateSubject(), is(SUBJECT));
        assertThat(spec.getCertificateSerialNumber(), is(SERIAL));
        assertThat(spec.getCertificateNotBefore(), is(NOT_BEFORE));
        assertThat(spec.getCertificateNotAfter(), is(NOT_AFTER));
        assertThat(spec.getKeyValidityStart(), is(KEY_VALIDITY_START));
        assertThat(spec.getKeyValidityForOriginationEnd(), is(KEY_VALIDITY_FOR_ORIG_END));
        assertThat(spec.getKeyValidityForConsumptionEnd(), is(KEY_VALIDITY_FOR_CONSUMPTION_END));
        assertThat(spec.getDigests(), is(new String[] {DIGEST}));
        assertThat(spec.isMgf1DigestsSpecified(), is(false));
        assertThat(spec.getEncryptionPaddings(), is(new String[] {ENCRYPTION_PADDING}));
        assertThat(spec.getSignaturePaddings(), is(new String[] {SIGNATURE_PADDING}));
        assertThat(spec.getBlockModes(), is(new String[] {BLOCK_MODE}));
        assertThat(spec.isRandomizedEncryptionRequired(), is(true));
        assertThat(spec.isUserAuthenticationRequired(), is(true));
        assertThat(
                spec.getUserAuthenticationValidityDurationSeconds(),
                is(USER_AUTHENTICATION_DURATION));
        assertThat(spec.getAttestationChallenge(), is(ATTESTATION_CHALLENGE));
        assertThat(spec.isUniqueIdIncluded(), is(true));
        assertThat(spec.isUserAuthenticationValidWhileOnBody(), is(true));
        assertThat(spec.isInvalidatedByBiometricEnrollment(), is(true));
        assertThat(spec.isStrongBoxBacked(), is(true));
        assertThat(spec.isUserConfirmationRequired(), is(true));
        assertThat(spec.isUnlockedDeviceRequired(), is(true));
        assertThat(spec.isCriticalToDeviceEncryption(), is(true));
    }

    private Parcel parcelForReading(ParcelableKeyGenParameterSpec spec) {
        Parcel parcel = Parcel.obtain();
        spec.writeToParcel(parcel, spec.describeContents());

        parcel.setDataPosition(0);
        return parcel;
    }

    @Test
    public void testParcelingWithAllValues() {
        ParcelableKeyGenParameterSpec spec =
            new ParcelableKeyGenParameterSpec(configureDefaultSpec());
        Parcel parcel = parcelForReading(spec);
        ParcelableKeyGenParameterSpec fromParcel =
            ParcelableKeyGenParameterSpec.CREATOR.createFromParcel(parcel);
        validateSpecValues(fromParcel.getSpec(), KeyProperties.NAMESPACE_WIFI, ALIAS);
        assertThat(parcel.dataAvail(), is(0));
    }

    @Test
    public void testParcelingWithNullValues() {
        ParcelableKeyGenParameterSpec spec = new ParcelableKeyGenParameterSpec(
            new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES).build());

        Parcel parcel = parcelForReading(spec);
        KeyGenParameterSpec fromParcel = ParcelableKeyGenParameterSpec.CREATOR
            .createFromParcel(parcel)
            .getSpec();
        assertThat(fromParcel.getKeystoreAlias(), is(ALIAS));
        assertThat(fromParcel.getPurposes(), is(KEY_PURPOSES));
        assertThat(fromParcel.getCertificateNotBefore(), is(new Date(0L)));
        assertThat(fromParcel.getCertificateNotAfter(), is(new Date(2461449600000L)));
        assertThat(parcel.dataAvail(), is(0));
    }

    @Test
    public void testParcelingRSAAlgoParameter() {
        RSAKeyGenParameterSpec rsaSpec =
                new RSAKeyGenParameterSpec(2048, new BigInteger("5231123"));
        ParcelableKeyGenParameterSpec spec = new ParcelableKeyGenParameterSpec(
            new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES)
            .setAlgorithmParameterSpec(rsaSpec)
            .build());

        Parcel parcel = parcelForReading(spec);
        KeyGenParameterSpec fromParcel =
            ParcelableKeyGenParameterSpec.CREATOR.createFromParcel(parcel).getSpec();
        RSAKeyGenParameterSpec parcelSpec =
                (RSAKeyGenParameterSpec) fromParcel.getAlgorithmParameterSpec();
        // Compare individual fields as RSAKeyGenParameterSpec, on android, does not
        // implement equals()
        assertEquals(parcelSpec.getKeysize(), rsaSpec.getKeysize());
        assertEquals(parcelSpec.getPublicExponent(), rsaSpec.getPublicExponent());
    }

    @Test
    public void testParcelingECAlgoParameter() {
        ECGenParameterSpec ecSpec = new ECGenParameterSpec("P-256");
        ParcelableKeyGenParameterSpec spec = new ParcelableKeyGenParameterSpec(
                new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES)
                        .setAlgorithmParameterSpec(ecSpec)
                        .build());
        Parcel parcel = parcelForReading(spec);
        KeyGenParameterSpec fromParcel =
            ParcelableKeyGenParameterSpec.CREATOR.createFromParcel(parcel).getSpec();
        // Compare individual fields as ECGenParameterSpec, on android, does not
        // implement equals()
        ECGenParameterSpec parcelSpec = (ECGenParameterSpec) fromParcel.getAlgorithmParameterSpec();
        assertEquals(parcelSpec.getName(), ecSpec.getName());
    }

    @Test
    public void testParcelingMgf1Digests() {
        String[] mgf1Digests =
                new String[] {KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256};

        ParcelableKeyGenParameterSpec spec = new ParcelableKeyGenParameterSpec(
                new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES)
                        .setMgf1Digests(mgf1Digests)
                        .build());
        Parcel parcel = parcelForReading(spec);
        KeyGenParameterSpec fromParcel =
                ParcelableKeyGenParameterSpec.CREATOR.createFromParcel(parcel).getSpec();
        assertArrayEquals(fromParcel.getMgf1Digests().toArray(), mgf1Digests);
    }
}
