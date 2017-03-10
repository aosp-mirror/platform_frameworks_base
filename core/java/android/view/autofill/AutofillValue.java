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
 * Abstracts how a {@link View} can be autofilled by an
 * {@link android.service.autofill.AutofillService}.
 *
 * <p>Each {@link AutofillValue} is associated with a {@code type}, as defined by
 * {@link View#getAutofillType()}.
 */
public final class AutofillValue implements Parcelable {
    private final String mText;
    private final int mListIndex;
    private final boolean mToggle;
    private final long mDate;

    private AutofillValue(CharSequence text, int listIndex, boolean toggle, long date) {
        mText = (text == null) ? null : text.toString();
        mListIndex = listIndex;
        mToggle = toggle;
        mDate = date;
    }

    /**
     * Gets the value to autofill a text field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TEXT} for more info.
     */
    public CharSequence getTextValue() {
        return mText;
    }

    /**
     * Gets the value to autofill a toggable field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TOGGLE} for more info.
     */
    public boolean getToggleValue() {
        return mToggle;
    }

    /**
     * Gets the value to autofill a selection list field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_LIST} for more info.
     */
    public int getListValue() {
        return mListIndex;
    }

    /**
     * Gets the value to autofill a date field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_DATE} for more info.
     */
    public long getDateValue() {
        return mDate;
    }

    /////////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////////

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mText == null) ? 0 : mText.hashCode());
        result = prime * result + mListIndex;
        result = prime * result + (mToggle ? 1231 : 1237);
        result = prime * result + (int) (mDate ^ (mDate >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final AutofillValue other = (AutofillValue) obj;
        if (mText == null) {
            if (other.mText != null) return false;
        } else {
            if (!mText.equals(other.mText)) return false;
        }
        if (mListIndex != other.mListIndex) return false;
        if (mToggle != other.mToggle) return false;
        if (mDate != other.mDate) return false;
        return true;
    }

    /** @hide */
    public String coerceToString() {
        // TODO(b/33197203): How can we filter on toggles or list values?
        return mText;
    }

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        if (mText != null) {
            return mText.length() + "_chars";
        }

        return "[l=" + mListIndex + ", t=" + mToggle + ", d=" + mDate + "]";
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
        parcel.writeString(mText);
        parcel.writeInt(mListIndex);
        parcel.writeInt(mToggle ? 1 : 0);
        parcel.writeLong(mDate);
    }

    private AutofillValue(Parcel parcel) {
        mText = parcel.readString();
        mListIndex = parcel.readInt();
        mToggle = parcel.readInt() == 1;
        mDate = parcel.readLong();
    }

    public static final Parcelable.Creator<AutofillValue> CREATOR =
            new Parcelable.Creator<AutofillValue>() {
        @Override
        public AutofillValue createFromParcel(Parcel source) {
            return new AutofillValue(source);
        }

        @Override
        public AutofillValue[] newArray(int size) {
            return new AutofillValue[size];
        }
    };

    ////////////////////
    // Factory methods //
    ////////////////////

    /**
     * Creates a new {@link AutofillValue} to autofill a {@link View} representing a text field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TEXT} for more info.
     */
    // TODO(b/33197203): use cache
    @Nullable
    public static AutofillValue forText(@Nullable CharSequence value) {
        return value == null ? null : new AutofillValue(value, 0, false, 0);
    }

    /**
     * Creates a new {@link AutofillValue} to autofill a {@link View} representing a toggable
     * field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TOGGLE} for more info.
     */
    public static AutofillValue forToggle(boolean value) {
        return new AutofillValue(null, 0, value, 0);
    }

    /**
     * Creates a new {@link AutofillValue} to autofill a {@link View} representing a selection
     * list.
     *
     * <p>See {@link View#AUTOFILL_TYPE_LIST} for more info.
     */
    public static AutofillValue forList(int value) {
        return new AutofillValue(null, value, false, 0);
    }

    /**
     * Creates a new {@link AutofillValue} to autofill a {@link View} representing a date.
     *
     * <p>See {@link View#AUTOFILL_TYPE_DATE} for more info.
     */
    public static AutofillValue forDate(long value) {
        return new AutofillValue(null, 0, false, value);
    }
}
