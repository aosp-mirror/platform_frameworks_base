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

import com.android.org.conscrypt.NativeCrypto;

import android.content.Context;
import android.text.TextUtils;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.DSAParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * This provides the required parameters needed for initializing the
 * {@code KeyPairGenerator} that works with
 * <a href="{@docRoot}guide/topics/security/keystore.html">Android KeyStore
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
 */
public final class KeyPairGeneratorSpec implements AlgorithmParameterSpec {
    /*
     * These must be kept in sync with system/security/keystore/defaults.h
     */

    /* DSA */
    private static final int DSA_DEFAULT_KEY_SIZE = 1024;
    private static final int DSA_MIN_KEY_SIZE = 512;
    private static final int DSA_MAX_KEY_SIZE = 8192;

    /* EC */
    private static final int EC_DEFAULT_KEY_SIZE = 256;
    private static final int EC_MIN_KEY_SIZE = 192;
    private static final int EC_MAX_KEY_SIZE = 521;

    /* RSA */
    private static final int RSA_DEFAULT_KEY_SIZE = 2048;
    private static final int RSA_MIN_KEY_SIZE = 512;
    private static final int RSA_MAX_KEY_SIZE = 8192;

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

        final int keyTypeInt = KeyStore.getKeyTypeForAlgorithm(keyType);
        if (keySize == -1) {
            keySize = getDefaultKeySizeForType(keyTypeInt);
        }
        checkCorrectParametersSpec(keyTypeInt, keySize, spec);
        checkValidKeySize(keyTypeInt, keySize);

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

    private static int getDefaultKeySizeForType(int keyType) {
        if (keyType == NativeCrypto.EVP_PKEY_DSA) {
            return DSA_DEFAULT_KEY_SIZE;
        } else if (keyType == NativeCrypto.EVP_PKEY_EC) {
            return EC_DEFAULT_KEY_SIZE;
        } else if (keyType == NativeCrypto.EVP_PKEY_RSA) {
            return RSA_DEFAULT_KEY_SIZE;
        }
        throw new IllegalArgumentException("Invalid key type " + keyType);
    }

    private static void checkValidKeySize(int keyType, int keySize) {
        if (keyType == NativeCrypto.EVP_PKEY_DSA) {
            if (keySize < DSA_MIN_KEY_SIZE || keySize > DSA_MAX_KEY_SIZE) {
                throw new IllegalArgumentException("DSA keys must be >= " + DSA_MIN_KEY_SIZE
                        + " and <= " + DSA_MAX_KEY_SIZE);
            }
        } else if (keyType == NativeCrypto.EVP_PKEY_EC) {
            if (keySize < EC_MIN_KEY_SIZE || keySize > EC_MAX_KEY_SIZE) {
                throw new IllegalArgumentException("EC keys must be >= " + EC_MIN_KEY_SIZE
                        + " and <= " + EC_MAX_KEY_SIZE);
            }
        } else if (keyType == NativeCrypto.EVP_PKEY_RSA) {
            if (keySize < RSA_MIN_KEY_SIZE || keySize > RSA_MAX_KEY_SIZE) {
                throw new IllegalArgumentException("RSA keys must be >= " + RSA_MIN_KEY_SIZE
                        + " and <= " + RSA_MAX_KEY_SIZE);
            }
        } else {
            throw new IllegalArgumentException("Invalid key type " + keyType);
        }
    }

