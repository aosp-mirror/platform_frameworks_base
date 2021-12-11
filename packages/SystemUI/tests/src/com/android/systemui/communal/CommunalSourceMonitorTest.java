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


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.condition.Monitor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.ref.WeakReference;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CommunalSourceMonitorTest extends SysuiTestCase {
    @Mock private Monitor mCommunalConditionsMonitor;

    @Captor private ArgumentCaptor<Monitor.Callback> mConditionsCallbackCaptor;

    private CommunalSourceMonitor mCommunalSourceMonitor;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mCommunalSourceMonitor = new CommunalSourceMonitor(mExecutor, mCommunalConditionsMonitor);
    }

    @Test
    public void testSourceAddedBeforeCallbackAdded() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        setSource(source);
        mCommunalSourceMonitor.addCallback(callback);
        setConditionsMet(true);

        verifyOnSourceAvailableCalledWith(callback, source);
    }

    @Test
    public void testRemoveCallback() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        mCommunalSourceMonitor.removeCallback(callback);
        setSource(source);

        verify(callback, never()).onSourceAvailable(any());
    }

    @Test
    public void testAddCallbackWithConditionsMet() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        setConditionsMet(true);
        clearInvocations(callback);
        setSource(source);

        verifyOnSourceAvailableCalledWith(callback, source);
    }

    @Test
    public void testAddCallbackWithConditionsNotMet() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        setConditionsMet(false);
        setSource(source);

        verify(callback, never()).onSourceAvailable(any());
    }

    @Test
    public void testConditionsAreMetAfterCallbackAdded() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        setSource(source);

        // The callback should not have executed since communal is disabled.
        verify(callback, never()).onSourceAvailable(any());

        // The callback should execute when all conditions are met.
        setConditionsMet(true);
        verifyOnSourceAvailableCalledWith(callback, source);
    }

    @Test
    public void testConditionsNoLongerMetAfterCallbackAdded() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        setSource(source);
        setConditionsMet(true);
        verifyOnSourceAvailableCalledWith(callback, source);

        // The callback should execute again when conditions are no longer met, with a value of
        // null.
        setConditionsMet(false);
        verify(callback).onSourceAvailable(null);
    }

    private void verifyOnSourceAvailableCalledWith(CommunalSourceMonitor.Callback callback,
            CommunalSource source) {
        final ArgumentCaptor<WeakReference<CommunalSource>> sourceCapture =
                ArgumentCaptor.forClass(WeakReference.class);
        verify(callback).onSourceAvailable(sourceCapture.capture());
        assertThat(sourceCapture.getValue().get()).isEqualTo(source);
    }

    // Pushes an update on whether the communal conditions are met, assuming that a callback has
    // been registered with the communal conditions monitor.
    private void setConditionsMet(boolean value) {
        mExecutor.runAllReady();
        verify(mCommunalConditionsMonitor).addCallback(mConditionsCallbackCaptor.capture());
        final Monitor.Callback conditionsCallback =
                mConditionsCallbackCaptor.getValue();
        conditionsCallback.onConditionsChanged(value);
        mExecutor.runAllReady();
    }

    private void setSource(CommunalSource source) {
        mCommunalSourceMonitor.setSource(source);
        mExecutor.runAllReady();
    }
}
