/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.settingslib.inputmethod;

import android.content.Context;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.Preference;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.inputmethod.InputMethodUtils;

import java.text.Collator;
import java.util.Locale;

/**
 * Input method subtype preference.
 *
 * This preference represents a subtype of an IME. It is used to enable or disable the subtype.
 */
public class InputMethodSubtypePreference extends SwitchWithNoTextPreference {
    private final boolean mIsSystemLocale;
    private final boolean mIsSystemLanguage;

    public InputMethodSubtypePreference(final Context context, final InputMethodSubtype subtype,
            final InputMethodInfo imi) {
        super(context);
        setPersistent(false);
        setKey(imi.getId() + subtype.hashCode());
        final CharSequence subtypeLabel =
                InputMethodAndSubtypeUtil.getSubtypeLocaleNameAsSentence(subtype, context, imi);
        setTitle(subtypeLabel);
        final String subtypeLocaleString = subtype.getLocale();
        if (TextUtils.isEmpty(subtypeLocaleString)) {
            mIsSystemLocale = false;
            mIsSystemLanguage = false;
        } else {
            final Locale systemLocale = context.getResources().getConfiguration().locale;
            mIsSystemLocale = subtypeLocaleString.equals(systemLocale.toString());
            mIsSystemLanguage = mIsSystemLocale
                    || InputMethodUtils.getLanguageFromLocaleString(subtypeLocaleString)
                            .equals(systemLocale.getLanguage());
        }
    }

    public int compareTo(final Preference rhs, final Collator collator) {
        if (this == rhs) {
            return 0;
        }
        if (rhs instanceof InputMethodSubtypePreference) {
            final InputMethodSubtypePreference rhsPref = (InputMethodSubtypePreference) rhs;
            if (mIsSystemLocale && !rhsPref.mIsSystemLocale) {
                return -1;
            }
            if (!mIsSystemLocale && rhsPref.mIsSystemLocale) {
                return 1;
            }
            if (mIsSystemLanguage && !rhsPref.mIsSystemLanguage) {
                return -1;
            }
            if (!mIsSystemLanguage && rhsPref.mIsSystemLanguage) {
                return 1;
            }
            final CharSequence t0 = getTitle();
            final CharSequence t1 = rhs.getTitle();
            if (t0 == null && t1 == null) {
                return Integer.compare(hashCode(), rhs.hashCode());
            }
            if (t0 != null && t1 != null) {
                return collator.compare(t0.toString(), t1.toString());
            }
            return t0 == null ? -1 : 1;
        }
        return super.compareTo(rhs);
    }
}
