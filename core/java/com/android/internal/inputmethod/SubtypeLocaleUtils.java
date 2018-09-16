/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.inputmethod;

import android.annotation.Nullable;
import android.text.TextUtils;

import java.util.Locale;

/**
 * A utility class to handle {@link Locale} related logic for
 * {@link android.view.inputmethod.InputMethodSubtype} and
 * {@link android.view.textservice.SpellCheckerSubtype}.
 */
public class SubtypeLocaleUtils {
    /**
     * Maintains deprecated logic about how subtype locales specified in XML resources have been
     * parsed.
     *
     * <p>This logic is kept basically for compatibility purpose.  Consider relying on languageTag
     * attribute instead.</p>
     *
     * @param localeStr string representation that is specified in the locale attribute
     * @return {@link Locale} object parsed from {@code localeStr}. {@code null} for unexpected
     *         format
     *
     * @attr ref android.R.styleable#InputMethod_Subtype_imeSubtypeLocale
     * @attr ref android.R.styleable#InputMethod_Subtype_languageTag
     * @attr ref android.R.styleable#SpellChecker_Subtype_languageTag
     * @attr ref android.R.styleable#SpellChecker_Subtype_subtypeLocale
     */
    @Nullable
    public static Locale constructLocaleFromString(String localeStr) {
        if (TextUtils.isEmpty(localeStr)) {
            return null;
        }
        // TODO: Use {@link Locale#toLanguageTag()} and {@link Locale#forLanguageTag(languageTag)}.
        String[] localeParams = localeStr.split("_", 3);
        if (localeParams.length >= 1 && "tl".equals(localeParams[0])) {
            // Convert a locale whose language is "tl" to one whose language is "fil".
            // For example, "tl_PH" will get converted to "fil_PH".
            // Versions of Android earlier than Lollipop did not support three letter language
            // codes, and used "tl" (Tagalog) as the language string for "fil" (Filipino).
            // On Lollipop and above, the current three letter version must be used.
            localeParams[0] = "fil";
        }
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
}
