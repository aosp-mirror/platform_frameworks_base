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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

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
public class OneHandedTouchHandlerTest extends OneHandedTestCase {
    Instrumentation mInstrumentation;
    OneHandedTouchHandler mTouchHandler;
    OneHandedManagerImpl mOneHandedManagerImpl;
    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    SysUiState mMockSysUiState;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mTouchHandler = Mockito.spy(new OneHandedTouchHandler());
        mOneHandedManagerImpl = new OneHandedManagerImpl(mInstrumentation.getContext(),
                mMockDisplayController,
                mMockDisplayAreaOrganizer,
                mTouchHandler,
                mMockSysUiState);
    }

    @Test
    public void testOneHandedManager_registerForDisplayAreaOrganizer() {
        verify(mMockDisplayAreaOrganizer, times(1)).registerTransitionCallback(mTouchHandler);
    }

    @Test
    public void testOneHandedManager_registerTouchEventListener() {
        verify(mTouchHandler).registerTouchEventListener(any());
        assertThat(mTouchHandler.mTouchEventCallback).isNotNull();
    }

    @Test
    public void testOneHandedDisabled_shouldDisposeInputChannel() {
        mOneHandedManagerImpl.setOneHandedEnabled(false);
        assertThat(mTouchHandler.mInputMonitor).isNull();
        assertThat(mTouchHandler.mInputEventReceiver).isNull();
    }

    @Test
    public void testOneHandedEnabled_monitorInputChannel() {
        mOneHandedManagerImpl.setOneHandedEnabled(true);
        assertThat(mTouchHandler.mInputMonitor).isNotNull();
        assertThat(mTouchHandler.mInputEventReceiver).isNotNull();
    }

    @Test
    public void testReceiveNewConfig_whenSetOneHandedEnabled() {
        // 1st called at init
        verify(mTouchHandler).onOneHandedEnabled(true);
        mOneHandedManagerImpl.setOneHandedEnabled(true);
        // 2nd called by setOneHandedEnabled()
        verify(mTouchHandler, times(2)).onOneHandedEnabled(true);
    }
}
