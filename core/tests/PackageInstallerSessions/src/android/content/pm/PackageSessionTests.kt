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

package android.content.pm

import android.content.Context
import android.content.pm.PackageInstaller.SessionParams
import android.platform.test.annotations.Presubmit
import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import com.android.compatibility.common.util.ShellIdentityUtils
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.testng.Assert.assertThrows
import kotlin.random.Random

/**
 * For verifying public [PackageInstaller] session APIs. This differs from
 * [com.android.server.pm.PackageInstallerSessionTest] in services because that mocks the session,
 * whereas this test uses the installer on device.
 */
@Presubmit
class PackageSessionTests {

    companion object {
        /**
         * Permissions marked "hardRestricted" or "softRestricted" in core/res/AndroidManifest.xml.
         */
        private val RESTRICTED_PERMISSIONS = listOf(
                "android.permission.SEND_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_WAP_PUSH",
                "android.permission.RECEIVE_MMS",
                "android.permission.READ_CELL_BROADCASTS",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
                "android.permission.READ_CALL_LOG",
                "android.permission.WRITE_CALL_LOG",
                "android.permission.PROCESS_OUTGOING_CALLS"
        )
    }

    private val context: Context = InstrumentationRegistry.getContext()

    private val installer = context.packageManager.packageInstaller

    @Before
    @After
    fun abandonAllSessions() {
        installer.mySessions.asSequence()
                .map { it.sessionId }
                .forEach {
                    try {
                        installer.abandonSession(it)
                    } catch (ignored: Exception) {
                        // Querying for sessions checks by calling package name, but abandoning
                        // checks by UID, which won't match if this test failed to clean up
                        // on a previous install + run + uninstall, so ignore these failures.
                    }
                }
    }

    @Test
    fun truncateAppLabel() {
        val longLabel = invalidAppLabel()
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setAppLabel(longLabel)
        }

        createSession(params) {
            assertThat(installer.getSessionInfo(it)?.appLabel)
                    .isEqualTo(longLabel.take(PackageItemInfo.MAX_SAFE_LABEL_LENGTH))
        }
    }

    @Test
    fun removeInvalidAppPackageName() {
        val longName = invalidPackageName()
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(longName)
        }

        createSession(params) {
            assertThat(installer.getSessionInfo(it)?.appPackageName)
                    .isEqualTo(null)
        }
    }

    @Test
    fun removeInvalidInstallerPackageName() {
        val longName = invalidPackageName()
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setInstallerPackageName(longName)
        }

        createSession(params) {
            // If a custom installer name is dropped, it defaults to the caller
            assertThat(installer.getSessionInfo(it)?.installerPackageName)
                    .isEqualTo(context.packageName)
        }
    }

    @Test
    fun truncateWhitelistPermissions() {
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setWhitelistedRestrictedPermissions(invalidPermissions())
        }

        createSession(params) {
            assertThat(installer.getSessionInfo(it)?.whitelistedRestrictedPermissions!!)
                    .containsExactlyElementsIn(RESTRICTED_PERMISSIONS)
        }
    }

    @LargeTest
    @Test
    fun allocateMaxSessionsWithPermission() {
        ShellIdentityUtils.invokeWithShellPermissions {
            repeat(1024) { createDummySession() }
            assertThrows(IllegalStateException::class.java) { createDummySession() }
        }
    }

    @LargeTest
    @Test
    fun allocateMaxSessionsNoPermission() {
        repeat(50) { createDummySession() }
        assertThrows(IllegalStateException::class.java) { createDummySession() }
    }

    private fun createDummySession() {
        installer.createSession(SessionParams(SessionParams.MODE_FULL_INSTALL)
                .apply {
                    setAppPackageName(invalidPackageName())
                    setAppLabel(invalidAppLabel())
                    setWhitelistedRestrictedPermissions(invalidPermissions())
                })
    }

    private fun invalidPackageName(maxLength: Int = SessionParams.MAX_PACKAGE_NAME_LENGTH): String {
        return (0 until (maxLength + 10))
                .asSequence()
                .mapIndexed { index, _ ->
                    // A package name needs at least one separator
                    if (index == 2) {
                        '.'
                    } else {
                        Random.nextInt('z' - 'a').toChar() + 'a'.toInt()
                    }
                }
                .joinToString(separator = "")
    }

    private fun invalidAppLabel() = (0 until PackageItemInfo.MAX_SAFE_LABEL_LENGTH + 10)
            .asSequence()
            .map { Random.nextInt(Char.MAX_VALUE.toInt()).toChar() }
            .joinToString(separator = "")

    private fun invalidPermissions() = RESTRICTED_PERMISSIONS.toMutableSet()
            .apply {
                // Add some invalid permission names
                repeat(10) { add(invalidPackageName(300)) }
            }

    private fun createSession(params: SessionParams, block: (Int) -> Unit = {}) {
        val sessionId = installer.createSession(params)
        try {
            block(sessionId)
        } finally {
            installer.abandonSession(sessionId)
        }
    }
}
