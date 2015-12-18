/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.icu.util.ULocale;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.GuardedBy;

import java.util.HashSet;
import java.util.Locale;

// TODO: We don't except too many LocaleLists to exist at the same time, and
// we need access to the data at native level, so we should pass the data
// down to the native level, create a map of every list seen there, take a
// pointer back, and just keep that pointer in the Java-level object, so
// things could be copied very quickly.

/**
 * LocaleList is an immutable list of Locales, typically used to keep an
 * ordered user preferences for locales.
 */
public final class LocaleList implements Parcelable {
    private final Locale[] mList;
    // This is a comma-separated list of the locales in the LocaleList created at construction time,
    // basically the result of running each locale's toLanguageTag() method and concatenating them
    // with commas in between.
    @NonNull
    private final String mStringRepresentation;

    private static final Locale[] sEmptyList = new Locale[0];
    private static final LocaleList sEmptyLocaleList = new LocaleList();

    public Locale get(int location) {
        return location < mList.length ? mList[location] : null;
    }

    @Nullable
    public Locale getPrimary() {
        return mList.length == 0 ? null : get(0);
    }

    public boolean isEmpty() {
        return mList.length == 0;
    }

    public int size() {
        return mList.length;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this)
            return true;
        if (!(other instanceof LocaleList))
            return false;
        final Locale[] otherList = ((LocaleList) other).mList;
        if (mList.length != otherList.length)
            return false;
        for (int i = 0; i < mList.length; ++i) {
            if (!mList[i].equals(otherList[i]))
                return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < mList.length; ++i) {
            result = 31 * result + mList[i].hashCode();
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < mList.length; ++i) {
            sb.append(mList[i]);
            if (i < mList.length - 1) {
                sb.append(',');
            }
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeString(mStringRepresentation);
    }

    @NonNull
    public String toLanguageTags() {
        return mStringRepresentation;
    }

    /**
     * It is almost always better to call {@link #getEmptyLocaleList()} instead which returns
     * a pre-constructed empty locale list.
     */
    public LocaleList() {
        mList = sEmptyList;
        mStringRepresentation = "";
    }

    /**
     * @throws NullPointerException if any of the input locales is <code>null</code>.
     * @throws IllegalArgumentException if any of the input locales repeat.
     */
    public LocaleList(@Nullable Locale locale) {
        if (locale == null) {
            mList = sEmptyList;
            mStringRepresentation = "";
        } else {
            mList = new Locale[1];
            mList[0] = (Locale) locale.clone();
            mStringRepresentation = locale.toLanguageTag();
        }
    }

