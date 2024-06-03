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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.PixelFormat;
import android.platform.test.annotations.Presubmit;
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

    private ImeInsetsSourceProvider mImeProvider;

    @Before
    public void setUp() throws Exception {
        mImeProvider = mDisplayContent.getInsetsStateController().getImeSourceProvider();
        mImeProvider.getSource().setVisible(true);
    }

    @Test
    public void testTransparentControlTargetWindowCanShowIme() {
        final WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        makeWindowVisibleAndDrawn(ime);
        mImeProvider.setWindowContainer(ime, null, null);

        final WindowState appWin = createWindow(null, TYPE_APPLICATION, "app");
        final WindowState popup = createWindow(appWin, TYPE_APPLICATION, "popup");
        popup.mAttrs.format = PixelFormat.TRANSPARENT;
        mDisplayContent.setImeLayeringTarget(appWin);
        mDisplayContent.updateImeInputAndControlTarget(popup);
        performSurfacePlacementAndWaitForWindowAnimator();

        mImeProvider.scheduleShowImePostLayout(appWin, ImeTracker.Token.empty());
        assertTrue(mImeProvider.isScheduledAndReadyToShowIme());
    }

    /**
     * Checks that scheduling with all the state set and manually triggering the show does succeed.
     */
    @Test
    public void testScheduleShowIme() {
        final WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        makeWindowVisibleAndDrawn(ime);
        mImeProvider.setWindowContainer(ime, null, null);

        final WindowState target = createWindow(null, TYPE_APPLICATION, "app");
        mDisplayContent.setImeLayeringTarget(target);
        mDisplayContent.updateImeInputAndControlTarget(target);
        performSurfacePlacementAndWaitForWindowAnimator();

        // Schedule (without triggering) after everything is ready.
        mImeProvider.scheduleShowImePostLayout(target, ImeTracker.Token.empty());
        assertTrue(mImeProvider.isScheduledAndReadyToShowIme());
        assertFalse(mImeProvider.isImeShowing());

        // Manually trigger the show.
        mImeProvider.checkAndStartShowImePostLayout();
        // No longer scheduled as it was already shown.
        assertFalse(mImeProvider.isScheduledAndReadyToShowIme());
        assertTrue(mImeProvider.isImeShowing());
    }

    /**
     * Checks that scheduling to show before any state is set does succeed when
     * all the state becomes available.
     */
    @Test
    public void testScheduleShowIme_noInitialState() {
        final WindowState target = createWindow(null, TYPE_APPLICATION, "app");

        // Schedule before anything is ready.
        mImeProvider.scheduleShowImePostLayout(target, ImeTracker.Token.empty());
        assertFalse(mImeProvider.isScheduledAndReadyToShowIme());
        assertFalse(mImeProvider.isImeShowing());

        final WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        makeWindowVisibleAndDrawn(ime);
        mImeProvider.setWindowContainer(ime, null, null);

        mDisplayContent.setImeLayeringTarget(target);
        mDisplayContent.updateImeInputAndControlTarget(target);
        // Performing surface placement picks up the show scheduled above.
        performSurfacePlacementAndWaitForWindowAnimator();
        // No longer scheduled as it was already shown.
        assertFalse(mImeProvider.isScheduledAndReadyToShowIme());
        assertTrue(mImeProvider.isImeShowing());
    }

    /**
     * Checks that scheduling to show before starting the {@code afterPrepareSurfacesRunnable}
     * from {@link InsetsStateController#notifyPendingInsetsControlChanged}
     * does continue and succeed when the runnable is started.
     */
    @Test
    public void testScheduleShowIme_delayedAfterPrepareSurfaces() {
        final WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        makeWindowVisibleAndDrawn(ime);
        mImeProvider.setWindowContainer(ime, null, null);

        final WindowState target = createWindow(null, TYPE_APPLICATION, "app");
        mDisplayContent.setImeLayeringTarget(target);
        mDisplayContent.updateImeInputAndControlTarget(target);

        // Schedule before starting the afterPrepareSurfacesRunnable.
        mImeProvider.scheduleShowImePostLayout(target, ImeTracker.Token.empty());
        assertFalse(mImeProvider.isScheduledAndReadyToShowIme());
        assertFalse(mImeProvider.isImeShowing());

        // This tries to pick up the show scheduled above, but must fail as the
        // afterPrepareSurfacesRunnable was not started yet.
        mDisplayContent.applySurfaceChangesTransaction();
        assertFalse(mImeProvider.isScheduledAndReadyToShowIme());
        assertFalse(mImeProvider.isImeShowing());

        // Starting the afterPrepareSurfacesRunnable picks up the show scheduled above.
        mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
        // No longer scheduled as it was already shown.
        assertFalse(mImeProvider.isScheduledAndReadyToShowIme());
        assertTrue(mImeProvider.isImeShowing());
    }

    /**
     * Checks that scheduling to show before the surface placement does continue and succeed
     * when the surface placement happens.
     */
    @Test
    public void testScheduleShowIme_delayedSurfacePlacement() {
        final WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        makeWindowVisibleAndDrawn(ime);
        mImeProvider.setWindowContainer(ime, null, null);

        final WindowState target = createWindow(null, TYPE_APPLICATION, "app");
        mDisplayContent.setImeLayeringTarget(target);
        mDisplayContent.updateImeInputAndControlTarget(target);

        // Schedule before surface placement.
        mImeProvider.scheduleShowImePostLayout(target, ImeTracker.Token.empty());
        assertFalse(mImeProvider.isScheduledAndReadyToShowIme());
        assertFalse(mImeProvider.isImeShowing());

        // Performing surface placement picks up the show scheduled above, and succeeds.
        // This first executes the afterPrepareSurfacesRunnable, and then
        // applySurfaceChangesTransaction. Both of them try to trigger the show,
        // but only the second one can succeed, as it comes after onPostLayout.
        performSurfacePlacementAndWaitForWindowAnimator();
        // No longer scheduled as it was already shown.
        assertFalse(mImeProvider.isScheduledAndReadyToShowIme());
        assertTrue(mImeProvider.isImeShowing());
    }

    @Test
    public void testSetFrozen() {
        final WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
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
