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

package android.widget;

import android.graphics.Rect;
import android.text.Layout;
import android.text.Spannable;
import android.view.AccessibilityIterators.AbstractTextSegmentIterator;

/**
 * This class contains the implementation of text segment iterators
 * for accessibility support.
 */
final class AccessibilityIterators {

    static class LineTextSegmentIterator extends AbstractTextSegmentIterator {
        private static LineTextSegmentIterator sLineInstance;

        protected static final int DIRECTION_START = -1;
        protected static final int DIRECTION_END = 1;

        protected Layout mLayout;

        public static LineTextSegmentIterator getInstance() {
            if (sLineInstance == null) {
                sLineInstance = new LineTextSegmentIterator();
            }
            return sLineInstance;
        }

        public void initialize(Spannable text, Layout layout) {
            mText = text.toString();
            mLayout = layout;
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
            int nextLine = -1;
            if (offset < 0) {
                nextLine = mLayout.getLineForOffset(0);
            } else {
                final int currentLine = mLayout.getLineForOffset(offset);
                if (currentLine < mLayout.getLineCount() - 1) {
                    nextLine = currentLine + 1;
                }
            }
            if (nextLine < 0) {
                return null;
            }
            final int start = getLineEdgeIndex(nextLine, DIRECTION_START);
            final int end = getLineEdgeIndex(nextLine, DIRECTION_END) + 1;
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
            int previousLine = -1;
            if (offset > mText.length()) {
                previousLine = mLayout.getLineForOffset(mText.length());
            } else {
                final int currentLine = mLayout.getLineForOffset(offset - 1);
                if (currentLine > 0) {
                    previousLine = currentLine - 1;
                }
            }
            if (previousLine < 0) {
                return null;
            }
            final int start = getLineEdgeIndex(previousLine, DIRECTION_START);
            final int end = getLineEdgeIndex(previousLine, DIRECTION_END) + 1;
            return getRange(start, end);
        }

        protected int getLineEdgeIndex(int lineNumber, int direction) {
            final int paragraphDirection = mLayout.getParagraphDirection(lineNumber);
            if (direction * paragraphDirection < 0) {
                return mLayout.getLineStart(lineNumber);
            } else {
                return mLayout.getLineEnd(lineNumber) - 1;
            }
        }
    }

    static class PageTextSegmentIterator extends LineTextSegmentIterator {
        private static PageTextSegmentIterator sPageInstance;

        private TextView mView;

        private final Rect mTempRect = new Rect();

        public static PageTextSegmentIterator getInstance() {
            if (sPageInstance == null) {
                sPageInstance = new PageTextSegmentIterator();
            }
            return sPageInstance;
        }

        public void initialize(TextView view) {
            super.initialize((Spannable) view.getIterableTextForAccessibility(), view.getLayout());
            mView = view;
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
            if (!mView.getGlobalVisibleRect(mTempRect)) {
                return null;
            }

            final int currentLine = mLayout.getLineForOffset(offset);
            final int currentLineTop = mLayout.getLineTop(currentLine);
            final int pageHeight = mTempRect.height() - mView.getTotalPaddingTop()
                    - mView.getTotalPaddingBottom();

            final int nextPageStartLine;
            final int nextPageEndLine;
            if (offset < 0) {
                nextPageStartLine = currentLine;
                final int nextPageEndY = currentLineTop + pageHeight;
                nextPageEndLine = mLayout.getLineForVertical(nextPageEndY);
            } else {
                final int nextPageStartY = currentLineTop + pageHeight;
                nextPageStartLine = mLayout.getLineForVertical(nextPageStartY) + 1;
                if (mLayout.getLineTop(nextPageStartLine) <= nextPageStartY) {
                    return null;
                }
                final int nextPageEndY = nextPageStartY + pageHeight;
                nextPageEndLine = mLayout.getLineForVertical(nextPageEndY);
            }

            final int start = getLineEdgeIndex(nextPageStartLine, DIRECTION_START);
            final int end = getLineEdgeIndex(nextPageEndLine, DIRECTION_END) + 1;

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
            if (!mView.getGlobalVisibleRect(mTempRect)) {
                return null;
            }

            final int currentLine = mLayout.getLineForOffset(offset);
            final int currentLineTop = mLayout.getLineTop(currentLine);
            final int pageHeight = mTempRect.height() - mView.getTotalPaddingTop()
                    - mView.getTotalPaddingBottom();

            final int previousPageStartLine;
            final int previousPageEndLine;
            if (offset > mText.length()) {
                final int prevousPageStartY = mLayout.getHeight() - pageHeight;
                if (prevousPageStartY < 0) {
                    return null;
                }
                previousPageStartLine = mLayout.getLineForVertical(prevousPageStartY);
                previousPageEndLine = mLayout.getLineCount() - 1;
            } else {
                final int prevousPageStartY;
                if (offset == mText.length()) {
                    prevousPageStartY = mLayout.getHeight() - 2 * pageHeight;
                } else {
                    prevousPageStartY = currentLineTop - 2 * pageHeight;
                }
                if (prevousPageStartY < 0) {
                    return null;
                }
                previousPageStartLine = mLayout.getLineForVertical(prevousPageStartY);
                final int previousPageEndY = prevousPageStartY + pageHeight;
                previousPageEndLine = mLayout.getLineForVertical(previousPageEndY) - 1;
            }

            final int start = getLineEdgeIndex(previousPageStartLine, DIRECTION_START);
            final int end = getLineEdgeIndex(previousPageEndLine, DIRECTION_END) + 1;

            return getRange(start, end);
        }
    }
}
