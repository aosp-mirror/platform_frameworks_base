/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.telephony;

import com.android.i18n.phonenumbers.AsYouTypeFormatter;
import com.android.i18n.phonenumbers.PhoneNumberUtil;

import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.Selection;
import android.text.TextWatcher;

import java.util.Locale;

/**
 * Watches a {@link android.widget.TextView} and if a phone number is entered
 * will format it.
 * <p>
 * Stop formatting when the user
 * <ul>
 * <li>Inputs non-dialable characters</li>
 * <li>Removes the separator in the middle of string.</li>
 * </ul>
 * <p>
 * The formatting will be restarted once the text is cleared.
 */
public class PhoneNumberFormattingTextWatcher implements TextWatcher {
    /**
     * One or more characters were removed from the end.
     */
    private final static int STATE_REMOVE_LAST = 0;

    /**
     * One or more characters were appended.
     */
    private final static int STATE_APPEND = 1;

    /**
     * One or more digits were changed in the beginning or the middle of text.
     */
    private final static int STATE_MODIFY_DIGITS = 2;

    /**
     * The changes other than the above.
     */
    private final static int STATE_OTHER = 3;

    /**
     * The state of this change could be one value of the above
     */
    private int mState;

    /**
     * Indicates the change was caused by ourselves.
     */
    private boolean mSelfChange = false;

    /**
     * Indicates the formatting has been stopped.
     */
    private boolean mStopFormatting;

    private AsYouTypeFormatter mFormatter;

    /**
     * The formatting is based on the current system locale and future locale changes
     * may not take effect on this instance.
     */
    public PhoneNumberFormattingTextWatcher() {
        this(Locale.getDefault().getCountry());
    }

    /**
     * The formatting is based on the given <code>countryCode</code>.
     *
     * @param countryCode the ISO 3166-1 two-letter country code that indicates the country/region
     * where the phone number is being entered.
     *
     * @hide
     */
    public PhoneNumberFormattingTextWatcher(String countryCode) {
        if (countryCode == null) throw new IllegalArgumentException();
        mFormatter = PhoneNumberUtil.getInstance().getAsYouTypeFormatter(countryCode);
    }

    public void beforeTextChanged(CharSequence s, int start, int count,
            int after) {
        if (mSelfChange || mStopFormatting) {
            return;
        }
        if (count == 0 && s.length() == start) {
            // Append one or more new chars
            mState = STATE_APPEND;
        } else if (after == 0 && start + count == s.length() && count > 0) {
            // Remove one or more chars from the end of string.
            mState = STATE_REMOVE_LAST;
        } else if (count > 0 && !hasSeparator(s, start, count)) {
            // Remove the dialable chars in the begin or middle of text.
            mState = STATE_MODIFY_DIGITS;
        } else {
            mState = STATE_OTHER;
        }
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (mSelfChange || mStopFormatting) {
            return;
        }
        if (mState == STATE_OTHER) {
            if (count > 0 && !hasSeparator(s, start, count)) {
                // User inserted the dialable characters in the middle of text.
                mState = STATE_MODIFY_DIGITS;
            }
        }
        // Check whether we should stop formatting.
        if (mState == STATE_APPEND && count > 0 && hasSeparator(s, start, count)) {
            // User appended the non-dialable character, stop formatting.
            stopFormatting();
        } else if (mState == STATE_OTHER) {
            // User must insert or remove the non-dialable characters in the begin or middle of
            // number, stop formatting.
            stopFormatting();
        }
    }

    public synchronized void afterTextChanged(Editable s) {
        if (mStopFormatting) {
            // Restart the formatting when all texts were clear.
            mStopFormatting = !(s.length() == 0);
            return;
        }
        if (mSelfChange) {
            // Ignore the change caused by s.replace().
            return;
        }
        String formatted = reformat(s, Selection.getSelectionEnd(s));
        if (formatted != null) {
            int rememberedPos = mFormatter.getRememberedPosition();
            mSelfChange = true;
            s.replace(0, s.length(), formatted, 0, formatted.length());
            // The text could be changed by other TextWatcher after we changed it. If we found the
            // text is not the one we were expecting, just give up calling setSelection().
            if (formatted.equals(s.toString())) {
                Selection.setSelection(s, rememberedPos);
            }
            mSelfChange = false;
        }
    }

    /**
     * Generate the formatted number by ignoring all non-dialable chars and stick the cursor to the
     * nearest dialable char to the left. For instance, if the number is  (650) 123-45678 and '4' is
     * removed then the cursor should be behind '3' instead of '-'.
     */
    private String reformat(CharSequence s, int cursor) {
        // The index of char to the leftward of the cursor.
        int curIndex = cursor - 1;
        String formatted = null;
        mFormatter.clear();
        char lastNonSeparator = 0;
        boolean hasCursor = false;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (PhoneNumberUtils.isNonSeparator(c)) {
                if (lastNonSeparator != 0) {
                    formatted = getFormattedNumber(lastNonSeparator, hasCursor);
                    hasCursor = false;
                }
                lastNonSeparator = c;
            }
            if (i == curIndex) {
                hasCursor = true;
            }
        }
        if (lastNonSeparator != 0) {
            formatted = getFormattedNumber(lastNonSeparator, hasCursor);
        }
        return formatted;
    }

    private String getFormattedNumber(char lastNonSeparator, boolean hasCursor) {
        return hasCursor ? mFormatter.inputDigitAndRememberPosition(lastNonSeparator)
                : mFormatter.inputDigit(lastNonSeparator);
    }

    private void stopFormatting() {
        mStopFormatting = true;
        mFormatter.clear();
    }

    private boolean hasSeparator(final CharSequence s, final int start, final int count) {
        for (int i = start; i < start + count; i++) {
            char c = s.charAt(i);
            if (!PhoneNumberUtils.isNonSeparator(c)) {
                return true;
            }
        }
        return false;
    }
}
