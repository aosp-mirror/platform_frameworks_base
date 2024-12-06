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

package com.android.systemui.util.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.FakeUserTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Objects;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ObservableServiceConnectionTest extends SysuiTestCase {
    static class Foo {
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
    Context mContext;

    @Mock
    Intent mIntent;

    @Mock
    Foo mResult;

    @Mock
    ComponentName mComponentName;

    @Mock
    IBinder mBinder;

    @Mock
    ObservableServiceConnection.ServiceTransformer<Foo> mTransformer;

    @Mock
    ObservableServiceConnection.Callback<Foo> mCallback;

    FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    private FakeUserTracker mUserTracker;

    private static final int MAIN_USER_ID = 10;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUserTracker = new FakeUserTracker();
        // Set the main user as the current user.
        mUserTracker.set(List.of(new UserInfo(MAIN_USER_ID, "main", UserInfo.FLAG_MAIN)), 0);
    }

    @Test
    public void testConnect() {
        ObservableServiceConnection<Foo> connection = new ObservableServiceConnection<>(mContext,
                mIntent, mUserTracker, mExecutor, mTransformer);
        // Register twice to ensure only one callback occurs.
        connection.addCallback(mCallback);
        connection.addCallback(mCallback);
        mExecutor.runAllReady();
        connection.bind();
        mExecutor.runAllReady();

        when(mTransformer.convert(eq(mBinder))).thenReturn(mResult);

        connection.onServiceConnected(mComponentName, mBinder);

        mExecutor.runAllReady();

        // Ensure callback is informed of the translated result.
        verify(mCallback, times(1)).onConnected(eq(connection), eq(mResult));
    }

    @Test
    public void testDisconnect() {
        ObservableServiceConnection<Foo> connection = new ObservableServiceConnection<>(mContext,
                mIntent, mUserTracker, mExecutor, mTransformer);
        connection.addCallback(mCallback);
        connection.onServiceDisconnected(mComponentName);
        mExecutor.runAllReady();

        // Disconnects before binds should be ignored.
        verify(mCallback, never()).onDisconnected(eq(connection), anyInt());

        when(mContext.bindServiceAsUser(eq(mIntent), eq(connection), anyInt(),
                eq(UserHandle.of(MAIN_USER_ID)))).thenReturn(true);
        connection.bind();
        mExecutor.runAllReady();
        connection.onServiceDisconnected(mComponentName);
        mExecutor.runAllReady();

        // Ensure proper disconnect reason reported back
        verify(mCallback).onDisconnected(eq(connection),
                eq(ObservableServiceConnection.DISCONNECT_REASON_DISCONNECTED));

        // Verify unbound from service.
        verify(mContext, times(1)).unbindService(eq(connection));

        clearInvocations(mContext);
        // Ensure unbind after disconnect has no effect on the connection
        connection.unbind();
        mExecutor.runAllReady();
        verify(mContext, never()).unbindService(eq(connection));
    }

    @Test
    public void testUnbind() {
        ObservableServiceConnection<Foo> connection = new ObservableServiceConnection<>(mContext,
                mIntent, mUserTracker, mExecutor, mTransformer);
        connection.addCallback(mCallback);
        connection.onServiceDisconnected(mComponentName);
        mExecutor.runAllReady();

        // Disconnects before binds should be ignored.
        verify(mCallback, never()).onDisconnected(eq(connection), anyInt());

        when(mContext.bindServiceAsUser(eq(mIntent), eq(connection), anyInt(),
                eq(UserHandle.of(MAIN_USER_ID)))).thenReturn(true);
        connection.bind();

        mExecutor.runAllReady();

        connection.unbind();

        mExecutor.runAllReady();

        verify(mCallback).onDisconnected(eq(connection),
                eq(ObservableServiceConnection.DISCONNECT_REASON_UNBIND));
    }

    @Test
    public void testBindServiceThrowsError() {
        ObservableServiceConnection<Foo> connection = new ObservableServiceConnection<>(mContext,
                mIntent, mUserTracker, mExecutor, mTransformer);
        connection.addCallback(mCallback);

        when(mContext.bindServiceAsUser(eq(mIntent), eq(connection), anyInt(),
                eq(UserHandle.of(MAIN_USER_ID))))
                .thenThrow(new SecurityException());

        // Verify that the exception was caught and that bind returns false, and we properly
        // unbind.
        connection.bind();
        mExecutor.runAllReady();
        verify(mContext).unbindService(connection);
    }

    @Test
    public void testUnbindDoesNotCallUnbindServiceWhenBindThrowsError() {
        ObservableServiceConnection<Foo> connection = new ObservableServiceConnection<>(mContext,
                mIntent, mUserTracker, mExecutor, mTransformer);
        connection.addCallback(mCallback);

        when(mContext.bindServiceAsUser(eq(mIntent), eq(connection), anyInt(),
                eq(UserHandle.of(MAIN_USER_ID))))
                .thenThrow(new SecurityException());

        // Verify that bind returns false and we properly unbind.
        connection.bind();
        mExecutor.runAllReady();
        verify(mContext).unbindService(connection);

        clearInvocations(mContext);

        // Ensure unbind after the failed bind has no effect.
        connection.unbind();
        mExecutor.runAllReady();
        verify(mContext, never()).unbindService(eq(connection));
    }
}
