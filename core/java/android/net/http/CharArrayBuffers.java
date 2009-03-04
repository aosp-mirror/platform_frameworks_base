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

package android.net.http;

import org.apache.http.util.CharArrayBuffer;
import org.apache.http.protocol.HTTP;

/**
 * Utility methods for working on CharArrayBuffers.
 * 
 * {@hide}
 */
class CharArrayBuffers {

    static final char uppercaseAddon = 'a' - 'A';

    /**
     * Returns true if the buffer contains the given string. Ignores leading
     * whitespace and case.
     *
     * @param buffer to search
     * @param beginIndex index at which we should start
     * @param str to search for
     */
    static boolean containsIgnoreCaseTrimmed(CharArrayBuffer buffer,
            int beginIndex, final String str) {
        int len = buffer.length();
        char[] chars = buffer.buffer();
        while (beginIndex < len && HTTP.isWhitespace(chars[beginIndex])) {
            beginIndex++;
        }
        int size = str.length();
        boolean ok = len >= beginIndex + size;
        for (int j=0; ok && (j<size); j++) {
            char a = chars[beginIndex+j];
            char b = str.charAt(j);
            if (a != b) {
                a = toLower(a);
                b = toLower(b);
                ok = a == b;
            }
        }
        return ok;
    }

    /**
     * Returns index of first occurence ch. Lower cases characters leading up
     * to first occurrence of ch.
     */
    static int setLowercaseIndexOf(CharArrayBuffer buffer, final int ch) {

        int beginIndex = 0;
        int endIndex = buffer.length();
        char[] chars = buffer.buffer();

        for (int i = beginIndex; i < endIndex; i++) {
            char current = chars[i];
            if (current == ch) {
                return i;
            } else if (current >= 'A' && current <= 'Z'){
                // make lower case
                current += uppercaseAddon;
                chars[i] = current;
            }
        }
        return -1;
    }

    private static char toLower(char c) {
        if (c >= 'A' && c <= 'Z'){
            c += uppercaseAddon;
        }
        return c;
    }
}
