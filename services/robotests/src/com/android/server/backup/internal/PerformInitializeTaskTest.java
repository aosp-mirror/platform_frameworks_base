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

package com.android.server.backup.internal;

import static android.app.backup.BackupTransport.TRANSPORT_ERROR;
import static android.app.backup.BackupTransport.TRANSPORT_OK;

import static com.android.server.backup.testing.TestUtils.assertLogcatContains;
import static com.android.server.backup.testing.TransportData.backupTransport;
import static com.android.server.backup.testing.TransportData.d2dTransport;
import static com.android.server.backup.testing.TransportData.localTransport;
import static com.android.server.backup.testing.TransportTestUtils.setUpTransports;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.app.backup.IBackupObserver;
import android.os.DeadObjectException;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.transport.TransportClient;
import com.android.server.testing.shadows.ShadowSlog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowSlog.class)
@Presubmit
public class PerformInitializeTaskTest {
    @Mock private UserBackupManagerService mBackupManagerService;
    @Mock private TransportManager mTransportManager;
    @Mock private OnTaskFinishedListener mListener;
    @Mock private IBackupTransport mTransportBinder;
    @Mock private IBackupObserver mObserver;
    @Mock private AlarmManager mAlarmManager;
    @Mock private PendingIntent mRunInitIntent;
    private File mBaseStateDir;
    private TransportData mTransport;
    private String mTransportName;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTransport = backupTransport();
        mTransportName = mTransport.transportName;

        Application context = RuntimeEnvironment.application;
        mBaseStateDir = new File(context.getCacheDir(), "base_state_dir");
        assertThat(mBaseStateDir.mkdir()).isTrue();

