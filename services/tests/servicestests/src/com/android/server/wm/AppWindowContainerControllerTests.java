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

import org.junit.Test;

import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.EMPTY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Test class for {@link AppWindowContainerController}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.AppWindowContainerControllerTests
 */
@SmallTest
@Presubmit
@org.junit.runner.RunWith(AndroidJUnit4.class)
public class AppWindowContainerControllerTests extends WindowTestsBase {

    @Test
    public void testRemoveContainer() throws Exception {
        final TestAppWindowContainerController controller = createAppWindowController();

        // Assert token was added to display.
        assertNotNull(sDisplayContent.getWindowToken(controller.mToken.asBinder()));
        // Assert that the container was created and linked.
        assertNotNull(controller.mContainer);

        controller.removeContainer(sDisplayContent.getDisplayId());

        // Assert token was remove from display.
        assertNull(sDisplayContent.getWindowToken(controller.mToken.asBinder()));
        // Assert that the container was removed.
        assertNull(controller.mContainer);
    }

    @Test
    public void testSetOrientation() throws Exception {
        final TestAppWindowContainerController controller = createAppWindowController();

        // Assert orientation is unspecified to start.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, controller.getOrientation());

        controller.setOrientation(SCREEN_ORIENTATION_LANDSCAPE, sDisplayContent.getDisplayId(),
                EMPTY /* displayConfig */, false /* freezeScreenIfNeeded */);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, controller.getOrientation());

        controller.removeContainer(sDisplayContent.getDisplayId());
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
    }

    @Test
    public void testCreateRemoveStartingWindow() throws Exception {
        final TestAppWindowContainerController controller = createAppWindowController();
        controller.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false);
        waitUntilHandlerIdle();
        final AppWindowToken atoken = controller.getAppWindowToken();
        assertHasStartingWindow(atoken);
        controller.removeStartingWindow();
        waitUntilHandlerIdle();
        assertNoStartingWindow(atoken);
    }

    @Test
    public void testTransferStartingWindow() throws Exception {
        final TestAppWindowContainerController controller1 = createAppWindowController();
        final TestAppWindowContainerController controller2 = createAppWindowController();
        controller1.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false);
        waitUntilHandlerIdle();
        controller2.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, controller1.mToken.asBinder(),
                true, true, false);
        waitUntilHandlerIdle();
        assertNoStartingWindow(controller1.getAppWindowToken());
        assertHasStartingWindow(controller2.getAppWindowToken());
    }

    @Test
    public void testTransferStartingWindowWhileCreating() throws Exception {
        final TestAppWindowContainerController controller1 = createAppWindowController();
        final TestAppWindowContainerController controller2 = createAppWindowController();
        sPolicy.setRunnableWhenAddingSplashScreen(() -> {

            // Surprise, ...! Transfer window in the middle of the creation flow.
            controller2.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                    android.R.style.Theme, null, "Test", 0, 0, 0, 0, controller1.mToken.asBinder(),
                    true, true, false);
        });
        controller1.addStartingWindow(InstrumentationRegistry.getContext().getPackageName(),
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false);
        waitUntilHandlerIdle();
        assertNoStartingWindow(controller1.getAppWindowToken());
        assertHasStartingWindow(controller2.getAppWindowToken());
    }

    @Test
    public void testReparent() throws Exception {
        final TestTaskWindowContainerController taskController1 =
                new TestTaskWindowContainerController(
                        createStackControllerOnDisplay(sDisplayContent));
        final TestAppWindowContainerController appWindowController1 = createAppWindowController(
                taskController1);
        final TestTaskWindowContainerController taskController2 =
                new TestTaskWindowContainerController(
                        createStackControllerOnDisplay(sDisplayContent));
        final TestAppWindowContainerController appWindowController2 = createAppWindowController(
                taskController2);
        final TestTaskWindowContainerController taskController3 =
                new TestTaskWindowContainerController(
                        createStackControllerOnDisplay(sDisplayContent));

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
        assertEquals(0, ((TestAppWindowToken) appWindowController1.mContainer).positionInParent());
        assertEquals(1, ((TestAppWindowToken) appWindowController2.mContainer).positionInParent());
    }

    private TestAppWindowContainerController createAppWindowController() {
        return createAppWindowController(new TestTaskWindowContainerController());
    }

    private TestAppWindowContainerController createAppWindowController(
            TestTaskWindowContainerController taskController) {
        return new TestAppWindowContainerController(taskController);
    }
}
