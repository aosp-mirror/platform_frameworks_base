package com.android.wm.shell.recents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.wm.shell.util.StagedSplitBounds;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StagedSplitBoundsTest {
    private static final int DEVICE_WIDTH = 100;
    private static final int DEVICE_LENGTH = 200;
    private static final int DIVIDER_SIZE = 20;
    private static final int TASK_ID_1 = 4;
    private static final int TASK_ID_2 = 9;

    // Bounds in screen space
    private final Rect mTopRect = new Rect();
    private final Rect mBottomRect = new Rect();
    private final Rect mLeftRect = new Rect();
    private final Rect mRightRect = new Rect();

    @Before
    public void setup() {
        mTopRect.set(0, 0, DEVICE_WIDTH, DEVICE_LENGTH / 2 - DIVIDER_SIZE / 2);
        mBottomRect.set(0, DEVICE_LENGTH / 2 + DIVIDER_SIZE / 2,
                DEVICE_WIDTH, DEVICE_LENGTH);
        mLeftRect.set(0, 0, DEVICE_WIDTH / 2 - DIVIDER_SIZE / 2, DEVICE_LENGTH);
        mRightRect.set(DEVICE_WIDTH / 2 + DIVIDER_SIZE / 2, 0,
                DEVICE_WIDTH, DEVICE_LENGTH);
    }

    @Test
    public void testVerticalStacked() {
        StagedSplitBounds ssb = new StagedSplitBounds(mTopRect, mBottomRect,
                TASK_ID_1, TASK_ID_2);
        assertTrue(ssb.appsStackedVertically);
    }

    @Test
    public void testHorizontalStacked() {
        StagedSplitBounds ssb = new StagedSplitBounds(mLeftRect, mRightRect,
                TASK_ID_1, TASK_ID_2);
        assertFalse(ssb.appsStackedVertically);
    }

    @Test
    public void testHorizontalDividerBounds() {
        StagedSplitBounds ssb = new StagedSplitBounds(mTopRect, mBottomRect,
                TASK_ID_1, TASK_ID_2);
        Rect dividerBounds = ssb.visualDividerBounds;
        assertEquals(0, dividerBounds.left);
        assertEquals(DEVICE_LENGTH / 2 - DIVIDER_SIZE / 2, dividerBounds.top);
        assertEquals(DEVICE_WIDTH, dividerBounds.right);
        assertEquals(DEVICE_LENGTH / 2 + DIVIDER_SIZE / 2, dividerBounds.bottom);
    }

    @Test
    public void testVerticalDividerBounds() {
        StagedSplitBounds ssb = new StagedSplitBounds(mLeftRect, mRightRect,
                TASK_ID_1, TASK_ID_2);
        Rect dividerBounds = ssb.visualDividerBounds;
        assertEquals(DEVICE_WIDTH / 2 - DIVIDER_SIZE / 2, dividerBounds.left);
        assertEquals(0, dividerBounds.top);
        assertEquals(DEVICE_WIDTH / 2 + DIVIDER_SIZE / 2, dividerBounds.right);
        assertEquals(DEVICE_LENGTH, dividerBounds.bottom);
    }

    @Test
    public void testEqualVerticalTaskPercent() {
        StagedSplitBounds ssb = new StagedSplitBounds(mTopRect, mBottomRect,
                TASK_ID_1, TASK_ID_2);
        float topPercentSpaceTaken = (float) (DEVICE_LENGTH / 2 - DIVIDER_SIZE / 2) / DEVICE_LENGTH;
        assertEquals(topPercentSpaceTaken, ssb.topTaskPercent, 0.01);
    }

    @Test
    public void testEqualHorizontalTaskPercent() {
        StagedSplitBounds ssb = new StagedSplitBounds(mLeftRect, mRightRect,
                TASK_ID_1, TASK_ID_2);
        float leftPercentSpaceTaken = (float) (DEVICE_WIDTH / 2 - DIVIDER_SIZE / 2) / DEVICE_WIDTH;
        assertEquals(leftPercentSpaceTaken, ssb.leftTaskPercent, 0.01);
    }
}
