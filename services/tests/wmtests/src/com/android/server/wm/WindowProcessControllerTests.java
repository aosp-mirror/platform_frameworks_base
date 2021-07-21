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
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
    public void testSetRunningRecentsAnimation() {
        mWpc.setRunningRecentsAnimation(true);
        mWpc.setRunningRecentsAnimation(false);
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
        orderVerifier.verify(mMockListener, times(3)).setRunningRemoteAnimation(eq(true));
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
    public void testComputeOomAdjFromActivities() {
        final ActivityRecord activity = createActivityRecord(mWpc);
        activity.mVisibleRequested = true;
        final int[] callbackResult = { 0 };
        final int visible = 1;
        final int paused = 2;
        final int stopping = 4;
        final int other = 8;
        final WindowProcessController.ComputeOomAdjCallback callback =
                new WindowProcessController.ComputeOomAdjCallback() {
            @Override
            public void onVisibleActivity() {
                callbackResult[0] |= visible;
            }

            @Override
            public void onPausedActivity() {
                callbackResult[0] |= paused;
            }

            @Override
            public void onStoppingActivity(boolean finishing) {
                callbackResult[0] |= stopping;
            }

            @Override
            public void onOtherActivity() {
                callbackResult[0] |= other;
            }
        };

        // onStartActivity should refresh the state immediately.
        mWpc.onStartActivity(0 /* topProcessState */, activity.info);
        assertEquals(1 /* minTaskLayer */, mWpc.computeOomAdjFromActivities(callback));
        assertEquals(visible, callbackResult[0]);

        callbackResult[0] = 0;
        activity.mVisibleRequested = false;
        activity.setState(Task.ActivityState.PAUSED, "test");
        mWpc.computeOomAdjFromActivities(callback);
        assertEquals(paused, callbackResult[0]);

        callbackResult[0] = 0;
        activity.setState(Task.ActivityState.STOPPING, "test");
        mWpc.computeOomAdjFromActivities(callback);
        assertEquals(stopping, callbackResult[0]);

        callbackResult[0] = 0;
        activity.setState(Task.ActivityState.STOPPED, "test");
        mWpc.computeOomAdjFromActivities(callback);
        assertEquals(other, callbackResult[0]);
    }

    @Test
    public void testComputeProcessActivityState() {
        final VisibleActivityProcessTracker tracker = mAtm.mVisibleActivityProcessTracker;
        spyOn(tracker);
        final ActivityRecord activity = createActivityRecord(mWpc);
        activity.mVisibleRequested = true;
        activity.setState(Task.ActivityState.STARTED, "test");

        verify(tracker).onAnyActivityVisible(mWpc);
        assertTrue(mWpc.hasVisibleActivities());

        activity.setState(Task.ActivityState.RESUMED, "test");

        verify(tracker).onActivityResumedWhileVisible(mWpc);
        assertTrue(tracker.hasResumedActivity(mWpc.mUid));

        activity.makeFinishingLocked();
        activity.setState(Task.ActivityState.PAUSING, "test");

        assertFalse(tracker.hasResumedActivity(mWpc.mUid));
        assertTrue(mWpc.hasForegroundActivities());

        activity.setVisibility(false);
        activity.mVisibleRequested = false;
        activity.setState(Task.ActivityState.STOPPED, "test");

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
        activity.mVisibleRequested = true;
        doReturn(true).when(activity).setOverrideNightMode(anyInt());
        mWpc.updateNightModeForAllActivities(Configuration.UI_MODE_NIGHT_YES);
        verify(activity).ensureActivityConfiguration(anyInt(), anyBoolean());
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
