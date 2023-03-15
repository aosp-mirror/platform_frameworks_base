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

package com.android.internal.util;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.test.filters.SmallTest;

import com.android.internal.util.ObservableServiceConnection.ServiceTransformer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;

@SmallTest
public class ObservableServiceConnectionTest {
    private static final ComponentName COMPONENT_NAME =
            new ComponentName("test.package", "component");

    public static class Foo {
        int mValue;

        Foo(int value) {
            mValue = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Foo)) return false;
            Foo foo = (Foo) o;
            return mValue == foo.mValue;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mValue);
        }
    }


    @Mock
    private Context mContext;
    @Mock
    private Intent mIntent;
    @Mock
    private Foo mResult;
    @Mock
    private IBinder mBinder;
    @Mock
    private ServiceTransformer<Foo> mTransformer;
    @Mock
    private ObservableServiceConnection.Callback<Foo> mCallback;
    private final FakeExecutor mExecutor = new FakeExecutor();
    private ObservableServiceConnection<Foo> mConnection;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mConnection = new ObservableServiceConnection<>(
                mContext,
                mExecutor,
                mTransformer,
                mIntent,
                /* flags= */ Context.BIND_AUTO_CREATE);
    }

    @After
    public void tearDown() {
        mExecutor.clearAll();
    }

    @Test
    public void testConnect() {
        // Register twice to ensure only one callback occurs.
        mConnection.addCallback(mCallback);
        mConnection.addCallback(mCallback);

        mExecutor.runAll();
        mConnection.bind();

        // Ensure that no callbacks happen before connection.
        verify(mCallback, never()).onConnected(any(), any());
        verify(mCallback, never()).onDisconnected(any(), anyInt());

        when(mTransformer.convert(mBinder)).thenReturn(mResult);
        mConnection.onServiceConnected(COMPONENT_NAME, mBinder);

        mExecutor.runAll();
        verify(mCallback, times(1)).onConnected(mConnection, mResult);
    }

    @Test
    public void testDisconnectBeforeBind() {
        mConnection.addCallback(mCallback);
        mExecutor.runAll();
        mConnection.onServiceDisconnected(COMPONENT_NAME);
        mExecutor.runAll();
        // Disconnects before binds should be ignored.
        verify(mCallback, never()).onDisconnected(eq(mConnection), anyInt());
    }

    @Test
    public void testDisconnect() {
        mConnection.addCallback(mCallback);
        mExecutor.runAll();
        mConnection.bind();
        mConnection.onServiceDisconnected(COMPONENT_NAME);

        // Ensure the callback doesn't get triggered until the executor runs.
        verify(mCallback, never()).onDisconnected(eq(mConnection), anyInt());
        mExecutor.runAll();
        // Ensure proper disconnect reason reported.
        verify(mCallback, times(1)).onDisconnected(mConnection,
                ObservableServiceConnection.DISCONNECT_REASON_DISCONNECTED);
        // Verify unbound from service.
        verify(mContext, times(1)).unbindService(mConnection);

        clearInvocations(mContext);
        // Ensure unbind after disconnect has no effect on the connection
        mConnection.unbind();
        verify(mContext, never()).unbindService(mConnection);
    }

    @Test
    public void testBindingDied() {
        mConnection.addCallback(mCallback);
        mExecutor.runAll();
        mConnection.bind();
        mConnection.onBindingDied(COMPONENT_NAME);

        // Ensure the callback doesn't get triggered until the executor runs.
        verify(mCallback, never()).onDisconnected(eq(mConnection), anyInt());
        mExecutor.runAll();
        // Ensure proper disconnect reason reported.
        verify(mCallback, times(1)).onDisconnected(mConnection,
                ObservableServiceConnection.DISCONNECT_REASON_BINDING_DIED);
        // Verify unbound from service.
        verify(mContext, times(1)).unbindService(mConnection);
    }

    @Test
    public void testNullBinding() {
        mConnection.addCallback(mCallback);
        mExecutor.runAll();
        mConnection.bind();
        mConnection.onNullBinding(COMPONENT_NAME);

        // Ensure the callback doesn't get triggered until the executor runs.
        verify(mCallback, never()).onDisconnected(eq(mConnection), anyInt());
        mExecutor.runAll();
        // Ensure proper disconnect reason reported.
        verify(mCallback, times(1)).onDisconnected(mConnection,
                ObservableServiceConnection.DISCONNECT_REASON_NULL_BINDING);
        // Verify unbound from service.
        verify(mContext, times(1)).unbindService(mConnection);
    }

    @Test
    public void testUnbind() {
        mConnection.addCallback(mCallback);
        mExecutor.runAll();
        mConnection.bind();
        mConnection.unbind();

        // Ensure the callback doesn't get triggered until the executor runs.
        verify(mCallback, never()).onDisconnected(eq(mConnection), anyInt());
        mExecutor.runAll();
        verify(mCallback).onDisconnected(mConnection,
                ObservableServiceConnection.DISCONNECT_REASON_UNBIND);
    }

    static class FakeExecutor implements Executor {
        private final Queue<Runnable> mQueue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            mQueue.add(command);
        }

        public void runAll() {
            while (!mQueue.isEmpty()) {
                mQueue.remove().run();
            }
        }

        public void clearAll() {
            while (!mQueue.isEmpty()) {
                mQueue.remove();
            }
        }
    }
}
