/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view.inputmethod;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Information given to an {@link InputMethod} about a client connecting
 * to it.
 */
/**
 * InputMethodSubtype is a subtype contained in the input method. Subtype can describe
 * locales (e.g. en_US, fr_FR...) and modes (e.g. voice, keyboard...), and is used for
 * IME switch. The subtype allows the system to call the specified subtype of IME directly.
 */
public final class InputMethodSubtype implements Parcelable {
    private final int mSubtypeNameResId;
    private final int mSubtypeIconResId;
    private final String mSubtypeLocale;
    private final int mSubtypeModeResId;
    private final String mSubtypeExtraValue;
    private final int mSubtypeHashCode;

    /**
     * Constructor
     * @param nameId The name of the subtype
     * @param iconId The icon of the subtype
     * @param locale The locale supported by the subtype
     * @param modeId The mode supported by the subtype
     * @param extraValue The extra value of the subtype
     */
    InputMethodSubtype(int nameId, int iconId, String locale, int modeId, String extraValue) {
        mSubtypeNameResId = nameId;
        mSubtypeIconResId = iconId;
        mSubtypeLocale = locale;
        mSubtypeModeResId = modeId;
        mSubtypeExtraValue = extraValue;
        mSubtypeHashCode = hashCodeInternal(mSubtypeNameResId, mSubtypeIconResId, mSubtypeLocale,
                mSubtypeModeResId, mSubtypeExtraValue);
    }

    InputMethodSubtype(Parcel source) {
        mSubtypeNameResId = source.readInt();
        mSubtypeIconResId = source.readInt();
        mSubtypeLocale = source.readString();
        mSubtypeModeResId = source.readInt();
        mSubtypeExtraValue = source.readString();
        mSubtypeHashCode = hashCodeInternal(mSubtypeNameResId, mSubtypeIconResId, mSubtypeLocale,
                mSubtypeModeResId, mSubtypeExtraValue);
    }

    /**
     * @return the name of the subtype
     */
    public int getNameResId() {
        return mSubtypeNameResId;
    }

    /**
     * @return the icon of the subtype
     */
    public int getIconResId() {
        return mSubtypeIconResId;
    }

    /**
     * @return the locale of the subtype
     */
    public String getLocale() {
        return mSubtypeLocale;
    }

    /**
     * @return the mode of the subtype
     */
    public int getModeResId() {
        return mSubtypeModeResId;
    }

    /**
     * @return the extra value of the subtype
     */
    public String getExtraValue() {
        return mSubtypeExtraValue;
    }

    @Override
    public int hashCode() {
        return mSubtypeHashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof InputMethodSubtype) {
            InputMethodSubtype subtype = (InputMethodSubtype) o;
            return (subtype.getNameResId() == getNameResId())
                && (subtype.getModeResId() == getModeResId())
                && (subtype.getIconResId() == getIconResId())
                && (subtype.getLocale().equals(getLocale()))
                && (subtype.getExtraValue().equals(getExtraValue()));
        }
        return false;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(mSubtypeNameResId);
        dest.writeInt(mSubtypeIconResId);
        dest.writeString(mSubtypeLocale);
        dest.writeInt(mSubtypeModeResId);
        dest.writeString(mSubtypeExtraValue);
    }

    public static final Parcelable.Creator<InputMethodSubtype> CREATOR
            = new Parcelable.Creator<InputMethodSubtype>() {
        public InputMethodSubtype createFromParcel(Parcel source) {
            return new InputMethodSubtype(source);
        }

        public InputMethodSubtype[] newArray(int size) {
            return new InputMethodSubtype[size];
        }
    };

    private static int hashCodeInternal(int nameResId, int iconResId, String locale,
            int modeResId, String extraValue) {
        return Arrays.hashCode(new Object[] {nameResId, iconResId, locale, modeResId, extraValue});
    }
}