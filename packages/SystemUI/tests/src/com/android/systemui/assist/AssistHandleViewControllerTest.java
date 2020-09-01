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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.view.ViewPropertyAnimator;

import androidx.test.filters.SmallTest;

import com.android.systemui.CornerHandleView;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class AssistHandleViewControllerTest extends SysuiTestCase {

    private AssistHandleViewController mAssistHandleViewController;

    @Mock private Handler mMockHandler;
    @Mock private Looper mMockLooper;
    @Mock private View mMockBarView;
    @Mock private CornerHandleView mMockAssistHint;
    @Mock private ViewPropertyAnimator mMockAnimator;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mMockBarView.findViewById(anyInt())).thenReturn(mMockAssistHint);
        when(mMockAssistHint.animate()).thenReturn(mMockAnimator);
        when(mMockAnimator.setInterpolator(any())).thenReturn(mMockAnimator);
        when(mMockAnimator.setDuration(anyLong())).thenReturn(mMockAnimator);
        doNothing().when(mMockAnimator).cancel();
        when(mMockHandler.getLooper()).thenReturn(mMockLooper);
        when(mMockLooper.isCurrentThread()).thenReturn(true);

        mAssistHandleViewController = new AssistHandleViewController(mMockHandler, mMockBarView);
    }

    @Test
    public void testSetVisibleWithoutBlocked() {
        // Act
        mAssistHandleViewController.setAssistHintVisible(true);

        // Assert
        assertTrue(mAssistHandleViewController.mAssistHintVisible);
    }

    @Test
    public void testSetInvisibleWithoutBlocked() {
        // Arrange
        mAssistHandleViewController.setAssistHintVisible(true);

        // Act
        mAssistHandleViewController.setAssistHintVisible(false);

        // Assert
        assertFalse(mAssistHandleViewController.mAssistHintVisible);
    }

    @Test
    public void testSetVisibleWithBlocked() {
        // Act
        mAssistHandleViewController.setAssistHintBlocked(true);
        mAssistHandleViewController.setAssistHintVisible(true);

        // Assert
        assertFalse(mAssistHandleViewController.mAssistHintVisible);
        assertTrue(mAssistHandleViewController.mAssistHintBlocked);
    }
}
