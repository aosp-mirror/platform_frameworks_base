/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.autofill;

import static android.view.autofill.Helper.DEBUG;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

/**
 * @hide
 * @deprecated TODO(b/35956626): remove once clients use AutofillValue
 */
@Deprecated
public final class AutoFillValue implements Parcelable {
    private final AutofillValue mRealValue;

    private AutoFillValue(AutofillValue daRealValue) {
        this.mRealValue = daRealValue;
    }

    /**
     * Gets the value to autofill a text field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TEXT} for more info.
     */
    public CharSequence getTextValue() {
        return mRealValue.getTextValue();
    }

    /**
     * Gets the value to autofill a toggable field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TOGGLE} for more info.
     */
    public boolean getToggleValue() {
        return mRealValue.getToggleValue();
    }

    /**
     * Gets the value to autofill a selection list field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_LIST} for more info.
     */
    public int getListValue() {
        return mRealValue.getListValue();
    }

    /**
     * Gets the value to autofill a date field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_DATE} for more info.
     */
    public long getDateValue() {
        return mRealValue.getDateValue();
    }

    /////////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////////

    @Override
    public int hashCode() {
        return mRealValue.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final AutoFillValue other = (AutoFillValue) obj;
        return mRealValue.equals(other.mRealValue);
    }

    /** @hide */
    public String coerceToString() {
        return mRealValue.coerceToString();
    }

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        return mRealValue.toString();
    }

    /////////////////////////////////////
    //  Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mRealValue, 0);
    }

    private AutoFillValue(Parcel parcel) {
        mRealValue = parcel.readParcelable(null);
    }

    public static final Parcelable.Creator<AutoFillValue> CREATOR =
            new Parcelable.Creator<AutoFillValue>() {
        @Override
        public AutoFillValue createFromParcel(Parcel source) {
            return new AutoFillValue(source);
        }

        @Override
        public AutoFillValue[] newArray(int size) {
            return new AutoFillValue[size];
        }
    };

    ////////////////////
    // Factory methods //
    ////////////////////
    /**
     * Creates a new {@link AutoFillValue} to autofill a {@link View} representing a text field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TEXT} for more info.
     */
    @Nullable
    public static AutoFillValue forText(@Nullable CharSequence value) {
        return value == null ? null : new AutoFillValue(AutofillValue.forText(value));
    }

    /**
     * Creates a new {@link AutoFillValue} to autofill a {@link View} representing a toggable
     * field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TOGGLE} for more info.
     */
    public static AutoFillValue forToggle(boolean value) {
        return new AutoFillValue(AutofillValue.forToggle(value));
    }

    /**
     * Creates a new {@link AutoFillValue} to autofill a {@link View} representing a selection
     * list.
     *
     * <p>See {@link View#AUTOFILL_TYPE_LIST} for more info.
     */
    public static AutoFillValue forList(int value) {
        return new AutoFillValue(AutofillValue.forList(value));
    }

    /**
     * Creates a new {@link AutoFillValue} to autofill a {@link View} representing a date.
     *
     * <p>See {@link View#AUTOFILL_TYPE_DATE} for more info.
     */
    public static AutoFillValue forDate(long date) {
        return new AutoFillValue(AutofillValue.forDate(date));
    }

    /** @hide */
    public static AutoFillValue forDaRealValue(AutofillValue daRealValue) {
        return new AutoFillValue(daRealValue);
    }

    /** @hide */
    public AutofillValue getDaRealValue() {
        return mRealValue;
    }
}
