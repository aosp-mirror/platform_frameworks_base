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
import android.icu.text.UnicodeSet;
import android.icu.text.UnicodeSetIterator;
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
import java.util.ArrayList;

public class TextPerfUtils {

    private static final int PARA_LENGTH = 500;  // Number of characters in a paragraph.

    private Random mRandom = new Random(0);

    private static final String[] ALPHABET;
    private static final int ALPHABET_LENGTH;
    static {
        String alphabets = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        ALPHABET_LENGTH = alphabets.length();
        ALPHABET = new String[ALPHABET_LENGTH];
        for (int i = 0; i < ALPHABET_LENGTH; ++i) {
            ALPHABET[i] = Character.toString(alphabets.charAt(i));
        }
    }


    private static final ColorStateList TEXT_COLOR = ColorStateList.valueOf(0x00000000);
    private static final String[] FAMILIES = { "sans-serif", "serif", "monospace" };
    private static final int[] STYLES = {
            Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC
    };

    public void resetRandom(long seed) {
        mRandom = new Random(seed);
    }

    private static String[] UnicodeSetToArray(String setStr) {
        final UnicodeSet set = new UnicodeSet(setStr);
        final UnicodeSetIterator iterator = new UnicodeSetIterator(set);
        final ArrayList<String> out = new ArrayList<>(set.size());
        while (iterator.next()) {
          out.add(iterator.getString());
        }
        return out.toArray(new String[out.size()]);
    }

    public CharSequence nextRandomParagraph(int wordLen, boolean applyRandomStyle, String setStr) {
        return nextRandomParagraph(wordLen, applyRandomStyle, UnicodeSetToArray(setStr));
    }

    public CharSequence nextRandomParagraph(int wordLen, boolean applyRandomStyle) {
        return nextRandomParagraph(wordLen, applyRandomStyle, ALPHABET);
    }

    public CharSequence nextRandomParagraph(int wordLen, boolean applyRandomStyle,
            String[] charSet) {
        ArrayList<Character> chars = new ArrayList<>();
        ArrayList<Integer> wordOffsets = new ArrayList<>();
        for (int i = 0; i < PARA_LENGTH; i++) {
            if (i % (wordLen + 1) == wordLen) {
                chars.add(' ');
                wordOffsets.add(chars.size());
            } else {
                final String str = charSet[mRandom.nextInt(charSet.length)];
                for (int j = 0; j < str.length(); ++j) {
                    chars.add(str.charAt(j));
                }
            }
        }
        wordOffsets.add(chars.size());

        char[] buffer = new char[chars.size()];
        for (int i = 0; i < buffer.length; ++i) {
            buffer[i] = chars.get(i);
        }
        CharSequence cs = CharBuffer.wrap(buffer);
        if (!applyRandomStyle) {
            return cs;
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(cs);
        int prevWordStart = 0;
        for (int i = 0; i < wordOffsets.size(); i++) {
            final int spanStart = prevWordStart;
            final int spanEnd = wordOffsets.get(i);

            final TextAppearanceSpan span = new TextAppearanceSpan(
                  FAMILIES[mRandom.nextInt(FAMILIES.length)],
                  STYLES[mRandom.nextInt(STYLES.length)],
                  24 + mRandom.nextInt(32),  // text size. min 24 max 56
                  TEXT_COLOR, TEXT_COLOR);

            ssb.setSpan(span, spanStart, spanEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            prevWordStart = spanEnd;
        }
        return ssb;
    }
}
