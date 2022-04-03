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

package com.android.server.biometrics.sensors;

import static android.testing.TestableLooper.RunWithLooper;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.os.Handler;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class BiometricSchedulerOperationTest {

    public interface FakeHal {}
    public abstract static class InterruptableMonitor<T>
            extends HalClientMonitor<T> implements  Interruptable {
        public InterruptableMonitor() {
            super(null, null, null, null, 0, null, 0, 0, 0, 0, 0);
        }
    }

    @Mock
    private InterruptableMonitor<FakeHal> mClientMonitor;
    @Mock
    private BaseClientMonitor.Callback mClientCallback;
    @Mock
    private FakeHal mHal;
    @Captor
    ArgumentCaptor<BaseClientMonitor.Callback> mStartCallback;

    private Handler mHandler;
    private BiometricSchedulerOperation mOperation;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mHandler = new Handler(TestableLooper.get(this).getLooper());
        mOperation = new BiometricSchedulerOperation(mClientMonitor, mClientCallback);
    }

    @Test
    public void testStartWithCookie() {
        final int cookie = 200;
        when(mClientMonitor.getCookie()).thenReturn(cookie);
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        assertThat(mOperation.isReadyToStart()).isEqualTo(cookie);
        assertThat(mOperation.isStarted()).isFalse();
        assertThat(mOperation.isCanceling()).isFalse();
        assertThat(mOperation.isFinished()).isFalse();

        final boolean started = mOperation.startWithCookie(
                mock(BaseClientMonitor.Callback.class), cookie);

        assertThat(started).isTrue();
        verify(mClientMonitor).start(mStartCallback.capture());
        mStartCallback.getValue().onClientStarted(mClientMonitor);
        assertThat(mOperation.isStarted()).isTrue();
    }

    @Test
    public void testNoStartWithoutCookie() {
        final int goodCookie = 20;
        final int badCookie = 22;
        when(mClientMonitor.getCookie()).thenReturn(goodCookie);
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        assertThat(mOperation.isReadyToStart()).isEqualTo(goodCookie);
        final boolean started = mOperation.startWithCookie(
                mock(BaseClientMonitor.Callback.class), badCookie);

        assertThat(started).isFalse();
        assertThat(mOperation.isStarted()).isFalse();
        assertThat(mOperation.isCanceling()).isFalse();
        assertThat(mOperation.isFinished()).isFalse();
    }

    @Test
    public void startsWhenReadyAndHalAvailable() {
        when(mClientMonitor.getCookie()).thenReturn(0);
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final BaseClientMonitor.Callback cb = mock(BaseClientMonitor.Callback.class);
        mOperation.start(cb);
        verify(mClientMonitor).start(mStartCallback.capture());
        mStartCallback.getValue().onClientStarted(mClientMonitor);

        assertThat(mOperation.isStarted()).isTrue();
        assertThat(mOperation.isCanceling()).isFalse();
        assertThat(mOperation.isFinished()).isFalse();

        verify(mClientCallback).onClientStarted(eq(mClientMonitor));
        verify(cb).onClientStarted(eq(mClientMonitor));
        verify(mClientCallback, never()).onClientFinished(any(), anyBoolean());
        verify(cb, never()).onClientFinished(any(), anyBoolean());

        mStartCallback.getValue().onClientFinished(mClientMonitor, true);

        assertThat(mOperation.isFinished()).isTrue();
        assertThat(mOperation.isCanceling()).isFalse();
        verify(mClientMonitor).destroy();
        verify(cb).onClientFinished(eq(mClientMonitor), eq(true));
    }

    @Test
    public void startFailsWhenReadyButHalNotAvailable() {
        when(mClientMonitor.getCookie()).thenReturn(0);
        when(mClientMonitor.getFreshDaemon()).thenReturn(null);

        final BaseClientMonitor.Callback cb = mock(BaseClientMonitor.Callback.class);
        mOperation.start(cb);
        verify(mClientMonitor, never()).start(any());

        assertThat(mOperation.isStarted()).isFalse();
        assertThat(mOperation.isCanceling()).isFalse();
        assertThat(mOperation.isFinished()).isTrue();

        verify(mClientCallback, never()).onClientStarted(eq(mClientMonitor));
        verify(cb, never()).onClientStarted(eq(mClientMonitor));
        verify(mClientCallback).onClientFinished(eq(mClientMonitor), eq(false));
        verify(cb).onClientFinished(eq(mClientMonitor), eq(false));
    }

    @Test
    public void doesNotStartWithCookie() {
        when(mClientMonitor.getCookie()).thenReturn(9);
        assertThrows(IllegalStateException.class,
                () -> mOperation.start(mock(BaseClientMonitor.Callback.class)));
    }

    @Test
    public void cannotRestart() {
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mOperation.start(mock(BaseClientMonitor.Callback.class));

        assertThrows(IllegalStateException.class,
                () -> mOperation.start(mock(BaseClientMonitor.Callback.class)));
    }

    @Test
    public void abortsNotRunning() {
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mOperation.abort();

        assertThat(mOperation.isFinished()).isTrue();
        verify(mClientMonitor).unableToStart();
        verify(mClientMonitor).destroy();
        assertThrows(IllegalStateException.class,
                () -> mOperation.start(mock(BaseClientMonitor.Callback.class)));
    }

    @Test
    public void cannotAbortRunning() {
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mOperation.start(mock(BaseClientMonitor.Callback.class));

        assertThrows(IllegalStateException.class, () -> mOperation.abort());
    }

    @Test
    public void cancel() {
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final BaseClientMonitor.Callback startCb = mock(BaseClientMonitor.Callback.class);
        final BaseClientMonitor.Callback cancelCb = mock(BaseClientMonitor.Callback.class);
        mOperation.start(startCb);
        verify(mClientMonitor).start(mStartCallback.capture());
        mStartCallback.getValue().onClientStarted(mClientMonitor);
        mOperation.cancel(mHandler, cancelCb);

        assertThat(mOperation.isCanceling()).isTrue();
        verify(mClientMonitor).cancel();
        verify(mClientMonitor, never()).cancelWithoutStarting(any());
        verify(mClientMonitor, never()).destroy();

        mStartCallback.getValue().onClientFinished(mClientMonitor, true);

        assertThat(mOperation.isFinished()).isTrue();
        assertThat(mOperation.isCanceling()).isFalse();
        verify(mClientMonitor).destroy();

        // should be unused since the operation was started
        verify(cancelCb, never()).onClientStarted(any());
        verify(cancelCb, never()).onClientFinished(any(), anyBoolean());
    }

    @Test
    public void cancelWithoutStarting() {
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final BaseClientMonitor.Callback cancelCb = mock(BaseClientMonitor.Callback.class);
        mOperation.cancel(mHandler, cancelCb);

        assertThat(mOperation.isCanceling()).isTrue();
        ArgumentCaptor<BaseClientMonitor.Callback> cbCaptor =
                ArgumentCaptor.forClass(BaseClientMonitor.Callback.class);
        verify(mClientMonitor).cancelWithoutStarting(cbCaptor.capture());

        cbCaptor.getValue().onClientFinished(mClientMonitor, true);
        verify(cancelCb).onClientFinished(eq(mClientMonitor), eq(true));
        verify(mClientMonitor, never()).start(any());
        verify(mClientMonitor, never()).cancel();
        verify(mClientMonitor).destroy();
    }

    @Test
    public void markCanceling() {
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mOperation.markCanceling();

        assertThat(mOperation.isMarkedCanceling()).isTrue();
        assertThat(mOperation.isCanceling()).isFalse();
        assertThat(mOperation.isFinished()).isFalse();
        verify(mClientMonitor, never()).start(any());
        verify(mClientMonitor, never()).cancel();
        verify(mClientMonitor, never()).cancelWithoutStarting(any());
        verify(mClientMonitor, never()).unableToStart();
        verify(mClientMonitor, never()).destroy();
    }

    @Test
    public void cancelPendingWithCookie() {
        markCancellingAndStart(2);
    }

    @Test
    public void cancelPendingWithoutCookie() {
        markCancellingAndStart(null);
    }

    private void markCancellingAndStart(Integer withCookie) {
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);
        if (withCookie != null) {
            when(mClientMonitor.getCookie()).thenReturn(withCookie);
        }

        mOperation.markCanceling();
        final BaseClientMonitor.Callback cb = mock(BaseClientMonitor.Callback.class);
        if (withCookie != null) {
            mOperation.startWithCookie(cb, withCookie);
        } else {
            mOperation.start(cb);
        }

        assertThat(mOperation.isFinished()).isTrue();
        verify(cb).onClientFinished(eq(mClientMonitor), eq(true));
        verify(mClientMonitor, never()).start(any());
        verify(mClientMonitor, never()).cancel();
        verify(mClientMonitor, never()).cancelWithoutStarting(any());
        verify(mClientMonitor, never()).unableToStart();
        verify(mClientMonitor).destroy();
    }

    @Test
    public void cancelWatchdogWhenStarted() {
        cancelWatchdog(true);
    }

    @Test
    public void cancelWatchdogWithoutStarting() {
        cancelWatchdog(false);
    }

    private void cancelWatchdog(boolean start) {
        when(mClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mOperation.start(mock(BaseClientMonitor.Callback.class));
        if (start) {
            verify(mClientMonitor).start(mStartCallback.capture());
            mStartCallback.getValue().onClientStarted(mClientMonitor);
        }
        mOperation.cancel(mHandler, mock(BaseClientMonitor.Callback.class));

        assertThat(mOperation.isCanceling()).isTrue();

        // omit call to onClientFinished and trigger watchdog
        mOperation.mCancelWatchdog.run();

        assertThat(mOperation.isFinished()).isTrue();
        assertThat(mOperation.isCanceling()).isFalse();
        verify(mClientMonitor).destroy();
    }
}
