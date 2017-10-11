/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text.method;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.icu.text.DecimalFormatSymbols;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;

import libcore.icu.LocaleData;

import java.util.Collection;
import java.util.Locale;

/**
 * For numeric text entry
 * <p></p>
 * As for all implementations of {@link KeyListener}, this class is only concerned
 * with hardware keyboards.  Software input methods have no obligation to trigger
 * the methods in this class.
 */
public abstract class NumberKeyListener extends BaseKeyListener
    implements InputFilter
{
    /**
     * You can say which characters you can accept.
     */
    @NonNull
    protected abstract char[] getAcceptedChars();

    protected int lookup(KeyEvent event, Spannable content) {
        return event.getMatch(getAcceptedChars(), getMetaState(content, event));
    }

    public CharSequence filter(CharSequence source, int start, int end,
                               Spanned dest, int dstart, int dend) {
        char[] accept = getAcceptedChars();
        boolean filter = false;

        int i;
        for (i = start; i < end; i++) {
            if (!ok(accept, source.charAt(i))) {
                break;
            }
        }

        if (i == end) {
            // It was all OK.
            return null;
        }

        if (end - start == 1) {
            // It was not OK, and there is only one char, so nothing remains.
            return "";
        }

        SpannableStringBuilder filtered =
            new SpannableStringBuilder(source, start, end);
        i -= start;
        end -= start;

        int len = end - start;
        // Only count down to i because the chars before that were all OK.
        for (int j = end - 1; j >= i; j--) {
            if (!ok(accept, source.charAt(j))) {
                filtered.delete(j, j + 1);
            }
        }

        return filtered;
    }

    protected static boolean ok(char[] accept, char c) {
        for (int i = accept.length - 1; i >= 0; i--) {
            if (accept[i] == c) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean onKeyDown(View view, Editable content,
                             int keyCode, KeyEvent event) {
        int selStart, selEnd;

        {
            int a = Selection.getSelectionStart(content);
            int b = Selection.getSelectionEnd(content);

            selStart = Math.min(a, b);
            selEnd = Math.max(a, b);
        }

        if (selStart < 0 || selEnd < 0) {
            selStart = selEnd = 0;
            Selection.setSelection(content, 0);
        }

        int i = event != null ? lookup(event, content) : 0;
        int repeatCount = event != null ? event.getRepeatCount() : 0;
        if (repeatCount == 0) {
            if (i != 0) {
                if (selStart != selEnd) {
                    Selection.setSelection(content, selEnd);
                }

                content.replace(selStart, selEnd, String.valueOf((char) i));

                adjustMetaAfterKeypress(content);
                return true;
            }
        } else if (i == '0' && repeatCount == 1) {
            // Pretty hackish, it replaces the 0 with the +

            if (selStart == selEnd && selEnd > 0 &&
                    content.charAt(selStart - 1) == '0') {
                content.replace(selStart - 1, selEnd, String.valueOf('+'));
                adjustMetaAfterKeypress(content);
                return true;
            }
        }

        adjustMetaAfterKeypress(content);
        return super.onKeyDown(view, content, keyCode, event);
    }

    /* package */
    @Nullable
    static boolean addDigits(@NonNull Collection<Character> collection, @Nullable Locale locale) {
        if (locale == null) {
            return false;
        }
        final String[] digits = DecimalFormatSymbols.getInstance(locale).getDigitStrings();
        for (int i = 0; i < 10; i++) {
            if (digits[i].length() > 1) { // multi-codeunit digits. Not supported.
                return false;
            }
            collection.add(Character.valueOf(digits[i].charAt(0)));
        }
        return true;
    }

    // From http://unicode.org/reports/tr35/tr35-dates.html#Date_Format_Patterns
    private static final String DATE_TIME_FORMAT_SYMBOLS =
            "GyYuUrQqMLlwWdDFgEecabBhHKkjJCmsSAzZOvVXx";
    private static final char SINGLE_QUOTE = '\'';

    /* package */
    static boolean addFormatCharsFromSkeleton(
            @NonNull Collection<Character> collection, @Nullable Locale locale,
            @NonNull String skeleton, @NonNull String symbolsToIgnore) {
        if (locale == null) {
            return false;
        }
        final String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        boolean outsideQuotes = true;
        for (int i = 0; i < pattern.length(); i++) {
            final char ch = pattern.charAt(i);
            if (Character.isSurrogate(ch)) { // characters outside BMP are not supported.
                return false;
            } else if (ch == SINGLE_QUOTE) {
                outsideQuotes = !outsideQuotes;
                // Single quote characters should be considered if and only if they follow
                // another single quote.
                if (i == 0 || pattern.charAt(i - 1) != SINGLE_QUOTE) {
                    continue;
                }
            }

            if (outsideQuotes) {
                if (symbolsToIgnore.indexOf(ch) != -1) {
                    // Skip expected pattern characters.
                    continue;
                } else if (DATE_TIME_FORMAT_SYMBOLS.indexOf(ch) != -1) {
                    // An unexpected symbols is seen. We've failed.
                    return false;
                }
            }
            // If we are here, we are either inside quotes, or we have seen a non-pattern
            // character outside quotes. So ch is a valid character in a date.
            collection.add(Character.valueOf(ch));
        }
        return true;
    }

    /* package */
    static boolean addFormatCharsFromSkeletons(
            @NonNull Collection<Character> collection, @Nullable Locale locale,
            @NonNull String[] skeletons, @NonNull String symbolsToIgnore) {
        for (int i = 0; i < skeletons.length; i++) {
            final boolean success = addFormatCharsFromSkeleton(
                    collection, locale, skeletons[i], symbolsToIgnore);
            if (!success) {
                return false;
            }
        }
        return true;
    }


    /* package */
    static boolean addAmPmChars(@NonNull Collection<Character> collection,
                                @Nullable Locale locale) {
        if (locale == null) {
            return false;
        }
        final String[] amPm = LocaleData.get(locale).amPm;
        for (int i = 0; i < amPm.length; i++) {
            for (int j = 0; j < amPm[i].length(); j++) {
                final char ch = amPm[i].charAt(j);
                if (Character.isBmpCodePoint(ch)) {
                    collection.add(Character.valueOf(ch));
                } else {  // We don't support non-BMP characters.
                    return false;
                }
            }
        }
        return true;
    }

    /* package */
    @NonNull
    static char[] collectionToArray(@NonNull Collection<Character> chars) {
        final char[] result = new char[chars.size()];
        int i = 0;
        for (Character ch : chars) {
            result[i++] = ch;
        }
        return result;
    }
}
