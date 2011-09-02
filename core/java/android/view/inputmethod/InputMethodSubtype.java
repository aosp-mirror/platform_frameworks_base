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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * This class is used to specify meta information of a subtype contained in an input method.
 * Subtype can describe locale (e.g. en_US, fr_FR...) and mode (e.g. voice, keyboard...), and is
 * used for IME switch and settings. The input method subtype allows the system to bring up the
 * specified subtype of the designated input method directly.
 */
public final class InputMethodSubtype implements Parcelable {
    private static final String TAG = InputMethodSubtype.class.getSimpleName();
    private static final String EXTRA_VALUE_PAIR_SEPARATOR = ",";
    private static final String EXTRA_VALUE_KEY_VALUE_SEPARATOR = "=";

    private final boolean mIsAuxiliary;
    private final int mSubtypeHashCode;
    private final int mSubtypeIconResId;
    private final int mSubtypeNameResId;
    private final String mSubtypeLocale;
    private final String mSubtypeMode;
    private final String mSubtypeExtraValue;
    private HashMap<String, String> mExtraValueHashMapCache;

    /**
     * Constructor
     * @param nameId The name of the subtype
     * @param iconId The icon of the subtype
     * @param locale The locale supported by the subtype
     * @param mode The mode supported by the subtype
     * @param extraValue The extra value of the subtype
     * @hide
     */
    public InputMethodSubtype(
            int nameId, int iconId, String locale, String mode, String extraValue) {
        this(nameId, iconId, locale, mode, extraValue, false);
    }

    /**
     * Constructor
     * @param nameId The name of the subtype
     * @param iconId The icon of the subtype
     * @param locale The locale supported by the subtype
     * @param mode The mode supported by the subtype
     * @param extraValue The extra value of the subtype
     * @param isAuxiliary true when this subtype is one shot subtype.
     * @hide
     */
    public InputMethodSubtype(int nameId, int iconId, String locale, String mode, String extraValue,
            boolean isAuxiliary) {
        mSubtypeNameResId = nameId;
        mSubtypeIconResId = iconId;
        mSubtypeLocale = locale != null ? locale : "";
        mSubtypeMode = mode != null ? mode : "";
        mSubtypeExtraValue = extraValue != null ? extraValue : "";
        mIsAuxiliary = isAuxiliary;
        mSubtypeHashCode = hashCodeInternal(mSubtypeLocale, mSubtypeMode, mSubtypeExtraValue,
                mIsAuxiliary);
    }

