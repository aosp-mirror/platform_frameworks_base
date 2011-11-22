/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.view.textservice;

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
 * This class is used to specify meta information of a subtype contained in a spell checker.
 * Subtype can describe locale (e.g. en_US, fr_FR...) used for settings.
 */
public final class SpellCheckerSubtype implements Parcelable {
    private static final String TAG = SpellCheckerSubtype.class.getSimpleName();
    private static final String EXTRA_VALUE_PAIR_SEPARATOR = ",";
    private static final String EXTRA_VALUE_KEY_VALUE_SEPARATOR = "=";

    private final int mSubtypeHashCode;
    private final int mSubtypeNameResId;
    private final String mSubtypeLocale;
    private final String mSubtypeExtraValue;
    private HashMap<String, String> mExtraValueHashMapCache;

    /**
     * Constructor
     * @param nameId The name of the subtype
     * @param locale The locale supported by the subtype
     * @param extraValue The extra value of the subtype
     */
    public SpellCheckerSubtype(int nameId, String locale, String extraValue) {
        mSubtypeNameResId = nameId;
        mSubtypeLocale = locale != null ? locale : "";
        mSubtypeExtraValue = extraValue != null ? extraValue : "";
        mSubtypeHashCode = hashCodeInternal(mSubtypeLocale, mSubtypeExtraValue);
    }

    SpellCheckerSubtype(Parcel source) {
        String s;
        mSubtypeNameResId = source.readInt();
        s = source.readString();
        mSubtypeLocale = s != null ? s : "";
        s = source.readString();
        mSubtypeExtraValue = s != null ? s : "";
        mSubtypeHashCode = hashCodeInternal(mSubtypeLocale, mSubtypeExtraValue);
    }

    /**
     * @return the name of the subtype
     */
    public int getNameResId() {
        return mSubtypeNameResId;
    }

    /**
     * @return the locale of the subtype
     */
    public String getLocale() {
        return mSubtypeLocale;
    }

    /**
     * @return the extra value of the subtype
     */
    public String getExtraValue() {
        return mSubtypeExtraValue;
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
     * @hide
     * The string of ExtraValue in subtype should be defined as follows:
     * example: key0,key1=value1,key2,key3,key4=value4
     * @param key the key of extra value
     * @return the subtype contains specified the extra value
     */
    public boolean containsExtraValueKey(String key) {
        return getExtraValueHashMap().containsKey(key);
    }

    /**
     * @hide
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
        if (o instanceof SpellCheckerSubtype) {
            SpellCheckerSubtype subtype = (SpellCheckerSubtype) o;
            return (subtype.hashCode() == hashCode())
                && (subtype.getNameResId() == getNameResId())
                && (subtype.getLocale().equals(getLocale()))
                && (subtype.getExtraValue().equals(getExtraValue()));
        }
        return false;
    }

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

    /**
     * @param context Context will be used for getting Locale and PackageManager.
     * @param packageName The package name of the spell checker
     * @param appInfo The application info of the spell checker
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(mSubtypeNameResId);
        dest.writeString(mSubtypeLocale);
        dest.writeString(mSubtypeExtraValue);
    }

    public static final Parcelable.Creator<SpellCheckerSubtype> CREATOR
            = new Parcelable.Creator<SpellCheckerSubtype>() {
        @Override
        public SpellCheckerSubtype createFromParcel(Parcel source) {
            return new SpellCheckerSubtype(source);
        }

        @Override
        public SpellCheckerSubtype[] newArray(int size) {
            return new SpellCheckerSubtype[size];
        }
    };

    private static int hashCodeInternal(String locale, String extraValue) {
        return Arrays.hashCode(new Object[] {locale, extraValue});
    }

    /**
     * Sort the list of subtypes
     * @param context Context will be used for getting localized strings
     * @param flags Flags for the sort order
     * @param sci SpellCheckerInfo of which subtypes are subject to be sorted
     * @param subtypeList List which will be sorted
     * @return Sorted list of subtypes
     * @hide
     */
    public static List<SpellCheckerSubtype> sort(Context context, int flags, SpellCheckerInfo sci,
            List<SpellCheckerSubtype> subtypeList) {
        if (sci == null) return subtypeList;
        final HashSet<SpellCheckerSubtype> subtypesSet = new HashSet<SpellCheckerSubtype>(
                subtypeList);
        final ArrayList<SpellCheckerSubtype> sortedList = new ArrayList<SpellCheckerSubtype>();
        int N = sci.getSubtypeCount();
        for (int i = 0; i < N; ++i) {
            SpellCheckerSubtype subtype = sci.getSubtypeAt(i);
            if (subtypesSet.contains(subtype)) {
                sortedList.add(subtype);
                subtypesSet.remove(subtype);
            }
        }
        // If subtypes in subtypesSet remain, that means these subtypes are not
        // contained in sci, so the remaining subtypes will be appended.
        for (SpellCheckerSubtype subtype: subtypesSet) {
            sortedList.add(subtype);
        }
        return sortedList;
    }
}
