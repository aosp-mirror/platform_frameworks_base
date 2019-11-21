/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.test.InsetsModeSession;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@FlakyTest(detail = "Promote to pre-submit once confirmed stable.")
@Presubmit
@RunWith(WindowTestRunner.class)
public class InsetsStateControllerTest extends WindowTestsBase {
    private static InsetsModeSession sInsetsModeSession;

    @BeforeClass
    public static void setUpOnce() {
        // To let the insets provider control the insets visibility, the insets mode has to be
        // NEW_INSETS_MODE_FULL.
        sInsetsModeSession = new InsetsModeSession(NEW_INSETS_MODE_FULL);
    }

    @AfterClass
    public static void tearDownOnce() {
        sInsetsModeSession.close();
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testStripForDispatch_notOwn() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null);
        statusBar.setControllableInsetProvider(getController().getSourceProvider(ITYPE_STATUS_BAR));
        assertNotNull(getController().getInsetsForDispatch(app).getSource(ITYPE_STATUS_BAR));
    }

    @Test
    public void testStripForDispatch_own() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        mDisplayContent.getInsetsStateController().getSourceProvider(ITYPE_STATUS_BAR)
                .setWindow(statusBar, null);
        statusBar.setControllableInsetProvider(getController().getSourceProvider(ITYPE_STATUS_BAR));
        final InsetsState state = getController().getInsetsForDispatch(statusBar);
        for (int i = state.getSourcesCount() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            assertNotEquals(ITYPE_STATUS_BAR, source.getType());
        }
    }

    @Test
    public void testStripForDispatch_navBar() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState ime = createWindow(null, TYPE_APPLICATION, "ime");
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null);
        getController().getSourceProvider(ITYPE_IME).setWindow(ime, null);
        assertEquals(0, getController().getInsetsForDispatch(navBar).getSourcesCount());
    }

    @Test
    public void testBarControllingWinChanged() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null);
        getController().onBarControlTargetChanged(app, null, app, null);
        InsetsSourceControl[] controls = getController().getControlsForDispatch(app);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlRevoked() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null);
        getController().onBarControlTargetChanged(app, null, null, null);
        assertNotNull(getController().getControlsForDispatch(app));
        getController().onBarControlTargetChanged(null, null, null, null);
        assertNull(getController().getControlsForDispatch(app));
    }

    @FlakyTest(bugId = 124088319)
    @Test
    public void testControlRevoked_animation() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null);
        getController().onBarControlTargetChanged(app, null, null, null);
        assertNotNull(getController().getControlsForDispatch(app));
        statusBar.cancelAnimation();
        assertNull(getController().getControlsForDispatch(app));
    }

    private InsetsStateController getController() {
        return mDisplayContent.getInsetsStateController();
    }
}
