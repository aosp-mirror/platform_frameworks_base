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
 * For entering dates in a text field.
 * <p></p>
 * As for all implementations of {@link KeyListener}, this class is only concerned
 * with hardware keyboards.  Software input methods have no obligation to trigger
 * the methods in this class.
 */
public class DateKeyListener extends NumberKeyListener
{
    public int getInputType() {
        if (mNeedsAdvancedInput) {
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        } else {
            return InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE;
        }
    }

    @Override
    @NonNull
    protected char[] getAcceptedChars() {
        return mCharacters;
    }

    /**
     * @deprecated Use {@link #DateKeyListener(Locale)} instead.
     */
    @Deprecated
    public DateKeyListener() {
        this(null);
    }

    private static final String SYMBOLS_TO_IGNORE = "yMLd";
    private static final String[] SKELETONS = {"yMd", "yM", "Md"};

    public DateKeyListener(@Nullable Locale locale) {
        final LinkedHashSet<Character> chars = new LinkedHashSet<>();
        // First add the digits, then add all the non-pattern characters seen in the pattern for
        // "yMd", which is supposed to only have numerical fields.
        final boolean success = NumberKeyListener.addDigits(chars, locale)
                                && NumberKeyListener.addFormatCharsFromSkeletons(
                                        chars, locale, SKELETONS, SYMBOLS_TO_IGNORE);
        if (success) {
            mCharacters = NumberKeyListener.collectionToArray(chars);
            mNeedsAdvancedInput = !ArrayUtils.containsAll(CHARACTERS, mCharacters);
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
    public static DateKeyListener getInstance() {
        return getInstance(null);
    }

    /**
     * Returns an instance of DateKeyListener appropriate for the given locale.
     */
    @NonNull
    public static DateKeyListener getInstance(@Nullable Locale locale) {
        DateKeyListener instance;
        synchronized (sLock) {
            instance = sInstanceCache.get(locale);
            if (instance == null) {
                instance = new DateKeyListener(locale);
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
    @Deprecated
    public static final char[] CHARACTERS = new char[] {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '/', '-', '.'
        };

    private final char[] mCharacters;
    private final boolean mNeedsAdvancedInput;

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static final HashMap<Locale, DateKeyListener> sInstanceCache = new HashMap<>();
}
