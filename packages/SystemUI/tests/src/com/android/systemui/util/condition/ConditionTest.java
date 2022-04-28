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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
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

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ConditionTest extends SysuiTestCase {
    private FakeCondition mCondition;

    @Before
    public void setup() {
        mCondition = spy(new FakeCondition());
    }

    @Test
    public void addCallback_addFirstCallback_triggerStart() {
        final Condition.Callback callback = mock(Condition.Callback.class);
        mCondition.addCallback(callback);
        verify(mCondition).start();
    }

    @Test
    public void addCallback_addMultipleCallbacks_triggerStartOnlyOnce() {
        final Condition.Callback callback1 = mock(Condition.Callback.class);
        final Condition.Callback callback2 = mock(Condition.Callback.class);
        final Condition.Callback callback3 = mock(Condition.Callback.class);

        mCondition.addCallback(callback1);
        mCondition.addCallback(callback2);
        mCondition.addCallback(callback3);

        verify(mCondition, times(1)).start();
    }

    @Test
    public void addCallback_alreadyStarted_triggerUpdate() {
        final Condition.Callback callback1 = mock(Condition.Callback.class);
        mCondition.addCallback(callback1);

        mCondition.fakeUpdateCondition(true);

        final Condition.Callback callback2 = mock(Condition.Callback.class);
        mCondition.addCallback(callback2);
        verify(callback2).onConditionChanged(mCondition);
        assertThat(mCondition.isConditionMet()).isTrue();
    }

    @Test
    public void removeCallback_removeLastCallback_triggerStop() {
        final Condition.Callback callback = mock(Condition.Callback.class);
        mCondition.addCallback(callback);
        verify(mCondition, never()).stop();

        mCondition.removeCallback(callback);
        verify(mCondition).stop();
    }

    @Test
    public void updateCondition_falseToTrue_reportTrue() {
        mCondition.fakeUpdateCondition(false);

        final Condition.Callback callback = mock(Condition.Callback.class);
        mCondition.addCallback(callback);

        mCondition.fakeUpdateCondition(true);
        verify(callback).onConditionChanged(eq(mCondition));
        assertThat(mCondition.isConditionMet()).isTrue();
    }

    @Test
    public void updateCondition_trueToFalse_reportFalse() {
        mCondition.fakeUpdateCondition(true);

        final Condition.Callback callback = mock(Condition.Callback.class);
        mCondition.addCallback(callback);

        mCondition.fakeUpdateCondition(false);
        verify(callback).onConditionChanged(eq(mCondition));
        assertThat(mCondition.isConditionMet()).isFalse();
    }

    @Test
    public void updateCondition_trueToTrue_reportNothing() {
        mCondition.fakeUpdateCondition(true);

        final Condition.Callback callback = mock(Condition.Callback.class);
        mCondition.addCallback(callback);

        mCondition.fakeUpdateCondition(true);
        verify(callback, never()).onConditionChanged(eq(mCondition));
    }

    @Test
    public void updateCondition_falseToFalse_reportNothing() {
        mCondition.fakeUpdateCondition(false);

        final Condition.Callback callback = mock(Condition.Callback.class);
        mCondition.addCallback(callback);

        mCondition.fakeUpdateCondition(false);
        verify(callback, never()).onConditionChanged(eq(mCondition));
    }
}
