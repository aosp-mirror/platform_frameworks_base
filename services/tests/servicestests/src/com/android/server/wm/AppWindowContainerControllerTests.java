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

import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.IApplicationToken;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.EMPTY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
    }

    private TestAppWindowContainerController createAppWindowController() {
        final TaskStack stack = createTaskStackOnDisplay(sDisplayContent);
        final TestTaskWindowContainerController taskController =
                new TestTaskWindowContainerController(stack.mStackId);
        final IApplicationToken token = new TestIApplicationToken();
        return new TestAppWindowContainerController(taskController, token);
    }

    private class TestAppWindowContainerController extends AppWindowContainerController {

        final IApplicationToken mToken;

        TestAppWindowContainerController(TestTaskWindowContainerController taskController,
                IApplicationToken token) {
            super(taskController, token, null /* listener */, 0 /* index */,
                    SCREEN_ORIENTATION_UNSPECIFIED, true /* fullscreen */,
                    true /* showForAllUsers */, 0 /* configChanges */, false /* voiceInteraction */,
                    false /* launchTaskBehind */, false /* alwaysFocusable */,
                    0 /* targetSdkVersion */, 0 /* rotationAnimationHint */,
                    0 /* inputDispatchingTimeoutNanos */, sWm);
            mToken = token;
        }
    }

    private class TestIApplicationToken implements IApplicationToken {

        private final Binder mBinder = new Binder();
        @Override
        public IBinder asBinder() {
            return mBinder;
        }
    }
}
