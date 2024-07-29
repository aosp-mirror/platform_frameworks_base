/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class AbsSeekBarTest {

    public static final int PADDING = 10;
    private Context mContext;
    private AbsSeekBar mBar;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mBar = new SeekBar(mContext);
    }

    @Test
    public void testExclusionForThumb_limitedTo48dp() {
        mBar.setPadding(PADDING, PADDING, PADDING, PADDING);
        mBar.setThumb(newThumb(dpToPxSize(20)));
        mBar.setMin(0);
        mBar.setMax(100);
        mBar.setProgress(50);

        final int thumbOffset = mBar.getThumbOffset();

        measureAndLayout(dpToPxSize(200), dpToPxSize(100));
        List<Rect> exclusions = mBar.getSystemGestureExclusionRects();

        assertEquals("exclusions should be size 1, but was " + exclusions, 1, exclusions.size());
        assertEquals("exclusion should be centered on thumb",
                center(offset(mBar.getThumb().getBounds(), PADDING - thumbOffset, PADDING)),
                center(exclusions.get(0)));
        assertEquals("exclusion should be 48dp high", dpToPxSize(48), exclusions.get(0).height());
        assertEquals("exclusion should be 48dp wide", dpToPxSize(48), exclusions.get(0).width());
    }

    @Test
    public void testExclusionForThumb_limitedToHeight() {
        mBar.setPadding(PADDING, PADDING, PADDING, PADDING);
        mBar.setThumb(newThumb(dpToPxSize(20)));
        mBar.setMin(0);
        mBar.setMax(100);
        mBar.setProgress(50);

        final int thumbOffset = mBar.getThumbOffset();

        measureAndLayout(dpToPxSize(200), dpToPxSize(32));
        List<Rect> exclusions = mBar.getSystemGestureExclusionRects();

        assertEquals("exclusions should be size 1, but was " + exclusions, 1, exclusions.size());
        assertEquals("exclusion should be centered on thumb",
                center(offset(mBar.getThumb().getBounds(), PADDING - thumbOffset, PADDING)),
                center(exclusions.get(0)));
        assertEquals("exclusion should be 32dp high", dpToPxSize(32), exclusions.get(0).height());
        assertEquals("exclusion should be 32dp wide", dpToPxSize(32), exclusions.get(0).width());
    }

    @Test
    public void testExclusionForThumb_passesThroughUserExclusions() {
        mBar.setSystemGestureExclusionRects(Arrays.asList(new Rect(1, 2, 3, 4)));

        mBar.setPadding(PADDING, PADDING, PADDING, PADDING);
        mBar.setThumb(newThumb(dpToPxSize(20)));
        mBar.setMin(0);
        mBar.setMax(100);
        mBar.setProgress(50);
        measureAndLayout(dpToPxSize(200), dpToPxSize(32));

        assertThat(mBar.getSystemGestureExclusionRects(), hasItem(new Rect(1, 2, 3, 4)));
        assertThat(mBar.getSystemGestureExclusionRects(), hasSize(2));

        mBar.setSystemGestureExclusionRects(Arrays.asList(new Rect(3, 4, 5, 6)));
        assertThat(mBar.getSystemGestureExclusionRects(), hasItem(new Rect(3, 4, 5, 6)));
        assertThat(mBar.getSystemGestureExclusionRects(), hasSize(2));
    }

    @Test
    public void testGrowRectTo_evenInitialDifference() {
        doGrowRectTest(new Rect(0, 0, 0, 0), 10, new Rect(-5, -5, 5, 5));
    }

    @Test
    public void testGrowRectTo_unevenInitialDifference() {
        doGrowRectTest(new Rect(0, 0, 1, 1), 10, new Rect(-5, -5, 5, 5));
    }

    @Test
    public void testGrowRectTo_unevenInitialDifference_unevenSize() {
        doGrowRectTest(new Rect(0, 0, 0, 0), 9, new Rect(-5, -5, 4, 4));
    }

    public void doGrowRectTest(Rect in, int minimumSize, Rect expected) {
        Rect result = new Rect(in);
        mBar.growRectTo(result, minimumSize);

        assertEquals("grown rect", expected, result);
        assertEquals("grown rect center point", center(expected), center(result));
    }

    private Point center(Rect rect) {
        return new Point(rect.centerX(), rect.centerY());
    }

    private Rect offset(Rect rect, int dx, int dy) {
        Rect result = new Rect(rect);
        result.offset(dx, dy);
        return result;
    }

    private ShapeDrawable newThumb(int size) {
        final ShapeDrawable thumb = new ShapeDrawable(new RectShape());
        thumb.setIntrinsicWidth(size);
        thumb.setIntrinsicHeight(size);
        return thumb;
    }

    private void measureAndLayout(int wPx, int hPx) {
        mBar.measure(makeMeasureSpec(wPx, EXACTLY), makeMeasureSpec(hPx, EXACTLY));
        mBar.layout(0, 0, wPx, hPx);
    }

    private int dpToPxSize(int dp) {
        return (int) (mContext.getResources().getDisplayMetrics().density * dp + 0.5f);
    }
}
