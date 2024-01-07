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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
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

    @Mock
    private IBinder.DeathRecipient mDeathRecipient;

    private Handler mHandler;

    private ClientController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mHandler = new Handler(Looper.getMainLooper());
        mController = new ClientController(mMockPackageManagerInternal);
        when(mClient.asBinder()).thenReturn((IBinder) mClient);
    }

    @Test
    // TODO(b/314150112): Enable host side mode for this test once b/315544364 is fixed.
    @IgnoreUnderRavenwood(blockedBy = {InputBinding.class, IInputMethodClientInvoker.class})
    public void testAddClient_cannotAddTheSameClientTwice() {
        var invoker = IInputMethodClientInvoker.create(mClient, mHandler);

        synchronized (ImfLock.class) {
            mController.addClient(invoker, mConnection, ANY_DISPLAY_ID, mDeathRecipient,
                    ANY_CALLER_UID, ANY_CALLER_PID);

            SecurityException thrown = assertThrows(SecurityException.class,
                    () -> {
                        synchronized (ImfLock.class) {
                            mController.addClient(invoker, mConnection, ANY_DISPLAY_ID,
                                    mDeathRecipient, ANY_CALLER_UID, ANY_CALLER_PID);
                        }
                    });
            assertThat(thrown.getMessage()).isEqualTo(
                    "uid=1/pid=1/displayId=0 is already registered");
        }
    }
}
