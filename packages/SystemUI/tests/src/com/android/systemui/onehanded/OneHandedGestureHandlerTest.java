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

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.systemui.model.SysUiState;
import com.android.systemui.statusbar.phone.NavigationModeController;
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
public class OneHandedGestureHandlerTest extends OneHandedTestCase {
    Instrumentation mInstrumentation;
    OneHandedTouchHandler mTouchHandler;
    OneHandedGestureHandler mGestureHandler;
    OneHandedManagerImpl mOneHandedManagerImpl;
    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    SysUiState mMockSysUiState;
    @Mock
    NavigationModeController mMockNavigationModeController;
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mTouchHandler = Mockito.spy(new OneHandedTouchHandler());
        mGestureHandler = Mockito.spy(new OneHandedGestureHandler(
                mContext, mMockDisplayController, mMockNavigationModeController));
        mOneHandedManagerImpl = new OneHandedManagerImpl(mInstrumentation.getContext(),
                mMockDisplayController,
                mMockDisplayAreaOrganizer,
                mTouchHandler,
                mGestureHandler,
                mMockSysUiState);
    }

    @Test
    public void testOneHandedManager_registerForDisplayAreaOrganizer() {
        verify(mMockDisplayAreaOrganizer, times(1)).registerTransitionCallback(mGestureHandler);
    }

    @Test
    public void testOneHandedManager_setGestureEventListener() {
        verify(mGestureHandler).setGestureEventListener(any());

        assertThat(mGestureHandler.mGestureEventCallback).isNotNull();
    }

    @Test
    public void testReceiveNewConfig_whenSetOneHandedEnabled() {
        // 1st called at init
        verify(mGestureHandler).onOneHandedEnabled(true);
        mOneHandedManagerImpl.setOneHandedEnabled(true);
        // 2nd called by setOneHandedEnabled()
        verify(mGestureHandler, times(2)).onOneHandedEnabled(true);
    }

    @Test
    public void testOneHandedDisabled_shouldDisposeInputChannel() {
        mOneHandedManagerImpl.setOneHandedEnabled(false);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }

    @Test
    public void testChangeNavBarTo2Button_shouldDisposeInputChannel() {
        // 1st called at init
        verify(mGestureHandler).onOneHandedEnabled(true);
        mOneHandedManagerImpl.setOneHandedEnabled(true);
        // 2nd called by setOneHandedEnabled()
        verify(mGestureHandler, times(2)).onOneHandedEnabled(true);

        mGestureHandler.onNavigationModeChanged(NAV_BAR_MODE_2BUTTON);

        assertThat(mGestureHandler.mInputMonitor).isNull();
        assertThat(mGestureHandler.mInputEventReceiver).isNull();
    }
}
