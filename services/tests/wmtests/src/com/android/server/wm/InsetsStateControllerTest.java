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
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.platform.test.annotations.Presubmit;
import android.view.InsetsSourceControl;
import android.view.InsetsState;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
@FlakyTest(detail = "Promote once confirmed non-flaky")
@Presubmit
public class InsetsStateControllerTest extends WindowTestsBase {

    @Test
    public void testStripForDispatch_notOwn() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "parentWindow");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        topBar.setInsetProvider(getController().getSourceProvider(TYPE_TOP_BAR));
        assertNotNull(getController().getInsetsForDispatch(app).getSource(TYPE_TOP_BAR));
    }

    @Test
    public void testStripForDispatch_own() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        mDisplayContent.getInsetsStateController().getSourceProvider(TYPE_TOP_BAR)
                .setWindow(topBar, null);
        topBar.setInsetProvider(getController().getSourceProvider(TYPE_TOP_BAR));
        assertEquals(new InsetsState(), getController().getInsetsForDispatch(topBar));
    }

    @Test
    public void testStripForDispatch_navBar() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState ime = createWindow(null, TYPE_APPLICATION, "parentWindow");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        getController().getSourceProvider(TYPE_NAVIGATION_BAR).setWindow(navBar, null);
        getController().getSourceProvider(TYPE_IME).setWindow(ime, null);
        assertEquals(new InsetsState(), getController().getInsetsForDispatch(navBar));
    }

    @Test
    public void testBarControllingWinChanged() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "parentWindow");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        getController().getSourceProvider(TYPE_NAVIGATION_BAR).setWindow(navBar, null);
        getController().onBarControllingWindowChanged(app);
        InsetsSourceControl[] controls = getController().getControlsForDispatch(app);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlRevoked() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "parentWindow");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        getController().onBarControllingWindowChanged(app);
        assertNotNull(getController().getControlsForDispatch(app));
        getController().onBarControllingWindowChanged(null);
        assertNull(getController().getControlsForDispatch(app));
    }

    @Test
    public void testControlRevoked_animation() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "parentWindow");
        getController().getSourceProvider(TYPE_TOP_BAR).setWindow(topBar, null);
        getController().onBarControllingWindowChanged(app);
        assertNotNull(getController().getControlsForDispatch(app));
        topBar.cancelAnimation();
        assertNull(getController().getControlsForDispatch(app));
    }

    private InsetsStateController getController() {
        return mDisplayContent.getInsetsStateController();
    }
}
