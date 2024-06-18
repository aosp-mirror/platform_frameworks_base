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

package com.android.server.wm;

import static android.view.InsetsSource.ID_IME;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.PixelFormat;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;
import android.view.inputmethod.ImeTracker;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ImeInsetsSourceProvider} class.
 *
 * <p> Build/Install/Run:
 * atest WmTests:ImeInsetsSourceProviderTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ImeInsetsSourceProviderTest extends WindowTestsBase {

    private InsetsSource mImeSource = new InsetsSource(ID_IME, ime());
    private ImeInsetsSourceProvider mImeProvider;

    @Before
    public void setUp() throws Exception {
        mImeSource.setVisible(true);
        mImeProvider = new ImeInsetsSourceProvider(mImeSource,
                mDisplayContent.getInsetsStateController(), mDisplayContent);
    }

    @Test
    public void testTransparentControlTargetWindowCanShowIme() {
        final WindowState appWin = createWindow(null, TYPE_APPLICATION, "app");
        final WindowState popup = createWindow(appWin, TYPE_APPLICATION, "popup");
        mDisplayContent.setImeControlTarget(popup);
        mDisplayContent.setImeLayeringTarget(appWin);
        popup.mAttrs.format = PixelFormat.TRANSPARENT;
        mImeProvider.scheduleShowImePostLayout(appWin, ImeTracker.Token.empty());
        assertTrue(mImeProvider.isReadyToShowIme());
    }

    @Test
    public void testInputMethodInputTargetCanShowIme() {
        WindowState target = createWindow(null, TYPE_APPLICATION, "app");
        mDisplayContent.setImeLayeringTarget(target);
        mDisplayContent.updateImeInputAndControlTarget(target);
        mImeProvider.scheduleShowImePostLayout(target, ImeTracker.Token.empty());
        assertTrue(mImeProvider.isReadyToShowIme());
    }

    @Test
    public void testIsImeShowing() {
        WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        makeWindowVisibleAndDrawn(ime);
        mImeProvider.setWindowContainer(ime, null, null);

        WindowState target = createWindow(null, TYPE_APPLICATION, "app");
        mDisplayContent.setImeLayeringTarget(target);
        mDisplayContent.setImeControlTarget(target);

        mImeProvider.scheduleShowImePostLayout(target, ImeTracker.Token.empty());
        assertFalse(mImeProvider.isImeShowing());
        mImeProvider.checkShowImePostLayout();
        assertTrue(mImeProvider.isImeShowing());
        mImeProvider.setImeShowing(false);
        assertFalse(mImeProvider.isImeShowing());
    }

    @Test
    public void testSetFrozen() {
        WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        makeWindowVisibleAndDrawn(ime);
        mImeProvider.setWindowContainer(ime, null, null);
        mImeProvider.setServerVisible(true);
        mImeProvider.setClientVisible(true);
        mImeProvider.updateVisibility();
        assertTrue(mImeProvider.getSource().isVisible());

        // Freezing IME states and set the server visible as false.
        mImeProvider.setFrozen(true);
        mImeProvider.setServerVisible(false);
        // Expect the IME insets visible won't be changed.
        assertTrue(mImeProvider.getSource().isVisible());

        // Unfreeze IME states and expect the IME insets became invisible due to pending IME
        // visible state updated.
        mImeProvider.setFrozen(false);
        assertFalse(mImeProvider.getSource().isVisible());
    }
}
