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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.assist.AssistManager.VisualQueryAttentionListener;
import com.android.systemui.shared.condition.Condition;

import kotlinx.coroutines.CoroutineScope;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@android.platform.test.annotations.EnabledOnRavenwood
public class AssistantAttentionConditionTest extends SysuiTestCase {
    @Mock
    Condition.Callback mCallback;
    @Mock
    AssistManager mAssistManager;
    @Mock
    CoroutineScope mScope;

    private AssistantAttentionCondition mAssistantAttentionCondition;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mAssistantAttentionCondition = new AssistantAttentionCondition(mScope, mAssistManager);
        // Adding a callback also starts the condition.
        mAssistantAttentionCondition.addCallback(mCallback);
    }

    @Test
    public void testEnableVisualQueryDetection() {
        verify(mAssistManager).addVisualQueryAttentionListener(
                any(VisualQueryAttentionListener.class));
    }

    @Test
    public void testDisableVisualQueryDetection() {
        mAssistantAttentionCondition.stop();
        verify(mAssistManager).removeVisualQueryAttentionListener(
                any(VisualQueryAttentionListener.class));
    }

    @Test
    public void testAttentionChangedTriggersCondition() {
        final ArgumentCaptor<VisualQueryAttentionListener> argumentCaptor =
                ArgumentCaptor.forClass(VisualQueryAttentionListener.class);
        verify(mAssistManager).addVisualQueryAttentionListener(argumentCaptor.capture());

        argumentCaptor.getValue().onAttentionGained();
        assertThat(mAssistantAttentionCondition.isConditionMet()).isTrue();

        argumentCaptor.getValue().onAttentionLost();
        assertThat(mAssistantAttentionCondition.isConditionMet()).isFalse();

        verify(mCallback, times(2)).onConditionChanged(eq(mAssistantAttentionCondition));
    }
}
