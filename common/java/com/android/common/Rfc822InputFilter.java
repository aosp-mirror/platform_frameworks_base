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

package com.android.common;

import android.text.InputFilter;
import android.text.Spanned;
import android.text.SpannableStringBuilder;

/**
 * Implements special address cleanup rules:
 * The first space key entry following an "@" symbol that is followed by any combination
 * of letters and symbols, including one+ dots and zero commas, should insert an extra
 * comma (followed by the space).
 *
 * @hide
 */
public class Rfc822InputFilter implements InputFilter {

    public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
        int dstart, int dend) {

        // quick check - did they enter a single space?
        if (end-start != 1 || source.charAt(start) != ' ') {
            return null;
        }

        // determine if the characters before the new space fit the pattern
        // follow backwards and see if we find a comma, dot, or @
        int scanBack = dstart;
        boolean dotFound = false;
        while (scanBack > 0) {
            char c = dest.charAt(--scanBack);
            switch (c) {
                case '.':
                    dotFound = true;    // one or more dots are req'd
                    break;
                case ',':
                    return null;
                case '@':
                    if (!dotFound) {
                        return null;
                    }
                    // we have found a comma-insert case.  now just do it
                    // in the least expensive way we can.
                    if (source instanceof Spanned) {
                        SpannableStringBuilder sb = new SpannableStringBuilder(",");
                        sb.append(source);
                        return sb;
                    } else {
                        return ", ";
                    }
                default:
                    // just keep going
            }
        }

        // no termination cases were found, so don't edit the input
        return null;
    }
}
