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
            int nextLine;
            if (offset < 0) {
                nextLine = mLayout.getLineForOffset(0);
            } else {
                final int currentLine = mLayout.getLineForOffset(offset);
                if (getLineEdgeIndex(currentLine, DIRECTION_START) == offset) {
                    nextLine = currentLine;
                } else {
                    nextLine = currentLine + 1;
                }
            }
            if (nextLine >= mLayout.getLineCount()) {
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
            int previousLine;
            if (offset > mText.length()) {
                previousLine = mLayout.getLineForOffset(mText.length());
            } else {
                final int currentLine = mLayout.getLineForOffset(offset);
                if (getLineEdgeIndex(currentLine, DIRECTION_END) + 1 == offset) {
                    previousLine = currentLine;
                } else {
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
            final int textLength = mText.length();
            if (textLength <= 0) {
                return null;
            }
            if (offset >= mText.length()) {
                return null;
            }
            if (!mView.getGlobalVisibleRect(mTempRect)) {
                return null;
            }

            final int start = Math.max(0, offset);

            final int currentLine = mLayout.getLineForOffset(start);
            final int currentLineTop = mLayout.getLineTop(currentLine);
            final int pageHeight = mTempRect.height() - mView.getTotalPaddingTop()
                    - mView.getTotalPaddingBottom();
            final int nextPageStartY = currentLineTop + pageHeight;
            final int lastLineTop = mLayout.getLineTop(mLayout.getLineCount() - 1);
            final int currentPageEndLine = (nextPageStartY < lastLineTop)
                    ? mLayout.getLineForVertical(nextPageStartY) - 1 : mLayout.getLineCount() - 1;

            final int end = getLineEdgeIndex(currentPageEndLine, DIRECTION_END) + 1;

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
            if (!mView.getGlobalVisibleRect(mTempRect)) {
                return null;
            }

            final int end = Math.min(mText.length(), offset);

            final int currentLine = mLayout.getLineForOffset(end);
            final int currentLineTop = mLayout.getLineTop(currentLine);
            final int pageHeight = mTempRect.height() - mView.getTotalPaddingTop()
                    - mView.getTotalPaddingBottom();
            final int previousPageEndY = currentLineTop - pageHeight;
            int currentPageStartLine = (previousPageEndY > 0) ?
                     mLayout.getLineForVertical(previousPageEndY) : 0;
            // If we're at the end of text, we're at the end of the current line rather than the
            // start of the next line, so we should move up one fewer lines than we would otherwise.
            if (end == mText.length() && (currentPageStartLine < currentLine)) {
                currentPageStartLine += 1;
            }

            final int start = getLineEdgeIndex(currentPageStartLine, DIRECTION_START);

            return getRange(start, end);
        }
    }
}
