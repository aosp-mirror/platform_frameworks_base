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

package com.android.systemui.globalactions;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.leak.RotationUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ListGridLayout}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class GlobalActionsGridLayoutTest extends SysuiTestCase {

    private GlobalActionsGridLayout mGridLayout;
    private ListGridLayout mListGrid;

    @Before
    public void setUp() throws Exception {
        mGridLayout = spy(LayoutInflater.from(mContext)
                .inflate(R.layout.global_actions_grid, null)
                .requireViewById(R.id.global_actions_view));
        mListGrid = spy(mGridLayout.getListView());
        doReturn(mListGrid).when(mGridLayout).getListView();
    }

    @Test
    public void testShouldSwapRowsAndColumns() {
        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldSwapRowsAndColumns());

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldSwapRowsAndColumns());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldSwapRowsAndColumns());
    }

    @Test
    public void testShouldReverseListItems() {
        doReturn(View.LAYOUT_DIRECTION_LTR).when(mGridLayout).getCurrentLayoutDirection();

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldReverseListItems());

        doReturn(View.LAYOUT_DIRECTION_RTL).when(mGridLayout).getCurrentLayoutDirection();

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseListItems());
    }

    @Test
    public void testShouldReverseSublists() {
        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseSublists());

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(false, mGridLayout.shouldReverseSublists());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(true, mGridLayout.shouldReverseSublists());
    }

    @Test
    public void testGetAnimationOffsetX() {
        doReturn(50f).when(mGridLayout).getAnimationDistance();

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(0f, mGridLayout.getAnimationOffsetX(), .01);

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(50f, mGridLayout.getAnimationOffsetX(), .01);

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(-50f, mGridLayout.getAnimationOffsetX(), .01);
    }

    @Test
    public void testGetAnimationOffsetY() {
        doReturn(50f).when(mGridLayout).getAnimationDistance();

        doReturn(RotationUtils.ROTATION_NONE).when(mGridLayout).getCurrentRotation();
        assertEquals(50f, mGridLayout.getAnimationOffsetY(), .01);

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(0f, mGridLayout.getAnimationOffsetY(), .01);

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mGridLayout).getCurrentRotation();
        assertEquals(0f, mGridLayout.getAnimationOffsetY(), .01);
    }

    @Test
    public void testUpdateSeparatedItemSize() {
        View firstView = new View(mContext, null);
        View secondView = new View(mContext, null);

        ViewGroup separatedView = mGridLayout.getSeparatedView();
        separatedView.addView(firstView);

        mGridLayout.updateSeparatedItemSize();

        ViewGroup.LayoutParams childParams = firstView.getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, childParams.width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, childParams.height);

        separatedView.addView(secondView);

        mGridLayout.updateSeparatedItemSize();

        childParams = firstView.getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, childParams.width);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, childParams.height);
    }
}
