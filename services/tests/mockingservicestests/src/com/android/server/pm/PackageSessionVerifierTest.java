/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.function.Predicate;

@Presubmit
@RunWith(JUnit4.class)
public class PackageSessionVerifierTest {
    private PackageSessionVerifier mSessionVerifier = new PackageSessionVerifier();

    @Test
    public void checkRebootlessApex() throws Exception {
        StagingManager.StagedSession session1 = createStagedSession(111, "com.foo", 1);
        mSessionVerifier.storeSession(session1);

        // Should throw for package name conflicts
        PackageInstallerSession session2 = createSession(false, true, "com.foo");
        assertThrows(PackageManagerException.class,
                () -> mSessionVerifier.checkRebootlessApex(session2));

        // Shouldn't throw if no package name conflicts
        PackageInstallerSession session3 = createSession(false, true, "com.bar");
        mSessionVerifier.checkRebootlessApex(session3);
    }

    @Test
    public void checkActiveSessions() throws Exception {
        StagingManager.StagedSession session1 = createStagedSession(111, "com.foo", 1);
        mSessionVerifier.storeSession(session1);
        // Shouldn't throw for a single session no matter if supporting checkpoint or not
        mSessionVerifier.checkActiveSessions(true);
        mSessionVerifier.checkActiveSessions(false);

        // Now we have multiple active sessions
        StagingManager.StagedSession session2 = createStagedSession(222, "com.bar", 2);
        mSessionVerifier.storeSession(session2);
        // Shouldn't throw if supporting checkpoint
        mSessionVerifier.checkActiveSessions(true);
        // Should throw if not supporting checkpoint
        assertThrows(PackageManagerException.class,
                () -> mSessionVerifier.checkActiveSessions(false));
    }

    @Test
    public void checkRollbacks() throws Exception {
        StagingManager.StagedSession session1 = createStagedSession(111, "com.foo", 1);
        StagingManager.StagedSession session2 = createStagedSession(222, "com.bar", 2);
        StagingManager.StagedSession session3 = createStagedSession(333, "com.baz", 3);
        session2.sessionParams().setInstallReason(PackageManager.INSTALL_REASON_ROLLBACK);
        session3.sessionParams().setInstallReason(PackageManager.INSTALL_REASON_ROLLBACK);
        when(session2.isDestroyed()).thenReturn(true);
        mSessionVerifier.storeSession(session1);
        mSessionVerifier.storeSession(session2);
        mSessionVerifier.storeSession(session3);

        // Non-rollback session shouldn't be failed by a destroyed session
        mSessionVerifier.checkRollbacks(session2);
        verify(session1, never()).setSessionFailed(anyInt(), anyString());

        // Non-rollback session should fail
        mSessionVerifier.checkRollbacks(session3);
        verify(session1, times(1)).setSessionFailed(anyInt(), anyString());

        // Yet another non-rollback session should fail
        StagingManager.StagedSession session4 = createStagedSession(444, "com.fur", 4);
        assertThrows(PackageManagerException.class,
                () -> mSessionVerifier.checkRollbacks(session4));
    }

    @Test
    public void checkOverlaps() throws Exception {
        StagingManager.StagedSession session1 = createStagedSession(111, "com.foo", 1);
        StagingManager.StagedSession session2 = createStagedSession(222, "com.foo", 2);
        mSessionVerifier.storeSession(session1);
        mSessionVerifier.storeSession(session2);
        // No exception should be thrown for the earlier session should not fail
        mSessionVerifier.checkOverlaps(session1, session1);
        // Later session should fail
        verify(session2, times(1)).setSessionFailed(anyInt(), anyString());
        // Yet another later session should fail
        StagingManager.StagedSession session3 = createStagedSession(333, "com.foo", 3);
        assertThrows(PackageManagerException.class,
                () -> mSessionVerifier.checkOverlaps(session3, session3));
        // session4 is earlier than session1, but it shouldn't fail session1
        StagingManager.StagedSession session4 = createStagedSession(444, "com.foo", 0);
        when(session4.isDestroyed()).thenReturn(true);
        mSessionVerifier.checkOverlaps(session4, session4);
        verify(session1, never()).setSessionFailed(anyInt(), anyString());
    }

    private PackageInstallerSession createSession(boolean isStaged, boolean isApex,
            String packageName) {
        PackageInstallerSession session = mock(PackageInstallerSession.class);
        when(session.isStaged()).thenReturn(isStaged);
        when(session.isApexSession()).thenReturn(isApex);
        when(session.getPackageName()).thenReturn(packageName);
        return session;
    }

    private StagingManager.StagedSession createStagedSession(int sessionId, String packageName,
            long committedMillis) {
        StagingManager.StagedSession session = mock(StagingManager.StagedSession.class);
        when(session.sessionId()).thenReturn(sessionId);
        when(session.getPackageName()).thenReturn(packageName);
        when(session.getCommittedMillis()).thenReturn(committedMillis);
        when(session.sessionContains(any())).then(invocation -> {
            Predicate<StagingManager.StagedSession> filter = invocation.getArgument(0);
            return filter.test(session);
        });
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        when(session.sessionParams()).thenReturn(params);
        return session;
    }
}
