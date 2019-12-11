/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents a simple formula consisting of an app install metadata field and a value.
 *
 * <p>Instances of this class are immutable.
 *
 * @hide
 */
@SystemApi
@VisibleForTesting
public abstract class AtomicFormula implements Formula {

    private static final String TAG = "AtomicFormula";

    /** @hide */
    @IntDef(
            value = {
                PACKAGE_NAME,
                APP_CERTIFICATE,
                INSTALLER_NAME,
                INSTALLER_CERTIFICATE,
                VERSION_CODE,
                PRE_INSTALLED,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Key {}

    /** @hide */
    @IntDef(value = {EQ, LT, LE, GT, GE})
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
     * <p>Can only be used in {@link IntAtomicFormula}.
     */
    public static final int VERSION_CODE = 4;

    /**
     * If the app is pre-installed on the device.
     *
     * <p>Can only be used in {@link BooleanAtomicFormula}.
     */
    public static final int PRE_INSTALLED = 5;

    public static final int EQ = 0;
    public static final int LT = 1;
    public static final int LE = 2;
    public static final int GT = 3;
    public static final int GE = 4;

    private final @Key int mKey;

    public AtomicFormula(@Key int key) {
        checkArgument(isValidKey(key), String.format("Unknown key: %d", key));
        mKey = key;
    }

    /** An {@link AtomicFormula} with an key and int value. */
    public static final class IntAtomicFormula extends AtomicFormula implements Parcelable {
        private final int mValue;
        private final @Operator int mOperator;

        /**
         * Constructs a new {@link IntAtomicFormula}.
         *
         * <p>This formula will hold if and only if the corresponding information of an install
         * specified by {@code key} is of the correct relationship to {@code value} as specified by
         * {@code operator}.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with integer value
         */
        public IntAtomicFormula(@Key int key, @Operator int operator, int value) {
            super(key);
            checkArgument(
                    key == VERSION_CODE,
                    String.format("Key %s cannot be used with IntAtomicFormula", keyToString(key)));
            checkArgument(isValidOperator(operator),
                    String.format("Unknown operator: %d", operator));
            mOperator = operator;
            mValue = value;
        }

        IntAtomicFormula(Parcel in) {
            super(in.readInt());
            mValue = in.readInt();
            mOperator = in.readInt();
        }

        @NonNull
        public static final Creator<IntAtomicFormula> CREATOR =
                new Creator<IntAtomicFormula>() {
                    @Override
                    public IntAtomicFormula createFromParcel(Parcel in) {
                        return new IntAtomicFormula(in);
                    }

                    @Override
                    public IntAtomicFormula[] newArray(int size) {
                        return new IntAtomicFormula[size];
                    }
                };

        @Override
        public boolean isSatisfied(@NonNull AppInstallMetadata appInstallMetadata) {
            int metadataValue = getMetadataValueByKey(appInstallMetadata);
            switch (mOperator) {
                case EQ:
                    return metadataValue == mValue;
                case LE:
                    return metadataValue <= mValue;
                case LT:
                    return metadataValue < mValue;
                case GE:
                    return metadataValue >= mValue;
                case GT:
                    return metadataValue > mValue;
                default:
                    Slog.i(TAG, String.format("Unexpected operator %d", mOperator));
                    return false;
            }
        }

        @Override
        public int getTag() {
            return Formula.INT_ATOMIC_FORMULA_TAG;
        }

        @Override
        public String toString() {
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
            IntAtomicFormula that = (IntAtomicFormula) o;
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
            dest.writeInt(getKey());
            dest.writeInt(mValue);
            dest.writeInt(mOperator);
        }

        public int getValue() {
            return mValue;
        }

        public int getOperator() {
            return mOperator;
        }

        private int getMetadataValueByKey(AppInstallMetadata appInstallMetadata) {
            switch (getKey()) {
                case VERSION_CODE:
                    return appInstallMetadata.getVersionCode();
                default:
                    throw new IllegalStateException(
                            "Unexpected key in IntAtomicFormula" + getKey());
            }
        }

        private static boolean isValidOperator(int operator) {
            return operator == EQ
                    || operator == LT
                    || operator == LE
                    || operator == GT
                    || operator == GE;
        }
    }

    /** An {@link AtomicFormula} with a key and string value. */
    public static final class StringAtomicFormula extends AtomicFormula implements Parcelable {
        private final String mValue;
        // Indicates whether the value is the actual value or the hashed value.
        private final boolean mIsHashedValue;

        /**
         * Constructs a new {@link StringAtomicFormula}.
         *
         * <p>This formula will hold if and only if the corresponding information of an install
         * specified by {@code key} equals {@code value}.
         *
         * @throws IllegalArgumentException if {@code key} cannot be used with string value
         */
        public StringAtomicFormula(@Key int key, @NonNull String value, boolean isHashedValue) {
            super(key);
            mIsHashedValue = isHashedValue;
            checkArgument(
                    key == PACKAGE_NAME
                            || key == APP_CERTIFICATE
                            || key == INSTALLER_CERTIFICATE
                            || key == INSTALLER_NAME,
                    String.format(
                            "Key %s cannot be used with StringAtomicFormula", keyToString(key)));
            mValue = value;
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
        public boolean isSatisfied(@NonNull AppInstallMetadata appInstallMetadata) {
            String metadataValue = getMetadataValueByKey(appInstallMetadata);
            return metadataValue.equals(mValue);
        }

        @Override
        public int getTag() {
            return Formula.STRING_ATOMIC_FORMULA_TAG;
        }

        @Override
        public String toString() {
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
            dest.writeInt(getKey());
            dest.writeStringNoHelper(mValue);
            dest.writeByte((byte) (mIsHashedValue ? 1 : 0));
        }

        @NonNull
        public String getValue() {
            return mValue;
        }

        public boolean getIsHashedValue() {
            return mIsHashedValue;
        }

        private String getMetadataValueByKey(AppInstallMetadata appInstallMetadata) {
            switch (getKey()) {
                case PACKAGE_NAME:
                    return appInstallMetadata.getPackageName();
                case APP_CERTIFICATE:
                    return appInstallMetadata.getAppCertificate();
                case INSTALLER_CERTIFICATE:
                    return appInstallMetadata.getInstallerCertificate();
                case INSTALLER_NAME:
                    return appInstallMetadata.getInstallerName();
                default:
                    throw new IllegalStateException(
                            "Unexpected key in StringAtomicFormula: " + getKey());
            }
        }
    }

    /** An {@link AtomicFormula} with a key and boolean value. */
    public static final class BooleanAtomicFormula extends AtomicFormula implements Parcelable {
        private final boolean mValue;

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
                    key == PRE_INSTALLED,
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
        public boolean isSatisfied(@NonNull AppInstallMetadata appInstallMetadata) {
            boolean metadataValue = getMetadataValueByKey(appInstallMetadata);
            return metadataValue == mValue;
        }

        @Override
        public int getTag() {
            return Formula.BOOLEAN_ATOMIC_FORMULA_TAG;
        }

        @Override
        public String toString() {
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
            dest.writeInt(getKey());
            dest.writeByte((byte) (mValue ? 1 : 0));
        }

        public boolean getValue() {
            return mValue;
        }

        private boolean getMetadataValueByKey(AppInstallMetadata appInstallMetadata) {
            switch (getKey()) {
                case PRE_INSTALLED:
                    return appInstallMetadata.isPreInstalled();
                default:
                    throw new IllegalStateException(
                            "Unexpected key in BooleanAtomicFormula: " + getKey());
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
            default:
                throw new IllegalArgumentException("Unknown key " + key);
        }
    }

    static String operatorToString(int op) {
        switch (op) {
            case EQ:
                return "EQ";
            case LT:
                return "LT";
            case LE:
                return "LE";
            case GT:
                return "GT";
            case GE:
                return "GE";
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
                || key == PRE_INSTALLED;
    }
}
