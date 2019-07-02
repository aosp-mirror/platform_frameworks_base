/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.text;

import static org.junit.Assert.assertArrayEquals;

import android.content.Context;
import android.graphics.Path;
import android.graphics.Typeface;
import android.platform.test.annotations.Presubmit;
import android.text.method.MetaKeyKeyListener;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LayoutBidiCursorPathTest {

    private static final float BIDI_TEXT_SIZE = 12f;
    private static final String LTR_TEXT = "hello";
    private static final String RTL_TEXT = "مرحبا";

    private SpannableStringBuilder mBidiText;
    private TextPaint mTextPaint;

    @Before
    public void setup() {
        mBidiText = new SpannableStringBuilder(LTR_TEXT + RTL_TEXT);

        final Context context = InstrumentationRegistry.getTargetContext();
        mTextPaint = new TextPaint();
        mTextPaint.setTypeface(
                Typeface.createFromAsset(context.getAssets(), "fonts/1em_bidi_font.ttf"));
        mTextPaint.setTextSize(BIDI_TEXT_SIZE);
    }

    @Test
    public void testGetCursorPathSegments() {
        // Setup layout and Act.
        final Path actualPath = new Path();
        setupLayoutAndGetCursorPath(actualPath);

        // Expected path.
        final float h1 = BIDI_TEXT_SIZE * LTR_TEXT.length() - 0.5f;
        final int top = 0;
        // sTypoLineGap is set to 1/5 of the Height in font metrics of the font file used here.
        final int bottom = Math.round(BIDI_TEXT_SIZE + BIDI_TEXT_SIZE / 5f);

        final Path expectedPath = new Path();

        expectedPath.moveTo(h1, top);
        expectedPath.lineTo(h1, bottom);

        // Assert.
        assertArrayEquals(expectedPath.approximate(0f), actualPath.approximate(0f), 0f);
    }

    @Test
    public void testGetCursorPath_whenShiftIsPressed() {
        // When shift is pressed a triangle is drawn at the bottom quarter of the cursor.
        // Set up key.
        final MetaKeyKeyListener metaKeyKeyListener = new MetaKeyKeyListener() {};
        metaKeyKeyListener
            .onKeyDown(null /*view*/, mBidiText, KeyEvent.KEYCODE_SHIFT_RIGHT, null /*keyEvent*/);

        // Setup layout and Act.
        final Path actualPath = new Path();
        setupLayoutAndGetCursorPath(actualPath);

        // Expected path.
        final float h1 = BIDI_TEXT_SIZE * LTR_TEXT.length() - 0.5f;
        final int top = 0;
        // sTypoLineGap is set to 1/5 of the Height in font metrics of the font file used here.
        int bottom = Math.round(BIDI_TEXT_SIZE + BIDI_TEXT_SIZE / 5f);
        // Draw a triangle at the bottom quarter of the cursor, thus cut the cursor to its 3/4
        // length.
        final int dist = (bottom - top) / 4;
        bottom -= dist;

        final Path expectedPath = new Path();

        expectedPath.moveTo(h1, top);
        expectedPath.lineTo(h1, bottom);

        expectedPath.moveTo(h1, bottom);
        expectedPath.lineTo(h1 - dist, bottom + dist);

        expectedPath.moveTo(h1 - dist, bottom + dist - 0.5f);
        expectedPath.lineTo(h1 + dist, bottom + dist - 0.5f);

        expectedPath.moveTo(h1 + dist, bottom + dist);
        expectedPath.lineTo(h1, bottom);

        // Assert.
        assertArrayEquals(expectedPath.approximate(0f), actualPath.approximate(0f), 0f);
    }

    @Test
    public void testGetCursorPath_whenAltIsPressed() {
        // When alt is pressed a triangle is drawn at the top quarter of the cursor.
        // Set up key.
        final MetaKeyKeyListener metaKeyKeyListener = new MetaKeyKeyListener() {};
        metaKeyKeyListener
            .onKeyDown(null /*view*/, mBidiText, KeyEvent.KEYCODE_ALT_RIGHT, null /*keyEvent*/);

        // Setup layout and Act.
        final Path actualPath = new Path();
        setupLayoutAndGetCursorPath(actualPath);

        // Expected path.
        final float h1 = BIDI_TEXT_SIZE * LTR_TEXT.length() - 0.5f;
        int top = 0;
        // sTypoLineGap is set to 1/5 of the Height in font metrics of the font file used here.
        final int bottom = Math.round(BIDI_TEXT_SIZE + BIDI_TEXT_SIZE / 5f);
        // Draw a triangle at the top quarter of the cursor, thus cut the cursor to its 3/4 length.
        final int dist = (bottom - top) / 4;
        top += dist;

        final Path expectedPath = new Path();

        expectedPath.moveTo(h1, top);
        expectedPath.lineTo(h1, bottom);

        expectedPath.moveTo(h1, top);
        expectedPath.lineTo(h1 - dist, top - dist);

        expectedPath.moveTo(h1 - dist, top - dist + 0.5f);
        expectedPath.lineTo(h1 + dist, top - dist + 0.5f);

        expectedPath.moveTo(h1 + dist, top - dist);
        expectedPath.lineTo(h1, top);

        // Assert.
        assertArrayEquals(expectedPath.approximate(0f), actualPath.approximate(0f), 0f);
    }

    private void setupLayoutAndGetCursorPath(Path path) {
        final Layout layout = StaticLayout.Builder.obtain(
                mBidiText, 0, mBidiText.length(),  mTextPaint, Integer.MAX_VALUE)
                .setIncludePad(false)
                .build();

        layout.getCursorPath(LTR_TEXT.length(), path, mBidiText);
    }
}
