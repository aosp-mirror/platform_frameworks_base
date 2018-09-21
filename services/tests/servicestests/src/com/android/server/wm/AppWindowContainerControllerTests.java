/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.EMPTY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.wm.WindowTestUtils.TestTaskWindowContainerController;

import org.junit.Test;

/**
 * Test class for {@link AppWindowContainerController}.
 *
 * atest FrameworksServicesTests:com.android.server.wm.AppWindowContainerControllerTests
 */
@SmallTest
@Presubmit
@FlakyTest(bugId = 74078662)
@org.junit.runner.RunWith(AndroidJUnit4.class)
public class AppWindowContainerControllerTests extends WindowTestsBase {

    @Test
    public void testRemoveContainer() throws Exception {
        final WindowTestUtils.TestAppWindowContainerController controller =
                createAppWindowController();

        // Assert token was added to display.
        assertNotNull(mDisplayContent.getWindowToken(controller.mToken.asBinder()));
        // Assert that the container was created and linked.
        assertNotNull(controller.mContainer);

        controller.removeContainer(mDisplayContent.getDisplayId());

        // Assert token was remove from display.
        assertNull(mDisplayContent.getWindowToken(controller.mToken.asBinder()));
        // Assert that the container was removed.
        assertNull(controller.mContainer);
    }

    @Test
    public void testSetOrientation() throws Exception {
        final WindowTestUtils.TestAppWindowContainerController controller =
                createAppWindowController();

        // Assert orientation is unspecified to start.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, controller.getOrientation());

        controller.setOrientation(SCREEN_ORIENTATION_LANDSCAPE, mDisplayContent.getDisplayId(),
                EMPTY /* displayConfig */, false /* freezeScreenIfNeeded */);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, controller.getOrientation());

        controller.removeContainer(mDisplayContent.getDisplayId());
        // Assert orientation is unspecified to after container is removed.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, controller.getOrientation());

