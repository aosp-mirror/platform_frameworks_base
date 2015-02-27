/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.internal.alsa;

/**
 * @hide
 * Breaks lines in an ALSA "cards" or "devices" file into tokens.
 * TODO(pmclean) Look into replacing this with String.split().
 */
public class LineTokenizer {
    public static final int kTokenNotFound = -1;

    private String mDelimiters = "";

    public LineTokenizer(String delimiters) {
        mDelimiters = delimiters;
    }

    int nextToken(String line, int startIndex) {
        int len = line.length();
        int offset = startIndex;
        for (; offset < len; offset++) {
            if (mDelimiters.indexOf(line.charAt(offset)) == -1) {
                // past a delimiter
                break;
            }
      }

      return offset < len ? offset : kTokenNotFound;
    }

    int nextDelimiter(String line, int startIndex) {
        int len = line.length();
        int offset = startIndex;
        for (; offset < len; offset++) {
            if (mDelimiters.indexOf(line.charAt(offset)) != -1) {
                // past a delimiter
                break;
            }
        }

      return offset < len ? offset : kTokenNotFound;
    }
}
