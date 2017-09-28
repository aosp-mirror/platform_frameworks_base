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

package android.text;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Locale;

/**
 * Hyphenator is a wrapper class for a native implementation of automatic hyphenation,
 * in essence finding valid hyphenation opportunities in a word.
 *
 * @hide
 */
public class Hyphenator {
    private static String TAG = "Hyphenator";

    private final static Object sLock = new Object();

    @GuardedBy("sLock")
    final static HashMap<Locale, Hyphenator> sMap = new HashMap<Locale, Hyphenator>();

    private final long mNativePtr;
    private final HyphenationData mData;

    private Hyphenator(long nativePtr, HyphenationData data) {
        mNativePtr = nativePtr;
        mData = data;
    }

    public long getNativePtr() {
        return mNativePtr;
    }

    public static Hyphenator get(@Nullable Locale locale) {
        synchronized (sLock) {
            Hyphenator result = sMap.get(locale);
            if (result != null) {
                return result;
            }

            // If there's a variant, fall back to language+variant only, if available
            final String variant = locale.getVariant();
            if (!variant.isEmpty()) {
                final Locale languageAndVariantOnlyLocale =
                        new Locale(locale.getLanguage(), "", variant);
                result = sMap.get(languageAndVariantOnlyLocale);
                if (result != null) {
                    return putAlias(locale, result);
                }
            }

            // Fall back to language-only, if available
            final Locale languageOnlyLocale = new Locale(locale.getLanguage());
            result = sMap.get(languageOnlyLocale);
            if (result != null) {
                return putAlias(locale, result);
            }

            // Fall back to script-only, if available
            final String script = locale.getScript();
            if (!script.equals("")) {
                final Locale scriptOnlyLocale = new Locale.Builder()
                        .setLanguage("und")
                        .setScript(script)
                        .build();
                result = sMap.get(scriptOnlyLocale);
                if (result != null) {
                    return putAlias(locale, result);
                }
            }

            return putEmptyAlias(locale);
        }
    }

    private static class HyphenationData {
        private static final String SYSTEM_HYPHENATOR_LOCATION = "/system/usr/hyphen-data";

        public final int mMinPrefix, mMinSuffix;
        public final long mDataAddress;

        // Reasonable enough values for cases where we have no hyphenation patterns but may be able
        // to do some automatic hyphenation based on characters. These values would be used very
        // rarely.
        private static final int DEFAULT_MIN_PREFIX = 2;
        private static final int DEFAULT_MIN_SUFFIX = 2;

        public static final HyphenationData sEmptyData =
                new HyphenationData(DEFAULT_MIN_PREFIX, DEFAULT_MIN_SUFFIX);

        // Create empty HyphenationData.
        private HyphenationData(int minPrefix, int minSuffix) {
            mMinPrefix = minPrefix;
            mMinSuffix = minSuffix;
            mDataAddress = 0;
        }

        HyphenationData(String languageTag, int minPrefix, int minSuffix) {
            mMinPrefix = minPrefix;
            mMinSuffix = minSuffix;

            final String patternFilename = "hyph-" + languageTag.toLowerCase(Locale.US) + ".hyb";
            final File patternFile = new File(SYSTEM_HYPHENATOR_LOCATION, patternFilename);
            if (!patternFile.canRead()) {
                Log.e(TAG, "hyphenation patterns for " + patternFile + " not found or unreadable");
                mDataAddress = 0;
            } else {
                long address;
                try (RandomAccessFile f = new RandomAccessFile(patternFile, "r")) {
                    address = Os.mmap(0, f.length(), OsConstants.PROT_READ,
                            OsConstants.MAP_SHARED, f.getFD(), 0 /* offset */);
                } catch (IOException | ErrnoException e) {
                    Log.e(TAG, "error loading hyphenation " + patternFile, e);
                    address = 0;
                }
                mDataAddress = address;
            }
        }
    }

    // Do not call this method outside of init method.
    private static Hyphenator putNewHyphenator(Locale loc, HyphenationData data) {
        final Hyphenator hyphenator = new Hyphenator(nBuildHyphenator(
                data.mDataAddress, loc.getLanguage(), data.mMinPrefix, data.mMinSuffix), data);
        sMap.put(loc, hyphenator);
        return hyphenator;
    }

    // Do not call this method outside of init method.
    private static void loadData(String langTag, int minPrefix, int maxPrefix) {
        final HyphenationData data = new HyphenationData(langTag, minPrefix, maxPrefix);
        putNewHyphenator(Locale.forLanguageTag(langTag), data);
    }

    // Caller must acquire sLock before calling this method.
    // The Hyphenator for the baseLangTag must exists.
    private static Hyphenator addAliasByTag(String langTag, String baseLangTag) {
        return putAlias(Locale.forLanguageTag(langTag),
                sMap.get(Locale.forLanguageTag(baseLangTag)));
    }

    // Caller must acquire sLock before calling this method.
    private static Hyphenator putAlias(Locale locale, Hyphenator base) {
        return putNewHyphenator(locale, base.mData);
    }

