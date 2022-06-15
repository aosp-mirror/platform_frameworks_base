/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link AccessibilityFloatingMenu}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AccessibilityFloatingMenuTest extends SysuiTestCase {

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityManager mAccessibilityManager;

    private AccessibilityFloatingMenuView mMenuView;
    private AccessibilityFloatingMenu mMenu;

    @Before
    public void initMenu() {
        final List<String> assignedTargets = new ArrayList<>();
        mContext.addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        assignedTargets.add(MAGNIFICATION_CONTROLLER_NAME);
        doReturn(assignedTargets).when(mAccessibilityManager).getAccessibilityShortcutTargets(
                anyInt());

        final Position position = new Position(0, 0);
        mMenuView = new AccessibilityFloatingMenuView(mContext, position);
        mMenu = new AccessibilityFloatingMenu(mContext, mMenuView);
    }

    @Test
    public void showMenuView_success() {
        mMenu.show();

        assertThat(mMenuView.isShowing()).isTrue();
    }

    @Test
    public void hideMenuView_success() {
        mMenu.show();
        mMenu.hide();

        assertThat(mMenuView.isShowing()).isFalse();
    }

    @After
    public void tearDown() {
        mMenu.hide();
    }
}
