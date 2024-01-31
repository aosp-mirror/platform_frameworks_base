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
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.platform.test.ravenwood.RavenwoodRule;
import android.view.Display;

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
    private static final int ANY_CALLER_PID = 2;
    private static final String SOME_PACKAGE_NAME = "some.package";

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

    // TODO(b/322895594): No need to directly invoke create$ravenwood once b/322895594 is fixed.
    private IInputMethodClientInvoker createInvoker(IInputMethodClient client, Handler handler) {
        return RavenwoodRule.isOnRavenwood()
                ? IInputMethodClientInvoker.create$ravenwood(client, handler) :
                IInputMethodClientInvoker.create(client, handler);
    }

    @Test
    public void testAddClient_cannotAddTheSameClientTwice() {
        final var invoker = createInvoker(mClient, mHandler);
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
                    "uid=" + ANY_CALLER_UID + "/pid=" + ANY_CALLER_PID
                            + "/displayId=0 is already registered");
        }
    }

    @Test
    public void testAddClient() throws Exception {
        final var invoker = createInvoker(mClient, mHandler);
        synchronized (ImfLock.class) {
            final var added = mController.addClient(invoker, mConnection, ANY_DISPLAY_ID,
                    ANY_CALLER_UID,
                    ANY_CALLER_PID);

            verify(invoker.asBinder()).linkToDeath(any(IBinder.DeathRecipient.class), eq(0));
            assertThat(mController.getClient(invoker.asBinder())).isSameInstanceAs(added);
        }
    }

    @Test
    public void testRemoveClient() {
        final var invoker = createInvoker(mClient, mHandler);
        final var callback = new TestClientControllerCallback();
        ClientState added;
        synchronized (ImfLock.class) {
            mController.addClientControllerCallback(callback);
            added = mController.addClient(invoker, mConnection, ANY_DISPLAY_ID, ANY_CALLER_UID,
                    ANY_CALLER_PID);
            assertThat(mController.getClient(invoker.asBinder())).isSameInstanceAs(added);
            assertThat(mController.removeClient(mClient)).isTrue();
        }

        // Test callback
        final var removed = callback.waitForRemovedClient(5, TimeUnit.SECONDS);
        assertThat(removed).isSameInstanceAs(added);
    }

    @Test
    public void testVerifyClientAndPackageMatch() {
        final var invoker = createInvoker(mClient, mHandler);
        when(mMockPackageManagerInternal.isSameApp(eq(SOME_PACKAGE_NAME),  /* flags= */
                anyLong(), eq(ANY_CALLER_UID), /* userId= */ anyInt())).thenReturn(true);

        synchronized (ImfLock.class) {
            mController.addClient(invoker, mConnection, ANY_DISPLAY_ID, ANY_CALLER_UID,
                    ANY_CALLER_PID);
            assertThat(
                    mController.verifyClientAndPackageMatch(mClient, SOME_PACKAGE_NAME)).isTrue();
        }
    }

    @Test
    public void testVerifyClientAndPackageMatch_unknownClient() {
        synchronized (ImfLock.class) {
            assertThrows(IllegalArgumentException.class,
                    () -> {
                        synchronized (ImfLock.class) {
                            mController.verifyClientAndPackageMatch(mClient, SOME_PACKAGE_NAME);
                        }
                    });
        }
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
