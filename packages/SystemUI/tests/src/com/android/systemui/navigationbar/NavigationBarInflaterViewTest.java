/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.SparseArray;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.navigationbar.buttons.ButtonDispatcher;
import com.android.systemui.recents.OverviewProxyService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** atest NavigationBarInflaterViewTest */
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NavigationBarInflaterViewTest extends SysuiTestCase {

    private NavigationBarInflaterView mNavBarInflaterView;

    private static final int BUTTON_ID = 0;

    @Before
    public void setUp() {
        mDependency.injectMockDependency(AssistManager.class);
        mDependency.injectMockDependency(OverviewProxyService.class);
        mDependency.injectMockDependency(NavigationModeController.class);
        mDependency.injectMockDependency(NavigationBarController.class);

        mNavBarInflaterView = spy(new NavigationBarInflaterView(mContext, null));
        doNothing().when(mNavBarInflaterView).createInflaters();

        mNavBarInflaterView.mButtonDispatchers = new SparseArray<>(1);
        mNavBarInflaterView.mButtonDispatchers.put(BUTTON_ID, new ButtonDispatcher(BUTTON_ID));

        initializeViews();
    }

    private void initializeViews() {
        mNavBarInflaterView.mVertical = mock(FrameLayout.class);
        mNavBarInflaterView.mHorizontal = mock(FrameLayout.class);
        initializeLayout(mNavBarInflaterView.mVertical);
        initializeLayout(mNavBarInflaterView.mHorizontal);
    }

    private void initializeLayout(FrameLayout layout) {
        View verticalChildView = mock(View.class);
        verticalChildView.setId(BUTTON_ID);
        doReturn(layout).when(verticalChildView).getParent();
        doReturn(verticalChildView).when(layout).findViewById(BUTTON_ID);
    }

    @After
    public void tearDown() {
        mNavBarInflaterView = null;
    }

    @Test
    public void testUpdateButtonDispatchersCurrentView_isVerticalTrue() {
        mNavBarInflaterView.setVertical(true);

        mNavBarInflaterView.updateButtonDispatchersCurrentView();

        ButtonDispatcher button = mNavBarInflaterView.mButtonDispatchers.get(BUTTON_ID);
        assertEquals("Buttons need to be set to vertical layout",
                mNavBarInflaterView.mVertical.getId(),
                ((View) button.getCurrentView().getParent()).getId());
    }

    @Test
    public void testUpdateButtonDispatchersCurrentView_isVerticalFalse() {
        mNavBarInflaterView.setVertical(false);

        mNavBarInflaterView.updateButtonDispatchersCurrentView();

        ButtonDispatcher button = mNavBarInflaterView.mButtonDispatchers.get(BUTTON_ID);
        assertEquals("Buttons need to be set to horizon layout",
                mNavBarInflaterView.mHorizontal.getId(),
                ((View) button.getCurrentView().getParent()).getId());
    }
}
