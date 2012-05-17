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

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.ActivityInfo;
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
        protected static final int DONE = -1;

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
            implements ComponentCallbacks {
        private static CharacterTextSegmentIterator sInstance;

        private final Context mAppContext;

        protected BreakIterator mImpl;

        public static CharacterTextSegmentIterator getInstance(Context context) {
            if (sInstance == null) {
                sInstance = new CharacterTextSegmentIterator(context);
            }
            return sInstance;
        }

        private CharacterTextSegmentIterator(Context context) {
            mAppContext = context.getApplicationContext();
            Locale locale = mAppContext.getResources().getConfiguration().locale;
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
            int start = -1;
            if (offset < 0) {
                offset = 0;
                if (mImpl.isBoundary(offset)) {
                    start = offset;
                }
            }
            if (start < 0) {
                start = mImpl.following(offset);
            }
            if (start < 0) {
                return null;
            }
            final int end = mImpl.following(start);
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
            int end = -1;
            if (offset > mText.length()) {
                offset = mText.length();
                if (mImpl.isBoundary(offset)) {
                    end = offset;
                }
            }
            if (end < 0) {
                end = mImpl.preceding(offset);
            }
            if (end < 0) {
                return null;
            }
            final int start = mImpl.preceding(end);
            return getRange(start, end);
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            Configuration oldConfig = mAppContext.getResources().getConfiguration();
            final int changed = oldConfig.diff(newConfig);
            if ((changed & ActivityInfo.CONFIG_LOCALE) != 0) {
                Locale locale = newConfig.locale;
                onLocaleChanged(locale);
            }
        }

        @Override
        public void onLowMemory() {
            /* ignore */
        }

        protected void onLocaleChanged(Locale locale) {
            mImpl = BreakIterator.getCharacterInstance(locale);
        }
    }

    static class WordTextSegmentIterator extends CharacterTextSegmentIterator {
        private static WordTextSegmentIterator sInstance;

        public static WordTextSegmentIterator getInstance(Context context) {
            if (sInstance == null) {
                sInstance = new WordTextSegmentIterator(context);
            }
            return sInstance;
        }

        private WordTextSegmentIterator(Context context) {
           super(context);
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
            int start = -1;
            if (offset < 0) {
                offset = 0;
                if (mImpl.isBoundary(offset) && isLetterOrDigit(offset)) {
                    start = offset;
                }
            }
            if (start < 0) {
                while ((offset = mImpl.following(offset)) != DONE) {
                    if (isLetterOrDigit(offset)) {
                        start = offset;
                        break;
                    }
                }
            }
            if (start < 0) {
                return null;
            }
            final int end = mImpl.following(start);
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
            int end = -1;
            if (offset > mText.length()) {
                offset = mText.length();
                if (mImpl.isBoundary(offset) && offset > 0 && isLetterOrDigit(offset - 1)) {
                    end = offset;
                }
            }
            if (end < 0) {
                while ((offset = mImpl.preceding(offset)) != DONE) {
                    if (offset > 0 && isLetterOrDigit(offset - 1)) {
                        end = offset;
                        break;
                    }
                }
            }
            if (end < 0) {
                return null;
            }
            final int start = mImpl.preceding(end);
            return getRange(start, end);
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
            int start = -1;
            if (offset < 0) {
                start = 0;
            } else {
                for (int i = offset + 1; i < textLength; i++) {
                    if (mText.charAt(i) == '\n') {
                        start = i;
                        break;
                    }
                }
            }
            if (start < 0) {
                return null;
            }
            while (start < textLength && mText.charAt(start) == '\n') {
                start++;
            }
            int end = start;
            for (int i = end + 1; i < textLength; i++) {
                end = i;
                if (mText.charAt(i) == '\n') {
                    break;
                }
            }
            while (end < textLength && mText.charAt(end) == '\n') {
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
            int end = -1;
            if (offset > mText.length()) {
                end = mText.length();
            } else {
                if (offset > 0 && mText.charAt(offset - 1) == '\n') {
                    offset--;
                }
                for (int i = offset - 1; i >= 0; i--) {
                    if (i > 0 && mText.charAt(i - 1) == '\n') {
                        end = i;
                        break;
                    }
                }
            }
            if (end <= 0) {
                return null;
            }
            int start = end;
            while (start > 0 && mText.charAt(start - 1) == '\n') {
                start--;
            }
            if (start == 0 && mText.charAt(start) == '\n') {
                return null;
            }
            for (int i = start - 1; i >= 0; i--) {
                start = i;
                if (start > 0 && mText.charAt(i - 1) == '\n') {
                    break;
                }
            }
            start = Math.max(0, start);
            return getRange(start, end);
        }
    }
}
