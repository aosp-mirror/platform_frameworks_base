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

import static android.app.UiModeManager.MODE_NIGHT_YES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.UiModeManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MenuView}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuViewTest extends SysuiTestCase {
    private static final int INDEX_MENU_ITEM = 0;
    private int mNightMode;
    private UiModeManager mUiModeManager;
    private MenuView mMenuView;

    @Before
    public void setUp() throws Exception {
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mNightMode = mUiModeManager.getNightMode();
        mUiModeManager.setNightMode(MODE_NIGHT_YES);
        final MenuViewModel stubMenuViewModel = new MenuViewModel(mContext);
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        final MenuViewAppearance stubMenuViewAppearance = new MenuViewAppearance(mContext,
                stubWindowManager);
        mMenuView = spy(new MenuView(mContext, stubMenuViewModel, stubMenuViewAppearance));
    }

    @Test
    public void onConfigurationChanged_updateViewModel() {
        mMenuView.onConfigurationChanged(/* newConfig= */ null);

        verify(mMenuView).loadLayoutResources();
    }

    @Test
    public void insetsOnDarkTheme_menuOnLeft_matchInsets() {
        mMenuView.onConfigurationChanged(/* newConfig= */ null);
        final InstantInsetLayerDrawable insetLayerDrawable =
                (InstantInsetLayerDrawable) mMenuView.getBackground();
        final boolean areInsetsMatched = insetLayerDrawable.getLayerInsetLeft(INDEX_MENU_ITEM) != 0
                && insetLayerDrawable.getLayerInsetRight(INDEX_MENU_ITEM) == 0;

        assertThat(areInsetsMatched).isTrue();
    }

    @After
    public void tearDown() throws Exception {
        mUiModeManager.setNightMode(mNightMode);
    }
}
