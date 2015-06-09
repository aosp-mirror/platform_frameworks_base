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

import android.app.KeyguardManager;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * This provides the required parameters needed for initializing the
 * {@code KeyPairGenerator} that works with
 * <a href="{@docRoot}training/articles/keystore.html">Android KeyStore
 * facility</a>. The Android KeyStore facility is accessed through a
 * {@link java.security.KeyPairGenerator} API using the {@code AndroidKeyStore}
 * provider. The {@code context} passed in may be used to pop up some UI to ask
 * the user to unlock or initialize the Android KeyStore facility.
 * <p>
 * After generation, the {@code keyStoreAlias} is used with the
 * {@link java.security.KeyStore#getEntry(String, java.security.KeyStore.ProtectionParameter)}
 * interface to retrieve the {@link PrivateKey} and its associated
 * {@link Certificate} chain.
 * <p>
 * The KeyPair generator will create a self-signed certificate with the subject
 * as its X.509v3 Subject Distinguished Name and as its X.509v3 Issuer
 * Distinguished Name along with the other parameters specified with the
 * {@link Builder}.
 * <p>
 * The self-signed X.509 certificate may be replaced at a later time by a
 * certificate signed by a real Certificate Authority.
 *
 * @deprecated Use {@link KeyGenParameterSpec} instead.
 */
@Deprecated
public final class KeyPairGeneratorSpec implements AlgorithmParameterSpec {

    private final Context mContext;

    private final String mKeystoreAlias;

    private final String mKeyType;

    private final int mKeySize;

    private final AlgorithmParameterSpec mSpec;

    private final X500Principal mSubjectDN;

    private final BigInteger mSerialNumber;

    private final Date mStartDate;

    private final Date mEndDate;

    private final int mFlags;

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
     * @param keyType key algorithm to use (RSA, DSA, EC)
     * @param keySize size of key to generate
     * @param spec the underlying key type parameters
     * @param subjectDN X.509 v3 Subject Distinguished Name
     * @param serialNumber X509 v3 certificate serial number
     * @param startDate the start of the self-signed certificate validity period
     * @param endDate the end date of the self-signed certificate validity
     *            period
     * @throws IllegalArgumentException when any argument is {@code null} or
     *             {@code endDate} is before {@code startDate}.
     * @hide should be built with KeyPairGeneratorSpecBuilder
     */
    public KeyPairGeneratorSpec(Context context, String keyStoreAlias, String keyType, int keySize,
            AlgorithmParameterSpec spec, X500Principal subjectDN, BigInteger serialNumber,
            Date startDate, Date endDate, int flags) {
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

        if (endDate.before(startDate)) {
            throw new IllegalArgumentException("endDate < startDate");
        }

        mContext = context;
        mKeystoreAlias = keyStoreAlias;
        mKeyType = keyType;
        mKeySize = keySize;
        mSpec = spec;
        mSubjectDN = subjectDN;
        mSerialNumber = serialNumber;
        mStartDate = startDate;
        mEndDate = endDate;
        mFlags = flags;
    }

    /**
     * Gets the Android context used for operations with this instance.
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Returns the alias that will be used in the {@code java.security.KeyStore}
     * in conjunction with the {@code AndroidKeyStore}.
     */
    public String getKeystoreAlias() {
        return mKeystoreAlias;
    }

    /**
     * Returns the type of key pair (e.g., {@code EC}, {@code RSA}) to be generated. See
     * {@link KeyProperties}.{@code KEY_ALGORITHM} constants.
     */
    @Nullable
    public @KeyProperties.KeyAlgorithmEnum String getKeyType() {
        return mKeyType;
    }

    /**
     * Returns the key size specified by this parameter. For instance, for RSA
     * this will return the modulus size and for EC it will return the field
     * size.
     */
    public int getKeySize() {
        return mKeySize;
    }

    /**
     * Returns the {@link AlgorithmParameterSpec} that will be used for creation
     * of the key pair.
     */
    @NonNull
    public AlgorithmParameterSpec getAlgorithmParameterSpec() {
        return mSpec;
    }

    /**
     * Gets the subject distinguished name to be used on the X.509 certificate
     * that will be put in the {@link java.security.KeyStore}.
     */
    @NonNull
    public X500Principal getSubjectDN() {
        return mSubjectDN;
    }

    /**
     * Gets the serial number to be used on the X.509 certificate that will be
     * put in the {@link java.security.KeyStore}.
     */
    @NonNull
    public BigInteger getSerialNumber() {
        return mSerialNumber;
    }

    /**
     * Gets the start date to be used on the X.509 certificate that will be put
     * in the {@link java.security.KeyStore}.
     */
    @NonNull
    public Date getStartDate() {
        return mStartDate;
    }

    /**
     * Gets the end date to be used on the X.509 certificate that will be put in
     * the {@link java.security.KeyStore}.
     */
    @NonNull
    public Date getEndDate() {
        return mEndDate;
    }

    /**
     * @hide
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Returns {@code true} if the key must be encrypted at rest. This will protect the key pair
     * with the secure lock screen credential (e.g., password, PIN, or pattern).
     *
     * <p>Note that encrypting the key at rest requires that the secure lock screen (e.g., password,
     * PIN, pattern) is set up, otherwise key generation will fail. Moreover, this key will be
     * deleted when the secure lock screen is disabled or reset (e.g., by the user or a Device
     * Administrator). Finally, this key cannot be used until the user unlocks the secure lock
     * screen after boot.
     *
     * @see KeyguardManager#isDeviceSecure()
     */
    public boolean isEncryptionRequired() {
        return (mFlags & KeyStore.FLAG_ENCRYPTED) != 0;
    }

    /**
     * Builder class for {@link KeyPairGeneratorSpec} objects.
     * <p>
     * This will build a parameter spec for use with the <a href="{@docRoot}
     * training/articles/keystore.html">Android KeyStore facility</a>.
     * <p>
     * The required fields must be filled in with the builder.
     * <p>
     * Example:
     *
     * <pre class="prettyprint">
     * Calendar start = new Calendar();
     * Calendar end = new Calendar();
     * end.add(1, Calendar.YEAR);
     *
     * KeyPairGeneratorSpec spec =
     *         new KeyPairGeneratorSpec.Builder(mContext).setAlias(&quot;myKey&quot;)
     *                 .setSubject(new X500Principal(&quot;CN=myKey&quot;)).setSerial(BigInteger.valueOf(1337))
     *                 .setStartDate(start.getTime()).setEndDate(end.getTime()).build();
     * </pre>
     *
     *  @deprecated Use {@link KeyGenParameterSpec.Builder} instead.
     */
    @Deprecated
    public final static class Builder {
        private final Context mContext;

        private String mKeystoreAlias;

        private String mKeyType;

        private int mKeySize = -1;

        private AlgorithmParameterSpec mSpec;

        private X500Principal mSubjectDN;

        private BigInteger mSerialNumber;

        private Date mStartDate;

        private Date mEndDate;

        private int mFlags;

        /**
         * Creates a new instance of the {@code Builder} with the given
         * {@code context}. The {@code context} passed in may be used to pop up
         * some UI to ask the user to unlock or initialize the Android KeyStore
         * facility.
         */
        public Builder(@NonNull Context context) {
            if (context == null) {
                throw new NullPointerException("context == null");
            }
            mContext = context;
        }

        /**
         * Sets the alias to be used to retrieve the key later from a
         * {@link java.security.KeyStore} instance using the
         * {@code AndroidKeyStore} provider.
         */
        @NonNull
        public Builder setAlias(@NonNull String alias) {
            if (alias == null) {
                throw new NullPointerException("alias == null");
            }
            mKeystoreAlias = alias;
            return this;
        }

        /**
         * Sets the type of key pair (e.g., {@code EC}, {@code RSA}) of the key pair to be
         * generated. See {@link KeyProperties}.{@code KEY_ALGORITHM} constants.
         *
         */
        @NonNull
        public Builder setKeyType(@NonNull @KeyProperties.KeyAlgorithmEnum String keyType)
                throws NoSuchAlgorithmException {
            if (keyType == null) {
                throw new NullPointerException("keyType == null");
            } else {
                try {
                    KeyProperties.KeyAlgorithm.toKeymasterAsymmetricKeyAlgorithm(keyType);
                } catch (IllegalArgumentException e) {
                    throw new NoSuchAlgorithmException("Unsupported key type: " + keyType);
                }
            }
            mKeyType = keyType;
            return this;
        }

        /**
         * Sets the key size for the keypair to be created. For instance, for a
         * key type of RSA this will set the modulus size and for a key type of
         * EC it will select a curve with a matching field size.
         */
        @NonNull
        public Builder setKeySize(int keySize) {
            if (keySize < 0) {
                throw new IllegalArgumentException("keySize < 0");
            }
            mKeySize = keySize;
            return this;
        }

        /**
         * Sets the algorithm-specific key generation parameters. For example, for RSA keys
         * this may be an instance of {@link java.security.spec.RSAKeyGenParameterSpec}.
         */
        public Builder setAlgorithmParameterSpec(@NonNull AlgorithmParameterSpec spec) {
            if (spec == null) {
                throw new NullPointerException("spec == null");
            }
            mSpec = spec;
            return this;
        }

        /**
         * Sets the subject used for the self-signed certificate of the
         * generated key pair.
         */
        @NonNull
        public Builder setSubject(@NonNull X500Principal subject) {
            if (subject == null) {
                throw new NullPointerException("subject == null");
            }
            mSubjectDN = subject;
            return this;
        }

        /**
         * Sets the serial number used for the self-signed certificate of the
         * generated key pair.
         */
        @NonNull
        public Builder setSerialNumber(@NonNull BigInteger serialNumber) {
            if (serialNumber == null) {
                throw new NullPointerException("serialNumber == null");
            }
            mSerialNumber = serialNumber;
            return this;
        }

        /**
         * Sets the start of the validity period for the self-signed certificate
         * of the generated key pair.
         */
        @NonNull
        public Builder setStartDate(@NonNull Date startDate) {
            if (startDate == null) {
                throw new NullPointerException("startDate == null");
            }
            mStartDate = startDate;
            return this;
        }

        /**
         * Sets the end of the validity period for the self-signed certificate
         * of the generated key pair.
         */
        @NonNull
        public Builder setEndDate(@NonNull Date endDate) {
            if (endDate == null) {
                throw new NullPointerException("endDate == null");
            }
            mEndDate = endDate;
            return this;
        }

        /**
         * Indicates that this key pair must be encrypted at rest. This will protect the key pair
         * with the secure lock screen credential (e.g., password, PIN, or pattern).
         *
         * <p>Note that this feature requires that the secure lock screen (e.g., password, PIN,
         * pattern) is set up, otherwise key pair generation will fail. Moreover, this key pair will
         * be deleted when the secure lock screen is disabled or reset (e.g., by the user or a
         * Device Administrator). Finally, this key pair cannot be used until the user unlocks the
         * secure lock screen after boot.
         *
         * @see KeyguardManager#isDeviceSecure()
         */
        @NonNull
        public Builder setEncryptionRequired() {
            mFlags |= KeyStore.FLAG_ENCRYPTED;
            return this;
        }

        /**
         * Builds the instance of the {@code KeyPairGeneratorSpec}.
         *
         * @throws IllegalArgumentException if a required field is missing
         * @return built instance of {@code KeyPairGeneratorSpec}
         */
        @NonNull
        public KeyPairGeneratorSpec build() {
            return new KeyPairGeneratorSpec(mContext,
                    mKeystoreAlias,
                    mKeyType,
                    mKeySize,
                    mSpec,
                    mSubjectDN,
                    mSerialNumber,
                    mStartDate,
                    mEndDate,
                    mFlags);
        }
    }
}
