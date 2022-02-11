/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import android.os.LocaleList;

import java.util.Locale;

/**
 * Static utilities to overlay locales on top of another localeList.
 *
 * <p>This is used to overlay application-specific locales in
 *  {@link com.android.server.wm.ActivityTaskManagerInternal.PackageConfigurationUpdater} on top of
 *  system locales.
 */
final class LocaleOverlayHelper {

    /**
     * Combines the overlay locales and base locales.
     * @return the combined {@link LocaleList} if the overlay locales is not empty/null else
     * returns the empty/null LocaleList.
     */
    static LocaleList combineLocalesIfOverlayExists(LocaleList overlayLocales,
            LocaleList baseLocales) {
        if (overlayLocales == null || overlayLocales.isEmpty()) {
            return overlayLocales;
        }
        return combineLocales(overlayLocales, baseLocales);
    }

    /**
     * Creates a combined {@link LocaleList} by placing overlay locales before base locales and
     * dropping duplicates from the base locales.
     */
    private static LocaleList combineLocales(LocaleList overlayLocales, LocaleList baseLocales) {
        Locale[] combinedLocales = new Locale[overlayLocales.size() + baseLocales.size()];
        for (int i = 0; i < overlayLocales.size(); i++) {
            combinedLocales[i] = overlayLocales.get(i);
        }
        for (int i = 0; i < baseLocales.size(); i++) {
            combinedLocales[i + overlayLocales.size()] = baseLocales.get(i);
        }
        // Constructor of {@link LocaleList} removes duplicates
        return new LocaleList(combinedLocales);
    }


}
