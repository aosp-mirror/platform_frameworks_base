/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Tests for RootWindowContainer.
 *
 * Build/Install/Run:
 *  atest WmTests:RootWindowContainerTests
 */
@SmallTest
@Presubmit
public class RootWindowContainerTests extends WindowTestsBase {

    private static final int FAKE_CALLING_UID = 667;

    @Test
    public void testIsAnyNonToastWindowVisibleForUid_oneToastOneNonToastBothVisible() {
        final WindowState toastyToast = createWindow(null, TYPE_TOAST, "toast", FAKE_CALLING_UID);
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app", FAKE_CALLING_UID);
        toastyToast.mHasSurface = true;
        app.mHasSurface = true;

        assertTrue(toastyToast.isVisible());
        assertTrue(app.isVisible());
        assertTrue(mWm.mRoot.isAnyNonToastWindowVisibleForUid(FAKE_CALLING_UID));
    }

    @Test
    public void testIsAnyNonToastWindowVisibleForUid_onlyToastVisible() {
        final WindowState toastyToast = createWindow(null, TYPE_TOAST, "toast", FAKE_CALLING_UID);
        toastyToast.mHasSurface = true;

        assertTrue(toastyToast.isVisible());
        assertFalse(mWm.mRoot.isAnyNonToastWindowVisibleForUid(FAKE_CALLING_UID));
    }

    @Test
    public void testIsAnyNonToastWindowVisibleForUid_aFewNonToastButNoneVisible() {
        final WindowState topBar = createWindow(null, TYPE_STATUS_BAR, "topBar", FAKE_CALLING_UID);
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app", FAKE_CALLING_UID);

        assertFalse(topBar.isVisible());
        assertFalse(app.isVisible());
        assertFalse(mWm.mRoot.isAnyNonToastWindowVisibleForUid(FAKE_CALLING_UID));
    }
}

