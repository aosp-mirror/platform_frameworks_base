/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BackgroundThread;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import libcore.io.IoUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@Presubmit
public class PackageInstallerSessionTest {
    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private File mTmpDir;
    private AtomicFile mSessionsFile;
    private static final String TAG_SESSIONS = "sessions";

    @Mock
    PackageManagerService mMockPackageManagerInternal;

    @Mock
    Computer mSnapshot;

    @Before
    public void setUp() throws Exception {
        mTmpDir = mTemporaryFolder.newFolder("PackageInstallerSessionTest");
        mSessionsFile = new AtomicFile(
                new File(mTmpDir.getAbsolutePath() + "/sessions.xml"), "package-session");
        MockitoAnnotations.initMocks(this);
        when(mSnapshot.getPackageUid(anyString(), anyLong(), anyInt())).thenReturn(0);
        when(mMockPackageManagerInternal.snapshotComputer()).thenReturn(mSnapshot);
    }

    @Test
    public void testWriteAndRestoreSessionXmlSimpleSession() {
        PackageInstallerSession session = createSimpleSession();
        dumpSession(session);
        List<PackageInstallerSession> restored = restoreSessions();
        assertEquals(1, restored.size());
        assertSessionsEquivalent(session, restored.get(0));
    }

    @Test
    public void testWriteAndRestoreSessionXmlStagedSession() {
        PackageInstallerSession session = createStagedSession();
        dumpSession(session);
        List<PackageInstallerSession> restored = restoreSessions();
        assertEquals(1, restored.size());
        assertSessionsEquivalent(session, restored.get(0));
    }

    @Test
    public void testWriteAndRestoreSessionXmlGrantedPermission() {
        PackageInstallerSession session = createSessionWithGrantedPermissions();
        dumpSession(session);
        List<PackageInstallerSession> restored = restoreSessions();
        assertEquals(1, restored.size());
        assertSessionsEquivalent(session, restored.get(0));
    }

    @Test
    public void testWriteAndRestoreSessionXmlMultiPackageSessions() {
        PackageInstallerSession session = createMultiPackageParentSession(123, new int[]{234, 345});
        PackageInstallerSession childSession1 = createMultiPackageChildSession(234, 123);
        PackageInstallerSession childSession2 = createMultiPackageChildSession(345, 123);
        List<PackageInstallerSession> sessionGroup =
                Arrays.asList(session, childSession1, childSession2);
        dumpSessions(sessionGroup);
        List<PackageInstallerSession> restored = restoreSessions();
        assertEquals(3, restored.size());
        assertSessionsEquivalent(sessionGroup, restored);
    }

    private PackageInstallerSession createSimpleSession() {
        return createSession(false, false, 123, false, PackageInstaller.SessionInfo.INVALID_ID,
                null);
    }

    private PackageInstallerSession createStagedSession() {
        return createSession(true, false, 123, false, PackageInstaller.SessionInfo.INVALID_ID,
                null);
    }

    private PackageInstallerSession createSessionWithGrantedPermissions() {
        return createSession(false, true, 123, false, PackageInstaller.SessionInfo.INVALID_ID,
                null);
    }

    private PackageInstallerSession createMultiPackageParentSession(int sessionId,
                                                                    int[] childSessionIds) {
        return createSession(false, false, sessionId, true,
                PackageInstaller.SessionInfo.INVALID_ID, childSessionIds);
    }

    private PackageInstallerSession createMultiPackageChildSession(int sessionId,
                                                                   int parentSessionId) {
        return createSession(false, false, sessionId, false, parentSessionId, null);
    }

    private PackageInstallerSession createSession(boolean staged, boolean withGrantedPermissions,
                                                  int sessionId, boolean isMultiPackage,
                                                  int parentSessionId, int[] childSessionIds) {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (staged) {
            params.isStaged = true;
        }
        if (withGrantedPermissions) {
            params.grantedRuntimePermissions = new String[]{"permission1", "permission2"};
        }
        if (isMultiPackage) {
            params.isMultiPackage = true;
        }
        InstallSource installSource = InstallSource.create("testInstallInitiator",
                "testInstallOriginator", "testInstaller", -1, "testUpdateOwner",
                "testAttributionTag", PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);
        return new PackageInstallerSession(
                /* callback */ null,
                /* context */null,
                /* pm */ mMockPackageManagerInternal,
                /* sessionProvider */ null,
                /* silentUpdatePolicy */ null,
                /* looper */ BackgroundThread.getHandler().getLooper(),
                /* stagingManager */ null,
                /* sessionId */ sessionId,
                /* userId */  456,
                /* installerUid */ -1,
                /* installSource */ installSource,
                /* sessionParams */ params,
                /* createdMillis */ 0L,
                /* committedMillis */ 0L,
                /* stageDir */ mTmpDir,
                /* stageCid */ null,
                /* files */ null,
                /* checksums */ null,
                /* prepared */ true,
                /* committed */ true,
                /* destroyed */ staged ? true : false,
                /* sealed */ false,  // Setting to true would trigger some PM logic.
                /* childSessionIds */ childSessionIds != null ? childSessionIds : new int[0],
                /* parentSessionId */ parentSessionId,
                /* isReady */ staged ? true : false,
                /* isFailed */ false,
                /* isApplied */false,
                /* stagedSessionErrorCode */
                PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                /* stagedSessionErrorMessage */ "some error");
    }

    private void dumpSession(PackageInstallerSession session) {
        dumpSessions(Arrays.asList(session));
    }

