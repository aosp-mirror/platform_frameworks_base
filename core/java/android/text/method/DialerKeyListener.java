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
import android.view.KeyCharacterMap.KeyData;
import android.text.InputType;
import android.text.Spannable;

/**
 * For dialing-only text entry
 * <p></p>
 * As for all implementations of {@link KeyListener}, this class is only concerned
 * with hardware keyboards.  Software input methods have no obligation to trigger
 * the methods in this class.
 */
public class DialerKeyListener extends NumberKeyListener
{
    @Override
    protected char[] getAcceptedChars()
    {
        return CHARACTERS;
    }

    public static DialerKeyListener getInstance() {
        if (sInstance != null)
            return sInstance;

        sInstance = new DialerKeyListener();
        return sInstance;
    }

    public int getInputType() {
        return InputType.TYPE_CLASS_PHONE;
    }
    
    /**
     * Overrides the superclass's lookup method to prefer the number field
     * from the KeyEvent.
     */
    protected int lookup(KeyEvent event, Spannable content) {
        int meta = event.getMetaState() | getMetaState(content);
        int number = event.getNumber();

        /*
         * Prefer number if no meta key is active, or if it produces something
         * valid and the meta lookup does not.
         */
        if ((meta & (MetaKeyKeyListener.META_ALT_ON | MetaKeyKeyListener.META_SHIFT_ON)) == 0) {
            if (number != 0) {
                return number;
            }
        }

        int match = super.lookup(event, content);

        if (match != 0) {
            return match;
        } else {
            /*
             * If a meta key is active but the lookup with the meta key
             * did not produce anything, try some other meta keys, because
             * the user might have pressed SHIFT when they meant ALT,
             * or vice versa.
             */

            if (meta != 0) {
                KeyData kd = new KeyData();
                char[] accepted = getAcceptedChars();

                if (event.getKeyData(kd)) {
                    for (int i = 1; i < kd.meta.length; i++) {
                        if (ok(accepted, kd.meta[i])) {
                            return kd.meta[i];
                        }
                    }
                }
            }

            /*
             * Otherwise, use the number associated with the key, since
             * whatever they wanted to do with the meta key does not
             * seem to be valid here.
             */

            return number;
        }
    }


    /**
     * The characters that are used.
     *
     * @see KeyEvent#getMatch
     * @see #getAcceptedChars
     */
    public static final char[] CHARACTERS = new char[] {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '#', '*',
            '+', '-', '(', ')', ',', '/', 'N', '.', ' ', ';'
        };

    private static DialerKeyListener sInstance;
}
