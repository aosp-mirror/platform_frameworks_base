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

package android.os;

/**
 * A simple pattern matcher, which is safe to use on untrusted data: it does
 * not provide full reg-exp support, only simple globbing that can not be
 * used maliciously.
 */
public class PatternMatcher implements Parcelable {
    /**
     * Pattern type: the given pattern must exactly match the string it is
     * tested against.
     */
    public static final int PATTERN_LITERAL = 0;
    
    /**
     * Pattern type: the given pattern must match the
     * beginning of the string it is tested against.
     */
    public static final int PATTERN_PREFIX = 1;
    
    /**
     * Pattern type: the given pattern is interpreted with a
     * simple glob syntax for matching against the string it is tested against.
     * In this syntax, you can use the '*' character to match against zero or
     * more occurrences of the character immediately before.  If the
     * character before it is '.' it will match any character.  The character
     * '\' can be used as an escape.  This essentially provides only the '*'
     * wildcard part of a normal regexp. 
     */
    public static final int PATTERN_SIMPLE_GLOB = 2;
    
    private final String mPattern;
    private final int mType;
    
    public PatternMatcher(String pattern, int type) {
        mPattern = pattern;
        mType = type;
    }

    public final String getPath() {
        return mPattern;
    }
    
    public final int getType() {
        return mType;
    }
    
    public boolean match(String str) {
        return matchPattern(mPattern, str, mType);
    }

    public String toString() {
        String type = "? ";
        switch (mType) {
            case PATTERN_LITERAL:
                type = "LITERAL: ";
                break;
            case PATTERN_PREFIX:
                type = "PREFIX: ";
                break;
            case PATTERN_SIMPLE_GLOB:
                type = "GLOB: ";
                break;
        }
        return "PatternMatcher{" + type + mPattern + "}";
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPattern);
        dest.writeInt(mType);
    }
    
    public PatternMatcher(Parcel src) {
        mPattern = src.readString();
        mType = src.readInt();
    }
    
    public static final Parcelable.Creator<PatternMatcher> CREATOR
            = new Parcelable.Creator<PatternMatcher>() {
        public PatternMatcher createFromParcel(Parcel source) {
            return new PatternMatcher(source);
        }

        public PatternMatcher[] newArray(int size) {
            return new PatternMatcher[size];
        }
    };
    
    static boolean matchPattern(String pattern, String match, int type) {
        if (match == null) return false;
        if (type == PATTERN_LITERAL) {
            return pattern.equals(match);
        } if (type == PATTERN_PREFIX) {
            return match.startsWith(pattern);
        } else if (type != PATTERN_SIMPLE_GLOB) {
            return false;
        }
        
        final int NP = pattern.length();
        if (NP <= 0) {
            return match.length() <= 0;
        }
        final int NM = match.length();
        int ip = 0, im = 0;
        char nextChar = pattern.charAt(0);
        while ((ip<NP) && (im<NM)) {
            char c = nextChar;
            ip++;
            nextChar = ip < NP ? pattern.charAt(ip) : 0;
            final boolean escaped = (c == '\\');
            if (escaped) {
                c = nextChar;
                ip++;
                nextChar = ip < NP ? pattern.charAt(ip) : 0;
            }
            if (nextChar == '*') {
                if (!escaped && c == '.') {
                    if (ip >= (NP-1)) {
                        // at the end with a pattern match, so
                        // all is good without checking!
                        return true;
                    }
                    ip++;
                    nextChar = pattern.charAt(ip);
                    // Consume everything until the next character in the
                    // pattern is found.
                    if (nextChar == '\\') {
                        ip++;
                        nextChar = ip < NP ? pattern.charAt(ip) : 0;
                    }
                    do {
                        if (match.charAt(im) == nextChar) {
                            break;
                        }
                        im++;
                    } while (im < NM);
                    if (im == NM) {
                        // Whoops, the next character in the pattern didn't
                        // exist in the match.
                        return false;
                    }
                    ip++;
                    nextChar = ip < NP ? pattern.charAt(ip) : 0;
                    im++;
                } else {
                    // Consume only characters matching the one before '*'.
                    do {
                        if (match.charAt(im) != c) {
                            break;
                        }
                        im++;
                    } while (im < NM);
                    ip++;
                    nextChar = ip < NP ? pattern.charAt(ip) : 0;
                }
            } else {
                if (c != '.' && match.charAt(im) != c) return false;
                im++;
            }
        }
        
        if (ip >= NP && im >= NM) {
            // Reached the end of both strings, all is good!
            return true;
        }
        
        // One last check: we may have finished the match string, but still
        // have a '.*' at the end of the pattern, which should still count
        // as a match.
        if (ip == NP-2 && pattern.charAt(ip) == '.'
            && pattern.charAt(ip+1) == '*') {
            return true;
        }
        
        return false;
    }
}
