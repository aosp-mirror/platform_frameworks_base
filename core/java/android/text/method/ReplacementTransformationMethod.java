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

package android.text.method;

import android.graphics.Rect;
import android.text.Editable;
import android.text.GetChars;
import android.text.Spannable;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.view.View; 

/**
 * This transformation method causes the characters in the {@link #getOriginal}
 * array to be replaced by the corresponding characters in the
 * {@link #getReplacement} array.
 */
public abstract class ReplacementTransformationMethod
implements TransformationMethod
{
    /**
     * Returns the list of characters that are to be replaced by other
     * characters when displayed.
     */
    protected abstract char[] getOriginal();
    /**
     * Returns a parallel array of replacement characters for the ones
     * that are to be replaced.
     */
    protected abstract char[] getReplacement();

    /**
     * Returns a CharSequence that will mirror the contents of the
     * source CharSequence but with the characters in {@link #getOriginal}
     * replaced by ones from {@link #getReplacement}.
     */
    public CharSequence getTransformation(CharSequence source, View v) {
        char[] original = getOriginal();
        char[] replacement = getReplacement();

        /*
         * Short circuit for faster display if the text will never change.
         */
        if (!(source instanceof Editable)) {
            /*
             * Check whether the text does not contain any of the
             * source characters so can be used unchanged.
             */
            boolean doNothing = true;
            int n = original.length;
            for (int i = 0; i < n; i++) {
                if (TextUtils.indexOf(source, original[i]) >= 0) {
                    doNothing = false;
                    break;
                }
            }
            if (doNothing) {
                return source;
            }

            if (!(source instanceof Spannable)) {
                /*
                 * The text contains some of the source characters,
                 * but they can be flattened out now instead of
                 * at display time.
                 */
                if (source instanceof Spanned) {
                    return new SpannedString(new SpannedReplacementCharSequence(
                                                        (Spanned) source,
                                                        original, replacement));
                } else {
                    return new ReplacementCharSequence(source,
                                                       original,
                                                       replacement).toString();
                }
            }
        }

        if (source instanceof Spanned) {
            return new SpannedReplacementCharSequence((Spanned) source,
                                                      original, replacement);
        } else {
            return new ReplacementCharSequence(source, original, replacement);
        }
    }

    public void onFocusChanged(View view, CharSequence sourceText,
                               boolean focused, int direction,
                               Rect previouslyFocusedRect) {
        // This callback isn't used.
    }

    private static class ReplacementCharSequence
    implements CharSequence, GetChars {
        private char[] mOriginal, mReplacement;

        public ReplacementCharSequence(CharSequence source, char[] original,
                                       char[] replacement) {
            mSource = source;
            mOriginal = original;
            mReplacement = replacement;
        }

        public int length() {
            return mSource.length();
        }

        public char charAt(int i) {
            char c = mSource.charAt(i);

            int n = mOriginal.length;
            for (int j = 0; j < n; j++) {
                if (c == mOriginal[j]) {
                    c = mReplacement[j];
                }
            }

            return c;
        }

        public CharSequence subSequence(int start, int end) {
            char[] c = new char[end - start];

            getChars(start, end, c, 0);
            return new String(c);
        }

        public String toString() {
            char[] c = new char[length()];

            getChars(0, length(), c, 0);
            return new String(c);
        }

        public void getChars(int start, int end, char[] dest, int off) {
            TextUtils.getChars(mSource, start, end, dest, off);
            int offend = end - start + off;
            int n = mOriginal.length;

            for (int i = off; i < offend; i++) {
                char c = dest[i];

                for (int j = 0; j < n; j++) {
                    if (c == mOriginal[j]) {
                        dest[i] = mReplacement[j];
                    }
                }
            }
        }

        private CharSequence mSource;
    }

    private static class SpannedReplacementCharSequence
    extends ReplacementCharSequence
    implements Spanned
    {
        public SpannedReplacementCharSequence(Spanned source, char[] original,
                                              char[] replacement) {
            super(source, original, replacement);
            mSpanned = source;
        }

        public CharSequence subSequence(int start, int end) {
            return new SpannedString(this).subSequence(start, end);
        }

        public <T> T[] getSpans(int start, int end, Class<T> type) {
            return mSpanned.getSpans(start, end, type);
        }

        public int getSpanStart(Object tag) {
            return mSpanned.getSpanStart(tag);
        }

        public int getSpanEnd(Object tag) {
            return mSpanned.getSpanEnd(tag);
        }

        public int getSpanFlags(Object tag) {
            return mSpanned.getSpanFlags(tag);
        }

        public int nextSpanTransition(int start, int end, Class type) {
            return mSpanned.nextSpanTransition(start, end, type);
        }

        private Spanned mSpanned;
    }
}
