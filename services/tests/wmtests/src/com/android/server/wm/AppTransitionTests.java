/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link AppTransition}.
 *
 * Build/Install/Run:
 *  atest WmTests:AppTransitionTests
 */
@SmallTest
@Presubmit
public class AppTransitionTests extends WindowTestsBase {
    private DisplayContent mDc;

    @Before
    public void setUp() throws Exception {
        synchronized (mWm.mGlobalLock) {
            // Hold the lock to protect the stubbing from being accessed by other threads.
            spyOn(mWm.mRoot);
            doNothing().when(mWm.mRoot).performSurfacePlacement(anyBoolean());
        }
        mDc = mWm.getDefaultDisplayContentLocked();
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testKeyguardOverride() {
        mWm.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, false /* alwaysKeepCurrent */);
        assertEquals(TRANSIT_KEYGUARD_GOING_AWAY, mDc.mAppTransition.getAppTransition());
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testKeyguardKeep() {
        mWm.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false /* alwaysKeepCurrent */);
        assertEquals(TRANSIT_KEYGUARD_GOING_AWAY, mDc.mAppTransition.getAppTransition());
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testForceOverride() {
        mWm.prepareAppTransition(TRANSIT_KEYGUARD_UNOCCLUDE, false /* alwaysKeepCurrent */);
        mDc.prepareAppTransition(TRANSIT_ACTIVITY_OPEN,
                false /* alwaysKeepCurrent */, 0 /* flags */, true /* forceOverride */);
        assertEquals(TRANSIT_ACTIVITY_OPEN, mDc.mAppTransition.getAppTransition());
    }

    @Test
    public void testCrashing() {
        mWm.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
        assertEquals(TRANSIT_CRASHING_ACTIVITY_CLOSE, mDc.mAppTransition.getAppTransition());
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testKeepKeyguard_withCrashing() {
        mWm.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
        assertEquals(TRANSIT_KEYGUARD_GOING_AWAY, mDc.mAppTransition.getAppTransition());
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testAppTransitionStateForMultiDisplay() {
        // Create 2 displays & presume both display the state is ON for ready to display & animate.
        final DisplayContent dc1 = createNewDisplay(Display.STATE_ON);
        final DisplayContent dc2 = createNewDisplay(Display.STATE_ON);

        // Create 2 app window tokens to represent 2 activity window.
        final WindowTestUtils.TestAppWindowToken token1 = createTestAppWindowToken(dc1,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowTestUtils.TestAppWindowToken token2 = createTestAppWindowToken(dc2,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);

        token1.allDrawn = true;
        token1.startingDisplayed = true;
        token1.startingMoved = true;

        // Simulate activity resume / finish flows to prepare app transition & set visibility,
        // make sure transition is set as expected for each display.
        dc1.prepareAppTransition(TRANSIT_ACTIVITY_OPEN,
                false /* alwaysKeepCurrent */, 0 /* flags */, false /* forceOverride */);
        assertEquals(TRANSIT_ACTIVITY_OPEN, dc1.mAppTransition.getAppTransition());
        dc2.prepareAppTransition(TRANSIT_ACTIVITY_CLOSE,
                false /* alwaysKeepCurrent */, 0 /* flags */, false /* forceOverride */);
        assertEquals(TRANSIT_ACTIVITY_CLOSE, dc2.mAppTransition.getAppTransition());
        // One activity window is visible for resuming & the other activity window is invisible
        // for finishing in different display.
        token1.setVisibility(true, false);
        token2.setVisibility(false, false);

        // Make sure each display is in animating stage.
        assertTrue(dc1.mOpeningApps.size() > 0);
        assertTrue(dc2.mClosingApps.size() > 0);
        assertTrue(dc1.isAppAnimating());
        assertTrue(dc2.isAppAnimating());
    }

    @Test
    public void testCleanAppTransitionWhenTaskStackReparent() {
        // Create 2 displays & presume both display the state is ON for ready to display & animate.
        final DisplayContent dc1 = createNewDisplay(Display.STATE_ON);
        final DisplayContent dc2 = createNewDisplay(Display.STATE_ON);

        final TaskStack stack1 = createTaskStackOnDisplay(dc1);
        final Task task1 = createTaskInStack(stack1, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken token1 =
                WindowTestUtils.createTestAppWindowToken(dc1);
        task1.addChild(token1, 0);

        // Simulate same app is during opening / closing transition set stage.
        dc1.mClosingApps.add(token1);
        assertTrue(dc1.mClosingApps.size() > 0);

        dc1.prepareAppTransition(TRANSIT_ACTIVITY_OPEN,
                false /* alwaysKeepCurrent */, 0 /* flags */, false /* forceOverride */);
        assertEquals(TRANSIT_ACTIVITY_OPEN, dc1.mAppTransition.getAppTransition());
        assertTrue(dc1.mAppTransition.isTransitionSet());

        dc1.mOpeningApps.add(token1);
        assertTrue(dc1.mOpeningApps.size() > 0);

        // Move stack to another display.
        stack1.reparent(dc2.getDisplayId(),  new Rect(), true);

        // Verify if token are cleared from both pending transition list in former display.
        assertFalse(dc1.mOpeningApps.contains(token1));
        assertFalse(dc1.mOpeningApps.contains(token1));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testLoadAnimationSafely() {
        DisplayContent dc = createNewDisplay(Display.STATE_ON);
        assertNull(dc.mAppTransition.loadAnimationSafely(
                getInstrumentation().getTargetContext(), -1));
    }

    @Test
    public void testCancelRemoteAnimationWhenFreeze() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final WindowState exitingAppWindow = createWindow(null /* parent */, TYPE_BASE_APPLICATION,
                dc, "exiting app");
        final AppWindowToken exitingAppToken = exitingAppWindow.mAppToken;
        // Wait until everything in animation handler get executed to prevent the exiting window
        // from being removed during WindowSurfacePlacer Traversal.
        waitUntilHandlersIdle();

        // Set a remote animator.
        final TestRemoteAnimationRunner runner = new TestRemoteAnimationRunner();
        final RemoteAnimationAdapter adapter = new RemoteAnimationAdapter(
                runner, 100, 50, true /* changeNeedsSnapshot */);
        // RemoteAnimationController will tracking RemoteAnimationAdapter's caller with calling pid.
        adapter.setCallingPidUid(123, 456);

        // Simulate activity finish flows to prepare app transition & set visibility,
        // make sure transition is set as expected.
        dc.prepareAppTransition(TRANSIT_ACTIVITY_CLOSE,
                false /* alwaysKeepCurrent */, 0 /* flags */, false /* forceOverride */);
        assertEquals(TRANSIT_ACTIVITY_CLOSE, dc.mAppTransition.getAppTransition());
        dc.mAppTransition.overridePendingAppTransitionRemote(adapter);
        exitingAppToken.setVisibility(false, false);
        assertTrue(dc.mClosingApps.size() > 0);

        // Make sure window is in animating stage before freeze, and cancel after freeze.
        assertTrue(dc.isAppAnimating());
        assertFalse(runner.mCancelled);
        dc.mAppTransition.freeze();
        assertFalse(dc.isAppAnimating());
        assertTrue(runner.mCancelled);
    }

    @Test
    public void testGetAnimationStyleResId() {
        // Verify getAnimationStyleResId will return as LayoutParams.windowAnimations when without
        // specifying window type.
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
        attrs.windowAnimations = 0x12345678;
        assertEquals(attrs.windowAnimations, mDc.mAppTransition.getAnimationStyleResId(attrs));

        // Verify getAnimationStyleResId will return system resource Id when the window type is
        // starting window.
        attrs.type = TYPE_APPLICATION_STARTING;
        assertEquals(mDc.mAppTransition.getDefaultWindowAnimationStyleResId(),
                mDc.mAppTransition.getAnimationStyleResId(attrs));
    }

    private class TestRemoteAnimationRunner implements IRemoteAnimationRunner {
        boolean mCancelled = false;
        @Override
        public void onAnimationStart(RemoteAnimationTarget[] apps,
                IRemoteAnimationFinishedCallback finishedCallback) throws RemoteException {
        }

        @Override
        public void onAnimationCancelled() {
            mCancelled = true;
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }
}
