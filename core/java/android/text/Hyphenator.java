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

import android.annotation.Nullable;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Locale;

/**
 * Hyphenator is a wrapper class for a native implementation of automatic hyphenation,
 * in essence finding valid hyphenation opportunities in a word.
 *
 * @hide
 */
public class Hyphenator {
    // This class has deliberately simple lifetime management (no finalizer) because in
    // the common case a process will use a very small number of locales.

    private static String TAG = "Hyphenator";

    // TODO: Confirm that these are the best values. Various sources suggest (1, 1), but
    // that appears too small.
    private static final int INDIC_MIN_PREFIX = 2;
    private static final int INDIC_MIN_SUFFIX = 2;

    private final static Object sLock = new Object();

    @GuardedBy("sLock")
    final static HashMap<Locale, Hyphenator> sMap = new HashMap<Locale, Hyphenator>();

    // Reasonable enough values for cases where we have no hyphenation patterns but may be able to
    // do some automatic hyphenation based on characters. These values would be used very rarely.
    private static final int DEFAULT_MIN_PREFIX = 2;
    private static final int DEFAULT_MIN_SUFFIX = 2;
    final static Hyphenator sEmptyHyphenator =
            new Hyphenator(StaticLayout.nLoadHyphenator(
                                   null, 0, DEFAULT_MIN_PREFIX, DEFAULT_MIN_SUFFIX),
                           null);

    final private long mNativePtr;

    // We retain a reference to the buffer to keep the memory mapping valid
    @SuppressWarnings("unused")
    final private ByteBuffer mBuffer;