    /**
     * @throws NullPointerException if any of the input locales is <code>null</code>.
     * @throws IllegalArgumentException if any of the input locales repeat.
     */
    public LocaleList(@Nullable Locale[] list) {
        if (list == null || list.length == 0) {
            mList = sEmptyList;
            mStringRepresentation = "";
        } else {
            final Locale[] localeList = new Locale[list.length];
            final HashSet<Locale> seenLocales = new HashSet<Locale>();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.length; ++i) {
                final Locale l = list[i];
                if (l == null) {
                    throw new NullPointerException();
                } else if (seenLocales.contains(l)) {
                    throw new IllegalArgumentException();
                } else {
                    final Locale localeClone = (Locale) l.clone();
                    localeList[i] = localeClone;
                    sb.append(localeClone.toLanguageTag());
                    if (i < list.length - 1) {
                        sb.append(',');
                    }
                    seenLocales.add(localeClone);
                }
            }
            mList = localeList;
            mStringRepresentation = sb.toString();
        }
    }

    public static final Parcelable.Creator<LocaleList> CREATOR
            = new Parcelable.Creator<LocaleList>() {
        @Override
        public LocaleList createFromParcel(Parcel source) {
            return LocaleList.forLanguageTags(source.readString());
        }

        @Override
        public LocaleList[] newArray(int size) {
            return new LocaleList[size];
        }
    };

    @NonNull
    public static LocaleList getEmptyLocaleList() {
        return sEmptyLocaleList;
    }

    @NonNull
    public static LocaleList forLanguageTags(@Nullable String list) {
        if (list == null || list.equals("")) {
            return getEmptyLocaleList();
        } else {
            final String[] tags = list.split(",");
            final Locale[] localeArray = new Locale[tags.length];
            for (int i = 0; i < localeArray.length; ++i) {
                localeArray[i] = Locale.forLanguageTag(tags[i]);
            }
            return new LocaleList(localeArray);
        }
    }

    private static String getLikelyScript(Locale locale) {
        final String script = locale.getScript();
        if (!script.isEmpty()) {
            return script;
        } else {
            // TODO: Cache the results if this proves to be too slow
            return ULocale.addLikelySubtags(ULocale.forLocale(locale)).getScript();
        }
    }

    private static final String STRING_EN_XA = "en-XA";
    private static final String STRING_AR_XB = "ar-XB";
    private static final Locale LOCALE_EN_XA = new Locale("en", "XA");
    private static final Locale LOCALE_AR_XB = new Locale("ar", "XB");
    private static final int NUM_PSEUDO_LOCALES = 2;

    private static boolean isPseudoLocale(String locale) {
        return STRING_EN_XA.equals(locale) || STRING_AR_XB.equals(locale);
    }

    private static boolean isPseudoLocale(Locale locale) {
        return LOCALE_EN_XA.equals(locale) || LOCALE_AR_XB.equals(locale);
    }

    private static int matchScore(Locale supported, Locale desired) {
        if (supported.equals(desired)) {
            return 1;  // return early so we don't do unnecessary computation
        }
        if (!supported.getLanguage().equals(desired.getLanguage())) {
            return 0;
        }
        if (isPseudoLocale(supported) || isPseudoLocale(desired)) {
            // The locales are not the same, but the languages are the same, and one of the locales
            // is a pseudo-locale. So this is not a match.
            return 0;
        }
        // There is no match if the two locales use different scripts. This will most imporantly
        // take care of traditional vs simplified Chinese.
        final String supportedScr = getLikelyScript(supported);
        final String desiredScr = getLikelyScript(desired);
        return supportedScr.equals(desiredScr) ? 1 : 0;
    }

    /**
     * Returns the first match in the locale list given an unordered array of supported locales
     * in BCP47 format.
     *
     * If the locale list is empty, null would be returned.
     */
    @Nullable
    public Locale getFirstMatch(String[] supportedLocales) {
        if (mList.length == 1) {  // just one locale, perhaps the most common scenario
            return mList[0];
        }
        if (mList.length == 0) {  // empty locale list
            return null;
        }
        int bestIndex = Integer.MAX_VALUE;
        for (String tag : supportedLocales) {
            final Locale supportedLocale = Locale.forLanguageTag(tag);
            // We expect the average length of locale lists used for locale resolution to be
            // smaller than three, so it's OK to do this as an O(mn) algorithm.
            for (int idx = 0; idx < mList.length; idx++) {
                final int score = matchScore(supportedLocale, mList[idx]);
                if (score > 0) {
                    if (idx == 0) {  // We have a match on the first locale, which is good enough
                        return mList[0];
                    } else if (idx < bestIndex) {
                        bestIndex = idx;
                    }
                }
            }
        }
        if (bestIndex == Integer.MAX_VALUE) {  // no match was found
            return mList[0];
        } else {
            return mList[bestIndex];
        }
    }

    /**
     * Returns true if the array of locale tags only contains empty locales and pseudolocales.
     * Assumes that there is no repetition in the input.
     * {@hide}
     */
    public static boolean isPseudoLocalesOnly(String[] supportedLocales) {
        if (supportedLocales.length > NUM_PSEUDO_LOCALES + 1) {
            // This is for optimization. Since there's no repetition in the input, if we have more
            // than the number of pseudo-locales plus one for the empty string, it's guaranteed
            // that we have some meaninful locale in the list, so the list is not "practically
            // empty".
            return false;
        }
        for (String locale : supportedLocales) {
            if (!locale.isEmpty() && !isPseudoLocale(locale)) {
                return false;
            }
        }
        return true;
    }

    private final static Object sLock = new Object();

    @GuardedBy("sLock")
    private static LocaleList sDefaultLocaleList;

    // TODO: fix this to return the default system locale list once we have that
    @NonNull @Size(min=1)
    public static LocaleList getDefault() {
        Locale defaultLocale = Locale.getDefault();
        synchronized (sLock) {
            if (sDefaultLocaleList == null || sDefaultLocaleList.size() != 1
                    || !defaultLocale.equals(sDefaultLocaleList.getPrimary())) {
                sDefaultLocaleList = new LocaleList(defaultLocale);
            }
        }
        return sDefaultLocaleList;
    }
}
