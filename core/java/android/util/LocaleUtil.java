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

package android.util;

import java.util.Locale;

import libcore.icu.ICU;

/**
 * Various utilities for Locales
 *
 * @hide
 */
public class LocaleUtil {

    private LocaleUtil() { /* cannot be instantiated */ }

    /**
     * @hide Do not use. Implementation not finished.
     */
    public static final int TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE = 0;

    /**
     * @hide Do not use. Implementation not finished.
     */
    public static final int TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE = 1;

    private static String ARAB_SCRIPT_SUBTAG = "Arab";
    private static String HEBR_SCRIPT_SUBTAG = "Hebr";

    /**
     * Return the layout direction for a given Locale
     *
     * @param locale the Locale for which we want the layout direction. Can be null.
     * @return the layout direction. This may be one of:
     * {@link #TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE} or
     * {@link #TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE}.
     *
     * Be careful: this code will need to be changed when vertical scripts will be supported
     *
     * @hide
     */
    public static int getLayoutDirectionFromLocale(Locale locale) {
        if (locale != null && !locale.equals(Locale.ROOT)) {
            final String scriptSubtag = ICU.getScript(ICU.addLikelySubtags(locale.toString()));
            if (scriptSubtag == null) return getLayoutDirectionFromFirstChar(locale);

            if (scriptSubtag.equalsIgnoreCase(ARAB_SCRIPT_SUBTAG) ||
                    scriptSubtag.equalsIgnoreCase(HEBR_SCRIPT_SUBTAG)) {
                return TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE;
            }
        }

        return TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE;
    }

    /**
     * Fallback algorithm to detect the locale direction. Rely on the fist char of the
     * localized locale name. This will not work if the localized locale name is in English
     * (this is the case for ICU 4.4 and "Urdu" script)
     *
     * @param locale
     * @return the layout direction. This may be one of:
     * {@link #TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE} or
     * {@link #TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE}.
     *
     * Be careful: this code will need to be changed when vertical scripts will be supported
     *
     * @hide
     */
    private static int getLayoutDirectionFromFirstChar(Locale locale) {
        switch(Character.getDirectionality(locale.getDisplayName(locale).charAt(0))) {
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
                return TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE;

            case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
            default:
                return TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE;
        }
    }
}
