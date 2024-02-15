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
package com.android.server.pm

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageInstaller.SessionParams.PERMISSION_STATE_DEFAULT
import android.content.pm.PackageInstaller.SessionParams.PERMISSION_STATE_DENIED
import android.content.pm.PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainSet
import android.os.Parcel
import android.os.Process
import android.platform.test.annotations.Presubmit
import android.util.AtomicFile
import android.util.Slog
import android.util.Xml
import com.android.internal.os.BackgroundThread
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import libcore.io.IoUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

@Presubmit
class PackageInstallerSessionTest {

    companion object {
        private const val TAG_SESSIONS = "sessions"
    }

    @JvmField
    @Rule
    var mTemporaryFolder = TemporaryFolder()

    private lateinit var mTmpDir: File
    private lateinit var mSessionsFile: AtomicFile

    @Mock
    lateinit var mMockPackageManagerInternal: PackageManagerService

    @Mock
    lateinit var mSnapshot: Computer

    @Before
    @Throws(Exception::class)
    fun setUp() {
        mTmpDir = mTemporaryFolder.newFolder("PackageInstallerSessionTest")
        mSessionsFile = AtomicFile(
            File(mTmpDir.getAbsolutePath() + "/sessions.xml"), "package-session"
        )
        MockitoAnnotations.initMocks(this)
        whenever(mSnapshot.getPackageUid(anyString(), anyLong(), anyInt())) { 0 }
        whenever(mMockPackageManagerInternal.snapshotComputer()) { mSnapshot }
    }

    @Test
    fun testWriteAndRestoreSessionXmlSimpleSession() {
        writeRestoreAssert(listOf(createSession()))
    }

    @Test
    fun testWriteAndRestoreSessionXmlStagedSession() {
        writeRestoreAssert(listOf(createSession(staged = true)))
    }

    @Test
    fun testWriteAndRestoreSessionXmlLegacyGrantedPermission() {
        val sessions = createSession {
            @Suppress("DEPRECATION")
            it.setGrantedRuntimePermissions(arrayOf("permission1", "permission2"))
        }.let(::listOf)

        val restored = writeRestoreAssert(sessions)
        assertThat(restored.single().params.legacyGrantedRuntimePermissions).asList()
            .containsExactly("permission1", "permission2")
    }

    @Test
    fun testWriteAndRestoreSessionXmlPermissionState() {
        val sessions = createSession {
            it.setPermissionState("grantPermission", PERMISSION_STATE_GRANTED)
                .setPermissionState("denyPermission", PERMISSION_STATE_DENIED)
                .setPermissionState("grantToDefaultPermission", PERMISSION_STATE_GRANTED)
                .setPermissionState("grantToDefaultPermission", PERMISSION_STATE_DEFAULT)
                .setPermissionState("denyToDefaultPermission", PERMISSION_STATE_DENIED)
                .setPermissionState("denyToDefaultPermission", PERMISSION_STATE_DEFAULT)
                .setPermissionState("grantToDenyPermission", PERMISSION_STATE_GRANTED)
                .setPermissionState("grantToDenyPermission", PERMISSION_STATE_DENIED)
                .setPermissionState("denyToGrantPermission", PERMISSION_STATE_DENIED)
                .setPermissionState("denyToGrantPermission", PERMISSION_STATE_GRANTED)
        }.let(::listOf)

        writeRestoreAssert(sessions).single().params.run {
            assertThat(legacyGrantedRuntimePermissions).asList()
                .containsExactly("grantPermission", "denyToGrantPermission")
            assertThat(permissionStates)
                .containsExactlyEntriesIn(mapOf(
                    "grantPermission" to PERMISSION_STATE_GRANTED,
                    "denyToGrantPermission" to PERMISSION_STATE_GRANTED,
                    "denyPermission" to PERMISSION_STATE_DENIED,
                    "grantToDenyPermission" to PERMISSION_STATE_DENIED,
                ))
        }
    }

    @Test
    fun testWriteAndRestoreSessionXmlMultiPackageSessions() {
        val session = createSession(
            sessionId = 123,
            multiPackage = true,
            childSessionIds = listOf(234, 345)
        )
        val childSession1 = createSession(sessionId = 234, parentSessionId = 123)
        val childSession2 = createSession(sessionId = 345, parentSessionId = 123)
        writeRestoreAssert(listOf(session, childSession1, childSession2))
    }

