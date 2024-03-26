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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.hardware.biometrics.BiometricConstants;
import android.os.Handler;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class BiometricSchedulerOperationTest {

    public interface FakeHal {}
    public abstract static class InterruptableMonitor<T>
            extends HalClientMonitor<T> {
        public InterruptableMonitor() {
            super(null, null, null, null, 0, null, 0, 0,
                    mock(BiometricLogger.class), mock(BiometricContext.class));
        }
    }

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private InterruptableMonitor<FakeHal> mInterruptableClientMonitor;
    @Mock
    private BaseClientMonitor mNonInterruptableClientMonitor;
    @Mock
    private ClientMonitorCallbackConverter mListener;
    @Mock
    private ClientMonitorCallback mClientCallback;
    @Mock
    private ClientMonitorCallback mOnStartCallback;
    @Mock
    private FakeHal mHal;
    @Captor
    ArgumentCaptor<ClientMonitorCallback> mStartedCallbackCaptor;

    private Handler mHandler;
    private BiometricSchedulerOperation mInterruptableOperation;
    private BiometricSchedulerOperation mNonInterruptableOperation;
    private boolean mIsDebuggable;

    @Before
    public void setUp() {
        mHandler = new Handler(TestableLooper.get(this).getLooper());
        mIsDebuggable = false;
        mInterruptableOperation = new BiometricSchedulerOperation(mInterruptableClientMonitor,
                mClientCallback, () -> mIsDebuggable);
        mNonInterruptableOperation = new BiometricSchedulerOperation(mNonInterruptableClientMonitor,
                mClientCallback, () -> mIsDebuggable);

        when(mInterruptableClientMonitor.isInterruptable()).thenReturn(true);
        when(mNonInterruptableClientMonitor.isInterruptable()).thenReturn(false);
    }

    @Test
    public void testStartWithCookie() {
        final int cookie = 200;
        when(mInterruptableClientMonitor.getCookie()).thenReturn(cookie);
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        assertThat(mInterruptableOperation.isReadyToStart(mOnStartCallback)).isEqualTo(cookie);
        assertThat(mInterruptableOperation.isStarted()).isFalse();
        assertThat(mInterruptableOperation.isCanceling()).isFalse();
        assertThat(mInterruptableOperation.isFinished()).isFalse();
        verify(mInterruptableClientMonitor).waitForCookie(any());

        final boolean started = mInterruptableOperation.startWithCookie(mOnStartCallback, cookie);

        assertThat(started).isTrue();
        verify(mInterruptableClientMonitor).start(mStartedCallbackCaptor.capture());
        mStartedCallbackCaptor.getValue().onClientStarted(mInterruptableClientMonitor);
        assertThat(mInterruptableOperation.isStarted()).isTrue();
    }

    @Test
    public void testNoStartWithoutCookie() {
        final int goodCookie = 20;
        final int badCookie = 22;
        when(mInterruptableClientMonitor.getCookie()).thenReturn(goodCookie);
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        assertThat(mInterruptableOperation.isReadyToStart(mOnStartCallback)).isEqualTo(goodCookie);
        final boolean started = mInterruptableOperation.startWithCookie(mOnStartCallback,
                badCookie);

        assertThat(started).isFalse();
        assertThat(mInterruptableOperation.isStarted()).isFalse();
        assertThat(mInterruptableOperation.isCanceling()).isFalse();
        assertThat(mInterruptableOperation.isFinished()).isFalse();
        verify(mInterruptableClientMonitor).waitForCookie(any());
        verify(mInterruptableClientMonitor, never()).start(any());
    }

    @Test
    public void testSecondStartWithCookieCrashesWhenDebuggable() {
        final int cookie = 5;
        mIsDebuggable = true;
        when(mInterruptableClientMonitor.getCookie()).thenReturn(cookie);
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final boolean started = mInterruptableOperation.startWithCookie(mOnStartCallback, cookie);
        assertThat(started).isTrue();

        assertThrows(IllegalStateException.class,
                () -> mInterruptableOperation.startWithCookie(mOnStartCallback, cookie));
    }

    @Test
    public void testSecondStartWithCookieFailsNicelyWhenNotDebuggable() {
        final int cookie = 5;
        mIsDebuggable = false;
        when(mInterruptableClientMonitor.getCookie()).thenReturn(cookie);
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final boolean started = mInterruptableOperation.startWithCookie(mOnStartCallback, cookie);
        assertThat(started).isTrue();

        final boolean startedAgain = mInterruptableOperation.startWithCookie(mOnStartCallback,
                cookie);
        assertThat(startedAgain).isFalse();
    }

    @Test
    public void startsWhenReadyAndHalAvailable() {
        when(mInterruptableClientMonitor.getCookie()).thenReturn(0);
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mInterruptableOperation.start(mOnStartCallback);
        verify(mInterruptableClientMonitor).start(mStartedCallbackCaptor.capture());
        mStartedCallbackCaptor.getValue().onClientStarted(mInterruptableClientMonitor);

        assertThat(mInterruptableOperation.isStarted()).isTrue();
        assertThat(mInterruptableOperation.isCanceling()).isFalse();
        assertThat(mInterruptableOperation.isFinished()).isFalse();

        verify(mClientCallback).onClientStarted(eq(mInterruptableClientMonitor));
        verify(mOnStartCallback).onClientStarted(eq(mInterruptableClientMonitor));
        verify(mClientCallback, never()).onClientFinished(any(), anyBoolean());
        verify(mOnStartCallback, never()).onClientFinished(any(), anyBoolean());

        mStartedCallbackCaptor.getValue().onClientFinished(mInterruptableClientMonitor,
                true);

        assertThat(mInterruptableOperation.isFinished()).isTrue();
        assertThat(mInterruptableOperation.isCanceling()).isFalse();
        verify(mInterruptableClientMonitor).destroy();
        verify(mOnStartCallback).onClientFinished(eq(mInterruptableClientMonitor), eq(true));
    }

    @Test
    public void startFailsWhenReadyButHalNotAvailable() {
        when(mInterruptableClientMonitor.getCookie()).thenReturn(0);
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(null);

        mInterruptableOperation.start(mOnStartCallback);
        verify(mInterruptableClientMonitor, never()).start(any());

        assertThat(mInterruptableOperation.isStarted()).isFalse();
        assertThat(mInterruptableOperation.isCanceling()).isFalse();
        assertThat(mInterruptableOperation.isFinished()).isTrue();

        verify(mClientCallback, never()).onClientStarted(eq(mInterruptableClientMonitor));
        verify(mOnStartCallback, never()).onClientStarted(eq(mInterruptableClientMonitor));
        verify(mClientCallback).onClientFinished(eq(mInterruptableClientMonitor), eq(false));
        verify(mOnStartCallback).onClientFinished(eq(mInterruptableClientMonitor), eq(false));
    }

    @Test
    public void secondStartCrashesWhenDebuggable() {
        mIsDebuggable = true;
        when(mInterruptableClientMonitor.getCookie()).thenReturn(0);
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final boolean started = mInterruptableOperation.start(mOnStartCallback);
        assertThat(started).isTrue();

        assertThrows(IllegalStateException.class, () -> mInterruptableOperation.start(
                mOnStartCallback));
    }

    @Test
    public void secondStartFailsNicelyWhenNotDebuggable() {
        mIsDebuggable = false;
        when(mInterruptableClientMonitor.getCookie()).thenReturn(0);
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final boolean started = mInterruptableOperation.start(mOnStartCallback);
        assertThat(started).isTrue();

        final boolean startedAgain = mInterruptableOperation.start(mOnStartCallback);
        assertThat(startedAgain).isFalse();
    }

    @Test
    public void doesNotStartWithCookie() {
        // This class only throws exceptions when debuggable.
        mIsDebuggable = true;
        when(mInterruptableClientMonitor.getCookie()).thenReturn(9);
        assertThrows(IllegalStateException.class,
                () -> mInterruptableOperation.start(mock(ClientMonitorCallback.class)));
    }

    @Test
    public void cannotRestart() {
        // This class only throws exceptions when debuggable.
        mIsDebuggable = true;
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mInterruptableOperation.start(mOnStartCallback);

        assertThrows(IllegalStateException.class,
                () -> mInterruptableOperation.start(mock(ClientMonitorCallback.class)));
    }

    @Test
    public void abortsNotRunning() {
        // This class only throws exceptions when debuggable.
        mIsDebuggable = true;
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mInterruptableOperation.abort();

        assertThat(mInterruptableOperation.isFinished()).isTrue();
        verify(mInterruptableClientMonitor).unableToStart();
        verify(mInterruptableClientMonitor).destroy();
        assertThrows(IllegalStateException.class,
                () -> mInterruptableOperation.start(mock(ClientMonitorCallback.class)));
    }

    @Test
    public void abortCrashesWhenDebuggableIfOperationIsRunning() {
        mIsDebuggable = true;
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mInterruptableOperation.start(mOnStartCallback);

        assertThrows(IllegalStateException.class, () -> mInterruptableOperation.abort());
    }

    @Test
    public void abortFailsNicelyWhenNotDebuggableIfOperationIsRunning() {
        mIsDebuggable = false;
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mInterruptableOperation.start(mOnStartCallback);

        mInterruptableOperation.abort();
    }

    @Test
    public void cancel() {
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final ClientMonitorCallback cancelCb = mock(ClientMonitorCallback.class);
        mInterruptableOperation.start(mOnStartCallback);
        verify(mInterruptableClientMonitor).start(mStartedCallbackCaptor.capture());
        mStartedCallbackCaptor.getValue().onClientStarted(mInterruptableClientMonitor);
        mInterruptableOperation.cancel(mHandler, cancelCb);

        assertThat(mInterruptableOperation.isCanceling()).isTrue();
        verify(mInterruptableClientMonitor).cancel();
        verify(mInterruptableClientMonitor, never()).destroy();

        mStartedCallbackCaptor.getValue().onClientFinished(mInterruptableClientMonitor,
                true);

        assertThat(mInterruptableOperation.isFinished()).isTrue();
        assertThat(mInterruptableOperation.isCanceling()).isFalse();
        verify(mInterruptableClientMonitor).destroy();

        // should be unused since the operation was started
        verify(cancelCb, never()).onClientStarted(any());
        verify(cancelCb, never()).onClientFinished(any(), anyBoolean());
    }

    @Test
    public void cancelWithoutStarting() {
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        final ClientMonitorCallback cancelCb = mock(ClientMonitorCallback.class);
        mInterruptableOperation.cancel(mHandler, cancelCb);

        assertThat(mInterruptableOperation.isCanceling()).isTrue();
        ArgumentCaptor<ClientMonitorCallback> cbCaptor =
                ArgumentCaptor.forClass(ClientMonitorCallback.class);
        verify(mInterruptableClientMonitor).cancelWithoutStarting(cbCaptor.capture());

        cbCaptor.getValue().onClientFinished(mInterruptableClientMonitor, true);
        verify(cancelCb).onClientFinished(eq(mInterruptableClientMonitor), eq(true));
        verify(mInterruptableClientMonitor, never()).start(any());
        verify(mInterruptableClientMonitor, never()).cancel();
        verify(mInterruptableClientMonitor).destroy();
    }

    @Test
    public void cancelCrashesWhenDebuggableIfOperationIsFinished() {
        mIsDebuggable = true;
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mInterruptableOperation.abort();
        assertThat(mInterruptableOperation.isFinished()).isTrue();

        final ClientMonitorCallback cancelCb = mock(ClientMonitorCallback.class);
        assertThrows(IllegalStateException.class, () -> mInterruptableOperation.cancel(mHandler,
                cancelCb));
    }

    @Test
    public void cancelFailsNicelyWhenNotDebuggableIfOperationIsFinished() {
        mIsDebuggable = false;
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mInterruptableOperation.abort();
        assertThat(mInterruptableOperation.isFinished()).isTrue();

        final ClientMonitorCallback cancelCb = mock(ClientMonitorCallback.class);
        mInterruptableOperation.cancel(mHandler, cancelCb);
    }

    @Test
    public void markCanceling_interruptableClient() {
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);

        mInterruptableOperation.markCanceling();

        assertThat(mInterruptableOperation.isMarkedCanceling()).isTrue();
        assertThat(mInterruptableOperation.isCanceling()).isFalse();
        assertThat(mInterruptableOperation.isFinished()).isFalse();
        verify(mInterruptableClientMonitor, never()).start(any());
        verify(mInterruptableClientMonitor, never()).cancel();
        verify(mInterruptableClientMonitor, never()).cancelWithoutStarting(any());
        verify(mInterruptableClientMonitor, never()).unableToStart();
        verify(mInterruptableClientMonitor, never()).destroy();
    }

    @Test
    public void markCanceling_nonInterruptableClient() {
        mNonInterruptableOperation.markCanceling();

        assertThat(mNonInterruptableOperation.isMarkedCanceling()).isFalse();
        assertThat(mNonInterruptableOperation.isCanceling()).isFalse();
        assertThat(mNonInterruptableOperation.isFinished()).isFalse();
        verify(mNonInterruptableClientMonitor, never()).start(any());
        verify(mNonInterruptableClientMonitor, never()).cancel();
        verify(mNonInterruptableClientMonitor, never()).cancelWithoutStarting(any());
        verify(mNonInterruptableClientMonitor, never()).destroy();
    }

    @Test
    public void markCancelingForWatchdog() {
        mNonInterruptableOperation.markCancelingForWatchdog();
        mInterruptableOperation.markCancelingForWatchdog();

        assertThat(mInterruptableOperation.isMarkedCanceling()).isTrue();
        assertThat(mNonInterruptableOperation.isMarkedCanceling()).isTrue();
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
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);
        if (withCookie != null) {
            when(mInterruptableClientMonitor.getCookie()).thenReturn(withCookie);
        }

        mInterruptableOperation.markCanceling();
        final ClientMonitorCallback cb = mock(ClientMonitorCallback.class);
        if (withCookie != null) {
            mInterruptableOperation.startWithCookie(cb, withCookie);
        } else {
            mInterruptableOperation.start(cb);
        }

        assertThat(mInterruptableOperation.isFinished()).isTrue();
        verify(cb).onClientFinished(eq(mInterruptableClientMonitor), eq(true));
        verify(mInterruptableClientMonitor, never()).start(any());
        verify(mInterruptableClientMonitor, never()).cancel();
        verify(mInterruptableClientMonitor, never()).cancelWithoutStarting(any());
        verify(mInterruptableClientMonitor, never()).unableToStart();
        verify(mInterruptableClientMonitor).destroy();
    }

    @Test
    public void cancelWatchdogWhenStarted() throws RemoteException {
        cancelWatchdog(true);
    }

    @Test
    public void cancelWatchdogWithoutStarting() throws RemoteException {
        cancelWatchdog(false);
    }

    private void cancelWatchdog(boolean start) throws RemoteException {
        when(mInterruptableClientMonitor.getFreshDaemon()).thenReturn(mHal);
        when(mInterruptableClientMonitor.getListener()).thenReturn(mListener);

        mInterruptableOperation.start(mOnStartCallback);
        if (start) {
            verify(mInterruptableClientMonitor).start(mStartedCallbackCaptor.capture());
            mStartedCallbackCaptor.getValue().onClientStarted(mInterruptableClientMonitor);
        }
        mInterruptableOperation.cancel(mHandler, mock(ClientMonitorCallback.class));

        assertThat(mInterruptableOperation.isCanceling()).isTrue();

        // omit call to onClientFinished and trigger watchdog
        mInterruptableOperation.mCancelWatchdog.run();

        assertThat(mInterruptableOperation.isFinished()).isTrue();
        assertThat(mInterruptableOperation.isCanceling()).isFalse();
        verify(mInterruptableClientMonitor.getListener()).onError(anyInt(), anyInt(), eq(
                BiometricConstants.BIOMETRIC_ERROR_CANCELED), eq(0));
        verify(mOnStartCallback).onClientFinished(eq(mInterruptableClientMonitor), eq(false));
        verify(mInterruptableClientMonitor).destroy();
    }
}
