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

package com.android.server.inputmethod;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.inputmethodservice.InputMethodService;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.view.inputmethod.InputMethodInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.inputmethod.InputBindResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class InputMethodBindingControllerTest extends InputMethodManagerServiceTestBase {

    private static final String PACKAGE_NAME = "com.android.frameworks.inputmethodtests";
    private static final String TEST_SERVICE_NAME =
            "com.android.server.inputmethod.InputMethodBindingControllerTest"
                    + "$EmptyInputMethodService";
    private static final String TEST_IME_ID = PACKAGE_NAME + "/" + TEST_SERVICE_NAME;
    private static final long TIMEOUT_IN_SECONDS = 3;

    private InputMethodBindingController mBindingController;
    private Instrumentation mInstrumentation;
    private final int mImeConnectionBindFlags =
            InputMethodBindingController.IME_CONNECTION_BIND_FLAGS
                    & ~Context.BIND_SCHEDULE_LIKE_TOP_APP;
    private CountDownLatch mCountDownLatch;

    public static class EmptyInputMethodService extends InputMethodService {}

    @Before
    public void setUp() throws RemoteException {
        super.setUp();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mCountDownLatch = new CountDownLatch(1);
        // Remove flag Context.BIND_SCHEDULE_LIKE_TOP_APP because in tests we are not calling
        // from system.
        synchronized (ImfLock.class) {
            mBindingController =
                    new InputMethodBindingController(mUserId, mInputMethodManagerService,
                            mImeConnectionBindFlags, mCountDownLatch);
        }
    }

    @Test
    public void testBindCurrentMethod_noIme() {
        synchronized (ImfLock.class) {
            mBindingController.setSelectedMethodId(null);
            InputBindResult result = mBindingController.bindCurrentMethod();
            assertThat(result).isEqualTo(InputBindResult.NO_IME);
        }
    }

    @Test
    public void testBindCurrentMethod_unknownId() {
        synchronized (ImfLock.class) {
            mBindingController.setSelectedMethodId("unknown ime id");
        }
        assertThrows(IllegalArgumentException.class, () -> {
            synchronized (ImfLock.class) {
                mBindingController.bindCurrentMethod();
            }
        });
    }

    @Test
    public void testBindCurrentMethod_notConnected() {
        synchronized (ImfLock.class) {
            mBindingController.setSelectedMethodId(TEST_IME_ID);
            doReturn(false)
                    .when(mContext)
                    .bindServiceAsUser(
                            any(Intent.class),
                            any(ServiceConnection.class),
                            anyInt(),
                            any(UserHandle.class));

            InputBindResult result = mBindingController.bindCurrentMethod();
            assertThat(result).isEqualTo(InputBindResult.IME_NOT_CONNECTED);
        }
    }

    @Test
    public void testBindAndUnbindMethod() throws Exception {
        // Bind with main connection
        testBindCurrentMethodWithMainConnection();

        // Bind with visible connection
        testBindCurrentMethodWithVisibleConnection();

        // Unbind both main and visible connections
        testUnbindCurrentMethod();
    }

    private void testBindCurrentMethodWithMainConnection() throws Exception {
        final InputMethodInfo info;
        synchronized (ImfLock.class) {
            mBindingController.setSelectedMethodId(TEST_IME_ID);
            info = InputMethodSettingsRepository.get(mUserId).getMethodMap().get(TEST_IME_ID);
        }
        assertThat(info).isNotNull();
        assertThat(info.getId()).isEqualTo(TEST_IME_ID);
        assertThat(info.getServiceName()).isEqualTo(TEST_SERVICE_NAME);

        // Bind input method with main connection. It is called on another thread because we should
        // wait for onServiceConnected() to finish.
        InputBindResult result = callOnMainSync(() -> {
            synchronized (ImfLock.class) {
                return mBindingController.bindCurrentMethod();
            }
        });

        verify(mContext, times(1))
                .bindServiceAsUser(
                        any(Intent.class),
                        any(ServiceConnection.class),
                        eq(mImeConnectionBindFlags),
                        any(UserHandle.class));
        assertThat(result.result).isEqualTo(InputBindResult.ResultCode.SUCCESS_WAITING_IME_BINDING);
        assertThat(result.id).isEqualTo(info.getId());
        synchronized (ImfLock.class) {
            assertThat(mBindingController.hasMainConnection()).isTrue();
            assertThat(mBindingController.getCurId()).isEqualTo(info.getId());
            assertThat(mBindingController.getCurToken()).isNotNull();
        }
        // Wait for onServiceConnected()
        boolean completed = mCountDownLatch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for onServiceConnected()");
        }

        // Verify onServiceConnected() is called and bound successfully.
        synchronized (ImfLock.class) {
            assertThat(mBindingController.getCurMethod()).isNotNull();
            assertThat(mBindingController.getCurMethodUid()).isNotEqualTo(Process.INVALID_UID);
        }
    }

    private void testBindCurrentMethodWithVisibleConnection() {
        mInstrumentation.runOnMainSync(() -> {
            synchronized (ImfLock.class) {
                mBindingController.setCurrentMethodVisible();
            }
        });
        // Bind input method with visible connection
        verify(mContext, times(1))
                .bindServiceAsUser(
                        any(Intent.class),
                        any(ServiceConnection.class),
                        eq(InputMethodBindingController.IME_VISIBLE_BIND_FLAGS),
                        any(UserHandle.class));
        synchronized (ImfLock.class) {
            assertThat(mBindingController.isVisibleBound()).isTrue();
        }
    }

    private void testUnbindCurrentMethod() {
        mInstrumentation.runOnMainSync(() -> {
            synchronized (ImfLock.class) {
                mBindingController.unbindCurrentMethod();
            }
        });

        synchronized (ImfLock.class) {
            // Unbind both main connection and visible connection
            assertThat(mBindingController.hasMainConnection()).isFalse();
            assertThat(mBindingController.isVisibleBound()).isFalse();
            verify(mContext, times(2)).unbindService(any(ServiceConnection.class));
            assertThat(mBindingController.getCurToken()).isNull();
            assertThat(mBindingController.getCurId()).isNull();
            assertThat(mBindingController.getCurMethod()).isNull();
            assertThat(mBindingController.getCurMethodUid()).isEqualTo(Process.INVALID_UID);
        }
    }

    private static <V> V callOnMainSync(Callable<V> callable) {
        AtomicReference<V> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(
                        () -> {
                            try {
                                result.set(callable.call());
                            } catch (Exception e) {
                                throw new RuntimeException("Exception was thrown", e);
                            }
                        });
        return result.get();
    }
}
