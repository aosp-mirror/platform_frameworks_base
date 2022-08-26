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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link MenuViewLayerController}. */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class MenuViewLayerControllerTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private WindowManager mWindowManager;

    private MenuViewLayerController mMenuViewLayerController;

    @Before
    public void setUp() throws Exception {
        mMenuViewLayerController = new MenuViewLayerController(mContext, mWindowManager);
    }

    @Test
    public void show_shouldAddViewToWindow() {
        mMenuViewLayerController.show();

        verify(mWindowManager).addView(any(View.class), any(ViewGroup.LayoutParams.class));
    }

    @Test
    public void hide_menuIsShowing_removeViewFromWindow() {
        mMenuViewLayerController.show();

        mMenuViewLayerController.hide();

        verify(mWindowManager).removeView(any(View.class));
    }
}
