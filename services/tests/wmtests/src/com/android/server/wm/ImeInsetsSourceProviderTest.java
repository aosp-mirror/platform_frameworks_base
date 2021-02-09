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

import static android.view.InsetsState.ITYPE_IME;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.PixelFormat;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ImeInsetsSourceProviderTest extends WindowTestsBase {

    private InsetsSource mImeSource = new InsetsSource(ITYPE_IME);
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
        mDisplayContent.mInputMethodControlTarget = popup;
        mDisplayContent.mInputMethodTarget = appWin;
        popup.mAttrs.format = PixelFormat.TRANSPARENT;
        mImeProvider.scheduleShowImePostLayout(appWin);
        assertTrue(mImeProvider.isImeTargetFromDisplayContentAndImeSame());
    }

    @Test
    public void testInputMethodInputTargetCanShowIme() {
        WindowState target = createWindow(null, TYPE_APPLICATION, "app");
        mDisplayContent.mInputMethodTarget = target;
        mImeProvider.scheduleShowImePostLayout(target);
        assertTrue(mImeProvider.isImeTargetFromDisplayContentAndImeSame());
    }

    @Test
    public void testIsImeShowing() {
        WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        makeWindowVisibleAndDrawn(ime);
        mImeProvider.setWindow(ime, null, null);

        WindowState target = createWindow(null, TYPE_APPLICATION, "app");
        mDisplayContent.mInputMethodTarget = target;
        mDisplayContent.mInputMethodControlTarget = target;

        mImeProvider.scheduleShowImePostLayout(target);
        assertFalse(mImeProvider.isImeShowing());
        mImeProvider.checkShowImePostLayout();
        assertTrue(mImeProvider.isImeShowing());
        mImeProvider.setImeShowing(false);
        assertFalse(mImeProvider.isImeShowing());
    }
}
