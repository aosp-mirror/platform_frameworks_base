/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION;
import static android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION;
import static android.view.inputmethod.CursorAnchorInfo.FLAG_IS_RTL;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TextViewPopulateCharacterBoundsTest {
    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);
    private Activity mActivity;
    private Instrumentation mInstrumentation;
    private Typeface mTypeface;
    @Before
    public void setup() {
        mActivity = mActivityRule.getActivity();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();

        // The test font has following coverage and width.
        // U+0020: 10em
        // U+002E (.): 10em
        // U+0043 (C): 100em
        // U+0049 (I): 1em
        // U+004C (L): 50em
        // U+0056 (V): 5em
        // U+0058 (X): 10em
        // U+005F (_): 0em
        // U+05D0    : 1em  // HEBREW LETTER ALEF
        // U+05D1    : 5em  // HEBREW LETTER BET
        // U+FFFD (invalid surrogate will be replaced to this): 7em
        // U+10331 (\uD800\uDF31): 10em
        // Undefined : 0.5em
        mTypeface = Typeface.createFromAsset(mInstrumentation.getTargetContext().getAssets(),
                "fonts/StaticLayoutLineBreakingTestFont.ttf");
    }

    private TextView createTextView(String text, float textSize, int width, int height) {
        final TextView textView = new TextView(mActivity);
        textView.setTypeface(mTypeface);

        textView.setText(text);
        // Make 1 em equal to 10 pixels.
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        textView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        textView.layout(0, 0, width, height);
        return textView;
    }

    @Test
    public void testPopulateCharacterBounds_LTR() {
        final String text = "IIVX";
        final TextView textView = createTextView(text, 10.0f, 200, 1000);

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        textView.populateCharacterBounds(builder, 0, text.length(), 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final Layout layout = textView.getLayout();
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(0.0f, layout.getLineTop(0), 10.0f, layout.getLineBottom(0)),
                new RectF(10.0f, layout.getLineTop(0), 20.0f, layout.getLineBottom(0)),
                new RectF(20.0f, layout.getLineTop(0), 70.0f, layout.getLineBottom(0)),
                new RectF(70.0f, layout.getLineTop(0), 170.0f, layout.getLineBottom(0))
        };
        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_LTR_multiline() {
        final String text = "IVVI";
        final TextView textView = createTextView(text, 10.0f, 100, 1000);

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        textView.populateCharacterBounds(builder, 0, text.length(), 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final Layout layout = textView.getLayout();
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(0.0f, layout.getLineTop(0), 10.0f, layout.getLineBottom(0)),
                new RectF(10.0f, layout.getLineTop(0), 60.0f, layout.getLineBottom(0)),
                // The second line.
                new RectF(0.0f, layout.getLineTop(1), 50.0f, layout.getLineBottom(1)),
                new RectF(50.0f, layout.getLineTop(1), 60.0f, layout.getLineBottom(1))
        };
        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_LTR_newline() {
        final String text = "IV\nVI";
        final TextView textView = createTextView(text, 10.0f, 100, 1000);

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        textView.populateCharacterBounds(builder, 0, text.length(), 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final Layout layout = textView.getLayout();
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(0.0f, layout.getLineTop(0), 10.0f, layout.getLineBottom(0)),
                new RectF(10.0f, layout.getLineTop(0), 60.0f, layout.getLineBottom(0)),
                // Newline belongs to the first line, and it has 0 width in the font.
                new RectF(60.0f, layout.getLineTop(0), 60.0f, layout.getLineBottom(0)),
                // The second line.
                new RectF(0.0f, layout.getLineTop(1), 50.0f, layout.getLineBottom(1)),
                new RectF(50.0f, layout.getLineTop(1), 60.0f, layout.getLineBottom(1))
        };
        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_RTL() {
        final String text = "\u05D0\u05D0\u05D1\u05D1";
        final TextView textView = createTextView(text, 10.0f, 200, 1000);

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        textView.populateCharacterBounds(builder, 0, text.length(), 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final Layout layout = textView.getLayout();
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(190.0f, layout.getLineTop(0), 200.0f, layout.getLineBottom(0)),
                new RectF(180.0f, layout.getLineTop(0), 190.0f, layout.getLineBottom(0)),
                new RectF(130.0f, layout.getLineTop(0), 180.0f, layout.getLineBottom(0)),
                new RectF(80.0f, layout.getLineTop(0), 130.0f, layout.getLineBottom(0))
        };
        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_RTL_multiline() {
        final String text = "\u05D0\u05D1\u05D1\u05D0";
        final TextView textView = createTextView(text, 10.0f, 100, 1000);

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        textView.populateCharacterBounds(builder, 0, text.length(), 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final Layout layout = textView.getLayout();
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(90.0f, layout.getLineTop(0), 100.0f, layout.getLineBottom(0)),
                new RectF(40.0f, layout.getLineTop(0), 90.0f, layout.getLineBottom(0)),
                // The second line
                new RectF(50.0f, layout.getLineTop(1), 100.0f, layout.getLineBottom(1)),
                new RectF(40.0f, layout.getLineTop(1), 50.0f, layout.getLineBottom(1))
        };
        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_RTL_newline() {
        final String text = "\u05D0\u05D1\n\u05D1\u05D0";
        final TextView textView = createTextView(text, 10.0f, 100, 1000);

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        textView.populateCharacterBounds(builder, 0, text.length(), 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final Layout layout = textView.getLayout();
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(90.0f, layout.getLineTop(0), 100.0f, layout.getLineBottom(0)),
                new RectF(40.0f, layout.getLineTop(0), 90.0f, layout.getLineBottom(0)),
                // Newline belongs to the first line, and it has 0 width in the font.
                new RectF(40.0f, layout.getLineTop(0), 40.0f, layout.getLineBottom(0)),
                // The second line
                new RectF(50.0f, layout.getLineTop(1), 100.0f, layout.getLineBottom(1)),
                new RectF(40.0f, layout.getLineTop(1), 50.0f, layout.getLineBottom(1))
        };
        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                // Newline is in an RTL run.
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_BiDi() {
        final String text = "IV\u05D0\u05D1IV";
        final TextView textView = createTextView(text, 10.0f, 200, 1000);

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        textView.populateCharacterBounds(builder, 0, text.length(), 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final Layout layout = textView.getLayout();
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(0.0f, layout.getLineTop(0), 10.0f, layout.getLineBottom(0)),
                new RectF(10.0f, layout.getLineTop(0), 60.0f, layout.getLineBottom(0)),
                new RectF(110.0f, layout.getLineTop(0), 120.0f, layout.getLineBottom(0)),
                new RectF(60.0f, layout.getLineTop(0), 110.0f, layout.getLineBottom(0)),
                new RectF(120.0f, layout.getLineTop(0), 130.0f, layout.getLineBottom(0)),
                new RectF(130.0f, layout.getLineTop(0), 180.0f, layout.getLineBottom(0))
        };
        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_BiDi_multiline() {
        final String text = "IV\u05D0\u05D1IV";
        final TextView textView = createTextView(text, 10.0f, 100, 1000);

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        textView.populateCharacterBounds(builder, 0, text.length(), 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final Layout layout = textView.getLayout();
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(0.0f, layout.getLineTop(0), 10.0f, layout.getLineBottom(0)),
                new RectF(10.0f, layout.getLineTop(0), 60.0f, layout.getLineBottom(0)),
                new RectF(60.0f, layout.getLineTop(0), 70.0f, layout.getLineBottom(0)),
                // The second line.
                new RectF(0.0f, layout.getLineTop(1), 50.0f, layout.getLineBottom(1)),
                new RectF(50.0f, layout.getLineTop(1), 60.0f, layout.getLineBottom(1)),
                // The third line
                new RectF(0.0f, layout.getLineTop(2), 50.0f, layout.getLineBottom(2))
        };
        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION | FLAG_IS_RTL,
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_charactersWithInvisibleRegion() {
        final String text = "IVVI";
        final TextView textView = createTextView(text, 10.0f, 100, 1000);
        final Layout layout = textView.getLayout();

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        final int verticalOffset = -50;
        // Make viewToContentVerticalOffset -50px to simulate the case where TextView is scrolled.
        textView.populateCharacterBounds(builder, 0, text.length(), 0, verticalOffset);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        final float firstLineTop = layout.getLineTop(0) + verticalOffset;
        final float firstLineBottom = layout.getLineBottom(0) + verticalOffset;

        final float secondLineTop = layout.getLineTop(1) + verticalOffset;
        final float secondLineBottom = layout.getLineBottom(1) + verticalOffset;
        final RectF[] expectedCharacterBounds = new RectF[] {
                new RectF(0.0f, firstLineTop, 10.0f, firstLineBottom),
                new RectF(10.0f, firstLineTop, 60.0f, firstLineBottom),
                new RectF(0.0f, secondLineTop, 50.0f, secondLineBottom),
                new RectF(50.0f, secondLineTop, 60.0f, secondLineBottom)
        };

        assertCharacterBounds(expectedCharacterBounds, cursorAnchorInfo);

        final int[] expectedCharacterBoundsFlags = new int[] {
                FLAG_HAS_VISIBLE_REGION | FLAG_HAS_INVISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION | FLAG_HAS_INVISIBLE_REGION,
                // The second line is visible.
                FLAG_HAS_VISIBLE_REGION,
                FLAG_HAS_VISIBLE_REGION
        };
        assertCharacterBoundsFlags(expectedCharacterBoundsFlags, cursorAnchorInfo);
    }

    @Test
    public void testPopulateCharacterBounds_withinRange() {
        final String text = "IVVI";
        final TextView textView = createTextView(text, 10.0f, 100, 1000);
        final Layout layout = textView.getLayout();

        final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        builder.setMatrix(Matrix.IDENTITY_MATRIX);
        // Only query for character bounds within the range [2, 4).
        textView.populateCharacterBounds(builder, 2, 4, 0, 0);

        final CursorAnchorInfo cursorAnchorInfo = builder.build();

        assertThat(cursorAnchorInfo.getCharacterBounds(2)).isEqualTo(
                new RectF(0.0f, layout.getLineTop(1), 50.0f, layout.getLineBottom(1)));
        assertThat(cursorAnchorInfo.getCharacterBounds(3)).isEqualTo(
                new RectF(50.0f, layout.getLineTop(1), 60.0f, layout.getLineBottom(1)));

        assertThat(cursorAnchorInfo.getCharacterBoundsFlags(2)).isEqualTo(FLAG_HAS_VISIBLE_REGION);
        assertThat(cursorAnchorInfo.getCharacterBoundsFlags(3)).isEqualTo(FLAG_HAS_VISIBLE_REGION);
    }

    private static void assertCharacterBounds(RectF[] expected,
            CursorAnchorInfo cursorAnchorInfo) {
        final RectF[] characterBounds = new RectF[expected.length];
        for (int i = 0; i < expected.length; ++i) {
            characterBounds[i] = cursorAnchorInfo.getCharacterBounds(i);
        }
        assertArrayEquals(expected, characterBounds);
    }

    private static void assertCharacterBoundsFlags(int[] expected,
            CursorAnchorInfo cursorAnchorInfo) {
        final int[] characterBoundsFlags = new int[expected.length];
        for (int i = 0; i < expected.length; ++i) {
            characterBoundsFlags[i] = cursorAnchorInfo.getCharacterBoundsFlags(i);
        }
        assertArrayEquals(expected, characterBoundsFlags);
    }

}
