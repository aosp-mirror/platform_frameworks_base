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

package android.content.integrity;

import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a simple formula consisting of an app install metadata field and a value.
 *
 * <p>Instances of this class are immutable.
 *
 * @hide
 */
@VisibleForTesting
public abstract class AtomicFormula extends IntegrityFormula {

    /** @hide */
    @IntDef(
            value = {
                PACKAGE_NAME,
                APP_CERTIFICATE,
                INSTALLER_NAME,
                INSTALLER_CERTIFICATE,
                VERSION_CODE,
                PRE_INSTALLED,
                STAMP_TRUSTED,
                STAMP_CERTIFICATE_HASH,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Key {}

    /** @hide */
    @IntDef(value = {EQ, GT, GTE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Operator {}

    /**
     * Package name of the app.
     *
     * <p>Can only be used in {@link StringAtomicFormula}.
     */
    public static final int PACKAGE_NAME = 0;

    /**
     * SHA-256 of the app certificate of the app.
     *
     * <p>Can only be used in {@link StringAtomicFormula}.
     */
    public static final int APP_CERTIFICATE = 1;

    /**
     * Package name of the installer. Will be empty string if installed by the system (e.g., adb).
     *
     * <p>Can only be used in {@link StringAtomicFormula}.
     */
    public static final int INSTALLER_NAME = 2;

    /**
     * SHA-256 of the cert of the installer. Will be empty string if installed by the system (e.g.,
     * adb).
     *
     * <p>Can only be used in {@link StringAtomicFormula}.
     */
    public static final int INSTALLER_CERTIFICATE = 3;

    /**
     * Version code of the app.
     *
     * <p>Can only be used in {@link LongAtomicFormula}.
     */
    public static final int VERSION_CODE = 4;

    /**
     * If the app is pre-installed on the device.
     *
     * <p>Can only be used in {@link BooleanAtomicFormula}.
     */
    public static final int PRE_INSTALLED = 5;

    /**
     * If the APK has an embedded trusted stamp.
     *
     * <p>Can only be used in {@link BooleanAtomicFormula}.
     */
    public static final int STAMP_TRUSTED = 6;

    /**
     * SHA-256 of the certificate used to sign the stamp embedded in the APK.
     *
     * <p>Can only be used in {@link StringAtomicFormula}.
     */
    public static final int STAMP_CERTIFICATE_HASH = 7;

    public static final int EQ = 0;
    public static final int GT = 1;
    public static final int GTE = 2;

    private final @Key int mKey;

    public AtomicFormula(@Key int key) {
        checkArgument(isValidKey(key), String.format("Unknown key: %d", key));
        mKey = key;
    }

    /** An {@link AtomicFormula} with an key and long value. */
    public static final class LongAtomicFormula extends AtomicFormula implements Parcelable {
        private final Long mValue;
        private final @Operator Integer mOperator;

        /**
         * Constructs an empty {@link LongAtomicFormula}. This should only be used as a base.
         *
         * <p>This formula will always return false.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with long value
         */
        public LongAtomicFormula(@Key int key) {
            super(key);
            checkArgument(
                    key == VERSION_CODE,
                    String.format(
                            "Key %s cannot be used with LongAtomicFormula", keyToString(key)));
            mValue = null;
            mOperator = null;
        }

        /**
         * Constructs a new {@link LongAtomicFormula}.
         *
         * <p>This formula will hold if and only if the corresponding information of an install
         * specified by {@code key} is of the correct relationship to {@code value} as specified by
         * {@code operator}.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with long value
         */
        public LongAtomicFormula(@Key int key, @Operator int operator, long value) {
            super(key);
            checkArgument(
                    key == VERSION_CODE,
                    String.format(
                            "Key %s cannot be used with LongAtomicFormula", keyToString(key)));
            checkArgument(
                    isValidOperator(operator), String.format("Unknown operator: %d", operator));
            mOperator = operator;
            mValue = value;
        }

        LongAtomicFormula(Parcel in) {
            super(in.readInt());
            mValue = in.readLong();
            mOperator = in.readInt();
        }

        @NonNull
        public static final Creator<LongAtomicFormula> CREATOR =
                new Creator<LongAtomicFormula>() {
                    @Override
                    public LongAtomicFormula createFromParcel(Parcel in) {
                        return new LongAtomicFormula(in);
                    }

                    @Override
                    public LongAtomicFormula[] newArray(int size) {
                        return new LongAtomicFormula[size];
                    }
                };

        @Override
        public int getTag() {
            return IntegrityFormula.LONG_ATOMIC_FORMULA_TAG;
        }

        @Override
        public boolean matches(AppInstallMetadata appInstallMetadata) {
            if (mValue == null || mOperator == null) {
                return false;
            }

            long metadataValue = getLongMetadataValue(appInstallMetadata, getKey());
            switch (mOperator) {
                case EQ:
                    return metadataValue == mValue;
                case GT:
                    return metadataValue > mValue;
                case GTE:
                    return metadataValue >= mValue;
                default:
                    throw new IllegalArgumentException(
                            String.format("Unexpected operator %d", mOperator));
            }
        }

        @Override
        public boolean isAppCertificateFormula() {
            return false;
        }

        @Override
        public boolean isInstallerFormula() {
            return false;
        }

        @Override
        public String toString() {
            if (mValue == null || mOperator == null) {
                return String.format("(%s)", keyToString(getKey()));
            }
            return String.format(
                    "(%s %s %s)", keyToString(getKey()), operatorToString(mOperator), mValue);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            LongAtomicFormula that = (LongAtomicFormula) o;
            return getKey() == that.getKey()
                    && mValue == that.mValue
                    && mOperator == that.mOperator;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getKey(), mOperator, mValue);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            if (mValue == null || mOperator == null) {
                throw new IllegalStateException("Cannot write an empty LongAtomicFormula.");
            }
            dest.writeInt(getKey());
            dest.writeLong(mValue);
            dest.writeInt(mOperator);
        }

        public Long getValue() {
            return mValue;
        }

        public Integer getOperator() {
            return mOperator;
        }

        private static boolean isValidOperator(int operator) {
            return operator == EQ || operator == GT || operator == GTE;
        }

        private static long getLongMetadataValue(AppInstallMetadata appInstallMetadata, int key) {
            switch (key) {
                case AtomicFormula.VERSION_CODE:
                    return appInstallMetadata.getVersionCode();
                default:
                    throw new IllegalStateException("Unexpected key in IntAtomicFormula" + key);
            }
        }
    }

    /** An {@link AtomicFormula} with a key and string value. */
    public static final class StringAtomicFormula extends AtomicFormula implements Parcelable {
        private final String mValue;
        // Indicates whether the value is the actual value or the hashed value.
        private final Boolean mIsHashedValue;

        /**
         * Constructs an empty {@link StringAtomicFormula}. This should only be used as a base.
         *
         * <p>An empty formula will always match to false.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with string value
         */
        public StringAtomicFormula(@Key int key) {
            super(key);
            checkArgument(
                    key == PACKAGE_NAME
                            || key == APP_CERTIFICATE
                            || key == INSTALLER_CERTIFICATE
                            || key == INSTALLER_NAME
                            || key == STAMP_CERTIFICATE_HASH,
                    String.format(
                            "Key %s cannot be used with StringAtomicFormula", keyToString(key)));
            mValue = null;
            mIsHashedValue = null;
        }

        /**
         * Constructs a new {@link StringAtomicFormula}.
         *
         * <p>This formula will hold if and only if the corresponding information of an install
         * specified by {@code key} equals {@code value}.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with string value
         */
        public StringAtomicFormula(@Key int key, @NonNull String value, boolean isHashed) {
            super(key);
            checkArgument(
                    key == PACKAGE_NAME
                            || key == APP_CERTIFICATE
                            || key == INSTALLER_CERTIFICATE
                            || key == INSTALLER_NAME
                            || key == STAMP_CERTIFICATE_HASH,
                    String.format(
                            "Key %s cannot be used with StringAtomicFormula", keyToString(key)));
            mValue = value;
            mIsHashedValue = isHashed;
        }

        /**
         * Constructs a new {@link StringAtomicFormula} together with handling the necessary hashing
         * for the given key.
         *
         * <p>The value will be automatically hashed with SHA256 and the hex digest will be computed
         * when the key is PACKAGE_NAME or INSTALLER_NAME and the value is more than 32 characters.
         *
         * <p>The APP_CERTIFICATES, INSTALLER_CERTIFICATES, and STAMP_CERTIFICATE_HASH are always
         * delivered in hashed form. So the isHashedValue is set to true by default.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with string value.
         */
        public StringAtomicFormula(@Key int key, @NonNull String value) {
            super(key);
            checkArgument(
                    key == PACKAGE_NAME
                            || key == APP_CERTIFICATE
                            || key == INSTALLER_CERTIFICATE
                            || key == INSTALLER_NAME
                            || key == STAMP_CERTIFICATE_HASH,
                    String.format(
                            "Key %s cannot be used with StringAtomicFormula", keyToString(key)));
            mValue = hashValue(key, value);
            mIsHashedValue =
                    (key == APP_CERTIFICATE
                                    || key == INSTALLER_CERTIFICATE
                                    || key == STAMP_CERTIFICATE_HASH)
                            || !mValue.equals(value);
        }

        StringAtomicFormula(Parcel in) {
            super(in.readInt());
            mValue = in.readStringNoHelper();
            mIsHashedValue = in.readByte() != 0;
        }

        @NonNull
        public static final Creator<StringAtomicFormula> CREATOR =
                new Creator<StringAtomicFormula>() {
                    @Override
                    public StringAtomicFormula createFromParcel(Parcel in) {
                        return new StringAtomicFormula(in);
                    }

                    @Override
                    public StringAtomicFormula[] newArray(int size) {
                        return new StringAtomicFormula[size];
                    }
                };

        @Override
        public int getTag() {
            return IntegrityFormula.STRING_ATOMIC_FORMULA_TAG;
        }

        @Override
        public boolean matches(AppInstallMetadata appInstallMetadata) {
            if (mValue == null || mIsHashedValue == null) {
                return false;
            }
            return getMetadataValue(appInstallMetadata, getKey()).contains(mValue);
        }

        @Override
        public boolean isAppCertificateFormula() {
            return getKey() == APP_CERTIFICATE;
        }

        @Override
        public boolean isInstallerFormula() {
            return getKey() == INSTALLER_NAME || getKey() == INSTALLER_CERTIFICATE;
        }

        @Override
        public String toString() {
            if (mValue == null || mIsHashedValue == null) {
                return String.format("(%s)", keyToString(getKey()));
            }
            return String.format("(%s %s %s)", keyToString(getKey()), operatorToString(EQ), mValue);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            StringAtomicFormula that = (StringAtomicFormula) o;
            return getKey() == that.getKey() && Objects.equals(mValue, that.mValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getKey(), mValue);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            if (mValue == null || mIsHashedValue == null) {
                throw new IllegalStateException("Cannot write an empty StringAtomicFormula.");
            }
            dest.writeInt(getKey());
            dest.writeStringNoHelper(mValue);
            dest.writeByte((byte) (mIsHashedValue ? 1 : 0));
        }

        public String getValue() {
            return mValue;
        }

        public Boolean getIsHashedValue() {
            return mIsHashedValue;
        }

        private static List<String> getMetadataValue(
                AppInstallMetadata appInstallMetadata, int key) {
            switch (key) {
                case AtomicFormula.PACKAGE_NAME:
                    return Collections.singletonList(appInstallMetadata.getPackageName());
                case AtomicFormula.APP_CERTIFICATE:
                    return appInstallMetadata.getAppCertificates();
                case AtomicFormula.INSTALLER_CERTIFICATE:
                    return appInstallMetadata.getInstallerCertificates();
                case AtomicFormula.INSTALLER_NAME:
                    return Collections.singletonList(appInstallMetadata.getInstallerName());
                case AtomicFormula.STAMP_CERTIFICATE_HASH:
                    return Collections.singletonList(appInstallMetadata.getStampCertificateHash());
                default:
                    throw new IllegalStateException(
                            "Unexpected key in StringAtomicFormula: " + key);
            }
        }

        private static String hashValue(@Key int key, String value) {
            // Hash the string value if it is a PACKAGE_NAME or INSTALLER_NAME and the value is
            // greater than 32 characters.
            if (value.length() > 32) {
                if (key == PACKAGE_NAME || key == INSTALLER_NAME) {
                    return hash(value);
                }
            }
            return value;
        }

        private static String hash(String value) {
            try {
                MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = messageDigest.digest(value.getBytes(StandardCharsets.UTF_8));
                return IntegrityUtils.getHexDigest(hashBytes);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 algorithm not found", e);
            }
        }
    }

    /** An {@link AtomicFormula} with a key and boolean value. */
    public static final class BooleanAtomicFormula extends AtomicFormula implements Parcelable {
        private final Boolean mValue;

        /**
         * Constructs an empty {@link BooleanAtomicFormula}. This should only be used as a base.
         *
         * <p>An empty formula will always match to false.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with boolean value
         */
        public BooleanAtomicFormula(@Key int key) {
            super(key);
            checkArgument(
                    key == PRE_INSTALLED || key == STAMP_TRUSTED,
                    String.format(
                            "Key %s cannot be used with BooleanAtomicFormula", keyToString(key)));
            mValue = null;
        }

        /**
         * Constructs a new {@link BooleanAtomicFormula}.
         *
         * <p>This formula will hold if and only if the corresponding information of an install
         * specified by {@code key} equals {@code value}.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with boolean value
         */
        public BooleanAtomicFormula(@Key int key, boolean value) {
            super(key);
            checkArgument(
                    key == PRE_INSTALLED || key == STAMP_TRUSTED,
                    String.format(
                            "Key %s cannot be used with BooleanAtomicFormula", keyToString(key)));
            mValue = value;
        }

        BooleanAtomicFormula(Parcel in) {
            super(in.readInt());
            mValue = in.readByte() != 0;
        }

        @NonNull
        public static final Creator<BooleanAtomicFormula> CREATOR =
                new Creator<BooleanAtomicFormula>() {
                    @Override
                    public BooleanAtomicFormula createFromParcel(Parcel in) {
                        return new BooleanAtomicFormula(in);
                    }

                    @Override
                    public BooleanAtomicFormula[] newArray(int size) {
                        return new BooleanAtomicFormula[size];
                    }
                };

        @Override
        public int getTag() {
            return IntegrityFormula.BOOLEAN_ATOMIC_FORMULA_TAG;
        }

        @Override
        public boolean matches(AppInstallMetadata appInstallMetadata) {
            if (mValue == null) {
                return false;
            }
            return getBooleanMetadataValue(appInstallMetadata, getKey()) == mValue;
        }

        @Override
        public boolean isAppCertificateFormula() {
            return false;
        }

        @Override
        public boolean isInstallerFormula() {
            return false;
        }

        @Override
        public String toString() {
            if (mValue == null) {
                return String.format("(%s)", keyToString(getKey()));
            }
            return String.format("(%s %s %s)", keyToString(getKey()), operatorToString(EQ), mValue);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            BooleanAtomicFormula that = (BooleanAtomicFormula) o;
            return getKey() == that.getKey() && mValue == that.mValue;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getKey(), mValue);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            if (mValue == null) {
                throw new IllegalStateException("Cannot write an empty BooleanAtomicFormula.");
            }
            dest.writeInt(getKey());
            dest.writeByte((byte) (mValue ? 1 : 0));
        }

        public Boolean getValue() {
            return mValue;
        }

        private static boolean getBooleanMetadataValue(
                AppInstallMetadata appInstallMetadata, int key) {
            switch (key) {
                case AtomicFormula.PRE_INSTALLED:
                    return appInstallMetadata.isPreInstalled();
                case AtomicFormula.STAMP_TRUSTED:
                    return appInstallMetadata.isStampTrusted();
                default:
                    throw new IllegalStateException(
                            "Unexpected key in BooleanAtomicFormula: " + key);
            }
        }
    }

    public int getKey() {
        return mKey;
    }

    static String keyToString(int key) {
        switch (key) {
            case PACKAGE_NAME:
                return "PACKAGE_NAME";
            case APP_CERTIFICATE:
                return "APP_CERTIFICATE";
            case VERSION_CODE:
                return "VERSION_CODE";
            case INSTALLER_NAME:
                return "INSTALLER_NAME";
            case INSTALLER_CERTIFICATE:
                return "INSTALLER_CERTIFICATE";
            case PRE_INSTALLED:
                return "PRE_INSTALLED";
            case STAMP_TRUSTED:
                return "STAMP_TRUSTED";
            case STAMP_CERTIFICATE_HASH:
                return "STAMP_CERTIFICATE_HASH";
            default:
                throw new IllegalArgumentException("Unknown key " + key);
        }
    }

    static String operatorToString(int op) {
        switch (op) {
            case EQ:
                return "EQ";
            case GT:
                return "GT";
            case GTE:
                return "GTE";
            default:
                throw new IllegalArgumentException("Unknown operator " + op);
        }
    }

    private static boolean isValidKey(int key) {
        return key == PACKAGE_NAME
                || key == APP_CERTIFICATE
                || key == VERSION_CODE
                || key == INSTALLER_NAME
                || key == INSTALLER_CERTIFICATE
                || key == PRE_INSTALLED
                || key == STAMP_TRUSTED
                || key == STAMP_CERTIFICATE_HASH;
    }
}
