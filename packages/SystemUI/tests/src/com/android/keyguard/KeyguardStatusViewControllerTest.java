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

package com.android.keyguard;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.AnimatorTestRule;
import android.platform.test.annotations.DisableFlags;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.app.animation.Interpolators;
import com.android.systemui.Flags;
import com.android.systemui.animation.ViewHierarchyAnimator;
import com.android.systemui.plugins.clocks.ClockConfig;
import com.android.systemui.plugins.clocks.ClockController;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner.class)
public class KeyguardStatusViewControllerTest extends KeyguardStatusViewControllerBaseTest {

    @Rule
    public final AnimatorTestRule mAnimatorTestRule = new AnimatorTestRule(this);

    @Test
    public void dozeTimeTick_updatesSlice() {
        mController.dozeTimeTick();
        verify(mKeyguardSliceViewController).refresh();
    }

    @Test
    public void dozeTimeTick_updatesClock() {
        mController.dozeTimeTick();
        verify(mKeyguardClockSwitchController).refresh();
    }

    @Test
    public void setTranslationYExcludingMedia_forwardsCallToView() {
        float translationY = 123f;

        mController.setTranslationY(translationY, /* excludeMedia= */true);

        verify(mKeyguardStatusView).setChildrenTranslationY(translationY, /* excludeMedia= */true);
    }

    @Test
    @DisableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onLocaleListChangedNotifiesClockSwitchController() {
        ArgumentCaptor<ConfigurationListener> configurationListenerArgumentCaptor =
                ArgumentCaptor.forClass(ConfigurationListener.class);

        mController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());

