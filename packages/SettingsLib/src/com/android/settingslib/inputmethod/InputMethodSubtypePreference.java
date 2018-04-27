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
import androidx.preference.Preference;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;

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
        this(context,
                imi.getId() + subtype.hashCode(),
                InputMethodAndSubtypeUtil.getSubtypeLocaleNameAsSentence(subtype, context, imi),
                subtype.getLocaleObject(),
                context.getResources().getConfiguration().locale);
    }

    @VisibleForTesting
    InputMethodSubtypePreference(
            final Context context,
            final String prefKey,
            final CharSequence title,
            final Locale subtypeLocale,
            final Locale systemLocale) {
        super(context);
        setPersistent(false);
        setKey(prefKey);
        setTitle(title);
        if (subtypeLocale == null) {
            mIsSystemLocale = false;
            mIsSystemLanguage = false;
        } else {
            mIsSystemLocale = subtypeLocale.equals(systemLocale);
            mIsSystemLanguage = mIsSystemLocale
                    || TextUtils.equals(subtypeLocale.getLanguage(), systemLocale.getLanguage());
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
            final CharSequence title = getTitle();
            final CharSequence rhsTitle = rhs.getTitle();
            final boolean emptyTitle = TextUtils.isEmpty(title);
            final boolean rhsEmptyTitle = TextUtils.isEmpty(rhsTitle);
            if (!emptyTitle && !rhsEmptyTitle) {
                return collator.compare(title.toString(), rhsTitle.toString());
            }
            // For historical reasons, an empty text needs to be put at the first.
            return (emptyTitle ? -1 : 0) - (rhsEmptyTitle ? -1 : 0);
        }
        return super.compareTo(rhs);
    }
}
