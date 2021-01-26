/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.IWindowToken;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:WindowContextListenerControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowContextListenerControllerTests extends WindowTestsBase {
    private WindowContextListenerController mController;

    private static final int TEST_UID = 12345;
    private static final int ANOTHER_UID = 1000;

    private final IBinder mClientToken = new Binder();
    private WindowContainer mContainer;

    @Before
    public void setUp() {
        mController = new WindowContextListenerController();
        mContainer = createTestWindowToken(TYPE_APPLICATION_OVERLAY, mDisplayContent);
    }

    @Test
    public void testRegisterWindowContextListener() {
        mController.registerWindowContainerListener(mClientToken, mContainer, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertEquals(1, mController.mListeners.size());

        final IBinder clientToken = new Binder();
        mController.registerWindowContainerListener(clientToken, mContainer, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertEquals(2, mController.mListeners.size());

        final WindowContainer container = createTestWindowToken(TYPE_APPLICATION_OVERLAY,
                mDefaultDisplay);
        mController.registerWindowContainerListener(mClientToken, container, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        // The number of listeners doesn't increase since the listener just gets updated.
        assertEquals(2, mController.mListeners.size());

        WindowContextListenerController.WindowContextListenerImpl listener =
                mController.mListeners.get(mClientToken);
        assertEquals(container, listener.getWindowContainer());
    }

    @Test
    public void testRegisterWindowContextListenerClientConfigPropagation() {
        final TestWindowTokenClient clientToken = new TestWindowTokenClient();

        final Configuration config1 = mContainer.getConfiguration();
        final Rect bounds1 = new Rect(0, 0, 10, 10);
        config1.windowConfiguration.setBounds(bounds1);
        config1.densityDpi = 100;
        mContainer.onRequestedOverrideConfigurationChanged(config1);

        mController.registerWindowContainerListener(clientToken, mContainer, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertEquals(bounds1, clientToken.mConfiguration.windowConfiguration.getBounds());
        assertEquals(config1.densityDpi, clientToken.mConfiguration.densityDpi);
        assertEquals(mDisplayContent.mDisplayId, clientToken.mDisplayId);

        // Update the WindowContainer.
        final WindowContainer container = createTestWindowToken(TYPE_APPLICATION_OVERLAY,
                mDefaultDisplay);
        final Configuration config2 = container.getConfiguration();
        final Rect bounds2 = new Rect(0, 0, 20, 20);
        config2.windowConfiguration.setBounds(bounds2);
        config2.densityDpi = 200;
        container.onRequestedOverrideConfigurationChanged(config2);

        mController.registerWindowContainerListener(clientToken, container, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertEquals(bounds2, clientToken.mConfiguration.windowConfiguration.getBounds());
        assertEquals(config2.densityDpi, clientToken.mConfiguration.densityDpi);
        assertEquals(DEFAULT_DISPLAY, clientToken.mDisplayId);

        // Update the configuration of WindowContainer.
        container.onRequestedOverrideConfigurationChanged(config1);

        assertEquals(bounds1, clientToken.mConfiguration.windowConfiguration.getBounds());
        assertEquals(config1.densityDpi, clientToken.mConfiguration.densityDpi);

        // Update the display of WindowContainer.
        container.onDisplayChanged(mDisplayContent);

        assertEquals(mDisplayContent.mDisplayId, clientToken.mDisplayId);
    }

    @Test
    public void testCanCallerRemoveListener_NullListener_ReturnFalse() {
        assertFalse(mController.assertCallerCanRemoveListener(mClientToken,
                true /* callerCanManagerAppTokens */, TEST_UID));
    }

    @Test
    public void testCanCallerRemoveListener_CanManageAppTokens_ReturnTrue() {
        mController.registerWindowContainerListener(mClientToken, mContainer, TEST_UID,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertTrue(mController.assertCallerCanRemoveListener(mClientToken,
                true /* callerCanManagerAppTokens */, ANOTHER_UID));
    }

    @Test
    public void testCanCallerRemoveListener_SameUid_ReturnTrue() {
        mController.registerWindowContainerListener(mClientToken, mContainer, TEST_UID,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertTrue(mController.assertCallerCanRemoveListener(mClientToken,
                false /* callerCanManagerAppTokens */, TEST_UID));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testCanCallerRemoveListener_DifferentUid_ThrowException() {
        mController.registerWindowContainerListener(mClientToken, mContainer, TEST_UID,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        mController.assertCallerCanRemoveListener(mClientToken,
                false /* callerCanManagerAppTokens */, ANOTHER_UID);
    }

    private class TestWindowTokenClient extends IWindowToken.Stub {
        private Configuration mConfiguration;
        private int mDisplayId;
        private boolean mRemoved;

        @Override
        public void onConfigurationChanged(Configuration configuration, int displayId) {
            mConfiguration = configuration;
            mDisplayId = displayId;
        }

        @Override
        public void onWindowTokenRemoved() {
            mRemoved = true;
        }
    }
}
