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

import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.EnterPipRequestedItem;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.IDisplayWindowListener;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoSession;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Tests for the {@link ActivityTaskManagerService} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityTaskManagerServiceTests
 */
@Presubmit
@MediumTest
@RunWith(WindowTestRunner.class)
public class ActivityTaskManagerServiceTests extends WindowTestsBase {

    private final ArgumentCaptor<ClientTransaction> mClientTransactionCaptor =
            ArgumentCaptor.forClass(ClientTransaction.class);

    @Before
    public void setUp() throws Exception {
        setBooted(mAtm);
    }

    /** Verify that activity is finished correctly upon request. */
    @Test
    public void testActivityFinish() {
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        assertTrue("Activity must be finished", mAtm.mActivityClientController.finishActivity(
                activity.appToken, 0 /* resultCode */, null /* resultData */,
                Activity.DONT_FINISH_TASK_WITH_ACTIVITY));
        assertTrue(activity.finishing);

        assertTrue("Duplicate activity finish request must also return 'true'",
                mAtm.mActivityClientController.finishActivity(activity.appToken, 0 /* resultCode */,
                        null /* resultData */, Activity.DONT_FINISH_TASK_WITH_ACTIVITY));
    }

    @Test
    public void testOnPictureInPictureRequested() throws RemoteException {
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        final ClientLifecycleManager mockLifecycleManager = mock(ClientLifecycleManager.class);
        doReturn(mockLifecycleManager).when(mAtm).getLifecycleManager();
        doReturn(true).when(activity).checkEnterPictureInPictureState(anyString(), anyBoolean());

        mAtm.mActivityClientController.requestPictureInPictureMode(activity);

        verify(mockLifecycleManager).scheduleTransaction(mClientTransactionCaptor.capture());
        final ClientTransaction transaction = mClientTransactionCaptor.getValue();
        // Check that only an enter pip request item callback was scheduled.
        assertEquals(1, transaction.getCallbacks().size());
        assertTrue(transaction.getCallbacks().get(0) instanceof EnterPipRequestedItem);
        // Check the activity lifecycle state remains unchanged.
        assertNull(transaction.getLifecycleStateRequest());
    }

    @Test(expected = IllegalStateException.class)
    public void testOnPictureInPictureRequested_cannotEnterPip() throws RemoteException {
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        ClientLifecycleManager lifecycleManager = mAtm.getLifecycleManager();
        doReturn(false).when(activity).inPinnedWindowingMode();
        doReturn(false).when(activity).checkEnterPictureInPictureState(anyString(), anyBoolean());

        mAtm.mActivityClientController.requestPictureInPictureMode(activity);

        // Check enter no transactions with enter pip requests are made.
        verify(lifecycleManager, times(0)).scheduleTransaction(any());
    }

    @Test(expected = IllegalStateException.class)
    public void testOnPictureInPictureRequested_alreadyInPIPMode() throws RemoteException {
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        ClientLifecycleManager lifecycleManager = mAtm.getLifecycleManager();
        doReturn(true).when(activity).inPinnedWindowingMode();

        mAtm.mActivityClientController.requestPictureInPictureMode(activity);

        // Check that no transactions with enter pip requests are made.
        verify(lifecycleManager, times(0)).scheduleTransaction(any());
    }

    @Test
    public void testDisplayWindowListener() {
        final ArrayList<Integer> added = new ArrayList<>();
        final ArrayList<Integer> changed = new ArrayList<>();
        final ArrayList<Integer> removed = new ArrayList<>();
        IDisplayWindowListener listener = new IDisplayWindowListener.Stub() {
            @Override
            public void onDisplayAdded(int displayId) {
                added.add(displayId);
            }

            @Override
            public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                changed.add(displayId);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                removed.add(displayId);
            }

            @Override
            public void onFixedRotationStarted(int displayId, int newRotation) {}

            @Override
            public void onFixedRotationFinished(int displayId) {}
        };
        mAtm.mWindowManager.registerDisplayWindowListener(listener);
        // Check that existing displays call added
        assertEquals(mRootWindowContainer.getChildCount(), added.size());
        assertEquals(0, changed.size());
        assertEquals(0, removed.size());
        added.clear();
        // Check adding a display
        DisplayContent newDisp1 = new TestDisplayContent.Builder(mAtm, 600, 800).build();
        assertEquals(1, added.size());
        assertEquals(0, changed.size());
        assertEquals(0, removed.size());
        added.clear();
        // Check that changes are reported
        Configuration c = new Configuration(newDisp1.getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(new Rect(0, 0, 1000, 1300));
        newDisp1.onRequestedOverrideConfigurationChanged(c);
        mAtm.mRootWindowContainer.ensureVisibilityAndConfig(null /* starting */,
                newDisp1.mDisplayId, false /* markFrozenIfConfigChanged */,
                false /* deferResume */);
        assertEquals(0, added.size());
        assertEquals(1, changed.size());
        assertEquals(0, removed.size());
        changed.clear();
        // Check that removal is reported
        newDisp1.remove();
        assertEquals(0, added.size());
        assertEquals(0, changed.size());
        assertEquals(1, removed.size());
    }

