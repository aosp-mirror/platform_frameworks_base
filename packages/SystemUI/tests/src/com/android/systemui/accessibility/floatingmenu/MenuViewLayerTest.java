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

package com.android.systemui.accessibility.floatingmenu;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.android.systemui.accessibility.floatingmenu.MenuViewLayer.LayerIndex;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link MenuViewLayer}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuViewLayerTest extends SysuiTestCase {
    private MenuViewLayer mMenuViewLayer;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private IAccessibilityFloatingMenu mFloatingMenu;

    @Before
    public void setUp() throws Exception {
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        final AccessibilityManager stubAccessibilityManager = mContext.getSystemService(
                AccessibilityManager.class);
        mMenuViewLayer = new MenuViewLayer(mContext, stubWindowManager, stubAccessibilityManager,
                mFloatingMenu);
    }

    @Test
    public void onAttachedToWindow_menuIsVisible() {
        mMenuViewLayer.onAttachedToWindow();
        final View menuView = mMenuViewLayer.getChildAt(LayerIndex.MENU_VIEW);

        assertThat(menuView.getVisibility()).isEqualTo(VISIBLE);
    }

    @Test
    public void onAttachedToWindow_menuIsGone() {
        mMenuViewLayer.onDetachedFromWindow();
        final View menuView = mMenuViewLayer.getChildAt(LayerIndex.MENU_VIEW);

        assertThat(menuView.getVisibility()).isEqualTo(GONE);
    }

    @Test
    public void tiggerDismissMenuAction_hideFloatingMenu() {
        mMenuViewLayer.mDismissMenuAction.run();

        verify(mFloatingMenu).hide();
    }
}
