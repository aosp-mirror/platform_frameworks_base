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
import android.text.InputType;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * For entering dates and times in the same text field.
 * <p></p>
 * As for all implementations of {@link KeyListener}, this class is only concerned
 * with hardware keyboards.  Software input methods have no obligation to trigger
 * the methods in this class.
 */
public class DateTimeKeyListener extends NumberKeyListener
{
    public int getInputType() {
        if (mNeedsAdvancedInput) {
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        } else {
            return InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_NORMAL;
        }
    }

    @Override
    @NonNull
    protected char[] getAcceptedChars()
    {
        return mCharacters;
    }

    /**
     * @deprecated Use {@link #DateTimeKeyListener(Locale)} instead.
     */
    @Deprecated
    public DateTimeKeyListener() {
        this(null);
    }

    private static final String SYMBOLS_TO_IGNORE = "yMLdahHKkms";
    private static final String SKELETON_12HOUR = "yMdhms";
    private static final String SKELETON_24HOUR = "yMdHms";

    public DateTimeKeyListener(@Nullable Locale locale) {
        final LinkedHashSet<Character> chars = new LinkedHashSet<>();
        // First add the digits. Then, add all the character in AM and PM markers. Finally, add all
        // the non-pattern characters seen in the patterns for "yMdhms" and "yMdHms".
        final boolean success = NumberKeyListener.addDigits(chars, locale)
                          && NumberKeyListener.addAmPmChars(chars, locale)
                          && NumberKeyListener.addFormatCharsFromSkeleton(
                              chars, locale, SKELETON_12HOUR, SYMBOLS_TO_IGNORE)
                          && NumberKeyListener.addFormatCharsFromSkeleton(
                              chars, locale, SKELETON_24HOUR, SYMBOLS_TO_IGNORE);
        if (success) {
            mCharacters = NumberKeyListener.collectionToArray(chars);
            if (locale != null && "en".equals(locale.getLanguage())) {
                // For backward compatibility reasons, assume we don't need advanced input for
                // English locales, although English locales literally also need a comma and perhaps
                // uppercase letters for AM and PM.
                mNeedsAdvancedInput = false;
            } else {
                mNeedsAdvancedInput = !ArrayUtils.containsAll(CHARACTERS, mCharacters);
            }
        } else {
            mCharacters = CHARACTERS;
            mNeedsAdvancedInput = false;
        }
    }

    /**
     * @deprecated Use {@link #getInstance(Locale)} instead.
     */
    @Deprecated
    @NonNull
    public static DateTimeKeyListener getInstance() {
        return getInstance(null);
    }

    /**
     * Returns an instance of DateTimeKeyListener appropriate for the given locale.
     */
    @NonNull
    public static DateTimeKeyListener getInstance(@Nullable Locale locale) {
        DateTimeKeyListener instance;
        synchronized (sLock) {
            instance = sInstanceCache.get(locale);
            if (instance == null) {
                instance = new DateTimeKeyListener(locale);
                sInstanceCache.put(locale, instance);
            }
        }
        return instance;
    }

    /**
     * This field used to list the characters that were used. But is now a fixed data
     * field that is the list of code units used for the deprecated case where the class
     * is instantiated with null or no input parameter.
     *
     * @see KeyEvent#getMatch
     * @see #getAcceptedChars
     *
     * @deprecated Use {@link #getAcceptedChars()} instead.
     */
    public static final char[] CHARACTERS = new char[] {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'm',
            'p', ':', '/', '-', ' '
        };

    private final char[] mCharacters;
    private final boolean mNeedsAdvancedInput;

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static final HashMap<Locale, DateTimeKeyListener> sInstanceCache = new HashMap<>();
}
