/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.dreams.conditions;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.DreamManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.condition.Condition;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import kotlinx.coroutines.CoroutineScope;

@SmallTest
@RunWith(AndroidJUnit4.class)
@android.platform.test.annotations.EnabledOnRavenwood
public class DreamConditionTest extends SysuiTestCase {
    @Mock
    Condition.Callback mCallback;

    @Mock
    DreamManager mDreamManager;

    @Mock
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    @Mock
    CoroutineScope mScope;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Ensure a dreaming state immediately triggers the condition.
     */
    @Test
    public void testInitialDreamingState() {
        when(mDreamManager.isDreaming()).thenReturn(true);
        final DreamCondition condition = new DreamCondition(mScope, mDreamManager,
                mKeyguardUpdateMonitor);
        condition.addCallback(mCallback);

        verify(mCallback).onConditionChanged(eq(condition));
        assertThat(condition.isConditionMet()).isTrue();
    }

    /**
     * Ensure a non-dreaming state does not trigger the condition.
     */
    @Test
    public void testInitialNonDreamingState() {
        when(mDreamManager.isDreaming()).thenReturn(false);
        final DreamCondition condition = new DreamCondition(mScope, mDreamManager,
                mKeyguardUpdateMonitor);
        condition.addCallback(mCallback);

        verify(mCallback, never()).onConditionChanged(eq(condition));
        assertThat(condition.isConditionMet()).isFalse();
    }

    /**
     * Ensure that changing dream state triggers condition.
     */
    @Test
    public void testChange() {
        final ArgumentCaptor<KeyguardUpdateMonitorCallback> callbackCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        when(mDreamManager.isDreaming()).thenReturn(true);
        final DreamCondition condition = new DreamCondition(mScope, mDreamManager,
                mKeyguardUpdateMonitor);
        condition.addCallback(mCallback);
        verify(mKeyguardUpdateMonitor).registerCallback(callbackCaptor.capture());

        clearInvocations(mCallback);
        callbackCaptor.getValue().onDreamingStateChanged(false);
        verify(mCallback).onConditionChanged(eq(condition));
        assertThat(condition.isConditionMet()).isFalse();

        clearInvocations(mCallback);
        callbackCaptor.getValue().onDreamingStateChanged(true);
        verify(mCallback).onConditionChanged(eq(condition));
        assertThat(condition.isConditionMet()).isTrue();
    }
}
