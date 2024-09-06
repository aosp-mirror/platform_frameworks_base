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

package com.android.systemui.util.condition;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.CoreStartable;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.shared.condition.Monitor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ConditionalCoreStartableTest extends SysuiTestCase {
    public static class FakeConditionalCoreStartable extends ConditionalCoreStartable {
        interface Callback {
            void onStart();
            void bootCompleted();
        }

        private final Callback mCallback;

        public FakeConditionalCoreStartable(Monitor monitor, Set<Condition> conditions,
                Callback callback) {
            super(monitor, conditions);
            mCallback = callback;
        }

        public FakeConditionalCoreStartable(Monitor monitor, Callback callback) {
            super(monitor);
            mCallback = callback;
        }

        @Override
        protected void onStart() {
            mCallback.onStart();
        }

        @Override
        protected void bootCompleted() {
            mCallback.bootCompleted();
        }
    }


    final Set<Condition> mConditions = new HashSet<>();

    @Mock
    Condition mCondition;

    @Mock
    Monitor mMonitor;

    @Mock
    FakeConditionalCoreStartable.Callback mCallback;

    @Mock
    Monitor.Subscription.Token mSubscriptionToken;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mConditions.clear();
    }

    /**
     * Verifies that {@link ConditionalCoreStartable#onStart()} is predicated on conditions being
     * met.
     */
    @Test
    public void testOnStartCallback() {
        final CoreStartable coreStartable =
                new FakeConditionalCoreStartable(mMonitor,
                        new HashSet<>(Arrays.asList(mCondition)),
                        mCallback);

        when(mMonitor.addSubscription(any())).thenReturn(mSubscriptionToken);
        coreStartable.start();

        final ArgumentCaptor<Monitor.Subscription> subscriptionCaptor = ArgumentCaptor.forClass(
                Monitor.Subscription.class);
        verify(mMonitor).addSubscription(subscriptionCaptor.capture());

        final Monitor.Subscription subscription = subscriptionCaptor.getValue();

        assertThat(subscription.getConditions()).containsExactly(mCondition);

        verify(mCallback, never()).onStart();

        subscription.getCallback().onConditionsChanged(true);

        verify(mCallback).onStart();
        verify(mMonitor).removeSubscription(mSubscriptionToken);
    }

    @Test
    public void testOnStartCallbackWithNoConditions() {
        final CoreStartable coreStartable =
                new FakeConditionalCoreStartable(mMonitor,
                        mCallback);

        when(mMonitor.addSubscription(any())).thenReturn(mSubscriptionToken);
        coreStartable.start();

        final ArgumentCaptor<Monitor.Subscription> subscriptionCaptor = ArgumentCaptor.forClass(
                Monitor.Subscription.class);
        verify(mMonitor).addSubscription(subscriptionCaptor.capture());

        final Monitor.Subscription subscription = subscriptionCaptor.getValue();

        assertThat(subscription.getConditions()).isEmpty();

        verify(mCallback, never()).onStart();

        subscription.getCallback().onConditionsChanged(true);

        verify(mCallback).onStart();
        verify(mMonitor).removeSubscription(mSubscriptionToken);
    }


    /**
     * Verifies that {@link ConditionalCoreStartable#bootCompleted()} ()} is predicated on
     * conditions being met.
     */
    @Test
    public void testBootCompleted() {
        final CoreStartable coreStartable =
                new FakeConditionalCoreStartable(mMonitor,
                        new HashSet<>(Arrays.asList(mCondition)),
                        mCallback);

        when(mMonitor.addSubscription(any())).thenReturn(mSubscriptionToken);
        coreStartable.onBootCompleted();

        final ArgumentCaptor<Monitor.Subscription> subscriptionCaptor = ArgumentCaptor.forClass(
                Monitor.Subscription.class);
        verify(mMonitor).addSubscription(subscriptionCaptor.capture());

        final Monitor.Subscription subscription = subscriptionCaptor.getValue();

        assertThat(subscription.getConditions()).containsExactly(mCondition);

        verify(mCallback, never()).bootCompleted();

        subscription.getCallback().onConditionsChanged(true);

        verify(mCallback).bootCompleted();
        verify(mMonitor).removeSubscription(mSubscriptionToken);
    }
}
