/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import android.os.RemoteException;
import android.uwb.UwbManager.AdapterStateCallback;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Test of {@link AdapterStateListener}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdapterStateListenerTest {

    IUwbAdapter mUwbAdapter = mock(IUwbAdapter.class);

    Answer mRegisterSuccessAnswer = new Answer() {
        public Object answer(InvocationOnMock invocation) {
            Object[] args = invocation.getArguments();
            IUwbAdapterStateCallbacks cb = (IUwbAdapterStateCallbacks) args[0];
            try {
                cb.onAdapterStateChanged(AdapterState.STATE_DISABLED, StateChangeReason.UNKNOWN);
            } catch (RemoteException e) {
                // Nothing to do
            }
            return new Object();
        }
    };

    Throwable mThrowRemoteException = new RemoteException("RemoteException");

    private static Executor getExecutor() {
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    private static void verifyCallbackStateChangedInvoked(
            AdapterStateCallback callback, int numTimes) {
        verify(callback, times(numTimes)).onStateChanged(anyInt(), anyInt());
    }

    @Test
    public void testRegister_RegisterUnregister() throws RemoteException {
        doAnswer(mRegisterSuccessAnswer).when(mUwbAdapter).registerAdapterStateCallbacks(any());

        AdapterStateListener adapterStateListener = new AdapterStateListener(mUwbAdapter);
        AdapterStateCallback callback1 = mock(AdapterStateCallback.class);
        AdapterStateCallback callback2 = mock(AdapterStateCallback.class);

        // Verify that the adapter state listener registered with the UWB Adapter
        adapterStateListener.register(getExecutor(), callback1);
        verify(mUwbAdapter, times(1)).registerAdapterStateCallbacks(any());
        verifyCallbackStateChangedInvoked(callback1, 1);
        verifyCallbackStateChangedInvoked(callback2, 0);

        // Register a second client and no new call to UWB Adapter
        adapterStateListener.register(getExecutor(), callback2);
        verify(mUwbAdapter, times(1)).registerAdapterStateCallbacks(any());
        verifyCallbackStateChangedInvoked(callback1, 1);
        verifyCallbackStateChangedInvoked(callback2, 1);

        // Unregister first callback
        adapterStateListener.unregister(callback1);
        verify(mUwbAdapter, times(1)).registerAdapterStateCallbacks(any());
        verify(mUwbAdapter, times(0)).unregisterAdapterStateCallbacks(any());
        verifyCallbackStateChangedInvoked(callback1, 1);
        verifyCallbackStateChangedInvoked(callback2, 1);

        // Unregister second callback
        adapterStateListener.unregister(callback2);
        verify(mUwbAdapter, times(1)).registerAdapterStateCallbacks(any());
        verify(mUwbAdapter, times(1)).unregisterAdapterStateCallbacks(any());
        verifyCallbackStateChangedInvoked(callback1, 1);
        verifyCallbackStateChangedInvoked(callback2, 1);
    }

    @Test
    public void testRegister_RegisterSameCallbackTwice() throws RemoteException {
        AdapterStateListener adapterStateListener = new AdapterStateListener(mUwbAdapter);
        AdapterStateCallback callback = mock(AdapterStateCallback.class);
        doAnswer(mRegisterSuccessAnswer).when(mUwbAdapter).registerAdapterStateCallbacks(any());

        adapterStateListener.register(getExecutor(), callback);
        verifyCallbackStateChangedInvoked(callback, 1);

        adapterStateListener.register(getExecutor(), callback);
        verifyCallbackStateChangedInvoked(callback, 1);

        // Invoke a state change and ensure the callback is only called once
        adapterStateListener.onAdapterStateChanged(AdapterState.STATE_DISABLED,
                StateChangeReason.UNKNOWN);
        verifyCallbackStateChangedInvoked(callback, 2);
    }

    @Test
    public void testCallback_RunViaExecutor_Success() throws RemoteException {
        // Verify that the callbacks are invoked on the executor when successful
        doAnswer(mRegisterSuccessAnswer).when(mUwbAdapter).registerAdapterStateCallbacks(any());
        runViaExecutor();
    }

    private void runViaExecutor() {
        AdapterStateListener adapterStateListener = new AdapterStateListener(mUwbAdapter);
        AdapterStateCallback callback = mock(AdapterStateCallback.class);

        Executor executor = mock(Executor.class);

        // Do not run commands received and ensure that the callback is not invoked
        doAnswer(new ExecutorAnswer(false)).when(executor).execute(any());
        adapterStateListener.register(executor, callback);
        verify(executor, times(1)).execute(any());
        verifyCallbackStateChangedInvoked(callback, 0);

        // Manually invoke the callback and ensure callback is not invoked
        adapterStateListener.onAdapterStateChanged(AdapterState.STATE_DISABLED,
                StateChangeReason.UNKNOWN);
        verify(executor, times(2)).execute(any());
        verifyCallbackStateChangedInvoked(callback, 0);

        // Run the command that the executor receives
        doAnswer(new ExecutorAnswer(true)).when(executor).execute(any());
        adapterStateListener.onAdapterStateChanged(AdapterState.STATE_DISABLED,
                StateChangeReason.UNKNOWN);
        verify(executor, times(3)).execute(any());
        verifyCallbackStateChangedInvoked(callback, 1);
    }

    class ExecutorAnswer implements Answer {

        final boolean mShouldRun;
        ExecutorAnswer(boolean shouldRun) {
            mShouldRun = shouldRun;
        }

        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            if (mShouldRun) {
                ((Runnable) invocation.getArgument(0)).run();
            }
            return null;
        }
    }

    @Test
    public void testNotify_AllCallbacksNotified() throws RemoteException {
        doAnswer(mRegisterSuccessAnswer).when(mUwbAdapter).registerAdapterStateCallbacks(any());

        AdapterStateListener adapterStateListener = new AdapterStateListener(mUwbAdapter);
        List<AdapterStateCallback> callbacks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            AdapterStateCallback callback = mock(AdapterStateCallback.class);
            adapterStateListener.register(getExecutor(), callback);
            callbacks.add(callback);
        }

            // Ensure every callback got the initial state
        for (AdapterStateCallback callback : callbacks) {
            verifyCallbackStateChangedInvoked(callback, 1);
        }

        // Invoke a state change and ensure all callbacks are invoked
        adapterStateListener.onAdapterStateChanged(AdapterState.STATE_DISABLED,
                StateChangeReason.ALL_SESSIONS_CLOSED);
        for (AdapterStateCallback callback : callbacks) {
            verifyCallbackStateChangedInvoked(callback, 2);
        }
    }

    @Test
    public void testStateChange_CorrectValue() {
        AdapterStateListener adapterStateListener = new AdapterStateListener(mUwbAdapter);

        AdapterStateCallback callback = mock(AdapterStateCallback.class);

        adapterStateListener.register(getExecutor(), callback);

        runStateChangeValue(StateChangeReason.ALL_SESSIONS_CLOSED,
                AdapterState.STATE_ENABLED_INACTIVE,
                AdapterStateCallback.STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED,
                AdapterStateCallback.STATE_ENABLED_INACTIVE);

        runStateChangeValue(StateChangeReason.SESSION_STARTED, AdapterState.STATE_ENABLED_ACTIVE,
                AdapterStateCallback.STATE_CHANGED_REASON_SESSION_STARTED,
                AdapterStateCallback.STATE_ENABLED_ACTIVE);

        runStateChangeValue(StateChangeReason.SYSTEM_BOOT, AdapterState.STATE_DISABLED,
                AdapterStateCallback.STATE_CHANGED_REASON_SYSTEM_BOOT,
                AdapterStateCallback.STATE_DISABLED);

        runStateChangeValue(StateChangeReason.SYSTEM_POLICY, AdapterState.STATE_DISABLED,
                AdapterStateCallback.STATE_CHANGED_REASON_SYSTEM_POLICY,
                AdapterStateCallback.STATE_DISABLED);

        runStateChangeValue(StateChangeReason.UNKNOWN, AdapterState.STATE_DISABLED,
                AdapterStateCallback.STATE_CHANGED_REASON_ERROR_UNKNOWN,
                AdapterStateCallback.STATE_DISABLED);
    }

    private void runStateChangeValue(@StateChangeReason int reasonIn, @AdapterState int stateIn,
            @AdapterStateCallback.StateChangedReason int reasonOut,
            @AdapterStateCallback.State int stateOut) {
        AdapterStateListener adapterStateListener = new AdapterStateListener(mUwbAdapter);
        AdapterStateCallback callback = mock(AdapterStateCallback.class);
        adapterStateListener.register(getExecutor(), callback);

        adapterStateListener.onAdapterStateChanged(stateIn, reasonIn);
        verify(callback, times(1)).onStateChanged(stateOut, reasonOut);
    }

    @Test
    public void testStateChange_FirstRegisterGetsCorrectState() throws RemoteException {
        AdapterStateListener adapterStateListener = new AdapterStateListener(mUwbAdapter);
        AdapterStateCallback callback = mock(AdapterStateCallback.class);

        Answer registerAnswer = new Answer() {
            public Object answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                IUwbAdapterStateCallbacks cb = (IUwbAdapterStateCallbacks) args[0];
                try {
                    cb.onAdapterStateChanged(AdapterState.STATE_ENABLED_ACTIVE,
                            StateChangeReason.SESSION_STARTED);
                } catch (RemoteException e) {
                    // Nothing to do
                }
                return new Object();
            }
        };

        doAnswer(registerAnswer).when(mUwbAdapter).registerAdapterStateCallbacks(any());

        adapterStateListener.register(getExecutor(), callback);
        verify(callback).onStateChanged(AdapterStateCallback.STATE_ENABLED_ACTIVE,
                AdapterStateCallback.STATE_CHANGED_REASON_SESSION_STARTED);
    }

    @Test
    public void testStateChange_SecondRegisterGetsCorrectState() {
        AdapterStateListener adapterStateListener = new AdapterStateListener(mUwbAdapter);
        AdapterStateCallback callback1 = mock(AdapterStateCallback.class);
        AdapterStateCallback callback2 = mock(AdapterStateCallback.class);

        adapterStateListener.register(getExecutor(), callback1);
        adapterStateListener.onAdapterStateChanged(AdapterState.STATE_ENABLED_ACTIVE,
                StateChangeReason.SYSTEM_BOOT);

        adapterStateListener.register(getExecutor(), callback2);
        verify(callback2).onStateChanged(AdapterStateCallback.STATE_ENABLED_ACTIVE,
                AdapterStateCallback.STATE_CHANGED_REASON_SYSTEM_BOOT);
    }
}