    private void dumpSessions(List<PackageInstallerSession> sessions) {
        FileOutputStream fos = null;
        try {
            fos = mSessionsFile.startWrite();

            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.startTag(null, TAG_SESSIONS);
            for (PackageInstallerSession session : sessions) {
                session.write(out, mTmpDir);
            }
            out.endTag(null, TAG_SESSIONS);
            out.endDocument();

            mSessionsFile.finishWrite(fos);
            Slog.d("PackageInstallerSessionTest", new String(mSessionsFile.readFully()));
        } catch (IOException e) {
            if (fos != null) {
                mSessionsFile.failWrite(fos);
            }
        }
    }

    // This is roughly the logic used in PackageInstallerService to read the session. Note that
    // this test stresses readFromXml method from PackageInstallerSession, and doesn't cover the
    // PackageInstallerService portion of the parsing.
    private List<PackageInstallerSession> restoreSessions() {
        List<PackageInstallerSession> ret = new ArrayList<>();
        FileInputStream fis = null;
        try {
            fis = mSessionsFile.openRead();
            TypedXmlPullParser in = Xml.resolvePullParser(fis);

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                if (type == START_TAG) {
                    final String tag = in.getName();
                    if (PackageInstallerSession.TAG_SESSION.equals(tag)) {
                        final PackageInstallerSession session;
                        try {
                            session = PackageInstallerSession.readFromXml(in, null,
                                    null, mMockPackageManagerInternal,
                                    BackgroundThread.getHandler().getLooper(), null,
                                    mTmpDir, null, null);
                            ret.add(session);
                        } catch (Exception e) {
                            Slog.e("PackageInstallerSessionTest", "Exception ", e);
                            continue;
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Missing sessions are okay, probably first boot
        } catch (IOException | XmlPullParserException e) {

        } finally {
            IoUtils.closeQuietly(fis);
        }
        return ret;
    }

    private void assertSessionParamsEquivalent(PackageInstaller.SessionParams expected,
                                               PackageInstaller.SessionParams actual) {
        assertEquals(expected.mode, actual.mode);
        assertEquals(expected.installFlags, actual.installFlags);
        assertEquals(expected.installLocation, actual.installLocation);
        assertEquals(expected.installReason, actual.installReason);
        assertEquals(expected.sizeBytes, actual.sizeBytes);
        assertEquals(expected.appPackageName, actual.appPackageName);
        assertEquals(expected.appIcon, actual.appIcon);
        assertEquals(expected.originatingUri, actual.originatingUri);
        assertEquals(expected.originatingUid, actual.originatingUid);
        assertEquals(expected.referrerUri, actual.referrerUri);
        assertEquals(expected.abiOverride, actual.abiOverride);
        assertEquals(expected.volumeUuid, actual.volumeUuid);
        assertArrayEquals(expected.grantedRuntimePermissions, actual.grantedRuntimePermissions);
        assertEquals(expected.installerPackageName, actual.installerPackageName);
        assertEquals(expected.isMultiPackage, actual.isMultiPackage);
        assertEquals(expected.isStaged, actual.isStaged);
    }

    private void assertSessionsEquivalent(List<PackageInstallerSession> expected,
                                          List<PackageInstallerSession> actual) {
        assertEquals(expected.size(), actual.size());
        for (PackageInstallerSession expectedSession : expected) {
            boolean foundSession = false;
            for (PackageInstallerSession actualSession : actual) {
                if (expectedSession.sessionId == actualSession.sessionId) {
                    // We should only encounter each expected session once.
                    assertFalse(foundSession);
                    foundSession = true;
                    assertSessionsEquivalent(expectedSession, actualSession);
                }
            }
            assertTrue(foundSession);
        }
    }

    private void assertSessionsEquivalent(PackageInstallerSession expected,
                                          PackageInstallerSession actual) {
        assertEquals(expected.sessionId, actual.sessionId);
        assertEquals(expected.userId, actual.userId);
        assertSessionParamsEquivalent(expected.params, actual.params);
        assertEquals(expected.getInstallerUid(), actual.getInstallerUid());
        assertEquals(expected.getInstallerPackageName(), actual.getInstallerPackageName());
        assertInstallSourcesEquivalent(expected.getInstallSource(), actual.getInstallSource());
        assertEquals(expected.stageDir.getAbsolutePath(), actual.stageDir.getAbsolutePath());
        assertEquals(expected.stageCid, actual.stageCid);
        assertEquals(expected.isPrepared(), actual.isPrepared());
        assertEquals(expected.isStaged(), actual.isStaged());
        assertEquals(expected.isSessionApplied(), actual.isSessionApplied());
        assertEquals(expected.isSessionFailed(), actual.isSessionFailed());
        assertEquals(expected.isSessionReady(), actual.isSessionReady());
        assertEquals(expected.getSessionErrorCode(), actual.getSessionErrorCode());
        assertEquals(expected.getSessionErrorMessage(),
                actual.getSessionErrorMessage());
        assertEquals(expected.isPrepared(), actual.isPrepared());
        assertEquals(expected.isCommitted(), actual.isCommitted());
        assertEquals(expected.isPreapprovalRequested(), actual.isPreapprovalRequested());
        assertEquals(expected.createdMillis, actual.createdMillis);
        assertEquals(expected.isSealed(), actual.isSealed());
        assertEquals(expected.isMultiPackage(), actual.isMultiPackage());
        assertEquals(expected.hasParentSessionId(), actual.hasParentSessionId());
        assertEquals(expected.getParentSessionId(), actual.getParentSessionId());
        assertArrayEquals(expected.getChildSessionIds(), actual.getChildSessionIds());
    }

    private void assertInstallSourcesEquivalent(InstallSource expected, InstallSource actual) {
        assertEquals(expected.mInstallerPackageName, actual.mInstallerPackageName);
        assertEquals(expected.mInitiatingPackageName, actual.mInitiatingPackageName);
        assertEquals(expected.mOriginatingPackageName, actual.mOriginatingPackageName);
    }
}
