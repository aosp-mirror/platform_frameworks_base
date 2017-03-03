/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

/**
 * Defines the type of a object that can be used to auto-fill a {@link View} so the
 * {@link android.service.autofill.AutoFillService} can use the proper {@link AutoFillValue} to
 * fill it.
 */
public final class AutoFillType implements Parcelable {

    // Cached instance for types that don't have subtype; it uses the "lazy initialization holder
    // class idiom" (Effective Java, Item 71) to avoid memory utilization when auto-fill is not
    // enabled.
    private static class DefaultTypesHolder {
        static final AutoFillType TEXT = new AutoFillType(TYPE_TEXT);
        static final AutoFillType TOGGLE = new AutoFillType(TYPE_TOGGLE);
        static final AutoFillType LIST = new AutoFillType(TYPE_LIST);
        static final AutoFillType DATE = new AutoFillType(TYPE_DATE);
    }

    private static final int TYPE_TEXT = 1;
    private static final int TYPE_TOGGLE = 2;
    private static final int TYPE_LIST = 3;
    private static final int TYPE_DATE = 4;

    private final int mType;

    private AutoFillType(int type) {
        mType = type;
    }

    /**
     * Checks if this is a type for a text field, which is filled by a {@link CharSequence}.
     *
     * <p>{@link AutoFillValue} instances for auto-filling a {@link View} can be obtained through
     * {@link AutoFillValue#forText(CharSequence)}, and the value passed to auto-fill a
     * {@link View} can be fetched through {@link AutoFillValue#getTextValue()}.
     */
    public boolean isText() {
        return mType == TYPE_TEXT;
    }

    /**
     * Checks if this is a a type for a togglable field, which is filled by a {@code boolean}.
     *
     * <p>{@link AutoFillValue} instances for auto-filling a {@link View} can be obtained through
     * {@link AutoFillValue#forToggle(boolean)}, and the value passed to auto-fill a
     * {@link View} can be fetched through {@link AutoFillValue#getToggleValue()}.
     */
    public boolean isToggle() {
        return mType == TYPE_TOGGLE;
    }

    /**
     * Checks if this is a type for a selection list field, which is filled by a {@code integer}
     * representing the element index inside the list (starting at {@code 0}.
     *
     * <p>{@link AutoFillValue} instances for auto-filling a {@link View} can be obtained through
     * {@link AutoFillValue#forList(int)}, and the value passed to auto-fill a
     * {@link View} can be fetched through {@link AutoFillValue#getListValue()}.
     *
     * <p>The available options in the selection list are typically provided by
     * {@link android.app.assist.AssistStructure.ViewNode#getAutoFillOptions()}.
     */
    public boolean isList() {
        return mType == TYPE_LIST;
    }

    /**
     * Checks if this is a type for a date and time, which is represented by a long representing
     * the number of milliseconds since the standard base time known as "the epoch", namely
     * January 1, 1970, 00:00:00 GMT (see {@link java.util.Date#getTime()}.
     *
     * <p>{@link AutoFillValue} instances for auto-filling a {@link View} can be obtained through
     * {@link AutoFillValue#forDate(long)}, and the values passed to
     * auto-fill a {@link View} can be fetched through {@link AutoFillValue#getDateValue()}.
     *
     * <p>This type has no sub-types.
     */
    public boolean isDate() {
        return mType == TYPE_DATE;
    }

    /////////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////////

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        return "AutoFillType [type=" + mType + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mType;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final AutoFillType other = (AutoFillType) obj;
        if (mType != other.mType) return false;
        return true;
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
    }

    private AutoFillType(Parcel parcel) {
        mType = parcel.readInt();
    }

    public static final Parcelable.Creator<AutoFillType> CREATOR =
            new Parcelable.Creator<AutoFillType>() {
        @Override
        public AutoFillType createFromParcel(Parcel source) {
            return new AutoFillType(source);
        }

        @Override
        public AutoFillType[] newArray(int size) {
            return new AutoFillType[size];
        }
    };

    ////////////////////
    // Factory methods //
    ////////////////////

    /**
     * Creates a text field type, which is filled by a {@link CharSequence}.
     *
     * <p>See {@link #isText()} for more info.
     */
    public static AutoFillType forText() {
        return DefaultTypesHolder.TEXT;
    }

    /**
     * Creates a type that can be toggled which is filled by a {@code boolean}.
     *
     * <p>See {@link #isToggle()} for more info.
     */
    public static AutoFillType forToggle() {
        return DefaultTypesHolder.TOGGLE;
    }

    /**
     * Creates a selection list, which is filled by a {@code integer} representing the element index
     * inside the list (starting at {@code 0}.
     *
     * <p>See {@link #isList()} for more info.
     */
    public static AutoFillType forList() {
        return DefaultTypesHolder.LIST;
    }

    /**
     * Creates a type that represents a date.
     *
     * <p>See {@link #isDate()} for more info.
     */
    public static AutoFillType forDate() {
        return DefaultTypesHolder.DATE;
    }
}
