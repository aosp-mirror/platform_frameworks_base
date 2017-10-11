package com.android.settingslib.graph;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import com.android.settingslib.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.google.common.truth.Truth.assertThat;
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
}
