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

package com.android.server.integrity.model;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.integrity.model.AtomicFormula.BooleanAtomicFormula;
import com.android.server.integrity.model.AtomicFormula.IntAtomicFormula;
import com.android.server.integrity.model.AtomicFormula.StringAtomicFormula;

/**
 * Represents a rule logic/content.
 *
 * @hide
 */
@SystemApi
@VisibleForTesting
public interface Formula {

    int OPEN_FORMULA_TAG = 0;
    int STRING_ATOMIC_FORMULA_TAG = 1;
    int INT_ATOMIC_FORMULA_TAG = 2;
    int BOOLEAN_ATOMIC_FORMULA_TAG = 3;

    /**
     * Returns if this formula can be satisfied by substituting the corresponding information of
     * {@code appInstallMetadata} into the formula.
     */
    boolean isSatisfied(@NonNull AppInstallMetadata appInstallMetadata);

    /**
     * Write a {@link Formula} to {@link android.os.Parcel}.
     *
     * <p>This helper method is needed because non-final class/interface are not allowed to be
     * {@link Parcelable}.
     *
     * @throws IllegalArgumentException if {@link Formula} is not a recognized subclass
     */
    static void writeToParcel(@NonNull Formula formula, @NonNull Parcel dest, int flags) {
        if (formula instanceof OpenFormula) {
            dest.writeInt(OPEN_FORMULA_TAG);
            ((OpenFormula) formula).writeToParcel(dest, flags);
        } else if (formula instanceof StringAtomicFormula) {
            dest.writeInt(STRING_ATOMIC_FORMULA_TAG);
            ((StringAtomicFormula) formula).writeToParcel(dest, flags);
        } else if (formula instanceof IntAtomicFormula) {
            dest.writeInt(INT_ATOMIC_FORMULA_TAG);
            ((IntAtomicFormula) formula).writeToParcel(dest, flags);
        } else if (formula instanceof BooleanAtomicFormula) {
            dest.writeInt(BOOLEAN_ATOMIC_FORMULA_TAG);
            ((BooleanAtomicFormula) formula).writeToParcel(dest, flags);
        } else {
            throw new IllegalArgumentException("Unrecognized class " + formula.getClass());
        }
    }

    /**
     * Read a {@link Formula} from a {@link android.os.Parcel}.
     *
     * <p>We need this (hacky) helper method because non-final class/interface cannot be {@link
     * Parcelable} (api lint error).
     *
     * @throws IllegalArgumentException if the parcel cannot be parsed
     */
    @NonNull
    static Formula readFromParcel(@NonNull Parcel in) {
        int tag = in.readInt();
        switch (tag) {
            case OPEN_FORMULA_TAG:
                return OpenFormula.CREATOR.createFromParcel(in);
            case STRING_ATOMIC_FORMULA_TAG:
                return StringAtomicFormula.CREATOR.createFromParcel(in);
            case INT_ATOMIC_FORMULA_TAG:
                return IntAtomicFormula.CREATOR.createFromParcel(in);
            case BOOLEAN_ATOMIC_FORMULA_TAG:
                return BooleanAtomicFormula.CREATOR.createFromParcel(in);
            default:
                throw new IllegalArgumentException("Unknown formula tag " + tag);
        }
    }
}
