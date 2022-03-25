/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.transport;

import static com.android.server.backup.TransportManager.SERVICE_ACTION_TRANSPORT_HOST;
import static com.android.server.backup.testing.TestUtils.assertEventLogged;
import static com.android.server.backup.testing.TestUtils.assertLogcatAtLeast;
import static com.android.server.backup.testing.TestUtils.assertLogcatAtMost;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.shadow.api.Shadow.extract;
import static org.testng.Assert.expectThrows;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import com.android.internal.backup.IBackupTransport;
import com.android.server.EventLogTags;
import com.android.server.testing.shadows.FrameworkShadowLooper;
import com.android.server.testing.shadows.ShadowCloseGuard;
import com.android.server.testing.shadows.ShadowEventLog;
import com.android.server.testing.shadows.ShadowSlog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {
            ShadowEventLog.class,
            ShadowCloseGuard.class,
            ShadowSlog.class,
            FrameworkShadowLooper.class
        })
@Presubmit
public class TransportConnectionTest {
    private static final String PACKAGE_NAME = "some.package.name";

    @Mock private Context mContext;
    @Mock private TransportConnectionListener mTransportConnectionListener;
    @Mock private TransportConnectionListener mTransportConnectionListener2;
    @Mock private IBackupTransport.Stub mTransportBinder;

    @UserIdInt private int mUserId;
    private TransportStats mTransportStats;
    private TransportConnection mTransportConnection;
    private BackupTransportClient mTransportClient;
    private ComponentName mTransportComponent;
    private String mTransportString;
    private Intent mBindIntent;
    private FrameworkShadowLooper mShadowMainLooper;
    private ShadowLooper mShadowWorkerLooper;
    private Handler mWorkerHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mUserId = UserHandle.USER_SYSTEM;
        Looper mainLooper = Looper.getMainLooper();
        mShadowMainLooper = extract(mainLooper);
        mTransportComponent =
                new ComponentName(PACKAGE_NAME, PACKAGE_NAME + ".transport.Transport");
        mTransportString = mTransportComponent.flattenToShortString();
        mTransportStats = new TransportStats();
        mBindIntent = new Intent(SERVICE_ACTION_TRANSPORT_HOST).setComponent(mTransportComponent);
        mTransportConnection =
                new TransportConnection(
                        mUserId,
                        mContext,
                        mTransportStats,
                        mBindIntent,
                        mTransportComponent,
                        "1",
                        "caller",
                        new Handler(mainLooper));
        mTransportClient = new BackupTransportClient(mTransportBinder);

