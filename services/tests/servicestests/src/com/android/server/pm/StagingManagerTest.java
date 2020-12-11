/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.storage.StorageManager;
import android.platform.test.annotations.Presubmit;

import com.android.internal.os.BackgroundThread;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

@Presubmit
@RunWith(JUnit4.class)
public class StagingManagerTest {
    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private File mTmpDir;
    private StagingManager mStagingManager;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        StorageManager storageManager = Mockito.mock(StorageManager.class);
        Context context = Mockito.mock(Context.class);
        when(storageManager.isCheckpointSupported()).thenReturn(true);
        when(context.getSystemService(eq(Context.POWER_SERVICE))).thenReturn(null);
        when(context.getSystemService(eq(Context.STORAGE_SERVICE))).thenReturn(storageManager);

        mTmpDir = mTemporaryFolder.newFolder("StagingManagerTest");
        mStagingManager = new StagingManager(context, null);
    }

    /**
     * Tests that sessions committed later shouldn't cause earlier ones to fail the overlapping
     * check.
     */
    @Test
    public void checkNonOverlappingWithStagedSessions_laterSessionShouldNotFailEarlierOnes()
            throws Exception {
        // Create 2 sessions with overlapping packages
        StagingManager.StagedSession session1 = createSession(111, "com.foo", 1);
        StagingManager.StagedSession session2 = createSession(222, "com.foo", 2);

        mStagingManager.createSession(session1);
        mStagingManager.createSession(session2);
        // Session1 should not fail in spite of the overlapping packages
        mStagingManager.checkNonOverlappingWithStagedSessions(session1);
        // Session2 should fail due to overlapping packages
        assertThrows(PackageManagerException.class,
                () -> mStagingManager.checkNonOverlappingWithStagedSessions(session2));
    }

    private StagingManager.StagedSession createSession(int sessionId, String packageName,
            long committedMillis) {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.isStaged = true;

        InstallSource installSource = InstallSource.create("testInstallInitiator",
                "testInstallOriginator", "testInstaller", "testAttributionTag");

        PackageInstallerSession session = new PackageInstallerSession(
                /* callback */ null,
                /* context */ null,
                /* pm */ null,
                /* sessionProvider */ null,
                /* looper */ BackgroundThread.getHandler().getLooper(),
                /* stagingManager */ null,
                /* sessionId */ sessionId,
                /* userId */ 456,
                /* installerUid */ -1,
                /* installSource */ installSource,
                /* sessionParams */ params,
                /* createdMillis */ 0L,
                /* committedMillis */ committedMillis,
                /* stageDir */ mTmpDir,
                /* stageCid */ null,
                /* files */ null,
                /* checksums */ null,
                /* prepared */ true,
                /* committed */ true,
                /* destroyed */ false,
                /* sealed */ false,  // Setting to true would trigger some PM logic.
                /* childSessionIds */ null,
                /* parentSessionId */ -1,
                /* isReady */ false,
                /* isFailed */ false,
                /* isApplied */false,
                /* stagedSessionErrorCode */ PackageInstaller.SessionInfo.STAGED_SESSION_NO_ERROR,
                /* stagedSessionErrorMessage */ "no error");

        StagingManager.StagedSession stagedSession = spy(session.mStagedSession);
        doReturn(packageName).when(stagedSession).getPackageName();
        doAnswer(invocation -> {
            Predicate<StagingManager.StagedSession> filter = invocation.getArgument(0);
            return filter.test(stagedSession);
        }).when(stagedSession).sessionContains(any());
        return stagedSession;
    }
}
