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

package com.android.systemui.util.condition;

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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ConditionMonitorTest extends SysuiTestCase {
    private FakeCondition mCondition1;
    private FakeCondition mCondition2;
    private FakeCondition mCondition3;
    private HashSet<Condition> mConditions;

    private Monitor mConditionMonitor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mCondition1 = spy(new FakeCondition());
        mCondition2 = spy(new FakeCondition());
        mCondition3 = spy(new FakeCondition());
        mConditions = new HashSet<>(Arrays.asList(mCondition1, mCondition2, mCondition3));

        mConditionMonitor = new Monitor(mConditions, null /*callbacks*/);
    }

    @Test
    public void addCallback_addFirstCallback_addCallbackToAllConditions() {
        final Monitor.Callback callback1 =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback1);
        mConditions.forEach(condition -> verify(condition).addCallback(any()));

        final Monitor.Callback callback2 =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback2);
        mConditions.forEach(condition -> verify(condition, times(1)).addCallback(any()));
    }

    @Test
    public void addCallback_addFirstCallback_reportWithDefaultValue() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback);
        verify(callback).onConditionsChanged(false);
    }

    @Test
    public void addCallback_addSecondCallback_reportWithExistingValue() {
        final Monitor.Callback callback1 =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback1);

        mConditionMonitor.overrideAllConditionsMet(true);

        final Monitor.Callback callback2 =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback2);
        verify(callback2).onConditionsChanged(true);
    }

    @Test
    public void addCallback_noConditions_reportAllConditionsMet() {
        final Monitor monitor = new Monitor(new HashSet<>(), null /*callbacks*/);
        final Monitor.Callback callback = mock(Monitor.Callback.class);

        monitor.addCallback(callback);

        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void removeCallback_shouldNoLongerReceiveUpdate() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback);
        clearInvocations(callback);
        mConditionMonitor.removeCallback(callback);

        mConditionMonitor.overrideAllConditionsMet(true);
        verify(callback, never()).onConditionsChanged(true);

        mConditionMonitor.overrideAllConditionsMet(false);
        verify(callback, never()).onConditionsChanged(false);
    }

    @Test
    public void removeCallback_removeLastCallback_removeCallbackFromAllConditions() {
        final Monitor.Callback callback1 =
                mock(Monitor.Callback.class);
        final Monitor.Callback callback2 =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback1);
        mConditionMonitor.addCallback(callback2);

        mConditionMonitor.removeCallback(callback1);
        mConditions.forEach(condition -> verify(condition, never()).removeCallback(any()));

        mConditionMonitor.removeCallback(callback2);
        mConditions.forEach(condition -> verify(condition).removeCallback(any()));
    }

    @Test
    public void updateCallbacks_allConditionsMet_reportTrue() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback);
        clearInvocations(callback);

        mCondition1.fakeUpdateCondition(true);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);

        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void updateCallbacks_oneConditionStoppedMeeting_reportFalse() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback);

        mCondition1.fakeUpdateCondition(true);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);
        clearInvocations(callback);

        mCondition1.fakeUpdateCondition(false);
        verify(callback).onConditionsChanged(false);
    }

    @Test
    public void updateCallbacks_shouldOnlyUpdateWhenValueChanges() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addCallback(callback);
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
