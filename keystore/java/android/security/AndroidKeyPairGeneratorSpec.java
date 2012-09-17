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

package android.security;

import android.content.Context;
import android.text.TextUtils;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * This provides the required parameters needed for initializing the KeyPair
 * generator that works with
 * <a href="{@docRoot}guide/topics/security/keystore.html">Android KeyStore
 * facility</a>.
 * @hide
 */
public class AndroidKeyPairGeneratorSpec implements AlgorithmParameterSpec {
    private final String mKeystoreAlias;

    private final Context mContext;

    private final X500Principal mSubjectDN;

    private final BigInteger mSerialNumber;

    private final Date mStartDate;

    private final Date mEndDate;

    /**
     * Parameter specification for the "{@code AndroidKeyPairGenerator}"
     * instance of the {@link java.security.KeyPairGenerator} API. The
     * {@code context} passed in may be used to pop up some UI to ask the user
     * to unlock or initialize the Android keystore facility.
     * <p>
     * After generation, the {@code keyStoreAlias} is used with the
     * {@link java.security.KeyStore#getEntry(String, java.security.KeyStore.ProtectionParameter)}
     * interface to retrieve the {@link PrivateKey} and its associated
     * {@link Certificate} chain.
     * <p>
     * The KeyPair generator will create a self-signed certificate with the
     * properties of {@code subjectDN} as its X.509v3 Subject Distinguished Name
     * and as its X.509v3 Issuer Distinguished Name, using the specified
     * {@code serialNumber}, and the validity date starting at {@code startDate}
     * and ending at {@code endDate}.
     *
     * @param context Android context for the activity
     * @param keyStoreAlias name to use for the generated key in the Android
     *            keystore
     * @param subjectDN X.509 v3 Subject Distinguished Name
     * @param serialNumber X509 v3 certificate serial number
     * @param startDate the start of the self-signed certificate validity period
     * @param endDate the end date of the self-signed certificate validity
     *            period
     * @throws IllegalArgumentException when any argument is {@code null} or
     *             {@code endDate} is before {@code startDate}.
     */
    public AndroidKeyPairGeneratorSpec(Context context, String keyStoreAlias,
            X500Principal subjectDN, BigInteger serialNumber, Date startDate, Date endDate) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        } else if (TextUtils.isEmpty(keyStoreAlias)) {
            throw new IllegalArgumentException("keyStoreAlias must not be empty");
        } else if (subjectDN == null) {
            throw new IllegalArgumentException("subjectDN == null");
        } else if (serialNumber == null) {
            throw new IllegalArgumentException("serialNumber == null");
        } else if (startDate == null) {
            throw new IllegalArgumentException("startDate == null");
        } else if (endDate == null) {
            throw new IllegalArgumentException("endDate == null");
        } else if (endDate.before(startDate)) {
            throw new IllegalArgumentException("endDate < startDate");
        }

        mContext = context;
        mKeystoreAlias = keyStoreAlias;
        mSubjectDN = subjectDN;
        mSerialNumber = serialNumber;
        mStartDate = startDate;
        mEndDate = endDate;
    }

    /**
     * @hide
     */
    String getKeystoreAlias() {
        return mKeystoreAlias;
    }

    /**
     * @hide
     */
    Context getContext() {
        return mContext;
    }

    /**
     * @hide
     */
    X500Principal getSubjectDN() {
        return mSubjectDN;
    }

    /**
     * @hide
     */
    BigInteger getSerialNumber() {
        return mSerialNumber;
    }

    /**
     * @hide
     */
    Date getStartDate() {
        return mStartDate;
    }

    /**
     * @hide
     */
    Date getEndDate() {
        return mEndDate;
    }
}
