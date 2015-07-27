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

import com.android.internal.annotations.GuardedBy;

import android.annotation.Nullable;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
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

    private final static Object sLock = new Object();

    @GuardedBy("sLock")
    final static HashMap<Locale, Hyphenator> sMap = new HashMap<Locale, Hyphenator>();

    final static Hyphenator sEmptyHyphenator = new Hyphenator(StaticLayout.nLoadHyphenator(""));

    final private long mNativePtr;

    private Hyphenator(long nativePtr) {
        mNativePtr = nativePtr;
    }

    public static long get(@Nullable Locale locale) {
        synchronized (sLock) {
            Hyphenator result = sMap.get(locale);
            if (result != null) {
                return result.mNativePtr;
            }

            // TODO: Convert this a proper locale-fallback system

            // Fall back to language-only, if available
            Locale languageOnlyLocale = new Locale(locale.getLanguage());
            result = sMap.get(languageOnlyLocale);
            if (result != null) {
                sMap.put(locale, result);
                return result.mNativePtr;
            }

            // Fall back to script-only, if available
            String script = locale.getScript();
            if (!script.equals("")) {
                Locale scriptOnlyLocale = new Locale.Builder()
                        .setLanguage("und")
                        .setScript(script)
                        .build();
                result = sMap.get(scriptOnlyLocale);
                if (result != null) {
                    sMap.put(locale, result);
                    return result.mNativePtr;
                }
            }

            sMap.put(locale, sEmptyHyphenator);  // To remember we found nothing.
        }
        return sEmptyHyphenator.mNativePtr;
    }

    private static Hyphenator loadHyphenator(String languageTag) {
        String patternFilename = "hyph-"+languageTag.toLowerCase(Locale.US)+".pat.txt";
        File patternFile = new File(getSystemHyphenatorLocation(), patternFilename);
        try {
            String patternData = IoUtils.readFileAsString(patternFile.getAbsolutePath());
            long nativePtr = StaticLayout.nLoadHyphenator(patternData);
            return new Hyphenator(nativePtr);
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

        // Norwegian is very probably Norwegian Bokmål.
        {"no", "nb"},

        // Fall back to Ethiopic script for languages likely to be written in Ethiopic.
        // Data is from CLDR's likelySubtags.xml.
        // TODO: Convert this to a mechanism using ICU4J's ULocale#addLikelySubtags().
        {"am", "und-Ethi"}, // Amharic
        {"byn", "und-Ethi"}, // Blin
        {"gez", "und-Ethi"}, // Geʻez
        {"ti", "und-Ethi"}, // Tigrinya
        {"wal", "und-Ethi"}, // Wolaytta
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

        // TODO: replace this with a discovery-based method that looks into /system/usr/hyphen-data
        String[] availableLanguages = {"en-US", "eu", "hu", "hy", "nb", "nn", "sa", "und-Ethi"};
        for (int i = 0; i < availableLanguages.length; i++) {
            String languageTag = availableLanguages[i];
            Hyphenator h = loadHyphenator(languageTag);
            if (h != null) {
                sMap.put(Locale.forLanguageTag(languageTag), h);
            }
        }

        for (int i = 0; i < LOCALE_FALLBACK_DATA.length; i++) {
            String language = LOCALE_FALLBACK_DATA[i][0];
            String fallback = LOCALE_FALLBACK_DATA[i][1];
            sMap.put(Locale.forLanguageTag(language), sMap.get(Locale.forLanguageTag(fallback)));
        }
    }
}
