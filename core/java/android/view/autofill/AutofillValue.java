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

import static android.view.View.AUTOFILL_TYPE_DATE;
import static android.view.View.AUTOFILL_TYPE_LIST;
import static android.view.View.AUTOFILL_TYPE_TEXT;
import static android.view.View.AUTOFILL_TYPE_TOGGLE;
import static android.view.autofill.Helper.sDebug;
import static android.view.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Abstracts how a {@link View} can be autofilled by an
 * {@link android.service.autofill.AutofillService}.
 *
 * <p>Each {@link AutofillValue} is associated with a {@code type}, as defined by
 * {@link View#getAutofillType()}.
 */
public final class AutofillValue implements Parcelable {

    private static final String TAG = "AutofillValue";

    private final @View.AutofillType int mType;
    private final @NonNull Object mValue;

    private AutofillValue(@View.AutofillType int type, @NonNull Object value) {
        mType = type;
        mValue = value;
    }

    /**
     * Gets the value to autofill a text field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TEXT} for more info.</p>
     *
     * @throws IllegalStateException if the value is not a text value
     */
    @NonNull public CharSequence getTextValue() {
        Preconditions.checkState(isText(), "value must be a text value, not type=%d", mType);
        return (CharSequence) mValue;
    }

    /**
     * Checks if this is a text value.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TEXT} for more info.</p>
     */
    public boolean isText() {
        return mType == AUTOFILL_TYPE_TEXT;
    }

    /**
     * Gets the value to autofill a toggable field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TOGGLE} for more info.</p>
     *
     * @throws IllegalStateException if the value is not a toggle value
     */
    public boolean getToggleValue() {
        Preconditions.checkState(isToggle(), "value must be a toggle value, not type=%d", mType);
        return (Boolean) mValue;
    }

    /**
     * Checks if this is a toggle value.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TOGGLE} for more info.</p>
     */
    public boolean isToggle() {
        return mType == AUTOFILL_TYPE_TOGGLE;
    }

    /**
     * Gets the value to autofill a selection list field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_LIST} for more info.</p>
     *
     * @throws IllegalStateException if the value is not a list value
     */
    public int getListValue() {
        Preconditions.checkState(isList(), "value must be a list value, not type=%d", mType);
        return (Integer) mValue;
    }

    /**
     * Checks if this is a list value.
     *
     * <p>See {@link View#AUTOFILL_TYPE_LIST} for more info.</p>
     */
    public boolean isList() {
        return mType == AUTOFILL_TYPE_LIST;
    }

    /**
     * Gets the value to autofill a date field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_DATE} for more info.</p>
     *
     * @throws IllegalStateException if the value is not a date value
     */
    public long getDateValue() {
        Preconditions.checkState(isDate(), "value must be a date value, not type=%d", mType);
        return (Long) mValue;
    }

    /**
     * Checks if this is a date value.
     *
     * <p>See {@link View#AUTOFILL_TYPE_DATE} for more info.</p>
     */
    public boolean isDate() {
        return mType == AUTOFILL_TYPE_DATE;
    }

    /**
     * Used to define whether a field is empty so it's not sent to service on save.
     *
     * <p>Only applies to some types, like text.
     *
     * @hide
     */
    public boolean isEmpty() {
        return isText() && ((CharSequence) mValue).length() == 0;
    }

    /////////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////////

    @Override
    public int hashCode() {
        return mType + mValue.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final AutofillValue other = (AutofillValue) obj;

        if (mType != other.mType) return false;

        if (isText()) {
            return mValue.toString().equals(other.mValue.toString());
        } else {
            return Objects.equals(mValue, other.mValue);
        }
    }

    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        final StringBuilder string = new StringBuilder()
                .append("[type=").append(mType)
                .append(", value=");
        if (isText()) {
            Helper.appendRedacted(string, (CharSequence) mValue);
        } else {
            string.append(mValue);
        }
        return string.append(']').toString();
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
        parcel.writeInt(mType);

        switch (mType) {
            case AUTOFILL_TYPE_TEXT:
                parcel.writeCharSequence((CharSequence) mValue);
                break;
            case AUTOFILL_TYPE_TOGGLE:
                parcel.writeInt((Boolean) mValue ? 1 : 0);
                break;
            case AUTOFILL_TYPE_LIST:
                parcel.writeInt((Integer) mValue);
                break;
            case AUTOFILL_TYPE_DATE:
                parcel.writeLong((Long) mValue);
                break;
        }
    }

    private AutofillValue(@NonNull Parcel parcel) {
        mType = parcel.readInt();

        switch (mType) {
            case AUTOFILL_TYPE_TEXT:
                mValue = parcel.readCharSequence();
                break;
            case AUTOFILL_TYPE_TOGGLE:
                int rawValue = parcel.readInt();
                mValue = rawValue != 0;
                break;
            case AUTOFILL_TYPE_LIST:
                mValue = parcel.readInt();
                break;
            case AUTOFILL_TYPE_DATE:
                mValue = parcel.readLong();
                break;
            default:
                throw new IllegalArgumentException("type=" + mType + " not valid");
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AutofillValue> CREATOR =
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
     *
     * <p><b>Note:</b> This method is not thread safe and can throw an exception if the
     * {@code value} is modified by a different thread before it returns.
     */
    public static AutofillValue forText(@Nullable CharSequence value) {
        if (sVerbose && !Looper.getMainLooper().isCurrentThread()) {
            Log.v(TAG, "forText() not called on main thread: " + Thread.currentThread());
        }

        return value == null ? null : new AutofillValue(AUTOFILL_TYPE_TEXT,
                TextUtils.trimNoCopySpans(value));
    }

    /**
     * Creates a new {@link AutofillValue} to autofill a {@link View} representing a toggable
     * field.
     *
     * <p>See {@link View#AUTOFILL_TYPE_TOGGLE} for more info.
     */
    public static AutofillValue forToggle(boolean value) {
        return new AutofillValue(AUTOFILL_TYPE_TOGGLE, value);
    }

    /**
     * Creates a new {@link AutofillValue} to autofill a {@link View} representing a selection
     * list.
     *
     * <p>See {@link View#AUTOFILL_TYPE_LIST} for more info.
     */
    public static AutofillValue forList(int value) {
        return new AutofillValue(AUTOFILL_TYPE_LIST, value);
    }

    /**
     * Creates a new {@link AutofillValue} to autofill a {@link View} representing a date.
     *
     * <p>See {@link View#AUTOFILL_TYPE_DATE} for more info.
     */
    public static AutofillValue forDate(long value) {
        return new AutofillValue(AUTOFILL_TYPE_DATE, value);
    }
}