        when(mBackupManagerService.getAlarmManager()).thenReturn(mAlarmManager);
        when(mBackupManagerService.getRunInitIntent()).thenReturn(mRunInitIntent);
    }

    @Test
    public void testRun_callsTransportCorrectly() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mTransportBinder).initializeDevice();
        verify(mTransportBinder).finishBackup();
    }

    @Test
    public void testRun_callsBackupManagerCorrectly() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mBackupManagerService)
                .recordInitPending(false, mTransportName, mTransport.transportDirName);
        verify(mBackupManagerService)
                .resetBackupState(eq(new File(mBaseStateDir, mTransport.transportDirName)));
    }

    @Test
    public void testRun_callsObserverAndListenerCorrectly() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mObserver).onResult(eq(mTransportName), eq(TRANSPORT_OK));
        verify(mObserver).backupFinished(eq(TRANSPORT_OK));
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRun_whenInitializeDeviceFails() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_ERROR, 0);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mTransportBinder).initializeDevice();
        verify(mTransportBinder, never()).finishBackup();
        verify(mBackupManagerService)
                .recordInitPending(true, mTransportName, mTransport.transportDirName);
    }

    @Test
    public void testRun_whenInitializeDeviceFails_callsObserverAndListenerCorrectly()
            throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_ERROR, 0);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mObserver).onResult(eq(mTransportName), eq(TRANSPORT_ERROR));
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRun_whenInitializeDeviceFails_schedulesAlarm() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_ERROR, 0);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mAlarmManager).set(anyInt(), anyLong(), eq(mRunInitIntent));
    }

    @Test
    public void testRun_whenFinishBackupFails() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_OK, TRANSPORT_ERROR);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mTransportBinder).initializeDevice();
        verify(mTransportBinder).finishBackup();
        verify(mBackupManagerService)
                .recordInitPending(true, mTransportName, mTransport.transportDirName);
    }

    @Test
    public void testRun_whenFinishBackupFails_callsObserverAndListenerCorrectly() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_OK, TRANSPORT_ERROR);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mObserver).onResult(eq(mTransportName), eq(TRANSPORT_ERROR));
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRun_whenFinishBackupFails_logs() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_OK, TRANSPORT_ERROR);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        assertLogcatContains(
                BackupManagerService.TAG,
                log -> log.msg.contains("finishBackup()") && log.type >= Log.ERROR);
    }

    @Test
    public void testRun_whenInitializeDeviceFails_logs() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_ERROR, 0);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        assertLogcatContains(
                BackupManagerService.TAG,
                log -> log.msg.contains("initializeDevice()") && log.type >= Log.ERROR);
    }

    @Test
    public void testRun_whenFinishBackupFails_schedulesAlarm() throws Exception {
        setUpTransport(mTransport);
        configureTransport(mTransportBinder, TRANSPORT_OK, TRANSPORT_ERROR);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mAlarmManager).set(anyInt(), anyLong(), eq(mRunInitIntent));
    }

    @Test
    public void testRun_whenOnlyOneTransportFails() throws Exception {
        TransportData transport1 = backupTransport();
        TransportData transport2 = d2dTransport();
        List<TransportMock> transportMocks =
                setUpTransports(mTransportManager, transport1, transport2);
        configureTransport(transportMocks.get(0).transport, TRANSPORT_ERROR, 0);
        configureTransport(transportMocks.get(1).transport, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask =
                createPerformInitializeTask(transport1.transportName, transport2.transportName);

        performInitializeTask.run();

        verify(transportMocks.get(1).transport).initializeDevice();
        verify(mObserver).onResult(eq(transport1.transportName), eq(TRANSPORT_ERROR));
        verify(mObserver).onResult(eq(transport2.transportName), eq(TRANSPORT_OK));
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
    }

    @Test
    public void testRun_withMultipleTransports() throws Exception {
        List<TransportMock> transportMocks =
                setUpTransports(
                        mTransportManager, backupTransport(), d2dTransport(), localTransport());
        configureTransport(transportMocks.get(0).transport, TRANSPORT_OK, TRANSPORT_OK);
        configureTransport(transportMocks.get(1).transport, TRANSPORT_OK, TRANSPORT_OK);
        configureTransport(transportMocks.get(2).transport, TRANSPORT_OK, TRANSPORT_OK);
        String[] transportNames =
                Stream.of(new TransportData[] {backupTransport(), d2dTransport(), localTransport()})
                        .map(t -> t.transportName)
                        .toArray(String[]::new);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(transportNames);

        performInitializeTask.run();

        Iterator<TransportData> transportsIterator =
                Arrays.asList(
                                new TransportData[] {
                                    backupTransport(), d2dTransport(), localTransport()
                                })
                        .iterator();
        for (TransportMock transportMock : transportMocks) {
            TransportData transport = transportsIterator.next();
            verify(mTransportManager).getTransportClient(eq(transport.transportName), any());
            verify(mTransportManager)
                    .disposeOfTransportClient(eq(transportMock.transportClient), any());
        }
    }

    @Test
    public void testRun_whenOnlyOneTransportFails_disposesAllTransports() throws Exception {
        TransportData transport1 = backupTransport();
        TransportData transport2 = d2dTransport();
        List<TransportMock> transportMocks =
                setUpTransports(mTransportManager, transport1, transport2);
        configureTransport(transportMocks.get(0).transport, TRANSPORT_ERROR, 0);
        configureTransport(transportMocks.get(1).transport, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask =
                createPerformInitializeTask(transport1.transportName, transport2.transportName);

        performInitializeTask.run();

        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMocks.get(0).transportClient), any());
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMocks.get(1).transportClient), any());
    }

    @Test
    public void testRun_whenTransportNotRegistered() throws Exception {
        setUpTransports(mTransportManager, mTransport.unregistered());
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mTransportManager, never()).disposeOfTransportClient(any(), any());
        verify(mObserver, never()).onResult(any(), anyInt());
        verify(mObserver).backupFinished(eq(TRANSPORT_OK));
    }

    @Test
    public void testRun_whenOnlyOneTransportNotRegistered() throws Exception {
        TransportData transport1 = backupTransport().unregistered();
        TransportData transport2 = d2dTransport();
        List<TransportMock> transportMocks =
                setUpTransports(mTransportManager, transport1, transport2);
        String registeredTransportName = transport2.transportName;
        IBackupTransport registeredTransport = transportMocks.get(1).transport;
        TransportClient registeredTransportClient = transportMocks.get(1).transportClient;
        PerformInitializeTask performInitializeTask =
                createPerformInitializeTask(transport1.transportName, transport2.transportName);

        performInitializeTask.run();

        verify(registeredTransport).initializeDevice();
        verify(mTransportManager).disposeOfTransportClient(eq(registeredTransportClient), any());
        verify(mObserver).onResult(eq(registeredTransportName), eq(TRANSPORT_OK));
    }

    @Test
    public void testRun_whenTransportNotAvailable() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport.unavailable());
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRun_whenTransportThrowsDeadObjectException() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transport = transportMock.transport;
        TransportClient transportClient = transportMock.transportClient;
        when(transport.initializeDevice()).thenThrow(DeadObjectException.class);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(mTransportName);

        performInitializeTask.run();

        verify(mTransportManager).disposeOfTransportClient(eq(transportClient), any());
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
        verify(mListener).onFinished(any());
    }

    private PerformInitializeTask createPerformInitializeTask(String... transportNames) {
        return new PerformInitializeTask(
                mBackupManagerService,
                mTransportManager,
                transportNames,
                mObserver,
                mListener,
                mBaseStateDir);
    }

    private void configureTransport(
            IBackupTransport transportMock, int initializeDeviceStatus, int finishBackupStatus)
            throws Exception {
        when(transportMock.initializeDevice()).thenReturn(initializeDeviceStatus);
        when(transportMock.finishBackup()).thenReturn(finishBackupStatus);
    }

    private TransportMock setUpTransport(TransportData transport) throws Exception {
        TransportMock transportMock =
                TransportTestUtils.setUpTransport(mTransportManager, transport);
        mTransportBinder = transportMock.transport;
        return transportMock;
    }
}
