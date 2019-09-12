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

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AssistHandleOffBehaviorTest extends SysuiTestCase {

    private AssistHandleOffBehavior mAssistHandleOffBehavior;

    @Mock private AssistHandleCallbacks mMockAssistHandleCallbacks;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mAssistHandleOffBehavior = new AssistHandleOffBehavior();
    }

    @Test
    public void onModeActivated_hidesHandles() {
        // Arrange

        // Act
        mAssistHandleOffBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }

    @Test
    public void onModeDeactivated_doesNothing() {
        // Arrange
        mAssistHandleOffBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        reset(mMockAssistHandleCallbacks);

        // Act
        mAssistHandleOffBehavior.onModeDeactivated();

        // Assert
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }

    @Test
    public void onAssistantGesturePerformed_doesNothing() {
        // Arrange
        mAssistHandleOffBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        reset(mMockAssistHandleCallbacks);

        // Act
        mAssistHandleOffBehavior.onAssistantGesturePerformed();

        // Assert
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }

    @Test
    public void onAssistHandlesRequested_doesNothing() {
        // Arrange
        mAssistHandleOffBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        reset(mMockAssistHandleCallbacks);

        // Act
        mAssistHandleOffBehavior.onAssistHandlesRequested();

        // Assert
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }
}
