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
import static android.view.Display.STATE_OFF;
import static android.view.Display.STATE_ON;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.window.WindowProvider.KEY_IS_WINDOW_PROVIDER_SERVICE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.IWindowToken;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

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
    private WindowContainer<?> mContainer;

    @Before
    public void setUp() {
        mController = new WindowContextListenerController();
        mContainer = createTestWindowToken(TYPE_APPLICATION_OVERLAY, mDisplayContent);
        // Make display on to verify configuration propagation.
        mDefaultDisplay.getDisplayInfo().state = STATE_ON;
        mDisplayContent.getDisplayInfo().state = STATE_ON;
    }

    @Test
    public void testRegisterWindowContextListener() {
        mController.registerWindowContainerListener(mClientToken, mContainer, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertEquals(1, mController.mListeners.size());

        final IBinder clientToken = mock(IBinder.class);
        mController.registerWindowContainerListener(clientToken, mContainer, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertEquals(2, mController.mListeners.size());

        final WindowContainer<?> container = createTestWindowToken(TYPE_APPLICATION_OVERLAY,
                mDefaultDisplay);
        mController.registerWindowContainerListener(mClientToken, container, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        // The number of listeners doesn't increase since the listener just gets updated.
        assertEquals(2, mController.mListeners.size());

        WindowContextListenerController.WindowContextListenerImpl listener =
                mController.mListeners.get(mClientToken);
        assertEquals(container, listener.getWindowContainer());

        mController.unregisterWindowContainerListener(clientToken);
        assertFalse(mController.mListeners.containsKey(clientToken));
        verify(clientToken).unlinkToDeath(any(), anyInt());
    }

    @UseTestDisplay
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
        final WindowContainer<?> container = createTestWindowToken(TYPE_APPLICATION_OVERLAY,
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
    public void testAssertCallerCanModifyListener_NullListener_ReturnFalse() {
        assertFalse(mController.assertCallerCanModifyListener(mClientToken,
                true /* callerCanManagerAppTokens */, TEST_UID));
    }

    @Test
    public void testAssertCallerCanModifyListener_CanManageAppTokens_ReturnTrue() {
        mController.registerWindowContainerListener(mClientToken, mContainer, TEST_UID,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertTrue(mController.assertCallerCanModifyListener(mClientToken,
                true /* callerCanManagerAppTokens */, ANOTHER_UID));
    }

    @Test
    public void testAssertCallerCanModifyListener_SameUid_ReturnTrue() {
        mController.registerWindowContainerListener(mClientToken, mContainer, TEST_UID,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        assertTrue(mController.assertCallerCanModifyListener(mClientToken,
                false /* callerCanManagerAppTokens */, TEST_UID));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testAssertCallerCanModifyListener_DifferentUid_ThrowException() {
        mController.registerWindowContainerListener(mClientToken, mContainer, TEST_UID,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        mController.assertCallerCanModifyListener(mClientToken,
                false /* callerCanManagerAppTokens */, ANOTHER_UID);
    }

    @Test
    public void testWindowContextCreatedWindowTokenRemoved_SwitchToListenToDA() {
        WindowToken windowContextCreatedToken = new WindowToken.Builder(mWm, mClientToken,
                TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                .setDisplayContent(mDefaultDisplay)
                .setFromClientToken(true)
                .build();
        final DisplayArea<?> da = windowContextCreatedToken.getDisplayArea();

        mController.registerWindowContainerListener(mClientToken, windowContextCreatedToken,
                TEST_UID, TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY, null /* options */);

        assertThat(mController.getContainer(mClientToken)).isEqualTo(windowContextCreatedToken);

        // Remove WindowToken
        windowContextCreatedToken.removeImmediately();

        assertThat(mController.getContainer(mClientToken)).isEqualTo(da);
    }

    @Test
    public void testImeSwitchDialogWindowTokenRemovedOnDualDisplayContent_ListenToImeContainer() {
        // Let the Display to be created with the DualDisplay policy.
        final DisplayAreaPolicy.Provider policyProvider =
                new DualDisplayAreaGroupPolicyTest.DualDisplayTestPolicyProvider();
        doReturn(policyProvider).when(mWm).getDisplayAreaPolicyProvider();
        // Create a DisplayContent with dual RootDisplayArea
        DualDisplayAreaGroupPolicyTest.DualDisplayContent dualDisplayContent =
                new DualDisplayAreaGroupPolicyTest.DualDisplayContent
                 .Builder(mAtm, 1000, 1000).build();
        dualDisplayContent.getDisplayInfo().state = STATE_ON;
        final DisplayArea.Tokens imeContainer = dualDisplayContent.getImeContainer();
        // Put the ImeContainer to the first sub-RootDisplayArea
        dualDisplayContent.mFirstRoot.placeImeContainer(imeContainer);

        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(dualDisplayContent.mFirstRoot);

        // Simulate the behavior to show IME switch dialog: its context switches to register to
        // context created WindowToken.
        WindowToken windowContextCreatedToken = new WindowToken.Builder(mWm, mClientToken,
                TYPE_INPUT_METHOD_DIALOG)
                .setDisplayContent(dualDisplayContent)
                .setFromClientToken(true)
                .build();
        mController.registerWindowContainerListener(mClientToken, windowContextCreatedToken,
                TEST_UID, TYPE_INPUT_METHOD_DIALOG, null /* options */);

        assertThat(mController.getContainer(mClientToken)).isEqualTo(windowContextCreatedToken);

        // Remove WindowToken
        windowContextCreatedToken.removeImmediately();

        // Now context should listen to ImeContainer.
        assertThat(mController.getContainer(mClientToken)).isEqualTo(imeContainer);
    }

    @Test
    public void testConfigUpdateForSuspendedWindowContext() {
        final TestWindowTokenClient mockToken = new TestWindowTokenClient();
        spyOn(mockToken);

        mContainer.getDisplayContent().getDisplayInfo().state = STATE_OFF;

        final Configuration config1 = mContainer.getConfiguration();
        final Rect bounds1 = new Rect(0, 0, 10, 10);
        config1.windowConfiguration.setBounds(bounds1);
        config1.densityDpi = 100;
        mContainer.onRequestedOverrideConfigurationChanged(config1);

        mController.registerWindowContainerListener(mockToken, mContainer, -1,
                TYPE_APPLICATION_OVERLAY, null /* options */);

        verify(mockToken, never()).onConfigurationChanged(any(), anyInt());

        // Turn on the display and verify if the client receive the callback
        Display display = mContainer.getDisplayContent().getDisplay();
        spyOn(display);
        Mockito.doAnswer(invocation -> {
            final DisplayInfo info = mContainer.getDisplayContent().getDisplayInfo();
            info.state = STATE_ON;
            ((DisplayInfo) invocation.getArgument(0)).copyFrom(info);
            return null;
        }).when(display).getDisplayInfo(any(DisplayInfo.class));

        mContainer.getDisplayContent().onDisplayChanged();

        assertThat(mockToken.mConfiguration).isEqualTo(config1);
        assertThat(mockToken.mDisplayId).isEqualTo(mContainer.getDisplayContent().getDisplayId());
    }

    @Test
    public void testReportConfigUpdateForSuspendedWindowProviderService() {
        final TestWindowTokenClient clientToken = new TestWindowTokenClient();
        final Bundle options = new Bundle();
        options.putBoolean(KEY_IS_WINDOW_PROVIDER_SERVICE, true);

        mContainer.getDisplayContent().getDisplayInfo().state = STATE_OFF;

        final Configuration config1 = mContainer.getConfiguration();
        final Rect bounds1 = new Rect(0, 0, 10, 10);
        config1.windowConfiguration.setBounds(bounds1);
        config1.densityDpi = 100;
        mContainer.onRequestedOverrideConfigurationChanged(config1);

        mController.registerWindowContainerListener(clientToken, mContainer, -1,
                TYPE_APPLICATION_OVERLAY, options);

        assertThat(clientToken.mConfiguration).isEqualTo(config1);
        assertThat(clientToken.mDisplayId).isEqualTo(mDisplayContent.mDisplayId);
    }

    private static class TestWindowTokenClient extends IWindowToken.Stub {
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
