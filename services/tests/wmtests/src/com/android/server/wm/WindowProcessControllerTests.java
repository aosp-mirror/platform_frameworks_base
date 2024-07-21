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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STARTED;
import static com.android.server.wm.ActivityRecord.State.STOPPED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ClientTransactionHandler;
import android.app.IApplicationThread;
import android.app.servertransaction.ConfigurationChangeItem;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.LocaleList;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Tests for the {@link WindowProcessController} class.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowProcessControllerTests
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowProcessControllerTests extends WindowTestsBase {

    WindowProcessController mWpc;
    WindowProcessListener mMockListener;

    @Before
    public void setUp() {
        mMockListener = mock(WindowProcessListener.class);

        ApplicationInfo info = mock(ApplicationInfo.class);
        info.packageName = "test.package.name";
        mWpc = new WindowProcessController(
                mAtm, info, null, 0, -1, null, mMockListener);
        mWpc.setThread(mock(IApplicationThread.class));
    }

    @Test
    public void testDisplayAreaConfigurationListener() {
        // By default, the process should not listen to any display area.
        assertNull(mWpc.getDisplayArea());

        // Register to ImeContainer on display 1 as a listener.
        final TestDisplayContent testDisplayContent1 = createTestDisplayContentInContainer();
        final DisplayArea imeContainer1 = testDisplayContent1.getImeContainer();
        mWpc.registerDisplayAreaConfigurationListener(imeContainer1);
        assertTrue(imeContainer1.containsListener(mWpc));
        assertEquals(imeContainer1, mWpc.getDisplayArea());

        // Register to ImeContainer on display 2 as a listener.
        final TestDisplayContent testDisplayContent2 = createTestDisplayContentInContainer();
        final DisplayArea imeContainer2 = testDisplayContent2.getImeContainer();
        mWpc.registerDisplayAreaConfigurationListener(imeContainer2);
        assertFalse(imeContainer1.containsListener(mWpc));
        assertTrue(imeContainer2.containsListener(mWpc));
        assertEquals(imeContainer2, mWpc.getDisplayArea());

        // Null DisplayArea will not change anything.
        mWpc.registerDisplayAreaConfigurationListener(null);
        assertTrue(imeContainer2.containsListener(mWpc));
        assertEquals(imeContainer2, mWpc.getDisplayArea());

        // Unregister listener will remove the wpc from registered display area.
        mWpc.unregisterDisplayAreaConfigurationListener();
        assertFalse(imeContainer1.containsListener(mWpc));
        assertFalse(imeContainer2.containsListener(mWpc));
        assertNull(mWpc.getDisplayArea());

        // Unregistration still work even if the display was removed.
        mWpc.registerDisplayAreaConfigurationListener(imeContainer1);
        assertEquals(imeContainer1, mWpc.getDisplayArea());
        mRootWindowContainer.removeChild(testDisplayContent1);
        mWpc.unregisterDisplayAreaConfigurationListener();
        assertNull(mWpc.getDisplayArea());
    }

    @Test
    public void testDisplayAreaConfigurationListener_verifyConfig() {
        final Rect displayBounds = new Rect(0, 0, 2000, 1000);
        final DisplayContent display = new TestDisplayContent.Builder(
                mAtm, displayBounds.width(), displayBounds.height())
                .setDensityDpi(300)
                .setPosition(DisplayContent.POSITION_TOP)
                .build();
        final DisplayArea imeContainer = display.getImeContainer();

        // Register to the ime container.
        mWpc.registerDisplayAreaConfigurationListener(imeContainer);

        assertEquals(displayBounds, mWpc.getConfiguration().windowConfiguration.getBounds());

        // Resize the ime container.
        final Rect resizeImeBounds = new Rect(0, 0, 1000, 1000);
        imeContainer.setBounds(resizeImeBounds);

        assertEquals(resizeImeBounds, mWpc.getConfiguration().windowConfiguration.getBounds());

        // Register to the display.
        mWpc.registerDisplayAreaConfigurationListener(display);

        assertEquals(displayBounds, mWpc.getConfiguration().windowConfiguration.getBounds());
    }

    @Test
    public void testDestroy_unregistersDisplayAreaListener() {
        final TestDisplayContent testDisplayContent1 = createTestDisplayContentInContainer();
        final DisplayArea imeContainer1 = testDisplayContent1.getImeContainer();
        mWpc.registerDisplayAreaConfigurationListener(imeContainer1);

        mWpc.destroy();

        assertNull(mWpc.getDisplayArea());
    }

    @Test
    public void testSetAnimatingReason() {
        mWpc.addAnimatingReason(WindowProcessController.ANIMATING_REASON_REMOTE_ANIMATION);
        assertTrue(mWpc.isRunningRemoteTransition());
        mWpc.addAnimatingReason(WindowProcessController.ANIMATING_REASON_WAKEFULNESS_CHANGE);
        mWpc.removeAnimatingReason(WindowProcessController.ANIMATING_REASON_REMOTE_ANIMATION);
        assertFalse(mWpc.isRunningRemoteTransition());
        mWpc.removeAnimatingReason(WindowProcessController.ANIMATING_REASON_WAKEFULNESS_CHANGE);
        waitHandlerIdle(mAtm.mH);

        InOrder orderVerifier = Mockito.inOrder(mMockListener);
        orderVerifier.verify(mMockListener).setRunningRemoteAnimation(eq(true));
        orderVerifier.verify(mMockListener).setRunningRemoteAnimation(eq(false));
    }

    @Test
    public void testSetRunningRemoteAnimation() {
        mWpc.setRunningRemoteAnimation(true);
        mWpc.setRunningRemoteAnimation(false);
        waitHandlerIdle(mAtm.mH);

        InOrder orderVerifier = Mockito.inOrder(mMockListener);
        orderVerifier.verify(mMockListener).setRunningRemoteAnimation(eq(true));
        orderVerifier.verify(mMockListener).setRunningRemoteAnimation(eq(false));
    }

    @Test
    public void testSetRunningBothAnimations() {
        mWpc.setRunningRemoteAnimation(true);
        mWpc.setRunningRecentsAnimation(true);

        mWpc.setRunningRecentsAnimation(false);
        mWpc.setRunningRemoteAnimation(false);
        waitHandlerIdle(mAtm.mH);

        InOrder orderVerifier = Mockito.inOrder(mMockListener);
        orderVerifier.verify(mMockListener, times(1)).setRunningRemoteAnimation(eq(true));
        orderVerifier.verify(mMockListener, times(1)).setRunningRemoteAnimation(eq(false));
        orderVerifier.verifyNoMoreInteractions();
    }

    @Test
    public void testConfigurationForSecondaryScreenDisplayArea() {
        // By default, the process should not listen to any display area.
        assertNull(mWpc.getDisplayArea());

        // Register to the ImeContainer on the new display as a listener.
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 2000, 1000)
                .setDensityDpi(300).setPosition(DisplayContent.POSITION_TOP).build();
        final DisplayArea imeContainer = display.getImeContainer();
        mWpc.registerDisplayAreaConfigurationListener(imeContainer);

        assertEquals(imeContainer, mWpc.getDisplayArea());
        final Configuration expectedConfig = mAtm.mRootWindowContainer.getConfiguration();
        expectedConfig.updateFrom(imeContainer.getConfiguration());
        assertEquals(expectedConfig, mWpc.getConfiguration());
    }

    @Test
    public void testActivityNotOverridingSystemUiProcessConfig() {
        final ComponentName systemUiServiceComponent = mAtm.getSysUiServiceComponentLocked();
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
        applicationInfo.packageName = systemUiServiceComponent.getPackageName();

        WindowProcessController wpc = new WindowProcessController(
                mAtm, applicationInfo, null, 0, -1, null, mMockListener);
        wpc.setThread(mock(IApplicationThread.class));

        final ActivityRecord activity = createActivityRecord(wpc);
        wpc.addActivityIfNeeded(activity);
        // System UI owned processes should not be registered for activity config changes.
        assertFalse(wpc.registeredForActivityConfigChanges());
    }

    @Test
    public void testActivityNotOverridingImeProcessConfig() {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = Manifest.permission.BIND_INPUT_METHOD;
        // Notify WPC that this process has started an IME service.
        mWpc.onServiceStarted(serviceInfo);

        final ActivityRecord activity = createActivityRecord(mWpc);
        mWpc.addActivityIfNeeded(activity);
        // IME processes should not be registered for activity config changes.
        assertFalse(mWpc.registeredForActivityConfigChanges());
    }

    @Test
    public void testActivityNotOverridingAllyProcessConfig() {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = Manifest.permission.BIND_ACCESSIBILITY_SERVICE;
        // Notify WPC that this process has started an ally service.
        mWpc.onServiceStarted(serviceInfo);

        final ActivityRecord activity = createActivityRecord(mWpc);
        mWpc.addActivityIfNeeded(activity);
        // Ally processes should not be registered for activity config changes.
        assertFalse(mWpc.registeredForActivityConfigChanges());
    }

    @Test
    public void testActivityNotOverridingVoiceInteractionProcessConfig() {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = Manifest.permission.BIND_VOICE_INTERACTION;
        // Notify WPC that this process has started an voice interaction service.
        mWpc.onServiceStarted(serviceInfo);

        final ActivityRecord activity = createActivityRecord(mWpc);
        mWpc.addActivityIfNeeded(activity);
        // Voice interaction service processes should not be registered for activity config changes.
        assertFalse(mWpc.registeredForActivityConfigChanges());
    }

    @Test
    public void testProcessLevelConfiguration() {
        Configuration config = new Configuration();
        config.windowConfiguration.setActivityType(ACTIVITY_TYPE_HOME);
        mWpc.onRequestedOverrideConfigurationChanged(config);
        assertEquals(ACTIVITY_TYPE_HOME, config.windowConfiguration.getActivityType());
        assertEquals(ACTIVITY_TYPE_UNDEFINED, mWpc.getActivityType());

        mWpc.onMergedOverrideConfigurationChanged(config);
        assertEquals(ACTIVITY_TYPE_HOME, config.windowConfiguration.getActivityType());
        assertEquals(ACTIVITY_TYPE_UNDEFINED, mWpc.getActivityType());

        final int globalSeq = 100;
        mRootWindowContainer.getConfiguration().seq = globalSeq;
        invertOrientation(mWpc.getConfiguration());
        createActivityRecord(mWpc);

        assertTrue(mWpc.registeredForActivityConfigChanges());
        assertEquals("Config seq of process should not be affected by activity",
                mWpc.getConfiguration().seq, globalSeq);
    }

    @Test
    public void testCachedStateConfigurationChange() throws RemoteException {
        doNothing().when(mClientLifecycleManager).scheduleTransactionItemNow(any(), any());
        final IApplicationThread thread = mWpc.getThread();
        final Configuration newConfig = new Configuration(mWpc.getConfiguration());
        newConfig.densityDpi += 100;
        mWpc.mWindowSession = getTestSession();
        mWpc.mWindowSession.onWindowAdded(mock(WindowState.class));
        // Non-cached state will send the change directly.
        mWpc.setReportedProcState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        clearInvocations(mClientLifecycleManager);
        mWpc.onConfigurationChanged(newConfig);
        verify(mClientLifecycleManager).scheduleTransactionItem(eq(thread), any());

        // Cached state won't send the change.
        clearInvocations(mClientLifecycleManager);
        mWpc.setReportedProcState(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);
        newConfig.densityDpi += 100;
        mWpc.onConfigurationChanged(newConfig);
        verify(mClientLifecycleManager, never()).scheduleTransactionItem(eq(thread), any());
        verify(mClientLifecycleManager, never()).scheduleTransactionItemNow(eq(thread), any());

        // Cached -> non-cached will send the previous deferred config immediately.
        mWpc.setReportedProcState(ActivityManager.PROCESS_STATE_RECEIVER);
        final ArgumentCaptor<ConfigurationChangeItem> captor =
                ArgumentCaptor.forClass(ConfigurationChangeItem.class);
        verify(mClientLifecycleManager).scheduleTransactionItemNow(
                eq(thread), captor.capture());
        final ClientTransactionHandler client = mock(ClientTransactionHandler.class);
        captor.getValue().preExecute(client);
        verify(client).updatePendingConfiguration(newConfig);
    }

    @Test
    public void testComputeOomAdjFromActivities() {
        final ActivityRecord activity = createActivityRecord(mWpc);
        activity.setVisibleRequested(true);

        // onStartActivity should refresh the state immediately.
        mWpc.onStartActivity(0 /* topProcessState */, activity.info);
        int flags = mWpc.getActivityStateFlags();
        assertEquals(1 /* minTaskLayer */,
                flags & WindowProcessController.ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER);
        final int visibleFlags = WindowProcessController.ACTIVITY_STATE_FLAG_IS_VISIBLE
                | WindowProcessController.ACTIVITY_STATE_FLAG_IS_WINDOW_VISIBLE
                | WindowProcessController.ACTIVITY_STATE_FLAG_HAS_ACTIVITY_IN_VISIBLE_TASK;
        assertEquals(visibleFlags,
                flags & ~WindowProcessController.ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER);

        activity.setVisibleRequested(false);
        activity.setState(PAUSED, "test");
        final int exclusiveFlags = WindowProcessController.ACTIVITY_STATE_FLAG_IS_VISIBLE
                | WindowProcessController.ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED
                | WindowProcessController.ACTIVITY_STATE_FLAG_IS_STOPPING;
        flags = mWpc.getActivityStateFlags() & exclusiveFlags;
        assertEquals(WindowProcessController.ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED, flags);

        activity.setState(STOPPING, "test");
        flags = mWpc.getActivityStateFlags() & exclusiveFlags;
        assertEquals(WindowProcessController.ACTIVITY_STATE_FLAG_IS_STOPPING, flags);

        activity.setState(STOPPED, "test");
        flags = mWpc.getActivityStateFlags() & exclusiveFlags;
        assertEquals(0, flags);
    }

    @Test
    public void testComputeProcessActivityState() {
        final VisibleActivityProcessTracker tracker = mAtm.mVisibleActivityProcessTracker;
        spyOn(tracker);
        final ActivityRecord activity = createActivityRecord(mWpc);
        activity.setVisibleRequested(true);
        activity.setState(STARTED, "test");

        verify(tracker).onAnyActivityVisible(mWpc);
        assertTrue(mWpc.hasVisibleActivities());

        activity.setState(RESUMED, "test");

        verify(tracker).onActivityResumedWhileVisible(mWpc);
        assertTrue(tracker.hasResumedActivity(mWpc.mUid));

        mAtm.mTopApp = null;
        activity.makeFinishingLocked();
        activity.setState(PAUSING, "test");

        assertFalse(tracker.hasResumedActivity(mWpc.mUid));
        assertTrue(mWpc.hasForegroundActivities());

        activity.setVisibility(false);
        activity.setVisibleRequested(false);
        activity.setState(STOPPED, "test");

        verify(tracker).onAllActivitiesInvisible(mWpc);
        assertFalse(mWpc.hasVisibleActivities());
        assertFalse(mWpc.hasForegroundActivities());
    }

    private ActivityRecord createActivityRecord(WindowProcessController wpc) {
        return new ActivityBuilder(mAtm).setCreateTask(true).setUseProcess(wpc).build();
    }

    @Test
    public void testTopActivityUiModeChangeScheduleConfigChange() {
        final ActivityRecord activity = createActivityRecord(mWpc);
        activity.setVisibleRequested(true);
        doReturn(true).when(activity).applyAppSpecificConfig(anyInt(), any(), anyInt());
        mWpc.updateAppSpecificSettingsForAllActivitiesInPackage(DEFAULT_COMPONENT_PACKAGE_NAME,
                Configuration.UI_MODE_NIGHT_YES, LocaleList.forLanguageTags("en-XA"),
                GRAMMATICAL_GENDER_NOT_SPECIFIED);
        verify(activity).ensureActivityConfiguration();
    }

    @Test
    public void testTopActivityUiModeChangeForDifferentPackage_noScheduledConfigChange() {
        final ActivityRecord activity = createActivityRecord(mWpc);
        activity.setVisibleRequested(true);
        mWpc.updateAppSpecificSettingsForAllActivitiesInPackage("com.different.package",
                Configuration.UI_MODE_NIGHT_YES, LocaleList.forLanguageTags("en-XA"),
                GRAMMATICAL_GENDER_NOT_SPECIFIED);
        verify(activity, never()).applyAppSpecificConfig(anyInt(), any(), anyInt());
        verify(activity, never()).ensureActivityConfiguration();
    }

    @Test
    public void testTopActivityDisplayAreaMatchesTopMostActivity_noActivities() {
        assertNull(mWpc.getTopActivityDisplayArea());
    }

    @Test
    public void testTopActivityDisplayAreaMatchesTopMostActivity_singleActivity() {
        final ActivityRecord activityRecord = new ActivityBuilder(mSupervisor.mService).build();
        final TaskDisplayArea expectedDisplayArea = mock(TaskDisplayArea.class);

        when(activityRecord.getDisplayArea())
                .thenReturn(expectedDisplayArea);

        mWpc.addActivityIfNeeded(activityRecord);

        assertEquals(expectedDisplayArea, mWpc.getTopActivityDisplayArea());
    }

    /**
     * Test that top most activity respects z-order.
     */
    @Test
    public void testTopActivityDisplayAreaMatchesTopMostActivity_multipleActivities() {
        final ActivityRecord bottomRecord = new ActivityBuilder(mSupervisor.mService).build();
        final TaskDisplayArea bottomDisplayArea = mock(TaskDisplayArea.class);
        final ActivityRecord topRecord = new ActivityBuilder(mSupervisor.mService).build();
        final TaskDisplayArea topDisplayArea = mock(TaskDisplayArea.class);

        when(bottomRecord.getDisplayArea()).thenReturn(bottomDisplayArea);
        when(topRecord.getDisplayArea()).thenReturn(topDisplayArea);
        doReturn(-1).when(bottomRecord).compareTo(topRecord);
        doReturn(1).when(topRecord).compareTo(bottomRecord);

        mWpc.addActivityIfNeeded(topRecord);
        mWpc.addActivityIfNeeded(bottomRecord);

        assertEquals(topDisplayArea, mWpc.getTopActivityDisplayArea());
    }

    private TestDisplayContent createTestDisplayContentInContainer() {
        return new TestDisplayContent.Builder(mAtm, 1000, 1500).build();
    }

    private static void invertOrientation(Configuration config) {
        config.orientation = config.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
    }
}
