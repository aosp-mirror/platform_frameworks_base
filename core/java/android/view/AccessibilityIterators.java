/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.view;

import android.annotation.UnsupportedAppUsage;
import android.content.res.Configuration;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * This class contains the implementation of text segment iterators
 * for accessibility support.
 *
 * Note: Such iterators are needed in the view package since we want
 * to be able to iterator over content description of any view.
 *
 * @hide
 */
public final class AccessibilityIterators {

    /**
     * @hide
     */
    public static interface TextSegmentIterator {
        public int[] following(int current);
        public int[] preceding(int current);
    }

    /**
     * @hide
     */
    public static abstract class AbstractTextSegmentIterator implements TextSegmentIterator {

        @UnsupportedAppUsage
        protected String mText;

        private final int[] mSegment = new int[2];

        public void initialize(String text) {
            mText = text;
        }

        protected int[] getRange(int start, int end) {
            if (start < 0 || end < 0 || start ==  end) {
                return null;
            }
            mSegment[0] = start;
            mSegment[1] = end;
            return mSegment;
        }
    }

    static class CharacterTextSegmentIterator extends AbstractTextSegmentIterator
            implements ViewRootImpl.ConfigChangedCallback {
        private static CharacterTextSegmentIterator sInstance;

        private Locale mLocale;

        protected BreakIterator mImpl;

        public static CharacterTextSegmentIterator getInstance(Locale locale) {
            if (sInstance == null) {
                sInstance = new CharacterTextSegmentIterator(locale);
            }
            return sInstance;
        }

        private CharacterTextSegmentIterator(Locale locale) {
            mLocale = locale;
            onLocaleChanged(locale);
            ViewRootImpl.addConfigCallback(this);
        }

        @Override
        public void initialize(String text) {
            super.initialize(text);
            mImpl.setText(text);
        }

        @Override
        public int[] following(int offset) {
            final int textLegth = mText.length();
            if (textLegth <= 0) {
                return null;
            }
            if (offset >= textLegth) {
                return null;
            }
            int start = offset;
            if (start < 0) {
                start = 0;
            }
            while (!mImpl.isBoundary(start)) {
                start = mImpl.following(start);
                if (start == BreakIterator.DONE) {
                    return null;
                }
            }
            final int end = mImpl.following(start);
            if (end == BreakIterator.DONE) {
                return null;
            }
            return getRange(start, end);
        }

        @Override
        public int[] preceding(int offset) {
            final int textLegth = mText.length();
            if (textLegth <= 0) {
                return null;
            }
            if (offset <= 0) {
                return null;
            }
            int end = offset;
            if (end > textLegth) {
                end = textLegth;
            }
            while (!mImpl.isBoundary(end)) {
                end = mImpl.preceding(end);
                if (end == BreakIterator.DONE) {
                    return null;
                }
            }
            final int start = mImpl.preceding(end);
            if (start == BreakIterator.DONE) {
                return null;
            }
            return getRange(start, end);
        }

        @Override
        public void onConfigurationChanged(Configuration globalConfig) {
            final Locale locale = globalConfig.getLocales().get(0);
            if (locale == null) {
                return;
            }
            if (!mLocale.equals(locale)) {
                mLocale = locale;
                onLocaleChanged(locale);
            }
        }

        protected void onLocaleChanged(Locale locale) {
            mImpl = BreakIterator.getCharacterInstance(locale);
        }
    }

    static class WordTextSegmentIterator extends CharacterTextSegmentIterator {
        private static WordTextSegmentIterator sInstance;

        public static WordTextSegmentIterator getInstance(Locale locale) {
            if (sInstance == null) {
                sInstance = new WordTextSegmentIterator(locale);
            }
            return sInstance;
        }

        private WordTextSegmentIterator(Locale locale) {
           super(locale);
        }

        @Override
        protected void onLocaleChanged(Locale locale) {
            mImpl = BreakIterator.getWordInstance(locale);
        }

        @Override
        public int[] following(int offset) {
            final int textLegth = mText.length();
            if (textLegth <= 0) {
                return null;
            }
            if (offset >= mText.length()) {
                return null;
            }
            int start = offset;
            if (start < 0) {
                start = 0;
            }
            while (!isLetterOrDigit(start) && !isStartBoundary(start)) {
                start = mImpl.following(start);
                if (start == BreakIterator.DONE) {
                    return null;
                }
            }
            final int end = mImpl.following(start);
            if (end == BreakIterator.DONE || !isEndBoundary(end)) {
                return null;
            }
            return getRange(start, end);
        }

        @Override
        public int[] preceding(int offset) {
            final int textLegth = mText.length();
            if (textLegth <= 0) {
                return null;
            }
            if (offset <= 0) {
                return null;
            }
            int end = offset;
            if (end > textLegth) {
                end = textLegth;
            }
            while (end > 0 && !isLetterOrDigit(end - 1) && !isEndBoundary(end)) {
                end = mImpl.preceding(end);
                if (end == BreakIterator.DONE) {
                    return null;
                }
            }
            final int start = mImpl.preceding(end);
            if (start == BreakIterator.DONE || !isStartBoundary(start)) {
                return null;
            }
            return getRange(start, end);
        }

        private boolean isStartBoundary(int index) {
            return isLetterOrDigit(index)
                && (index == 0 || !isLetterOrDigit(index - 1));
        }

        private boolean isEndBoundary(int index) {
            return (index > 0 && isLetterOrDigit(index - 1))
                && (index == mText.length() || !isLetterOrDigit(index));
        }

        private boolean isLetterOrDigit(int index) {
            if (index >= 0 && index < mText.length()) {
                final int codePoint = mText.codePointAt(index);
                return Character.isLetterOrDigit(codePoint);
            }
            return false;
        }
    }

    static class ParagraphTextSegmentIterator extends AbstractTextSegmentIterator {
        private static ParagraphTextSegmentIterator sInstance;

        public static ParagraphTextSegmentIterator getInstance() {
            if (sInstance == null) {
                sInstance = new ParagraphTextSegmentIterator();
            }
            return sInstance;
        }

        @Override
        public int[] following(int offset) {
            final int textLength = mText.length();
            if (textLength <= 0) {
                return null;
            }
            if (offset >= textLength) {
                return null;
            }
            int start = offset;
            if (start < 0) {
                start = 0;
            }
            while (start < textLength && mText.charAt(start) == '\n'
                    && !isStartBoundary(start)) {
                start++;
            }
            if (start >= textLength) {
                return null;
            }
            int end = start + 1;
            while (end < textLength && !isEndBoundary(end)) {
                end++;
            }
            return getRange(start, end);
        }

        @Override
        public int[] preceding(int offset) {
            final int textLength = mText.length();
            if (textLength <= 0) {
                return null;
            }
            if (offset <= 0) {
                return null;
            }
            int end = offset;
            if (end > textLength) {
                end = textLength;
            }
            while(end > 0 && mText.charAt(end - 1) == '\n' && !isEndBoundary(end)) {
                end--;
            }
            if (end <= 0) {
                return null;
            }
            int start = end - 1;
            while (start > 0 && !isStartBoundary(start)) {
                start--;
            }
            return getRange(start, end);
        }

        private boolean isStartBoundary(int index) {
            return (mText.charAt(index) != '\n'
                && (index == 0 || mText.charAt(index - 1) == '\n'));
        }

        private boolean isEndBoundary(int index) {
            return (index > 0 && mText.charAt(index - 1) != '\n'
                && (index == mText.length() || mText.charAt(index) == '\n'));
        }
    }
}
