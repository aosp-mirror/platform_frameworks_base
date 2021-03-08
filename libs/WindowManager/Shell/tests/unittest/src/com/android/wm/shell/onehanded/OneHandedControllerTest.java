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

import static android.window.DisplayAreaOrganizer.FEATURE_ONE_HANDED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.om.IOverlayManager;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.util.ArrayMap;
import android.view.Display;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;
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
    Display mDisplay;
    OneHandedController mSpiedOneHandedController;
    OneHandedTimeoutHandler mSpiedTimeoutHandler;

    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedBackgroundPanelOrganizer mMockBackgroundOrganizer;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    OneHandedTouchHandler mMockTouchHandler;
    @Mock
    OneHandedTutorialHandler mMockTutorialHandler;
    @Mock
    OneHandedGestureHandler mMockGestureHandler;
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

    final boolean mDefaultEnabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
            getTestContext().getContentResolver());
    final boolean mDefaultSwipeToNotificationEnabled =
            OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                    getTestContext().getContentResolver());
    final boolean mDefaultTapAppToExitEnabled = OneHandedSettingsUtil.getSettingsTapsAppToExit(
            getTestContext().getContentResolver());

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDisplay = mContext.getDisplay();
        mSpiedTimeoutHandler = spy(new OneHandedTimeoutHandler(mMockShellMainExecutor));

        when(mMockDisplayController.getDisplay(anyInt())).thenReturn(mDisplay);
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
        when(mMockDisplayAreaOrganizer.getDisplayAreaTokenMap()).thenReturn(new ArrayMap<>());
        when(mMockBackgroundOrganizer.getBackgroundSurface()).thenReturn(mMockLeash);

        mSpiedOneHandedController = spy(new OneHandedController(
                mContext,
                mWindowManager,
                mMockDisplayController,
                mMockBackgroundOrganizer,
                mMockDisplayAreaOrganizer,
                mMockTouchHandler,
                mMockTutorialHandler,
                mMockGestureHandler,
                mSpiedTimeoutHandler,
                mMockUiEventLogger,
                mMockOverlayManager,
                mMockTaskStackListener,
                mMockShellMainExecutor,
                mMockShellMainHandler)
        );
    }

    @Test
    public void testDefaultShouldNotInOneHanded() {
        final OneHandedAnimationController animationController = new OneHandedAnimationController(
                mContext);
        OneHandedDisplayAreaOrganizer displayAreaOrganizer = new OneHandedDisplayAreaOrganizer(
                mContext, mWindowManager, mMockDisplayController, animationController,
                mMockTutorialHandler, mMockBackgroundOrganizer, mMockShellMainExecutor);

        assertThat(displayAreaOrganizer.isInOneHanded()).isFalse();
    }

    @Test
    public void testNoRegisterAndUnregisterInSameCall() {
        if (mDefaultEnabled) {
            verify(mMockDisplayAreaOrganizer, never()).unregisterOrganizer();
        } else {
            verify(mMockDisplayAreaOrganizer, never()).registerOrganizer(FEATURE_ONE_HANDED);
        }
    }

    @Test
    public void testStartOneHandedShouldTriggerScheduleOffset() {
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testStartOneHandedShouldNotTriggerScheduleOffset() {
        mSpiedOneHandedController.setOneHandedEnabled(true);
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(true);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testStopOneHanded() {
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
        mSpiedOneHandedController.stopOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testRegisterTransitionCallbackAfterInit() {
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mMockTouchHandler);
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mMockGestureHandler);
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
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(true);
        mSpiedOneHandedController.stopOneHanded();

        verify(mSpiedTimeoutHandler, atLeastOnce()).removeTimer();
    }

    @Test
    public void testUpdateEnabled() {
        mSpiedOneHandedController.setOneHandedEnabled(true);

        verify(mMockTouchHandler, atLeastOnce()).onOneHandedEnabled(mDefaultEnabled);
        verify(mMockGestureHandler, atLeastOnce()).onOneHandedEnabled(
                mDefaultEnabled || mDefaultSwipeToNotificationEnabled);
    }

    @Test
    public void testUpdateSwipeToNotification() {
        mSpiedOneHandedController.setSwipeToNotificationEnabled(mDefaultSwipeToNotificationEnabled);

        verify(mMockTouchHandler, atLeastOnce()).onOneHandedEnabled(mDefaultEnabled);
        verify(mMockGestureHandler, atLeastOnce()).onOneHandedEnabled(
                mDefaultEnabled || mDefaultSwipeToNotificationEnabled);
    }

    @Test
    public void testSettingsObserverUpdateTapAppToExit() {
        mSpiedOneHandedController.onTaskChangeExitSettingChanged();
        if (mDefaultTapAppToExitEnabled) {
            verify(mMockTaskStackListener, atLeastOnce()).addListener(any());
        } else {
            verify(mMockTaskStackListener, atLeastOnce()).removeListener(any());
        }
    }

    @Test
    public void testSettingsObserverUpdateEnabled() {
        mSpiedOneHandedController.onEnabledSettingChanged();

        verify(mSpiedOneHandedController).setOneHandedEnabled(mDefaultEnabled);
    }

    @Test
    public void testSettingsObserverUpdateTimeout() {
        mSpiedOneHandedController.onTimeoutSettingChanged();

        verify(mSpiedTimeoutHandler, atLeastOnce()).setTimeout(anyInt());
    }

    @Test
    public void testSettingsObserverUpdateSwipeToNotification() {
        mSpiedOneHandedController.onSwipeToNotificationEnabledSettingChanged();

        // Swipe to notification function is opposite with one handed mode function
        if (mDefaultSwipeToNotificationEnabled) {
            verify(mSpiedOneHandedController).setSwipeToNotificationEnabled(
                    mDefaultSwipeToNotificationEnabled);
        } else {
            verify(mSpiedOneHandedController, never()).setSwipeToNotificationEnabled(
                    mDefaultSwipeToNotificationEnabled);
        }
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
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.setLockedDisabled(true /* locked */, false /* enabled */);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testResetKeyguardShowingLockOneHandedDisabled() {
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
        mSpiedOneHandedController.setOneHandedEnabled(true);
        mSpiedOneHandedController.setLockedDisabled(false /* locked */, false /* enabled */);
        mSpiedOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer).scheduleOffset(anyInt(), anyInt());
    }
}
