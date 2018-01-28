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

import static com.android.server.backup.testing.TransportData.backupTransport;
import static com.android.server.backup.testing.TransportTestUtils.setUpTransport;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

import android.app.Application;
import android.app.IActivityManager;
import android.app.IBackupAgent;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.internal.BackupRequest;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.internal.PerformBackupTask;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.transport.TransportClient;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderClasses;
import com.android.server.testing.shadows.FrameworkShadowPackageManager;
import com.android.server.testing.shadows.ShadowBackupDataInput;
import com.android.server.testing.shadows.ShadowBackupDataOutput;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowQueuedWork;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 26,
    shadows = {
        FrameworkShadowPackageManager.class,
        ShadowBackupDataInput.class,
        ShadowBackupDataOutput.class,
        ShadowQueuedWork.class
    }
)
@SystemLoaderClasses({
    BackupManagerService.class,
    PerformBackupTask.class,
    BackupDataOutput.class,
    FullBackupDataOutput.class,
    TransportManager.class,
    BackupAgent.class,
    IBackupTransport.class,
    IBackupAgent.class,
    PackageInfo.class
})
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
    private IBackupTransport mTransportBinder;
    private TransportClient mTransportClient;
    private ShadowLooper mShadowBackupLooper;
    private BackupHandler mBackupHandler;
    private PowerManager.WakeLock mWakeLock;
    private ShadowPackageManager mShadowPackageManager;
    private FakeIBackupManager mBackupManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTransport = backupTransport();
        TransportMock transportMock = setUpTransport(mTransportManager, mTransport);
        mTransportBinder = transportMock.transport;
        mTransportClient = transportMock.transportClient;

        Application application = RuntimeEnvironment.application;
        File cacheDir = application.getCacheDir();
        File baseStateDir = new File(cacheDir, "base_state_dir");
        File dataDir = new File(cacheDir, "data_dir");
        File stateDir = new File(baseStateDir, mTransport.transportDirName);
        assertThat(baseStateDir.mkdir()).isTrue();
        assertThat(dataDir.mkdir()).isTrue();
        assertThat(stateDir.mkdir()).isTrue();

        PackageManager packageManager = application.getPackageManager();
        mShadowPackageManager = Shadow.extract(packageManager);

        PowerManager powerManager =
                (PowerManager) application.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*backup*");

        // Robolectric simulates multi-thread in a single-thread to avoid flakiness
        HandlerThread backupThread = new HandlerThread("backup");
        backupThread.setUncaughtExceptionHandler(
                (t, e) -> fail("Uncaught exception " + e.getMessage()));
        backupThread.start();
        Looper backupLooper = backupThread.getLooper();
        mShadowBackupLooper = shadowOf(backupLooper);
        mBackupHandler = new BackupHandler(mBackupManagerService, backupLooper);

        mBackupManager = spy(FakeIBackupManager.class);

        when(mBackupManagerService.getTransportManager()).thenReturn(mTransportManager);
        when(mBackupManagerService.getContext()).thenReturn(application);
        when(mBackupManagerService.getPackageManager()).thenReturn(packageManager);
        when(mBackupManagerService.getWakelock()).thenReturn(mWakeLock);
        when(mBackupManagerService.getCurrentOpLock()).thenReturn(new Object());
        when(mBackupManagerService.getQueueLock()).thenReturn(new Object());
        when(mBackupManagerService.getBaseStateDir()).thenReturn(baseStateDir);
        when(mBackupManagerService.getDataDir()).thenReturn(dataDir);
        when(mBackupManagerService.getCurrentOperations()).thenReturn(new SparseArray<>());
        when(mBackupManagerService.getBackupHandler()).thenReturn(mBackupHandler);
        when(mBackupManagerService.getBackupManagerBinder()).thenReturn(mBackupManager);
        when(mBackupManagerService.getActivityManager()).thenReturn(mock(IActivityManager.class));
    }

    @Test
    public void testRunTask_whenTransportProvidesFlags_passesThemToTheAgent() throws Exception {
        BackupAgent agent = setUpAgent(PACKAGE_1);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(mTransportBinder.getTransportFlags()).thenReturn(flags);
        PerformBackupTask task = createPerformBackupTask(emptyList(), false, true, PACKAGE_1);

        runTask(task);

        verify(agent).onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_whenTransportDoesNotProvidesFlags() throws Exception {
        BackupAgent agent = setUpAgent(PACKAGE_1);
        PerformBackupTask task = createPerformBackupTask(emptyList(), false, true, PACKAGE_1);

        runTask(task);

        verify(agent).onBackup(any(), argThat(dataOutputWithTransportFlags(0)), any());
    }

    @Test
    public void testRunTask_whenTransportProvidesFlagsAndMultipleAgents_passesToAll()
            throws Exception {
        List<BackupAgent> agents = setUpAgents(PACKAGE_1, PACKAGE_2);
        BackupAgent agent1 = agents.get(0);
        BackupAgent agent2 = agents.get(1);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(mTransportBinder.getTransportFlags()).thenReturn(flags);
        PerformBackupTask task =
                createPerformBackupTask(emptyList(), false, true, PACKAGE_1, PACKAGE_2);

        runTask(task);

        verify(agent1).onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
        verify(agent2).onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_whenTransportChangeFlagsAfterTaskCreation() throws Exception {
        BackupAgent agent = setUpAgent(PACKAGE_1);
        PerformBackupTask task = createPerformBackupTask(emptyList(), false, true, PACKAGE_1);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(mTransportBinder.getTransportFlags()).thenReturn(flags);

        runTask(task);

        verify(agent).onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    private void runTask(PerformBackupTask task) {
        Message message = mBackupHandler.obtainMessage(BackupHandler.MSG_BACKUP_RESTORE_STEP, task);
        mBackupHandler.sendMessage(message);
        while (mShadowBackupLooper.getScheduler().areAnyRunnable()) {
            mShadowBackupLooper.runToEndOfTasks();
        }
    }

    private List<BackupAgent> setUpAgents(String... packageNames) {
        return Stream.of(packageNames).map(this::setUpAgent).collect(toList());
    }

    private BackupAgent setUpAgent(String packageName) {
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
        IBackupAgent backupAgentBinder = IBackupAgent.Stub.asInterface(backupAgent.onBind());
        when(mBackupManagerService.bindToAgentSynchronous(
                        eq(packageInfo.applicationInfo), anyInt()))
                .thenReturn(backupAgentBinder);
        return backupAgent;
    }

    private PerformBackupTask createPerformBackupTask(
            List<String> pendingFullBackups,
            boolean userInitiated,
            boolean nonIncremental,
            String... packages) {
        ArrayList<BackupRequest> backupRequests =
                Stream.of(packages).map(BackupRequest::new).collect(toCollection(ArrayList::new));
        mWakeLock.acquire();
        PerformBackupTask task =
                new PerformBackupTask(
                        mBackupManagerService,
                        mTransportClient,
                        mTransport.transportDirName,
                        backupRequests,
                        mDataChangedJournal,
                        mObserver,
                        mMonitor,
                        mListener,
                        pendingFullBackups,
                        userInitiated,
                        nonIncremental);
        mBackupManager.setUp(mBackupHandler, task);
        return task;
    }

    private ArgumentMatcher<BackupDataOutput> dataOutputWithTransportFlags(int flags) {
        return dataOutput -> dataOutput.getTransportFlags() == flags;
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
}
