/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup;

import static com.android.server.backup.testing.BackupManagerServiceTestUtils.createBackupWakeLock;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils.setUpBackupManagerServiceBasics;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils.startBackupThreadAndGetLooper;
import static com.android.server.backup.testing.TestUtils.uncheck;
import static com.android.server.backup.testing.TransportData.backupTransport;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.IBackupAgent;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupManager;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.internal.BackupRequest;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.internal.PerformBackupTask;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.transport.TransportClient;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderClasses;
import com.android.server.testing.SystemLoaderPackages;
import com.android.server.testing.shadows.ShadowBackupDataInput;
import com.android.server.testing.shadows.ShadowBackupDataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowQueuedWork;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 26,
        shadows = {
            ShadowBackupDataInput.class,
            ShadowBackupDataOutput.class,
            ShadowQueuedWork.class
        })
@SystemLoaderPackages({"com.android.server.backup", "android.app.backup"})
@SystemLoaderClasses({IBackupTransport.class, IBackupAgent.class, PackageInfo.class})
@Presubmit
public class PerformBackupTaskTest {
    private static final String PACKAGE_1 = "com.example.package1";
    private static final String PACKAGE_2 = "com.example.package2";

    @Mock private BackupManagerService mBackupManagerService;
    @Mock private TransportManager mTransportManager;
    @Mock private DataChangedJournal mDataChangedJournal;
    @Mock private IBackupObserver mObserver;
    @Mock private IBackupManagerMonitor mMonitor;
    @Mock private OnTaskFinishedListener mListener;
    private TransportData mTransport;
    private ShadowLooper mShadowBackupLooper;
    private BackupHandler mBackupHandler;
    private PowerManager.WakeLock mWakeLock;
    private ShadowPackageManager mShadowPackageManager;
    private FakeIBackupManager mBackupManager;
    private File mBaseStateDir;
    private Application mApplication;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTransport = backupTransport();

        mApplication = RuntimeEnvironment.application;
        File cacheDir = mApplication.getCacheDir();
        mBaseStateDir = new File(cacheDir, "base_state_dir");
        File dataDir = new File(cacheDir, "data_dir");
        assertThat(mBaseStateDir.mkdir()).isTrue();
        assertThat(dataDir.mkdir()).isTrue();

        PackageManager packageManager = mApplication.getPackageManager();
        mShadowPackageManager = shadowOf(packageManager);

        mWakeLock = createBackupWakeLock(mApplication);

        Looper backupLooper = startBackupThreadAndGetLooper();
        mShadowBackupLooper = shadowOf(backupLooper);

        Handler mainHandler = new Handler(Looper.getMainLooper());
        BackupAgentTimeoutParameters agentTimeoutParameters =
                new BackupAgentTimeoutParameters(mainHandler, mApplication.getContentResolver());
        agentTimeoutParameters.start();

        // We need to mock BMS timeout parameters before initializing the BackupHandler since
        // the constructor of BackupHandler relies on the timeout parameters.
        when(mBackupManagerService.getAgentTimeoutParameters()).thenReturn(agentTimeoutParameters);
        mBackupHandler = new BackupHandler(mBackupManagerService, backupLooper);

        mBackupManager = spy(FakeIBackupManager.class);

        BackupManagerConstants constants =
                new BackupManagerConstants(mainHandler, mApplication.getContentResolver());
        constants.start();

