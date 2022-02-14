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

package com.android.wm.shell.onehanded;

import static com.android.wm.shell.onehanded.OneHandedState.STATE_ACTIVE;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_ENTERING;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_EXITING;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_NONE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.om.IOverlayManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.util.ArrayMap;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class OneHandedControllerTest extends OneHandedTestCase {
    private int mCurrentUser = UserHandle.myUserId();

    Display mDisplay;
    DisplayLayout mDisplayLayout;
    OneHandedAccessibilityUtil mOneHandedAccessibilityUtil;
    OneHandedController mSpiedOneHandedController;
    OneHandedTimeoutHandler mSpiedTimeoutHandler;
    OneHandedState mSpiedTransitionState;

    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedBackgroundPanelOrganizer mMockBackgroundOrganizer;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    OneHandedEventCallback mMockEventCallback;
    @Mock
    OneHandedTouchHandler mMockTouchHandler;
    @Mock
    OneHandedTutorialHandler mMockTutorialHandler;
    @Mock
    OneHandedSettingsUtil mMockSettingsUitl;
    @Mock
    OneHandedUiEventLogger mMockUiEventLogger;
    @Mock
    IOverlayManager mMockOverlayManager;
    @Mock
    TaskStackListenerImpl mMockTaskStackListener;
    @Mock
    ShellExecutor mMockShellMainExecutor;
    @Mock
    SurfaceControl mMockLeash;
    @Mock
    Handler mMockShellMainHandler;

    final boolean mDefaultEnabled = true;
    final boolean mDefaultSwipeToNotificationEnabled = false;
    final boolean mDefaultTapAppToExitEnabled = true;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDisplay = mContext.getDisplay();
        mDisplayLayout = new DisplayLayout(mContext, mDisplay);
        mSpiedTimeoutHandler = spy(new OneHandedTimeoutHandler(mMockShellMainExecutor));
        mSpiedTransitionState = spy(new OneHandedState());

        when(mMockDisplayController.getDisplay(anyInt())).thenReturn(mDisplay);
        when(mMockDisplayAreaOrganizer.getDisplayAreaTokenMap()).thenReturn(new ArrayMap<>());
        when(mMockDisplayAreaOrganizer.isReady()).thenReturn(true);
        when(mMockBackgroundOrganizer.isRegistered()).thenReturn(true);
        when(mMockSettingsUitl.getSettingsOneHandedModeEnabled(any(), anyInt())).thenReturn(
                mDefaultEnabled);
        when(mMockSettingsUitl.getSettingsOneHandedModeTimeout(any(), anyInt())).thenReturn(
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);
        when(mMockSettingsUitl.getSettingsTapsAppToExit(any(), anyInt())).thenReturn(
                mDefaultTapAppToExitEnabled);
        when(mMockSettingsUitl.getSettingsSwipeToNotificationEnabled(any(), anyInt())).thenReturn(
                mDefaultSwipeToNotificationEnabled);
        when(mMockSettingsUitl.getShortcutEnabled(any(), anyInt())).thenReturn(false);

        when(mMockDisplayAreaOrganizer.getLastDisplayBounds()).thenReturn(
                new Rect(0, 0, mDisplayLayout.width(), mDisplayLayout.height()));
        when(mMockDisplayAreaOrganizer.getDisplayLayout()).thenReturn(mDisplayLayout);

        mOneHandedAccessibilityUtil = new OneHandedAccessibilityUtil(mContext);
        mSpiedOneHandedController = spy(new OneHandedController(
                mContext,
                mMockDisplayController,
                mMockBackgroundOrganizer,
                mMockDisplayAreaOrganizer,
                mMockTouchHandler,
                mMockTutorialHandler,
                mMockSettingsUitl,
                mOneHandedAccessibilityUtil,
                mSpiedTimeoutHandler,
                mSpiedTransitionState,
                mMockUiEventLogger,
                mMockOverlayManager,
                mMockTaskStackListener,
                mMockShellMainExecutor,
                mMockShellMainHandler)
        );
    }

    @Test
    public void testDefaultShouldNotInOneHanded() {
        // Assert default transition state is STATE_NONE
        assertThat(mSpiedTransitionState.getState()).isEqualTo(STATE_NONE);
    }

    @Test
    public void testStartOneHandedShouldTriggerScheduleOffset() {
        mSpiedTransitionState.setState(STATE_NONE);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testStartOneHandedShouldNotTriggerScheduleOffset() {
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedTransitionState.setState(STATE_ENTERING);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testStopOneHanded() {
        mSpiedTransitionState.setState(STATE_NONE);
        mSpiedOneHandedController.stopOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testRegisterTransitionCallbackAfterInit() {
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mMockTouchHandler);
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mMockTutorialHandler);
    }

    @Test
    public void testRegisterTransitionCallback() {
        OneHandedTransitionCallback callback = new OneHandedTransitionCallback() {
        };
        mSpiedOneHandedController.registerTransitionCallback(callback);

        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(callback);
    }

    @Test
    public void testStopOneHandedShouldRemoveTimer() {
        mSpiedTransitionState.setState(STATE_ENTERING);
        mSpiedOneHandedController.stopOneHanded();

        verify(mSpiedTimeoutHandler, atLeastOnce()).removeTimer();
    }

    @Test
    public void testUpdateEnabled() {
        mSpiedOneHandedController.setOneHandedEnabled(true);

        verify(mMockTouchHandler, atLeastOnce()).onOneHandedEnabled(anyBoolean());
    }

    @Test
    public void testUpdateSwipeToNotification() {
        mSpiedOneHandedController.setSwipeToNotificationEnabled(mDefaultSwipeToNotificationEnabled);

        verify(mMockTouchHandler, atLeastOnce()).onOneHandedEnabled(anyBoolean());
    }

    @Test
    public void testTapAppToExitEnabledAddListener() {
        mSpiedOneHandedController.setTaskChangeToExit(mDefaultTapAppToExitEnabled);

        // If device settings default ON, then addListener() will be trigger 1 time at init
        verify(mMockTaskStackListener, atLeastOnce()).addListener(any());
    }

    @Test
    public void testTapAppToExitDisabledRemoveListener() {
        mSpiedOneHandedController.setTaskChangeToExit(!mDefaultTapAppToExitEnabled);

        // If device settings default ON, then removeListener() will be trigger 1 time at init
        verify(mMockTaskStackListener, atLeastOnce()).removeListener(any());
    }

    @Test
    public void testSettingsObserverUpdateEnabled() {
        mSpiedOneHandedController.onEnabledSettingChanged();

        verify(mSpiedOneHandedController).setOneHandedEnabled(anyBoolean());
    }

    @Test
    public void testSettingsObserverUpdateSwipeToNotification() {
        mSpiedOneHandedController.onSwipeToNotificationEnabledChanged();

        verify(mSpiedOneHandedController).setSwipeToNotificationEnabled(anyBoolean());
    }

    @Test
    public void testLockedOneHandedDisabled() {
        // Default mLockDisabled is false
        assertThat(mSpiedOneHandedController.isLockedDisabled()).isFalse();

        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.setLockedDisabled(false /* locked */, true /* enabled */);

        // If mOneHandedEnabled == enabled, then keep unlocked
        assertThat(mSpiedOneHandedController.isLockedDisabled()).isFalse();

        // If prefer locked enabled state and 'mOneHandedEnabled == enabled', then unlocked
        mSpiedOneHandedController.setLockedDisabled(true /* locked */, true /* enabled */);

        assertThat(mSpiedOneHandedController.isLockedDisabled()).isFalse();

        // If prefer locked disabled state and 'mOneHandedEnabled != enabled', then locked disabled
        mSpiedOneHandedController.setLockedDisabled(true /* locked */, false /* enabled */);

        assertThat(mSpiedOneHandedController.isLockedDisabled()).isTrue();

        // If prefer unlock disabled state and 'mOneHandedEnabled != enabled', then unlocked
        mSpiedOneHandedController.setLockedDisabled(false /* locked */, false /* enabled */);

        assertThat(mSpiedOneHandedController.isLockedDisabled()).isFalse();
    }

    @Test
    public void testKeyguardShowingLockOneHandedDisabled() {
        mSpiedTransitionState.setState(STATE_NONE);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.setLockedDisabled(true /* locked */, false /* enabled */);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testResetKeyguardShowingLockOneHandedDisabled() {
        mSpiedTransitionState.setState(STATE_NONE);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.setLockedDisabled(false /* locked */, false /* enabled */);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testRotation90CanNotStartOneHanded() {
        final DisplayLayout landscapeDisplayLayout = new DisplayLayout(mDisplayLayout);
        landscapeDisplayLayout.rotateTo(mContext.getResources(), Surface.ROTATION_90);
        mSpiedTransitionState.setState(STATE_NONE);
        when(mMockDisplayAreaOrganizer.getDisplayLayout()).thenReturn(landscapeDisplayLayout);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.setLockedDisabled(false /* locked */, false /* enabled */);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testRotation180CanStartOneHanded() {
        final DisplayLayout testDisplayLayout = new DisplayLayout(mDisplayLayout);
        testDisplayLayout.rotateTo(mContext.getResources(), Surface.ROTATION_180);
        mSpiedTransitionState.setState(STATE_NONE);
        when(mMockDisplayAreaOrganizer.isReady()).thenReturn(true);
        when(mMockDisplayAreaOrganizer.getDisplayLayout()).thenReturn(testDisplayLayout);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.setLockedDisabled(false /* locked */, false /* enabled */);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testRotation270CanNotStartOneHanded() {
        final DisplayLayout testDisplayLayout = new DisplayLayout(mDisplayLayout);
        testDisplayLayout.rotateTo(mContext.getResources(), Surface.ROTATION_270);
        mSpiedTransitionState.setState(STATE_NONE);
        when(mMockDisplayAreaOrganizer.getDisplayLayout()).thenReturn(testDisplayLayout);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.setLockedDisabled(false /* locked */, false /* enabled */);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testOneHandedEnabledRotation90ShouldHandleRotate() {
        when(mMockSettingsUitl.getSettingsOneHandedModeEnabled(any(), anyInt())).thenReturn(true);
        when(mMockSettingsUitl.getSettingsSwipeToNotificationEnabled(any(), anyInt())).thenReturn(
                false);
        final WindowContainerTransaction handlerWCT = new WindowContainerTransaction();
        mSpiedOneHandedController.onRotateDisplay(mDisplay.getDisplayId(), Surface.ROTATION_0,
                Surface.ROTATION_90, handlerWCT);

        verify(mMockDisplayAreaOrganizer, atLeastOnce()).onRotateDisplay(eq(mContext),
                eq(Surface.ROTATION_90), any(WindowContainerTransaction.class));
    }

    @Test
    public void testOneHandedDisabledRotation90ShouldNotHandleRotate() {
        when(mMockSettingsUitl.getSettingsOneHandedModeEnabled(any(), anyInt())).thenReturn(false);
        when(mMockSettingsUitl.getSettingsSwipeToNotificationEnabled(any(), anyInt())).thenReturn(
                false);
        final WindowContainerTransaction handlerWCT = new WindowContainerTransaction();
        mSpiedOneHandedController.onRotateDisplay(mDisplay.getDisplayId(), Surface.ROTATION_0,
                Surface.ROTATION_90, handlerWCT);

        verify(mMockDisplayAreaOrganizer, never()).onRotateDisplay(eq(mContext),
                eq(Surface.ROTATION_90), any(WindowContainerTransaction.class));
    }

    @Test
    public void testSwipeToNotificationEnabledRotation90ShouldNotHandleRotate() {
        when(mMockSettingsUitl.getSettingsOneHandedModeEnabled(any(), anyInt())).thenReturn(true);
        when(mMockSettingsUitl.getSettingsSwipeToNotificationEnabled(any(), anyInt())).thenReturn(
                true);
        final WindowContainerTransaction handlerWCT = new WindowContainerTransaction();
        mSpiedOneHandedController.onRotateDisplay(mDisplay.getDisplayId(), Surface.ROTATION_0,
                Surface.ROTATION_90, handlerWCT);

        verify(mMockDisplayAreaOrganizer, never()).onRotateDisplay(eq(mContext),
                eq(Surface.ROTATION_90), any(WindowContainerTransaction.class));
    }

    @Test
    public void testSwipeToNotificationDisabledRotation90ShouldHandleRotate() {
        when(mMockSettingsUitl.getSettingsOneHandedModeEnabled(any(), anyInt())).thenReturn(true);
        when(mMockSettingsUitl.getSettingsSwipeToNotificationEnabled(any(), anyInt())).thenReturn(
                false);
        final WindowContainerTransaction handlerWCT = new WindowContainerTransaction();
        mSpiedOneHandedController.onRotateDisplay(mDisplay.getDisplayId(), Surface.ROTATION_0,
                Surface.ROTATION_90, handlerWCT);

        verify(mMockDisplayAreaOrganizer, atLeastOnce()).onRotateDisplay(eq(mContext),
                eq(Surface.ROTATION_90), any(WindowContainerTransaction.class));
    }

    @Test
    public void testStateActive_shortcutRequestActivate_skipActions() {
        when(mSpiedTransitionState.getState()).thenReturn(STATE_ACTIVE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mMockSettingsUitl.getOneHandedModeActivated(any(), anyInt())).thenReturn(true);
        when(mSpiedOneHandedController.isShortcutEnabled()).thenReturn(true);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mSpiedOneHandedController, never()).startOneHanded();
        verify(mSpiedOneHandedController, never()).stopOneHanded();
    }

    @Test
    public void testStateNotActive_shortcutRequestInActivate_skipAction() {
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mMockSettingsUitl.getOneHandedModeActivated(any(), anyInt())).thenReturn(false);
        when(mSpiedOneHandedController.isShortcutEnabled()).thenReturn(true);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mSpiedOneHandedController, never()).startOneHanded();
        verify(mSpiedOneHandedController, never()).stopOneHanded();
    }

    @Test
    public void testStateNotActive_shortcutRequestActivate_doAction() {
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mMockSettingsUitl.getOneHandedModeActivated(any(), anyInt())).thenReturn(true);
        when(mSpiedOneHandedController.isShortcutEnabled()).thenReturn(true);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mSpiedOneHandedController).startOneHanded();
        verify(mSpiedOneHandedController, never()).stopOneHanded();
    }

    @Test
    public void testEnteringTransition_shortcutRequestActivate_skipActions() {
        when(mSpiedTransitionState.getState()).thenReturn(STATE_ENTERING);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(true);
        when(mMockSettingsUitl.getOneHandedModeActivated(any(), anyInt())).thenReturn(true);
        when(mSpiedOneHandedController.isShortcutEnabled()).thenReturn(true);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mSpiedTransitionState, never()).setState(STATE_EXITING);
    }

    @Test
    public void testExitingTransition_shortcutRequestActivate_skipActions() {
        when(mSpiedTransitionState.getState()).thenReturn(STATE_EXITING);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(true);
        when(mMockSettingsUitl.getOneHandedModeActivated(any(), anyInt())).thenReturn(true);
        when(mSpiedOneHandedController.isShortcutEnabled()).thenReturn(true);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mSpiedTransitionState, never()).setState(STATE_ENTERING);
    }

    @Test
    public void testOneHandedDisabled_shortcutTrigger_thenAutoEnabled() {
        when(mSpiedOneHandedController.isOneHandedEnabled()).thenReturn(false);
        when(mSpiedOneHandedController.isShortcutEnabled()).thenReturn(true);
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mMockSettingsUitl.getOneHandedModeActivated(any(), anyInt())).thenReturn(false);
        when(mMockSettingsUitl.setOneHandedModeEnabled(any(), anyInt(), anyInt())).thenReturn(
                false);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mMockSettingsUitl).setOneHandedModeEnabled(any(), eq(1), anyInt());
    }

    @Test
    public void testControllerInit_tutorialAddStateChangeListener() {
        when(mSpiedOneHandedController.isOneHandedEnabled()).thenReturn(true);
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mMockSettingsUitl.getOneHandedModeActivated(any(), anyInt())).thenReturn(false);

        verify(mSpiedTransitionState).addSListeners(mMockTutorialHandler);
    }

    @Test
    public void testNotifyEventCallbackWithMainExecutor() {
        when(mSpiedOneHandedController.isOneHandedEnabled()).thenReturn(true);
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mSpiedOneHandedController.isShortcutEnabled()).thenReturn(true);
        when(mSpiedOneHandedController.isSwipeToNotificationEnabled()).thenReturn(true);
        mSpiedOneHandedController.registerEventCallback(mMockEventCallback);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mMockShellMainExecutor).execute(any());
    }

    @Test
    public void testNotifyShortcutState_whenSetOneHandedEnabled() {
        when(mSpiedOneHandedController.isOneHandedEnabled()).thenReturn(true);
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mSpiedOneHandedController.isSwipeToNotificationEnabled()).thenReturn(false);
        mSpiedOneHandedController.registerEventCallback(mMockEventCallback);
        mSpiedOneHandedController.setOneHandedEnabled(true);

        verify(mSpiedOneHandedController).notifyShortcutStateChanged(anyInt());
    }

    @Test
    public void testNotifyExpandNotification_withNullCheckProtection() {
        when(mSpiedOneHandedController.isOneHandedEnabled()).thenReturn(false);
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mSpiedOneHandedController.isSwipeToNotificationEnabled()).thenReturn(true);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.notifyExpandNotification();

        // Verify no NPE crash and mMockShellMainExecutor never be execute.
        verify(mMockShellMainExecutor, never()).execute(any());
    }

    @Test
    public void testShortcutEnable_ableToAutoEnableOneHandedMode() {
        when(mSpiedOneHandedController.isOneHandedEnabled()).thenReturn(false);
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mSpiedOneHandedController.isShortcutEnabled()).thenReturn(true);
        when(mSpiedOneHandedController.isSwipeToNotificationEnabled()).thenReturn(false);
        when(mMockSettingsUitl.setOneHandedModeEnabled(any(), anyInt(), anyInt())).thenReturn(
                false /* To avoid test runner create Toast */);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mMockSettingsUitl).setOneHandedModeEnabled(any(), eq(1), anyInt());
    }

    @Test
    public void testShortcutDisable_shouldNotAutoEnableOneHandedMode() {
        when(mSpiedOneHandedController.isOneHandedEnabled()).thenReturn(false);
        when(mSpiedTransitionState.getState()).thenReturn(STATE_NONE);
        when(mSpiedTransitionState.isTransitioning()).thenReturn(false);
        when(mSpiedOneHandedController.isSwipeToNotificationEnabled()).thenReturn(false);
        when(mMockSettingsUitl.setOneHandedModeEnabled(any(), anyInt(), anyInt())).thenReturn(true);
        mSpiedOneHandedController.onActivatedActionChanged();

        verify(mMockSettingsUitl, never()).setOneHandedModeEnabled(any(), anyInt(), anyInt());
    }
}
