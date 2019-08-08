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
import android.view.View;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class AbsSeekBarTest {

    private Context mContext;
    private AbsSeekBar mBar;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mBar = new SeekBar(mContext);
    }

    @Test
    public void testExclusionForThumb_limitedTo48dp() {
        mBar.setPadding(10, 10, 10, 10);
        mBar.setThumb(newThumb(dpToPx(20)));
        mBar.setMin(0);
        mBar.setMax(100);
        mBar.setProgress(50);
        measureAndLayout(dpToPx(200), dpToPx(100));
        List<Rect> exclusions = mBar.getSystemGestureExclusionRects();

        assertEquals("exclusions should be size 1, but was " + exclusions, 1, exclusions.size());
        assertEquals("exclusion should be centered on thumb",
                center(mBar), center(exclusions.get(0)));
        assertEquals("exclusion should be 48dp high", dpToPx(48), exclusions.get(0).height());
        assertEquals("exclusion should be 48dp wide", dpToPx(48), exclusions.get(0).width());
    }

    @Test
    public void testExclusionForThumb_limitedToHeight() {
        mBar.setPadding(10, 10, 10, 10);
        mBar.setThumb(newThumb(dpToPx(20)));
        mBar.setMin(0);
        mBar.setMax(100);
        mBar.setProgress(50);
        measureAndLayout(dpToPx(200), dpToPx(32));
        List<Rect> exclusions = mBar.getSystemGestureExclusionRects();

        assertEquals("exclusions should be size 1, but was " + exclusions, 1, exclusions.size());
        assertEquals("exclusion should be centered on thumb",
                center(mBar), center(exclusions.get(0)));
        assertEquals("exclusion should be 32dp high", dpToPx(32), exclusions.get(0).height());
        assertEquals("exclusion should be 32dp wide", dpToPx(32), exclusions.get(0).width());
    }

    @Test
    public void testExclusionForThumb_passesThroughUserExclusions() {
        mBar.setSystemGestureExclusionRects(Arrays.asList(new Rect(1, 2, 3, 4)));

        mBar.setPadding(10, 10, 10, 10);
        mBar.setThumb(newThumb(dpToPx(20)));
        mBar.setMin(0);
        mBar.setMax(100);
        mBar.setProgress(50);
        measureAndLayout(dpToPx(200), dpToPx(32));

        assertThat(mBar.getSystemGestureExclusionRects(), hasItem(new Rect(1, 2, 3, 4)));
        assertThat(mBar.getSystemGestureExclusionRects(), hasSize(2));

        mBar.setSystemGestureExclusionRects(Arrays.asList(new Rect(3, 4, 5, 6)));
        assertThat(mBar.getSystemGestureExclusionRects(), hasItem(new Rect(3, 4, 5, 6)));
        assertThat(mBar.getSystemGestureExclusionRects(), hasSize(2));
    }

    private Point center(Rect rect) {
        return new Point(rect.centerX(), rect.centerY());
    }

    private Point center(View view) {
        return center(new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
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

    private int dpToPx(int dp) {
        return (int) (mContext.getResources().getDisplayMetrics().density * dp);
    }
}