        configurationListenerArgumentCaptor.getValue().onLocaleListChanged();
        verify(mKeyguardClockSwitchController).onLocaleListChanged();
    }

    @Test
    public void updatePosition_primaryClockAnimation() {
        ClockController mockClock = mock(ClockController.class);
        when(mKeyguardClockSwitchController.getClock()).thenReturn(mockClock);
        when(mockClock.getConfig()).thenReturn(new ClockConfig("MOCK", "", "", false, true, false));

        mController.updatePosition(10, 15, 20f, true);

        verify(mControllerMock).setProperty(AnimatableProperty.Y, 15f, true);
        verify(mKeyguardClockSwitchController).updatePosition(
                10, 20f, KeyguardStatusViewController.CLOCK_ANIMATION_PROPERTIES, true);
        verify(mControllerMock).setProperty(AnimatableProperty.SCALE_X, 1f, true);
        verify(mControllerMock).setProperty(AnimatableProperty.SCALE_Y, 1f, true);
    }

    @Test
    public void updatePosition_alternateClockAnimation() {
        ClockController mockClock = mock(ClockController.class);
        when(mKeyguardClockSwitchController.getClock()).thenReturn(mockClock);
        when(mockClock.getConfig()).thenReturn(new ClockConfig("MOCK", "", "", true, true, false));

        mController.updatePosition(10, 15, 20f, true);

        verify(mControllerMock).setProperty(AnimatableProperty.Y, 15f, true);
        verify(mKeyguardClockSwitchController).updatePosition(
                10, 1f, KeyguardStatusViewController.CLOCK_ANIMATION_PROPERTIES, true);
        verify(mControllerMock).setProperty(AnimatableProperty.SCALE_X, 20f, true);
        verify(mControllerMock).setProperty(AnimatableProperty.SCALE_Y, 20f, true);
    }

    @Test
    public void splitShadeEnabledPassedToClockSwitchController() {
        mController.setSplitShadeEnabled(true);
        verify(mKeyguardClockSwitchController, times(1)).setSplitShadeEnabled(true);
        verify(mKeyguardClockSwitchController, times(0)).setSplitShadeEnabled(false);
    }

    @Test
    public void splitShadeDisabledPassedToClockSwitchController() {
        mController.setSplitShadeEnabled(false);
        verify(mKeyguardClockSwitchController, times(1)).setSplitShadeEnabled(false);
        verify(mKeyguardClockSwitchController, times(0)).setSplitShadeEnabled(true);
    }

    @Test
    public void correctlyDump() {
        mController.onInit();
        verify(mDumpManager).registerDumpable(eq(mController.getInstanceName()), eq(mController));
        mController.onDestroy();
        verify(mDumpManager, times(1)).unregisterDumpable(eq(mController.getInstanceName()));
    }

    @Test
    public void onInit_addsOnLayoutChangeListenerToClockSwitch() {
        when(mKeyguardStatusView.findViewById(R.id.status_view_media_container)).thenReturn(
                mMediaHostContainer);

        mController.onInit();

        ArgumentCaptor<View.OnLayoutChangeListener> captor =
                ArgumentCaptor.forClass(View.OnLayoutChangeListener.class);
        verify(mKeyguardClockSwitch).addOnLayoutChangeListener(captor.capture());
    }

    @Test
    public void clockSwitchHeightChanged_animatesMediaHostContainer() {
        when(mKeyguardStatusView.findViewById(R.id.status_view_media_container)).thenReturn(
                mMediaHostContainer);

        mController.onInit();

        ArgumentCaptor<View.OnLayoutChangeListener> captor =
                ArgumentCaptor.forClass(View.OnLayoutChangeListener.class);
        verify(mKeyguardClockSwitch).addOnLayoutChangeListener(captor.capture());

        // Above here is the same as `onInit_addsOnLayoutChangeListenerToClockSwitch`.
        // Below here is the actual test.

        ViewHierarchyAnimator.Companion animator = ViewHierarchyAnimator.Companion;
        ViewHierarchyAnimator.Companion spiedAnimator = spy(animator);
        setCompanion(spiedAnimator);

        View.OnLayoutChangeListener listener = captor.getValue();

        mController.setSplitShadeEnabled(true);
        when(mKeyguardClockSwitch.getSplitShadeCentered()).thenReturn(false);
        when(mKeyguardUpdateMonitor.isKeyguardVisible()).thenReturn(true);
        when(mMediaHostContainer.getVisibility()).thenReturn(View.VISIBLE);
        when(mMediaHostContainer.getHeight()).thenReturn(200);

        when(mKeyguardClockSwitch.getHeight()).thenReturn(0);
        listener.onLayoutChange(mKeyguardClockSwitch, /* left= */ 0, /* top= */ 0, /* right= */
                0, /* bottom= */ 0, /* oldLeft= */ 0, /* oldTop= */ 0, /* oldRight= */
                0, /* oldBottom = */ 200);
        verify(spiedAnimator).animateNextUpdate(mMediaHostContainer,
                Interpolators.STANDARD, /* duration= */ 500L, /* animateChildren= */ false);

        // Resets ViewHierarchyAnimator.Companion to its original value
        setCompanion(animator);
    }

    @Test
    public void clockSwitchHeightNotChanged_doesNotAnimateMediaOutputContainer() {
        when(mKeyguardStatusView.findViewById(R.id.status_view_media_container)).thenReturn(
                mMediaHostContainer);

        mController.onInit();

        ArgumentCaptor<View.OnLayoutChangeListener> captor =
                ArgumentCaptor.forClass(View.OnLayoutChangeListener.class);
        verify(mKeyguardClockSwitch).addOnLayoutChangeListener(captor.capture());

        // Above here is the same as `onInit_addsOnLayoutChangeListenerToClockSwitch`.
        // Below here is the actual test.

        ViewHierarchyAnimator.Companion animator = ViewHierarchyAnimator.Companion;
        ViewHierarchyAnimator.Companion spiedAnimator = spy(animator);
        setCompanion(spiedAnimator);

        View.OnLayoutChangeListener listener = captor.getValue();

        mController.setSplitShadeEnabled(true);
        when(mKeyguardClockSwitch.getSplitShadeCentered()).thenReturn(false);
        when(mKeyguardUpdateMonitor.isKeyguardVisible()).thenReturn(true);
        when(mMediaHostContainer.getVisibility()).thenReturn(View.VISIBLE);
        when(mMediaHostContainer.getHeight()).thenReturn(200);

        when(mKeyguardClockSwitch.getHeight()).thenReturn(200);
        listener.onLayoutChange(mKeyguardClockSwitch, /* left= */ 0, /* top= */ 0, /* right= */
                0, /* bottom= */ 0, /* oldLeft= */ 0, /* oldTop= */ 0, /* oldRight= */
                0, /* oldBottom = */ 200);
        verify(spiedAnimator, never()).animateNextUpdate(any(), any(), anyLong(), anyBoolean());

        // Resets ViewHierarchyAnimator.Companion to its original value
        setCompanion(animator);
    }

    private void setCompanion(ViewHierarchyAnimator.Companion companion) {
        try {
            Field field = ViewHierarchyAnimator.class.getDeclaredField("Companion");
            field.setAccessible(true);
            field.set(null, companion);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void statusAreaHeightChange_animatesHeightOutputChange() {
        // Init & Capture Layout Listener
        mController.onInit();
        mController.onViewAttached();

        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        ArgumentCaptor<View.OnLayoutChangeListener> captor =
                ArgumentCaptor.forClass(View.OnLayoutChangeListener.class);
        verify(mKeyguardStatusAreaView).addOnLayoutChangeListener(captor.capture());
        View.OnLayoutChangeListener listener = captor.getValue();

        // Setup and validate initial height
        when(mKeyguardStatusView.getHeight()).thenReturn(200);
        when(mKeyguardClockSwitchController.getNotificationIconAreaHeight()).thenReturn(10);
        assertEquals(190, mController.getLockscreenHeight());

        // Trigger Change and validate value unchanged immediately
        when(mKeyguardStatusAreaView.getHeight()).thenReturn(100);
        when(mKeyguardStatusView.getHeight()).thenReturn(300);      // Include child height
        listener.onLayoutChange(mKeyguardStatusAreaView,
            /* new layout */ 100, 300, 200, 400,
            /* old layout */ 100, 300, 200, 300);
        assertEquals(190, mController.getLockscreenHeight());

        // Complete animation, validate height increased
        mAnimatorTestRule.advanceTimeBy(200);
        assertEquals(290, mController.getLockscreenHeight());
    }
}
