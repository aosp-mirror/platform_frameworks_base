/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.utilities;

import java.util.Arrays;

/**
 * Utilities for string manipulation
 */
public class StringUtils {
    /**
     * Converts a float into a string.
     * Providing a defined number of characters before and after the
     * decimal point.
     *
     * @param value              The value to convert to string
     * @param beforeDecimalPoint digits before the decimal point
     * @param afterDecimalPoint  digits after the decimal point
     * @param pre                character to pad width 0 = no pad typically ' ' or '0'
     * @param post               character to pad width 0 = no pad typically ' ' or '0'
     * @return
     */
    public static String floatToString(float value,
                                       int beforeDecimalPoint,
                                       int afterDecimalPoint,
                                       char pre, char post) {

        int integerPart = (int) value;
        float fractionalPart = value % 1;

        // Convert integer part to string and pad with spaces
        String integerPartString = String.valueOf(integerPart);
        int iLen = integerPartString.length();
        if (iLen < beforeDecimalPoint) {
            int spacesToPad = beforeDecimalPoint - iLen;
            if (pre != 0) {
                char[] pad = new char[spacesToPad];
                Arrays.fill(pad, pre);
                integerPartString = new String(pad) + integerPartString;
            }


        } else if (iLen > beforeDecimalPoint) {
            integerPartString = integerPartString.substring(iLen - beforeDecimalPoint);
        }
        if (afterDecimalPoint == 0) {
            return integerPartString;
        }
        // Convert fractional part to string and pad with zeros

        for (int i = 0; i < afterDecimalPoint; i++) {
            fractionalPart *= 10;
        }

        fractionalPart = Math.round(fractionalPart);

        for (int i = 0; i < afterDecimalPoint; i++) {
            fractionalPart *= .1;
        }

        String fact = Float.toString(fractionalPart);
        fact = fact.substring(2, Math.min(fact.length(), afterDecimalPoint + 2));
        int trim = fact.length();
        for (int i = fact.length() - 1; i >= 0; i--) {
            if (fact.charAt(i) != '0') {
                break;
            }
            trim--;
        }
        if (trim != fact.length()) {
            fact = fact.substring(0, trim);
        }
        int len = fact.length();
        if (post != 0 && len < afterDecimalPoint) {
            char[] c = new char[afterDecimalPoint - len];
            Arrays.fill(c, post);
            fact = fact + new String(c);
        }

        return integerPartString + "." + fact;
    }

}