    InputMethodSubtype(Parcel source) {
        String s;
        mSubtypeNameResId = source.readInt();
        mSubtypeIconResId = source.readInt();
        s = source.readString();
        mSubtypeLocale = s != null ? s : "";
        s = source.readString();
        mSubtypeMode = s != null ? s : "";
        s = source.readString();
        mSubtypeExtraValue = s != null ? s : "";
        mIsAuxiliary = (source.readInt() == 1);
        mSubtypeHashCode = hashCodeInternal(mSubtypeLocale, mSubtypeMode, mSubtypeExtraValue,
                mIsAuxiliary);
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
    public String getMode() {
        return mSubtypeMode;
    }

    /**
     * @return the extra value of the subtype
     */
    public String getExtraValue() {
        return mSubtypeExtraValue;
    }

    /**
     * @return true if this subtype is one shot subtype. One shot subtype will not be shown in the
     * ime switch list when this subtype is implicitly enabled. The framework will never
     * switch the current ime to this subtype by switchToLastInputMethod in InputMethodManager.
     */
    public boolean isAuxiliary() {
        return mIsAuxiliary;
    }

    /**
     * @param context Context will be used for getting Locale and PackageManager.
     * @param packageName The package name of the IME
     * @param appInfo The application info of the IME
     * @return a display name for this subtype. The string resource of the label (mSubtypeNameResId)
     * can have only one %s in it. If there is, the %s part will be replaced with the locale's
     * display name by the formatter. If there is not, this method simply returns the string
     * specified by mSubtypeNameResId. If mSubtypeNameResId is not specified (== 0), it's up to the
     * framework to generate an appropriate display name.
     */
    public CharSequence getDisplayName(
            Context context, String packageName, ApplicationInfo appInfo) {
        final Locale locale = constructLocaleFromString(mSubtypeLocale);
        final String localeStr = locale != null ? locale.getDisplayName() : mSubtypeLocale;
        if (mSubtypeNameResId == 0) {
            return localeStr;
        }
        final CharSequence subtypeName = context.getPackageManager().getText(
                packageName, mSubtypeNameResId, appInfo);
        if (!TextUtils.isEmpty(subtypeName)) {
            return String.format(subtypeName.toString(), localeStr);
        } else {
            return localeStr;
        }
    }

    private HashMap<String, String> getExtraValueHashMap() {
        if (mExtraValueHashMapCache == null) {
            mExtraValueHashMapCache = new HashMap<String, String>();
            final String[] pairs = mSubtypeExtraValue.split(EXTRA_VALUE_PAIR_SEPARATOR);
            final int N = pairs.length;
            for (int i = 0; i < N; ++i) {
                final String[] pair = pairs[i].split(EXTRA_VALUE_KEY_VALUE_SEPARATOR);
                if (pair.length == 1) {
                    mExtraValueHashMapCache.put(pair[0], null);
                } else if (pair.length > 1) {
                    if (pair.length > 2) {
                        Slog.w(TAG, "ExtraValue has two or more '='s");
                    }
                    mExtraValueHashMapCache.put(pair[0], pair[1]);
                }
            }
        }
        return mExtraValueHashMapCache;
    }

    /**
     * The string of ExtraValue in subtype should be defined as follows:
     * example: key0,key1=value1,key2,key3,key4=value4
     * @param key the key of extra value
     * @return the subtype contains specified the extra value
     */
    public boolean containsExtraValueKey(String key) {
        return getExtraValueHashMap().containsKey(key);
    }

    /**
     * The string of ExtraValue in subtype should be defined as follows:
     * example: key0,key1=value1,key2,key3,key4=value4
     * @param key the key of extra value
     * @return the value of the specified key
     */
    public String getExtraValueOf(String key) {
        return getExtraValueHashMap().get(key);
    }

    @Override
    public int hashCode() {
        return mSubtypeHashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof InputMethodSubtype) {
            InputMethodSubtype subtype = (InputMethodSubtype) o;
            return (subtype.hashCode() == hashCode())
                && (subtype.getNameResId() == getNameResId())
                && (subtype.getMode().equals(getMode()))
                && (subtype.getIconResId() == getIconResId())
                && (subtype.getLocale().equals(getLocale()))
                && (subtype.getExtraValue().equals(getExtraValue()))
                && (subtype.isAuxiliary() == isAuxiliary());
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(mSubtypeNameResId);
        dest.writeInt(mSubtypeIconResId);
        dest.writeString(mSubtypeLocale);
        dest.writeString(mSubtypeMode);
        dest.writeString(mSubtypeExtraValue);
        dest.writeInt(mIsAuxiliary ? 1 : 0);
    }

    public static final Parcelable.Creator<InputMethodSubtype> CREATOR
            = new Parcelable.Creator<InputMethodSubtype>() {
        @Override
        public InputMethodSubtype createFromParcel(Parcel source) {
            return new InputMethodSubtype(source);
        }

        @Override
        public InputMethodSubtype[] newArray(int size) {
            return new InputMethodSubtype[size];
        }
    };

    private static Locale constructLocaleFromString(String localeStr) {
        if (TextUtils.isEmpty(localeStr))
            return null;
        String[] localeParams = localeStr.split("_", 3);
        // The length of localeStr is guaranteed to always return a 1 <= value <= 3
        // because localeStr is not empty.
        if (localeParams.length == 1) {
            return new Locale(localeParams[0]);
        } else if (localeParams.length == 2) {
            return new Locale(localeParams[0], localeParams[1]);
        } else if (localeParams.length == 3) {
            return new Locale(localeParams[0], localeParams[1], localeParams[2]);
        }
        return null;
    }

    private static int hashCodeInternal(String locale, String mode, String extraValue,
            boolean isAuxiliary) {
        return Arrays.hashCode(new Object[] {locale, mode, extraValue, isAuxiliary});
    }

    /**
     * Sort the list of InputMethodSubtype
     * @param context Context will be used for getting localized strings from IME
     * @param flags Flags for the sort order
     * @param imi InputMethodInfo of which subtypes are subject to be sorted
     * @param subtypeList List of InputMethodSubtype which will be sorted
     * @return Sorted list of subtypes
     * @hide
     */
    public static List<InputMethodSubtype> sort(Context context, int flags, InputMethodInfo imi,
            List<InputMethodSubtype> subtypeList) {
        if (imi == null) return subtypeList;
        final HashSet<InputMethodSubtype> inputSubtypesSet = new HashSet<InputMethodSubtype>(
                subtypeList);
        final ArrayList<InputMethodSubtype> sortedList = new ArrayList<InputMethodSubtype>();
        int N = imi.getSubtypeCount();
        for (int i = 0; i < N; ++i) {
            InputMethodSubtype subtype = imi.getSubtypeAt(i);
            if (inputSubtypesSet.contains(subtype)) {
                sortedList.add(subtype);
                inputSubtypesSet.remove(subtype);
            }
        }
        // If subtypes in inputSubtypesSet remain, that means these subtypes are not
        // contained in imi, so the remaining subtypes will be appended.
        for (InputMethodSubtype subtype: inputSubtypesSet) {
            sortedList.add(subtype);
        }
        return sortedList;
    }
}
