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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.utils.TestUtils;
import com.android.systemui.util.settings.SecureSettings;
import com.android.wm.shell.shared.bubbles.DismissView;
import com.android.wm.shell.shared.magnetictarget.MagnetizedObject;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link DragToInteractAnimationController}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class DragToInteractAnimationControllerTest extends SysuiTestCase {
    private DragToInteractAnimationController mDragToInteractAnimationController;
    private DragToInteractView mInteractView;
    private DismissView mDismissView;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityManager mAccessibilityManager;

    @Before
    public void setUp() throws Exception {
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        final SecureSettings mockSecureSettings = TestUtils.mockSecureSettings();
        final MenuViewModel stubMenuViewModel = new MenuViewModel(mContext, mAccessibilityManager,
                mockSecureSettings);
        final MenuViewAppearance stubMenuViewAppearance = new MenuViewAppearance(mContext,
                stubWindowManager);
        final MenuView stubMenuView = spy(new MenuView(mContext, stubMenuViewModel,
                stubMenuViewAppearance, mockSecureSettings));
        mInteractView = spy(new DragToInteractView(mContext));
        mDismissView = spy(new DismissView(mContext));

        if (Flags.floatingMenuDragToEdit()) {
            mDragToInteractAnimationController = new DragToInteractAnimationController(
                    mInteractView, stubMenuView);
        } else {
            mDragToInteractAnimationController = new DragToInteractAnimationController(
                    mDismissView, stubMenuView);
        }

        mDragToInteractAnimationController.setMagnetListener(new MagnetizedObject.MagnetListener() {
            @Override
            public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    @NonNull MagnetizedObject<?> draggedObject) {

            }

            @Override
            public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    @NonNull MagnetizedObject<?> draggedObject, float velX, float velY,
                    boolean wasFlungOut) {

            }

            @Override
            public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    @NonNull MagnetizedObject<?> draggedObject) {

            }
        });
    }

    @Test
    @DisableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void showDismissView_success_old() {
        mDragToInteractAnimationController.showInteractView(true);

        verify(mDismissView).show();
    }

    @Test
    @DisableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void hideDismissView_success_old() {
        mDragToInteractAnimationController.showInteractView(false);

        verify(mDismissView).hide();
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void showDismissView_success() {
        mDragToInteractAnimationController.showInteractView(true);

        verify(mInteractView).show();
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void hideDismissView_success() {
        mDragToInteractAnimationController.showInteractView(false);

        verify(mInteractView).hide();
    }
}