    private fun createSession(
        staged: Boolean = false,
        sessionId: Int = 123,
        multiPackage: Boolean = false,
        parentSessionId: Int = PackageInstaller.SessionInfo.INVALID_ID,
        childSessionIds: List<Int> = emptyList(),
        block: (SessionParams) -> Unit = {},
    ): PackageInstallerSession {
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            isStaged = staged
            isMultiPackage = multiPackage
            block(this)
        }

        val installSource = InstallSource.create(
            "testInstallInitiator",
            "testInstallOriginator", "testInstaller", -1, "testUpdateOwner",
            "testAttributionTag", PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED
        )

        return PackageInstallerSession(
            /* callback */ null,
            /* context */ null,
            /* pm */ mMockPackageManagerInternal,
            /* sessionProvider */ null,
            /* silentUpdatePolicy */ null,
            /* looper */ BackgroundThread.getHandler().looper,
            /* stagingManager */ null,
            /* sessionId */ sessionId,
            /* userId */ 456,
            /* installerUid */ Process.myUid(),
            /* installSource */ installSource,
            /* sessionParams */ params,
            /* createdMillis */ 0L,
            /* committedMillis */ 0L,
            /* stageDir */ mTmpDir,
            /* stageCid */ null,
            /* files */ null,
            /* checksums */ null,
            /* prepared */ true,
            /* committed */ false,
            /* destroyed */ false,
            /* sealed */ false, // Setting to true would trigger some PM logic.
            /* childSessionIds */ childSessionIds.toIntArray(),
            /* parentSessionId */ parentSessionId,
            /* isReady */ staged,
            /* isFailed */ false,
            /* isApplied */ false,
            /* stagedSessionErrorCode */ PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
            /* stagedSessionErrorMessage */ "some error",
            /* preVerifiedDomains */ DomainSet(setOf("com.foo", "com.bar"))
        )
    }

    private fun writeRestoreAssert(sessions: List<PackageInstallerSession>) =
        writeSessions(sessions)
            .run { restoreSessions() }
            .also { assertEquals(sessions, it) }

    private fun writeSessions(sessions: List<PackageInstallerSession>) {
        var fos: FileOutputStream? = null
        try {
            fos = mSessionsFile.startWrite()
            Xml.resolveSerializer(fos).apply {
                startDocument(null, true)
                startTag(null, TAG_SESSIONS)
                for (session in sessions) {
                    session.write(this, mTmpDir)
                }
                endTag(null, TAG_SESSIONS)
                endDocument()
            }
            mSessionsFile.finishWrite(fos)
            Slog.d("PackageInstallerSessionTest", String(mSessionsFile.readFully()))
        } catch (e: IOException) {
            mSessionsFile.failWrite(fos)
        }
    }

    // This is roughly the logic used in PackageInstallerService to read the session. Note that
    // this test stresses readFromXml method from PackageInstallerSession, and doesn't cover the
    // PackageInstallerService portion of the parsing.
    private fun restoreSessions(): List<PackageInstallerSession> {
        val ret: MutableList<PackageInstallerSession> = ArrayList()
        var fis: FileInputStream? = null
        try {
            fis = mSessionsFile.openRead()
            val parser = Xml.resolvePullParser(fis)
            var type: Int
            while (parser.next().also { type = it } != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    if (PackageInstallerSession.TAG_SESSION == tag) {
                        val session: PackageInstallerSession
                        try {
                            session = PackageInstallerSession.readFromXml(
                                parser,
                                mock(PackageInstallerService.InternalCallback::class.java),
                                mock(Context::class.java),
                                mMockPackageManagerInternal,
                                BackgroundThread.getHandler().looper,
                                mock(StagingManager::class.java),
                                mTmpDir,
                                mock(PackageSessionProvider::class.java),
                                mock(SilentUpdatePolicy::class.java)
                            )
                            ret.add(session)
                        } catch (e: Exception) {
                            Slog.e("PackageInstallerSessionTest", "Exception ", e)
                            continue
                        }
                    }
                }
            }
        } catch (_: FileNotFoundException) {
            // Missing sessions are okay, probably first boot
        } catch (_: IOException) {
        } catch (_: XmlPullParserException) {
        } finally {
            IoUtils.closeQuietly(fis)
        }
        return ret
    }

    private fun assertSessionParamsEquivalent(expected: SessionParams, actual: SessionParams) {
        assertThat(expected.mode).isEqualTo(actual.mode)
        assertThat(expected.installFlags).isEqualTo(actual.installFlags)
        assertThat(expected.installLocation).isEqualTo(actual.installLocation)
        assertThat(expected.installReason).isEqualTo(actual.installReason)
        assertThat(expected.sizeBytes).isEqualTo(actual.sizeBytes)
        assertThat(expected.appPackageName).isEqualTo(actual.appPackageName)
        assertThat(expected.appIcon).isEqualTo(actual.appIcon)
        assertThat(expected.originatingUri).isEqualTo(actual.originatingUri)
        assertThat(expected.originatingUid).isEqualTo(actual.originatingUid)
        assertThat(expected.referrerUri).isEqualTo(actual.referrerUri)
        assertThat(expected.abiOverride).isEqualTo(actual.abiOverride)
        assertThat(expected.volumeUuid).isEqualTo(actual.volumeUuid)
        assertThat(expected.permissionStates).isEqualTo(actual.permissionStates)
        assertThat(expected.installerPackageName).isEqualTo(actual.installerPackageName)
        assertThat(expected.isMultiPackage).isEqualTo(actual.isMultiPackage)
        assertThat(expected.isStaged).isEqualTo(actual.isStaged)
    }

    private fun assertEquals(
        expected: List<PackageInstallerSession>,
        actual: List<PackageInstallerSession>
    ) {
        assertThat(expected).hasSize(actual.size)
        expected.sortedBy { it.sessionId }.zip(actual.sortedBy { it.sessionId })
            .forEach { (expected, actual) ->
                assertEquals(expected, actual)
            }
    }

    private fun assertEquals(expected: PackageInstallerSession, actual: PackageInstallerSession) {
        // Check both the restored params and an unparcelized variant to ensure parcelling works
        assertSessionParamsEquivalent(expected.params, actual.params)
        assertSessionParamsEquivalent(expected.params, actual.params.let {
            val parcel = Parcel.obtain()
            it.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            SessionParams.CREATOR.createFromParcel(parcel).also {
                parcel.recycle()
            }
        })

        assertThat(expected.sessionId).isEqualTo(actual.sessionId)
        assertThat(expected.userId).isEqualTo(actual.userId)
        assertThat(expected.installerUid).isEqualTo(actual.installerUid)
        assertThat(expected.installerPackageName).isEqualTo(actual.installerPackageName)
        assertInstallSourcesEquivalent(expected.installSource, actual.installSource)
        assertThat(expected.stageDir.absolutePath).isEqualTo(actual.stageDir.absolutePath)
        assertThat(expected.stageCid).isEqualTo(actual.stageCid)
        assertThat(expected.isPrepared).isEqualTo(actual.isPrepared)
        assertThat(expected.isStaged).isEqualTo(actual.isStaged)
        assertThat(expected.isSessionApplied).isEqualTo(actual.isSessionApplied)
        assertThat(expected.isSessionFailed).isEqualTo(actual.isSessionFailed)
        assertThat(expected.isSessionReady).isEqualTo(actual.isSessionReady)
        assertThat(expected.sessionErrorCode).isEqualTo(actual.sessionErrorCode)
        assertThat(expected.sessionErrorMessage).isEqualTo(actual.sessionErrorMessage)
        assertThat(expected.isPrepared).isEqualTo(actual.isPrepared)
        assertThat(expected.isCommitted).isEqualTo(actual.isCommitted)
        assertThat(expected.isPreapprovalRequested).isEqualTo(actual.isPreapprovalRequested)
        assertThat(expected.createdMillis).isEqualTo(actual.createdMillis)
        assertThat(expected.isSealed).isEqualTo(actual.isSealed)
        assertThat(expected.isMultiPackage).isEqualTo(actual.isMultiPackage)
        assertThat(expected.hasParentSessionId()).isEqualTo(actual.hasParentSessionId())
        assertThat(expected.parentSessionId).isEqualTo(actual.parentSessionId)
        assertThat(expected.childSessionIds).asList()
            .containsExactlyElementsIn(actual.childSessionIds.toList())
        assertThat(expected.preVerifiedDomains).isEqualTo(actual.preVerifiedDomains)
    }

    private fun assertInstallSourcesEquivalent(expected: InstallSource, actual: InstallSource) {
        assertThat(expected.mInstallerPackageName).isEqualTo(actual.mInstallerPackageName)
        assertThat(expected.mInitiatingPackageName).isEqualTo(actual.mInitiatingPackageName)
        assertThat(expected.mOriginatingPackageName).isEqualTo(actual.mOriginatingPackageName)
    }
}