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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

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

    private static boolean sOneTimeSetupDone = false;
    private static WindowLayersController sLayersController;
    private static DisplayContent sDisplayContent;
    private static WindowState sImeWindow;
    private static WindowState sImeDialogWindow;
    private static WindowState sStatusBarWindow;
    private static WindowState sDockedDividerWindow;
    private static WindowState sNavBarWindow;
    private static WindowState sAppWindow;
    private static WindowState sChildAppWindow;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        if (sOneTimeSetupDone) {
            return;
        }
        sOneTimeSetupDone = true;
        sLayersController = new WindowLayersController(sWm);
        sDisplayContent =
                new DisplayContent(mDisplay, sWm, sLayersController, new WallpaperController(sWm));
        final WindowState wallpaperWindow =
                createWindow(null, TYPE_WALLPAPER, sDisplayContent, "wallpaperWindow");
        sImeWindow = createWindow(null, TYPE_INPUT_METHOD, sDisplayContent, "sImeWindow");
        sImeDialogWindow =
                createWindow(null, TYPE_INPUT_METHOD_DIALOG, sDisplayContent, "sImeDialogWindow");
        sStatusBarWindow = createWindow(null, TYPE_STATUS_BAR, sDisplayContent, "sStatusBarWindow");
        sNavBarWindow =
                createWindow(null, TYPE_NAVIGATION_BAR, sStatusBarWindow.mToken, "sNavBarWindow");
        sDockedDividerWindow =
                createWindow(null, TYPE_DOCK_DIVIDER, sDisplayContent, "sDockedDividerWindow");
        sAppWindow = createWindow(null, TYPE_BASE_APPLICATION, sDisplayContent, "sAppWindow");
        sChildAppWindow = createWindow(sAppWindow,
                TYPE_APPLICATION_ATTACHED_DIALOG, sAppWindow.mToken, "sChildAppWindow");
    }

    @Test
    public void testAssignWindowLayers_ForImeWithNoTarget() throws Exception {
        sWm.mInputMethodTarget = null;
        sLayersController.assignWindowLayers(sDisplayContent);

        // The Ime has an higher base layer than app windows and lower base layer than system
        // windows, so it should be above app windows and below system windows if there isn't an IME
        // target.
        assertWindowLayerGreaterThan(sImeWindow, sChildAppWindow);
        assertWindowLayerGreaterThan(sImeWindow, sAppWindow);
        assertWindowLayerGreaterThan(sImeWindow, sDockedDividerWindow);
        assertWindowLayerGreaterThan(sNavBarWindow, sImeWindow);
        assertWindowLayerGreaterThan(sStatusBarWindow, sImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowLayerGreaterThan(sImeDialogWindow, sImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithAppTarget() throws Exception {
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, sDisplayContent, "imeAppTarget");
        sWm.mInputMethodTarget = imeAppTarget;
        sLayersController.assignWindowLayers(sDisplayContent);

        // Ime should be above all app windows and below system windows if it is targeting an app
        // window.
        assertWindowLayerGreaterThan(sImeWindow, imeAppTarget);
        assertWindowLayerGreaterThan(sImeWindow, sChildAppWindow);
        assertWindowLayerGreaterThan(sImeWindow, sAppWindow);
        assertWindowLayerGreaterThan(sImeWindow, sDockedDividerWindow);
        assertWindowLayerGreaterThan(sNavBarWindow, sImeWindow);
        assertWindowLayerGreaterThan(sStatusBarWindow, sImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowLayerGreaterThan(sImeDialogWindow, sImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeNonAppImeTarget() throws Exception {
        final WindowState imeSystemOverlayTarget =
                createWindow(null, TYPE_SYSTEM_OVERLAY, sDisplayContent, "imeSystemOverlayTarget");

        sWm.mInputMethodTarget = imeSystemOverlayTarget;
        sLayersController.assignWindowLayers(sDisplayContent);

        // The IME target base layer is higher than all window except for the nav bar window, so the
        // IME should be above all windows except for the nav bar.
        assertWindowLayerGreaterThan(sImeWindow, imeSystemOverlayTarget);
        assertWindowLayerGreaterThan(sImeWindow, sChildAppWindow);
        assertWindowLayerGreaterThan(sImeWindow, sAppWindow);
        assertWindowLayerGreaterThan(sImeWindow, sDockedDividerWindow);
        assertWindowLayerGreaterThan(sImeWindow, sStatusBarWindow);
        assertWindowLayerGreaterThan(sNavBarWindow, sImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowLayerGreaterThan(sImeDialogWindow, sImeWindow);
    }

    private void assertWindowLayerGreaterThan(WindowState first, WindowState second)
            throws Exception {
        assertGreaterThan(first.mWinAnimator.mAnimLayer, second.mWinAnimator.mAnimLayer);
    }

}
