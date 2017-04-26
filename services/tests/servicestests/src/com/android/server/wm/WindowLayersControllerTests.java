/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import static android.app.ActivityManager.StackId.ASSISTANT_STACK_ID;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

/**
 * Tests for the {@link WindowLayersController} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.WindowLayersControllerTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowLayersControllerTests extends WindowTestsBase {

    @Test
    public void testAssignWindowLayers_ForImeWithNoTarget() throws Exception {
        sWm.mInputMethodTarget = null;
        mLayersController.assignWindowLayers(mDisplayContent);

        // The Ime has an higher base layer than app windows and lower base layer than system
        // windows, so it should be above app windows and below system windows if there isn't an IME
        // target.
        assertWindowLayerGreaterThan(mImeWindow, mChildAppWindowAbove);
        assertWindowLayerGreaterThan(mImeWindow, mAppWindow);
        assertWindowLayerGreaterThan(mImeWindow, mDockedDividerWindow);
        assertWindowLayerGreaterThan(mNavBarWindow, mImeWindow);
        assertWindowLayerGreaterThan(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowLayerGreaterThan(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithAppTarget() throws Exception {
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "imeAppTarget");
        sWm.mInputMethodTarget = imeAppTarget;
        mLayersController.assignWindowLayers(mDisplayContent);

        // Ime should be above all app windows and below system windows if it is targeting an app
        // window.
        assertWindowLayerGreaterThan(mImeWindow, imeAppTarget);
        assertWindowLayerGreaterThan(mImeWindow, mChildAppWindowAbove);
        assertWindowLayerGreaterThan(mImeWindow, mAppWindow);
        assertWindowLayerGreaterThan(mImeWindow, mDockedDividerWindow);
        assertWindowLayerGreaterThan(mNavBarWindow, mImeWindow);
        assertWindowLayerGreaterThan(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowLayerGreaterThan(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithAppTargetWithChildWindows() throws Exception {
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "imeAppTarget");
        final WindowState imeAppTargetChildAboveWindow = createWindow(imeAppTarget,
                TYPE_APPLICATION_ATTACHED_DIALOG, imeAppTarget.mToken,
                "imeAppTargetChildAboveWindow");
        final WindowState imeAppTargetChildBelowWindow = createWindow(imeAppTarget,
                TYPE_APPLICATION_MEDIA_OVERLAY, imeAppTarget.mToken,
                "imeAppTargetChildBelowWindow");

        sWm.mInputMethodTarget = imeAppTarget;
        mLayersController.assignWindowLayers(mDisplayContent);

        // Ime should be above all app windows except for child windows that are z-ordered above it
        // and below system windows if it is targeting an app window.
        assertWindowLayerGreaterThan(mImeWindow, imeAppTarget);
        assertWindowLayerGreaterThan(imeAppTargetChildAboveWindow, mImeWindow);
        assertWindowLayerGreaterThan(mImeWindow, imeAppTargetChildBelowWindow);
        assertWindowLayerGreaterThan(mImeWindow, mChildAppWindowAbove);
        assertWindowLayerGreaterThan(mImeWindow, mAppWindow);
        assertWindowLayerGreaterThan(mImeWindow, mDockedDividerWindow);
        assertWindowLayerGreaterThan(mNavBarWindow, mImeWindow);
        assertWindowLayerGreaterThan(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowLayerGreaterThan(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithAppTargetAndAppAbove() throws Exception {
        final WindowState appBelowImeTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "appBelowImeTarget");
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "imeAppTarget");
        final WindowState appAboveImeTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "appAboveImeTarget");

        sWm.mInputMethodTarget = imeAppTarget;
        mLayersController.assignWindowLayers(mDisplayContent);

        // Ime should be above all app windows except for non-fullscreen app window above it and
        // below system windows if it is targeting an app window.
        assertWindowLayerGreaterThan(mImeWindow, imeAppTarget);
        assertWindowLayerGreaterThan(mImeWindow, appBelowImeTarget);
        assertWindowLayerGreaterThan(appAboveImeTarget, mImeWindow);
        assertWindowLayerGreaterThan(mImeWindow, mChildAppWindowAbove);
        assertWindowLayerGreaterThan(mImeWindow, mAppWindow);
        assertWindowLayerGreaterThan(mImeWindow, mDockedDividerWindow);
        assertWindowLayerGreaterThan(mNavBarWindow, mImeWindow);
        assertWindowLayerGreaterThan(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowLayerGreaterThan(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeNonAppImeTarget() throws Exception {
        final WindowState imeSystemOverlayTarget = createWindow(null, TYPE_SYSTEM_OVERLAY,
                mDisplayContent, "imeSystemOverlayTarget",
                true /* ownerCanAddInternalSystemWindow */);

        sWm.mInputMethodTarget = imeSystemOverlayTarget;
        mLayersController.assignWindowLayers(mDisplayContent);

        // The IME target base layer is higher than all window except for the nav bar window, so the
        // IME should be above all windows except for the nav bar.
        assertWindowLayerGreaterThan(mImeWindow, imeSystemOverlayTarget);
        assertWindowLayerGreaterThan(mImeWindow, mChildAppWindowAbove);
        assertWindowLayerGreaterThan(mImeWindow, mAppWindow);
        assertWindowLayerGreaterThan(mImeWindow, mDockedDividerWindow);
        assertWindowLayerGreaterThan(mImeWindow, mStatusBarWindow);
        assertWindowLayerGreaterThan(mNavBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowLayerGreaterThan(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testStackLayers() throws Exception {
        WindowState pinnedStackWindow = createWindowOnStack(null, PINNED_STACK_ID,
                TYPE_BASE_APPLICATION, mDisplayContent, "pinnedStackWindow");
        WindowState dockedStackWindow = createWindowOnStack(null, DOCKED_STACK_ID,
                TYPE_BASE_APPLICATION, mDisplayContent, "dockedStackWindow");
        WindowState assistantStackWindow = createWindowOnStack(null, ASSISTANT_STACK_ID,
                TYPE_BASE_APPLICATION, mDisplayContent, "assistantStackWindow");

        mLayersController.assignWindowLayers(mDisplayContent);

        assertWindowLayerGreaterThan(dockedStackWindow, mAppWindow);
        assertWindowLayerGreaterThan(assistantStackWindow, dockedStackWindow);
        assertWindowLayerGreaterThan(pinnedStackWindow, assistantStackWindow);
    }

    private void assertWindowLayerGreaterThan(WindowState first, WindowState second)
            throws Exception {
        assertGreaterThan(first.mWinAnimator.mAnimLayer, second.mWinAnimator.mAnimLayer);
    }

}
