package android.security;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * {@link AlgorithmParameterSpec} for ECIES (Integrated Encryption Scheme using Elliptic Curve
 * cryptography) based on {@code ISO/IEC 18033-2}.
 *
 * <p>ECIES is a hybrid authenticated encryption scheme. Encryption is performed using an Elliptic
 * Curve (EC) public key. The resulting ciphertext can be decrypted only using the corresponding EC
 * private key. The scheme is called hybrid because the EC key is only used to securely encapsulate
 * symmetric key material. Encryption of plaintext and authentication of the corresponding
 * ciphertext is performed using symmetric cryptography.
 *
 * <p>Encryption using ECIES consists of two stages:
 * <ol>
 * <li>Key Encapsulation Mechanism (KEM) randomly generates symmetric key material and securely
 * encapsulates it in the output so that it can be extracted by the KEM when decrypting.
 * Encapsulated key material is represented in the output as an EC point.</li>
 * <li>The above symmetric key material is used by Data Encapsulation Mechanism (DEM) to encrypt the
 * provided plaintext and authenticate the ciphertext. The resulting authenticated ciphertext is
 * then output. When decrypting, the DEM first authenticates the ciphertext and, only if it
 * authenticates, decrypts the ciphertext and outputs the plaintext.</li>
 * </ol>
 *
 * <p>Details of KEM:
 * <ul>
 * <li>Only curves with cofactor of {@code 1} are supported.</li>
 * <li>{@code CheckMode}, {@code OldCofactorMode}, {@code CofactorMode}, and {@code SingleHashMode}
 * are {@code 0}.
 * <li>Point format is specified by {@link #getKemPointFormat()}.</li>
 * <li>KDF algorithm is specified by {@link #getKemKdfAlgorithm()}.</li>
 * </ul>
 *
 * <p>Details of DEM:
 * <ul>
 * <li>Only DEM1-like mechanism is supported, with its symmetric cipher (SC) specified by
 * {@link #getDemCipherTransformation()} (e.g., {@code AES/CBC/NoPadding} for standard DEM1) and
 * MAC algorithm specified by {@link #getDemMacAlgorithm()} (e.g., {@code HmacSHA1} for standard
 * DEM1).</li>
 * </ul>
 */
public class EcIesParameterSpec implements AlgorithmParameterSpec {

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        POINT_FORMAT_UNSPECIFIED,
        POINT_FORMAT_UNCOMPRESSED,
        POINT_FORMAT_COMPRESSED,
        })
    public @interface PointFormatEnum {}

    /** Unspecified EC point format. */
    public static final int POINT_FORMAT_UNSPECIFIED = -1;

    /**
     * Uncompressed EC point format: both coordinates are stored separately.
     *
     * <p>The wire format is byte {@code 0x04} followed by binary representation of the {@code x}
     * coordinate followed by binary representation of the {@code y} coordinate. See
     * {@code ISO 18033-2} section {@code 5.4.3}.
     */
    public static final int POINT_FORMAT_UNCOMPRESSED = 0;

    /**
     * Compressed EC point format: only one coordinate is stored.
     *
     * <p>The wire format is byte {@code 0x02} or {@code 0x03} (depending on the value of the stored
     * coordinate) followed by the binary representation of the {@code x} coordinate. See
     * {@code ISO 18033-2} section {@code 5.4.3}.
     */
    public static final int POINT_FORMAT_COMPRESSED = 1;

    /**
     * Default parameter spec: compressed point format, {@code HKDFwithSHA256}, DEM uses 128-bit AES
     * GCM.
     */
    public static final EcIesParameterSpec DEFAULT = new EcIesParameterSpec(
            POINT_FORMAT_COMPRESSED,
            "HKDFwithSHA256",
            "AES/GCM/NoPadding",
            128,
            null,
            0);

    private final @PointFormatEnum int mKemPointFormat;
    private final String mKemKdfAlgorithm;
    private final String mDemCipherTransformation;
    private final int mDemCipherKeySize;
    private final String mDemMacAlgorithm;
    private final int mDemMacKeySize;

    private EcIesParameterSpec(
            @PointFormatEnum int kemPointFormat,
            String kemKdfAlgorithm,
            String demCipherTransformation,
            int demCipherKeySize,
            String demMacAlgorithm,
            int demMacKeySize) {
        mKemPointFormat = kemPointFormat;
        mKemKdfAlgorithm = kemKdfAlgorithm;
        mDemCipherTransformation = demCipherTransformation;
        mDemCipherKeySize = demCipherKeySize;
        mDemMacAlgorithm = demMacAlgorithm;
        mDemMacKeySize = demMacKeySize;
    }

    /**
     * Returns KEM EC point wire format or {@link #POINT_FORMAT_UNSPECIFIED} if not specified.
     */
    public @PointFormatEnum int getKemPointFormat() {
        return mKemPointFormat;
    }

    /**
     * Returns KEM KDF algorithm (e.g., {@code HKDFwithSHA256} or {@code KDF1withSHA1}) or
     * {@code null} if not specified.
     */
    @Nullable
    public String getKemKdfAlgorithm() {
        return mKemKdfAlgorithm;
    }

    /**
     * Returns DEM {@link Cipher} transformation (e.g., {@code AES/GCM/NoPadding} or
     * {@code AES/CBC/PKCS7Padding}) or {@code null} if not specified.
     *
     * @see Cipher#getInstance(String)
     * @see #getDemCipherKeySize()
     */
    @Nullable
    public String getDemCipherTransformation() {
        return mDemCipherTransformation;
    }

    /**
     * Returns DEM {@link Cipher} key size in bits.
     *
     * @see #getDemCipherTransformation()
     */
    public int getDemCipherKeySize() {
        return mDemCipherKeySize;
    }

    /**
     * Returns DEM {@link Mac} algorithm (e.g., {@code HmacSHA256} or {@code HmacSHA1}) or
     * {@code null} if not specified.
     *
     * @see Mac#getInstance(String)
     * @see #getDemMacKeySize()
     */
    @Nullable
    public String getDemMacAlgorithm() {
        return mDemMacAlgorithm;
    }

    /**
     * Returns DEM {@link Mac} key size in bits.
     *
     * @see #getDemCipherTransformation()
     */
    public int getDemMacKeySize() {
        return mDemMacKeySize;
    }

    /**
     * Builder of {@link EcIesParameterSpec}.
     */
    public static class Builder {
        private @PointFormatEnum int mKemPointFormat = POINT_FORMAT_UNSPECIFIED;
        private String mKemKdfAlgorithm;
        private String mDemCipherTransformation;
        private int mDemCipherKeySize = 128;
        private String mDemMacAlgorithm;
        private int mDemMacKeySize = -1;

        /**
         * Sets KEM EC point wire format.
         */
        public Builder setKemPointFormat(@PointFormatEnum int pointFormat) {
            mKemPointFormat = pointFormat;
            return this;
        }

        /**
         * Sets KEM KDF algorithm. For example, {@code HKDFwithSHA256}, {@code KDF2withSHA256}, or
         * {@code KDF1withSHA1}.
         */
        @NonNull
        public Builder setKemKdfAlgorithm(@Nullable String algorithm) {
            mKemKdfAlgorithm = algorithm;
            return this;
        }

        /**
         * Sets DEM {@link Cipher} transformation. For example, {@code AES/GCM/NoPadding},
         * {@code AES/CBC/PKCS7Padding} or {@code AES/CTR/NoPadding}.
         *
         * @see Cipher#getInstance(String)
         */
        @NonNull
        public Builder setDemCipherTransformation(@Nullable String transformation) {
            mDemCipherTransformation = transformation;
            return this;
        }

        /**
         * Returns DEM {@link Cipher} key size in bits.
         *
         * <p>The default value is {@code 128} bits.
         *
         * @see #setDemCipherTransformation(String)
         */
        @NonNull
        public Builder setDemCipherKeySize(int sizeBits) {
            mDemCipherKeySize = sizeBits;
            return this;
        }

        /**
         * Sets DEM {@link Mac} algorithm. For example, {@code HmacSHA256} or {@code HmacSHA1}.
         *
         * @see Mac#getInstance(String)
         */
        @NonNull
        public Builder setDemMacAlgorithm(@Nullable String algorithm) {
            mDemMacAlgorithm = algorithm;
            return this;
        }

        /**
         * Sets DEM {@link Mac} key size in bits.
         *
         * <p>By default, {@code Mac} key size is the same as the {@code Cipher} key size.
         *
         * @see #setDemCipherKeySize(int)
         */
        @NonNull
        public Builder setDemMacKeySize(int sizeBits) {
            mDemMacKeySize = sizeBits;
            return this;
        }

        /**
         * Returns a new {@link EcIesParameterSpec} based on the current state of this builder.
         */
        @NonNull
        public EcIesParameterSpec build() {
            int demMacKeySize = (mDemMacKeySize != -1) ? mDemMacKeySize : mDemCipherKeySize;
            return new EcIesParameterSpec(
                    mKemPointFormat,
                    mKemKdfAlgorithm,
                    mDemCipherTransformation,
                    mDemCipherKeySize,
                    mDemMacAlgorithm,
                    demMacKeySize
                    );
        }
    }
}