    /*
        a test to verify b/144045134 - ignore PIP mode request for destroyed activity.
        mocks r.getParent() to return null to cause NPE inside enterPipRunnable#run() in
        ActivityTaskMangerservice#enterPictureInPictureMode(), which rebooted the device.
        It doesn't fully simulate the issue's reproduce steps, but this should suffice.
     */
    @Test
    public void testEnterPipModeWhenRecordParentChangesToNull() {
        MockitoSession mockSession = mockitoSession()
                .initMocks(this)
                .mockStatic(ActivityRecord.class)
                .startMocking();

        ActivityRecord record = mock(ActivityRecord.class);
        IBinder token = mock(IBinder.class);
        PictureInPictureParams params = mock(PictureInPictureParams.class);
        record.pictureInPictureArgs = params;

        //mock operations in private method ensureValidPictureInPictureActivityParamsLocked()
        when(ActivityRecord.forTokenLocked(token)).thenReturn(record);
        doReturn(true).when(record).supportsPictureInPicture();
        doReturn(false).when(params).hasSetAspectRatio();

        //mock other operations
        doReturn(true).when(record)
                .checkEnterPictureInPictureState("enterPictureInPictureMode", false);
        doReturn(false).when(record).inPinnedWindowingMode();
        doReturn(false).when(mAtm).isKeyguardLocked();

        //to simulate NPE
        doReturn(null).when(record).getParent();

        mAtm.mActivityClientController.enterPictureInPictureMode(token, params);
        //if record's null parent is not handled gracefully, test will fail with NPE

        mockSession.finishMocking();
    }

    @Test
    public void testResumeNextActivityOnCrashedAppDied() {
        mSupervisor.beginDeferResume();
        final ActivityRecord homeActivity = new ActivityBuilder(mAtm)
                .setTask(mRootWindowContainer.getDefaultTaskDisplayArea().getOrCreateRootHomeTask())
                .build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.setState(RESUMED, "test");
        mSupervisor.endDeferResume();

        assertEquals(activity.app, mAtm.mInternal.getTopApp());

        // Assume the activity is finishing and hidden because it was crashed.
        activity.finishing = true;
        activity.mVisibleRequested = false;
        activity.setVisible(false);
        activity.getTask().setPausingActivity(activity);
        homeActivity.setState(PAUSED, "test");

        // Even the visibility states are invisible, the next activity should be resumed because
        // the crashed activity was pausing.
        mAtm.mInternal.handleAppDied(activity.app, false /* restarting */,
                null /* finishInstrumentationCallback */);
        assertEquals(RESUMED, homeActivity.getState());
        assertEquals(homeActivity.app, mAtm.mInternal.getTopApp());
    }

    @Test
    public void testUpdateSleep() {
        doCallRealMethod().when(mWm.mRoot).hasAwakeDisplay();
        mSupervisor.mGoingToSleepWakeLock = mock(PowerManager.WakeLock.class);
        final Task rootHomeTask = mWm.mRoot.getDefaultTaskDisplayArea().getOrCreateRootHomeTask();
        final ActivityRecord homeActivity = new ActivityBuilder(mAtm).setTask(rootHomeTask).build();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        topActivity.setState(RESUMED, "test");

        final Consumer<ActivityRecord> assertTopNonSleeping = activity -> {
            assertFalse(mAtm.mInternal.isSleeping());
            assertEquals(ActivityManager.PROCESS_STATE_TOP, mAtm.mInternal.getTopProcessState());
            assertEquals(activity.app, mAtm.mInternal.getTopApp());
        };
        assertTopNonSleeping.accept(topActivity);

        // Sleep all displays.
        mWm.mRoot.forAllDisplays(display -> doReturn(true).when(display).shouldSleep());
        mAtm.updateSleepIfNeededLocked();
        // Simulate holding sleep wake lock if it is acquired.
        verify(mSupervisor.mGoingToSleepWakeLock).acquire();
        doReturn(true).when(mSupervisor.mGoingToSleepWakeLock).isHeld();

        assertEquals(PAUSING, topActivity.getState());
        assertTrue(mAtm.mInternal.isSleeping());
        assertEquals(ActivityManager.PROCESS_STATE_TOP_SLEEPING,
                mAtm.mInternal.getTopProcessState());
        // The top app should not change while sleeping.
        assertEquals(topActivity.app, mAtm.mInternal.getTopApp());

        // If all activities are stopped, the sleep wake lock must be released.
        final Task topRootTask = topActivity.getRootTask();
        doReturn(true).when(rootHomeTask).goToSleepIfPossible(anyBoolean());
        doReturn(true).when(topRootTask).goToSleepIfPossible(anyBoolean());
        topActivity.setState(STOPPING, "test");
        topActivity.activityStopped(null /* newIcicle */, null /* newPersistentState */,
                null /* description */);
        verify(mSupervisor.mGoingToSleepWakeLock).release();

        // Move the current top to back, the top app should update to the next activity.
        topRootTask.moveToBack("test", null /* self */);
        assertEquals(homeActivity.app, mAtm.mInternal.getTopApp());

        // Wake all displays.
        mWm.mRoot.forAllDisplays(display -> doReturn(false).when(display).shouldSleep());
        mAtm.updateSleepIfNeededLocked();

        assertTopNonSleeping.accept(homeActivity);
    }

