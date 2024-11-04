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

package com.android.server.dreams;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.EnableFlags;
import android.service.dreams.DreamOverlayService;
import android.service.dreams.Flags;
import android.service.dreams.IDreamOverlay;
import android.service.dreams.IDreamOverlayCallback;
import android.service.dreams.IDreamOverlayClient;
import android.service.dreams.IDreamOverlayClientCallback;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

/**
 * A collection of tests to exercise {@link DreamOverlayService}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamOverlayServiceTest {
    private static final ComponentName FIRST_DREAM_COMPONENT =
            ComponentName.unflattenFromString("com.foo.bar/.DreamService");
    private static final ComponentName SECOND_DREAM_COMPONENT =
            ComponentName.unflattenFromString("com.foo.baz/.DreamService");

    @Mock
    WindowManager.LayoutParams mLayoutParams;

    @Mock
    IDreamOverlayCallback mOverlayCallback;

    @Mock
    Executor mExecutor;

    /**
     * {@link TestDreamOverlayService} is a simple {@link DreamOverlayService} implementation for
     * tracking interactions across {@link IDreamOverlay} binder interface. The service reports
     * interactions to a {@link Monitor} instance provided at construction.
     */
    private static class TestDreamOverlayService extends DreamOverlayService {
        /**
         * An interface implemented to be informed when the corresponding methods in
         * {@link TestDreamOverlayService} are invoked.
         */
        interface Monitor {
            void onStartDream();
            void onEndDream();
            void onWakeUp();
        }

        private final Monitor mMonitor;

        TestDreamOverlayService(Monitor monitor, Executor executor) {
            super(executor);
            mMonitor = monitor;
        }

        @Override
        public void onStartDream(@NonNull WindowManager.LayoutParams layoutParams) {
            mMonitor.onStartDream();
        }

        @Override
        public void onEndDream() {
            mMonitor.onEndDream();
            super.onEndDream();
        }

        @Override
        public void onWakeUp() {
            mMonitor.onWakeUp();
            super.onWakeUp();
        }
    }

    /**
     * A {@link IDreamOverlayClientCallback} implementation that captures the requested client.
     */
    private static class OverlayClientCallback extends IDreamOverlayClientCallback.Stub {
        public IDreamOverlayClient retrievedClient;
        @Override
        public void onDreamOverlayClient(IDreamOverlayClient client) throws RemoteException {
            retrievedClient = client;
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Verifies that callbacks for subclasses are run on the provided executor.
     */
    @Test
    public void testCallbacksRunOnExecutor() throws RemoteException {
        final TestDreamOverlayService.Monitor monitor = Mockito.mock(
                TestDreamOverlayService.Monitor.class);
        final TestDreamOverlayService service = new TestDreamOverlayService(monitor, mExecutor);
        final IBinder binder = service.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(binder);

        final IDreamOverlayClient client = getClient(overlay);

        // Start the dream.
        client.startDream(mLayoutParams, mOverlayCallback,
                FIRST_DREAM_COMPONENT.flattenToString(), false, false);

        // The callback should not have run yet.
        verify(monitor, never()).onStartDream();

        // Run the Runnable sent to the executor.
        ArgumentCaptor<Runnable> mRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mExecutor).execute(mRunnableCaptor.capture());
        mRunnableCaptor.getValue().run();

        // Callback is run.
        verify(monitor).onStartDream();

        clearInvocations(mExecutor);

        // Verify onWakeUp is run on the executor.
        client.wakeUp();
        verify(monitor, never()).onWakeUp();
        mRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mExecutor).execute(mRunnableCaptor.capture());
        mRunnableCaptor.getValue().run();
        verify(monitor).onWakeUp();

        clearInvocations(mExecutor);

        // Verify onEndDream is run on the executor.
        client.endDream();
        verify(monitor, never()).onEndDream();
        mRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mExecutor).execute(mRunnableCaptor.capture());
        mRunnableCaptor.getValue().run();
        verify(monitor).onEndDream();
    }

    /**
     * Verifies that only the currently started dream is able to affect the overlay.
     */
    @Test
    public void testOverlayClientInteraction() throws RemoteException {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mExecutor).execute(any());

        final TestDreamOverlayService.Monitor monitor = Mockito.mock(
                TestDreamOverlayService.Monitor.class);
        final TestDreamOverlayService service = new TestDreamOverlayService(monitor, mExecutor);
        final IBinder binder = service.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(binder);

        // Create two overlay clients and ensure they are unique.
        final IDreamOverlayClient firstClient = getClient(overlay);
        assertThat(firstClient).isNotNull();

        final IDreamOverlayClient secondClient = getClient(overlay);
        assertThat(secondClient).isNotNull();

        assertThat(firstClient).isNotEqualTo(secondClient);

        // Start a dream with the first client and ensure the dream is now active from the
        // overlay's perspective.
        firstClient.startDream(mLayoutParams, mOverlayCallback,
                FIRST_DREAM_COMPONENT.flattenToString(), true, false);


        verify(monitor).onStartDream();
        assertThat(service.getDreamComponent()).isEqualTo(FIRST_DREAM_COMPONENT);
        assertThat(service.isDreamInPreviewMode()).isTrue();

        Mockito.clearInvocations(monitor);

        // Start a dream from the second client and verify that the overlay has both cycled to
        // the new dream (ended/started).
        secondClient.startDream(mLayoutParams, mOverlayCallback,
                SECOND_DREAM_COMPONENT.flattenToString(), false, false);

        verify(monitor).onEndDream();
        verify(monitor).onStartDream();
        assertThat(service.getDreamComponent()).isEqualTo(SECOND_DREAM_COMPONENT);
        assertThat(service.isDreamInPreviewMode()).isFalse();

        Mockito.clearInvocations(monitor);

        // Verify that interactions with the first, now inactive client don't affect the overlay.
        firstClient.endDream();
        verify(monitor, never()).onEndDream();

        firstClient.wakeUp();
        verify(monitor, never()).onWakeUp();
    }

    /**
     * Verifies that only the currently started dream is able to affect the overlay.
     */
    @Test
    @EnableFlags(Flags.FLAG_DREAM_WAKE_REDIRECT)
    public void testRedirectToWakeAcrossClients() throws RemoteException {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mExecutor).execute(any());

        final TestDreamOverlayService.Monitor monitor = Mockito.mock(
                TestDreamOverlayService.Monitor.class);
        final TestDreamOverlayService service = new TestDreamOverlayService(monitor, mExecutor);
        final IBinder binder = service.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(binder);

        service.redirectWake(true);

        final IDreamOverlayClient client = getClient(overlay);

        // Start the dream.
        client.startDream(mLayoutParams, mOverlayCallback,
                FIRST_DREAM_COMPONENT.flattenToString(), false, false);
        // Make sure redirect state is set on dream.
        verify(mOverlayCallback).onRedirectWake(eq(true));

        // Make sure new changes are propagated.
        clearInvocations(mOverlayCallback);
        service.redirectWake(false);
        verify(mOverlayCallback).onRedirectWake(eq(false));


        // Start another dream, make sure new dream is informed of current state.
        service.redirectWake(true);
        clearInvocations(mOverlayCallback);
        client.startDream(mLayoutParams, mOverlayCallback,
                FIRST_DREAM_COMPONENT.flattenToString(), false, false);
        verify(mOverlayCallback).onRedirectWake(eq(true));
    }

    private static IDreamOverlayClient getClient(IDreamOverlay overlay) throws RemoteException {
        final OverlayClientCallback callback = new OverlayClientCallback();
        overlay.getClient(callback);
        return callback.retrievedClient;
    }
}
