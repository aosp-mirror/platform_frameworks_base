/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.Nullable;
import android.content.Context;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.util.Locale;

/**
 * This class provides utility methods to handle and manage {@link InputMethodSubtype} for
 * {@link InputMethodManagerService}.
 *
 * <p>This class is intentionally package-private.  Utility methods here are tightly coupled with
 * implementation details in {@link InputMethodManagerService}.  Hence this class is not suitable
 * for other components to directly use.</p>
 */
final class SubtypeUtils {
    static final String SUBTYPE_MODE_ANY = null;
    static final String SUBTYPE_MODE_KEYBOARD = "keyboard";

    static boolean containsSubtypeOf(InputMethodInfo imi, @Nullable Locale locale,
            boolean checkCountry, String mode) {
        if (locale == null) {
            return false;
        }
        final int N = imi.getSubtypeCount();
        for (int i = 0; i < N; ++i) {
            final InputMethodSubtype subtype = imi.getSubtypeAt(i);
            if (checkCountry) {
                final Locale subtypeLocale = subtype.getLocaleObject();
                if (subtypeLocale == null ||
                        !TextUtils.equals(subtypeLocale.getLanguage(), locale.getLanguage()) ||
                        !TextUtils.equals(subtypeLocale.getCountry(), locale.getCountry())) {
                    continue;
                }
            } else {
                final Locale subtypeLocale = new Locale(LocaleUtils.getLanguageFromLocaleString(
                        subtype.getLocale()));
                if (!TextUtils.equals(subtypeLocale.getLanguage(), locale.getLanguage())) {
                    continue;
                }
            }
            if (mode == SUBTYPE_MODE_ANY || TextUtils.isEmpty(mode) ||
                    mode.equalsIgnoreCase(subtype.getMode())) {
                return true;
            }
        }
        return false;
    }
}