    @Test
    public void testSupportsMultiWindow_resizable() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setResizeMode(RESIZE_MODE_RESIZEABLE)
                .build();
        final Task task = activity.getTask();

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());
    }

    @Test
    public void testSupportsMultiWindow_nonResizable() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                .build();
        final Task task = activity.getTask();
        final TaskDisplayArea tda = task.getDisplayArea();

        // Device config as not support.
        mAtm.mSupportsNonResizableMultiWindow = -1;

        assertFalse(activity.supportsMultiWindow());
        assertFalse(task.supportsMultiWindow());

        // Device config as always support.
        mAtm.mSupportsNonResizableMultiWindow = 1;

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());

        // The default config is relying on the screen size.
        mAtm.mSupportsNonResizableMultiWindow = 0;

        // Supports on large screen.
        tda.getConfiguration().smallestScreenWidthDp = mAtm.mLargeScreenSmallestScreenWidthDp;

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());

        // Not supports on small screen.
        tda.getConfiguration().smallestScreenWidthDp = mAtm.mLargeScreenSmallestScreenWidthDp - 1;

        assertFalse(activity.supportsMultiWindow());
        assertFalse(task.supportsMultiWindow());
    }

    @Test
    public void testSupportsMultiWindow_activityMinWidthHeight_largerThanSupport() {
        final float density = mContext.getResources().getDisplayMetrics().density;
        final ActivityInfo.WindowLayout windowLayout =
                new ActivityInfo.WindowLayout(0, 0, 0, 0, 0,
                        // This is larger than the min dimensions device support in multi window,
                        // the activity will not be supported in multi window if the device respects
                        /* minWidth= */(int) (mAtm.mLargeScreenSmallestScreenWidthDp * density),
                        /* minHeight= */(int) (mAtm.mLargeScreenSmallestScreenWidthDp * density));
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setWindowLayout(windowLayout)
                .setResizeMode(RESIZE_MODE_RESIZEABLE)
                .build();
        final Task task = activity.getTask();
        final TaskDisplayArea tda = task.getDisplayArea();

        // Ignore the activity min width/height for determine multi window eligibility.
        mAtm.mRespectsActivityMinWidthHeightMultiWindow = -1;

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());

        // Always check the activity min width/height.
        mAtm.mRespectsActivityMinWidthHeightMultiWindow = 1;

        assertFalse(activity.supportsMultiWindow());
        assertFalse(task.supportsMultiWindow());

        // The default config is relying on the screen size.
        mAtm.mRespectsActivityMinWidthHeightMultiWindow = 0;

        // Ignore on large screen.
        tda.getConfiguration().smallestScreenWidthDp = mAtm.mLargeScreenSmallestScreenWidthDp;

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());

        // Check on small screen.
        tda.getConfiguration().smallestScreenWidthDp = mAtm.mLargeScreenSmallestScreenWidthDp - 1;

        assertFalse(activity.supportsMultiWindow());
        assertFalse(task.supportsMultiWindow());
    }

    @Test
    public void testSupportsMultiWindow_activityMinWidthHeight_smallerThanSupport() {
        // This is smaller than the min dimensions device support in multi window,
        // the activity will be supported in multi window
        final float density = mContext.getResources().getDisplayMetrics().density;
        final int supportedDimensions = (int) ((mAtm.mLargeScreenSmallestScreenWidthDp - 1)
                * mAtm.mMinPercentageMultiWindowSupportWidth * density);
        final ActivityInfo.WindowLayout windowLayout =
                new ActivityInfo.WindowLayout(0, 0, 0, 0, 0,
                        /* minWidth= */supportedDimensions,
                        /* minHeight= */supportedDimensions);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setWindowLayout(windowLayout)
                .setResizeMode(RESIZE_MODE_RESIZEABLE)
                .build();
        final Task task = activity.getTask();
        final TaskDisplayArea tda = task.getDisplayArea();
        tda.getConfiguration().smallestScreenWidthDp = mAtm.mLargeScreenSmallestScreenWidthDp - 1;

        // Always check the activity min width/height.
        mAtm.mSupportsNonResizableMultiWindow = 1;

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());

        // The default config is relying on the screen size. Check for small screen
        mAtm.mSupportsNonResizableMultiWindow = 0;

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());
    }
}

