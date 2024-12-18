/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shared.condition;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.log.TableLogBufferBase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import kotlinx.coroutines.CoroutineScope;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ConditionMonitorTest extends SysuiTestCase {
    private FakeCondition mCondition1;
    private FakeCondition mCondition2;
    private FakeCondition mCondition3;
    private HashSet<Condition> mConditions;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Mock
    private CoroutineScope mScope;
    @Mock
    private TableLogBufferBase mLogBuffer;

    private Monitor mConditionMonitor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mCondition1 = spy(new FakeCondition(mScope));
        mCondition2 = spy(new FakeCondition(mScope));
        mCondition3 = spy(new FakeCondition(mScope));
        mConditions = new HashSet<>(Arrays.asList(mCondition1, mCondition2, mCondition3));

        mConditionMonitor = new Monitor(mExecutor);
    }

    public Monitor.Subscription.Builder getDefaultBuilder(
            Monitor.Callback callback) {
        return new Monitor.Subscription.Builder(callback)
                .addConditions(mConditions);
    }

    private Condition createMockCondition() {
        final Condition condition = Mockito.mock(
                Condition.class);
        when(condition.isConditionSet()).thenReturn(true);
        return condition;
    }

    @Test
    public void testOverridingCondition() {
        final Condition overridingCondition = createMockCondition();
        final Condition regularCondition = createMockCondition();
        final Monitor.Callback callback = Mockito.mock(
                Monitor.Callback.class);

        final Monitor.Callback referenceCallback = Mockito.mock(
                Monitor.Callback.class);

        final Monitor
                monitor = new Monitor(mExecutor);

        monitor.addSubscription(getDefaultBuilder(callback)
                .addCondition(overridingCondition)
                .addCondition(regularCondition)
                .build());

        monitor.addSubscription(getDefaultBuilder(referenceCallback)
                .addCondition(regularCondition)
                .build());

        mExecutor.runAllReady();

        when(overridingCondition.isOverridingCondition()).thenReturn(true);
        when(overridingCondition.isConditionMet()).thenReturn(true);
        when(regularCondition.isConditionMet()).thenReturn(false);

        final ArgumentCaptor<Condition.Callback> mCallbackCaptor =
                ArgumentCaptor.forClass(Condition.Callback.class);

        verify(overridingCondition).addCallback(mCallbackCaptor.capture());

        mCallbackCaptor.getValue().onConditionChanged(overridingCondition);
        mExecutor.runAllReady();

        verify(callback).onConditionsChanged(eq(true));
        verify(referenceCallback).onConditionsChanged(eq(false));
        Mockito.clearInvocations(callback);
        Mockito.clearInvocations(referenceCallback);

        when(regularCondition.isConditionMet()).thenReturn(true);
        when(overridingCondition.isConditionMet()).thenReturn(false);

        mCallbackCaptor.getValue().onConditionChanged(overridingCondition);
        mExecutor.runAllReady();

        verify(callback).onConditionsChanged(eq(false));
        verify(referenceCallback, never()).onConditionsChanged(anyBoolean());
    }

    /**
     * Ensures that when multiple overriding conditions are present, it is the aggregate of those
     * conditions that are considered.
     */
    @Test
    public void testMultipleOverridingConditions() {
        final Condition overridingCondition = createMockCondition();
        final Condition overridingCondition2 = createMockCondition();
        final Condition regularCondition = createMockCondition();
        final Monitor.Callback callback = Mockito.mock(
                Monitor.Callback.class);

        final Monitor
                monitor = new Monitor(mExecutor);

        monitor.addSubscription(getDefaultBuilder(callback)
                .addCondition(overridingCondition)
                .addCondition(overridingCondition2)
                .build());

        mExecutor.runAllReady();

        when(overridingCondition.isOverridingCondition()).thenReturn(true);
        when(overridingCondition.isConditionMet()).thenReturn(true);
        when(overridingCondition2.isOverridingCondition()).thenReturn(true);
        when(overridingCondition.isConditionMet()).thenReturn(false);
        when(regularCondition.isConditionMet()).thenReturn(true);

        final ArgumentCaptor<Condition.Callback> mCallbackCaptor =
                ArgumentCaptor.forClass(Condition.Callback.class);

        verify(overridingCondition).addCallback(mCallbackCaptor.capture());

        mCallbackCaptor.getValue().onConditionChanged(overridingCondition);
        mExecutor.runAllReady();

        verify(callback).onConditionsChanged(eq(false));
        Mockito.clearInvocations(callback);
    }

    // Ensure that updating a callback that is removed doesn't result in an exception due to the
    // absence of the condition.
    @Test
    public void testUpdateRemovedCallback() {
        final Monitor.Callback callback1 =
                mock(Monitor.Callback.class);
        final Monitor.Subscription.Token subscription1 =
                mConditionMonitor.addSubscription(getDefaultBuilder(callback1).build());
        ArgumentCaptor<Condition.Callback> monitorCallback =
                ArgumentCaptor.forClass(Condition.Callback.class);
        mExecutor.runAllReady();
        verify(mCondition1).addCallback(monitorCallback.capture());
        // This will execute first before the handler for onConditionChanged.
        mConditionMonitor.removeSubscription(subscription1);
        monitorCallback.getValue().onConditionChanged(mCondition1);
        mExecutor.runAllReady();
    }

    @Test
    public void addCallback_addFirstCallback_addCallbackToAllConditions() {
        final Monitor.Callback callback1 =
                mock(Monitor.Callback.class);
        mConditionMonitor.addSubscription(getDefaultBuilder(callback1).build());
        mExecutor.runAllReady();
        mConditions.forEach(condition -> verify(condition).addCallback(any()));

        final Monitor.Callback callback2 =
                mock(Monitor.Callback.class);
        mConditionMonitor.addSubscription(getDefaultBuilder(callback2).build());
        mExecutor.runAllReady();
        mConditions.forEach(condition -> verify(condition, times(1)).addCallback(any()));
    }

    @Test
    public void addCallback_addFirstCallback_reportWithDefaultValue() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addSubscription(getDefaultBuilder(callback).build());
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(false);
    }

    @Test
    public void addCallback_addSecondCallback_reportWithExistingValue() {
        final Monitor.Callback callback1 =
                mock(Monitor.Callback.class);
        final Condition condition = mock(
                Condition.class);
        when(condition.isConditionMet()).thenReturn(true);
        final Monitor
                monitor = new Monitor(mExecutor);
        monitor.addSubscription(new Monitor.Subscription.Builder(callback1)
                .addCondition(condition)
                .build());

        final Monitor.Callback callback2 =
                mock(Monitor.Callback.class);
        monitor.addSubscription(new Monitor.Subscription.Builder(callback2)
                .addCondition(condition)
                .build());
        mExecutor.runAllReady();
        verify(callback2).onConditionsChanged(eq(true));
    }

    @Test
    public void addCallback_noConditions_reportAllConditionsMet() {
        final Monitor
                monitor = new Monitor(mExecutor);
        final Monitor.Callback callback = mock(
                Monitor.Callback.class);

        monitor.addSubscription(new Monitor.Subscription.Builder(callback).build());
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void addCallback_preCondition_noConditions_reportAllConditionsMet() {
        final Monitor
                monitor = new Monitor(mExecutor, new HashSet<>(Arrays.asList(mCondition1)));
        final Monitor.Callback callback = mock(
                Monitor.Callback.class);

        monitor.addSubscription(new Monitor.Subscription.Builder(callback).build());
        mExecutor.runAllReady();
        verify(callback, never()).onConditionsChanged(true);
        mCondition1.fakeUpdateCondition(true);
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void removeCallback_noFailureOnDoubleRemove() {
        final Condition condition = mock(
                Condition.class);
        final Monitor
                monitor = new Monitor(mExecutor);
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        final Monitor.Subscription.Token token = monitor.addSubscription(
                new Monitor.Subscription.Builder(callback).addCondition(condition).build()
        );
        monitor.removeSubscription(token);
        mExecutor.runAllReady();
        // Ensure second removal doesn't cause an exception.
        monitor.removeSubscription(token);
        mExecutor.runAllReady();
    }

    @Test
    public void removeCallback_shouldNoLongerReceiveUpdate() {
        final Condition condition = mock(
                Condition.class);
        final Monitor
                monitor = new Monitor(mExecutor);
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        final Monitor.Subscription.Token token = monitor.addSubscription(
                new Monitor.Subscription.Builder(callback).addCondition(condition).build()
        );
        monitor.removeSubscription(token);
        mExecutor.runAllReady();
        clearInvocations(callback);

        final ArgumentCaptor<Condition.Callback> conditionCallbackCaptor =
                ArgumentCaptor.forClass(Condition.Callback.class);
        verify(condition).addCallback(conditionCallbackCaptor.capture());

        final Condition.Callback conditionCallback = conditionCallbackCaptor.getValue();
        verify(condition).removeCallback(conditionCallback);
    }

    @Test
    public void removeCallback_removeLastCallback_removeCallbackFromAllConditions() {
        final Monitor.Callback callback1 =
                mock(Monitor.Callback.class);
        final Monitor.Callback callback2 =
                mock(Monitor.Callback.class);
        final Monitor.Subscription.Token subscription1 =
                mConditionMonitor.addSubscription(getDefaultBuilder(callback1).build());
        final Monitor.Subscription.Token subscription2 =
                mConditionMonitor.addSubscription(getDefaultBuilder(callback2).build());

        mConditionMonitor.removeSubscription(subscription1);
        mExecutor.runAllReady();
        mConditions.forEach(condition -> verify(condition, never()).removeCallback(any()));

        mConditionMonitor.removeSubscription(subscription2);
        mExecutor.runAllReady();
        mConditions.forEach(condition -> verify(condition).removeCallback(any()));
    }

    @Test
    public void updateCallbacks_allConditionsMet_reportTrue() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addSubscription(getDefaultBuilder(callback).build());
        clearInvocations(callback);

        mCondition1.fakeUpdateCondition(true);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);
        mExecutor.runAllReady();

        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void updateCallbacks_oneConditionStoppedMeeting_reportFalse() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addSubscription(getDefaultBuilder(callback).build());

        mCondition1.fakeUpdateCondition(true);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);
        clearInvocations(callback);

        mCondition1.fakeUpdateCondition(false);
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(false);
    }

    @Test
    public void updateCallbacks_shouldOnlyUpdateWhenValueChanges() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addSubscription(getDefaultBuilder(callback).build());
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(false);
        clearInvocations(callback);

        mCondition1.fakeUpdateCondition(true);
        mExecutor.runAllReady();
        verify(callback, never()).onConditionsChanged(anyBoolean());

        mCondition2.fakeUpdateCondition(true);
        mExecutor.runAllReady();
        verify(callback, never()).onConditionsChanged(anyBoolean());

        mCondition3.fakeUpdateCondition(true);
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void clearCondition_shouldUpdateValue() {
        mCondition1.fakeUpdateCondition(false);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);

        final Monitor.Callback callback =
                mock(Monitor.Callback.class);
        mConditionMonitor.addSubscription(getDefaultBuilder(callback).build());
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(false);

        mCondition1.clearCondition();
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void unsetCondition_shouldNotAffectValue() {
        final FakeCondition settableCondition = new FakeCondition(mScope, null, false);
        mCondition1.fakeUpdateCondition(true);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);

        final Monitor.Callback callback =
                mock(Monitor.Callback.class);

        mConditionMonitor.addSubscription(getDefaultBuilder(callback)
                .addCondition(settableCondition)
                .build());

        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void setUnsetCondition_shouldAffectValue() {
        final FakeCondition settableCondition = new FakeCondition(mScope, null, false);
        mCondition1.fakeUpdateCondition(true);
        mCondition2.fakeUpdateCondition(true);
        mCondition3.fakeUpdateCondition(true);

        final Monitor.Callback callback =
                mock(Monitor.Callback.class);

        mConditionMonitor.addSubscription(getDefaultBuilder(callback)
                .addCondition(settableCondition)
                .build());

        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
        clearInvocations(callback);

        settableCondition.fakeUpdateCondition(false);
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(false);
        clearInvocations(callback);


        settableCondition.clearCondition();
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
    }

    @Test
    public void clearingOverridingCondition_shouldBeExcluded() {
        final FakeCondition overridingCondition = new FakeCondition(mScope, true, true);
        mCondition1.fakeUpdateCondition(false);
        mCondition2.fakeUpdateCondition(false);
        mCondition3.fakeUpdateCondition(false);

        final Monitor.Callback callback =
                mock(Monitor.Callback.class);

        mConditionMonitor.addSubscription(getDefaultBuilder(callback)
                .addCondition(overridingCondition)
                .build());

        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
        clearInvocations(callback);

        overridingCondition.clearCondition();
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(false);
    }

    @Test
    public void settingUnsetOverridingCondition_shouldBeIncluded() {
        final FakeCondition overridingCondition = new FakeCondition(mScope, null, true);
        mCondition1.fakeUpdateCondition(false);
        mCondition2.fakeUpdateCondition(false);
        mCondition3.fakeUpdateCondition(false);

        final Monitor.Callback callback =
                mock(Monitor.Callback.class);

        mConditionMonitor.addSubscription(getDefaultBuilder(callback)
                .addCondition(overridingCondition)
                .build());

        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(false);
        clearInvocations(callback);

        overridingCondition.fakeUpdateCondition(true);
        mExecutor.runAllReady();
        verify(callback).onConditionsChanged(true);
    }

    /**
     * Ensures that the result of a condition being true leads to its nested condition being
     * activated.
     */
    @Test
    public void testNestedCondition() {
        mCondition1.fakeUpdateCondition(false);
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);

        mCondition2.fakeUpdateCondition(false);

        // Create a nested condition
        mConditionMonitor.addSubscription(new Monitor.Subscription.Builder(
                new Monitor.Subscription.Builder(callback)
                        .addCondition(mCondition2)
                        .build())
                .addCondition(mCondition1)
                .build());

        mExecutor.runAllReady();

        // Ensure the nested condition callback is not called at all.
        verify(callback, never()).onActiveChanged(anyBoolean());
        verify(callback, never()).onConditionsChanged(anyBoolean());

        // Update the inner condition to true and ensure that the nested condition is not triggered.
        mCondition2.fakeUpdateCondition(true);
        verify(callback, never()).onConditionsChanged(anyBoolean());
        mCondition2.fakeUpdateCondition(false);

        // Set outer condition and make sure the inner condition becomes active and reports that
        // conditions aren't met
        mCondition1.fakeUpdateCondition(true);
        mExecutor.runAllReady();

        verify(callback).onActiveChanged(eq(true));
        verify(callback).onConditionsChanged(eq(false));

        Mockito.clearInvocations(callback);

        // Update the inner condition and make sure the callback is updated.
        mCondition2.fakeUpdateCondition(true);
        mExecutor.runAllReady();

        verify(callback).onConditionsChanged(true);

        Mockito.clearInvocations(callback);
        // Invalidate outer condition and make sure callback is informed, but the last state is
        // not affected.
        mCondition1.fakeUpdateCondition(false);
        mExecutor.runAllReady();

        verify(callback).onActiveChanged(eq(false));
        verify(callback, never()).onConditionsChanged(anyBoolean());
    }

    /**
     * Ensure preconditions are applied to every subscription added to a monitor.
     */
    @Test
    public void testPreconditionMonitor() {
        final Monitor.Callback callback =
                mock(Monitor.Callback.class);

        mCondition2.fakeUpdateCondition(true);
        final Monitor monitor = new Monitor(mExecutor, new HashSet<>(Arrays.asList(mCondition1)));

        monitor.addSubscription(new Monitor.Subscription.Builder(callback)
                .addCondition(mCondition2)
                .build());

        mExecutor.runAllReady();

        verify(callback, never()).onActiveChanged(anyBoolean());
        verify(callback, never()).onConditionsChanged(anyBoolean());

        mCondition1.fakeUpdateCondition(true);
        mExecutor.runAllReady();

        verify(callback).onActiveChanged(eq(true));
        verify(callback).onConditionsChanged(eq(true));
    }

    @Test
    public void testLoggingCallback() {
        final Monitor monitor = new Monitor(mExecutor, Collections.emptySet(), mLogBuffer);

        final FakeCondition condition = new FakeCondition(mScope);
        final FakeCondition overridingCondition = new FakeCondition(
                mScope,
                /* initialValue= */ false,
                /* overriding= */ true);

        final Monitor.Callback callback = mock(Monitor.Callback.class);
        monitor.addSubscription(getDefaultBuilder(callback)
                .addCondition(condition)
                .addCondition(overridingCondition)
                .build());
        mExecutor.runAllReady();

        // condition set to true
        condition.fakeUpdateCondition(true);
        mExecutor.runAllReady();
        verify(mLogBuffer).logChange("", "FakeCondition", "True");

        // condition set to false
        condition.fakeUpdateCondition(false);
        mExecutor.runAllReady();
        verify(mLogBuffer).logChange("", "FakeCondition", "False");

        // condition unset
        condition.fakeClearCondition();
        mExecutor.runAllReady();
        verify(mLogBuffer).logChange("", "FakeCondition", "Invalid");

        // overriding condition set to true
        overridingCondition.fakeUpdateCondition(true);
        mExecutor.runAllReady();
        verify(mLogBuffer).logChange("", "FakeCondition[OVRD]", "True");
    }
}
