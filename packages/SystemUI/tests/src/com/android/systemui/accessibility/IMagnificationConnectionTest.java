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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IMagnificationConnectionCallback;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link android.view.accessibility.IMagnificationConnection} retrieved from
 * {@link Magnification}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class IMagnificationConnectionTest extends SysuiTestCase {

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

    private IMagnificationConnection mIMagnificationConnection;
    private Magnification mMagnification;
    private FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        doAnswer(invocation -> {
            mIMagnificationConnection = invocation.getArgument(0);
            return null;
        }).when(mAccessibilityManager).setMagnificationConnection(
                any(IMagnificationConnection.class));
        mMagnification = new Magnification(getContext(),
                getContext().getMainThreadHandler(), mCommandQueue,
                mModeSwitchesController, mSysUiState, mOverviewProxyService, mSecureSettings,
                mDisplayTracker, getContext().getSystemService(DisplayManager.class), mA11yLogger);
        mMagnification.mWindowMagnificationControllerSupplier =
                new FakeWindowMagnificationControllerSupplier(
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
        waitForIdleSync();

        verify(mWindowMagnificationController).enableWindowMagnification(eq(3.0f),
                eq(Float.NaN), eq(Float.NaN), eq(0f), eq(0f), eq(mAnimationCallback));
    }

    @Test
    public void disableWindowMagnification_deleteWindowMagnification() throws RemoteException {
        mIMagnificationConnection.disableWindowMagnification(TEST_DISPLAY,
                mAnimationCallback);
        waitForIdleSync();

        verify(mWindowMagnificationController).deleteWindowMagnification(
                mAnimationCallback);
    }

    @Test
    public void setScaleForWindowMagnification() throws RemoteException {
        mIMagnificationConnection.setScaleForWindowMagnification(TEST_DISPLAY, 3.0f);
        waitForIdleSync();

        verify(mWindowMagnificationController).setScale(3.0f);
    }

    @Test
    public void moveWindowMagnifier() throws RemoteException {
        mIMagnificationConnection.moveWindowMagnifier(TEST_DISPLAY, 100f, 200f);
        waitForIdleSync();

        verify(mWindowMagnificationController).moveWindowMagnifier(100f, 200f);
    }

    @Test
    public void moveWindowMagnifierToPosition() throws RemoteException {
        mIMagnificationConnection.moveWindowMagnifierToPosition(TEST_DISPLAY,
                100f, 200f, mAnimationCallback);
        waitForIdleSync();

        verify(mWindowMagnificationController).moveWindowMagnifierToPosition(
                eq(100f), eq(200f), any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void showMagnificationButton() throws RemoteException {
        // magnification settings panel should not be showing
        assertFalse(mMagnification.isMagnificationSettingsPanelShowing(TEST_DISPLAY));

        mIMagnificationConnection.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        waitForIdleSync();

        verify(mModeSwitchesController).showButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void removeMagnificationButton() throws RemoteException {
        mIMagnificationConnection.removeMagnificationButton(TEST_DISPLAY);
        waitForIdleSync();

        verify(mModeSwitchesController).removeButton(TEST_DISPLAY);
    }

    @Test
    public void removeMagnificationSettingsPanel() throws RemoteException {
        mIMagnificationConnection.removeMagnificationSettingsPanel(TEST_DISPLAY);
        waitForIdleSync();

        verify(mMagnificationSettingsController).closeMagnificationSettings();
    }

    @Test
    public void onUserMagnificationScaleChanged() throws RemoteException {
        final int testUserId = 1;
        final float testScale = 3.0f;
        mIMagnificationConnection.onUserMagnificationScaleChanged(
                testUserId, TEST_DISPLAY, testScale);
        waitForIdleSync();

        assertTrue(mMagnification.mUsersScales.contains(testUserId));
        assertEquals(mMagnification.mUsersScales.get(testUserId).get(TEST_DISPLAY),
                (Float) testScale);
        verify(mMagnificationSettingsController).setMagnificationScale(eq(testScale));
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

