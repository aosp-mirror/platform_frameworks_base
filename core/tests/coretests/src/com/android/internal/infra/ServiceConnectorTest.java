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

package com.android.internal.infra;

import static com.google.common.truth.Truth.assertThat;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.aidl.ITestServiceConnectorService;
import com.android.internal.infra.ServiceConnectorTest.CapturingServiceLifecycleCallbacks.ServiceLifeCycleEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Unit tests for {@link ServiceConnector}
 */
@RunWith(AndroidJUnit4.class)
public class ServiceConnectorTest {

    private final CapturingServiceLifecycleCallbacks mCapturingServiceLifecycleCallbacks =
            new CapturingServiceLifecycleCallbacks();
    private ServiceConnector<ITestServiceConnectorService> mServiceConnector;

    @Before
    public void setup() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Intent testServiceConnectorServiceIntent = new Intent(TestService.ACTION_TEST_SERVICE);
        testServiceConnectorServiceIntent.setPackage(context.getPackageName());

        ServiceConnector.Impl<ITestServiceConnectorService> serviceConnector =
                new ServiceConnector.Impl<ITestServiceConnectorService>(
                        context,
                        testServiceConnectorServiceIntent,
                        /* bindingFlags= */ 0,
                        UserHandle.myUserId(),
                        ITestServiceConnectorService.Stub::asInterface);
        serviceConnector.setServiceLifecycleCallbacks(mCapturingServiceLifecycleCallbacks);
        mServiceConnector = serviceConnector;
    }

    @Test
    public void connect_invokesLifecycleCallbacks() throws Exception {
        connectAndWaitForDone();

        assertThat(mCapturingServiceLifecycleCallbacks.getCapturedLifecycleEvents())
                .containsExactly(ServiceLifeCycleEvent.ON_CONNECTED)
                .inOrder();
    }

    @Test
    public void connect_alreadyConnected_invokesLifecycleCallbacksOnce() throws Exception {
        connectAndWaitForDone();
        connectAndWaitForDone();

        assertThat(mCapturingServiceLifecycleCallbacks.getCapturedLifecycleEvents())
                .containsExactly(ServiceLifeCycleEvent.ON_CONNECTED)
                .inOrder();
    }

    @Test
    public void unbind_neverConnected_noLifecycleCallbacks() {
        unbindAndWaitForDone();

        assertThat(mCapturingServiceLifecycleCallbacks.getCapturedLifecycleEvents())
                .isEmpty();
    }

    @Test
    public void unbind_whileConnected_invokesLifecycleCallbacks() throws Exception {
        connectAndWaitForDone();
        unbindAndWaitForDone();

        assertThat(mCapturingServiceLifecycleCallbacks.getCapturedLifecycleEvents())
                .containsExactly(
                        ServiceLifeCycleEvent.ON_CONNECTED,
                        ServiceLifeCycleEvent.ON_DISCONNECTED)
                .inOrder();
    }


    @Test
    public void unbind_alreadyUnbound_invokesLifecycleCallbacks() throws Exception {
        connectAndWaitForDone();
        unbindAndWaitForDone();
        unbindAndWaitForDone();

        assertThat(mCapturingServiceLifecycleCallbacks.getCapturedLifecycleEvents())
                .containsExactly(
                        ServiceLifeCycleEvent.ON_CONNECTED,
                        ServiceLifeCycleEvent.ON_DISCONNECTED)
                .inOrder();
    }

    @Test
    public void binds_connectsAndUnbindsMultipleTimes_invokesLifecycleCallbacks() throws Exception {
        connectAndWaitForDone();
        unbindAndWaitForDone();
        connectAndWaitForDone();
        unbindAndWaitForDone();
        connectAndWaitForDone();

        assertThat(mCapturingServiceLifecycleCallbacks.getCapturedLifecycleEvents())
                .containsExactly(
                        ServiceLifeCycleEvent.ON_CONNECTED,
                        ServiceLifeCycleEvent.ON_DISCONNECTED,
                        ServiceLifeCycleEvent.ON_CONNECTED,
                        ServiceLifeCycleEvent.ON_DISCONNECTED,
                        ServiceLifeCycleEvent.ON_CONNECTED)
                .inOrder();
    }

    @Test
    public void processCrashes_whileConnected_invokesLifecycleCallbacks() throws Exception {
        connectAndWaitForDone();
        waitForDone(mServiceConnector.post(service -> service.crashProcess()));

        assertThat(mCapturingServiceLifecycleCallbacks.getCapturedLifecycleEvents())
                .containsExactly(
                        ServiceLifeCycleEvent.ON_CONNECTED,
                        ServiceLifeCycleEvent.ON_BINDER_DIED)
                .inOrder();
    }

    private void connectAndWaitForDone() {
        waitForDone(mServiceConnector.connect());
    }

    private void unbindAndWaitForDone() {
        mServiceConnector.unbind();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void waitForDone(AndroidFuture<?> androidFuture) {
        if (androidFuture.isDone()) {
            return;
        }

        try {
            androidFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException ex) {
            throw new RuntimeException(ex);
        } catch (ExecutionException | CancellationException ex) {
            // Failed and canceled futures are completed
            return;
        }
    }

    public static final class CapturingServiceLifecycleCallbacks implements
            ServiceConnector.ServiceLifecycleCallbacks<ITestServiceConnectorService> {
        public enum ServiceLifeCycleEvent {
            ON_CONNECTED,
            ON_DISCONNECTED,
            ON_BINDER_DIED,
        }

        private final ArrayList<ServiceLifeCycleEvent> mCapturedLifecycleEventServices =
                new ArrayList<>();

        public ArrayList<ServiceLifeCycleEvent> getCapturedLifecycleEvents() {
            return mCapturedLifecycleEventServices;
        }

        @Override
        public void onConnected(@NonNull ITestServiceConnectorService service) {
            requireNonNull(service);
            mCapturedLifecycleEventServices.add(ServiceLifeCycleEvent.ON_CONNECTED);
        }

        @Override
        public void onDisconnected(@NonNull ITestServiceConnectorService service) {
            requireNonNull(service);
            mCapturedLifecycleEventServices.add(ServiceLifeCycleEvent.ON_DISCONNECTED);
        }

        @Override
        public void onBinderDied() {
            mCapturedLifecycleEventServices.add(ServiceLifeCycleEvent.ON_BINDER_DIED);
        }
    }

    public static final class TestService extends Service {

        public static String ACTION_TEST_SERVICE = "android.intent.action.BIND_TEST_SERVICE";

        @Nullable
        @Override
        public IBinder onBind(@Nullable Intent intent) {
            if (intent == null) {
                return null;
            }

            if (!intent.getAction().equals(ACTION_TEST_SERVICE)) {
                return null;
            }

            return new TestServiceConnectorService().asBinder();
        }
    }

    private static final class TestServiceConnectorService extends
            ITestServiceConnectorService.Stub {
        @Override
        public void crashProcess() {
            Process.killProcess(Process.myPid());
        }
    }
}

