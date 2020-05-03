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

import androidx.test.filters.SmallTest;

import com.android.systemui.model.SysUiState;
import com.android.systemui.wm.DisplayController;

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
    OneHandedManagerImpl mOneHandedManagerImpl;
    OneHandedTimeoutHandler mTimeoutHandler;

    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    OneHandedSurfaceTransactionHelper mMockSurfaceTransactionHelper;
    @Mock
    OneHandedTouchHandler mMockTouchHandler;
    @Mock
    SysUiState mMockSysUiState;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mOneHandedManagerImpl = new OneHandedManagerImpl(getContext(),
                mMockDisplayController,
                mMockDisplayAreaOrganizer,
                mMockTouchHandler,
                mMockSysUiState);
        mTimeoutHandler = Mockito.spy(OneHandedTimeoutHandler.get());

        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
    }


    @Test
    public void testDefaultShouldNotInOneHanded() {
        final OneHandedSurfaceTransactionHelper transactionHelper =
                new OneHandedSurfaceTransactionHelper(mContext);
        final OneHandedAnimationController animationController = new OneHandedAnimationController(
                mContext, transactionHelper);
        OneHandedDisplayAreaOrganizer displayAreaOrganizer = new OneHandedDisplayAreaOrganizer(
                mContext, mMockDisplayController, animationController,
                mMockSurfaceTransactionHelper);

        assertThat(displayAreaOrganizer.isInOneHanded()).isFalse();
    }

    @Test
    public void testRegisterOrganizer() {
        verify(mMockDisplayAreaOrganizer, times(1)).registerOrganizer(anyInt());
    }

    @Test
    public void testStartOneHanded() {
        mOneHandedManagerImpl.startOneHanded();

        verify(mMockDisplayAreaOrganizer, times(1)).scheduleOffset(anyInt(), anyInt());
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

        verify(mTimeoutHandler, times(1)).removeTimer();
    }

    @Test
    public void testUpdateIsEnabled() {
        final boolean enabled = true;
        mOneHandedManagerImpl.setOneHandedEnabled(enabled);

        verify(mMockTouchHandler, atLeastOnce()).onOneHandedEnabled(enabled);
    }

}
