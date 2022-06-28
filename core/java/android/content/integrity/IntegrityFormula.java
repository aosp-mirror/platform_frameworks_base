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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.integrity.AtomicFormula.BooleanAtomicFormula;
import android.content.integrity.AtomicFormula.LongAtomicFormula;
import android.content.integrity.AtomicFormula.StringAtomicFormula;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Represents a rule logic/content.
 *
 * @hide
 */
@SystemApi
@VisibleForTesting
public abstract class IntegrityFormula {

    /** Factory class for creating integrity formulas based on the app being installed. */
    public static final class Application {
        /** Returns an integrity formula that checks the equality to a package name. */
        @NonNull
        public static IntegrityFormula packageNameEquals(@NonNull String packageName) {
            return new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, packageName);
        }

        /**
         * Returns an integrity formula that checks if the app certificates contain the string
         * provided by the appCertificate parameter.
         */
        @NonNull
        public static IntegrityFormula certificatesContain(@NonNull String appCertificate) {
            return new StringAtomicFormula(AtomicFormula.APP_CERTIFICATE, appCertificate);
        }

        /**
         * Returns an integrity formula that checks if the app certificate lineage contains the
         * string provided by the appCertificate parameter.
         */
        @NonNull
        public static IntegrityFormula certificateLineageContains(@NonNull String appCertificate) {
            return new StringAtomicFormula(AtomicFormula.APP_CERTIFICATE_LINEAGE, appCertificate);
        }

