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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.DreamManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.condition.Condition;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamConditionTest extends SysuiTestCase {
    @Mock
    Context mContext;

    @Mock
    Condition.Callback mCallback;

    @Mock
    DreamManager mDreamManager;

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
        final DreamCondition condition = new DreamCondition(mContext, mDreamManager);
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
        final DreamCondition condition = new DreamCondition(mContext, mDreamManager);
        condition.addCallback(mCallback);

        verify(mCallback, never()).onConditionChanged(eq(condition));
        assertThat(condition.isConditionMet()).isFalse();
    }

    /**
     * Ensure that changing dream state triggers condition.
     */
    @Test
    public void testChange() {
        final ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        when(mDreamManager.isDreaming()).thenReturn(true);
        final DreamCondition condition = new DreamCondition(mContext, mDreamManager);
        condition.addCallback(mCallback);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any());
        clearInvocations(mCallback);
        receiverCaptor.getValue().onReceive(mContext, new Intent(Intent.ACTION_DREAMING_STOPPED));
        verify(mCallback).onConditionChanged(eq(condition));
        assertThat(condition.isConditionMet()).isFalse();
    }
}
