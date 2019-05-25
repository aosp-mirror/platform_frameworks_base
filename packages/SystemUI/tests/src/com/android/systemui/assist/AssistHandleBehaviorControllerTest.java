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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.ScreenDecorations;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class AssistHandleBehaviorControllerTest extends SysuiTestCase {

    private final AssistHandleBehavior mTestBehavior = AssistHandleBehavior.TEST;

    private AssistHandleBehaviorController mAssistHandleBehaviorController;

    @Mock private ScreenDecorations mMockScreenDecorations;
    @Mock private Handler mMockHandler;
    @Mock private AssistHandleBehaviorController.BehaviorController mMockBehaviorController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        doAnswer(answerVoid(Runnable::run)).when(mMockHandler).post(any(Runnable.class));
        doAnswer(answerVoid(Runnable::run)).when(mMockHandler)
                .postDelayed(any(Runnable.class), anyLong());
        mTestBehavior.setTestController(mMockBehaviorController);
        mAssistHandleBehaviorController =
                new AssistHandleBehaviorController(
                        mContext, mMockHandler, () -> mMockScreenDecorations);
    }

    @Test
    public void hide_hidesHandlesWhenShowing() {
        // Arrange
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
        mAssistHandleBehaviorController.showAndStay();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndStay();

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void showAndGo_showsThenHidesHandlesWhenHiding() {
        // Arrange
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
        mAssistHandleBehaviorController.showAndGo();
        reset(mMockScreenDecorations);

        // Act
        mAssistHandleBehaviorController.showAndGo();

        // Assert
        verifyNoMoreInteractions(mMockScreenDecorations);
    }

    @Test
    public void setBehavior_activatesTheBehaviorWhenInGesturalMode() {
        // Arrange
        mAssistHandleBehaviorController.setInGesturalModeForTest(true);

        // Act
        mAssistHandleBehaviorController.setBehavior(mTestBehavior);

        // Assert
        verify(mMockBehaviorController).onModeActivated(mContext, mAssistHandleBehaviorController);
        verifyNoMoreInteractions(mMockBehaviorController);
    }

    @Test
    public void setBehavior_deactivatesThePreviousBehaviorWhenInGesturalMode() {
        // Arrange
        mAssistHandleBehaviorController.setBehavior(mTestBehavior);
        mAssistHandleBehaviorController.setInGesturalModeForTest(true);

        // Act
        mAssistHandleBehaviorController.setBehavior(AssistHandleBehavior.OFF);

        // Assert
        verify(mMockBehaviorController).onModeDeactivated();
        verifyNoMoreInteractions(mMockBehaviorController);
    }

    @Test
    public void setBehavior_doesNothingWhenNotInGesturalMode() {
        // Arrange
        mAssistHandleBehaviorController.setInGesturalModeForTest(false);

        // Act
        mAssistHandleBehaviorController.setBehavior(mTestBehavior);

        // Assert
        verifyNoMoreInteractions(mMockBehaviorController);
    }
}
