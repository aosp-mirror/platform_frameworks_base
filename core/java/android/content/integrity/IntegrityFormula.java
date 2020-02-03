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
import android.annotation.TestApi;
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
@TestApi
@VisibleForTesting
public abstract class IntegrityFormula {

    /**
     * A static formula base for package name formulas.
     *
     * This formulation is incomplete and should always be used with {@code equals} formulation.
     * Evaluates to false when used directly and cannot be written as a parcel.
     */
    @NonNull
    public static final IntegrityFormula PACKAGE_NAME =
            new StringAtomicFormula(AtomicFormula.PACKAGE_NAME);

    /**
     * A static formula base for app certificate formulas.
     *
     * This formulation is incomplete and should always be used with {@code equals} formulation.
     * Evaluates to false when used directly and cannot be written as a parcel.
     */
    @NonNull
    public static final IntegrityFormula APP_CERTIFICATE =
            new StringAtomicFormula(AtomicFormula.APP_CERTIFICATE);

    /**
     * A static formula base for installer name formulas.
     *
     * This formulation is incomplete and should always be used with {@code equals} formulation.
     * Evaluates to false when used directly and cannot be written as a parcel.
     */
    @NonNull
    public static final IntegrityFormula INSTALLER_NAME =
            new StringAtomicFormula(AtomicFormula.INSTALLER_NAME);

    /**
     * A static formula base for installer certificate formulas.
     *
     * This formulation is incomplete and should always be used with {@code equals} formulation.
     * Evaluates to false when used directly and cannot be written as a parcel.
     */
    @NonNull
    public static final IntegrityFormula INSTALLER_CERTIFICATE =
            new StringAtomicFormula(AtomicFormula.INSTALLER_CERTIFICATE);

    /**
     * A static formula base for version code name formulas.
     *
     * This formulation is incomplete and should always be used with {@code equals},
     * {@code greaterThan} and {@code greaterThanEquals} formulation. Evaluates to false when used
     * directly and cannot be written as a parcel.
     */
    @NonNull
    public static final IntegrityFormula VERSION_CODE =
            new LongAtomicFormula(AtomicFormula.VERSION_CODE);

    /**
     * A static formula base for pre-installed status formulas.
     *
     * This formulation is incomplete and should always be used with {@code equals} formulation.
     * Evaluates to false when used directly and cannot be written as a parcel.
     */
    @NonNull
    public static final IntegrityFormula PRE_INSTALLED =
            new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED);

    /** @hide */
    @IntDef(
            value = {
                    COMPOUND_FORMULA_TAG,
                    STRING_ATOMIC_FORMULA_TAG,
                    LONG_ATOMIC_FORMULA_TAG,
                    BOOLEAN_ATOMIC_FORMULA_TAG
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
    public abstract @Tag boolean matches(AppInstallMetadata appInstallMetadata);

    /**
     * Returns true when the formula (or one of its atomic formulas) has app certificate as key.
     *
     * @hide
     */
    public abstract @Tag boolean isAppCertificateFormula();

    /**
     * Returns true when the formula (or one of its atomic formulas) has installer package name
     * or installer certificate as key.
     *
     * @hide
     */
    public abstract @Tag boolean isInstallerFormula();

    /**
     * Write an {@link IntegrityFormula} to {@link android.os.Parcel}.
     *
     * <p>This helper method is needed because non-final class/interface are not allowed to be
     * {@link Parcelable}.
     *
     * @throws IllegalArgumentException if {@link IntegrityFormula} is not a recognized subclass
     *
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
            default:
                throw new IllegalArgumentException("Unknown formula tag " + tag);
        }
    }

    /**
     * Returns an integrity formula that evaluates to true when value of the key matches to the
     * provided string value.
     *
     * <p>The value will be hashed with SHA256 and the hex digest will be computed; for
     * all cases except when the key is PACKAGE_NAME or INSTALLER_NAME and the value is less than
     * 32 characters.
     *
     * <p>Throws an {@link IllegalArgumentException} if the key is not string typed.
     */
    @NonNull
    public IntegrityFormula equalTo(@NonNull String value) {
        AtomicFormula baseFormula = (AtomicFormula) this;
        return new AtomicFormula.StringAtomicFormula(baseFormula.getKey(), value);
    }

    /**
     * Returns an integrity formula that evaluates to true when the boolean value of the key matches
     * the provided boolean value. It can only be used with the boolean comparison keys.
     *
     * <p>Throws an {@link IllegalArgumentException} if the key is not boolean typed.
     */
    @NonNull
    public IntegrityFormula equalTo(boolean value) {
        AtomicFormula baseFormula = (AtomicFormula) this;
        return new AtomicFormula.BooleanAtomicFormula(baseFormula.getKey(), value);
    }

    /**
     * Returns a formula that evaluates to true when the value of the key in the package being
     * installed is equal to {@code value}.
     *
     * <p>Throws an {@link IllegalArgumentException} if the key is not long typed.
     */
    @NonNull
    public IntegrityFormula equalTo(long value) {
        AtomicFormula baseFormula = (AtomicFormula) this;
        return new AtomicFormula.LongAtomicFormula(baseFormula.getKey(), AtomicFormula.EQ, value);
    }

    /**
     * Returns a formula that evaluates to true when the value of the key in the package being
     * installed is greater than {@code value}.
     *
     * <p>Throws an {@link IllegalArgumentException} if the key is not long typed.
     */
    @NonNull
    public IntegrityFormula greaterThan(long value) {
        AtomicFormula baseFormula = (AtomicFormula) this;
        return new AtomicFormula.LongAtomicFormula(baseFormula.getKey(), AtomicFormula.GT, value);
    }

    /**
     * Returns a formula that evaluates to true when the value of the key in the package being
     * installed is greater than or equals to the {@code value}.
     *
     * <p>Throws an {@link IllegalArgumentException} if the key is not long typed.
     */
    @NonNull
    public IntegrityFormula greaterThanOrEquals(long value) {
        AtomicFormula baseFormula = (AtomicFormula) this;
        return new AtomicFormula.LongAtomicFormula(baseFormula.getKey(), AtomicFormula.GTE, value);
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

    /**
     * Returns a formula that evaluates to true when {@code formula} evaluates to false.
     */
    @NonNull
    public static IntegrityFormula not(@NonNull IntegrityFormula formula) {
        return new CompoundFormula(CompoundFormula.NOT, Arrays.asList(formula));
    }

    // Constructor is package private so it cannot be inherited outside of this package.
    IntegrityFormula() {
    }
}
