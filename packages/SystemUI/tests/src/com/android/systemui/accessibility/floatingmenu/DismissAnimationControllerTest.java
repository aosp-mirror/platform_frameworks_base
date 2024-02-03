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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.settings.SecureSettings;
import com.android.wm.shell.bubbles.DismissViewUtils;
import com.android.wm.shell.common.bubbles.DismissView;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link DismissAnimationController}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DismissAnimationControllerTest extends SysuiTestCase {
    private DismissAnimationController mDismissAnimationController;
    private DismissView mDismissView;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityManager mAccessibilityManager;

    @Before
    public void setUp() throws Exception {
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        final MenuViewModel stubMenuViewModel = new MenuViewModel(mContext, mAccessibilityManager,
                mock(SecureSettings.class));
        final MenuViewAppearance stubMenuViewAppearance = new MenuViewAppearance(mContext,
                stubWindowManager);
        final MenuView stubMenuView = new MenuView(mContext, stubMenuViewModel,
                stubMenuViewAppearance);
        mDismissView = spy(new DismissView(mContext));
        DismissViewUtils.setup(mDismissView);
        mDismissAnimationController = new DismissAnimationController(mDismissView, stubMenuView);
    }

    @Test
    public void showDismissView_success() {
        mDismissAnimationController.showDismissView(true);

        verify(mDismissView).show();
    }

    @Test
    public void hideDismissView_success() {
        mDismissAnimationController.showDismissView(false);

        verify(mDismissView).hide();
    }
}
