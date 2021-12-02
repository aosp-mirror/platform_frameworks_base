/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.conditions.CommunalCondition;
import com.android.systemui.communal.conditions.CommunalConditionsMonitor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalConditionsMonitorTest extends SysuiTestCase {
    private FakeCommunalCondition mCondition1;
    private FakeCommunalCondition mCondition2;
    private FakeCommunalCondition mCondition3;
    private HashSet<CommunalCondition> mConditions;

    private CommunalConditionsMonitor mCommunalConditionsMonitor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mCondition1 = spy(new FakeCommunalCondition());
        mCondition2 = spy(new FakeCommunalCondition());
        mCondition3 = spy(new FakeCommunalCondition());
        mConditions = new HashSet<>(Arrays.asList(mCondition1, mCondition2, mCondition3));

        mCommunalConditionsMonitor = new CommunalConditionsMonitor(mConditions);
    }

    @Test
    public void addCallback_addFirstCallback_addCallbackToAllConditions() {
        final CommunalConditionsMonitor.Callback callback1 =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback1);
        mConditions.forEach(condition -> verify(condition).addCallback(any()));

        final CommunalConditionsMonitor.Callback callback2 =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback2);
        mConditions.forEach(condition -> verify(condition, times(1)).addCallback(any()));
    }

    @Test
    public void addCallback_addFirstCallback_reportWithDefaultValue() {
        final CommunalConditionsMonitor.Callback callback =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback);
        verify(callback).onConditionsChanged(false);
    }

    @Test
    public void addCallback_addSecondCallback_reportWithExistingValue() {
        final CommunalConditionsMonitor.Callback callback1 =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback1);

        mCommunalConditionsMonitor.overrideAllConditionsMet(true);

        final CommunalConditionsMonitor.Callback callback2 =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback2);
        verify(callback2).onConditionsChanged(true);
    }

    @Test
    public void removeCallback_shouldNoLongerReceiveUpdate() {
        final CommunalConditionsMonitor.Callback callback =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback);
        clearInvocations(callback);
        mCommunalConditionsMonitor.removeCallback(callback);

        mCommunalConditionsMonitor.overrideAllConditionsMet(true);
        verify(callback, never()).onConditionsChanged(true);

        mCommunalConditionsMonitor.overrideAllConditionsMet(false);
        verify(callback, never()).onConditionsChanged(false);
    }

    @Test
    public void removeCallback_removeLastCallback_removeCallbackFromAllConditions() {
        final CommunalConditionsMonitor.Callback callback1 =
                mock(CommunalConditionsMonitor.Callback.class);
        final CommunalConditionsMonitor.Callback callback2 =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback1);
        mCommunalConditionsMonitor.addCallback(callback2);

        mCommunalConditionsMonitor.removeCallback(callback1);
        mConditions.forEach(condition -> verify(condition, never()).removeCallback(any()));

        mCommunalConditionsMonitor.removeCallback(callback2);
        mConditions.forEach(condition -> verify(condition).removeCallback(any()));
    }

    @Test
    public void updateCallbacks_allConditionsMet_reportTrue() {
        final CommunalConditionsMonitor.Callback callback =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback);
        clearInvocations(callback);

        mCondition1.fakeUpdateCondition(true);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);

        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void updateCallbacks_oneConditionStoppedMeeting_reportFalse() {
        final CommunalConditionsMonitor.Callback callback =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback);

        mCondition1.fakeUpdateCondition(true);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);
        clearInvocations(callback);

        mCondition1.fakeUpdateCondition(false);
        verify(callback).onConditionsChanged(false);
    }

    @Test
    public void updateCallbacks_shouldOnlyUpdateWhenValueChanges() {
        final CommunalConditionsMonitor.Callback callback =
                mock(CommunalConditionsMonitor.Callback.class);
        mCommunalConditionsMonitor.addCallback(callback);
        verify(callback).onConditionsChanged(false);
        clearInvocations(callback);

        mCondition1.fakeUpdateCondition(true);
        verify(callback, never()).onConditionsChanged(anyBoolean());

        mCondition2.fakeUpdateCondition(true);
        verify(callback, never()).onConditionsChanged(anyBoolean());

        mCondition3.fakeUpdateCondition(true);
        verify(callback).onConditionsChanged(true);
    }
}
