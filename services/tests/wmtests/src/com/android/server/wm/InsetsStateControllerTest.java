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

import static android.view.InsetsState.TYPE_IME;
import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_TOP_BAR;
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
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "topBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        topBar.setInsetProvider(getController().getSourceProvider(TYPE_TOP_BAR));
        assertNotNull(getController().getInsetsForDispatch(app).getSource(TYPE_TOP_BAR));
    }

    @Test
    public void testStripForDispatch_own() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "topBar");
        mDisplayContent.getInsetsStateController().getSourceProvider(TYPE_TOP_BAR)
                .setWindow(topBar, null);
        topBar.setInsetProvider(getController().getSourceProvider(TYPE_TOP_BAR));
        final InsetsState state = getController().getInsetsForDispatch(topBar);
        for (int i = state.getSourcesCount() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            assertNotEquals(TYPE_TOP_BAR, source.getType());
        }
    }

    @Test
    public void testStripForDispatch_navBar() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "topBar");
        final WindowState ime = createWindow(null, TYPE_APPLICATION, "ime");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        getController().getSourceProvider(TYPE_NAVIGATION_BAR).setWindow(navBar, null);
        getController().getSourceProvider(TYPE_IME).setWindow(ime, null);
        assertEquals(0, getController().getInsetsForDispatch(navBar).getSourcesCount());
    }

    @Test
    public void testBarControllingWinChanged() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "topBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        getController().getSourceProvider(TYPE_NAVIGATION_BAR).setWindow(navBar, null);
        getController().onBarControlTargetChanged(app, app);
        InsetsSourceControl[] controls = getController().getControlsForDispatch(app);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlRevoked() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "topBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        getController().onBarControlTargetChanged(app, null);
        assertNotNull(getController().getControlsForDispatch(app));
        getController().onBarControlTargetChanged(null, null);
        assertNull(getController().getControlsForDispatch(app));
    }

    @FlakyTest(bugId = 124088319)
    @Test
    public void testControlRevoked_animation() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "topBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        getController().onBarControlTargetChanged(app, null);
        assertNotNull(getController().getControlsForDispatch(app));
        topBar.cancelAnimation();
        assertNull(getController().getControlsForDispatch(app));
    }

    private InsetsStateController getController() {
        return mDisplayContent.getInsetsStateController();
    }
}
