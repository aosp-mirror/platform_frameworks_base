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
 * For entering times in a text field.
 * <p></p>
 * As for all implementations of {@link KeyListener}, this class is only concerned
 * with hardware keyboards.  Software input methods have no obligation to trigger
 * the methods in this class.
 */
public class TimeKeyListener extends NumberKeyListener
{
    public int getInputType() {
        if (mNeedsAdvancedInput) {
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        } else {
            return InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME;
        }
    }

    @Override
    @NonNull
    protected char[] getAcceptedChars()
    {
        return mCharacters;
    }

    /**
     * @deprecated Use {@link #TimeKeyListener(Locale)} instead.
     */
    @Deprecated
    public TimeKeyListener() {
        this(null);
    }

    private static final String SYMBOLS_TO_IGNORE = "ahHKkms";
    private static final String SKELETON_12HOUR = "hms";
    private static final String SKELETON_24HOUR = "Hms";

    public TimeKeyListener(@Nullable Locale locale) {
        final LinkedHashSet<Character> chars = new LinkedHashSet<>();
        // First add the digits. Then, add all the character in AM and PM markers. Finally, add all
        // the non-pattern characters seen in the patterns for "hms" and "Hms".
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
                // English locales, although English locales may need uppercase letters for
                // AM and PM.
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
    public static TimeKeyListener getInstance() {
        return getInstance(null);
    }

    /**
     * Returns an instance of TimeKeyListener appropriate for the given locale.
     */
    @NonNull
    public static TimeKeyListener getInstance(@Nullable Locale locale) {
        TimeKeyListener instance;
        synchronized (sLock) {
            instance = sInstanceCache.get(locale);
            if (instance == null) {
                instance = new TimeKeyListener(locale);
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
            'p', ':'
        };

    private final char[] mCharacters;
    private final boolean mNeedsAdvancedInput;

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static final HashMap<Locale, TimeKeyListener> sInstanceCache = new HashMap<>();
}
