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
package com.android.server.inputmethod;

import static com.android.server.inputmethod.ClientController.ClientControllerCallback;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.view.Display;
import android.view.inputmethod.InputBinding;

import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteInputConnection;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// This test is designed to run on both device and host (Ravenwood) side.
public final class ClientControllerTest {
    private static final int ANY_DISPLAY_ID = Display.DEFAULT_DISPLAY;
    private static final int ANY_CALLER_UID = 1;
    private static final int ANY_CALLER_PID = 1;

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true).build();

    @Mock
    private PackageManagerInternal mMockPackageManagerInternal;

    @Mock(extraInterfaces = IBinder.class)
    private IInputMethodClient mClient;

    @Mock
    private IRemoteInputConnection mConnection;

    private Handler mHandler;

    private ClientController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mClient.asBinder()).thenReturn((IBinder) mClient);

        mHandler = new Handler(Looper.getMainLooper());
        mController = new ClientController(mMockPackageManagerInternal);
    }

    @Test
    // TODO(b/314150112): Enable host side mode for this test once b/315544364 is fixed.
    @IgnoreUnderRavenwood(blockedBy = {InputBinding.class, IInputMethodClientInvoker.class})
    public void testAddClient_cannotAddTheSameClientTwice() {
        var invoker = IInputMethodClientInvoker.create(mClient, mHandler);

        synchronized (ImfLock.class) {
            mController.addClient(invoker, mConnection, ANY_DISPLAY_ID, ANY_CALLER_UID,
                    ANY_CALLER_PID);

            SecurityException thrown = assertThrows(SecurityException.class,
                    () -> {
                        synchronized (ImfLock.class) {
                            mController.addClient(invoker, mConnection, ANY_DISPLAY_ID,
                                    ANY_CALLER_UID, ANY_CALLER_PID);
                        }
                    });
            assertThat(thrown.getMessage()).isEqualTo(
                    "uid=1/pid=1/displayId=0 is already registered");
        }
    }

    @Test
    // TODO(b/314150112): Enable host side mode for this test once b/315544364 is fixed.
    @IgnoreUnderRavenwood(blockedBy = {InputBinding.class, IInputMethodClientInvoker.class})
    public void testAddClient() throws Exception {
        synchronized (ImfLock.class) {
            var invoker = IInputMethodClientInvoker.create(mClient, mHandler);
            var added = mController.addClient(invoker, mConnection, ANY_DISPLAY_ID, ANY_CALLER_UID,
                    ANY_CALLER_PID);

            verify(invoker.asBinder()).linkToDeath(any(IBinder.DeathRecipient.class), eq(0));
            assertThat(mController.mClients).containsEntry(invoker.asBinder(), added);
        }
    }

    @Test
    // TODO(b/314150112): Enable host side mode for this test once b/315544364 is fixed.
    @IgnoreUnderRavenwood(blockedBy = {InputBinding.class, IInputMethodClientInvoker.class})
    public void testRemoveClient() {
        var callback = new TestClientControllerCallback();
        ClientState added;
        synchronized (ImfLock.class) {
            mController.addClientControllerCallback(callback);

            var invoker = IInputMethodClientInvoker.create(mClient, mHandler);
            added = mController.addClient(invoker, mConnection, ANY_DISPLAY_ID, ANY_CALLER_UID,
                    ANY_CALLER_PID);
            assertThat(mController.mClients).containsEntry(invoker.asBinder(), added);
            assertThat(mController.removeClient(mClient)).isTrue();
        }

        // Test callback
        var removed = callback.waitForRemovedClient(5, TimeUnit.SECONDS);
        assertThat(removed).isSameInstanceAs(added);
    }

    private static class TestClientControllerCallback implements ClientControllerCallback {

        private final CountDownLatch mLatch = new CountDownLatch(1);

        private ClientState mRemoved;

        @Override
        public void onClientRemoved(ClientState removed) {
            mRemoved = removed;
            mLatch.countDown();
        }

        ClientState waitForRemovedClient(long timeout, TimeUnit unit) {
            try {
                assertWithMessage("ClientController callback wasn't called on user removed").that(
                        mLatch.await(timeout, unit)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Unexpected thread interruption", e);
            }
            return mRemoved;
        }
    }
}
