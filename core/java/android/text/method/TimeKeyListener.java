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

import android.view.KeyEvent;
import android.text.InputType;

/**
 * For entering times in a text field.
 */
public class TimeKeyListener extends NumberKeyListener
{
    public int getInputType() {
        return InputType.TYPE_CLASS_DATETIME
        | InputType.TYPE_DATETIME_VARIATION_TIME;
    }
    
    @Override
    protected char[] getAcceptedChars()
    {
        return CHARACTERS;
    }

    public static TimeKeyListener getInstance() {
        if (sInstance != null)
            return sInstance;

        sInstance = new TimeKeyListener();
        return sInstance;
    }

    /**
     * The characters that are used.
     *
     * @see KeyEvent#getMatch
     * @see #getAcceptedChars
     */
    public static final char[] CHARACTERS = new char[] {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'm',
            'p', ':'
        };

    private static TimeKeyListener sInstance;
}
