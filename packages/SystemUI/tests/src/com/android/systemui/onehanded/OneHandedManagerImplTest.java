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

package com.android.systemui.onehanded;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.systemui.model.SysUiState;
import com.android.systemui.statusbar.CommandQueue;
import com.android.wm.shell.common.DisplayController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedManagerImplTest extends OneHandedTestCase {
    Display mDisplay;
    OneHandedManagerImpl mOneHandedManagerImpl;
    OneHandedTimeoutHandler mTimeoutHandler;

    @Mock
    CommandQueue mCommandQueue;
    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    OneHandedTouchHandler mMockTouchHandler;
    @Mock
    OneHandedTutorialHandler mMockTutorialHandler;
    @Mock
    OneHandedGestureHandler mMockGestureHandler;
    @Mock
    SysUiState mMockSysUiState;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDisplay = mContext.getDisplay();
        mOneHandedManagerImpl = new OneHandedManagerImpl(
                getContext(),
                mCommandQueue,
                mMockDisplayController,
                mMockDisplayAreaOrganizer,
                mMockTouchHandler,
                mMockTutorialHandler,
                mMockGestureHandler,
                mMockSysUiState);
        mTimeoutHandler = Mockito.spy(OneHandedTimeoutHandler.get());

        when(mMockDisplayController.getDisplay(anyInt())).thenReturn(mDisplay);
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
    }

    @Test
    public void testDefaultShouldNotInOneHanded() {
        final OneHandedAnimationController animationController = new OneHandedAnimationController(
                mContext);
        OneHandedDisplayAreaOrganizer displayAreaOrganizer = new OneHandedDisplayAreaOrganizer(
                mContext, mMockDisplayController, animationController, mMockTutorialHandler);

        assertThat(displayAreaOrganizer.isInOneHanded()).isFalse();
    }

    @Test
    public void testRegisterOrganizer() {
        verify(mMockDisplayAreaOrganizer).registerOrganizer(anyInt());
    }

    @Test
    public void testStartOneHanded() {
        mOneHandedManagerImpl.startOneHanded();

        verify(mMockDisplayAreaOrganizer).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testStopOneHanded() {
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
        mOneHandedManagerImpl.stopOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testRegisterTransitionCallback() {
        verify(mMockDisplayAreaOrganizer, atLeastOnce()).registerTransitionCallback(any());
    }

    @Test
    public void testStopOneHanded_shouldRemoveTimer() {
        mOneHandedManagerImpl.stopOneHanded();

        verify(mTimeoutHandler).removeTimer();
    }

    @Test
    public void testUpdateIsEnabled() {
        final boolean enabled = true;
        mOneHandedManagerImpl.setOneHandedEnabled(enabled);

        verify(mMockTouchHandler, times(2)).onOneHandedEnabled(enabled);
    }

    @Test
    public void testUpdateSwipeToNotificationIsEnabled() {
        final boolean enabled = true;
        mOneHandedManagerImpl.setSwipeToNotificationEnabled(enabled);

        verify(mMockTouchHandler, times(2)).onOneHandedEnabled(enabled);
    }
}