        setUpBackupManagerServiceBasics(
                mBackupManagerService,
                mApplication,
                mTransportManager,
                packageManager,
                mBackupHandler,
                mWakeLock,
                agentTimeoutParameters);
        when(mBackupManagerService.getBaseStateDir()).thenReturn(mBaseStateDir);
        when(mBackupManagerService.getDataDir()).thenReturn(dataDir);
        when(mBackupManagerService.getBackupManagerBinder()).thenReturn(mBackupManager);
        when(mBackupManagerService.getConstants()).thenReturn(constants);
        when(mBackupManagerService.makeMetadataAgent()).thenAnswer(invocation -> createPmAgent());
    }

    @Test
    public void testRunTask_whenTransportProvidesFlags_passesThemToTheAgent() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(agentMock.agent)
                .onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_whenTransportDoesNotProvidesFlags() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(agentMock.agent).onBackup(any(), argThat(dataOutputWithTransportFlags(0)), any());
    }

    @Test
    public void testRunTask_whenTransportProvidesFlagsAndMultipleAgents_passesToAll()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        List<AgentMock> agentMocks = setUpAgents(PACKAGE_1, PACKAGE_2);
        BackupAgent agent1 = agentMocks.get(0).agent;
        BackupAgent agent2 = agentMocks.get(1).agent;
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(agent1).onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
        verify(agent2).onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_whenTransportChangeFlagsAfterTaskCreation() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);

        runTask(task);

        verify(agentMock.agent)
                .onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_callsListenerAndObserver() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgent(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(eq(BackupManager.SUCCESS));
    }

    @Test
    public void testRunTask_releasesWakeLock() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgent(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertThat(mWakeLock.isHeld()).isFalse();
    }

    @Test
    public void testRunTask_callsTransportPerformBackupWithAgentData() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock.agent,
                (oldState, dataOutput, newState) -> {
                    writeData(dataOutput, "key1", "foo".getBytes());
                    writeData(dataOutput, "key2", "bar".getBytes());
                });
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        // We need to verify at call time because the file is deleted right after
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .then(this::mockAndVerifyTransportPerformBackupData);

        runTask(task);

        // Already verified data in mockAndVerifyPerformBackupData
        verify(transportBinder).performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt());
    }

    private int mockAndVerifyTransportPerformBackupData(InvocationOnMock invocation)
            throws IOException {
        ParcelFileDescriptor data = invocation.getArgument(1);

        // Verifying that what we passed to the transport is what the agent wrote
        BackupDataInput dataInput = new BackupDataInput(data.getFileDescriptor());

        // "key1" => "foo"
        assertThat(dataInput.readNextHeader()).isTrue();
        assertThat(dataInput.getKey()).isEqualTo("key1");
        int size1 = dataInput.getDataSize();
        byte[] data1 = new byte[size1];
        dataInput.readEntityData(data1, 0, size1);
        assertThat(data1).isEqualTo("foo".getBytes());

        // "key2" => "bar"
        assertThat(dataInput.readNextHeader()).isTrue();
        assertThat(dataInput.getKey()).isEqualTo("key2");
        int size2 = dataInput.getDataSize();
        byte[] data2 = new byte[size2];
        dataInput.readEntityData(data2, 0, size2);
        assertThat(data2).isEqualTo("bar".getBytes());

        // No more
        assertThat(dataInput.readNextHeader()).isFalse();

        return BackupTransport.TRANSPORT_OK;
    }

    @Test
    public void testRunTask_whenPerformBackupSucceeds_callsTransportFinishBackup()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);

        runTask(task);

        verify(transportBinder).finishBackup();
    }

    @Test
    public void testRunTask_whenProhibitedKey_failsAgent() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock.agent,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "foo".getBytes());
                });
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        // TODO: Should it not call mListener.onFinished()? PerformBackupTask:891 return?
        // verify(mListener).onFinished(any());
        verify(mObserver).onResult(eq(PACKAGE_1), eq(BackupManager.ERROR_AGENT_FAILURE));
        verify(agentMock.agentBinder).fail(any());
    }

    @Test
    public void testRunTask_whenTransportUnavailable() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport.unavailable());
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(eq(BackupManager.ERROR_TRANSPORT_ABORTED));
    }

    @Test
    public void testRunTask_whenTransportRejectsPackage() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1, BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).backupFinished(BackupManager.SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportRejectsFirstPackageButLastSucceeds() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        setUpAgentsWithData(PACKAGE_1, PACKAGE_2);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_2)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1, BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).onResult(PACKAGE_2, BackupManager.SUCCESS);
        verify(mObserver).backupFinished(BackupManager.SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportRejectsLastPackageButFirstSucceeds() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        setUpAgentsWithData(PACKAGE_1, PACKAGE_2);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_2)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1, BackupManager.SUCCESS);
        verify(mObserver).onResult(PACKAGE_2, BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).backupFinished(BackupManager.SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportReturnsQuotaExceeded() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_QUOTA_EXCEEDED);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1, BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED);
        verify(mObserver).backupFinished(BackupManager.SUCCESS);
        verify(agentMock.agent).onQuotaExceeded(anyLong(), anyLong());
    }

    @Test
    public void testRunTask_whenAgentUnknown() throws Exception {
        // Not calling setUpAgent()
        TransportMock transportMock = setUpTransport(mTransport);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(transportMock.transport, never()).performBackup(any(), any(), anyInt());
        verify(mObserver).onResult(PACKAGE_1, BackupManager.ERROR_PACKAGE_NOT_FOUND);
        verify(mObserver).backupFinished(BackupManager.SUCCESS);
    }

    @Test
    public void testRunTask_whenNonIncrementalAndTransportRequestsNonIncremental()
            throws Exception {
        // It's going to be non-incremental because we haven't created any previous state
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        true,
                        PACKAGE_1);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1, BackupManager.ERROR_TRANSPORT_ABORTED);
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    @Test
    public void testRunTask_whenIncrementalAndTransportRequestsNonIncremental() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        IBackupTransport transport = transportMock.transport;
        when(transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)),
                        any(),
                        intThat(flags -> (flags & BackupTransport.FLAG_INCREMENTAL) != 0)))
                .thenReturn(BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED);
        when(transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)),
                        any(),
                        intThat(flags -> (flags & BackupTransport.FLAG_NON_INCREMENTAL) != 0)))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);
        // Write content to be incremental
        Files.write(
                Paths.get(mBaseStateDir.getAbsolutePath(), mTransport.transportDirName, PACKAGE_1),
                "existent".getBytes());

        runTask(task);

        verify(agentMock.agent, times(2)).onBackup(any(), any(), any());
        verify(mObserver).onResult(PACKAGE_1, BackupManager.SUCCESS);
        verify(mObserver).backupFinished(BackupManager.SUCCESS);
    }

    @Test
    public void testRunTask_whenQueueEmpty() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        verify(mObserver).backupFinished(eq(BackupManager.SUCCESS));
    }

    @Test
    public void testRunTask_whenIncrementalAndTransportUnavailableDuringPmBackup()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        setUpAgent(PACKAGE_1);
        when(transportBinder.getBackupQuota(
                        eq(BackupManagerService.PACKAGE_MANAGER_SENTINEL), anyBoolean()))
                .thenThrow(DeadObjectException.class);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(eq(BackupManager.ERROR_TRANSPORT_ABORTED));
    }

    @Test
    public void testRunTask_whenIncrementalAndAgentFails() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        createFakePmAgent();
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(eq(BackupManager.ERROR_TRANSPORT_ABORTED));
    }

    private void runTask(PerformBackupTask task) {
        Message message = mBackupHandler.obtainMessage(BackupHandler.MSG_BACKUP_RESTORE_STEP, task);
        mBackupHandler.sendMessage(message);
        while (mShadowBackupLooper.getScheduler().areAnyRunnable()) {
            mShadowBackupLooper.runToEndOfTasks();
        }
    }

    private TransportMock setUpTransport(TransportData transport) throws Exception {
        TransportMock transportMock =
                TransportTestUtils.setUpTransport(mTransportManager, transport);
        File stateDir = new File(mBaseStateDir, transport.transportDirName);
        assertThat(stateDir.mkdir()).isTrue();
        return transportMock;
    }

    private List<AgentMock> setUpAgents(String... packageNames) {
        return Stream.of(packageNames).map(this::setUpAgent).collect(toList());
    }

    private AgentMock setUpAgent(String packageName) {
        try {
            PackageInfo packageInfo = new PackageInfo();
            packageInfo.packageName = packageName;
            packageInfo.applicationInfo = new ApplicationInfo();
            packageInfo.applicationInfo.flags = ApplicationInfo.FLAG_ALLOW_BACKUP;
            packageInfo.applicationInfo.backupAgentName = "BackupAgent" + packageName;
            packageInfo.applicationInfo.packageName = packageName;
            mShadowPackageManager.setApplicationEnabledSetting(
                    packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
            mShadowPackageManager.addPackage(packageInfo);
            BackupAgent backupAgent = spy(BackupAgent.class);
            IBackupAgent backupAgentBinder =
                    spy(IBackupAgent.Stub.asInterface(backupAgent.onBind()));
            // Don't crash our only process (in production code this would crash the app, not us)
            doNothing().when(backupAgentBinder).fail(any());
            when(mBackupManagerService.bindToAgentSynchronous(
                            eq(packageInfo.applicationInfo), anyInt()))
                    .thenReturn(backupAgentBinder);
            return new AgentMock(backupAgentBinder, backupAgent);
        } catch (RemoteException e) {
            // Never happens, compiler happy
            throw new AssertionError(e);
        }
    }

    private List<AgentMock> setUpAgentsWithData(String... packageNames) {
        return Stream.of(packageNames).map(this::setUpAgentWithData).collect(toList());
    }

    private AgentMock setUpAgentWithData(String packageName) {
        AgentMock agentMock = setUpAgent(packageName);
        uncheck(
                () ->
                        agentOnBackupDo(
                                agentMock.agent,
                                (oldState, dataOutput, newState) ->
                                        writeData(dataOutput, "key", packageName.getBytes())));
        return agentMock;
    }

    private PerformBackupTask createPerformBackupTask(
            TransportClient transportClient, String transportDirName, String... packages) {
        return createPerformBackupTask(transportClient, transportDirName, true, packages);
    }

    private PerformBackupTask createPerformBackupTask(
            TransportClient transportClient,
            String transportDirName,
            boolean nonIncremental,
            String... packages) {
        ArrayList<BackupRequest> backupRequests =
                Stream.of(packages).map(BackupRequest::new).collect(toCollection(ArrayList::new));
        mWakeLock.acquire();
        PerformBackupTask task =
                new PerformBackupTask(
                        mBackupManagerService,
                        transportClient,
                        transportDirName,
                        backupRequests,
                        mDataChangedJournal,
                        mObserver,
                        mMonitor,
                        mListener,
                        emptyList(),
                        /* userInitiated */ false,
                        nonIncremental);
        mBackupManager.setUp(mBackupHandler, task);
        return task;
    }

    private PackageManagerBackupAgent createPmAgent() {
        PackageManagerBackupAgent pmAgent =
                new PackageManagerBackupAgent(mApplication.getPackageManager());
        pmAgent.attach(mApplication);
        pmAgent.onCreate();
        return pmAgent;
    }

    /**
     * Returns an implementation of PackageManagerBackupAgent that throws RuntimeException in {@link
     * BackupAgent#onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor)}
     */
    private PackageManagerBackupAgent createFakePmAgent() {
        PackageManagerBackupAgent fakePmAgent =
                new FakePackageManagerBackupAgent(mApplication.getPackageManager());
        fakePmAgent.attach(mApplication);
        fakePmAgent.onCreate();
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(fakePmAgent);
        return fakePmAgent;
    }

    /** Matches {@link PackageInfo} whose package name is {@code packageName}. */
    private static ArgumentMatcher<PackageInfo> packageInfo(String packageName) {
        // We have to test for packageInfo nulity because of Mockito's own stubbing with argThat().
        // E.g. if you do:
        //
        //   1. when(object.method(argThat(str -> str.equals("foo")))).thenReturn(0)
        //   2. when(object.method(argThat(str -> str.equals("bar")))).thenReturn(2)
        //
        // The second line will throw NPE because it will call lambda 1 with null, since argThat()
        // returns null. So we guard against that by checking for null.
        return packageInfo -> packageInfo != null && packageName.equals(packageInfo.packageName);
    }

    private static ArgumentMatcher<BackupDataOutput> dataOutputWithTransportFlags(int flags) {
        return dataOutput -> dataOutput.getTransportFlags() == flags;
    }

    private static void writeData(BackupDataOutput dataOutput, String key, byte[] data)
            throws IOException {
        dataOutput.writeEntityHeader(key, data.length);
        dataOutput.writeEntityData(data, data.length);
    }

    private static void agentOnBackupDo(BackupAgent agent, BackupAgentOnBackup function)
            throws Exception {
        doAnswer(function).when(agent).onBackup(any(), any(), any());
    }

    @FunctionalInterface
    private interface BackupAgentOnBackup extends Answer<Void> {
        void onBackup(
                ParcelFileDescriptor oldState,
                BackupDataOutput dataOutput,
                ParcelFileDescriptor newState)
                throws IOException;

        @Override
        default Void answer(InvocationOnMock invocation) throws Throwable {
            onBackup(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2));
            return null;
        }
    }

    private static class AgentMock {
        private final IBackupAgent agentBinder;
        private final BackupAgent agent;

        private AgentMock(IBackupAgent agentBinder, BackupAgent agent) {
            this.agentBinder = agentBinder;
            this.agent = agent;
        }
    }

    private abstract static class FakeIBackupManager extends IBackupManager.Stub {
        private Handler mBackupHandler;
        private BackupRestoreTask mTask;

        public FakeIBackupManager() {}

        private void setUp(Handler backupHandler, BackupRestoreTask task) {
            mBackupHandler = backupHandler;
            mTask = task;
        }

        @Override
        public void opComplete(int token, long result) throws RemoteException {
            assertThat(mTask).isNotNull();
            Message message =
                    mBackupHandler.obtainMessage(
                            BackupHandler.MSG_OP_COMPLETE, Pair.create(mTask, result));
            mBackupHandler.sendMessage(message);
        }
    }

    private static class FakePackageManagerBackupAgent extends PackageManagerBackupAgent {
        public FakePackageManagerBackupAgent(PackageManager packageMgr) {
            super(packageMgr);
        }

        @Override
        public void onBackup(
                ParcelFileDescriptor oldState,
                BackupDataOutput data,
                ParcelFileDescriptor newState) {
            throw new RuntimeException();
        }
    }
}
