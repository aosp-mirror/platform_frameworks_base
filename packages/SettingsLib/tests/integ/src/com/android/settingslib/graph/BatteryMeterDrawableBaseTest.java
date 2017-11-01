package com.android.settingslib.graph;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import com.android.settingslib.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.google.common.truth.Truth.assertThat;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyFloat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryMeterDrawableBaseTest {
    private Context mContext;
    private Resources mResources;
    private BatteryMeterDrawableBase mBatteryDrawable;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mResources = mContext.getResources();
        mBatteryDrawable = new BatteryMeterDrawableBase(mContext, 0);
    }

    @Test
    public void testGetIntrinsicSize() {
        assertThat(mBatteryDrawable.getIntrinsicWidth()).
                isEqualTo(mResources.getDimensionPixelSize(R.dimen.battery_width));
        assertThat(mBatteryDrawable.getIntrinsicHeight()).
                isEqualTo(mResources.getDimensionPixelSize(R.dimen.battery_height));
    }

    @Test
    public void testDrawNothingBeforeOnBatteryLevelChanged() {
        final Canvas canvas = mock(Canvas.class);
        mBatteryDrawable.draw(canvas);
        verify(canvas, never()).drawPath(any(), any());
        verify(canvas, never()).drawText(anyString(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void testDrawingForTypicalValues() {
        final Canvas canvas = mock(Canvas.class);
        final int levels[] = { 0, 1, 5, 10, 25, 50, 75, 90, 95, 99, 100 };
        final boolean bools[] = { false, true };
        for (int l : levels) {
            for (boolean charging : bools) {
                for (boolean saver : bools) {
                    for (boolean percent : bools) {
                        mBatteryDrawable.setBatteryLevel(l);
                        mBatteryDrawable.setPowerSave(saver);
                        mBatteryDrawable.setCharging(charging);
                        mBatteryDrawable.setShowPercent(percent);
                        mBatteryDrawable.draw(canvas);
                    }
                }
            }
        }
    }

    @Test
    public void testPadding_returnsCorrectValues() {
        // different pads on each side to differentiate
        final int left = 1;
        final int top = 2;
        final int right = 3;
        final int bottom = 4;

        final Rect expected = new Rect(left, top, right, bottom);
        final Rect padding = new Rect();

        mBatteryDrawable.setPadding(left, top, right, bottom);

        assertThat(mBatteryDrawable.getPadding(padding)).isEqualTo(true);
        assertThat(padding).isEqualTo(expected);
    }

    @Test
    public void testPadding_falseIfUnsetOrZero() {
        final Rect padding = new Rect();
        assertThat(mBatteryDrawable.getPadding(padding)).isEqualTo(false);
        assertThat(isRectZero(padding)).isEqualTo(true);

        mBatteryDrawable.setPadding(0, 0, 0, 0);
        assertThat(mBatteryDrawable.getPadding(padding)).isEqualTo(false);
        assertThat(isRectZero(padding)).isEqualTo(true);
    }

    private boolean isRectZero(Rect r) {
        return r.left == 0 && r.top == 0 && r.right == 0 && r.bottom == 0;
    }

    @Test
    public void testPlusPaint_isEqualToBoltPaint() {
        // Before setting color
        assertTrue(mBatteryDrawable.mPlusPaint.hasEqualAttributes(mBatteryDrawable.mBoltPaint));

        final int fakeFillColor = 123;
        final int fakeBackgrundColor = 456;

        // After
        mBatteryDrawable.setColors(fakeFillColor, fakeBackgrundColor);
        assertTrue(mBatteryDrawable.mPlusPaint.hasEqualAttributes(mBatteryDrawable.mBoltPaint));
    }
}
