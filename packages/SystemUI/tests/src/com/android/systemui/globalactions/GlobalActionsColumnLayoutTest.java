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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;

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
public class GlobalActionsColumnLayoutTest extends SysuiTestCase {

    private GlobalActionsColumnLayout mColumnLayout;

    @Before
    public void setUp() throws Exception {
        mColumnLayout = spy((GlobalActionsColumnLayout)
                LayoutInflater.from(mContext).inflate(R.layout.global_actions_column, null));
    }

    @Test
    public void testShouldReverseListItems() {
        doReturn(View.LAYOUT_DIRECTION_LTR).when(mColumnLayout).getCurrentLayoutDirection();

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mColumnLayout).getCurrentRotation();
        assertEquals(false, mColumnLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_NONE).when(mColumnLayout).getCurrentRotation();
        assertEquals(false, mColumnLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mColumnLayout).getCurrentRotation();
        assertEquals(true, mColumnLayout.shouldReverseListItems());

        doReturn(View.LAYOUT_DIRECTION_RTL).when(mColumnLayout).getCurrentLayoutDirection();

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mColumnLayout).getCurrentRotation();
        assertEquals(true, mColumnLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_NONE).when(mColumnLayout).getCurrentRotation();
        assertEquals(false, mColumnLayout.shouldReverseListItems());

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mColumnLayout).getCurrentRotation();
        assertEquals(false, mColumnLayout.shouldReverseListItems());
    }

    @Test
    public void testGetAnimationOffsetX() {
        doReturn(50f).when(mColumnLayout).getAnimationDistance();

        doReturn(RotationUtils.ROTATION_NONE).when(mColumnLayout).getCurrentRotation();
        assertEquals(50f, mColumnLayout.getAnimationOffsetX(), .01);

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mColumnLayout).getCurrentRotation();
        assertEquals(0, mColumnLayout.getAnimationOffsetX(), .01);

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mColumnLayout).getCurrentRotation();
        assertEquals(0, mColumnLayout.getAnimationOffsetX(), .01);
    }

    @Test
    public void testGetAnimationOffsetY() {
        doReturn(50f).when(mColumnLayout).getAnimationDistance();

        doReturn(RotationUtils.ROTATION_NONE).when(mColumnLayout).getCurrentRotation();
        assertEquals(0, mColumnLayout.getAnimationOffsetY(), .01);

        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mColumnLayout).getCurrentRotation();
        assertEquals(-50f, mColumnLayout.getAnimationOffsetY(), .01);

        doReturn(RotationUtils.ROTATION_SEASCAPE).when(mColumnLayout).getCurrentRotation();
        assertEquals(50f, mColumnLayout.getAnimationOffsetY(), .01);
    }

    @Test
    public void testSnapToPowerButton_portrait() {
        doReturn(RotationUtils.ROTATION_NONE).when(mColumnLayout).getCurrentRotation();
        doReturn(50).when(mColumnLayout).getPowerButtonOffsetDistance();

        mColumnLayout.snapToPowerButton();
        assertEquals(Gravity.TOP | Gravity.RIGHT, mColumnLayout.getGravity());
        assertEquals(50, mColumnLayout.getPaddingTop(), .01);
    }

    @Test
    public void testCenterAlongEdge_portrait() {
        doReturn(RotationUtils.ROTATION_NONE).when(mColumnLayout).getCurrentRotation();

        mColumnLayout.centerAlongEdge();
        assertEquals(Gravity.CENTER_VERTICAL | Gravity.RIGHT, mColumnLayout.getGravity());
        assertEquals(0, mColumnLayout.getPaddingTop(), .01);
    }

    @Test
    public void testUpdateSnap_initialState() {
        doReturn(false).when(mColumnLayout).shouldSnapToPowerButton();

        mColumnLayout.updateSnap(); // should do nothing, since snap has not changed from init state

        verify(mColumnLayout, times(0)).snapToPowerButton();
        verify(mColumnLayout, times(0)).centerAlongEdge();
    }

    @Test
    public void testUpdateSnap_snapThenSnap() {
        doReturn(true).when(mColumnLayout).shouldSnapToPowerButton();

        mColumnLayout.updateSnap(); // should snap to power button

        verify(mColumnLayout, times(1)).snapToPowerButton();
        verify(mColumnLayout, times(0)).centerAlongEdge();

        mColumnLayout.updateSnap(); // should do nothing, since this is the same state as last time

        verify(mColumnLayout, times(1)).snapToPowerButton();
        verify(mColumnLayout, times(0)).centerAlongEdge();
    }

    @Test
    public void testUpdateSnap_snapThenCenter() {
        doReturn(true).when(mColumnLayout).shouldSnapToPowerButton();

        mColumnLayout.updateSnap(); // should snap to power button

        verify(mColumnLayout, times(1)).snapToPowerButton();
        verify(mColumnLayout, times(0)).centerAlongEdge();

        doReturn(false).when(mColumnLayout).shouldSnapToPowerButton();

        mColumnLayout.updateSnap(); // should center to edge

        verify(mColumnLayout, times(1)).snapToPowerButton();
        verify(mColumnLayout, times(1)).centerAlongEdge();
    }

    @Test
    public void testShouldSnapToPowerButton_vertical() {
        doReturn(RotationUtils.ROTATION_NONE).when(mColumnLayout).getCurrentRotation();
        doReturn(300).when(mColumnLayout).getPowerButtonOffsetDistance();
        doReturn(1000).when(mColumnLayout).getMeasuredHeight();
        View wrapper = spy(new View(mContext, null));
        doReturn(wrapper).when(mColumnLayout).getWrapper();
        doReturn(500).when(wrapper).getMeasuredHeight();

        assertEquals(true, mColumnLayout.shouldSnapToPowerButton());

        doReturn(600).when(mColumnLayout).getMeasuredHeight();

        assertEquals(false, mColumnLayout.shouldSnapToPowerButton());
    }

    @Test
    public void testShouldSnapToPowerButton_horizontal() {
        doReturn(RotationUtils.ROTATION_LANDSCAPE).when(mColumnLayout).getCurrentRotation();
        doReturn(300).when(mColumnLayout).getPowerButtonOffsetDistance();
        doReturn(1000).when(mColumnLayout).getMeasuredWidth();
        View wrapper = spy(new View(mContext, null));
        doReturn(wrapper).when(mColumnLayout).getWrapper();
        doReturn(500).when(wrapper).getMeasuredWidth();

        assertEquals(true, mColumnLayout.shouldSnapToPowerButton());

        doReturn(600).when(mColumnLayout).getMeasuredWidth();

        assertEquals(false, mColumnLayout.shouldSnapToPowerButton());
    }
}
