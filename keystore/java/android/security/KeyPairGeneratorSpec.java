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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.text.TextUtils;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * {@link AlgorithmParameterSpec} for initializing a {@link KeyPairGenerator} of the
 * <a href="{@docRoot}training/articles/keystore.html">Android KeyStore facility</a>. This class
 * specifies whether user authentication is required for using the private key, what uses the
 * private key is authorized for (e.g., only for signing -- decryption not permitted), whether the
 * private key should be encrypted at rest, the private key's and validity start and end dates.
 *
 * <p>To generate a key pair, create an instance of this class using the {@link Builder}, initialize
 * a {@code KeyPairGenerator} of the desired key type (e.g., {@code EC} or {@code RSA}) from the
 * {@code AndroidKeyStore} provider with the {@code KeyPairGeneratorSpec} instance, and then
 * generate a key pair using {@link KeyPairGenerator#generateKeyPair()}.
 *
 * <p>The generated key pair will be returned by the {@code KeyPairGenerator} and also stored in the
 * Android KeyStore under the alias specified in this {@code KeyPairGeneratorSpec}. To obtain the
 * private key from the Android KeyStore use
 * {@link java.security.KeyStore#getKey(String, char[]) KeyStore.getKey(String, null)} or
 * {@link java.security.KeyStore#getEntry(String, java.security.KeyStore.ProtectionParameter) KeyStore.getEntry(String, null)}.
 * To obtain the public key from the Android KeyStore use
 * {@link java.security.KeyStore#getCertificate(String)} and then
 * {@link Certificate#getPublicKey()}.
 *
 * <p>A self-signed X.509 certificate will be also generated and stored in the Android KeyStore.
 * This is because the {@link java.security.KeyStore} abstraction does not support storing key pairs
 * without a certificate. The subject, serial number, and validity dates of the certificate can be
 * specified in this {@code KeyPairGeneratorSpec}. The self-signed certificate may be replaced at a
 * later time by a certificate signed by a Certificate Authority (CA).
 *
 * <p>NOTE: The key material of the private keys generating using the {@code KeyPairGeneratorSpec}
 * is not accessible. The key material of the public keys is accessible.
 *
 * <p><h3>Example</h3>
 * The following example illustrates how to generate an EC key pair in the Android KeyStore under
 * alias {@code key2} authorized to be used only for signing using SHA-256, SHA-384, or SHA-512
 * digest and only if the user has been authenticated within the last five minutes.
 * <pre> {@code
 * KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(
 *         KeyStoreKeyProperties.Algorithm.EC,
 *         "AndroidKeyStore");
 * keyPairGenerator.initialize(
 *         new KeyGeneratorSpec.Builder(context)
 *                 .setAlias("key2")
 *                 .setPurposes(KeyStoreKeyProperties.Purpose.SIGN
 *                         | KeyStoreKeyProperties.Purpose.VERIFY)
 *                 .setDigests(KeyStoreKeyProperties.Digest.SHA256
 *                         | KeyStoreKeyProperties.Digest.SHA384
 *                         | KeyStoreKeyProperties.Digest.SHA512)
 *                 // Only permit this key to be used if the user authenticated
 *                 // within the last five minutes.
 *                 .setUserAuthenticationRequired(true)
 *                 .setUserAuthenticationValidityDurationSeconds(5 * 60)
 *                 .build());
 * KeyPair keyPair = keyPairGenerator.generateKey();
 *
 * // The key pair can also be obtained from the Android KeyStore any time as follows:
 * KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
 * keyStore.load(null);
 * PrivateKey privateKey = (PrivateKey) keyStore.getKey("key2", null);
 * PublicKey publicKey = keyStore.getCertificate("key2").getPublicKey();
 * }</pre>
 */
public final class KeyPairGeneratorSpec implements AlgorithmParameterSpec {

    private static final X500Principal DEFAULT_CERT_SUBJECT = new X500Principal("CN=fake");
    private static final BigInteger DEFAULT_CERT_SERIAL_NUMBER = new BigInteger("1");
    private static final Date DEFAULT_CERT_NOT_BEFORE = new Date(0L); // Jan 1 1970
    private static final Date DEFAULT_CERT_NOT_AFTER = new Date(2461449600000L); // Jan 1 2048

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

    private final Date mKeyValidityStart;

    private final Date mKeyValidityForOriginationEnd;

    private final Date mKeyValidityForConsumptionEnd;

    private final @KeyStoreKeyProperties.PurposeEnum int mPurposes;

    private final @KeyStoreKeyProperties.DigestEnum String[] mDigests;

    private final @KeyStoreKeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;

    private final @KeyStoreKeyProperties.SignaturePaddingEnum String[] mSignaturePaddings;

    private final @KeyStoreKeyProperties.BlockModeEnum String[] mBlockModes;

    private final boolean mRandomizedEncryptionRequired;

    private final boolean mUserAuthenticationRequired;

    private final int mUserAuthenticationValidityDurationSeconds;

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
     * @param keyType key algorithm to use (EC, RSA)
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
            Date startDate, Date endDate, int flags,
            Date keyValidityStart,
            Date keyValidityForOriginationEnd,
            Date keyValidityForConsumptionEnd,
            @KeyStoreKeyProperties.PurposeEnum int purposes,
            @KeyStoreKeyProperties.DigestEnum String[] digests,
            @KeyStoreKeyProperties.EncryptionPaddingEnum String[] encryptionPaddings,
            @KeyStoreKeyProperties.SignaturePaddingEnum String[] signaturePaddings,
            @KeyStoreKeyProperties.BlockModeEnum String[] blockModes,
            boolean randomizedEncryptionRequired,
            boolean userAuthenticationRequired,
            int userAuthenticationValidityDurationSeconds) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        } else if (TextUtils.isEmpty(keyStoreAlias)) {
            throw new IllegalArgumentException("keyStoreAlias must not be empty");
        } else if ((userAuthenticationValidityDurationSeconds < 0)
                && (userAuthenticationValidityDurationSeconds != -1)) {
            throw new IllegalArgumentException(
                    "userAuthenticationValidityDurationSeconds must not be negative");
        }

        if (subjectDN == null) {
            subjectDN = DEFAULT_CERT_SUBJECT;
        }
        if (startDate == null) {
            startDate = DEFAULT_CERT_NOT_BEFORE;
        }
        if (endDate == null) {
            endDate = DEFAULT_CERT_NOT_AFTER;
        }
        if (serialNumber == null) {
            serialNumber = DEFAULT_CERT_SERIAL_NUMBER;
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
        mKeyValidityStart = keyValidityStart;
        mKeyValidityForOriginationEnd = keyValidityForOriginationEnd;
        mKeyValidityForConsumptionEnd = keyValidityForConsumptionEnd;
        mPurposes = purposes;
        mDigests = ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(digests));
        mEncryptionPaddings =
                ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(encryptionPaddings));
        mSignaturePaddings = ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(signaturePaddings));
        mBlockModes = ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(blockModes));
        mRandomizedEncryptionRequired = randomizedEncryptionRequired;
        mUserAuthenticationRequired = userAuthenticationRequired;
        mUserAuthenticationValidityDurationSeconds = userAuthenticationValidityDurationSeconds;
    }

    /**
     * TODO: Remove this constructor once tests are switched over to the new one above.
     * @hide
     */
    public KeyPairGeneratorSpec(Context context, String keyStoreAlias, String keyType, int keySize,
            AlgorithmParameterSpec spec, X500Principal subjectDN, BigInteger serialNumber,
            Date startDate, Date endDate, int flags) {

        this(context,
                keyStoreAlias,
                keyType,
                keySize,
                spec,
                subjectDN,
                serialNumber,
                startDate,
                endDate,
                flags,
                startDate,
                endDate,
                endDate,
                0, // purposes
                null, // digests
                null, // encryption paddings
                null, // signature paddings
                null, // block modes
                false, // randomized encryption required
                false, // user authentication required
                -1 // user authentication validity duration (seconds)
                );
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
     * Returns the key type (e.g., "EC", "RSA") specified by this parameter.
     */
    @Nullable
    public @KeyStoreKeyProperties.AlgorithmEnum String getKeyType() {
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
    int getFlags() {
        return mFlags;
    }

    /**
     * Returns {@code true} if the key must be encrypted at rest. This will protect the key pair
     * with the secure lock screen credential (e.g., password, PIN, or pattern).
     */
    public boolean isEncryptionRequired() {
        return (mFlags & KeyStore.FLAG_ENCRYPTED) != 0;
    }

    /**
     * Gets the time instant before which the key pair is not yet valid.
     *
     * @return instant or {@code null} if not restricted.
     */
    @Nullable
    public Date getKeyValidityStart() {
        return mKeyValidityStart;
    }

    /**
     * Gets the time instant after which the key pair is no longer valid for decryption and
     * verification.
     *
     * @return instant or {@code null} if not restricted.
     */
    @Nullable
    public Date getKeyValidityForConsumptionEnd() {
        return mKeyValidityForConsumptionEnd;
    }

    /**
     * Gets the time instant after which the key pair is no longer valid for encryption and signing.
     *
     * @return instant or {@code null} if not restricted.
     */
    @Nullable
    public Date getKeyValidityForOriginationEnd() {
        return mKeyValidityForOriginationEnd;
    }

    /**
     * Gets the set of purposes for which the key can be used.
     */
    public @KeyStoreKeyProperties.PurposeEnum int getPurposes() {
        return mPurposes;
    }

    /**
     * Gets the set of digest algorithms with which the key can be used.
     */
    @NonNull
    public @KeyStoreKeyProperties.DigestEnum String[] getDigests() {
        return ArrayUtils.cloneIfNotEmpty(mDigests);
    }

    /**
     * Gets the set of padding schemes with which the key can be used when encrypting/decrypting.
     */
    @NonNull
    public @KeyStoreKeyProperties.EncryptionPaddingEnum String[] getEncryptionPaddings() {
        return ArrayUtils.cloneIfNotEmpty(mEncryptionPaddings);
    }

    /**
     * Gets the set of padding schemes with which the key can be used when signing/verifying.
     */
    @NonNull
    public @KeyStoreKeyProperties.SignaturePaddingEnum String[] getSignaturePaddings() {
        return ArrayUtils.cloneIfNotEmpty(mSignaturePaddings);
    }

    /**
     * Gets the set of block modes with which the key can be used.
     */
    @NonNull
    public @KeyStoreKeyProperties.BlockModeEnum String[] getBlockModes() {
        return ArrayUtils.cloneIfNotEmpty(mBlockModes);
    }

    /**
     * Returns {@code true} if encryption using this key must be sufficiently randomized to produce
     * different ciphertexts for the same plaintext every time. The formal cryptographic property
     * being required is <em>indistinguishability under chosen-plaintext attack ({@code
     * IND-CPA})</em>. This property is important because it mitigates several classes of
     * weaknesses due to which ciphertext may leak information about plaintext.  For example, if a
     * given plaintext always produces the same ciphertext, an attacker may see the repeated
     * ciphertexts and be able to deduce something about the plaintext.
     */
    public boolean isRandomizedEncryptionRequired() {
        return mRandomizedEncryptionRequired;
    }

    /**
     * Returns {@code true} if user authentication is required for this key to be used.
     *
     * <p>This restriction applies only to private key operations. Public key operations are not
     * restricted.
     *
     * @see #getUserAuthenticationValidityDurationSeconds()
     */
    public boolean isUserAuthenticationRequired() {
        return mUserAuthenticationRequired;
    }

    /**
     * Gets the duration of time (seconds) for which this key can be used after the user is
     * successfully authenticated. This has effect only if user authentication is required.
     *
     * <p>This restriction applies only to private key operations. Public key operations are not
     * restricted.
     *
     * @return duration in seconds or {@code -1} if authentication is required for every use of the
     *         key.
     *
     * @see #isUserAuthenticationRequired()
     */
    public int getUserAuthenticationValidityDurationSeconds() {
        return mUserAuthenticationValidityDurationSeconds;
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
     */
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

        private Date mKeyValidityStart;

        private Date mKeyValidityForOriginationEnd;

        private Date mKeyValidityForConsumptionEnd;

        private @KeyStoreKeyProperties.PurposeEnum int mPurposes;

        private @KeyStoreKeyProperties.DigestEnum String[] mDigests;

        private @KeyStoreKeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;

        private @KeyStoreKeyProperties.SignaturePaddingEnum String[] mSignaturePaddings;

        private @KeyStoreKeyProperties.BlockModeEnum String[] mBlockModes;

        private boolean mRandomizedEncryptionRequired = true;

        private boolean mUserAuthenticationRequired;

        private int mUserAuthenticationValidityDurationSeconds = -1;

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
         * Sets the key type (e.g., EC, RSA) of the keypair to be created.
         */
        @NonNull
        public Builder setKeyType(@NonNull @KeyStoreKeyProperties.AlgorithmEnum String keyType)
                throws NoSuchAlgorithmException {
            if (keyType == null) {
                throw new NullPointerException("keyType == null");
            } else {
                if (KeyStore.getKeyTypeForAlgorithm(keyType) == -1) {
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
         *
         * <p>The subject must be specified on API Level
         * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1 LOLLIPOP_MR1} and older platforms. On
         * newer platforms the subject defaults to {@code CN=fake} if not specified.
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
         *
         * <p>The serial number must be specified on API Level
         * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1 LOLLIPOP_MR1} and older platforms. On
         * newer platforms the serial number defaults to {@code 1} if not specified.
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
         *
         * <p>The date must be specified on API Level
         * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1 LOLLIPOP_MR1} and older platforms. On
         * newer platforms the date defaults to {@code Jan 1 1970} if not specified.
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
         *
         * <p>The date must be specified on API Level
         * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1 LOLLIPOP_MR1} and older platforms. On
         * newer platforms the date defaults to {@code Jan 1 2048} if not specified.
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
         * Sets the time instant before which the key is not yet valid.
         *
         * <p>By default, the key is valid at any instant.
         *
         * <p><b>NOTE: This has currently no effect.
         *
         * @see #setKeyValidityEnd(Date)
         */
        @NonNull
        public Builder setKeyValidityStart(Date startDate) {
            mKeyValidityStart = startDate;
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid.
         *
         * <p>By default, the key is valid at any instant.
         *
         * <p><b>NOTE: This has currently no effect.
         *
         * @see #setKeyValidityStart(Date)
         * @see #setKeyValidityForConsumptionEnd(Date)
         * @see #setKeyValidityForOriginationEnd(Date)
         */
        @NonNull
        public Builder setKeyValidityEnd(Date endDate) {
            setKeyValidityForOriginationEnd(endDate);
            setKeyValidityForConsumptionEnd(endDate);
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid for encryption and signing.
         *
         * <p>By default, the key is valid at any instant.
         *
         * <p><b>NOTE: This has currently no effect.
         *
         * @see #setKeyValidityForConsumptionEnd(Date)
         */
        @NonNull
        public Builder setKeyValidityForOriginationEnd(Date endDate) {
            mKeyValidityForOriginationEnd = endDate;
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid for decryption and
         * verification.
         *
         * <p>By default, the key is valid at any instant.
         *
         * <p><b>NOTE: This has currently no effect.
         *
         * @see #setKeyValidityForOriginationEnd(Date)
         */
        @NonNull
        public Builder setKeyValidityForConsumptionEnd(Date endDate) {
            mKeyValidityForConsumptionEnd = endDate;
            return this;
        }

        /**
         * Sets the set of purposes for which the key can be used.
         *
         * <p>This must be specified for all keys. There is no default.
         *
         * <p>If the set of purposes for which the key can be used does not contain
         * {@link KeyStoreKeyProperties.Purpose#SIGN}, the self-signed certificate generated by
         * {@link KeyPairGenerator} of {@code AndroidKeyStore} provider will contain an invalid
         * signature. This is OK if the certificate is only used for obtaining the public key from
         * Android KeyStore.
         *
         * <p><b>NOTE: This has currently no effect.
         */
        @NonNull
        public Builder setPurposes(@KeyStoreKeyProperties.PurposeEnum int purposes) {
            mPurposes = purposes;
            return this;
        }

        /**
         * Sets the set of digests with which the key can be used when signing/verifying. Attempts
         * to use the key with any other digest will be rejected.
         *
         * <p>This must be specified for keys which are used for signing/verification.
         *
         * <p><b>NOTE: This has currently no effect.
         */
        @NonNull
        public Builder setDigests(@KeyStoreKeyProperties.DigestEnum String... digests) {
            mDigests = ArrayUtils.cloneIfNotEmpty(digests);
            return this;
        }

        /**
         * Sets the set of padding schemes with which the key can be used when
         * encrypting/decrypting. Attempts to use the key with any other padding scheme will be
         * rejected.
         *
         * <p>This must be specified for keys which are used for encryption/decryption.
         *
         * <p><b>NOTE: This has currently no effect.
         */
        @NonNull
        public Builder setEncryptionPaddings(
                @KeyStoreKeyProperties.EncryptionPaddingEnum String... paddings) {
            mEncryptionPaddings = ArrayUtils.cloneIfNotEmpty(paddings);
            return this;
        }

        /**
         * Sets the set of padding schemes with which the key can be used when
         * signing/verifying. Attempts to use the key with any other padding scheme will be
         * rejected.
         *
         * <p>This must be specified for RSA keys which are used for signing/verification.
         *
         * <p><b>NOTE: This has currently no effect.
         */
        @NonNull
        public Builder setSignaturePaddings(
                @KeyStoreKeyProperties.SignaturePaddingEnum String... paddings) {
            mSignaturePaddings = ArrayUtils.cloneIfNotEmpty(paddings);
            return this;
        }

        /**
         * Sets the set of block modes with which the key can be used when encrypting/decrypting.
         * Attempts to use the key with any other block modes will be rejected.
         *
         * <p>This must be specified for encryption/decryption keys.
         *
         * <p><b>NOTE: This has currently no effect.
         */
        @NonNull
        public Builder setBlockModes(@KeyStoreKeyProperties.BlockModeEnum String... blockModes) {
            mBlockModes = ArrayUtils.cloneIfNotEmpty(blockModes);
            return this;
        }

        /**
         * Sets whether encryption using this key must be sufficiently randomized to produce
         * different ciphertexts for the same plaintext every time. The formal cryptographic
         * property being required is <em>indistinguishability under chosen-plaintext attack
         * ({@code IND-CPA})</em>. This property is important because it mitigates several classes
         * of weaknesses due to which ciphertext may leak information about plaintext. For example,
         * if a given plaintext always produces the same ciphertext, an attacker may see the
         * repeated ciphertexts and be able to deduce something about the plaintext.
         *
         * <p>By default, {@code IND-CPA} is required.
         *
         * <p>When {@code IND-CPA} is required, encryption/decryption transformations which do not
         * offer {@code IND-CPA}, such as RSA without padding, are prohibited.
         *
         * <p>Before disabling this requirement, consider the following approaches instead:
         * <ul>
         * <li>If you are using RSA encryption without padding, consider switching to padding
         * schemes which offer {@code IND-CPA}, such as PKCS#1 or OAEP.</li>
         * </ul>
         *
         * <p><b>NOTE: This has currently no effect.
         */
        @NonNull
        public Builder setRandomizedEncryptionRequired(boolean required) {
            mRandomizedEncryptionRequired = required;
            return this;
        }

        /**
         * Sets whether user authentication is required to use this key.
         *
         * <p>By default, the key can be used without user authentication.
         *
         * <p>When user authentication is required, the user authorizes the use of the key by
         * authenticating to this Android device using a subset of their secure lock screen
         * credentials. Different authentication methods are used depending on whether the every
         * use of the key must be authenticated (as specified by
         * {@link #setUserAuthenticationValidityDurationSeconds(int)}).
         * <a href="{@docRoot}training/articles/keystore.html#UserAuthentication">More
         * information</a>.
         *
         * <p>This restriction applies only to private key operations. Public key operations are not
         * restricted.
         *
         * <p><b>NOTE: This has currently no effect.
         *
         * @see #setUserAuthenticationValidityDurationSeconds(int)
         */
        @NonNull
        public Builder setUserAuthenticationRequired(boolean required) {
            mUserAuthenticationRequired = required;
            return this;
        }

        /**
         * Sets the duration of time (seconds) for which this key can be used after the user is
         * successfully authenticated. This has effect only if user authentication is required.
         *
         * <p>By default, the user needs to authenticate for every use of the key.
         *
         * <p>This restriction applies only to private key operations. Public key operations are not
         * restricted.
         *
         * <p><b>NOTE: This has currently no effect.
         *
         * @param seconds duration in seconds or {@code -1} if the user needs to authenticate for
         *        every use of the key.
         *
         * @see #setUserAuthenticationRequired(boolean)
         */
        @NonNull
        public Builder setUserAuthenticationValidityDurationSeconds(
                @IntRange(from = -1) int seconds) {
            mUserAuthenticationValidityDurationSeconds = seconds;
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
                    mFlags,
                    mKeyValidityStart,
                    mKeyValidityForOriginationEnd,
                    mKeyValidityForConsumptionEnd,
                    mPurposes,
                    mDigests,
                    mEncryptionPaddings,
                    mSignaturePaddings,
                    mBlockModes,
                    mRandomizedEncryptionRequired,
                    mUserAuthenticationRequired,
                    mUserAuthenticationValidityDurationSeconds);
        }
    }
}
