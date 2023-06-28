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

package com.android.systemui.process.condition;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.process.ProcessWrapper;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SystemProcessConditionTest extends SysuiTestCase {
    @Mock
    ProcessWrapper mProcessWrapper;

    @Mock
    Monitor.Callback mCallback;

    private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Verifies condition reports false when tracker reports the process is being ran by the
     * system user.
     */
    @Test
    public void testConditionFailsWithNonSystemProcess() {

        final Condition condition = new SystemProcessCondition(mProcessWrapper);
        when(mProcessWrapper.isSystemUser()).thenReturn(false);

        final Monitor monitor = new Monitor(mExecutor);

        monitor.addSubscription(new Monitor.Subscription.Builder(mCallback)
                .addCondition(condition)
                .build());

        mExecutor.runAllReady();

        verify(mCallback).onConditionsChanged(false);
    }

    /**
     * Verifies condition reports true when tracker reports the process is being ran by the
     * system user.
     */
    @Test
    public void testConditionSucceedsWithSystemProcess() {

        final Condition condition = new SystemProcessCondition(mProcessWrapper);
        when(mProcessWrapper.isSystemUser()).thenReturn(true);

        final Monitor monitor = new Monitor(mExecutor);

        monitor.addSubscription(new Monitor.Subscription.Builder(mCallback)
                .addCondition(condition)
                .build());

        mExecutor.runAllReady();

        verify(mCallback).onConditionsChanged(true);
    }
}
