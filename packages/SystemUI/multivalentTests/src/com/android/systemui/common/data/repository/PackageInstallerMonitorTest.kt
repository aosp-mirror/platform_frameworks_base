/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.common.data.repository

import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionInfo
import android.graphics.Bitmap
import android.os.fakeExecutorHandler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.PackageInstallSession
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class PackageInstallerMonitorTest : SysuiTestCase() {
    @Mock private lateinit var packageInstaller: PackageInstaller
    @Mock private lateinit var icon1: Bitmap
    @Mock private lateinit var icon2: Bitmap
    @Mock private lateinit var icon3: Bitmap

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val handler = kosmos.fakeExecutorHandler

    private lateinit var session1: SessionInfo
    private lateinit var session2: SessionInfo
    private lateinit var session3: SessionInfo

    private lateinit var defaultSessions: List<SessionInfo>

    private lateinit var underTest: PackageInstallerMonitor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        session1 =
            SessionInfo().apply {
                sessionId = 1
                appPackageName = "pkg_name_1"
                appIcon = icon1
            }
        session2 =
            SessionInfo().apply {
                sessionId = 2
                appPackageName = "pkg_name_2"
                appIcon = icon2
            }
        session3 =
            SessionInfo().apply {
                sessionId = 3
                appPackageName = "pkg_name_3"
                appIcon = icon3
            }
        defaultSessions = listOf(session1, session2)

        whenever(packageInstaller.allSessions).thenReturn(defaultSessions)
        whenever(packageInstaller.getSessionInfo(1)).thenReturn(session1)
        whenever(packageInstaller.getSessionInfo(2)).thenReturn(session2)

        underTest =
            PackageInstallerMonitor(
                handler,
                kosmos.applicationCoroutineScope,
                logcatLogBuffer("PackageInstallerRepositoryImplTest"),
                packageInstaller,
            )
    }

    @Test
    fun installSessions_callbacksRegisteredOnlyWhenFlowIsCollected() =
        testScope.runTest {
            // Verify callback not added before flow is collected
            verify(packageInstaller, never()).registerSessionCallback(any(), eq(handler))

            // Start collecting the flow
            val job =
                backgroundScope.launch {
                    underTest.installSessionsForPrimaryUser.collect {
                        // Do nothing with the value
                    }
                }
            runCurrent()

            // Verify callback added only after flow is collected
            val callback =
                withArgCaptor<PackageInstaller.SessionCallback> {
                    verify(packageInstaller).registerSessionCallback(capture(), eq(handler))
                }

            // Verify callback not removed
            verify(packageInstaller, never()).unregisterSessionCallback(any())

            // Stop collecting the flow
            job.cancel()
            runCurrent()

            // Verify callback removed once flow stops being collected
            verify(packageInstaller).unregisterSessionCallback(eq(callback))
        }

    @Test
    fun installSessions_ignoreNullPackageNameSessions() =
        testScope.runTest {
            val nullPackageSession =
                SessionInfo().apply {
                    sessionId = 1
                    appPackageName = null
                    appIcon = icon1
                }
            val wellFormedSession =
                SessionInfo().apply {
                    sessionId = 2
                    appPackageName = "pkg_name"
                    appIcon = icon2
                }

            defaultSessions = listOf(nullPackageSession, wellFormedSession)

            whenever(packageInstaller.allSessions).thenReturn(defaultSessions)
            whenever(packageInstaller.getSessionInfo(1)).thenReturn(nullPackageSession)
            whenever(packageInstaller.getSessionInfo(2)).thenReturn(wellFormedSession)

            val packageInstallerMonitor =
                PackageInstallerMonitor(
                    handler,
                    kosmos.applicationCoroutineScope,
                    logcatLogBuffer("PackageInstallerRepositoryImplTest"),
                    packageInstaller,
                )

            val sessions by
                testScope.collectLastValue(packageInstallerMonitor.installSessionsForPrimaryUser)
            assertThat(sessions?.size).isEqualTo(1)
        }

    @Test
    fun installSessions_newSessionsAreAdded() =
        testScope.runTest {
            val installSessions by collectLastValue(underTest.installSessionsForPrimaryUser)
            assertThat(installSessions)
                .comparingElementsUsing(represents)
                .containsExactlyElementsIn(defaultSessions)

            val callback =
                withArgCaptor<PackageInstaller.SessionCallback> {
                    verify(packageInstaller).registerSessionCallback(capture(), eq(handler))
                }

            // New session added
            whenever(packageInstaller.getSessionInfo(3)).thenReturn(session3)
            callback.onCreated(3)
            runCurrent()

            // Verify flow updated with the new session
            assertThat(installSessions)
                .comparingElementsUsing(represents)
                .containsExactlyElementsIn(defaultSessions + session3)
        }

    @Test
    fun installSessions_finishedSessionsAreRemoved() =
        testScope.runTest {
            val installSessions by collectLastValue(underTest.installSessionsForPrimaryUser)
            assertThat(installSessions)
                .comparingElementsUsing(represents)
                .containsExactlyElementsIn(defaultSessions)

            val callback =
                withArgCaptor<PackageInstaller.SessionCallback> {
                    verify(packageInstaller).registerSessionCallback(capture(), eq(handler))
                }

            // Session 1 finished successfully
            callback.onFinished(1, /* success= */ true)
            runCurrent()

            // Verify flow updated with session 1 removed
            assertThat(installSessions)
                .comparingElementsUsing(represents)
                .containsExactlyElementsIn(defaultSessions - session1)
        }

    @Test
    fun installSessions_sessionsUpdatedOnBadgingChange() =
        testScope.runTest {
            val installSessions by collectLastValue(underTest.installSessionsForPrimaryUser)
            assertThat(installSessions)
                .comparingElementsUsing(represents)
                .containsExactlyElementsIn(defaultSessions)

            val callback =
                withArgCaptor<PackageInstaller.SessionCallback> {
                    verify(packageInstaller).registerSessionCallback(capture(), eq(handler))
                }

            // App icon for session 1 updated
            val newSession =
                SessionInfo().apply {
                    sessionId = 1
                    appPackageName = "pkg_name_1"
                    appIcon = mock()
                }
            whenever(packageInstaller.getSessionInfo(1)).thenReturn(newSession)
            callback.onBadgingChanged(1)
            runCurrent()

            // Verify flow updated with the new session 1
            assertThat(installSessions)
                .comparingElementsUsing(represents)
                .containsExactlyElementsIn(defaultSessions - session1 + newSession)
        }

    private val represents =
        Correspondence.from<PackageInstallSession, SessionInfo>(
            { actual, expected ->
                actual?.sessionId == expected?.sessionId &&
                    actual?.packageName == expected?.appPackageName &&
                    actual?.icon == expected?.getAppIcon()
            },
            "represents",
        )
}
