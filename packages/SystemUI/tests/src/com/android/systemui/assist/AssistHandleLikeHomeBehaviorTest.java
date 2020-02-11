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

package com.android.systemui.assist;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.QuickStepContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AssistHandleLikeHomeBehaviorTest extends SysuiTestCase {

    private AssistHandleLikeHomeBehavior mAssistHandleLikeHomeBehavior;

    @Mock private StatusBarStateController mMockStatusBarStateController;
    @Mock private WakefulnessLifecycle mMockWakefulnessLifecycle;
    @Mock private OverviewProxyService mMockOverviewProxyService;
    @Mock private AssistHandleCallbacks mMockAssistHandleCallbacks;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mAssistHandleLikeHomeBehavior = new AssistHandleLikeHomeBehavior(
                () -> mMockStatusBarStateController,
                () -> mMockWakefulnessLifecycle,
                () -> mMockOverviewProxyService);
    }

    @Test
    public void onModeActivated_beginsObserving() {
        // Arrange

        // Act
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);

        // Assert
        verify(mMockStatusBarStateController).isDozing();
        verify(mMockStatusBarStateController).addCallback(
                any(StatusBarStateController.StateListener.class));
        verify(mMockWakefulnessLifecycle).getWakefulness();
        verify(mMockWakefulnessLifecycle).addObserver(any(WakefulnessLifecycle.Observer.class));
        verify(mMockOverviewProxyService).addCallback(any(
                OverviewProxyService.OverviewProxyListener.class));
        verifyNoMoreInteractions(mMockWakefulnessLifecycle, mMockOverviewProxyService);
    }

    @Test
    public void onModeActivated_showsHandlesWhenFullyAwake() {
        // Arrange
        when(mMockStatusBarStateController.isDozing()).thenReturn(false);
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);

        // Act
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);

        // Assert
        verify(mMockAssistHandleCallbacks).showAndStay();
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }

    @Test
    public void onModeActivated_hidesHandlesWhenNotAwake() {
        // Arrange
        when(mMockStatusBarStateController.isDozing()).thenReturn(true);
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_ASLEEP);

        // Act
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }

    @Test
    public void onModeActivated_hidesHandlesWhenDozing() {
        // Arrange
        when(mMockStatusBarStateController.isDozing()).thenReturn(true);
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);

        // Act
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }

    @Test
    public void onModeDeactivated_stopsObserving() {
        // Arrange
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        ArgumentCaptor<StatusBarStateController.StateListener> stateListener =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        ArgumentCaptor<WakefulnessLifecycle.Observer> observer =
                ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
        ArgumentCaptor<OverviewProxyService.OverviewProxyListener> overviewProxyListener =
                ArgumentCaptor.forClass(OverviewProxyService.OverviewProxyListener.class);
        verify(mMockStatusBarStateController).addCallback(stateListener.capture());
        verify(mMockWakefulnessLifecycle).addObserver(observer.capture());
        verify(mMockOverviewProxyService).addCallback(overviewProxyListener.capture());
        reset(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Act
        mAssistHandleLikeHomeBehavior.onModeDeactivated();

        // Assert
        verify(mMockStatusBarStateController).removeCallback(eq(stateListener.getValue()));
        verify(mMockWakefulnessLifecycle).removeObserver(eq(observer.getValue()));
        verify(mMockOverviewProxyService).removeCallback(eq(overviewProxyListener.getValue()));
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);
    }

    @Test
    public void onAssistantGesturePerformed_doesNothing() {
        // Arrange
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        reset(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Act
        mAssistHandleLikeHomeBehavior.onAssistantGesturePerformed();

        // Assert
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);
    }

    @Test
    public void onAssistHandlesRequested_doesNothing() {
        // Arrange
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        reset(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Act
        mAssistHandleLikeHomeBehavior.onAssistHandlesRequested();

        // Assert
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);
    }

    @Test
    public void onBothAwakeAndUnDoze_handlesShow() {
        // Arrange
        when(mMockStatusBarStateController.isDozing()).thenReturn(true);
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_ASLEEP);
        ArgumentCaptor<StatusBarStateController.StateListener> stateListener =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        ArgumentCaptor<WakefulnessLifecycle.Observer> observer =
                ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        verify(mMockStatusBarStateController).addCallback(stateListener.capture());
        verify(mMockWakefulnessLifecycle).addObserver(observer.capture());
        reset(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Act
        observer.getValue().onFinishedWakingUp();

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Arrange
        observer.getValue().onFinishedGoingToSleep();
        reset(mMockAssistHandleCallbacks);

        // Act
        stateListener.getValue().onDozingChanged(false);

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Act
        observer.getValue().onFinishedWakingUp();

        // Assert
        verify(mMockAssistHandleCallbacks).showAndStay();
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);
    }

    @Test
    public void onSleepOrDoze_handlesHide() {
        // Arrange
        when(mMockStatusBarStateController.isDozing()).thenReturn(false);
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        ArgumentCaptor<StatusBarStateController.StateListener> stateListener =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        ArgumentCaptor<WakefulnessLifecycle.Observer> observer =
                ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        verify(mMockStatusBarStateController).addCallback(stateListener.capture());
        verify(mMockWakefulnessLifecycle).addObserver(observer.capture());
        reset(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Act
        observer.getValue().onStartedGoingToSleep();

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Arrange
        observer.getValue().onFinishedWakingUp();
        reset(mMockAssistHandleCallbacks);

        // Act
        stateListener.getValue().onDozingChanged(true);

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);
    }

    @Test
    public void onHomeHandleHide_handlesHide() {
        // Arrange
        when(mMockStatusBarStateController.isDozing()).thenReturn(false);
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        ArgumentCaptor<OverviewProxyService.OverviewProxyListener> sysUiStateCallback =
                ArgumentCaptor.forClass(OverviewProxyService.OverviewProxyListener.class);
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        verify(mMockOverviewProxyService).addCallback(sysUiStateCallback.capture());
        reset(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Act
        sysUiStateCallback.getValue().onSystemUiStateChanged(
                QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN);

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);
    }

    @Test
    public void onHomeHandleUnhide_handlesShow() {
        // Arrange
        when(mMockStatusBarStateController.isDozing()).thenReturn(false);
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        ArgumentCaptor<OverviewProxyService.OverviewProxyListener> sysUiStateCallback =
                ArgumentCaptor.forClass(OverviewProxyService.OverviewProxyListener.class);
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        verify(mMockOverviewProxyService).addCallback(sysUiStateCallback.capture());
        sysUiStateCallback.getValue().onSystemUiStateChanged(
                QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN);
        reset(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);

        // Act
        sysUiStateCallback.getValue().onSystemUiStateChanged(
                ~QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN);

        // Assert
        verify(mMockAssistHandleCallbacks).showAndStay();
        verifyNoMoreInteractions(
                mMockStatusBarStateController,
                mMockWakefulnessLifecycle,
                mMockOverviewProxyService,
                mMockAssistHandleCallbacks);
    }
}
