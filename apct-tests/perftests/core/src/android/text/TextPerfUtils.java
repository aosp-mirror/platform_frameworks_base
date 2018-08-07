/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.text;

import static android.text.TextDirectionHeuristics.LTR;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.style.TextAppearanceSpan;
import android.view.DisplayListCanvas;
import android.view.RenderNode;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.CharBuffer;
import java.util.Random;

public class TextPerfUtils {

    private static final int PARA_LENGTH = 500;  // Number of characters in a paragraph.

    private Random mRandom = new Random(0);

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ALPHABET_LENGTH = ALPHABET.length();

    private static final ColorStateList TEXT_COLOR = ColorStateList.valueOf(0x00000000);
    private static final String[] FAMILIES = { "sans-serif", "serif", "monospace" };
    private static final int[] STYLES = {
            Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC
    };

    private final char[] mBuffer = new char[PARA_LENGTH];

    public void resetRandom(long seed) {
        mRandom = new Random(seed);
    }

    public CharSequence nextRandomParagraph(int wordLen, boolean applyRandomStyle) {
        for (int i = 0; i < PARA_LENGTH; i++) {
            if (i % (wordLen + 1) == wordLen) {
                mBuffer[i] = ' ';
            } else {
                mBuffer[i] = ALPHABET.charAt(mRandom.nextInt(ALPHABET_LENGTH));
            }
        }

        CharSequence cs = CharBuffer.wrap(mBuffer);
        if (!applyRandomStyle) {
            return cs;
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(cs);
        for (int i = 0; i < ssb.length(); i += wordLen + 1) {
            final int spanStart = i;
            final int spanEnd = (i + wordLen) > ssb.length() ? ssb.length() : i + wordLen;

            final TextAppearanceSpan span = new TextAppearanceSpan(
                  FAMILIES[mRandom.nextInt(FAMILIES.length)],
                  STYLES[mRandom.nextInt(STYLES.length)],
                  24 + mRandom.nextInt(32),  // text size. min 24 max 56
                  TEXT_COLOR, TEXT_COLOR);

            ssb.setSpan(span, spanStart, spanEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }
        return ssb;
    }
}
