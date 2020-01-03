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

import static android.view.Display.INVALID_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import android.content.pm.ApplicationInfo;
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
        mWpc = new WindowProcessController(
                mService, mock(ApplicationInfo.class), null, 0, -1, null, mMockListener);
    }

    @Test
    public void testDisplayConfigurationListener() {

        //By default, the process should not listen to any display.
        assertEquals(INVALID_DISPLAY, mWpc.getDisplayId());

        // Register to display 1 as a listener.
        TestDisplayContent testDisplayContent1 = createTestDisplayContentInContainer();
        mWpc.registerDisplayConfigurationListenerLocked(testDisplayContent1);
        assertTrue(testDisplayContent1.containsListener(mWpc));
        assertEquals(testDisplayContent1.mDisplayId, mWpc.getDisplayId());

        // Move to display 2.
        TestDisplayContent testDisplayContent2 = createTestDisplayContentInContainer();
        mWpc.registerDisplayConfigurationListenerLocked(testDisplayContent2);
        assertFalse(testDisplayContent1.containsListener(mWpc));
        assertTrue(testDisplayContent2.containsListener(mWpc));
        assertEquals(testDisplayContent2.mDisplayId, mWpc.getDisplayId());

        // Null DisplayContent will not change anything.
        mWpc.registerDisplayConfigurationListenerLocked(null);
        assertTrue(testDisplayContent2.containsListener(mWpc));
        assertEquals(testDisplayContent2.mDisplayId, mWpc.getDisplayId());

        // Unregister listener will remove the wpc from registered displays.
        mWpc.unregisterDisplayConfigurationListenerLocked();
        assertFalse(testDisplayContent1.containsListener(mWpc));
        assertFalse(testDisplayContent2.containsListener(mWpc));
        assertEquals(INVALID_DISPLAY, mWpc.getDisplayId());

        // Unregistration still work even if the display was removed.
        mWpc.registerDisplayConfigurationListenerLocked(testDisplayContent1);
        assertEquals(testDisplayContent1.mDisplayId, mWpc.getDisplayId());
        mRootWindowContainer.removeChild(testDisplayContent1);
        mWpc.unregisterDisplayConfigurationListenerLocked();
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

    private TestDisplayContent createTestDisplayContentInContainer() {
        return new TestDisplayContent.Builder(mService, 1000, 1500).build();
    }
}
