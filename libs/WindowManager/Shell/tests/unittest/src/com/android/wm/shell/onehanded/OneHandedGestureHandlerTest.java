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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.Surface;
import android.view.ViewConfiguration;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class OneHandedGestureHandlerTest extends OneHandedTestCase {
    OneHandedGestureHandler mGestureHandler;
    DisplayLayout mDisplayLayout;
    @Mock
    DisplayLayout mMockDisplayLayout;
    @Mock
    ShellExecutor mMockShellMainExecutor;

    @Before
    public void setUp() {
        final int mockNavBarHeight = 100;
        MockitoAnnotations.initMocks(this);
        mDisplayLayout = new DisplayLayout(mContext, mContext.getDisplay());
        mGestureHandler = new OneHandedGestureHandler(mContext, mDisplayLayout,
                ViewConfiguration.get(mTestContext), mMockShellMainExecutor);
        when(mMockDisplayLayout.navBarFrameHeight()).thenReturn(mockNavBarHeight);
    }

    @Test
    public void testSetGestureEventListener() {
        OneHandedGestureHandler.OneHandedGestureEventCallback callback = 
            new OneHandedGestureHandler.OneHandedGestureEventCallback() {
                @Override
                public void onStart() {}

                @Override
                public void onStop() {}
            };

        mGestureHandler.setGestureEventListener(callback);
        assertThat(mGestureHandler.mGestureEventCallback).isEqualTo(callback);
    }

    @Test
    public void testOneHandedDisabled_shouldDisposeInputChannel() {
        mGestureHandler.onGestureEnabled(false);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }

    @Test
    public void testChangeNavBarToNon3Button_shouldDisposeInputChannel() {
        mGestureHandler.onGestureEnabled(true);
        mGestureHandler.onThreeButtonModeEnabled(false);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }

    @Test
    public void testOnlyHandleGestureInPortraitMode() {
        mDisplayLayout.rotateTo(mContext.getResources(), Surface.ROTATION_90);
        mGestureHandler.onGestureEnabled(true);
        mGestureHandler.onRotateDisplay(mDisplayLayout);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }

    @Test
    public void testRotation90ShouldNotRegisterEventReceiver() throws InterruptedException {
        mDisplayLayout.rotateTo(mContext.getResources(), Surface.ROTATION_90);
        mGestureHandler.onGestureEnabled(true);
        mGestureHandler.onRotateDisplay(mDisplayLayout);

        verify(mMockShellMainExecutor, never()).executeBlocking(any());
    }

    @Test
    public void testRotation180ShouldNotRegisterEventReceiver() throws InterruptedException {
        mDisplayLayout.rotateTo(mContext.getResources(), Surface.ROTATION_180);
        mGestureHandler.onGestureEnabled(true);
        mGestureHandler.onRotateDisplay(mDisplayLayout);

        verify(mMockShellMainExecutor, never()).executeBlocking(any());
    }

    @Test
    public void testRotation270ShouldNotRegisterEventReceiver() throws InterruptedException {
        mDisplayLayout.rotateTo(mContext.getResources(), Surface.ROTATION_270);
        mGestureHandler.onGestureEnabled(true);
        mGestureHandler.onRotateDisplay(mDisplayLayout);

        verify(mMockShellMainExecutor, never()).executeBlocking(any());
    }
}