        /** Returns an integrity formula that checks the equality to a version code. */
        @NonNull
        public static IntegrityFormula versionCodeEquals(@NonNull long versionCode) {
            return new LongAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, versionCode);
        }

        /**
         * Returns an integrity formula that checks the app's version code is greater than the
         * provided value.
         */
        @NonNull
        public static IntegrityFormula versionCodeGreaterThan(@NonNull long versionCode) {
            return new LongAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GT, versionCode);
        }

        /**
         * Returns an integrity formula that checks the app's version code is greater than or equal
         * to the provided value.
         */
        @NonNull
        public static IntegrityFormula versionCodeGreaterThanOrEqualTo(@NonNull long versionCode) {
            return new LongAtomicFormula(
                    AtomicFormula.VERSION_CODE, AtomicFormula.GTE, versionCode);
        }

        /** Returns an integrity formula that is valid when app is pre-installed. */
        @NonNull
        public static IntegrityFormula isPreInstalled() {
            return new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);
        }

        private Application() {}
    }

    /** Factory class for creating integrity formulas based on installer. */
    public static final class Installer {
        /** Returns an integrity formula that checks the equality to an installer name. */
        @NonNull
        public static IntegrityFormula packageNameEquals(@NonNull String installerName) {
            return new StringAtomicFormula(AtomicFormula.INSTALLER_NAME, installerName);
        }

        /**
         * An static formula that evaluates to true if the installer is NOT allowed according to the
         * "allowed installer" field in the android manifest.
         */
        @NonNull
        public static IntegrityFormula notAllowedByManifest() {
            return not(new InstallerAllowedByManifestFormula());
        }

        /**
         * Returns an integrity formula that checks if the installer certificates contain {@code
         * installerCertificate}.
         */
        @NonNull
        public static IntegrityFormula certificatesContain(@NonNull String installerCertificate) {
            return new StringAtomicFormula(
                    AtomicFormula.INSTALLER_CERTIFICATE, installerCertificate);
        }

        private Installer() {}
    }

    /** Factory class for creating integrity formulas based on source stamp. */
    public static final class SourceStamp {
        /** Returns an integrity formula that checks the equality to a stamp certificate hash. */
        @NonNull
        public static IntegrityFormula stampCertificateHashEquals(
                @NonNull String stampCertificateHash) {
            return new StringAtomicFormula(
                    AtomicFormula.STAMP_CERTIFICATE_HASH, stampCertificateHash);
        }

        /**
         * Returns an integrity formula that is valid when stamp embedded in the APK is NOT trusted.
         */
        @NonNull
        public static IntegrityFormula notTrusted() {
            return new BooleanAtomicFormula(AtomicFormula.STAMP_TRUSTED, /* value= */ false);
        }

        private SourceStamp() {}
    }

    /** @hide */
    @IntDef(
            value = {
                COMPOUND_FORMULA_TAG,
                STRING_ATOMIC_FORMULA_TAG,
                LONG_ATOMIC_FORMULA_TAG,
                BOOLEAN_ATOMIC_FORMULA_TAG,
                INSTALLER_ALLOWED_BY_MANIFEST_FORMULA_TAG
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface Tag {}

    /** @hide */
    public static final int COMPOUND_FORMULA_TAG = 0;
    /** @hide */
    public static final int STRING_ATOMIC_FORMULA_TAG = 1;
    /** @hide */
    public static final int LONG_ATOMIC_FORMULA_TAG = 2;
    /** @hide */
    public static final int BOOLEAN_ATOMIC_FORMULA_TAG = 3;
    /** @hide */
    public static final int INSTALLER_ALLOWED_BY_MANIFEST_FORMULA_TAG = 4;

    /**
     * Returns the tag that identifies the current class.
     *
     * @hide
     */
    public abstract @Tag int getTag();

    /**
     * Returns true when the integrity formula is satisfied by the {@code appInstallMetadata}.
     *
     * @hide
     */
    public abstract boolean matches(AppInstallMetadata appInstallMetadata);

    /**
     * Returns true when the formula (or one of its atomic formulas) has app certificate as key.
     *
     * @hide
     */
    public abstract boolean isAppCertificateFormula();

    /**
     * Returns true when the formula (or one of its atomic formulas) has app certificate lineage as
     * key.
     *
     * @hide
     */
    public abstract boolean isAppCertificateLineageFormula();

    /**
     * Returns true when the formula (or one of its atomic formulas) has installer package name or
     * installer certificate as key.
     *
     * @hide
     */
    public abstract boolean isInstallerFormula();

    /**
     * Write an {@link IntegrityFormula} to {@link android.os.Parcel}.
     *
     * <p>This helper method is needed because non-final class/interface are not allowed to be
     * {@link Parcelable}.
     *
     * @throws IllegalArgumentException if {@link IntegrityFormula} is not a recognized subclass
     * @hide
     */
    public static void writeToParcel(
            @NonNull IntegrityFormula formula, @NonNull Parcel dest, int flags) {
        dest.writeInt(formula.getTag());
        ((Parcelable) formula).writeToParcel(dest, flags);
    }

    /**
     * Read a {@link IntegrityFormula} from a {@link android.os.Parcel}.
     *
     * <p>We need this (hacky) helper method because non-final class/interface cannot be {@link
     * Parcelable} (api lint error).
     *
     * @throws IllegalArgumentException if the parcel cannot be parsed
     * @hide
     */
    @NonNull
    public static IntegrityFormula readFromParcel(@NonNull Parcel in) {
        int tag = in.readInt();
        switch (tag) {
            case COMPOUND_FORMULA_TAG:
                return CompoundFormula.CREATOR.createFromParcel(in);
            case STRING_ATOMIC_FORMULA_TAG:
                return StringAtomicFormula.CREATOR.createFromParcel(in);
            case LONG_ATOMIC_FORMULA_TAG:
                return LongAtomicFormula.CREATOR.createFromParcel(in);
            case BOOLEAN_ATOMIC_FORMULA_TAG:
                return BooleanAtomicFormula.CREATOR.createFromParcel(in);
            case INSTALLER_ALLOWED_BY_MANIFEST_FORMULA_TAG:
                return InstallerAllowedByManifestFormula.CREATOR.createFromParcel(in);
            default:
                throw new IllegalArgumentException("Unknown formula tag " + tag);
        }
    }

    /**
     * Returns a formula that evaluates to true when any formula in {@code formulae} evaluates to
     * true.
     *
     * <p>Throws an {@link IllegalArgumentException} if formulae has less than two elements.
     */
    @NonNull
    public static IntegrityFormula any(@NonNull IntegrityFormula... formulae) {
        return new CompoundFormula(CompoundFormula.OR, Arrays.asList(formulae));
    }

    /**
     * Returns a formula that evaluates to true when all formula in {@code formulae} evaluates to
     * true.
     *
     * <p>Throws an {@link IllegalArgumentException} if formulae has less than two elements.
     */
    @NonNull
    public static IntegrityFormula all(@NonNull IntegrityFormula... formulae) {
        return new CompoundFormula(CompoundFormula.AND, Arrays.asList(formulae));
    }

    /** Returns a formula that evaluates to true when {@code formula} evaluates to false. */
    @NonNull
    public static IntegrityFormula not(@NonNull IntegrityFormula formula) {
        return new CompoundFormula(CompoundFormula.NOT, Arrays.asList(formula));
    }

    // Constructor is package private so it cannot be inherited outside of this package.
    IntegrityFormula() {}
}
