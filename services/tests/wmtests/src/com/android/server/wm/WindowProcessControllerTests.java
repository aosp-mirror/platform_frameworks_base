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

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.INVALID_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
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
public class WindowProcessControllerTests extends ActivityTestsBase {

    WindowProcessController mWpc;
    WindowProcessListener mMockListener;

    @Before
    public void setUp() {
        mMockListener = mock(WindowProcessListener.class);

        ApplicationInfo info = mock(ApplicationInfo.class);
        info.packageName = "test.package.name";
        mWpc = new WindowProcessController(
                mService, info, null, 0, -1, null, mMockListener);
        mWpc.setThread(mock(IApplicationThread.class));
    }

    @Test
    public void testDisplayConfigurationListener() {

        //By default, the process should not listen to any display.
        assertEquals(INVALID_DISPLAY, mWpc.getDisplayId());

        // Register to display 1 as a listener.
        TestDisplayContent testDisplayContent1 = createTestDisplayContentInContainer();
        mWpc.registerDisplayConfigurationListener(testDisplayContent1);
        assertTrue(testDisplayContent1.containsListener(mWpc));
        assertEquals(testDisplayContent1.mDisplayId, mWpc.getDisplayId());

        // Move to display 2.
        TestDisplayContent testDisplayContent2 = createTestDisplayContentInContainer();
        mWpc.registerDisplayConfigurationListener(testDisplayContent2);
        assertFalse(testDisplayContent1.containsListener(mWpc));
        assertTrue(testDisplayContent2.containsListener(mWpc));
        assertEquals(testDisplayContent2.mDisplayId, mWpc.getDisplayId());

        // Null DisplayContent will not change anything.
        mWpc.registerDisplayConfigurationListener(null);
        assertTrue(testDisplayContent2.containsListener(mWpc));
        assertEquals(testDisplayContent2.mDisplayId, mWpc.getDisplayId());

        // Unregister listener will remove the wpc from registered displays.
        mWpc.unregisterDisplayConfigurationListener();
        assertFalse(testDisplayContent1.containsListener(mWpc));
        assertFalse(testDisplayContent2.containsListener(mWpc));
        assertEquals(INVALID_DISPLAY, mWpc.getDisplayId());

        // Unregistration still work even if the display was removed.
        mWpc.registerDisplayConfigurationListener(testDisplayContent1);
        assertEquals(testDisplayContent1.mDisplayId, mWpc.getDisplayId());
        mRootWindowContainer.removeChild(testDisplayContent1);
        mWpc.unregisterDisplayConfigurationListener();
        assertEquals(INVALID_DISPLAY, mWpc.getDisplayId());
    }

    @Test
    public void testSetRunningRecentsAnimation() {
        mWpc.setRunningRecentsAnimation(true);
        mWpc.setRunningRecentsAnimation(false);
        waitHandlerIdle(mService.mH);

        InOrder orderVerifier = Mockito.inOrder(mMockListener);
        orderVerifier.verify(mMockListener).setRunningRemoteAnimation(eq(true));
        orderVerifier.verify(mMockListener).setRunningRemoteAnimation(eq(false));
    }

    @Test
    public void testSetRunningRemoteAnimation() {
        mWpc.setRunningRemoteAnimation(true);
        mWpc.setRunningRemoteAnimation(false);
        waitHandlerIdle(mService.mH);

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
        waitHandlerIdle(mService.mH);

        InOrder orderVerifier = Mockito.inOrder(mMockListener);
        orderVerifier.verify(mMockListener, times(3)).setRunningRemoteAnimation(eq(true));
        orderVerifier.verify(mMockListener, times(1)).setRunningRemoteAnimation(eq(false));
        orderVerifier.verifyNoMoreInteractions();
    }

    @Test
    public void testConfigurationForSecondaryScreen() {
        // By default, the process should not listen to any display.
        assertEquals(INVALID_DISPLAY, mWpc.getDisplayId());

        // Register to a new display as a listener.
        final DisplayContent display = new TestDisplayContent.Builder(mService, 2000, 1000)
                .setDensityDpi(300).setPosition(DisplayContent.POSITION_TOP).build();
        mWpc.registerDisplayConfigurationListener(display);

        assertEquals(display.mDisplayId, mWpc.getDisplayId());
        final Configuration expectedConfig = mService.mRootWindowContainer.getConfiguration();
        expectedConfig.updateFrom(display.getConfiguration());
        assertEquals(expectedConfig, mWpc.getConfiguration());
    }

    @Test
    public void testDelayingConfigurationChange() {
        when(mMockListener.isCached()).thenReturn(false);

        Configuration tmpConfig = new Configuration(mWpc.getConfiguration());
        invertOrientation(tmpConfig);
        mWpc.onConfigurationChanged(tmpConfig);

        // The last reported config should be the current config as the process is not cached.
        Configuration originalConfig = new Configuration(mWpc.getConfiguration());
        assertEquals(mWpc.getLastReportedConfiguration(), originalConfig);

        when(mMockListener.isCached()).thenReturn(true);
        invertOrientation(tmpConfig);
        mWpc.onConfigurationChanged(tmpConfig);

        Configuration newConfig = new Configuration(mWpc.getConfiguration());

        // Last reported config hasn't changed because the process is in a cached state.
        assertEquals(mWpc.getLastReportedConfiguration(), originalConfig);

        mWpc.onProcCachedStateChanged(false);
        assertEquals(mWpc.getLastReportedConfiguration(), newConfig);
    }

    @Test
    public void testActivityNotOverridingSystemUiProcessConfig() {
        final ComponentName systemUiServiceComponent = mService.getSysUiServiceComponentLocked();
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
        applicationInfo.packageName = systemUiServiceComponent.getPackageName();

        WindowProcessController wpc = new WindowProcessController(
                mService, applicationInfo, null, 0, -1, null, mMockListener);
        wpc.setThread(mock(IApplicationThread.class));

        final ActivityRecord activity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .setUseProcess(wpc)
                .build();

        wpc.addActivityIfNeeded(activity);
        // System UI owned processes should not be registered for activity config changes.
        assertFalse(wpc.registeredForActivityConfigChanges());
    }

    private TestDisplayContent createTestDisplayContentInContainer() {
        return new TestDisplayContent.Builder(mService, 1000, 1500).build();
    }

    private static void invertOrientation(Configuration config) {
        config.orientation = config.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
    }
}