    private Hyphenator(long nativePtr, ByteBuffer b) {
        mNativePtr = nativePtr;
        mBuffer = b;
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
                    sMap.put(locale, result);
                    return result;
                }
            }

            // Fall back to language-only, if available
            final Locale languageOnlyLocale = new Locale(locale.getLanguage());
            result = sMap.get(languageOnlyLocale);
            if (result != null) {
                sMap.put(locale, result);
                return result;
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
                    sMap.put(locale, result);
                    return result;
                }
            }

            sMap.put(locale, sEmptyHyphenator);  // To remember we found nothing.
        }
        return sEmptyHyphenator;
    }

    private static class HyphenationData {
        final String mLanguageTag;
        final int mMinPrefix, mMinSuffix;
        HyphenationData(String languageTag, int minPrefix, int minSuffix) {
            this.mLanguageTag = languageTag;
            this.mMinPrefix = minPrefix;
            this.mMinSuffix = minSuffix;
        }
    }

    private static Hyphenator loadHyphenator(HyphenationData data) {
        String patternFilename = "hyph-" + data.mLanguageTag.toLowerCase(Locale.US) + ".hyb";
        File patternFile = new File(getSystemHyphenatorLocation(), patternFilename);
        if (!patternFile.canRead()) {
            Log.e(TAG, "hyphenation patterns for " + patternFile + " not found or unreadable");
            return null;
        }
        try {
            RandomAccessFile f = new RandomAccessFile(patternFile, "r");
            try {
                FileChannel fc = f.getChannel();
                MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
                long nativePtr = StaticLayout.nLoadHyphenator(
                        buf, 0, data.mMinPrefix, data.mMinSuffix);
                return new Hyphenator(nativePtr, buf);
            } finally {
                f.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "error loading hyphenation " + patternFile, e);
            return null;
        }
    }

    private static File getSystemHyphenatorLocation() {
        return new File("/system/usr/hyphen-data");
    }

    // This array holds pairs of language tags that are used to prefill the map from locale to
    // hyphenation data: The hyphenation data for the first field will be prefilled from the
    // hyphenation data for the second field.
    //
    // The aliases that are computable by the get() method above are not included.
    private static final String[][] LOCALE_FALLBACK_DATA = {
        // English locales that fall back to en-US. The data is
        // from CLDR. It's all English locales, minus the locales whose
        // parent is en-001 (from supplementalData.xml, under <parentLocales>).
        // TODO: Figure out how to get this from ICU.
        {"en-AS", "en-US"}, // English (American Samoa)
        {"en-GU", "en-US"}, // English (Guam)
        {"en-MH", "en-US"}, // English (Marshall Islands)
        {"en-MP", "en-US"}, // English (Northern Mariana Islands)
        {"en-PR", "en-US"}, // English (Puerto Rico)
        {"en-UM", "en-US"}, // English (United States Minor Outlying Islands)
        {"en-VI", "en-US"}, // English (Virgin Islands)

        // All English locales other than those falling back to en-US are mapped to en-GB.
        {"en", "en-GB"},

        // For German, we're assuming the 1996 (and later) orthography by default.
        {"de", "de-1996"},
        // Liechtenstein uses the Swiss hyphenation rules for the 1901 orthography.
        {"de-LI-1901", "de-CH-1901"},

        // Norwegian is very probably Norwegian Bokmål.
        {"no", "nb"},

        // Use mn-Cyrl. According to CLDR's likelySubtags.xml, mn is most likely to be mn-Cyrl.
        {"mn", "mn-Cyrl"}, // Mongolian

        // Fall back to Ethiopic script for languages likely to be written in Ethiopic.
        // Data is from CLDR's likelySubtags.xml.
        // TODO: Convert this to a mechanism using ICU4J's ULocale#addLikelySubtags().
        {"am", "und-Ethi"}, // Amharic
        {"byn", "und-Ethi"}, // Blin
        {"gez", "und-Ethi"}, // Geʻez
        {"ti", "und-Ethi"}, // Tigrinya
        {"wal", "und-Ethi"}, // Wolaytta
    };

    private static final HyphenationData[] AVAILABLE_LANGUAGES = {
        new HyphenationData("as", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Assamese
        new HyphenationData("bg", 2, 2), // Bulgarian
        new HyphenationData("bn", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Bengali
        new HyphenationData("cu", 1, 2), // Church Slavonic
        new HyphenationData("cy", 2, 3), // Welsh
        new HyphenationData("da", 2, 2), // Danish
        new HyphenationData("de-1901", 2, 2), // German 1901 orthography
        new HyphenationData("de-1996", 2, 2), // German 1996 orthography
        new HyphenationData("de-CH-1901", 2, 2), // Swiss High German 1901 orthography
        new HyphenationData("en-GB", 2, 3), // British English
        new HyphenationData("en-US", 2, 3), // American English
        new HyphenationData("es", 2, 2), // Spanish
        new HyphenationData("et", 2, 3), // Estonian
        new HyphenationData("eu", 2, 2), // Basque
        new HyphenationData("fr", 2, 3), // French
        new HyphenationData("ga", 2, 3), // Irish
        new HyphenationData("gu", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Gujarati
        new HyphenationData("hi", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Hindi
        new HyphenationData("hr", 2, 2), // Croatian
        new HyphenationData("hu", 2, 2), // Hungarian
        // texhyphen sources say Armenian may be (1, 2), but that it needs confirmation.
        // Going with a more conservative value of (2, 2) for now.
        new HyphenationData("hy", 2, 2), // Armenian
        new HyphenationData("kn", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Kannada
        new HyphenationData("ml", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Malayalam
        new HyphenationData("mn-Cyrl", 2, 2), // Mongolian in Cyrillic script
        new HyphenationData("mr", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Marathi
        new HyphenationData("nb", 2, 2), // Norwegian Bokmål
        new HyphenationData("nn", 2, 2), // Norwegian Nynorsk
        new HyphenationData("or", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Oriya
        new HyphenationData("pa", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Punjabi
        new HyphenationData("pt", 2, 3), // Portuguese
        new HyphenationData("sl", 2, 2), // Slovenian
        new HyphenationData("ta", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Tamil
        new HyphenationData("te", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX), // Telugu
        new HyphenationData("tk", 2, 2), // Turkmen
        new HyphenationData("und-Ethi", 1, 1), // Any language in Ethiopic script
    };

    /**
     * Load hyphenation patterns at initialization time. We want to have patterns
     * for all locales loaded and ready to use so we don't have to do any file IO
     * on the UI thread when drawing text in different locales.
     *
     * @hide
     */
    public static void init() {
        sMap.put(null, null);

        for (int i = 0; i < AVAILABLE_LANGUAGES.length; i++) {
            HyphenationData data = AVAILABLE_LANGUAGES[i];
            Hyphenator h = loadHyphenator(data);
            if (h != null) {
                sMap.put(Locale.forLanguageTag(data.mLanguageTag), h);
            }
        }

        for (int i = 0; i < LOCALE_FALLBACK_DATA.length; i++) {
            String language = LOCALE_FALLBACK_DATA[i][0];
            String fallback = LOCALE_FALLBACK_DATA[i][1];
            sMap.put(Locale.forLanguageTag(language), sMap.get(Locale.forLanguageTag(fallback)));
        }
    }
}
