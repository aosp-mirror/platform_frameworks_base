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

package com.android.systemui.accessibility;

import static com.android.systemui.accessibility.MagnificationImpl.DELAY_SHOW_MAGNIFICATION_TIMEOUT_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.IWindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IMagnificationConnectionCallback;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link android.view.accessibility.IMagnificationConnection} retrieved from
 * {@link MagnificationImpl}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class IMagnificationConnectionTest extends SysuiTestCase {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private IMagnificationConnectionCallback mConnectionCallback;
    @Mock
    private WindowMagnificationController mWindowMagnificationController;
    @Mock
    private FullscreenMagnificationController mFullscreenMagnificationController;
    @Mock
    private MagnificationSettingsController mMagnificationSettingsController;
    @Mock
    private ModeSwitchesController mModeSwitchesController;
    @Mock
    private SysUiState mSysUiState;
    @Mock
    private IRemoteMagnificationAnimationCallback mAnimationCallback;
    @Mock
    private OverviewProxyService mOverviewProxyService;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private AccessibilityLogger mA11yLogger;
    @Mock
    private IWindowManager mIWindowManager;

    private IMagnificationConnection mIMagnificationConnection;
    private MagnificationImpl mMagnification;
    private FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        doAnswer(invocation -> {
            mIMagnificationConnection = invocation.getArgument(0);
            return null;
        }).when(mAccessibilityManager).setMagnificationConnection(
                any(IMagnificationConnection.class));
        mTestableLooper = TestableLooper.get(this);
        assertNotNull(mTestableLooper);
        mMagnification = new MagnificationImpl(getContext(),
                mTestableLooper.getLooper(), mContext.getMainExecutor(), mCommandQueue,
                mModeSwitchesController, mSysUiState, mOverviewProxyService, mSecureSettings,
                mDisplayTracker, getContext().getSystemService(DisplayManager.class),
                mA11yLogger, mIWindowManager, mAccessibilityManager);
        mMagnification.mWindowMagnificationControllerSupplier =
                new FakeWindowMagnificationControllerSupplier(
                        mContext.getSystemService(DisplayManager.class));
        mMagnification.mFullscreenMagnificationControllerSupplier =
                new FakeFullscreenMagnificationControllerSupplier(
                        mContext.getSystemService(DisplayManager.class));
        mMagnification.mMagnificationSettingsSupplier = new FakeSettingsSupplier(
                mContext.getSystemService(DisplayManager.class));

        mMagnification.requestMagnificationConnection(true);
        assertNotNull(mIMagnificationConnection);
        mIMagnificationConnection.setConnectionCallback(mConnectionCallback);
    }

    @Test
    public void enableWindowMagnification_passThrough() throws RemoteException {
        mIMagnificationConnection.enableWindowMagnification(TEST_DISPLAY, 3.0f, Float.NaN,
                Float.NaN, 0f, 0f, mAnimationCallback);
        processAllPendingMessages();

        verify(mWindowMagnificationController).enableWindowMagnification(eq(3.0f),
                eq(Float.NaN), eq(Float.NaN), eq(0f), eq(0f), eq(mAnimationCallback));
    }

    @Test
    public void onFullscreenMagnificationActivationChanged_passThrough() throws RemoteException {
        mIMagnificationConnection.onFullscreenMagnificationActivationChanged(TEST_DISPLAY, true);
        processAllPendingMessages();

        verify(mFullscreenMagnificationController)
                .onFullscreenMagnificationActivationChanged(eq(true));
    }

    @Test
    public void disableWindowMagnification_deleteWindowMagnification() throws RemoteException {
        mIMagnificationConnection.disableWindowMagnification(TEST_DISPLAY,
                mAnimationCallback);
        processAllPendingMessages();

        verify(mWindowMagnificationController).deleteWindowMagnification(
                mAnimationCallback);
    }

    @Test
    public void setScaleForWindowMagnification() throws RemoteException {
        mIMagnificationConnection.setScaleForWindowMagnification(TEST_DISPLAY, 3.0f);
        processAllPendingMessages();

        verify(mWindowMagnificationController).setScale(3.0f);
    }

    @Test
    public void moveWindowMagnifier() throws RemoteException {
        mIMagnificationConnection.moveWindowMagnifier(TEST_DISPLAY, 100f, 200f);
        processAllPendingMessages();

        verify(mWindowMagnificationController).moveWindowMagnifier(100f, 200f);
    }

    @Test
    public void moveWindowMagnifierToPosition() throws RemoteException {
        mIMagnificationConnection.moveWindowMagnifierToPosition(TEST_DISPLAY,
                100f, 200f, mAnimationCallback);
        processAllPendingMessages();

        verify(mWindowMagnificationController).moveWindowMagnifierToPosition(
                eq(100f), eq(200f), any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DELAY_SHOW_MAGNIFICATION_BUTTON)
    public void showMagnificationButton_flagOff_directlyShowButton() throws RemoteException {
        // magnification settings panel should not be showing
        assertFalse(mMagnification.isMagnificationSettingsPanelShowing(TEST_DISPLAY));

        mIMagnificationConnection.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        processAllPendingMessages();

        verify(mModeSwitchesController).showButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DELAY_SHOW_MAGNIFICATION_BUTTON)
    public void showMagnificationButton_flagOn_delayedShowButton() throws RemoteException {
        // magnification settings panel should not be showing
        assertFalse(mMagnification.isMagnificationSettingsPanelShowing(TEST_DISPLAY));

        mIMagnificationConnection.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        // This processAllPendingMessages lets the IMagnificationConnection to delegate the
        // showMagnificationButton request to Magnification.
        processAllPendingMessages();

        // The delayed message would be processed after DELAY_SHOW_MAGNIFICATION_TIMEOUT_MS.
        // So call this processAllPendingMessages with a timeout to verify the showButton
        // will be called.
        int timeout = DELAY_SHOW_MAGNIFICATION_TIMEOUT_MS + 100;
        processAllPendingMessages(timeout);
        verify(mModeSwitchesController).showButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void showMagnificationButton_settingsPanelShowing_doNotShowButton()
            throws RemoteException {
        when(mMagnificationSettingsController.isMagnificationSettingsShowing()).thenReturn(true);

        mIMagnificationConnection.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        // This processAllPendingMessages lets the IMagnificationConnection to delegate the
        // showMagnificationButton request to Magnification.
        processAllPendingMessages();

        // If the flag is on, the isMagnificationSettingsShowing will be checked after timeout, so
        // process all message after a timeout here to verify the showButton will not be called.
        int timeout = Flags.delayShowMagnificationButton()
                ? DELAY_SHOW_MAGNIFICATION_TIMEOUT_MS + 100
                : 0;
        processAllPendingMessages(timeout);
        verify(mModeSwitchesController, never()).showButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void removeMagnificationButton() throws RemoteException {
        mIMagnificationConnection.removeMagnificationButton(TEST_DISPLAY);
        processAllPendingMessages();

        verify(mModeSwitchesController).removeButton(TEST_DISPLAY);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DELAY_SHOW_MAGNIFICATION_BUTTON)
    public void removeMagnificationButton_delayingShowButton_doNotShowButtonAfterTimeout()
            throws RemoteException {
        // magnification settings panel should not be showing
        assertFalse(mMagnification.isMagnificationSettingsPanelShowing(TEST_DISPLAY));

        mIMagnificationConnection.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        mIMagnificationConnection.removeMagnificationButton(TEST_DISPLAY);
        // This processAllPendingMessages lets the IMagnificationConnection to delegate the
        // requests to Magnification.
        processAllPendingMessages();

        // Call this processAllPendingMessages with a timeout to ensure the delayed show button
        // message should be removed and thus the showButton will not be called after timeout.
        int timeout = DELAY_SHOW_MAGNIFICATION_TIMEOUT_MS + 100;
        processAllPendingMessages(/* timeForwardMs= */ timeout);
        verify(mModeSwitchesController, never()).showButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void removeMagnificationSettingsPanel() throws RemoteException {
        mIMagnificationConnection.removeMagnificationSettingsPanel(TEST_DISPLAY);
        processAllPendingMessages();

        verify(mMagnificationSettingsController).closeMagnificationSettings();
    }

    @Test
    public void onUserMagnificationScaleChanged() throws RemoteException {
        final int testUserId = 1;
        final float testScale = 3.0f;
        mIMagnificationConnection.onUserMagnificationScaleChanged(
                testUserId, TEST_DISPLAY, testScale);
        processAllPendingMessages();

        assertTrue(mMagnification.mUsersScales.contains(testUserId));
        assertEquals(mMagnification.mUsersScales.get(testUserId).get(TEST_DISPLAY),
                (Float) testScale);
        verify(mMagnificationSettingsController).setMagnificationScale(eq(testScale));
    }

    private void processAllPendingMessages() {
        processAllPendingMessages(/* timeForwardMs=*/ 0);
    }

    private void processAllPendingMessages(int timeForwardMs) {
        if (timeForwardMs > 0) {
            mTestableLooper.moveTimeForward(timeForwardMs);
        }
        mTestableLooper.processAllMessages();
    }

    private class FakeWindowMagnificationControllerSupplier extends
            DisplayIdIndexSupplier<WindowMagnificationController> {

        FakeWindowMagnificationControllerSupplier(DisplayManager displayManager) {
            super(displayManager);
        }

        @Override
        protected WindowMagnificationController createInstance(Display display) {
            return mWindowMagnificationController;
        }
    }

    private class FakeFullscreenMagnificationControllerSupplier extends
            DisplayIdIndexSupplier<FullscreenMagnificationController> {

        FakeFullscreenMagnificationControllerSupplier(DisplayManager displayManager) {
            super(displayManager);
        }

        @Override
        protected FullscreenMagnificationController createInstance(Display display) {
            return mFullscreenMagnificationController;
        }
    }

    private class FakeSettingsSupplier extends
            DisplayIdIndexSupplier<MagnificationSettingsController> {

        FakeSettingsSupplier(DisplayManager displayManager) {
            super(displayManager);
        }

        @Override
        protected MagnificationSettingsController createInstance(Display display) {
            return mMagnificationSettingsController;
        }
    }
}

