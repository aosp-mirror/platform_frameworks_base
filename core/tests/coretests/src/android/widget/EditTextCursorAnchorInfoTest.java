/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorBoundsInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class EditTextCursorAnchorInfoTest {
    private static final CursorAnchorInfo.Builder sCursorAnchorInfoBuilder =
            new CursorAnchorInfo.Builder();
    private static final Matrix sMatrix = new Matrix();
    private static final int[] sLocationOnScreen = new int[2];
    private static Typeface sTypeface;
    private static final float TEXT_SIZE = 1f;
    // The line height of the test font is 1.2 * textSize.
    private static final int LINE_HEIGHT = 12;
    private static final int HW_BOUNDS_OFFSET_LEFT = 10;
    private static final int HW_BOUNDS_OFFSET_TOP = 20;
    private static final int HW_BOUNDS_OFFSET_RIGHT = 30;
    private static final int HW_BOUNDS_OFFSET_BOTTOM = 40;


    // Default text has 5 lines of text. The needed width is 50px and the needed height is 60px.
    private static final CharSequence DEFAULT_TEXT = "X\nXX\nXXX\nXXXX\nXXXXX";
    private static final ImmutableList<RectF> DEFAULT_LINE_BOUNDS = ImmutableList.of(
            new RectF(0f, 0f, 10f, LINE_HEIGHT),
            new RectF(0f, LINE_HEIGHT, 20f, 2 * LINE_HEIGHT),
            new RectF(0f, 2 * LINE_HEIGHT, 30f, 3 * LINE_HEIGHT),
            new RectF(0f, 3 * LINE_HEIGHT, 40f, 4 * LINE_HEIGHT),
            new RectF(0f, 4 * LINE_HEIGHT, 50f, 5 * LINE_HEIGHT));

    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);
    private Activity mActivity;
    private TextView mEditText;

    @BeforeClass
    public static void setupClass() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

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
        sTypeface = Typeface.createFromAsset(instrumentation.getTargetContext().getAssets(),
                "fonts/StaticLayoutLineBreakingTestFont.ttf");
    }

    @Before
    public void setup() {
        mActivity = mActivityRule.getActivity();
    }

    @Test
    public void testMatrix() {
        setupEditText("", /* height= */ 100);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        Matrix actualMatrix = cursorAnchorInfo.getMatrix();
        Matrix expectedMatrix = new Matrix();
        expectedMatrix.setTranslate(sLocationOnScreen[0], sLocationOnScreen[1]);

        assertThat(actualMatrix).isEqualTo(expectedMatrix);
    }

    @Test
    public void testMatrix_withTranslation() {
        float translationX = 10f;
        float translationY = 20f;
        createEditText("");
        mEditText.setTranslationX(translationX);
        mEditText.setTranslationY(translationY);
        measureEditText(100);

        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        Matrix actualMatrix = cursorAnchorInfo.getMatrix();
        Matrix expectedMatrix = new Matrix();
        expectedMatrix.setTranslate(sLocationOnScreen[0] + translationX,
                sLocationOnScreen[1] + translationY);

        assertThat(actualMatrix).isEqualTo(expectedMatrix);
    }

    @Test
    public void testEditorBoundsInfo_allVisible() {
        // The needed width and height of the DEFAULT_TEXT are 50 px and 60 px respectfully.
        int width = 100;
        int height = 200;
        setupEditText(DEFAULT_TEXT, width, height);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);
        EditorBoundsInfo editorBoundsInfo = cursorAnchorInfo.getEditorBoundsInfo();
        assertThat(editorBoundsInfo).isNotNull();
        assertThat(editorBoundsInfo.getEditorBounds()).isEqualTo(new RectF(0, 0, width, height));
        assertThat(editorBoundsInfo.getHandwritingBounds())
                .isEqualTo(new RectF(-HW_BOUNDS_OFFSET_LEFT, -HW_BOUNDS_OFFSET_TOP,
                        width + HW_BOUNDS_OFFSET_RIGHT, height + HW_BOUNDS_OFFSET_BOTTOM));
    }

    @Test
    public void testEditorBoundsInfo_scrolled() {
        // The height of the editor will be 60 px.
        int width = 100;
        int visibleTop = 10;
        int visibleBottom = 30;
        setupVerticalClippedEditText(width, visibleTop, visibleBottom);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);
        EditorBoundsInfo editorBoundsInfo = cursorAnchorInfo.getEditorBoundsInfo();
        assertThat(editorBoundsInfo).isNotNull();
        assertThat(editorBoundsInfo.getEditorBounds())
                .isEqualTo(new RectF(0, visibleTop, width, visibleBottom));
        assertThat(editorBoundsInfo.getHandwritingBounds())
                .isEqualTo(new RectF(-HW_BOUNDS_OFFSET_LEFT, visibleTop - HW_BOUNDS_OFFSET_TOP,
                        width + HW_BOUNDS_OFFSET_RIGHT, visibleBottom + HW_BOUNDS_OFFSET_BOTTOM));
    }

    @Test
    public void testEditorBoundsInfo_invisible() {
        // The height of the editor will be 60px. Scroll it to 70px will make it invisible.
        int width = 100;
        int visibleTop = 70;
        int visibleBottom = 70;
        setupVerticalClippedEditText(width, visibleTop, visibleBottom);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);
        EditorBoundsInfo editorBoundsInfo = cursorAnchorInfo.getEditorBoundsInfo();
        assertThat(editorBoundsInfo).isNotNull();
        assertThat(editorBoundsInfo.getEditorBounds()).isEqualTo(new RectF(0, 0, 0, 0));
        assertThat(editorBoundsInfo.getHandwritingBounds()).isEqualTo(new RectF(0, 0, 0, 0));
    }

    @Test
    public void testVisibleLineBounds_allVisible() {
        setupEditText(DEFAULT_TEXT, /* height= */ 100);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        assertThat(lineBounds).isEqualTo(DEFAULT_LINE_BOUNDS);
    }

    @Test
    public void testVisibleLineBounds_allVisible_withLineSpacing() {
        float lineSpacing = 10f;
        setupEditText("X\nXX\nXXX", /* height= */ 100, lineSpacing,
                /* lineMultiplier=*/ 1f);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        assertThat(lineBounds.size()).isEqualTo(3);
        assertThat(lineBounds.get(0)).isEqualTo(new RectF(0f, 0f, 10f, LINE_HEIGHT));

        float line1Top = LINE_HEIGHT + lineSpacing;
        float line1Bottom = line1Top + LINE_HEIGHT;
        assertThat(lineBounds.get(1)).isEqualTo(new RectF(0f, line1Top, 20f, line1Bottom));

        float line2Top = 2 * (LINE_HEIGHT + lineSpacing);
        float line2Bottom = line2Top + LINE_HEIGHT;
        assertThat(lineBounds.get(2)).isEqualTo(new RectF(0f, line2Top, 30f, line2Bottom));
    }

    @Test
    public void testVisibleLineBounds_allVisible_withLineMultiplier() {
        float lineMultiplier = 2f;
        setupEditText("X\nXX\nXXX", /* height= */ 100, /* lineSpacing= */ 0f,
                /* lineMultiplier=*/ lineMultiplier);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        assertThat(lineBounds.size()).isEqualTo(3);
        assertThat(lineBounds.get(0)).isEqualTo(new RectF(0f, 0f, 10f, LINE_HEIGHT));

        float line1Top = LINE_HEIGHT * lineMultiplier;
        float line1Bottom = line1Top + LINE_HEIGHT;
        assertThat(lineBounds.get(1)).isEqualTo(new RectF(0f, line1Top, 20f, line1Bottom));

        float line2Top = 2 * LINE_HEIGHT * lineMultiplier;
        float line2Bottom = line2Top + LINE_HEIGHT;
        assertThat(lineBounds.get(2)).isEqualTo(new RectF(0f, line2Top, 30f, line2Bottom));
    }

    @Test
    public void testVisibleLineBounds_cutBottomLines() {
        // Line top is inclusive and line bottom is exclusive. And if the visible area's
        // bottom equals to the line top, this line is still visible. So the line height is
        // 3 * LINE_HEIGHT - 1 to avoid including the line 3.
        setupEditText(DEFAULT_TEXT, /* height= */ 3 * LINE_HEIGHT - 1);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        assertThat(lineBounds).isEqualTo(DEFAULT_LINE_BOUNDS.subList(0, 3));
    }

    @Test
    public void testVisibleLineBounds_scrolled_cutTopLines() {
        // First 2 lines are cut.
        int scrollY = 2 * LINE_HEIGHT;
        setupEditText(/* height= */ 3 * LINE_HEIGHT,
                /* scrollY= */ scrollY);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 2, 5);
        expectedLineBounds.forEach(rectF -> rectF.offset(0, -scrollY));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_scrolled_cutTopAndBottomLines() {
        // Line top is inclusive and line bottom is exclusive. And if the visible area's
        // bottom equals to the line top, this line is still visible. So the line height is
        // 2 * LINE_HEIGHT - 1 which only shows 2 lines.
        int scrollY = 2 * LINE_HEIGHT;
        setupEditText(/* height= */ 2 * LINE_HEIGHT - 1,
                /* scrollY= */ scrollY);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 2, 4);
        expectedLineBounds.forEach(rectF -> rectF.offset(0, -scrollY));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_scrolled_partiallyVisibleLines() {
        // The first 2 lines are completely cut, line 2 and 3 are partially visible.
        int scrollY = 2 * LINE_HEIGHT + LINE_HEIGHT / 2;
        setupEditText(/* height= */ LINE_HEIGHT,
                /* scrollY= */ scrollY);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 2, 4);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, -scrollY));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_withCompoundDrawable_allVisible() {
        int topDrawableHeight = LINE_HEIGHT;
        Drawable topDrawable = createDrawable(topDrawableHeight);
        Drawable bottomDrawable = createDrawable(2 * LINE_HEIGHT);
        setupEditText(/* height= */ 100,
                /* scrollY= */ 0, topDrawable, bottomDrawable);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = copy(DEFAULT_LINE_BOUNDS);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, topDrawableHeight));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_withCompoundDrawable_cutBottomLines() {
        // The view's totally height is 5 * LINE_HEIGHT, and drawables take 3 * LINE_HEIGHT.
        // Only first 2 lines are visible.
        int topDrawableHeight = LINE_HEIGHT;
        Drawable topDrawable = createDrawable(topDrawableHeight);
        Drawable bottomDrawable = createDrawable(2 * LINE_HEIGHT + 1);
        setupEditText(/* height= */ 5 * LINE_HEIGHT,
                /* scrollY= */ 0, topDrawable, bottomDrawable);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 0, 2);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, topDrawableHeight));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_withCompoundDrawable_scrolled() {
        // The view's totally height is 5 * LINE_HEIGHT, and drawables take 3 * LINE_HEIGHT.
        // So 2 lines are visible. Because the view is scrolled vertically by LINE_HEIGHT,
        // the line 1 and 2 are visible.
        int topDrawableHeight = LINE_HEIGHT;
        Drawable topDrawable = createDrawable(topDrawableHeight);
        Drawable bottomDrawable = createDrawable(2 * LINE_HEIGHT + 1);
        int scrollY = LINE_HEIGHT;
        setupEditText(/* height= */ 5 * LINE_HEIGHT, scrollY,
                topDrawable, bottomDrawable);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 1, 3);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, topDrawableHeight - scrollY));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_withCompoundDrawable_partiallyVisible() {
        // The view's totally height is 5 * LINE_HEIGHT, and drawables take 3 * LINE_HEIGHT.
        // And because the view is scrolled vertically by 0.5 * LINE_HEIGHT,
        // the line 0, 1 and 2 are visible.
        int topDrawableHeight = LINE_HEIGHT;
        Drawable topDrawable = createDrawable(topDrawableHeight);
        Drawable bottomDrawable = createDrawable(2 * LINE_HEIGHT + 1);
        int scrollY = LINE_HEIGHT / 2;
        setupEditText(/* height= */ 5 * LINE_HEIGHT, scrollY,
                topDrawable, bottomDrawable);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 0, 3);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, topDrawableHeight - scrollY));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_withPaddings_allVisible() {
        int topPadding = LINE_HEIGHT;
        int bottomPadding = LINE_HEIGHT;
        setupEditText(/* height= */ 100, /* scrollY= */ 0, topPadding, bottomPadding);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = copy(DEFAULT_LINE_BOUNDS);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, topPadding));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_withPaddings_cutBottomLines() {
        // The view's totally height is 5 * LINE_HEIGHT, and paddings take 3 * LINE_HEIGHT.
        // So 2 lines are visible.
        int topPadding = LINE_HEIGHT;
        int bottomPadding = 2 * LINE_HEIGHT + 1;
        setupEditText(/* height= */ 5 * LINE_HEIGHT, /* scrollY= */ 0, topPadding, bottomPadding);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 0, 2);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, topPadding));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_withPaddings_scrolled() {
        // The view's totally height is 5 * LINE_HEIGHT, and paddings take 3 * LINE_HEIGHT.
        // So 2 lines are visible. Because the view is scrolled vertically by LINE_HEIGHT,
        // the line 1 and 2 are visible.
        int topPadding = LINE_HEIGHT;
        int bottomPadding = 2 * LINE_HEIGHT + 1;
        int scrollY = LINE_HEIGHT;
        setupEditText(/* height= */ 5 * LINE_HEIGHT, scrollY,
                topPadding, bottomPadding);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 1, 3);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, topPadding - scrollY));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_withPadding_partiallyVisible() {
        // The view's totally height is 5 * LINE_HEIGHT, and paddings take 3 * LINE_HEIGHT.
        // And because the view is scrolled vertically by 0.5 * LINE_HEIGHT, the line 0, 1 and 2
        // are visible.
        int topPadding = LINE_HEIGHT;
        int bottomPadding = 2 * LINE_HEIGHT + 1;
        int scrollY = LINE_HEIGHT / 2;
        setupEditText(/* height= */ 5 * LINE_HEIGHT, scrollY,
                topPadding, bottomPadding);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 0, 3);
        expectedLineBounds.forEach(rectF -> rectF.offset(0f, topPadding - scrollY));

        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_clippedTop() {
        // The first line is clipped off.
        setupVerticalClippedEditText(LINE_HEIGHT, 5 * LINE_HEIGHT);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 1, 5);
        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_clippedBottom() {
        // The last line is clipped off.
        setupVerticalClippedEditText(0, 4 * LINE_HEIGHT - 1);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 0, 4);
        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    @Test
    public void testVisibleLineBounds_clippedTopAndBottom() {
        // The first and last line are clipped off.
        setupVerticalClippedEditText(LINE_HEIGHT, 4 * LINE_HEIGHT - 1);
        CursorAnchorInfo cursorAnchorInfo =
                mEditText.getCursorAnchorInfo(0, sCursorAnchorInfoBuilder, sMatrix);

        List<RectF> lineBounds = cursorAnchorInfo.getVisibleLineBounds();

        List<RectF> expectedLineBounds = subList(DEFAULT_LINE_BOUNDS, 1, 4);
        assertThat(lineBounds).isEqualTo(expectedLineBounds);
    }

    private List<RectF> copy(List<RectF> rectFList) {
        List<RectF> result = new ArrayList<>();
        for (RectF rectF : rectFList) {
            result.add(new RectF(rectF));
        }
        return result;
    }
    private List<RectF> subList(List<RectF> rectFList, int start, int end) {
        List<RectF> result = new ArrayList<>();
        for (int index = start; index < end; ++index) {
            result.add(new RectF(rectFList.get(index)));
        }
        return result;
    }

    private void setupVerticalClippedEditText(int visibleTop, int visibleBottom) {
        setupVerticalClippedEditText(1000, visibleTop, visibleBottom);
    }

    /**
     * Helper method to create an EditText in a vertical ScrollView so that its visible bounds
     * is Rect(0, visibleTop, width, visibleBottom) in the EditText's coordinates. Both ScrollView
     * and EditText's width is set to the given width.
     */
    private void setupVerticalClippedEditText(int width, int visibleTop, int visibleBottom) {
        ScrollView scrollView = new ScrollView(mActivity);
        createEditText();
        int scrollViewHeight = visibleBottom - visibleTop;

        scrollView.addView(mEditText, new FrameLayout.LayoutParams(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(5 * LINE_HEIGHT, View.MeasureSpec.EXACTLY)));
        scrollView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(scrollViewHeight, View.MeasureSpec.EXACTLY));
        scrollView.layout(0, 0, width, scrollViewHeight);
        scrollView.scrollTo(0, visibleTop);
    }

    private void setupEditText(CharSequence text, int height) {
        createEditText(text);
        measureEditText(height);
    }

    private void setupEditText(CharSequence text, int width, int height) {
        createEditText(text);
        measureEditText(width, height);
    }

    private void setupEditText(CharSequence text, int height, float lineSpacing,
            float lineMultiplier) {
        createEditText(text);
        mEditText.setLineSpacing(lineSpacing, lineMultiplier);
        measureEditText(height);
    }

    private void setupEditText(int height, int scrollY) {
        createEditText();
        mEditText.scrollTo(0, scrollY);
        measureEditText(height);
    }

    private void setupEditText(int height, int scrollY, Drawable drawableTop,
            Drawable drawableBottom) {
        createEditText();
        mEditText.scrollTo(0, scrollY);
        mEditText.setCompoundDrawables(null, drawableTop, null, drawableBottom);
        measureEditText(height);
    }

    private void setupEditText(int height, int scrollY, int paddingTop,
            int paddingBottom) {
        createEditText();
        mEditText.scrollTo(0, scrollY);
        mEditText.setPadding(0, paddingTop, 0, paddingBottom);
        measureEditText(height);
    }

    private void createEditText() {
        createEditText(DEFAULT_TEXT);
    }

    private void createEditText(CharSequence text) {
        mEditText = new EditText(mActivity);
        mEditText.setTypeface(sTypeface);
        mEditText.setText(text);
        mEditText.setTextSize(TypedValue.COMPLEX_UNIT_PX, TEXT_SIZE);
        mEditText.setHandwritingBoundsOffsets(HW_BOUNDS_OFFSET_LEFT, HW_BOUNDS_OFFSET_TOP,
                HW_BOUNDS_OFFSET_RIGHT, HW_BOUNDS_OFFSET_BOTTOM);

        mEditText.setPadding(0, 0, 0, 0);
        mEditText.setCompoundDrawables(null, null, null, null);
        mEditText.setCompoundDrawablePadding(0);

        mEditText.scrollTo(0, 0);
        mEditText.setLineSpacing(0f, 1f);

        // Place the text layout top to the view's top.
        mEditText.setGravity(Gravity.TOP);
    }

    private void measureEditText(int height) {
        // width equals to 1000 is enough to avoid line break for all test cases.
        measureEditText(1000, height);
    }

    private void measureEditText(int width, int height) {
        mEditText.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        mEditText.layout(0, 0, width, height);

        mEditText.getLocationOnScreen(sLocationOnScreen);
    }

    private Drawable createDrawable(int height) {
        // width is not important for this drawable, make it 1 pixel.
        return createDrawable(1, height);
    }

    private Drawable createDrawable(int width, int height) {
        ShapeDrawable drawable = new ShapeDrawable();
        drawable.setBounds(new Rect(0, 0, width, height));
        return drawable;
    }
}
