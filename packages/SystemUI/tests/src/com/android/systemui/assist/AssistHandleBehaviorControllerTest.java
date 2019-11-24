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

import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.app.AssistUtils;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.DumpController;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.phone.NavigationModeController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.EnumMap;
import java.util.Map;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class AssistHandleBehaviorControllerTest extends SysuiTestCase {

    private static final ComponentName COMPONENT_NAME = new ComponentName("", "");

    private AssistHandleBehaviorController mAssistHandleBehaviorController;

    @Mock private ScreenDecorations mMockScreenDecorations;
    @Mock private AssistUtils mMockAssistUtils;
    @Mock private Handler mMockHandler;
    @Mock private DeviceConfigHelper mMockDeviceConfigHelper;
    @Mock private AssistHandleOffBehavior mMockOffBehavior;
    @Mock private AssistHandleLikeHomeBehavior mMockLikeHomeBehavior;
    @Mock private AssistHandleReminderExpBehavior mMockReminderExpBehavior;
    @Mock private AssistHandleBehaviorController.BehaviorController mMockTestBehavior;
    @Mock private NavigationModeController mMockNavigationModeController;
    @Mock private DumpController mMockDumpController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(StatusBarStateController.class);
        mDependency.injectMockDependency(OverviewProxyService.class);
        doAnswer(answerVoid(Runnable::run)).when(mMockHandler).post(any(Runnable.class));
        doAnswer(answerVoid(Runnable::run)).when(mMockHandler)
                .postDelayed(any(Runnable.class), anyLong());

        Map<AssistHandleBehavior, AssistHandleBehaviorController.BehaviorController> behaviorMap =
                new EnumMap<>(AssistHandleBehavior.class);
        behaviorMap.put(AssistHandleBehavior.OFF, mMockOffBehavior);
        behaviorMap.put(AssistHandleBehavior.LIKE_HOME, mMockLikeHomeBehavior);
        behaviorMap.put(AssistHandleBehavior.REMINDER_EXP, mMockReminderExpBehavior);
        behaviorMap.put(AssistHandleBehavior.TEST, mMockTestBehavior);

        mAssistHandleBehaviorController =
                new AssistHandleBehaviorController(
                        mContext,
                        mMockAssistUtils,
                        mMockHandler,
                        () -> mMockScreenDecorations,
                        mMockDeviceConfigHelper,
                        behaviorMap,
                        mMockNavigationModeController,
                        mMockDumpController);
    }

    @After
    public void teardown() {
        mAssistHandleBehaviorController.setBehavior(AssistHandleBehavior.OFF);
    }

    @Test
    public void hide_hidesHandlesWhenShowing() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.showAndStay();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.hide();

        // Assert
        verify(mMockScreenDecorations).setAssistHintVisible(false);
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void hide_doesNothingWhenHiding() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.hide();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.hide();

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndStay_showsHandlesWhenHiding() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.hide();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndStay();

        // Assert
        verify(mMockScreenDecorations).setAssistHintVisible(true);
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndStay_doesNothingWhenShowing() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.showAndStay();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndStay();

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndStay_doesNothingWhenThereIsNoAssistant() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(null);
        mAssistHandleBehaviorController.hide();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndStay();

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndGo_showsThenHidesHandlesWhenHiding() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.hide();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGo();

        // Assert
        InOrder inOrder = inOrder(mMockScreenDecorations);
        inOrder.verify(mMockScreenDecorations).setAssistHintVisible(true);
        inOrder.verify(mMockScreenDecorations).setAssistHintVisible(false);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void showAndGo_hidesHandlesAfterTimeoutWhenShowing() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.showAndStay();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGo();

        // Assert
        verify(mMockScreenDecorations).setAssistHintVisible(false);
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndGo_doesNothingIfRecentlyHidden() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        when(mMockDeviceConfigHelper.getLong(
                eq(SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOWN_FREQUENCY_THRESHOLD_MS),
                anyLong())).thenReturn(10000L);
        mAssistHandleBehaviorController.showAndGo();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGo();

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndGo_doesNothingWhenThereIsNoAssistant() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(null);
        mAssistHandleBehaviorController.hide();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGo();

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndGoDelayed_showsThenHidesHandlesWhenHiding() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.hide();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGoDelayed(1000, false);

        // Assert
        InOrder inOrder = inOrder(mMockScreenDecorations);
        inOrder.verify(mMockScreenDecorations).setAssistHintVisible(true);
        inOrder.verify(mMockScreenDecorations).setAssistHintVisible(false);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void showAndGoDelayed_hidesHandlesAfterTimeoutWhenShowing() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.showAndStay();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGoDelayed(1000, false);

        // Assert
        verify(mMockScreenDecorations).setAssistHintVisible(false);
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndGoDelayed_hidesInitiallyThenShowsThenHidesAfterTimeoutWhenHideRequested() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.showAndStay();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGoDelayed(1000, true);

        // Assert
        InOrder inOrder = inOrder(mMockScreenDecorations);
        inOrder.verify(mMockScreenDecorations).setAssistHintVisible(false);
        inOrder.verify(mMockScreenDecorations).setAssistHintVisible(true);
        inOrder.verify(mMockScreenDecorations).setAssistHintVisible(false);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void showAndGoDelayed_doesNothingIfRecentlyHidden() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        when(mMockDeviceConfigHelper.getLong(
                eq(SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOWN_FREQUENCY_THRESHOLD_MS),
                anyLong())).thenReturn(10000L);
        mAssistHandleBehaviorController.showAndGo();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGoDelayed(1000, false);

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndGoDelayed_doesNothingWhenThereIsNoAssistant() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(null);
        mAssistHandleBehaviorController.hide();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGoDelayed(1000, false);

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void setBehavior_activatesTheBehaviorWhenInGesturalMode() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.setInGesturalModeForTest(true);

        // Act
        mAssistHandleBehaviorController.setBehavior(AssistHandleBehavior.TEST);

        // Assert
        verify(mMockTestBehavior).onModeActivated(mContext, mAssistHandleBehaviorController);
        verifyNoMoreInteractions(mMockTestBehavior);
    }

    @Test
    public void setBehavior_deactivatesThePreviousBehaviorWhenInGesturalMode() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.setBehavior(AssistHandleBehavior.TEST);
        mAssistHandleBehaviorController.setInGesturalModeForTest(true);
        reset(mMockTestBehavior);

        // Act
        mAssistHandleBehaviorController.setBehavior(AssistHandleBehavior.OFF);

        // Assert
        verify(mMockTestBehavior).onModeDeactivated();
        verifyNoMoreInteractions(mMockTestBehavior);
    }

    @Test
    public void setBehavior_doesNothingWhenNotInGesturalMode() {
        // Arrange
        when(mMockAssistUtils.getAssistComponentForUser(anyInt())).thenReturn(COMPONENT_NAME);
        mAssistHandleBehaviorController.setInGesturalModeForTest(false);

        // Act
        mAssistHandleBehaviorController.setBehavior(AssistHandleBehavior.TEST);

        // Assert
        verifyNoMoreInteractions(mMockTestBehavior);
    }
}
