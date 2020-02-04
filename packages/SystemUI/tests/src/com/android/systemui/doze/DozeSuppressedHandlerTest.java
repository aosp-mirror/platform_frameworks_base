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

package com.android.systemui.doze;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.hardware.display.AmbientDisplayConfiguration;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.DozeSuppressedHandler.DozeSuppressedSettingObserver;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class DozeSuppressedHandlerTest extends SysuiTestCase {
    @Mock private DozeMachine mMachine;
    @Mock private DozeSuppressedSettingObserver mObserver;
    private AmbientDisplayConfiguration mConfig;
    private DozeSuppressedHandler mSuppressedHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mConfig = DozeConfigurationUtil.createMockConfig();
        mSuppressedHandler = new DozeSuppressedHandler(mContext, mConfig, mMachine, mObserver);
    }

    @Test
    public void transitionTo_initialized_registersObserver() throws Exception {
        mSuppressedHandler.transitionTo(DozeMachine.State.UNINITIALIZED,
                DozeMachine.State.INITIALIZED);

        verify(mObserver).register();
    }

    @Test
    public void transitionTo_finish_unregistersObserver() throws Exception {
        mSuppressedHandler.transitionTo(DozeMachine.State.INITIALIZED,
                DozeMachine.State.FINISH);

        verify(mObserver).unregister();
    }

    @Test
    public void transitionTo_doze_doesNothing() throws Exception {
        mSuppressedHandler.transitionTo(DozeMachine.State.INITIALIZED,
                DozeMachine.State.DOZE);

        verify(mObserver, never()).register();
        verify(mObserver, never()).unregister();
    }
}
