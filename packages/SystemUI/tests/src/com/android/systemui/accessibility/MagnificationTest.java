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

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;

import static com.android.systemui.accessibility.AccessibilityLogger.MagnificationSettingsEvent;
import static com.android.systemui.accessibility.WindowMagnificationSettings.MagnificationSize;
import static com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_MAGNIFICATION_OVERLAP;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IMagnificationConnectionCallback;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class MagnificationTest extends SysuiTestCase {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private ModeSwitchesController mModeSwitchesController;
    @Mock
    private SysUiState mSysUiState;
    @Mock
    private IMagnificationConnectionCallback mConnectionCallback;
    @Mock
    private OverviewProxyService mOverviewProxyService;
    @Mock
    private SecureSettings mSecureSettings;

    private CommandQueue mCommandQueue;
    private Magnification mMagnification;
    private OverviewProxyListener mOverviewProxyListener;
    private FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);

    @Mock
    private WindowMagnificationController mWindowMagnificationController;
    @Mock
    private MagnificationSettingsController mMagnificationSettingsController;
    @Mock
    private AccessibilityLogger mA11yLogger;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        doAnswer(invocation -> {
            IMagnificationConnection connection = invocation.getArgument(0);
            connection.setConnectionCallback(mConnectionCallback);
            return null;
        }).when(mAccessibilityManager).setMagnificationConnection(
                any(IMagnificationConnection.class));

        when(mSysUiState.setFlag(anyInt(), anyBoolean())).thenReturn(mSysUiState);

        doAnswer(invocation -> {
            mMagnification.mMagnificationSettingsControllerCallback
                    .onSettingsPanelVisibilityChanged(TEST_DISPLAY, /* shown= */ true);
            return null;
        }).when(mMagnificationSettingsController).toggleSettingsPanelVisibility();
        doAnswer(invocation -> {
            mMagnification.mMagnificationSettingsControllerCallback
                    .onSettingsPanelVisibilityChanged(TEST_DISPLAY, /* shown= */ false);
            return null;
        }).when(mMagnificationSettingsController).closeMagnificationSettings();

        when(mWindowMagnificationController.isActivated()).thenReturn(true);

        mCommandQueue = new CommandQueue(getContext(), mDisplayTracker);
        mMagnification = new Magnification(getContext(),
                getContext().getMainThreadHandler(), getContext().getMainExecutor(),
                mCommandQueue, mModeSwitchesController,
                mSysUiState, mOverviewProxyService, mSecureSettings, mDisplayTracker,
                getContext().getSystemService(DisplayManager.class), mA11yLogger);
        mMagnification.mWindowMagnificationControllerSupplier = new FakeControllerSupplier(
                mContext.getSystemService(DisplayManager.class), mWindowMagnificationController);
        mMagnification.mMagnificationSettingsSupplier = new FakeSettingsSupplier(
                mContext.getSystemService(DisplayManager.class), mMagnificationSettingsController);
        mMagnification.start();

        final ArgumentCaptor<OverviewProxyListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(OverviewProxyListener.class);
        verify(mOverviewProxyService).addCallback(listenerArgumentCaptor.capture());
        mOverviewProxyListener = listenerArgumentCaptor.getValue();
    }

    @Test
    public void requestWindowMagnificationConnection_setConnectionAndListener() {
        mCommandQueue.requestMagnificationConnection(true);
        waitForIdleSync();

        verify(mAccessibilityManager).setMagnificationConnection(any(
                IMagnificationConnection.class));

        mCommandQueue.requestMagnificationConnection(false);
        waitForIdleSync();

        verify(mAccessibilityManager).setMagnificationConnection(isNull());
    }

    @Test
    public void onWindowMagnifierBoundsChanged() throws RemoteException {
        final Rect testBounds = new Rect(0, 0, 500, 600);
        mCommandQueue.requestMagnificationConnection(true);
        waitForIdleSync();

        mMagnification.mWindowMagnifierCallback
                .onWindowMagnifierBoundsChanged(TEST_DISPLAY, testBounds);

        verify(mConnectionCallback).onWindowMagnifierBoundsChanged(TEST_DISPLAY, testBounds);
    }

    @Test
    public void onPerformScaleAction_enabled_notifyCallback() throws RemoteException {
        final float newScale = 4.0f;
        final boolean updatePersistence = true;
        mCommandQueue.requestMagnificationConnection(true);
        waitForIdleSync();

        mMagnification.mWindowMagnifierCallback
                .onPerformScaleAction(TEST_DISPLAY, newScale, updatePersistence);

        verify(mConnectionCallback).onPerformScaleAction(
                eq(TEST_DISPLAY), eq(newScale), eq(updatePersistence));
    }

    @Test
    public void onAccessibilityActionPerformed_enabled_notifyCallback() throws RemoteException {
        mCommandQueue.requestMagnificationConnection(true);
        waitForIdleSync();

        mMagnification.mWindowMagnifierCallback
                .onAccessibilityActionPerformed(TEST_DISPLAY);

        verify(mConnectionCallback).onAccessibilityActionPerformed(TEST_DISPLAY);
    }

    @Test
    public void onMove_enabled_notifyCallback() throws RemoteException {
        mCommandQueue.requestMagnificationConnection(true);
        waitForIdleSync();

        mMagnification.mWindowMagnifierCallback.onMove(TEST_DISPLAY);

        verify(mConnectionCallback).onMove(TEST_DISPLAY);
    }

    @Test
    public void onClickSettingsButton_enabled_showPanelForWindowMode() {
        mMagnification.mWindowMagnifierCallback.onClickSettingsButton(TEST_DISPLAY);
        waitForIdleSync();

        verify(mMagnificationSettingsController).toggleSettingsPanelVisibility();
        verify(mA11yLogger).logWithPosition(
                eq(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_OPENED),
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW)
        );
    }

    @Test
    public void onSetMagnifierSize_delegateToMagnifier() {
        final @MagnificationSize int index = MagnificationSize.SMALL;
        mMagnification.mMagnificationSettingsControllerCallback.onSetMagnifierSize(
                TEST_DISPLAY, index);
        waitForIdleSync();

        verify(mWindowMagnificationController).changeMagnificationSize(eq(index));
        verify(mA11yLogger).logWithPosition(
                eq(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_WINDOW_SIZE_SELECTED),
                eq(index)
        );
    }

    @Test
    public void onSetDiagonalScrolling_delegateToMagnifier() {
        mMagnification.mMagnificationSettingsControllerCallback.onSetDiagonalScrolling(
                TEST_DISPLAY, /* enable= */ true);
        waitForIdleSync();

        verify(mWindowMagnificationController).setDiagonalScrolling(eq(true));
    }

    @Test
    public void onEditMagnifierSizeMode_windowActivated_delegateToMagnifier() {
        when(mWindowMagnificationController.isActivated()).thenReturn(true);
        mMagnification.mMagnificationSettingsControllerCallback.onEditMagnifierSizeMode(
                TEST_DISPLAY, /* enable= */ true);
        waitForIdleSync();

        verify(mWindowMagnificationController).setEditMagnifierSizeMode(eq(true));
        verify(mA11yLogger).log(
                eq(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_SIZE_EDITING_ACTIVATED));

        mMagnification.mMagnificationSettingsControllerCallback.onEditMagnifierSizeMode(
                TEST_DISPLAY, /* enable= */ false);
        waitForIdleSync();
        verify(mA11yLogger).log(
                eq(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_SIZE_EDITING_ACTIVATED));
        verify(mA11yLogger).log(
                eq(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_SIZE_EDITING_DEACTIVATED));
    }

    @Test
    public void onMagnifierScale_notifyCallback() throws RemoteException {
        mCommandQueue.requestMagnificationConnection(true);
        waitForIdleSync();
        final float scale = 3.0f;
        final boolean updatePersistence = false;
        mMagnification.mMagnificationSettingsControllerCallback.onMagnifierScale(
                TEST_DISPLAY, scale, updatePersistence);

        verify(mConnectionCallback).onPerformScaleAction(
                eq(TEST_DISPLAY), eq(scale), eq(updatePersistence));
        verify(mA11yLogger).logThrottled(
                eq(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_ZOOM_SLIDER_CHANGED));
    }

    @Test
    public void onModeSwitch_windowEnabledAndSwitchToFullscreen_hidePanelAndNotifyCallback()
            throws RemoteException {
        when(mWindowMagnificationController.isActivated()).thenReturn(true);
        mCommandQueue.requestMagnificationConnection(true);
        waitForIdleSync();

        mMagnification.mMagnificationSettingsControllerCallback.onModeSwitch(
                TEST_DISPLAY, ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        waitForIdleSync();

        verify(mMagnificationSettingsController).closeMagnificationSettings();
        verify(mA11yLogger).log(
                eq(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_CLOSED));
        verify(mConnectionCallback).onChangeMagnificationMode(eq(TEST_DISPLAY),
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN));
    }

    @Test
    public void onModeSwitch_switchToSameMode_doNothing()
            throws RemoteException {
        when(mWindowMagnificationController.isActivated()).thenReturn(true);
        mCommandQueue.requestMagnificationConnection(true);
        waitForIdleSync();

        mMagnification.mMagnificationSettingsControllerCallback.onModeSwitch(
                TEST_DISPLAY, ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        waitForIdleSync();

        verify(mMagnificationSettingsController, never()).closeMagnificationSettings();
        verify(mConnectionCallback, never()).onChangeMagnificationMode(eq(TEST_DISPLAY),
                /* magnificationMode = */ anyInt());
    }

    @Test
    public void onSettingsPanelVisibilityChanged_windowActivated_delegateToMagnifier() {
        when(mWindowMagnificationController.isActivated()).thenReturn(true);
        final boolean shown = false;
        mMagnification.mMagnificationSettingsControllerCallback
                .onSettingsPanelVisibilityChanged(TEST_DISPLAY, shown);
        waitForIdleSync();

        verify(mWindowMagnificationController).updateDragHandleResourcesIfNeeded(eq(shown));
        verify(mA11yLogger).log(
                eq(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_CLOSED));
    }

    @Test
    public void overviewProxyIsConnected_noController_resetFlag() {
        mOverviewProxyListener.onConnectionChanged(true);

        verify(mSysUiState).setFlag(SYSUI_STATE_MAGNIFICATION_OVERLAP, false);
        verify(mSysUiState).commitUpdate(mContext.getDisplayId());
    }

    @Test
    public void overviewProxyIsConnected_controllerIsAvailable_updateSysUiStateFlag() {
        final WindowMagnificationController mController = mock(WindowMagnificationController.class);
        mMagnification.mWindowMagnificationControllerSupplier = new FakeControllerSupplier(
                mContext.getSystemService(DisplayManager.class), mController);
        mMagnification.mWindowMagnificationControllerSupplier.get(TEST_DISPLAY);

        mOverviewProxyListener.onConnectionChanged(true);

        verify(mController).updateSysUIStateFlag();
    }

    private static class FakeControllerSupplier extends
            DisplayIdIndexSupplier<WindowMagnificationController> {

        private final WindowMagnificationController mController;

        FakeControllerSupplier(DisplayManager displayManager,
                WindowMagnificationController controller) {
            super(displayManager);
            mController = controller;
        }

        @Override
        protected WindowMagnificationController createInstance(Display display) {
            return mController;
        }
    }

    private static class FakeSettingsSupplier extends
            DisplayIdIndexSupplier<MagnificationSettingsController> {

        private final MagnificationSettingsController mController;

        FakeSettingsSupplier(DisplayManager displayManager,
                MagnificationSettingsController controller) {
            super(displayManager);
            mController = controller;
        }

        @Override
        protected MagnificationSettingsController createInstance(Display display) {
            return mController;
        }
    }
}
