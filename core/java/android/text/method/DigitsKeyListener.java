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
import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;
import android.icu.text.DecimalFormatSymbols;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * For digits-only text entry
 * <p></p>
 * As for all implementations of {@link KeyListener}, this class is only concerned
 * with hardware keyboards.  Software input methods have no obligation to trigger
 * the methods in this class.
 */
public class DigitsKeyListener extends NumberKeyListener
{
    private char[] mAccepted;
    private boolean mNeedsAdvancedInput;
    private final boolean mSign;
    private final boolean mDecimal;
    private final boolean mStringMode;
    @Nullable
    private final Locale mLocale;

    private static final String DEFAULT_DECIMAL_POINT_CHARS = ".";
    private static final String DEFAULT_SIGN_CHARS = "-+";

    private static final char HYPHEN_MINUS = '-';
    // Various locales use this as minus sign
    private static final char MINUS_SIGN = '\u2212';
    // Slovenian uses this as minus sign (a bug?): http://unicode.org/cldr/trac/ticket/10050
    private static final char EN_DASH = '\u2013';

    private String mDecimalPointChars = DEFAULT_DECIMAL_POINT_CHARS;
    private String mSignChars = DEFAULT_SIGN_CHARS;

    private static final int SIGN = 1;
    private static final int DECIMAL = 2;

    @Override
    protected char[] getAcceptedChars() {
        return mAccepted;
    }

