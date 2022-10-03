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
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityInterceptorCallback.FIRST_ORDERED_ID;
import static com.android.server.wm.ActivityInterceptorCallback.LAST_ORDERED_ID;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.app.PictureInPictureParams;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.EnterPipRequestedItem;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IDisplayWindowListener;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.util.ArrayList;
import java.util.List;
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

    private static final String DEFAULT_PACKAGE_NAME = "my.application.package";
    private static final int DEFAULT_USER_ID = 100;

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
                activity.token, 0 /* resultCode */, null /* resultData */,
                Activity.DONT_FINISH_TASK_WITH_ACTIVITY));
        assertTrue(activity.finishing);

        assertTrue("Duplicate activity finish request must also return 'true'",
                mAtm.mActivityClientController.finishActivity(activity.token, 0 /* resultCode */,
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

    @Test
    public void testOnPictureInPictureRequested_cannotEnterPip() throws RemoteException {
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        ClientLifecycleManager lifecycleManager = mAtm.getLifecycleManager();
        doReturn(false).when(activity).inPinnedWindowingMode();
        doReturn(false).when(activity).checkEnterPictureInPictureState(anyString(), anyBoolean());

        mAtm.mActivityClientController.requestPictureInPictureMode(activity);

        verify(lifecycleManager, atLeast(0))
                .scheduleTransaction(mClientTransactionCaptor.capture());
        final ClientTransaction transaction = mClientTransactionCaptor.getValue();
        // Check that none are enter pip request items.
        transaction.getCallbacks().forEach(clientTransactionItem -> {
            assertFalse(clientTransactionItem instanceof EnterPipRequestedItem);
        });
    }

    @Test
    public void testOnPictureInPictureRequested_alreadyInPIPMode() throws RemoteException {
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        ClientLifecycleManager lifecycleManager = mAtm.getLifecycleManager();
        doReturn(true).when(activity).inPinnedWindowingMode();

        mAtm.mActivityClientController.requestPictureInPictureMode(activity);

        verify(lifecycleManager, atLeast(0))
                .scheduleTransaction(mClientTransactionCaptor.capture());
        final ClientTransaction transaction = mClientTransactionCaptor.getValue();
        // Check that none are enter pip request items.
        transaction.getCallbacks().forEach(clientTransactionItem -> {
            assertFalse(clientTransactionItem instanceof EnterPipRequestedItem);
        });
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

            @Override
            public void onKeepClearAreasChanged(int displayId, List<Rect> restricted,
                    List<Rect> unrestricted) {}
        };
        int[] displayIds = mAtm.mWindowManager.registerDisplayWindowListener(listener);
        for (int i = 0; i < displayIds.length; i++) {
            added.add(displayIds[i]);
        }
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
        newDisp1.performDisplayOverrideConfigUpdate(c);
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

    @Test
    public void testSetLockScreenShownWithVirtualDisplay() {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.type = Display.TYPE_VIRTUAL;
        DisplayContent virtualDisplay = createNewDisplay(displayInfo);

        // Make sure we're starting out with 2 unlocked displays
        assertEquals(2, mRootWindowContainer.getChildCount());
        mRootWindowContainer.forAllDisplays(displayContent -> {
            assertFalse(displayContent.isKeyguardLocked());
            assertFalse(displayContent.isAodShowing());
        });

        // Check that setLockScreenShown locks both displays
        mAtm.setLockScreenShown(true, true);
        mRootWindowContainer.forAllDisplays(displayContent -> {
            assertTrue(displayContent.isKeyguardLocked());
            assertTrue(displayContent.isAodShowing());
        });

        // Check setLockScreenShown unlocking both displays
        mAtm.setLockScreenShown(false, false);
        mRootWindowContainer.forAllDisplays(displayContent -> {
            assertFalse(displayContent.isKeyguardLocked());
            assertFalse(displayContent.isAodShowing());
        });
    }

    @Test
    public void testSetLockScreenShownWithAlwaysUnlockedVirtualDisplay() {
        assertEquals(Display.DEFAULT_DISPLAY, mRootWindowContainer.getChildAt(0).getDisplayId());

        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.type = Display.TYPE_VIRTUAL;
        displayInfo.displayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        displayInfo.flags = Display.FLAG_OWN_DISPLAY_GROUP | Display.FLAG_ALWAYS_UNLOCKED;
        DisplayContent newDisplay = createNewDisplay(displayInfo);

        // Make sure we're starting out with 2 unlocked displays
        assertEquals(2, mRootWindowContainer.getChildCount());
        mRootWindowContainer.forAllDisplays(displayContent -> {
            assertFalse(displayContent.isKeyguardLocked());
            assertFalse(displayContent.isAodShowing());
        });

        // setLockScreenShown should only lock the default display, not the virtual one
        mAtm.setLockScreenShown(true, true);

        assertTrue(mDefaultDisplay.isKeyguardLocked());
        assertTrue(mDefaultDisplay.isAodShowing());

        DisplayContent virtualDisplay = mRootWindowContainer.getDisplayContent(
                newDisplay.getDisplayId());
        assertNotEquals(Display.DEFAULT_DISPLAY, virtualDisplay.getDisplayId());
        assertFalse(virtualDisplay.isKeyguardLocked());
        assertFalse(virtualDisplay.isAodShowing());
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
        doReturn(false).when(mAtm).isKeyguardLocked(anyInt());

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
        mSupervisor.mGoingToSleepWakeLock =
                mSystemServicesTestRule.createStubbedWakeLock(true /* needVerification */);
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

        mAtm.startLaunchPowerMode(ActivityTaskManagerService.POWER_MODE_REASON_START_ACTIVITY
                | ActivityTaskManagerService.POWER_MODE_REASON_UNKNOWN_VISIBILITY);
        assertEquals(ActivityManager.PROCESS_STATE_TOP, mAtm.mInternal.getTopProcessState());
        // Because there is no unknown visibility record, the state will be restored if other
        // reasons are all done.
        mAtm.endLaunchPowerMode(ActivityTaskManagerService.POWER_MODE_REASON_START_ACTIVITY);
        assertEquals(ActivityManager.PROCESS_STATE_TOP_SLEEPING,
                mAtm.mInternal.getTopProcessState());

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
    public void testSupportsMultiWindow_landscape_checkActivityMinWidth() {
        // This is smaller than the min dimensions device support in multi window,
        // the activity will be supported in multi window
        final float density = mContext.getResources().getDisplayMetrics().density;
        final int supportedWidth = (int) (mAtm.mLargeScreenSmallestScreenWidthDp
                * mAtm.mMinPercentageMultiWindowSupportWidth * density);
        final ActivityInfo.WindowLayout windowLayout =
                new ActivityInfo.WindowLayout(0, 0, 0, 0, 0,
                        /* minWidth= */ supportedWidth,
                        /* minHeight= */ 0);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setWindowLayout(windowLayout)
                .setResizeMode(RESIZE_MODE_RESIZEABLE)
                .build();
        final Task task = activity.getTask();
        final TaskDisplayArea tda = task.getDisplayArea();
        tda.getConfiguration().smallestScreenWidthDp = mAtm.mLargeScreenSmallestScreenWidthDp - 1;
        tda.getConfiguration().screenWidthDp = mAtm.mLargeScreenSmallestScreenWidthDp - 1;
        tda.getConfiguration().orientation = ORIENTATION_LANDSCAPE;

        assertFalse(activity.supportsMultiWindow());
        assertFalse(task.supportsMultiWindow());

        tda.getConfiguration().screenWidthDp = (int) Math.ceil(
                mAtm.mLargeScreenSmallestScreenWidthDp
                        / mAtm.mMinPercentageMultiWindowSupportWidth);

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());
    }

    @Test
    public void testSupportsMultiWindow_portrait_checkActivityMinHeight() {
        // This is smaller than the min dimensions device support in multi window,
        // the activity will be supported in multi window
        final float density = mContext.getResources().getDisplayMetrics().density;
        final int supportedHeight = (int) (mAtm.mLargeScreenSmallestScreenWidthDp
                * mAtm.mMinPercentageMultiWindowSupportHeight * density);
        final ActivityInfo.WindowLayout windowLayout =
                new ActivityInfo.WindowLayout(0, 0, 0, 0, 0,
                        /* minWidth= */ 0,
                        /* minHeight= */ supportedHeight);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setWindowLayout(windowLayout)
                .setResizeMode(RESIZE_MODE_RESIZEABLE)
                .build();
        final Task task = activity.getTask();
        final TaskDisplayArea tda = task.getDisplayArea();
        tda.getConfiguration().smallestScreenWidthDp = mAtm.mLargeScreenSmallestScreenWidthDp - 1;
        tda.getConfiguration().screenHeightDp = mAtm.mLargeScreenSmallestScreenWidthDp - 1;
        tda.getConfiguration().orientation = ORIENTATION_PORTRAIT;

        assertFalse(activity.supportsMultiWindow());
        assertFalse(task.supportsMultiWindow());

        tda.getConfiguration().screenHeightDp = (int) Math.ceil(
                mAtm.mLargeScreenSmallestScreenWidthDp
                        / mAtm.mMinPercentageMultiWindowSupportHeight);

        assertTrue(activity.supportsMultiWindow());
        assertTrue(task.supportsMultiWindow());
    }

    @Test
    public void testPackageConfigUpdate_locales_successfullyApplied() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID));

        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();
        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB")).commit();

        WindowProcessController wpcAfterConfigChange = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange.getConfiguration().getLocales());
        assertFalse(wpcAfterConfigChange.getConfiguration().isNightModeActive());
    }

    @Test
    public void testPackageConfigUpdate_nightMode_successfullyApplied() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID));
        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();

        packageConfigUpdater.setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();

        WindowProcessController wpcAfterConfigChange = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertTrue(wpcAfterConfigChange.getConfiguration().isNightModeActive());
        assertEquals(LocaleList.forLanguageTags("en-XC"),
                wpcAfterConfigChange.getConfiguration().getLocales());
    }

    @Test
    public void testPackageConfigUpdate_multipleLocaleUpdates_successfullyApplied() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        WindowProcessController wpc = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), wpc);
        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();

        WindowProcessController wpcAfterConfigChange1 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange1.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange1.getConfiguration().isNightModeActive());
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpc.getConfiguration().getLocales());

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("ja-XC,en-XC")).commit();

        WindowProcessController wpcAfterConfigChange2 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        assertEquals(LocaleList.forLanguageTags("ja-XC,en-XC"),
                wpcAfterConfigChange2.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange1.getConfiguration().isNightModeActive());
        assertEquals(LocaleList.forLanguageTags("ja-XC,en-XC"),
                wpc.getConfiguration().getLocales());
    }

    @Test
    public void testPackageConfigUpdate_multipleNightModeUpdates_successfullyApplied() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID));
        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();

        WindowProcessController wpcAfterConfigChange1 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange1.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange1.getConfiguration().isNightModeActive());

        packageConfigUpdater.setNightMode(Configuration.UI_MODE_NIGHT_NO).commit();

        WindowProcessController wpcAfterConfigChange2 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange2.getConfiguration().getLocales());
        assertFalse(wpcAfterConfigChange2.getConfiguration().isNightModeActive());
    }

    @Test
    public void testPackageConfigUpdate_onPackageUninstall_configShouldNotApply() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID));
        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();

        WindowProcessController wpcAfterConfigChange1 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange1.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange1.getConfiguration().isNightModeActive());

        mAtm.mInternal.onPackageUninstalled(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        WindowProcessController wpcAfterConfigChange2 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XC"),
                wpcAfterConfigChange2.getConfiguration().getLocales());
        assertFalse(wpcAfterConfigChange2.getConfiguration().isNightModeActive());
    }

    @Test
    public void testPackageConfigUpdate_LocalesEmptyAndNightModeUndefined_configShouldNotApply() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        WindowProcessController wpc = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), wpc);
        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();
        WindowProcessController wpcAfterConfigChange1 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange1.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange1.getConfiguration().isNightModeActive());
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpc.getConfiguration().getLocales());

        packageConfigUpdater.setLocales(LocaleList.getEmptyLocaleList())
                .setNightMode(Configuration.UI_MODE_NIGHT_UNDEFINED).commit();

        WindowProcessController wpcAfterConfigChange2 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XC"),
                wpcAfterConfigChange2.getConfiguration().getLocales());
        assertFalse(wpcAfterConfigChange2.getConfiguration().isNightModeActive());
        assertEquals(LocaleList.forLanguageTags("en-XC"),
                wpc.getConfiguration().getLocales());
    }

    @Test
    public void testPackageConfigUpdate_WhenUserRemoved_configShouldNotApply() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID));

        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();

        WindowProcessController wpcAfterConfigChange1 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange1.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange1.getConfiguration().isNightModeActive());

        mAtm.mInternal.removeUser(DEFAULT_USER_ID);

        WindowProcessController wpcAfterConfigChange2 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XC"),
                wpcAfterConfigChange2.getConfiguration().getLocales());
        assertFalse(wpcAfterConfigChange2.getConfiguration().isNightModeActive());
    }

    @Test
    public void testPackageConfigUpdate_setLocaleListToEmpty_doesNotOverlayLocaleListInWpc() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID));

        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();

        WindowProcessController wpcAfterConfigChange1 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange1.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange1.getConfiguration().isNightModeActive());

        packageConfigUpdater.setLocales(LocaleList.getEmptyLocaleList()).commit();

        WindowProcessController wpcAfterConfigChange2 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XC"),
                wpcAfterConfigChange2.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange2.getConfiguration().isNightModeActive());
    }

    @Test
    public void testPackageConfigUpdate_resetNightMode_doesNotOverrideNightModeInWpc() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID));

        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater();

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();

        WindowProcessController wpcAfterConfigChange1 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange1.getConfiguration().getLocales());
        assertTrue(wpcAfterConfigChange1.getConfiguration().isNightModeActive());

        packageConfigUpdater.setNightMode(Configuration.UI_MODE_NIGHT_UNDEFINED).commit();

        WindowProcessController wpcAfterConfigChange2 = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange2.getConfiguration().getLocales());
        assertFalse(wpcAfterConfigChange2.getConfiguration().isNightModeActive());
    }

    @Test
    public void testPackageConfigUpdate_localesNotSet_localeConfigRetrievedNull() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true,
                DEFAULT_USER_ID);
        WindowProcessController wpc = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), wpc);
        mAtm.mInternal.onProcessAdded(wpc);

        ActivityTaskManagerInternal.PackageConfig appSpecificConfig = mAtm.mInternal
                .getApplicationConfig(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        // when no configuration is set we get a null object.
        assertNull(appSpecificConfig);

        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater(DEFAULT_PACKAGE_NAME,
                        DEFAULT_USER_ID);
        packageConfigUpdater.setNightMode(Configuration.UI_MODE_NIGHT_YES).commit();

        ActivityTaskManagerInternal.PackageConfig appSpecificConfig2 = mAtm.mInternal
                .getApplicationConfig(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertNotNull(appSpecificConfig2);
        assertNull(appSpecificConfig2.mLocales);
        assertEquals(appSpecificConfig2.mNightMode.intValue(), Configuration.UI_MODE_NIGHT_YES);
    }

    @Test
    public void testPackageConfigUpdate_appNotRunning_configSuccessfullyApplied() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true,
                DEFAULT_USER_ID);

        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater(DEFAULT_PACKAGE_NAME,
                        DEFAULT_USER_ID);
        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB")).commit();

        // Verifies if the persisted app-specific configuration is same as the committed
        // configuration.
        ActivityTaskManagerInternal.PackageConfig appSpecificConfig = mAtm.mInternal
                .getApplicationConfig(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertNotNull(appSpecificConfig);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB"), appSpecificConfig.mLocales);

        // Verifies if the persisted configuration for an arbitrary app is applied correctly when
        // a new WindowProcessController is created for it.
        WindowProcessController wpcAfterConfigChange = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpcAfterConfigChange.getConfiguration().getLocales());
    }

    @Test
    public void testPackageConfigUpdate_appRunning_configSuccessfullyApplied() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true,
                DEFAULT_USER_ID);
        WindowProcessController wpc = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), wpc);
        mAtm.mInternal.onProcessAdded(wpc);

        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater(DEFAULT_PACKAGE_NAME,
                        DEFAULT_USER_ID);

        packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB")).commit();

        ActivityTaskManagerInternal.PackageConfig appSpecificConfig = mAtm.mInternal
                .getApplicationConfig(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        // Verifies if the persisted app-specific configuration is same as the committed
        // configuration.
        assertNotNull(appSpecificConfig);
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB"), appSpecificConfig.mLocales);

        // Verifies if the committed configuration is successfully applied to the required
        // application while it is currently running.
        assertEquals(LocaleList.forLanguageTags("en-XA,ar-XB,en-XC"),
                wpc.getConfiguration().getLocales());
    }

    @Test
    public void testPackageConfigUpdate_commitConfig_configSuccessfullyApplied() {
        Configuration config = mAtm.getGlobalConfiguration();
        config.setLocales(LocaleList.forLanguageTags("en-XC"));
        mAtm.updateGlobalConfigurationLocked(config, true, true,
                DEFAULT_USER_ID);
        WindowProcessController wpc = createWindowProcessController(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        mAtm.mProcessMap.put(Binder.getCallingPid(), wpc);
        mAtm.mInternal.onProcessAdded(wpc);

        ActivityTaskManagerInternal.PackageConfigurationUpdater packageConfigUpdater =
                mAtm.mInternal.createPackageConfigurationUpdater(DEFAULT_PACKAGE_NAME,
                        DEFAULT_USER_ID);

        // committing empty locales, when no config is set should return false.
        assertFalse(packageConfigUpdater.setLocales(LocaleList.getEmptyLocaleList()).commit());

        // committing new configuration returns true;
        assertTrue(packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .commit());
        // applying the same configuration returns false.
        assertFalse(packageConfigUpdater.setLocales(LocaleList.forLanguageTags("en-XA,ar-XB"))
                .commit());

        // committing empty locales and undefined nightMode should return true (deletes the
        // pre-existing record) if some config was previously set.
        assertTrue(packageConfigUpdater.setLocales(LocaleList.getEmptyLocaleList())
                .setNightMode(Configuration.UI_MODE_NIGHT_UNDEFINED).commit());
    }

    private WindowProcessController createWindowProcessController(String packageName,
            int userId) {
        WindowProcessListener mMockListener = Mockito.mock(WindowProcessListener.class);
        ApplicationInfo info = mock(ApplicationInfo.class);
        info.packageName = packageName;
        WindowProcessController wpc = new WindowProcessController(
                mAtm, info, packageName, 0, userId, null, mMockListener);
        wpc.setThread(mock(IApplicationThread.class));
        return wpc;
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterActivityStartInterceptor_IndexTooSmall() {
        mAtm.mInternal.registerActivityStartInterceptor(FIRST_ORDERED_ID - 1,
                new ActivityInterceptorCallback() {
                    @Nullable
                    @Override
                    public ActivityInterceptResult intercept(ActivityInterceptorInfo info) {
                        return null;
                    }
                });
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterActivityStartInterceptor_IndexTooLarge() {
        mAtm.mInternal.registerActivityStartInterceptor(LAST_ORDERED_ID + 1,
                new ActivityInterceptorCallback() {
                    @Nullable
                    @Override
                    public ActivityInterceptResult intercept(ActivityInterceptorInfo info) {
                        return null;
                    }
                });
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterActivityStartInterceptor_DuplicateId() {
        mAtm.mInternal.registerActivityStartInterceptor(FIRST_ORDERED_ID,
                new ActivityInterceptorCallback() {
                    @Nullable
                    @Override
                    public ActivityInterceptResult intercept(ActivityInterceptorInfo info) {
                        return null;
                    }
                });
        mAtm.mInternal.registerActivityStartInterceptor(FIRST_ORDERED_ID,
                new ActivityInterceptorCallback() {
                    @Nullable
                    @Override
                    public ActivityInterceptResult intercept(ActivityInterceptorInfo info) {
                        return null;
                    }
                });
    }

    @Test
    public void testRegisterActivityStartInterceptor() {
        assertEquals(0, mAtm.getActivityInterceptorCallbacks().size());

        mAtm.mInternal.registerActivityStartInterceptor(FIRST_ORDERED_ID,
                new ActivityInterceptorCallback() {
                    @Nullable
                    @Override
                    public ActivityInterceptResult intercept(ActivityInterceptorInfo info) {
                        return null;
                    }
                });

        assertEquals(1, mAtm.getActivityInterceptorCallbacks().size());
        assertTrue(mAtm.getActivityInterceptorCallbacks().contains(FIRST_ORDERED_ID));
    }
}
