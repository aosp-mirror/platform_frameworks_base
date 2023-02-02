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
import static org.mockito.Mockito.when;

import android.os.RemoteException;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVisualQueryDetectionAttentionListener;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.shared.condition.Condition;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class AssistantAttentionConditionTest extends SysuiTestCase {
    @Mock
    Condition.Callback mCallback;
    @Mock
    AssistUtils mAssistUtils;
    @Mock
    DreamOverlayStateController mDreamOverlayStateController;

    private AssistantAttentionCondition mAssistantAttentionCondition;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mAssistantAttentionCondition =
                new AssistantAttentionCondition(mDreamOverlayStateController, mAssistUtils);
        // Adding a callback also starts the condition.
        mAssistantAttentionCondition.addCallback(mCallback);
    }

    @Test
    public void testEnableVisualQueryDetection() {
        final ArgumentCaptor<DreamOverlayStateController.Callback> argumentCaptor =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(argumentCaptor.capture());

        when(mDreamOverlayStateController.isDreamOverlayStatusBarVisible()).thenReturn(true);
        argumentCaptor.getValue().onStateChanged();

        verify(mAssistUtils).enableVisualQueryDetection(any());
    }

    @Test
    public void testDisableVisualQueryDetection() {
        final ArgumentCaptor<DreamOverlayStateController.Callback> argumentCaptor =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(argumentCaptor.capture());

        when(mDreamOverlayStateController.isDreamOverlayStatusBarVisible()).thenReturn(true);
        argumentCaptor.getValue().onStateChanged();
        when(mDreamOverlayStateController.isDreamOverlayStatusBarVisible()).thenReturn(false);
        argumentCaptor.getValue().onStateChanged();

        verify(mAssistUtils).disableVisualQueryDetection();
    }

    @Test
    public void testAttentionChangedTriggersCondition() throws RemoteException {
        final ArgumentCaptor<DreamOverlayStateController.Callback> callbackCaptor =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(callbackCaptor.capture());

        when(mDreamOverlayStateController.isDreamOverlayStatusBarVisible()).thenReturn(true);
        callbackCaptor.getValue().onStateChanged();

        final ArgumentCaptor<IVisualQueryDetectionAttentionListener> listenerCaptor =
                ArgumentCaptor.forClass(IVisualQueryDetectionAttentionListener.class);
        verify(mAssistUtils).enableVisualQueryDetection(listenerCaptor.capture());

        listenerCaptor.getValue().onAttentionGained();
        assertThat(mAssistantAttentionCondition.isConditionMet()).isTrue();

        listenerCaptor.getValue().onAttentionLost();
        assertThat(mAssistantAttentionCondition.isConditionMet()).isFalse();

        verify(mCallback, times(2)).onConditionChanged(eq(mAssistantAttentionCondition));
    }
}