        when(mContext.bindServiceAsUser(
                        eq(mBindIntent),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class)))
                .thenReturn(true);

        HandlerThread workerThread = new HandlerThread("worker");
        workerThread.start();
        mShadowWorkerLooper = shadowOf(workerThread.getLooper());
        mWorkerHandler = workerThread.getThreadHandler();
    }

    @Test
    public void testGetTransportComponent_returnsTransportComponent() {
        assertThat(mTransportConnection.getTransportComponent()).isEqualTo(mTransportComponent);
    }

    @Test
    public void testConnectAsync_callsBindService() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller");

        verify(mContext)
                .bindServiceAsUser(
                        eq(mBindIntent),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class));
    }

    @Test
    public void testConnectAsync_callsListenerWhenConnected() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);

        connection.onServiceConnected(mTransportComponent, mTransportBinder);

        mShadowMainLooper.runToEndOfTasks();
        verify(mTransportConnectionListener)
                .onTransportConnectionResult(any(BackupTransportClient.class),
                        eq(mTransportConnection));
    }

    @Test
    public void testConnectAsync_whenPendingConnection_callsAllListenersWhenConnected() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);

        mTransportConnection.connectAsync(mTransportConnectionListener2, "caller2");

        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        mShadowMainLooper.runToEndOfTasks();
        verify(mTransportConnectionListener)
                .onTransportConnectionResult(any(BackupTransportClient.class),
                        eq(mTransportConnection));
        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(any(BackupTransportClient.class),
                        eq(mTransportConnection));
    }

    @Test
    public void testConnectAsync_whenAlreadyConnected_callsListener() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);

        mTransportConnection.connectAsync(mTransportConnectionListener2, "caller2");

        mShadowMainLooper.runToEndOfTasks();
        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(any(BackupTransportClient.class),
                        eq(mTransportConnection));
    }

    @Test
    public void testConnectAsync_whenFrameworkDoesntBind_callsListener() {
        when(mContext.bindServiceAsUser(
                        eq(mBindIntent),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class)))
                .thenReturn(false);

        mTransportConnection.connectAsync(mTransportConnectionListener, "caller");

        mShadowMainLooper.runToEndOfTasks();
        verify(mTransportConnectionListener)
                .onTransportConnectionResult(isNull(), eq(mTransportConnection));
    }

    @Test
    public void testConnectAsync_whenFrameworkDoesNotBind_releasesConnection() {
        when(mContext.bindServiceAsUser(
                        eq(mBindIntent),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class)))
                .thenReturn(false);

        mTransportConnection.connectAsync(mTransportConnectionListener, "caller");

        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        verify(mContext).unbindService(eq(connection));
    }

    @Test
    public void testConnectAsync_afterOnServiceDisconnectedBeforeNewConnection_callsListener() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        connection.onServiceDisconnected(mTransportComponent);

        mTransportConnection.connectAsync(mTransportConnectionListener2, "caller1");

        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(isNull(), eq(mTransportConnection));
    }

    @Test
    public void testConnectAsync_afterOnServiceDisconnectedAfterNewConnection_callsListener() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        connection.onServiceDisconnected(mTransportComponent);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);

        mTransportConnection.connectAsync(mTransportConnectionListener2, "caller1");

        // Yes, it should return null because the object became unusable, check design doc
        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(isNull(), eq(mTransportConnection));
    }

    @Test
    public void testConnectAsync_callsListenerIfBindingDies() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);

        connection.onBindingDied(mTransportComponent);

        mShadowMainLooper.runToEndOfTasks();
        verify(mTransportConnectionListener)
                .onTransportConnectionResult(isNull(), eq(mTransportConnection));
    }

    @Test
    public void testConnectAsync_whenPendingConnection_callsListenersIfBindingDies() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);

        mTransportConnection.connectAsync(mTransportConnectionListener2, "caller2");

        connection.onBindingDied(mTransportComponent);
        mShadowMainLooper.runToEndOfTasks();
        verify(mTransportConnectionListener)
                .onTransportConnectionResult(isNull(), eq(mTransportConnection));
        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(isNull(), eq(mTransportConnection));
    }

    @Test
    public void testConnectAsync_beforeFrameworkCall_logsBoundTransitionEvent() {
        ShadowEventLog.setUp();

        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");

        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, mTransportString, 1);
    }

    @Test
    public void testConnectAsync_afterOnServiceConnected_logsBoundAndConnectedTransitionEvents() {
        ShadowEventLog.setUp();
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);

        connection.onServiceConnected(mTransportComponent, mTransportBinder);

        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, mTransportString, 1);
        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_CONNECTION, mTransportString, 1);
    }

    @Test
    public void testConnectAsync_afterOnBindingDied_logsBoundAndUnboundTransitionEvents() {
        ShadowEventLog.setUp();
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);

        connection.onBindingDied(mTransportComponent);

        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, mTransportString, 1);
        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, mTransportString, 0);
    }

    @Test
    public void testConnect_whenConnected_returnsTransport() throws Exception {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);

        BackupTransportClient transportClient =
                runInWorkerThread(() -> mTransportConnection.connect("caller2"));

        assertThat(transportClient).isNotNull();
    }

    @Test
    public void testConnect_afterOnServiceDisconnected_returnsNull() throws Exception {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        connection.onServiceDisconnected(mTransportComponent);

        BackupTransportClient transportClient =
                runInWorkerThread(() -> mTransportConnection.connect("caller2"));

        assertThat(transportClient).isNull();
    }

    @Test
    public void testConnect_afterOnBindingDied_returnsNull() throws Exception {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onBindingDied(mTransportComponent);

        BackupTransportClient transportClient =
                runInWorkerThread(() -> mTransportConnection.connect("caller2"));

        assertThat(transportClient).isNull();
    }

    @Test
    public void testConnect_callsThroughToConnectAsync() throws Exception {
        // We can't mock bindServiceAsUser() instead of connectAsync() and call the listener inline
        // because in our code in TransportClient we assume this is NOT run inline, such that the
        // reentrant lock can't be acquired by the listener at the call-site of bindServiceAsUser(),
        // which is what would happened if we mocked bindServiceAsUser() to call the listener
        // inline.
        TransportConnection transportConnection = spy(mTransportConnection);
        doAnswer(
                        invocation -> {
                            TransportConnectionListener listener = invocation.getArgument(0);
                            listener.onTransportConnectionResult(mTransportClient,
                                    transportConnection);
                            return null;
                        })
                .when(transportConnection)
                .connectAsync(any(), any());

        BackupTransportClient transportClient =
                runInWorkerThread(() -> transportConnection.connect("caller"));

        assertThat(transportClient).isNotNull();
    }

    @Test
    public void testUnbind_whenConnected_logsDisconnectedAndUnboundTransitionEvents() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        ShadowEventLog.setUp();

        mTransportConnection.unbind("caller1");

        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_CONNECTION, mTransportString, 0);
        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, mTransportString, 0);
    }

    @Test
    public void
            testOnServiceDisconnected_whenConnected_logsDisconnectedAndUnboundTransitionEvents() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        ShadowEventLog.setUp();

        connection.onServiceDisconnected(mTransportComponent);

        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_CONNECTION, mTransportString, 0);
        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, mTransportString, 0);
    }

    @Test
    public void testOnBindingDied_whenConnected_logsDisconnectedAndUnboundTransitionEvents() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        ShadowEventLog.setUp();

        connection.onBindingDied(mTransportComponent);

        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_CONNECTION, mTransportString, 0);
        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, mTransportString, 0);
    }

    @Test
    public void testMarkAsDisposed_whenCreated() {
        mTransportConnection.markAsDisposed();

        // No exception thrown
    }

    @Test
    public void testMarkAsDisposed_afterOnBindingDied() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onBindingDied(mTransportComponent);

        mTransportConnection.markAsDisposed();

        // No exception thrown
    }

    @Test
    public void testMarkAsDisposed_whenConnectedAndUnbound() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        mTransportConnection.unbind("caller1");

        mTransportConnection.markAsDisposed();

        // No exception thrown
    }

    @Test
    public void testMarkAsDisposed_afterOnServiceDisconnected() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        connection.onServiceDisconnected(mTransportComponent);

        mTransportConnection.markAsDisposed();

        // No exception thrown
    }

    @Test
    public void testMarkAsDisposed_whenBound() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");

        expectThrows(RuntimeException.class, mTransportConnection::markAsDisposed);
    }

    @Test
    public void testMarkAsDisposed_whenConnected() {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);

        expectThrows(RuntimeException.class, mTransportConnection::markAsDisposed);
    }

    @Test
    @SuppressWarnings("FinalizeCalledExplicitly")
    public void testFinalize_afterCreated() throws Throwable {
        ShadowLog.reset();

        mTransportConnection.finalize();

        assertLogcatAtMost(TransportConnection.TAG, Log.INFO);
    }

    @Test
    @SuppressWarnings("FinalizeCalledExplicitly")
    public void testFinalize_whenBound() throws Throwable {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ShadowLog.reset();

        mTransportConnection.finalize();

        assertLogcatAtLeast(TransportConnection.TAG, Log.ERROR);
    }

    @Test
    @SuppressWarnings("FinalizeCalledExplicitly")
    public void testFinalize_whenConnected() throws Throwable {
        mTransportConnection.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(mTransportComponent, mTransportBinder);
        ShadowLog.reset();

        mTransportConnection.finalize();

        expectThrows(
                TransportNotAvailableException.class,
                () -> mTransportConnection.getConnectedTransport("caller1"));
        assertLogcatAtLeast(TransportConnection.TAG, Log.ERROR);
    }

    @Test
    @SuppressWarnings("FinalizeCalledExplicitly")
    public void testFinalize_whenNotMarkedAsDisposed() throws Throwable {
        ShadowCloseGuard.setUp();

        mTransportConnection.finalize();

        assertThat(ShadowCloseGuard.hasReported()).isTrue();
    }

    @Test
    @SuppressWarnings("FinalizeCalledExplicitly")
    public void testFinalize_whenMarkedAsDisposed() throws Throwable {
        mTransportConnection.markAsDisposed();
        ShadowCloseGuard.setUp();

        mTransportConnection.finalize();

        assertThat(ShadowCloseGuard.hasReported()).isFalse();
    }

    @Nullable
    private <T> T runInWorkerThread(Supplier<T> supplier) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        mWorkerHandler.post(() -> future.complete(supplier.get()));
        // Although we are using a separate looper, we are still calling runToEndOfTasks() in the
        // main thread (Robolectric only *simulates* multi-thread). The only option left is to fool
        // the caller.
        mShadowMainLooper.setCurrentThread(false);
        mShadowWorkerLooper.runToEndOfTasks();
        mShadowMainLooper.reset();
        return future.get();
    }

    private ServiceConnection verifyBindServiceAsUserAndCaptureServiceConnection(Context context) {
        ArgumentCaptor<ServiceConnection> connectionCaptor =
                ArgumentCaptor.forClass(ServiceConnection.class);
        verify(context)
                .bindServiceAsUser(
                        any(Intent.class),
                        connectionCaptor.capture(),
                        anyInt(),
                        any(UserHandle.class));
        return connectionCaptor.getValue();
    }
}