    // Caller must acquire sLock before calling this method.
    private static Hyphenator putEmptyAlias(Locale locale) {
        return putNewHyphenator(locale, HyphenationData.sEmptyData);
    }

    // TODO: Confirm that these are the best values. Various sources suggest (1, 1), but
    // that appears too small.
    private static final int INDIC_MIN_PREFIX = 2;
    private static final int INDIC_MIN_SUFFIX = 2;

    /**
     * Load hyphenation patterns at initialization time. We want to have patterns
     * for all locales loaded and ready to use so we don't have to do any file IO
     * on the UI thread when drawing text in different locales.
     *
     * @hide
     */
    public static void init() {
        synchronized (sLock) {
            sMap.put(null, null);

            loadData("as", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Assamese
            loadData("bg", 2, 2); // Bulgarian
            loadData("bn", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Bengali
            loadData("cu", 1, 2); // Church Slavonic
            loadData("cy", 2, 3); // Welsh
            loadData("da", 2, 2); // Danish
            loadData("de-1901", 2, 2); // German 1901 orthography
            loadData("de-1996", 2, 2); // German 1996 orthography
            loadData("de-CH-1901", 2, 2); // Swiss High German 1901 orthography
            loadData("en-GB", 2, 3); // British English
            loadData("en-US", 2, 3); // American English
            loadData("es", 2, 2); // Spanish
            loadData("et", 2, 3); // Estonian
            loadData("eu", 2, 2); // Basque
            loadData("fr", 2, 3); // French
            loadData("ga", 2, 3); // Irish
            loadData("gu", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Gujarati
            loadData("hi", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Hindi
            loadData("hr", 2, 2); // Croatian
            loadData("hu", 2, 2); // Hungarian
            // texhyphen sources say Armenian may be (1, 2); but that it needs confirmation.
            // Going with a more conservative value of (2, 2) for now.
            loadData("hy", 2, 2); // Armenian
            loadData("kn", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Kannada
            loadData("ml", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Malayalam
            loadData("mn-Cyrl", 2, 2); // Mongolian in Cyrillic script
            loadData("mr", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Marathi
            loadData("nb", 2, 2); // Norwegian Bokmål
            loadData("nn", 2, 2); // Norwegian Nynorsk
            loadData("or", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Oriya
            loadData("pa", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Punjabi
            loadData("pt", 2, 3); // Portuguese
            loadData("sl", 2, 2); // Slovenian
            loadData("ta", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Tamil
            loadData("te", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX); // Telugu
            loadData("tk", 2, 2); // Turkmen
            loadData("und-Ethi", 1, 1); // Any language in Ethiopic script

            // English locales that fall back to en-US. The data is
            // from CLDR. It's all English locales, minus the locales whose
            // parent is en-001 (from supplementalData.xml, under <parentLocales>).
            // TODO: Figure out how to get this from ICU.
            addAliasByTag("en-AS", "en-US"); // English (American Samoa)
            addAliasByTag("en-GU", "en-US"); // English (Guam)
            addAliasByTag("en-MH", "en-US"); // English (Marshall Islands)
            addAliasByTag("en-MP", "en-US"); // English (Northern Mariana Islands)
            addAliasByTag("en-PR", "en-US"); // English (Puerto Rico)
            addAliasByTag("en-UM", "en-US"); // English (United States Minor Outlying Islands)
            addAliasByTag("en-VI", "en-US"); // English (Virgin Islands)

            // All English locales other than those falling back to en-US are mapped to en-GB.
            addAliasByTag("en", "en-GB");

            // For German, we're assuming the 1996 (and later) orthography by default.
            addAliasByTag("de", "de-1996");
            // Liechtenstein uses the Swiss hyphenation rules for the 1901 orthography.
            addAliasByTag("de-LI-1901", "de-CH-1901");

            // Norwegian is very probably Norwegian Bokmål.
            addAliasByTag("no", "nb");

            // Use mn-Cyrl. According to CLDR's likelySubtags.xml, mn is most likely to be mn-Cyrl.
            addAliasByTag("mn", "mn-Cyrl"); // Mongolian

            // Fall back to Ethiopic script for languages likely to be written in Ethiopic.
            // Data is from CLDR's likelySubtags.xml.
            // TODO: Convert this to a mechanism using ICU4J's ULocale#addLikelySubtags().
            addAliasByTag("am", "und-Ethi"); // Amharic
            addAliasByTag("byn", "und-Ethi"); // Blin
            addAliasByTag("gez", "und-Ethi"); // Geʻez
            addAliasByTag("ti", "und-Ethi"); // Tigrinya
            addAliasByTag("wal", "und-Ethi"); // Wolaytta
        }
    };

    private static native long nBuildHyphenator(/* non-zero */ long dataAddress,
            @NonNull String langTag, @IntRange(from = 1) int minPrefix,
            @IntRange(from = 1) int minSuffix);
}