    private static void checkCorrectParametersSpec(int keyType, int keySize,
            AlgorithmParameterSpec spec) {
        if (keyType == NativeCrypto.EVP_PKEY_DSA && spec != null) {
            if (!(spec instanceof DSAParameterSpec)) {
                throw new IllegalArgumentException("DSA keys must have DSAParameterSpec specified");
            }
        } else if (keyType == NativeCrypto.EVP_PKEY_RSA && spec != null) {
            if (spec instanceof RSAKeyGenParameterSpec) {
                RSAKeyGenParameterSpec rsaSpec = (RSAKeyGenParameterSpec) spec;
                if (keySize != -1 && keySize != rsaSpec.getKeysize()) {
                    throw new IllegalArgumentException("RSA key size must match: " + keySize
                            + " vs " + rsaSpec.getKeysize());
                }
            } else {
                throw new IllegalArgumentException("RSA may only use RSAKeyGenParameterSpec");
            }
        }
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
     * Returns the key type (e.g., "RSA", "DSA", "EC") specified by this
     * parameter.
     */
    public String getKeyType() {
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
    public AlgorithmParameterSpec getAlgorithmParameterSpec() {
        return mSpec;
    }

    /**
     * Gets the subject distinguished name to be used on the X.509 certificate
     * that will be put in the {@link java.security.KeyStore}.
     */
    public X500Principal getSubjectDN() {
        return mSubjectDN;
    }

    /**
     * Gets the serial number to be used on the X.509 certificate that will be
     * put in the {@link java.security.KeyStore}.
     */
    public BigInteger getSerialNumber() {
        return mSerialNumber;
    }

    /**
     * Gets the start date to be used on the X.509 certificate that will be put
     * in the {@link java.security.KeyStore}.
     */
    public Date getStartDate() {
        return mStartDate;
    }

    /**
     * Gets the end date to be used on the X.509 certificate that will be put in
     * the {@link java.security.KeyStore}.
     */
    public Date getEndDate() {
        return mEndDate;
    }

    /**
     * @hide
     */
    int getFlags() {
        return mFlags;
    }

    /**
     * Returns {@code true} if this parameter will require generated keys to be
     * encrypted in the {@link java.security.KeyStore}.
     */
    public boolean isEncryptionRequired() {
        return (mFlags & KeyStore.FLAG_ENCRYPTED) != 0;
    }

    /**
     * Builder class for {@link KeyPairGeneratorSpec} objects.
     * <p>
     * This will build a parameter spec for use with the <a href="{@docRoot}
     * guide/topics/security/keystore.html">Android KeyStore facility</a>.
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
     */
    public final static class Builder {
        private final Context mContext;

        private String mKeystoreAlias;

        private String mKeyType = "RSA";

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
        public Builder(Context context) {
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
        public Builder setAlias(String alias) {
            if (alias == null) {
                throw new NullPointerException("alias == null");
            }
            mKeystoreAlias = alias;
            return this;
        }

        /**
         * Sets the key type (e.g., RSA, DSA, EC) of the keypair to be created.
         */
        public Builder setKeyType(String keyType) throws NoSuchAlgorithmException {
            if (keyType == null) {
                throw new NullPointerException("keyType == null");
            } else {
                try {
                    KeyStore.getKeyTypeForAlgorithm(keyType);
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
        public Builder setKeySize(int keySize) {
            if (keySize < 0) {
                throw new IllegalArgumentException("keySize < 0");
            }
            mKeySize = keySize;
            return this;
        }

        /**
         * Sets the underlying key type's parameters. This is required for DSA
         * where you must set this to an instance of
         * {@link java.security.spec.DSAParameterSpec}.
         */
        public Builder setAlgorithmParameterSpec(AlgorithmParameterSpec spec) {
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
        public Builder setSubject(X500Principal subject) {
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
        public Builder setSerialNumber(BigInteger serialNumber) {
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
        public Builder setStartDate(Date startDate) {
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
        public Builder setEndDate(Date endDate) {
            if (endDate == null) {
                throw new NullPointerException("endDate == null");
            }
            mEndDate = endDate;
            return this;
        }

        /**
         * Indicates that this key must be encrypted at rest on storage. Note
         * that enabling this will require that the user enable a strong lock
         * screen (e.g., PIN, password) before creating or using the generated
         * key is successful.
         */
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
        public KeyPairGeneratorSpec build() {
            return new KeyPairGeneratorSpec(mContext, mKeystoreAlias, mKeyType, mKeySize, mSpec,
                    mSubjectDN, mSerialNumber, mStartDate, mEndDate, mFlags);
        }
    }
}
