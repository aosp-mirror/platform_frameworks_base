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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.apex.ApexInfo;
import android.apex.ApexSessionInfo;
import android.apex.ApexSessionParams;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.ApexStagedEvent;
import android.content.pm.IStagedApexObserver;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionInfo.StagedSessionErrorCode;
import android.content.pm.StagedApexInfo;
import android.os.Message;
import android.os.SystemProperties;
import android.os.storage.IStorageManager;
import android.platform.test.annotations.Presubmit;
import android.util.IntArray;
import android.util.SparseArray;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

@Presubmit
@RunWith(JUnit4.class)
public class StagingManagerTest {
    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Mock private Context mContext;
    @Mock private IStorageManager mStorageManager;
    @Mock private ApexManager mApexManager;
    @Mock private PackageManagerService mMockPackageManagerInternal;

    private File mTmpDir;
    private StagingManager mStagingManager;

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(eq(Context.POWER_SERVICE))).thenReturn(null);

        mMockitoSession = ExtendedMockito.mockitoSession()
                    .strictness(Strictness.LENIENT)
                    .mockStatic(SystemProperties.class)
                    .mockStatic(PackageHelper.class)
                    .startMocking();

        when(mStorageManager.supportsCheckpoint()).thenReturn(true);
        when(mStorageManager.needsCheckpoint()).thenReturn(true);
        when(PackageHelper.getStorageManager()).thenReturn(mStorageManager);

        when(SystemProperties.get(eq("ro.apex.updatable"))).thenReturn("true");
        when(SystemProperties.get(eq("ro.apex.updatable"), anyString())).thenReturn("true");

        mTmpDir = mTemporaryFolder.newFolder("StagingManagerTest");
        mStagingManager = new StagingManager(mContext, null, mApexManager);
    }

    @After
    public void tearDown() throws Exception {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
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
        // setSessionFailed() should've been called when doing overlapping checks on session1
        verify(session2, times(1)).setSessionFailed(anyInt(), anyString());

        // Yet another session with overlapping packages
        StagingManager.StagedSession session3 = createSession(333, "com.foo", 3);
        mStagingManager.createSession(session3);
        assertThrows(PackageManagerException.class,
                () -> mStagingManager.checkNonOverlappingWithStagedSessions(session3));
        verify(session3, never()).setSessionFailed(anyInt(), anyString());
    }

    @Test
    public void restoreSessions_nonParentSession_throwsIAE() throws Exception {
        FakeStagedSession session = new FakeStagedSession(239);
        session.setParentSessionId(1543);

        assertThrows(IllegalArgumentException.class,
                () -> mStagingManager.restoreSessions(Arrays.asList(session), false));
    }

    @Test
    public void restoreSessions_nonCommittedSession_throwsIAE() throws Exception {
        FakeStagedSession session = new FakeStagedSession(239);

        assertThrows(IllegalArgumentException.class,
                () -> mStagingManager.restoreSessions(Arrays.asList(session), false));
    }

    @Test
    public void restoreSessions_terminalSession_throwsIAE() throws Exception {
        FakeStagedSession session = new FakeStagedSession(239);
        session.setCommitted(true);
        session.setSessionApplied();

        assertThrows(IllegalArgumentException.class,
                () -> mStagingManager.restoreSessions(Arrays.asList(session), false));
    }

    @Test
    public void restoreSessions_deviceUpgrading_failsAllSessions() throws Exception {
        FakeStagedSession session1 = new FakeStagedSession(37);
        session1.setCommitted(true);
        FakeStagedSession session2 = new FakeStagedSession(57);
        session2.setCommitted(true);

        mStagingManager.restoreSessions(Arrays.asList(session1, session2), true);

        assertThat(session1.getErrorCode()).isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(session1.getErrorMessage()).isEqualTo("Build fingerprint has changed");

        assertThat(session2.getErrorCode()).isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(session2.getErrorMessage()).isEqualTo("Build fingerprint has changed");
    }

    @Test
    public void restoreSessions_multipleSessions_deviceWithoutFsCheckpointSupport_throwISE()
            throws Exception {
        FakeStagedSession session1 = new FakeStagedSession(37);
        session1.setCommitted(true);
        FakeStagedSession session2 = new FakeStagedSession(57);
        session2.setCommitted(true);

        when(mStorageManager.supportsCheckpoint()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> mStagingManager.restoreSessions(Arrays.asList(session1, session2), false));
    }

    @Test
    public void restoreSessions_handlesDestroyedAndNotReadySessions() throws Exception {
        FakeStagedSession destroyedApkSession = new FakeStagedSession(23);
        destroyedApkSession.setCommitted(true);
        destroyedApkSession.setDestroyed(true);

        FakeStagedSession destroyedApexSession = new FakeStagedSession(37);
        destroyedApexSession.setCommitted(true);
        destroyedApexSession.setDestroyed(true);
        destroyedApexSession.setIsApex(true);

        FakeStagedSession nonReadyApkSession = new FakeStagedSession(57);
        nonReadyApkSession.setCommitted(true);

        FakeStagedSession nonReadyApexSession = new FakeStagedSession(73);
        nonReadyApexSession.setCommitted(true);
        nonReadyApexSession.setIsApex(true);

        FakeStagedSession destroyedNonReadySession = new FakeStagedSession(101);
        destroyedNonReadySession.setCommitted(true);
        destroyedNonReadySession.setDestroyed(true);

        FakeStagedSession regularApkSession = new FakeStagedSession(239);
        regularApkSession.setCommitted(true);
        regularApkSession.setSessionReady();

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(destroyedApkSession);
        sessions.add(destroyedApexSession);
        sessions.add(nonReadyApkSession);
        sessions.add(nonReadyApexSession);
        sessions.add(destroyedNonReadySession);
        sessions.add(regularApkSession);

        mStagingManager.restoreSessions(sessions, false);

        assertThat(sessions).containsExactly(regularApkSession);
        assertThat(destroyedApkSession.isDestroyed()).isTrue();
        assertThat(destroyedApexSession.isDestroyed()).isTrue();
        assertThat(destroyedNonReadySession.isDestroyed()).isTrue();

        mStagingManager.onBootCompletedBroadcastReceived();
        assertThat(nonReadyApkSession.hasVerificationStarted()).isTrue();
        assertThat(nonReadyApexSession.hasVerificationStarted()).isTrue();
    }

    @Test
    public void restoreSessions_unknownApexSession_failsAllSessions() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession = new FakeStagedSession(1543);
        apexSession.setCommitted(true);
        apexSession.setIsApex(true);
        apexSession.setSessionReady();

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession);

        when(mApexManager.getSessions()).thenReturn(new SparseArray<>());
        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint wasn't aborted.
        verify(mStorageManager, never()).abortChanges(eq("abort-staged-install"), eq(false));

        assertThat(apexSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession.getErrorMessage()).isEqualTo("apexd did not know anything about a "
                + "staged session supposed to be activated");

        assertThat(apkSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apkSession.getErrorMessage()).isEqualTo("Another apex session failed");
    }

    @Test
    public void restoreSessions_failedApexSessions_failsAllSessions() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession1 = new FakeStagedSession(1543);
        apexSession1.setCommitted(true);
        apexSession1.setIsApex(true);
        apexSession1.setSessionReady();

        FakeStagedSession apexSession2 = new FakeStagedSession(101);
        apexSession2.setCommitted(true);
        apexSession2.setIsApex(true);
        apexSession2.setSessionReady();

        FakeStagedSession apexSession3 = new FakeStagedSession(57);
        apexSession3.setCommitted(true);
        apexSession3.setIsApex(true);
        apexSession3.setSessionReady();

        ApexSessionInfo activationFailed = new ApexSessionInfo();
        activationFailed.sessionId = 1543;
        activationFailed.isActivationFailed = true;
        activationFailed.errorMessage = "Failed for test";

        ApexSessionInfo staged = new ApexSessionInfo();
        staged.sessionId = 101;
        staged.isStaged = true;

        SparseArray<ApexSessionInfo> apexdSessions = new SparseArray<>();
        apexdSessions.put(1543, activationFailed);
        apexdSessions.put(101, staged);
        when(mApexManager.getSessions()).thenReturn(apexdSessions);

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession1);
        sessions.add(apexSession2);
        sessions.add(apexSession3);

        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint wasn't aborted.
        verify(mStorageManager, never()).abortChanges(eq("abort-staged-install"), eq(false));

        assertThat(apexSession1.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession1.getErrorMessage()).isEqualTo("APEX activation failed. "
                + "Error: Failed for test");

        assertThat(apexSession2.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession2.getErrorMessage()).isEqualTo("Staged session 101 at boot didn't "
                + "activate nor fail. Marking it as failed anyway.");

        assertThat(apexSession3.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession3.getErrorMessage()).isEqualTo("apexd did not know anything about a "
                + "staged session supposed to be activated");

        assertThat(apkSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apkSession.getErrorMessage()).isEqualTo("Another apex session failed");
    }

    @Test
    public void restoreSessions_stagedApexSession_failsAllSessions() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession = new FakeStagedSession(1543);
        apexSession.setCommitted(true);
        apexSession.setIsApex(true);
        apexSession.setSessionReady();

        ApexSessionInfo staged = new ApexSessionInfo();
        staged.sessionId = 1543;
        staged.isStaged = true;

        SparseArray<ApexSessionInfo> apexdSessions = new SparseArray<>();
        apexdSessions.put(1543, staged);
        when(mApexManager.getSessions()).thenReturn(apexdSessions);

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession);

        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint wasn't aborted.
        verify(mStorageManager, never()).abortChanges(eq("abort-staged-install"), eq(false));

        assertThat(apexSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession.getErrorMessage()).isEqualTo("Staged session 1543 at boot didn't "
                + "activate nor fail. Marking it as failed anyway.");

        assertThat(apkSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apkSession.getErrorMessage()).isEqualTo("Another apex session failed");
    }

    @Test
    public void restoreSessions_failedAndActivatedApexSessions_abortsCheckpoint() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession1 = new FakeStagedSession(1543);
        apexSession1.setCommitted(true);
        apexSession1.setIsApex(true);
        apexSession1.setSessionReady();

        FakeStagedSession apexSession2 = new FakeStagedSession(101);
        apexSession2.setCommitted(true);
        apexSession2.setIsApex(true);
        apexSession2.setSessionReady();

        FakeStagedSession apexSession3 = new FakeStagedSession(57);
        apexSession3.setCommitted(true);
        apexSession3.setIsApex(true);
        apexSession3.setSessionReady();

        FakeStagedSession apexSession4 = new FakeStagedSession(37);
        apexSession4.setCommitted(true);
        apexSession4.setIsApex(true);
        apexSession4.setSessionReady();

        ApexSessionInfo activationFailed = new ApexSessionInfo();
        activationFailed.sessionId = 1543;
        activationFailed.isActivationFailed = true;

        ApexSessionInfo activated = new ApexSessionInfo();
        activated.sessionId = 101;
        activated.isActivated = true;

        ApexSessionInfo staged = new ApexSessionInfo();
        staged.sessionId = 57;
        staged.isActivationFailed = true;

        SparseArray<ApexSessionInfo> apexdSessions = new SparseArray<>();
        apexdSessions.put(1543, activationFailed);
        apexdSessions.put(101, activated);
        apexdSessions.put(57, staged);
        when(mApexManager.getSessions()).thenReturn(apexdSessions);

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession1);
        sessions.add(apexSession2);
        sessions.add(apexSession3);
        sessions.add(apexSession4);

        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint was aborted.
        verify(mStorageManager, times(1)).abortChanges(eq("abort-staged-install"), eq(false));
    }

    @Test
    public void restoreSessions_apexSessionInImpossibleState_failsAllSessions() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession = new FakeStagedSession(1543);
        apexSession.setCommitted(true);
        apexSession.setIsApex(true);
        apexSession.setSessionReady();

        ApexSessionInfo impossible  = new ApexSessionInfo();
        impossible.sessionId = 1543;

        SparseArray<ApexSessionInfo> apexdSessions = new SparseArray<>();
        apexdSessions.put(1543, impossible);
        when(mApexManager.getSessions()).thenReturn(apexdSessions);

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession);

        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint wasn't aborted.
        verify(mStorageManager, never()).abortChanges(eq("abort-staged-install"), eq(false));

        assertThat(apexSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession.getErrorMessage()).isEqualTo("Impossible state");

        assertThat(apkSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apkSession.getErrorMessage()).isEqualTo("Another apex session failed");
    }

    @Test
    public void getSessionIdByPackageName() throws Exception {
        FakeStagedSession session = new FakeStagedSession(239);
        session.setCommitted(true);
        session.setSessionReady();
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(239);
    }

    @Test
    public void getSessionIdByPackageName_appliedSession_ignores() throws Exception {
        FakeStagedSession session = new FakeStagedSession(37);
        session.setCommitted(true);
        session.setSessionApplied();
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(-1);
    }

    @Test
    public void getSessionIdByPackageName_failedSession_ignores() throws Exception {
        FakeStagedSession session = new FakeStagedSession(73);
        session.setCommitted(true);
        session.setSessionFailed(1, "whatevs");
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(-1);
    }

    @Test
    public void getSessionIdByPackageName_destroyedSession_ignores() throws Exception {
        FakeStagedSession session = new FakeStagedSession(23);
        session.setCommitted(true);
        session.setDestroyed(true);
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(-1);
    }

    @Test
    public void getSessionIdByPackageName_noSessions() throws Exception {
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(-1);
    }

    @Test
    public void getSessionIdByPackageName_noSessionHasThisPackage() throws Exception {
        FakeStagedSession session = new FakeStagedSession(37);
        session.setCommitted(true);
        session.setSessionApplied();
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.bar")).isEqualTo(-1);
    }

    @Test
    public void getStagedApexInfos_validatePreConditions() throws Exception {
        // Invalid session: null session
        {
            // Call and verify
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> mStagingManager.getStagedApexInfos(null));
            assertThat(thrown).hasMessageThat().contains("Session is null");
        }
        // Invalid session: has parent
        {
            FakeStagedSession session = new FakeStagedSession(241);
            session.setParentSessionId(239);
            session.setSessionReady();
            // Call and verify
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> mStagingManager.getStagedApexInfos(session));
            assertThat(thrown).hasMessageThat().contains("241 session has parent");
        }

        // Invalid session: does not contain apex
        {
            FakeStagedSession session = new FakeStagedSession(241);
            session.setSessionReady();
            // Call and verify
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> mStagingManager.getStagedApexInfos(session));
            assertThat(thrown).hasMessageThat().contains("241 session does not contain apex");
        }
        // Invalid session: not ready
        {
            FakeStagedSession session = new FakeStagedSession(239);
            session.setIsApex(true);
            // Call and verify
            Map<String, ApexInfo> result = mStagingManager.getStagedApexInfos(session);
            assertThat(result).isEmpty();
        }
        // Invalid session: destroyed
        {
            FakeStagedSession session = new FakeStagedSession(240);
            session.setSessionReady();
            session.setIsApex(true);
            session.setDestroyed(true);
            // Call and verify
            Map<String, ApexInfo> result = mStagingManager.getStagedApexInfos(session);
            assertThat(result).isEmpty();
        }
    }

    private ApexInfo[] getFakeApexInfo(List<String> moduleNames) {
        List<ApexInfo> result = new ArrayList<>();
        for (String moduleName : moduleNames) {
            ApexInfo info = new ApexInfo();
            info.moduleName = moduleName;
            result.add(info);
        }
        return result.toArray(new ApexInfo[0]);
    }

    @Test
    public void getStagedApexInfos_nonParentSession() throws Exception {
        FakeStagedSession validSession = new FakeStagedSession(239);
        validSession.setIsApex(true);
        validSession.setSessionReady();
        ApexInfo[] fakeApexInfos = getFakeApexInfo(Arrays.asList("module1"));
        when(mApexManager.getStagedApexInfos(any())).thenReturn(fakeApexInfos);

        // Call and verify
        Map<String, ApexInfo> result = mStagingManager.getStagedApexInfos(validSession);
        assertThat(result).containsExactly(fakeApexInfos[0].moduleName, fakeApexInfos[0]);

        ArgumentCaptor<ApexSessionParams> argumentCaptor =
                ArgumentCaptor.forClass(ApexSessionParams.class);
        verify(mApexManager, times(1)).getStagedApexInfos(argumentCaptor.capture());
        ApexSessionParams params = argumentCaptor.getValue();
        assertThat(params.sessionId).isEqualTo(239);
    }

    @Test
    public void getStagedApexInfos_parentSession() throws Exception {
        FakeStagedSession childSession1 = new FakeStagedSession(201);
        childSession1.setIsApex(true);
        FakeStagedSession childSession2 = new FakeStagedSession(202);
        childSession2.setIsApex(true);
        FakeStagedSession nonApexChild = new FakeStagedSession(203);
        FakeStagedSession parentSession = new FakeStagedSession(239,
                Arrays.asList(childSession1, childSession2, nonApexChild));
        parentSession.setSessionReady();
        ApexInfo[] fakeApexInfos = getFakeApexInfo(Arrays.asList("module1", "module2"));
        when(mApexManager.getStagedApexInfos(any())).thenReturn(fakeApexInfos);

        // Call and verify
        Map<String, ApexInfo> result = mStagingManager.getStagedApexInfos(parentSession);
        assertThat(result).containsExactly(fakeApexInfos[0].moduleName, fakeApexInfos[0],
                fakeApexInfos[1].moduleName, fakeApexInfos[1]);

        ArgumentCaptor<ApexSessionParams> argumentCaptor =
                ArgumentCaptor.forClass(ApexSessionParams.class);
        verify(mApexManager, times(1)).getStagedApexInfos(argumentCaptor.capture());
        ApexSessionParams params = argumentCaptor.getValue();
        assertThat(params.sessionId).isEqualTo(239);
        assertThat(params.childSessionIds).asList().containsExactly(201, 202);
    }

    @Test
    public void getStagedApexModuleNames_returnsStagedApexModules() throws Exception {
        FakeStagedSession validSession1 = new FakeStagedSession(239);
        validSession1.setIsApex(true);
        validSession1.setSessionReady();
        mStagingManager.createSession(validSession1);

        FakeStagedSession childSession1 = new FakeStagedSession(123);
        childSession1.setIsApex(true);
        FakeStagedSession childSession2 = new FakeStagedSession(124);
        childSession2.setIsApex(true);
        FakeStagedSession nonApexChild = new FakeStagedSession(125);
        FakeStagedSession parentSession = new FakeStagedSession(240,
                Arrays.asList(childSession1, childSession2, nonApexChild));
        parentSession.setSessionReady();
        mStagingManager.createSession(parentSession);

        mockApexManagerGetStagedApexInfoWithSessionId();

        List<String> result = mStagingManager.getStagedApexModuleNames();
        assertThat(result).containsExactly("239", "123", "124");
        verify(mApexManager, times(2)).getStagedApexInfos(any());
    }

    // Make mApexManager return ApexInfo with same module name as the sessionId
    // of the parameter that was passed into it
    private void mockApexManagerGetStagedApexInfoWithSessionId() {
        when(mApexManager.getStagedApexInfos(any())).thenAnswer(new Answer<ApexInfo[]>() {
            @Override
            public ApexInfo[] answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                ApexSessionParams params = (ApexSessionParams) args[0];
                IntArray sessionsToProcess = new IntArray();
                if (params.childSessionIds.length == 0) {
                    sessionsToProcess.add(params.sessionId);
                } else {
                    sessionsToProcess.addAll(params.childSessionIds);
                }
                List<ApexInfo> result = new ArrayList<>();
                for (int session : sessionsToProcess.toArray()) {
                    ApexInfo info = new ApexInfo();
                    info.moduleName = String.valueOf(session);
                    result.add(info);
                }
                return result.toArray(new ApexInfo[0]);
            }
        });
    }

    @Test
    public void getStagedApexInfo() throws Exception {
        FakeStagedSession validSession1 = new FakeStagedSession(239);
        validSession1.setIsApex(true);
        validSession1.setSessionReady();
        mStagingManager.createSession(validSession1);
        ApexInfo[] fakeApexInfos = getFakeApexInfo(Arrays.asList("module1"));
        when(mApexManager.getStagedApexInfos(any())).thenReturn(fakeApexInfos);

        // Verify null is returned if module name is not found
        StagedApexInfo result = mStagingManager.getStagedApexInfo("not found");
        assertThat(result).isNull();
        verify(mApexManager, times(1)).getStagedApexInfos(any());
        // Otherwise, the correct object is returned
        result = mStagingManager.getStagedApexInfo("module1");
        assertThat(result.moduleName).isEqualTo(fakeApexInfos[0].moduleName);
        assertThat(result.diskImagePath).isEqualTo(fakeApexInfos[0].modulePath);
        assertThat(result.versionCode).isEqualTo(fakeApexInfos[0].versionCode);
        assertThat(result.versionName).isEqualTo(fakeApexInfos[0].versionName);
        verify(mApexManager, times(2)).getStagedApexInfos(any());
    }

    @Test
    public void registeredStagedApexObserverIsNotifiedOnPreRebootVerificationCompletion()
            throws Exception {
        // Register observer
        IStagedApexObserver observer = Mockito.mock(IStagedApexObserver.class);
        mStagingManager.registerStagedApexObserver(observer);

        // Create one staged session and trigger end of pre-reboot verification
        {
            FakeStagedSession session = new FakeStagedSession(239);
            session.setIsApex(true);
            mStagingManager.createSession(session);

            mockApexManagerGetStagedApexInfoWithSessionId();
            triggerEndOfPreRebootVerification(session);

            assertThat(session.isSessionReady()).isTrue();
            ArgumentCaptor<ApexStagedEvent> argumentCaptor = ArgumentCaptor.forClass(
                    ApexStagedEvent.class);
            verify(observer, times(1)).onApexStaged(argumentCaptor.capture());
            assertThat(argumentCaptor.getValue().stagedApexModuleNames).isEqualTo(
                    new String[]{"239"});
        }

        // Create another staged session and verify observers are notified of union
        {
            Mockito.clearInvocations(observer);
            FakeStagedSession session = new FakeStagedSession(240);
            session.setIsApex(true);
            mStagingManager.createSession(session);

            triggerEndOfPreRebootVerification(session);

            assertThat(session.isSessionReady()).isTrue();
            ArgumentCaptor<ApexStagedEvent> argumentCaptor = ArgumentCaptor.forClass(
                    ApexStagedEvent.class);
            verify(observer, times(1)).onApexStaged(argumentCaptor.capture());
            assertThat(argumentCaptor.getValue().stagedApexModuleNames).isEqualTo(
                    new String[]{"239", "240"});
        }

        // Finally, verify that once unregistered, observer is not notified
        mStagingManager.unregisterStagedApexObserver(observer);
        {
            Mockito.clearInvocations(observer);
            FakeStagedSession session = new FakeStagedSession(241);
            session.setIsApex(true);
            mStagingManager.createSession(session);

            triggerEndOfPreRebootVerification(session);

            assertThat(session.isSessionReady()).isTrue();
            verify(observer, never()).onApexStaged(any());
        }
    }

    @Test
    public void registeredStagedApexObserverIsNotifiedOnSessionAbandon() throws Exception {
        // Register observer
        IStagedApexObserver observer = Mockito.mock(IStagedApexObserver.class);
        mStagingManager.registerStagedApexObserver(observer);

        // Create a ready session and abandon it
        FakeStagedSession session = new FakeStagedSession(239);
        session.setIsApex(true);
        session.setSessionReady();
        session.setDestroyed(true);
        mStagingManager.createSession(session);

        mStagingManager.abortCommittedSession(session);

        assertThat(session.isSessionReady()).isTrue();
        ArgumentCaptor<ApexStagedEvent> argumentCaptor = ArgumentCaptor.forClass(
                ApexStagedEvent.class);
        verify(observer, times(1)).onApexStaged(argumentCaptor.capture());
        assertThat(argumentCaptor.getValue().stagedApexModuleNames).hasLength(0);
    }

    @Test
    public void stagedApexObserverIsOnlyCalledForApexSessions() throws Exception {
        IStagedApexObserver observer = Mockito.mock(IStagedApexObserver.class);
        mStagingManager.registerStagedApexObserver(observer);

        //  Trigger end of pre-reboot verification
        FakeStagedSession session = new FakeStagedSession(239);
        mStagingManager.createSession(session);

        triggerEndOfPreRebootVerification(session);
        assertThat(session.isSessionReady()).isTrue();
        verify(observer, never()).onApexStaged(any());
    }

    private void triggerEndOfPreRebootVerification(StagingManager.StagedSession session) {
        StagingManager.PreRebootVerificationHandler handler =
                mStagingManager.mPreRebootVerificationHandler;
        Message msg =  handler.obtainMessage(
                handler.MSG_PRE_REBOOT_VERIFICATION_END, session.sessionId(), -1, session);
        handler.handleMessage(msg);
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
                /* pm */ mMockPackageManagerInternal,
                /* sessionProvider */ null,
                /* silentUpdatePolicy */ null,
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
        doNothing().when(stagedSession).setSessionFailed(anyInt(), anyString());
        return stagedSession;
    }

    private static final class FakeStagedSession implements StagingManager.StagedSession {
        private final int mSessionId;
        private boolean mIsApex = false;
        private boolean mIsCommitted = false;
        private boolean mIsReady = false;
        private boolean mIsApplied = false;
        private boolean mIsFailed = false;
        private @StagedSessionErrorCode int mErrorCode = -1;
        private String mErrorMessage;
        private boolean mIsDestroyed = false;
        private int mParentSessionId = -1;
        private String mPackageName;
        private boolean mIsAbandonded = false;
        private boolean mVerificationStarted = false;
        private final List<StagingManager.StagedSession> mChildSessions;

        private FakeStagedSession(int sessionId) {
            mSessionId = sessionId;
            mChildSessions = new ArrayList<>();
        }

        private FakeStagedSession(int sessionId, List<StagingManager.StagedSession> childSessions) {
            mSessionId = sessionId;
            mChildSessions = childSessions;
        }

        private void setParentSessionId(int parentSessionId) {
            mParentSessionId = parentSessionId;
        }

        private void setCommitted(boolean isCommitted) {
            mIsCommitted = isCommitted;
        }

        private void setIsApex(boolean isApex) {
            mIsApex = isApex;
        }

        private void setDestroyed(boolean isDestroyed) {
            mIsDestroyed = isDestroyed;
        }

        private void setPackageName(String packageName) {
            mPackageName = packageName;
        }

        private boolean isAbandonded() {
            return mIsAbandonded;
        }

        private boolean hasVerificationStarted() {
            return mVerificationStarted;
        }

        private FakeStagedSession addChildSession(FakeStagedSession session) {
            mChildSessions.add(session);
            session.setParentSessionId(sessionId());
            return this;
        }

        private @StagedSessionErrorCode int getErrorCode() {
            return mErrorCode;
        }

        private String getErrorMessage() {
            return mErrorMessage;
        }

        @Override
        public boolean isMultiPackage() {
            return !mChildSessions.isEmpty();
        }

        @Override
        public boolean isApexSession() {
            return mIsApex;
        }

        @Override
        public boolean isCommitted() {
            return mIsCommitted;
        }

        @Override
        public boolean isInTerminalState() {
            return isSessionApplied() || isSessionFailed();
        }

        @Override
        public boolean isDestroyed() {
            return mIsDestroyed;
        }

        @Override
        public boolean isSessionReady() {
            return mIsReady;
        }

        @Override
        public boolean isSessionApplied() {
            return mIsApplied;
        }

        @Override
        public boolean isSessionFailed() {
            return mIsFailed;
        }

        @Override
        public List<StagingManager.StagedSession> getChildSessions() {
            return mChildSessions;
        }

        @Override
        public String getPackageName() {
            return mPackageName;
        }

        @Override
        public int getParentSessionId() {
            return mParentSessionId;
        }

        @Override
        public int sessionId() {
            return mSessionId;
        }

        @Override
        public PackageInstaller.SessionParams sessionParams() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean sessionContains(Predicate<StagingManager.StagedSession> filter) {
            return filter.test(this);
        }

        @Override
        public boolean containsApkSession() {
            Preconditions.checkState(!hasParentSessionId(), "Child session");
            if (!isMultiPackage()) {
                return !isApexSession();
            }
            for (StagingManager.StagedSession session : mChildSessions) {
                if (!session.isApexSession()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean containsApexSession() {
            Preconditions.checkState(!hasParentSessionId(), "Child session");
            if (!isMultiPackage()) {
                return isApexSession();
            }
            for (StagingManager.StagedSession session : mChildSessions) {
                if (session.isApexSession()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void setSessionReady() {
            mIsReady = true;
        }

        @Override
        public void setSessionFailed(@StagedSessionErrorCode int errorCode, String errorMessage) {
            Preconditions.checkState(!mIsApplied, "Already marked as applied");
            mIsFailed = true;
            mErrorCode = errorCode;
            mErrorMessage = errorMessage;
        }

        @Override
        public void setSessionApplied() {
            Preconditions.checkState(!mIsFailed, "Already marked as failed");
            mIsApplied = true;
        }

        @Override
        public void installSession(IntentSender statusReceiver) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasParentSessionId() {
            return mParentSessionId != -1;
        }

        @Override
        public long getCommittedMillis() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abandon() {
            mIsAbandonded = true;
        }

        @Override
        public void notifyEndPreRebootVerification() {}

        @Override
        public void verifySession() {
            mVerificationStarted = true;
        }
    }
}
