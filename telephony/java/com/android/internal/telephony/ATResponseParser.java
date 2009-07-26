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

package com.android.internal.telephony;

/**
 * {@hide}
 */
public class ATResponseParser
{
    /*************************** Instance Variables **************************/

    private String line;
    private int next = 0;
    private int tokStart, tokEnd;

    /***************************** Class Methods *****************************/

    public
    ATResponseParser (String line)
    {
        this.line = line;
    }

    public boolean
    nextBoolean()
    {
        // "\s*(\d)(,|$)"
        // \d is '0' or '1'

        nextTok();

        if (tokEnd - tokStart > 1) {
            throw new ATParseEx();
        }
        char c = line.charAt(tokStart);

        if (c == '0') return false;
        if (c ==  '1') return true;
        throw new ATParseEx();
    }


    /** positive int only */
    public int
    nextInt()
    {
        // "\s*(\d+)(,|$)"
        int ret = 0;

        nextTok();

        for (int i = tokStart ; i < tokEnd ; i++) {
            char c = line.charAt(i);

            // Yes, ASCII decimal digits only
            if (c < '0' || c > '9') {
                throw new ATParseEx();
            }

            ret *= 10;
            ret += c - '0';
        }

        return ret;
    }

    public String
    nextString()
    {
        nextTok();

        return line.substring(tokStart, tokEnd);
    }

    public boolean
    hasMore()
    {
        return next < line.length();
    }

    private void
    nextTok()
    {
        int len = line.length();

        if (next == 0) {
            skipPrefix();
        }

        if (next >= len) {
            throw new ATParseEx();
        }

        try {
            // \s*("([^"]*)"|(.*)\s*)(,|$)

            char c = line.charAt(next++);
            boolean hasQuote = false;

            c = skipWhiteSpace(c);

            if (c == '"') {
                if (next >= len) {
                    throw new ATParseEx();
                }
                c = line.charAt(next++);
                tokStart = next - 1;
                while (c != '"' && next < len) {
                    c = line.charAt(next++);
                }
                if (c != '"') {
                    throw new ATParseEx();
                }
                tokEnd = next - 1;
                if (next < len && line.charAt(next++) != ',') {
                    throw new ATParseEx();
                }
            } else {
                tokStart = next - 1;
                tokEnd = tokStart;
                while (c != ',') {
                    if (!Character.isWhitespace(c)) {
                        tokEnd = next;
                    }
                    if (next == len) {
                        break;
                    }
                    c = line.charAt(next++);
                }
            }
        } catch (StringIndexOutOfBoundsException ex) {
            throw new ATParseEx();
        }
    }


    /** Throws ATParseEx if whitespace extends to the end of string */
    private char
    skipWhiteSpace (char c)
    {
        int len;
        len = line.length();
        while (next < len && Character.isWhitespace(c)) {
            c = line.charAt(next++);
        }

        if (Character.isWhitespace(c)) {
            throw new ATParseEx();
        }
        return c;
    }


    private void
    skipPrefix()
    {
        // consume "^[^:]:"

        next = 0;
        int s = line.length();
        while (next < s){
            char c = line.charAt(next++);

            if (c == ':') {
                return;
            }
        }

        throw new ATParseEx("missing prefix");
    }

}