    /**
     * The characters that are used in compatibility mode.
     *
     * @see KeyEvent#getMatch
     * @see #getAcceptedChars
     */
    private static final char[][] COMPATIBILITY_CHARACTERS = {
        { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' },
        { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '+' },
        { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.' },
        { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '+', '.' },
    };

    private boolean isSignChar(final char c) {
        return mSignChars.indexOf(c) != -1;
    }

    private boolean isDecimalPointChar(final char c) {
        return mDecimalPointChars.indexOf(c) != -1;
    }

    /**
     * Allocates a DigitsKeyListener that accepts the ASCII digits 0 through 9.
     *
     * @deprecated Use {@link #DigitsKeyListener(Locale)} instead.
     */
    @Deprecated
    public DigitsKeyListener() {
        this(null, false, false);
    }

    /**
     * Allocates a DigitsKeyListener that accepts the ASCII digits 0 through 9, plus the ASCII plus
     * or minus sign (only at the beginning) and/or the ASCII period ('.') as the decimal point
     * (only one per field) if specified.
     *
     * @deprecated Use {@link #DigitsKeyListener(Locale, boolean, boolean)} instead.
     */
    @Deprecated
    public DigitsKeyListener(boolean sign, boolean decimal) {
        this(null, sign, decimal);
    }

    public DigitsKeyListener(@Nullable Locale locale) {
        this(locale, false, false);
    }

    private void setToCompat() {
        mDecimalPointChars = DEFAULT_DECIMAL_POINT_CHARS;
        mSignChars = DEFAULT_SIGN_CHARS;
        final int kind = (mSign ? SIGN : 0) | (mDecimal ? DECIMAL : 0);
        mAccepted = COMPATIBILITY_CHARACTERS[kind];
        mNeedsAdvancedInput = false;
    }

    private void calculateNeedForAdvancedInput() {
        final int kind = (mSign ? SIGN : 0) | (mDecimal ? DECIMAL : 0);
        mNeedsAdvancedInput = !ArrayUtils.containsAll(COMPATIBILITY_CHARACTERS[kind], mAccepted);
    }

    // Takes a sign string and strips off its bidi controls, if any.
    @NonNull
    private static String stripBidiControls(@NonNull String sign) {
        // For the sake of simplicity, we operate on code units, since all bidi controls are
        // in the BMP. We also expect the string to be very short (almost always 1 character), so we
        // don't need to use StringBuilder.
        String result = "";
        for (int i = 0; i < sign.length(); i++) {
            final char c = sign.charAt(i);
            if (!UCharacter.hasBinaryProperty(c, UProperty.BIDI_CONTROL)) {
                if (result.isEmpty()) {
                    result = String.valueOf(c);
                } else {
                    // This should happen very rarely, only if we have a multi-character sign,
                    // or a sign outside BMP.
                    result += c;
                }
            }
        }
        return result;
    }

    public DigitsKeyListener(@Nullable Locale locale, boolean sign, boolean decimal) {
        mSign = sign;
        mDecimal = decimal;
        mStringMode = false;
        mLocale = locale;
        if (locale == null) {
            setToCompat();
            return;
        }
        LinkedHashSet<Character> chars = new LinkedHashSet<>();
        final boolean success = NumberKeyListener.addDigits(chars, locale);
        if (!success) {
            setToCompat();
            return;
        }
        if (sign || decimal) {
            final DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
            if (sign) {
                final String minusString = stripBidiControls(symbols.getMinusSignString());
                final String plusString = stripBidiControls(symbols.getPlusSignString());
                if (minusString.length() > 1 || plusString.length() > 1) {
                    // non-BMP and multi-character signs are not supported.
                    setToCompat();
                    return;
                }
                final char minus = minusString.charAt(0);
                final char plus = plusString.charAt(0);
                chars.add(Character.valueOf(minus));
                chars.add(Character.valueOf(plus));
                mSignChars = "" + minus + plus;

                if (minus == MINUS_SIGN || minus == EN_DASH) {
                    // If the minus sign is U+2212 MINUS SIGN or U+2013 EN DASH, we also need to
                    // accept the ASCII hyphen-minus.
                    chars.add(HYPHEN_MINUS);
                    mSignChars += HYPHEN_MINUS;
                }
            }
            if (decimal) {
                final String separatorString = symbols.getDecimalSeparatorString();
                if (separatorString.length() > 1) {
                    // non-BMP and multi-character decimal separators are not supported.
                    setToCompat();
                    return;
                }
                final Character separatorChar = Character.valueOf(separatorString.charAt(0));
                chars.add(separatorChar);
                mDecimalPointChars = separatorChar.toString();
            }
        }
        mAccepted = NumberKeyListener.collectionToArray(chars);
        calculateNeedForAdvancedInput();
    }

    private DigitsKeyListener(@NonNull final String accepted) {
        mSign = false;
        mDecimal = false;
        mStringMode = true;
        mLocale = null;
        mAccepted = new char[accepted.length()];
        accepted.getChars(0, accepted.length(), mAccepted, 0);
        // Theoretically we may need advanced input, but for backward compatibility, we don't change
        // the input type.
        mNeedsAdvancedInput = false;
    }

    /**
     * Returns a DigitsKeyListener that accepts the ASCII digits 0 through 9.
     *
     * @deprecated Use {@link #getInstance(Locale)} instead.
     */
    @Deprecated
    @NonNull
    public static DigitsKeyListener getInstance() {
        return getInstance(false, false);
    }

    /**
     * Returns a DigitsKeyListener that accepts the ASCII digits 0 through 9, plus the ASCII plus
     * or minus sign (only at the beginning) and/or the ASCII period ('.') as the decimal point
     * (only one per field) if specified.
     *
     * @deprecated Use {@link #getInstance(Locale, boolean, boolean)} instead.
     */
    @Deprecated
    @NonNull
    public static DigitsKeyListener getInstance(boolean sign, boolean decimal) {
        return getInstance(null, sign, decimal);
    }

    /**
     * Returns a DigitsKeyListener that accepts the locale-appropriate digits.
     */
    @NonNull
    public static DigitsKeyListener getInstance(@Nullable Locale locale) {
        return getInstance(locale, false, false);
    }

    private static final Object sLocaleCacheLock = new Object();
    @GuardedBy("sLocaleCacheLock")
    private static final HashMap<Locale, DigitsKeyListener[]> sLocaleInstanceCache =
            new HashMap<>();

    /**
     * Returns a DigitsKeyListener that accepts the locale-appropriate digits, plus the
     * locale-appropriate plus or minus sign (only at the beginning) and/or the locale-appropriate
     * decimal separator (only one per field) if specified.
     */
    @NonNull
    public static DigitsKeyListener getInstance(
            @Nullable Locale locale, boolean sign, boolean decimal) {
        final int kind = (sign ? SIGN : 0) | (decimal ? DECIMAL : 0);
        synchronized (sLocaleCacheLock) {
            DigitsKeyListener[] cachedValue = sLocaleInstanceCache.get(locale);
            if (cachedValue != null && cachedValue[kind] != null) {
                return cachedValue[kind];
            }
            if (cachedValue == null) {
                cachedValue = new DigitsKeyListener[4];
                sLocaleInstanceCache.put(locale, cachedValue);
            }
            return cachedValue[kind] = new DigitsKeyListener(locale, sign, decimal);
        }
    }

    private static final Object sStringCacheLock = new Object();
    @GuardedBy("sStringCacheLock")
    private static final HashMap<String, DigitsKeyListener> sStringInstanceCache = new HashMap<>();

    /**
     * Returns a DigitsKeyListener that accepts only the characters
     * that appear in the specified String.  Note that not all characters
     * may be available on every keyboard.
     */
    @NonNull
    public static DigitsKeyListener getInstance(@NonNull String accepted) {
        DigitsKeyListener result;
        synchronized (sStringCacheLock) {
            result = sStringInstanceCache.get(accepted);
            if (result == null) {
                result = new DigitsKeyListener(accepted);
                sStringInstanceCache.put(accepted, result);
            }
        }
        return result;
    }

    /**
     * Returns a DigitsKeyListener based on an the settings of a existing DigitsKeyListener, with
     * the locale modified.
     *
     * @hide
     */
    @NonNull
    public static DigitsKeyListener getInstance(
            @Nullable Locale locale,
            @NonNull DigitsKeyListener listener) {
        if (listener.mStringMode) {
            return listener; // string-mode DigitsKeyListeners have no locale.
        } else {
            return getInstance(locale, listener.mSign, listener.mDecimal);
        }
    }

    /**
     * Returns the input type for the listener.
     */
    public int getInputType() {
        int contentType;
        if (mNeedsAdvancedInput) {
            contentType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        } else {
            contentType = InputType.TYPE_CLASS_NUMBER;
            if (mSign) {
                contentType |= InputType.TYPE_NUMBER_FLAG_SIGNED;
            }
            if (mDecimal) {
                contentType |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
            }
        }
        return contentType;
    }

    @Override
    public CharSequence filter(CharSequence source, int start, int end,
                               Spanned dest, int dstart, int dend) {
        CharSequence out = super.filter(source, start, end, dest, dstart, dend);

        if (mSign == false && mDecimal == false) {
            return out;
        }

        if (out != null) {
            source = out;
            start = 0;
            end = out.length();
        }

        int sign = -1;
        int decimal = -1;
        int dlen = dest.length();

        /*
         * Find out if the existing text has a sign or decimal point characters.
         */

        for (int i = 0; i < dstart; i++) {
            char c = dest.charAt(i);

            if (isSignChar(c)) {
                sign = i;
            } else if (isDecimalPointChar(c)) {
                decimal = i;
            }
        }
        for (int i = dend; i < dlen; i++) {
            char c = dest.charAt(i);

            if (isSignChar(c)) {
                return "";    // Nothing can be inserted in front of a sign character.
            } else if (isDecimalPointChar(c)) {
                decimal = i;
            }
        }

        /*
         * If it does, we must strip them out from the source.
         * In addition, a sign character must be the very first character,
         * and nothing can be inserted before an existing sign character.
         * Go in reverse order so the offsets are stable.
         */

        SpannableStringBuilder stripped = null;

        for (int i = end - 1; i >= start; i--) {
            char c = source.charAt(i);
            boolean strip = false;

            if (isSignChar(c)) {
                if (i != start || dstart != 0) {
                    strip = true;
                } else if (sign >= 0) {
                    strip = true;
                } else {
                    sign = i;
                }
            } else if (isDecimalPointChar(c)) {
                if (decimal >= 0) {
                    strip = true;
                } else {
                    decimal = i;
                }
            }

            if (strip) {
                if (end == start + 1) {
                    return "";  // Only one character, and it was stripped.
                }

                if (stripped == null) {
                    stripped = new SpannableStringBuilder(source, start, end);
                }

                stripped.delete(i - start, i + 1 - start);
            }
        }

        if (stripped != null) {
            return stripped;
        } else if (out != null) {
            return out;
        } else {
            return null;
        }
    }
}
