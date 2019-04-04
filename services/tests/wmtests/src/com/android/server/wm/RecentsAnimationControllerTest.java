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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.atLeast;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_ORIGINAL_POSITION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

import android.os.Binder;
import android.os.IInterface;
import android.platform.test.annotations.Presubmit;
import android.util.SparseBooleanArray;
import android.view.IRecentsAnimationRunner;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:RecentsAnimationControllerTest
 */
@SmallTest
@Presubmit
public class RecentsAnimationControllerTest extends WindowTestsBase {

    @Mock SurfaceControl mMockLeash;
    @Mock SurfaceControl.Transaction mMockTransaction;
    @Mock OnAnimationFinishedCallback mFinishedCallback;
    @Mock IRecentsAnimationRunner mMockRunner;
    @Mock RecentsAnimationController.RecentsAnimationCallbacks mAnimationCallbacks;
    private RecentsAnimationController mController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        synchronized (mWm.mGlobalLock) {
            // Hold the lock to protect the stubbing from being accessed by other threads.
            spyOn(mWm.mRoot);
            doNothing().when(mWm.mRoot).performSurfacePlacement(anyBoolean());
        }
        when(mMockRunner.asBinder()).thenReturn(new Binder());
        mController = new RecentsAnimationController(mWm, mMockRunner, mAnimationCallbacks,
                DEFAULT_DISPLAY);
    }

    @Test
    public void testRemovedBeforeStarted_expectCanceled() throws Exception {
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        AnimationAdapter adapter = mController.addAnimation(appWindow.getTask(),
                false /* isRecentTaskInvisible */);
        adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);

        // Remove the app window so that the animation target can not be created
        appWindow.removeImmediately();
        mController.startAnimation();

        // Verify that the finish callback to reparent the leash is called
        verify(mFinishedCallback).onAnimationFinished(eq(adapter));
        // Verify the animation canceled callback to the app was made
        verify(mMockRunner).onAnimationCanceled(false);
        verifyNoMoreInteractionsExceptAsBinder(mMockRunner);
    }

    @Test
    public void testCancelAfterRemove_expectIgnored() {
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        AnimationAdapter adapter = mController.addAnimation(appWindow.getTask(),
                false /* isRecentTaskInvisible */);
        adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);

        // Remove the app window so that the animation target can not be created
        appWindow.removeImmediately();
        mController.startAnimation();
        mController.cleanupAnimation(REORDER_KEEP_IN_PLACE);
        try {
            mController.cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "test");
        } catch (Exception e) {
            fail("Unexpected failure when canceling animation after finishing it");
        }
    }

    @Test
    public void testIncludedApps_expectTargetAndVisible() {
        mWm.setRecentsAnimationController(mController);
        final AppWindowToken homeAppWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final AppWindowToken hiddenAppWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        hiddenAppWindow.setHidden(true);
        mDisplayContent.getConfiguration().windowConfiguration.setRotation(
                mDisplayContent.getRotation());
        mController.initialize(mDisplayContent, ACTIVITY_TYPE_HOME, new SparseBooleanArray());

        // Ensure that we are animating the target activity as well
        assertTrue(mController.isAnimatingTask(homeAppWindow.getTask()));
        assertTrue(mController.isAnimatingTask(appWindow.getTask()));
        assertFalse(mController.isAnimatingTask(hiddenAppWindow.getTask()));
    }

    @Test
    public void testCancelAnimationWithScreenShot() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, appWindow, "win1");
        appWindow.addWindow(win1);
        assertEquals(appWindow.getTask().getTopVisibleAppToken(), appWindow);
        assertEquals(appWindow.findMainWindow(), win1);

        mController.addAnimation(appWindow.getTask(), false /* isRecentTaskInvisible */);
        assertTrue(mController.isAnimatingTask(appWindow.getTask()));

        mController.setCancelWithDeferredScreenshotLocked(true);
        mController.cancelAnimationWithScreenShot();
        verify(mMockRunner).onAnimationCanceled(true /* deferredWithScreenshot */);
        assertNotNull(mController.mRecentScreenshotAnimator);
        assertTrue(mController.mRecentScreenshotAnimator.isAnimating());

        // Assume IRecentsAnimationController#cleanupScreenshot called to finish screenshot
        // animation.
        mController.mRecentScreenshotAnimator.cancelAnimation();
        verify(mAnimationCallbacks).onAnimationFinished(REORDER_KEEP_IN_PLACE, true);
    }

    private static void verifyNoMoreInteractionsExceptAsBinder(IInterface binder) {
        verify(binder, atLeast(0)).asBinder();
        verifyNoMoreInteractions(binder);
    }
}