        // Reset display frozen state
        sWm.mDisplayFrozen = false;
    }

    private void assertHasStartingWindow(AppWindowToken atoken) {
        assertNotNull(atoken.startingSurface);
        assertNotNull(atoken.startingData);
        assertNotNull(atoken.startingWindow);
    }

    private void assertNoStartingWindow(AppWindowToken atoken) {
        assertNull(atoken.startingSurface);
        assertNull(atoken.startingWindow);
        assertNull(atoken.startingData);
        atoken.forAllWindows(windowState -> {
            assertFalse(windowState.getBaseType() == TYPE_APPLICATION_STARTING);
        }, true);
    }

    @Test
    public void testCreateRemoveStartingWindow() throws Exception {
        final WindowTestUtils.TestAppWindowContainerController controller =
                createAppWindowController();
        controller.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        final AppWindowToken atoken = controller.getAppWindowToken(mDisplayContent);
        assertHasStartingWindow(atoken);
        controller.removeStartingWindow();
        waitUntilHandlersIdle();
        assertNoStartingWindow(atoken);
    }

    @Test
    public void testAddRemoveRace() throws Exception {

        // There was once a race condition between adding and removing starting windows
        for (int i = 0; i < 1000; i++) {
            final WindowTestUtils.TestAppWindowContainerController controller =
                    createAppWindowController();
            controller.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                    android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                    false, false);
            controller.removeStartingWindow();
            waitUntilHandlersIdle();
            assertNoStartingWindow(controller.getAppWindowToken(mDisplayContent));

            controller.getAppWindowToken(mDisplayContent).getParent().getParent().removeImmediately();
        }
    }

    @Test
    public void testTransferStartingWindow() throws Exception {
        final WindowTestUtils.TestAppWindowContainerController controller1 =
                createAppWindowController();
        final WindowTestUtils.TestAppWindowContainerController controller2 =
                createAppWindowController();
        controller1.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        controller2.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, controller1.mToken.asBinder(),
                true, true, false, true, false, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(controller1.getAppWindowToken(mDisplayContent));
        assertHasStartingWindow(controller2.getAppWindowToken(mDisplayContent));
    }

    @Test
    public void testTransferStartingWindowWhileCreating() throws Exception {
        final WindowTestUtils.TestAppWindowContainerController controller1 =
                createAppWindowController();
        final WindowTestUtils.TestAppWindowContainerController controller2 =
                createAppWindowController();
        ((TestWindowManagerPolicy) sWm.mPolicy).setRunnableWhenAddingSplashScreen(() -> {

            // Surprise, ...! Transfer window in the middle of the creation flow.
            controller2.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                    android.R.style.Theme, null, "Test", 0, 0, 0, 0, controller1.mToken.asBinder(),
                    true, true, false, true, false, false);
        });
        controller1.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(controller1.getAppWindowToken(mDisplayContent));
        assertHasStartingWindow(controller2.getAppWindowToken(mDisplayContent));
    }

    @Test
    public void testTryTransferStartingWindowFromHiddenAboveToken() throws Exception {

        // Add two tasks on top of each other.
        TestTaskWindowContainerController taskController =
                new WindowTestUtils.TestTaskWindowContainerController(this);
        final WindowTestUtils.TestAppWindowContainerController controllerTop =
                createAppWindowController(taskController);
        final WindowTestUtils.TestAppWindowContainerController controllerBottom =
                createAppWindowController(taskController);

        // Add a starting window.
        controllerTop.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();

        // Make the top one invisible, and try transfering the starting window from the top to the
        // bottom one.
        controllerTop.setVisibility(false, false);
        controllerBottom.mContainer.transferStartingWindowFromHiddenAboveTokenIfNeeded();

        // Assert that the bottom window now has the starting window.
        assertNoStartingWindow(controllerTop.getAppWindowToken(mDisplayContent));
        assertHasStartingWindow(controllerBottom.getAppWindowToken(mDisplayContent));
    }

    @Test
    public void testReparent() throws Exception {
        final StackWindowController stackController =
            createStackControllerOnDisplay(mDisplayContent);
        final WindowTestUtils.TestTaskWindowContainerController taskController1 =
                new WindowTestUtils.TestTaskWindowContainerController(stackController);
        final WindowTestUtils.TestAppWindowContainerController appWindowController1 =
                createAppWindowController(taskController1);
        final WindowTestUtils.TestTaskWindowContainerController taskController2 =
                new WindowTestUtils.TestTaskWindowContainerController(stackController);
        final WindowTestUtils.TestAppWindowContainerController appWindowController2 =
                createAppWindowController(taskController2);
        final WindowTestUtils.TestTaskWindowContainerController taskController3 =
                new WindowTestUtils.TestTaskWindowContainerController(stackController);

        try {
            appWindowController1.reparent(taskController1, 0);
            fail("Should not be able to reparent to the same parent");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            taskController3.setContainer(null);
            appWindowController1.reparent(taskController3, 0);
            fail("Should not be able to reparent to a task that doesn't have a container");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        // Reparent the app window and ensure that it is moved
        appWindowController1.reparent(taskController2, 0);
        assertEquals(taskController2.mContainer, appWindowController1.mContainer.getParent());
        assertEquals(0, ((WindowTestUtils.TestAppWindowToken) appWindowController1.mContainer)
                .positionInParent());
        assertEquals(1, ((WindowTestUtils.TestAppWindowToken) appWindowController2.mContainer)
                .positionInParent());
    }

    private WindowTestUtils.TestAppWindowContainerController createAppWindowController() {
        return createAppWindowController(
                new WindowTestUtils.TestTaskWindowContainerController(this));
    }

    private WindowTestUtils.TestAppWindowContainerController createAppWindowController(
            WindowTestUtils.TestTaskWindowContainerController taskController) {
        return new WindowTestUtils.TestAppWindowContainerController(taskController);
    }
}
