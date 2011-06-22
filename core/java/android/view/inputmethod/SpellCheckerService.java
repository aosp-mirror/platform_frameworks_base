/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;

import java.util.Arrays;
import java.util.Locale;

public abstract class SpellCheckerService extends Service {
    public static final String SERVICE_INTERFACE = SpellCheckerService.class.getName();

    private final SpellCheckerServiceBinder mBinder = new SpellCheckerServiceBinder(this);

    /**
     * Check if the substring of text from start to end is a correct word or not in the specified
     * locale.
     * @param text the substring of text from start to end will be checked.
     * @param start the start position of the text to be checked (inclusive)
     * @param end the end position of the text to be checked (exclusive)
     * @param locale the locale for checking the text
     * @return true if the substring of text from start to end is a correct word
     */
    protected abstract boolean isCorrect(CharSequence text, int start, int end, String locale);

    /**
     * @param text the substring of text from start to end for getting suggestions
     * @param start the start position of the text (inclusive)
     * @param end the end position of the text (exclusive)
     * @param locale the locale for getting suggestions
     * @return text with SuggestionSpan containing suggestions
     */
    protected CharSequence getSuggestions(CharSequence text, int start, int end, String locale) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(locale) || end <= start) {
            return text;
        }
        final String[] suggestions = getStringSuggestions(text, start, end, locale);
        if (suggestions == null || suggestions.length == 0) {
            return text;
        }
        final Spannable spannable;
        if (text instanceof Spannable) {
            spannable = (Spannable) text;
        } else {
            spannable = new SpannableString(text);
        }
        final int N = Math.min(SuggestionSpan.SUGGESTIONS_MAX_SIZE, suggestions.length);
        final SuggestionSpan ss = new SuggestionSpan(
                constructLocaleFromString(locale), Arrays.copyOfRange(suggestions, 0, N), 0);
        spannable.setSpan(ss, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    /**
     * Basic implementation for getting suggestions. This function is called from getSuggestions
     * and the returned strings array will be set as a SuggestionSpan.
     * If you want to set SuggestionSpan by yourself, make getStringSuggestions an empty
     * implementation and override getSuggestions.
     * @param text the substring of text from start to end for getting suggestions
     * @param start the start position of the text (inclusive)
     * @param end the end position of the text (exclusive)
     * @param locale the locale for getting suggestions
     * @return strings array for the substring of the specified text.
     */
    protected abstract String[] getStringSuggestions(
            CharSequence text, int start, int end, String locale);

    /**
     * Request to abort all tasks executed in SpellChecker
     */
    protected void cancel() {}

    @Override
    public final IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        mBinder.clearReference();
        super.onDestroy();
    }

    protected static final Locale constructLocaleFromString(String localeStr) {
        if (TextUtils.isEmpty(localeStr))
            return null;
        String[] localeParams = localeStr.split("_", 3);
        // The length of localeParams is guaranteed to always return a 1 <= value <= 3.
        if (localeParams.length == 1) {
            return new Locale(localeParams[0]);
        } else if (localeParams.length == 2) {
            return new Locale(localeParams[0], localeParams[1]);
        } else if (localeParams.length == 3) {
            return new Locale(localeParams[0], localeParams[1], localeParams[2]);
        }
        return null;
    }

    private static class SpellCheckerServiceBinder extends ISpellCheckerService.Stub {
        private SpellCheckerService mInternalService;

        public SpellCheckerServiceBinder(SpellCheckerService service) {
            mInternalService = service;
        }

        @Override
        public CharSequence getSuggestions(CharSequence text, int start, int end, String locale) {
            return mInternalService.getSuggestions(text, start, end, locale);
        }

        @Override
        public boolean isCorrect(CharSequence text, int start, int end, String locale) {
            return mInternalService.isCorrect(text, start, end, locale);
        }

        @Override
        public void cancel() {}

        private void clearReference() {
            mInternalService = null;
        }
    }
}
