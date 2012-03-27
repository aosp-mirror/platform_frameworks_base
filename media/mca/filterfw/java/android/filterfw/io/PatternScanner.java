/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.filterfw.io;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @hide
 */
public class PatternScanner {

    private String mInput;
    private Pattern mIgnorePattern;
    private int mOffset = 0;
    private int mLineNo = 0;
    private int mStartOfLine = 0;

    public PatternScanner(String input) {
        mInput = input;
    }

    public PatternScanner(String input, Pattern ignorePattern) {
        mInput = input;
        mIgnorePattern = ignorePattern;
        skip(mIgnorePattern);
    }

    public String tryEat(Pattern pattern) {
        // Skip ignore pattern
        if (mIgnorePattern != null) {
            skip(mIgnorePattern);
        }

        // Create the matcher
        Matcher matcher = pattern.matcher(mInput);
        matcher.region(mOffset, mInput.length());

        // Attempt to match
        String result = null;
        if (matcher.lookingAt()) {
            updateLineCount(mOffset, matcher.end());
            mOffset = matcher.end();
            result = mInput.substring(matcher.start(), matcher.end());
        }

        // Skip ignore pattern
        if (result != null && mIgnorePattern != null) {
            skip(mIgnorePattern);
        }

        return result;
    }

    public String eat(Pattern pattern, String tokenName) {
        String result = tryEat(pattern);
        if (result == null) {
            throw new RuntimeException(unexpectedTokenMessage(tokenName));
        }
        return result;
    }

    public boolean peek(Pattern pattern) {
        // Skip ignore pattern
        if (mIgnorePattern != null) {
            skip(mIgnorePattern);
        }

        // Create the matcher
        Matcher matcher = pattern.matcher(mInput);
        matcher.region(mOffset, mInput.length());

        // Attempt to match
        return matcher.lookingAt();
    }

    public void skip(Pattern pattern) {
        Matcher matcher = pattern.matcher(mInput);
        matcher.region(mOffset, mInput.length());
        if (matcher.lookingAt()) {
            updateLineCount(mOffset, matcher.end());
            mOffset = matcher.end();
        }
    }

    public boolean atEnd() {
        return mOffset >= mInput.length();
    }

    public int lineNo() {
        return mLineNo;
    }

    public String unexpectedTokenMessage(String tokenName) {
        String line = mInput.substring(mStartOfLine, mOffset);
        return "Unexpected token on line " + (mLineNo + 1) + " after '" + line + "' <- Expected " +
                tokenName + "!";
    }

    public void updateLineCount(int start, int end) {
        for (int i = start; i < end; ++i) {
            if (mInput.charAt(i) == '\n') {
                ++mLineNo;
                mStartOfLine = i + 1;
            }
        }
    }
}
