/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch.scrim;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@android.platform.test.annotations.EnabledOnRavenwood
public class ScrimManagerTest extends SysuiTestCase {
    @Mock
    ScrimController mBouncerlessScrimController;

    @Mock
    ScrimController mBouncerScrimController;

    @Mock
    KeyguardStateController mKeyguardStateController;

    @Mock
    ScrimManager.Callback mCallback;

    private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testControllerSelection() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        ArgumentCaptor<KeyguardStateController.Callback> callbackCaptor =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);
        final ScrimManager manager = new ScrimManager(mExecutor, mBouncerScrimController,
                mBouncerlessScrimController, mKeyguardStateController);
        verify(mKeyguardStateController).addCallback(callbackCaptor.capture());

        assertThat(manager.getCurrentController()).isEqualTo(mBouncerScrimController);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        callbackCaptor.getValue().onKeyguardShowingChanged();
        mExecutor.runAllReady();
        assertThat(manager.getCurrentController()).isEqualTo(mBouncerlessScrimController);
    }

    @Test
    public void testCallback() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        ArgumentCaptor<KeyguardStateController.Callback> callbackCaptor =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);
        final ScrimManager manager = new ScrimManager(mExecutor, mBouncerScrimController,
                mBouncerlessScrimController, mKeyguardStateController);
        verify(mKeyguardStateController).addCallback(callbackCaptor.capture());

        manager.addCallback(mCallback);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        callbackCaptor.getValue().onKeyguardShowingChanged();
        mExecutor.runAllReady();
        verify(mCallback).onScrimControllerChanged(eq(mBouncerlessScrimController));
    }
}
