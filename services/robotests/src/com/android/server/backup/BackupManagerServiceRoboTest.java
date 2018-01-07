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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.expectThrows;

import android.content.ContextWrapper;
import android.os.HandlerThread;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.testing.ShadowAppBackupUtils;
import com.android.server.backup.testing.TransportTestUtils;
import com.android.server.backup.testing.TransportTestUtils.TransportData;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderClasses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowLog;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 26,
    shadows = {ShadowAppBackupUtils.class}
)
@SystemLoaderClasses({RefactoredBackupManagerService.class, TransportManager.class})
@Presubmit
public class BackupManagerServiceRoboTest {
    private static final String TAG = "BMSTest";
    private static final String TRANSPORT_NAME =
            "com.google.android.gms/.backup.BackupTransportService";

    @Mock private TransportManager mTransportManager;
    private HandlerThread mBackupThread;
    private File mBaseStateDir;
    private File mDataDir;
    private RefactoredBackupManagerService mBackupManagerService;
    private ShadowContextWrapper mShadowContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mBackupThread = new HandlerThread("backup-test");
        mBackupThread.setUncaughtExceptionHandler(
                (t, e) -> ShadowLog.e(TAG, "Uncaught exception in test thread " + t.getName(), e));
        mBackupThread.start();

        ContextWrapper context = RuntimeEnvironment.application;
        mShadowContext = Shadows.shadowOf(context);

        File cacheDir = context.getCacheDir();
        mBaseStateDir = new File(cacheDir, "base_state_dir");
        mDataDir = new File(cacheDir, "data_dir");

        mBackupManagerService =
                new RefactoredBackupManagerService(
                        context,
                        new Trampoline(context),
                        mBackupThread,
                        mBaseStateDir,
                        mDataDir,
                        mTransportManager);
    }

    @After
    public void tearDown() throws Exception {
        mBackupThread.quit();
        ShadowAppBackupUtils.reset();
    }

    @Test
    public void testDestinationString() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(TRANSPORT_NAME)))
                .thenReturn("destinationString");

        String destination = mBackupManagerService.getDestinationString(TRANSPORT_NAME);

        assertThat(destination).isEqualTo("destinationString");
    }

    @Test
    public void testDestinationString_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(TRANSPORT_NAME)))
                .thenThrow(TransportNotRegisteredException.class);

        String destination = mBackupManagerService.getDestinationString(TRANSPORT_NAME);

        assertThat(destination).isNull();
    }

    @Test
    public void testDestinationString_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(TRANSPORT_NAME)))
                .thenThrow(TransportNotRegisteredException.class);

        expectThrows(
                SecurityException.class,
                () -> mBackupManagerService.getDestinationString(TRANSPORT_NAME));
    }

    @Test
    public void testIsAppEligibleForBackup_whenAppEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportData transport =
                TransportTestUtils.setUpCurrentTransport(mTransportManager, TRANSPORT_NAME);
        ShadowAppBackupUtils.sAppIsRunningAndEligibleForBackupWithTransport = p -> true;

        boolean result = mBackupManagerService.isAppEligibleForBackup("app.package");

        assertThat(result).isTrue();
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transport.transportClientMock), any());
    }

    @Test
    public void testIsAppEligibleForBackup_whenAppNotEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportTestUtils.setUpCurrentTransport(mTransportManager, TRANSPORT_NAME);
        ShadowAppBackupUtils.sAppIsRunningAndEligibleForBackupWithTransport = p -> false;

        boolean result = mBackupManagerService.isAppEligibleForBackup("app.package");

        assertThat(result).isFalse();
    }

    @Test
    public void testIsAppEligibleForBackup_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        TransportTestUtils.setUpCurrentTransport(mTransportManager, TRANSPORT_NAME);

        expectThrows(
                SecurityException.class,
                () -> mBackupManagerService.isAppEligibleForBackup("app.package"));
    }

    @Test
    public void testFilterAppsEligibleForBackup() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportData transport =
                TransportTestUtils.setUpCurrentTransport(mTransportManager, TRANSPORT_NAME);
        Map<String, Boolean> packagesMap = new HashMap<>();
        packagesMap.put("package.a", true);
        packagesMap.put("package.b", false);
        ShadowAppBackupUtils.sAppIsRunningAndEligibleForBackupWithTransport = packagesMap::get;
        String[] packages = packagesMap.keySet().toArray(new String[packagesMap.size()]);

        String[] filtered = mBackupManagerService.filterAppsEligibleForBackup(packages);

        assertThat(filtered).asList().containsExactly("package.a");
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transport.transportClientMock), any());
    }

    @Test
    public void testFilterAppsEligibleForBackup_whenNoneIsEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        ShadowAppBackupUtils.sAppIsRunningAndEligibleForBackupWithTransport = p -> false;

        String[] filtered =
                mBackupManagerService.filterAppsEligibleForBackup(
                        new String[] {"package.a", "package.b"});

        assertThat(filtered).isEmpty();
    }

    @Test
    public void testFilterAppsEligibleForBackup_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        TransportTestUtils.setUpCurrentTransport(mTransportManager, TRANSPORT_NAME);

        expectThrows(
                SecurityException.class,
                () ->
                        mBackupManagerService.filterAppsEligibleForBackup(
                                new String[] {"package.a", "package.b"}));
    }
}
