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

package android.service.dreams;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.RemoteException;
import android.os.test.TestLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.ObservableServiceConnection;
import com.android.internal.util.PersistentServiceConnection;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamOverlayConnectionHandlerTest {
    @Mock
    private Context mContext;
    @Mock
    private PersistentServiceConnection<IDreamOverlay> mConnection;
    @Mock
    private Intent mServiceIntent;
    @Mock
    private IDreamOverlay mOverlayService;
    @Mock
    private IDreamOverlayClient mOverlayClient;
    @Mock
    private Runnable mOnDisconnectRunnable;

    private TestLooper mTestLooper;
    private DreamOverlayConnectionHandler mDreamOverlayConnectionHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestLooper = new TestLooper();
        mDreamOverlayConnectionHandler = new DreamOverlayConnectionHandler(
                mContext,
                mTestLooper.getLooper(),
                mServiceIntent,
                mOnDisconnectRunnable,
                new TestInjector(mConnection));
    }

    @Test
    public void consumerShouldRunImmediatelyWhenClientAvailable() throws RemoteException {
        mDreamOverlayConnectionHandler.bind();
        connectService();
        provideClient();

        final Consumer<IDreamOverlayClient> consumer = Mockito.mock(Consumer.class);
        mDreamOverlayConnectionHandler.addConsumer(consumer);
        mTestLooper.dispatchAll();
        verify(consumer).accept(mOverlayClient);
    }

    @Test
    public void consumerShouldRunAfterClientAvailable() throws RemoteException {
        mDreamOverlayConnectionHandler.bind();
        connectService();

        final Consumer<IDreamOverlayClient> consumer = Mockito.mock(Consumer.class);
        mDreamOverlayConnectionHandler.addConsumer(consumer);
        mTestLooper.dispatchAll();
        // No client yet, so we shouldn't have executed
        verify(consumer, never()).accept(mOverlayClient);

        provideClient();
        mTestLooper.dispatchAll();
        verify(consumer).accept(mOverlayClient);
    }

    @Test
    public void consumerShouldNeverRunIfClientConnectsAndDisconnects() throws RemoteException {
        mDreamOverlayConnectionHandler.bind();
        connectService();

        final Consumer<IDreamOverlayClient> consumer = Mockito.mock(Consumer.class);
        mDreamOverlayConnectionHandler.addConsumer(consumer);
        mTestLooper.dispatchAll();
        // No client yet, so we shouldn't have executed
        verify(consumer, never()).accept(mOverlayClient);
        verify(mOnDisconnectRunnable, never()).run();

        provideClient();
        // Service disconnected before looper could handle the message.
        disconnectService();
        mTestLooper.dispatchAll();
        verify(consumer, never()).accept(mOverlayClient);
        verify(mOnDisconnectRunnable).run();
    }

    @Test
    public void consumerShouldNeverRunIfUnbindCalled() throws RemoteException {
        mDreamOverlayConnectionHandler.bind();
        connectService();
        provideClient();

        final Consumer<IDreamOverlayClient> consumer = Mockito.mock(Consumer.class);
        mDreamOverlayConnectionHandler.addConsumer(consumer);
        mDreamOverlayConnectionHandler.unbind();
        mTestLooper.dispatchAll();
        // We unbinded immediately after adding consumer, so should never have run.
        verify(consumer, never()).accept(mOverlayClient);
    }

    @Test
    public void consumersOnlyRunOnceIfUnbound() throws RemoteException {
        mDreamOverlayConnectionHandler.bind();
        connectService();
        provideClient();

        AtomicInteger counter = new AtomicInteger();
        // Add 10 consumers in a row which call unbind within the consumer.
        for (int i = 0; i < 10; i++) {
            mDreamOverlayConnectionHandler.addConsumer(client -> {
                counter.getAndIncrement();
                mDreamOverlayConnectionHandler.unbind();
            });
        }
        mTestLooper.dispatchAll();
        // Only the first consumer should have run, since we unbinded.
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    public void consumerShouldRunAgainAfterReconnect() throws RemoteException {
        mDreamOverlayConnectionHandler.bind();
        connectService();
        provideClient();

        final Consumer<IDreamOverlayClient> consumer = Mockito.mock(Consumer.class);
        mDreamOverlayConnectionHandler.addConsumer(consumer);
        mTestLooper.dispatchAll();
        verify(consumer, times(1)).accept(mOverlayClient);

        disconnectService();
        mTestLooper.dispatchAll();
        // No new calls should happen when service disconnected.
        verify(consumer, times(1)).accept(mOverlayClient);

        connectService();
        provideClient();
        mTestLooper.dispatchAll();
        // We should trigger the consumer again once the server reconnects.
        verify(consumer, times(2)).accept(mOverlayClient);
    }

    @Test
    public void consumerShouldNeverRunIfRemovedImmediately() throws RemoteException {
        mDreamOverlayConnectionHandler.bind();
        connectService();
        provideClient();

        final Consumer<IDreamOverlayClient> consumer = Mockito.mock(Consumer.class);
        mDreamOverlayConnectionHandler.addConsumer(consumer);
        mDreamOverlayConnectionHandler.removeConsumer(consumer);
        mTestLooper.dispatchAll();
        verify(consumer, never()).accept(mOverlayClient);
    }

    private void connectService() {
        final ObservableServiceConnection.Callback<IDreamOverlay> callback =
                captureConnectionCallback();
        callback.onConnected(mConnection, mOverlayService);
    }

    private void disconnectService() {
        final ObservableServiceConnection.Callback<IDreamOverlay> callback =
                captureConnectionCallback();
        callback.onDisconnected(mConnection, /* reason= */ 0);
    }

    private void provideClient() throws RemoteException {
        final IDreamOverlayClientCallback callback = captureClientCallback();
        callback.onDreamOverlayClient(mOverlayClient);
    }

    private ObservableServiceConnection.Callback<IDreamOverlay> captureConnectionCallback() {
        ArgumentCaptor<ObservableServiceConnection.Callback<IDreamOverlay>>
                callbackCaptor =
                ArgumentCaptor.forClass(ObservableServiceConnection.Callback.class);
        verify(mConnection).addCallback(callbackCaptor.capture());
        return callbackCaptor.getValue();
    }

    private IDreamOverlayClientCallback captureClientCallback() throws RemoteException {
        ArgumentCaptor<IDreamOverlayClientCallback> callbackCaptor =
                ArgumentCaptor.forClass(IDreamOverlayClientCallback.class);
        verify(mOverlayService, atLeastOnce()).getClient(callbackCaptor.capture());
        return callbackCaptor.getValue();
    }

    static class TestInjector extends DreamOverlayConnectionHandler.Injector {
        private final PersistentServiceConnection<IDreamOverlay> mConnection;

        TestInjector(PersistentServiceConnection<IDreamOverlay> connection) {
            mConnection = connection;
        }

        @Override
        public PersistentServiceConnection<IDreamOverlay> buildConnection(Context context,
                Handler handler, Intent serviceIntent) {
            return mConnection;
        }
    }
}
