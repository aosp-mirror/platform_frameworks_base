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

package com.android.server.pm.test

import com.android.internal.util.test.SystemPreparer
import com.android.tradefed.device.ITestDevice
import com.android.tradefed.device.UserInfo
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.google.common.truth.Truth.assertThat
import org.junit.AfterClass
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(DeviceJUnit4ClassRunner::class)
class SystemStubMultiUserDisableUninstallTest : BaseHostJUnit4Test() {

    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"
        private const val VERSION_STUB = "PackageManagerTestAppStub.apk"
        private const val VERSION_ONE = "PackageManagerTestAppVersion1.apk"

        /**
         * How many total users on device to test, including primary. This will clean up any
         * users created specifically for this test.
         */
        private const val USER_COUNT = 3

        /**
         * Whether to manually reset state at each test method without rebooting
         * for faster iterative development.
         */
        private const val DEBUG_NO_REBOOT = false

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)

        private val parentClassName = SystemStubMultiUserDisableUninstallTest::class.java.simpleName

        private val deviceCompressedFile =
                HostUtils.makePathForApk("$parentClassName.apk", Partition.PRODUCT).parent
                        .resolve("$parentClassName.apk.gz")

        private val stubFile =
                HostUtils.makePathForApk("$parentClassName-Stub.apk", Partition.PRODUCT)

        private val secondaryUsers = mutableListOf<Int>()
        private val usersToRemove = mutableListOf<Int>()
        private var savedDevice: ITestDevice? = null
        private var savedPreparer: SystemPreparer? = null

        private fun setUpUsers(device: ITestDevice) {
            if (this.savedDevice != null) return
            this.savedDevice = device
            secondaryUsers.clear()
            secondaryUsers += device.userInfos.values.map(UserInfo::userId).filterNot { it == 0 }
            while (secondaryUsers.size < USER_COUNT) {
                secondaryUsers += device.createUser(parentClassName + secondaryUsers.size)
                        .also { usersToRemove += it }
            }
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            savedDevice ?: return

            usersToRemove.forEach {
                savedDevice?.removeUser(it)
            }

            savedDevice?.uninstallPackage(TEST_PKG_NAME)
            savedDevice?.deleteFile(stubFile.parent.toString())
            savedDevice?.deleteFile(deviceCompressedFile.parent.toString())
            savedDevice?.reboot()
            savedDevice = null

            if (DEBUG_NO_REBOOT) {
                savedPreparer?.after()
                savedPreparer = null
            }
        }
    }

    private val tempFolder = TemporaryFolder()

    // TODO(b/160159215): Use START_STOP rather than FULL once it's fixed. This will drastically
    //  improve pre/post-submit times.
    private val preparer: SystemPreparer = SystemPreparer(tempFolder,
            SystemPreparer.RebootStrategy.FULL, deviceRebootRule) { this.device }

    @Rule
    @JvmField
    val rules = RuleChain.outerRule(tempFolder).let {
        if (DEBUG_NO_REBOOT) {
            it!!
        } else {
            it.around(preparer)!!
        }
    }

    private var hostCompressedFile: File? = null

    private val previousCodePaths = mutableListOf<String>()

    @Before
    fun ensureUserAndCompressStubAndInstall() {
        setUpUsers(device)

        val initialized = hostCompressedFile != null
        if (!initialized) {
            hostCompressedFile = tempFolder.newFile()
            hostCompressedFile!!.outputStream().use {
                javaClass.classLoader
                        .getResource(VERSION_ONE)!!
                        .openStream()
                        .use { input ->
                            GZIPOutputStream(it).use { output ->
                                input.copyTo(output)
                            }
                        }
            }
        }

        device.uninstallPackage(TEST_PKG_NAME)

        if (!initialized || !DEBUG_NO_REBOOT) {
            savedPreparer = preparer
            preparer.pushResourceFile(VERSION_STUB, stubFile.toString())
                    .pushFile(hostCompressedFile, deviceCompressedFile.toString())
                    .reboot()
        }

        // This test forces the state to installed/enabled for all users,
        // since it only tests the uninstall/disable side.
        installExisting(User.PRIMARY)
        installExisting(User.SECONDARY)

        ensureEnabled()

        // Ensure data app isn't re-installed multiple times by comparing against the original path
        val codePath = HostUtils.getCodePaths(device, TEST_PKG_NAME).first()
        assertThat(codePath).contains("/data/app")
        assertThat(codePath).contains(TEST_PKG_NAME)

        previousCodePaths.clear()
        previousCodePaths += codePath

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun disablePrimaryFirstAndUninstall() {
        toggleEnabled(false, User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        toggleEnabled(false, User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        device.uninstallPackage(TEST_PKG_NAME)

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.SYSTEM)
        )
    }

    @Test
    fun disableSecondaryFirstAndUninstall() {
        toggleEnabled(false, User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        toggleEnabled(false, User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        device.uninstallPackage(TEST_PKG_NAME)

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.SYSTEM)
        )
    }

    @Test
    fun disabledUninstalledEnablePrimaryFirst() {
        toggleEnabled(false, User.PRIMARY)
        toggleEnabled(false, User.SECONDARY)
        device.uninstallPackage(TEST_PKG_NAME)

        toggleEnabled(true, User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.DIFFERENT, CodePath.SYSTEM)
        )

        toggleEnabled(true, User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun disabledUninstalledEnableSecondaryFirst() {
        toggleEnabled(false, User.PRIMARY)
        toggleEnabled(false, User.SECONDARY)
        device.uninstallPackage(TEST_PKG_NAME)

        toggleEnabled(true, User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.DIFFERENT, CodePath.SYSTEM)
        )

        toggleEnabled(true, User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun uninstallPrimaryFirstByUserAndInstallExistingPrimaryFirst() {
        uninstall(User.PRIMARY)

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        uninstall(User.SECONDARY)

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        installExisting(User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        installExisting(User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun uninstallSecondaryFirstByUserAndInstallExistingSecondaryFirst() {
        uninstall(User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        uninstall(User.PRIMARY)

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        installExisting(User.SECONDARY)

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        installExisting(User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun uninstallUpdatesAndEnablePrimaryFirst() {
        device.executeShellCommand("pm uninstall-system-updates $TEST_PKG_NAME")

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                // If any user is enabled when uninstalling updates, /data is re-uncompressed
                codePaths = listOf(CodePath.DIFFERENT, CodePath.SYSTEM)
        )

        toggleEnabled(false, User.PRIMARY)
        toggleEnabled(true, User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        // Test enabling secondary to ensure path does not change, even though it's already enabled
        toggleEnabled(true, User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun uninstallUpdatesAndEnableSecondaryFirst() {
        device.executeShellCommand("pm uninstall-system-updates $TEST_PKG_NAME")

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                // If any user is enabled when uninstalling updates, /data is re-uncompressed
                codePaths = listOf(CodePath.DIFFERENT, CodePath.SYSTEM)
        )

        toggleEnabled(false, User.PRIMARY)

        toggleEnabled(true, User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )

        toggleEnabled(true, User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun disabledUninstallUpdatesAndEnablePrimaryFirst() {
        toggleEnabled(false, User.PRIMARY)
        toggleEnabled(false, User.SECONDARY)

        device.executeShellCommand("pm uninstall-system-updates $TEST_PKG_NAME")

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.SYSTEM)
        )

        toggleEnabled(false, User.PRIMARY)
        toggleEnabled(true, User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.DIFFERENT, CodePath.SYSTEM)
        )

        toggleEnabled(true, User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun disabledUninstallUpdatesAndEnableSecondaryFirst() {
        toggleEnabled(false, User.PRIMARY)
        toggleEnabled(false, User.SECONDARY)

        device.executeShellCommand("pm uninstall-system-updates $TEST_PKG_NAME")

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = false,
                codePaths = listOf(CodePath.SYSTEM)
        )

        toggleEnabled(false, User.PRIMARY)
        toggleEnabled(true, User.SECONDARY)

        assertState(
                primaryInstalled = true, primaryEnabled = false,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.DIFFERENT, CodePath.SYSTEM)
        )

        toggleEnabled(true, User.PRIMARY)

        assertState(
                primaryInstalled = true, primaryEnabled = true,
                secondaryInstalled = true, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun uninstalledUninstallUpdatesAndEnablePrimaryFirst() {
        uninstall(User.PRIMARY)
        uninstall(User.SECONDARY)

        device.executeShellCommand("pm uninstall-system-updates $TEST_PKG_NAME")

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.SYSTEM)
        )

        toggleEnabled(false, User.PRIMARY)
        toggleEnabled(true, User.PRIMARY)

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.DIFFERENT, CodePath.SYSTEM)
        )

        toggleEnabled(true, User.SECONDARY)

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    @Test
    fun uninstalledUninstallUpdatesAndEnableSecondaryFirst() {
        uninstall(User.PRIMARY)
        uninstall(User.SECONDARY)

        device.executeShellCommand("pm uninstall-system-updates $TEST_PKG_NAME")

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.SYSTEM)
        )

        toggleEnabled(true, User.SECONDARY)

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.DIFFERENT, CodePath.SYSTEM)
        )

        toggleEnabled(true, User.PRIMARY)

        assertState(
                primaryInstalled = false, primaryEnabled = true,
                secondaryInstalled = false, secondaryEnabled = true,
                codePaths = listOf(CodePath.SAME, CodePath.SYSTEM)
        )
    }

    private fun ensureEnabled() {
        toggleEnabled(true, User.PRIMARY)
        toggleEnabled(true, User.SECONDARY)

        assertThat(HostUtils.getUserIdToPkgEnabledState(device, TEST_PKG_NAME).all { it.value })
                .isTrue()
    }

    private fun toggleEnabled(enabled: Boolean, user: User, pkgName: String = TEST_PKG_NAME) {
        val command = if (enabled) "enable" else "disable"
        @Suppress("UNUSED_VARIABLE") val exhaust: Any = when (user) {
            User.PRIMARY -> {
                device.executeShellCommand("pm $command --user 0 $pkgName")
            }
            User.SECONDARY -> {
                secondaryUsers.forEach {
                    device.executeShellCommand("pm $command --user $it $pkgName")
                }
            }
        }
    }

    private fun uninstall(user: User, pkgName: String = TEST_PKG_NAME) {
        @Suppress("UNUSED_VARIABLE") val exhaust: Any = when (user) {
            User.PRIMARY -> {
                device.executeShellCommand("pm uninstall --user 0 $pkgName")
            }
            User.SECONDARY -> {
                secondaryUsers.forEach {
                    device.executeShellCommand("pm uninstall --user $it $pkgName")
                }
            }
        }
    }

    private fun installExisting(user: User, pkgName: String = TEST_PKG_NAME) {
        @Suppress("UNUSED_VARIABLE") val exhaust: Any = when (user) {
            User.PRIMARY -> {
                device.executeShellCommand("pm install-existing --user 0 $pkgName")
            }
            User.SECONDARY -> {
                secondaryUsers.forEach {
                    device.executeShellCommand("pm install-existing --user $it $pkgName")
                }
            }
        }
    }

    private fun assertState(
        primaryInstalled: Boolean,
        primaryEnabled: Boolean,
        secondaryInstalled: Boolean,
        secondaryEnabled: Boolean,
        codePaths: List<CodePath>
    ) {
        HostUtils.getUserIdToPkgInstalledState(device, TEST_PKG_NAME)
            .also { assertThat(it.size).isAtLeast(USER_COUNT) }
            .forEach { (userId, installed) ->
                if (userId == 0) {
                    assertThat(installed).isEqualTo(primaryInstalled)
                } else {
                    assertThat(installed).isEqualTo(secondaryInstalled)
                }
            }

        HostUtils.getUserIdToPkgEnabledState(device, TEST_PKG_NAME)
            .also { assertThat(it.size).isAtLeast(USER_COUNT) }
            .forEach { (userId, enabled) ->
                if (userId == 0) {
                    assertThat(enabled).isEqualTo(primaryEnabled)
                } else {
                    assertThat(enabled).isEqualTo(secondaryEnabled)
                }
            }

        assertCodePaths(codePaths.first(), codePaths.getOrNull(1))
    }

    private fun assertCodePaths(firstCodePath: CodePath, secondCodePath: CodePath? = null) {
        val codePaths = HostUtils.getCodePaths(device, TEST_PKG_NAME)
        assertThat(codePaths).hasSize(listOfNotNull(firstCodePath, secondCodePath).size)

        when (firstCodePath) {
            CodePath.SAME -> {
                assertThat(codePaths[0]).contains("/data/app")
                assertThat(codePaths[0]).contains(TEST_PKG_NAME)
                assertThat(codePaths[0]).isEqualTo(previousCodePaths.last())
            }
            CodePath.DIFFERENT -> {
                assertThat(codePaths[0]).contains("/data/app")
                assertThat(codePaths[0]).contains(TEST_PKG_NAME)
                assertThat(previousCodePaths).doesNotContain(codePaths[0])
                previousCodePaths.add(codePaths[0])
            }
            CodePath.SYSTEM -> assertThat(codePaths[0]).isEqualTo(stubFile.parent.toString())
        }

        when (secondCodePath) {
            CodePath.SAME, CodePath.DIFFERENT ->
                throw AssertionError("secondDataPath cannot be a data path")
            CodePath.SYSTEM -> assertThat(codePaths[1]).isEqualTo(stubFile.parent.toString())
            else -> {}
        }
    }

    enum class User {
        /** The primary system user 0 */
        PRIMARY,

        /**
         * All other users on the device that are not 0. This is split into an enum so that all
         * methods that handle secondary act on all non-system users. Some behaviors only occur
         * if a package state is marked for all non-primary users on the device, which can be
         * more than just 1.
         */
        SECONDARY
    }

    enum class CodePath {
        /** The data code path hasn't changed */
        SAME,

        /** New data code path */
        DIFFERENT,

        /** The static system code path */
        SYSTEM
    }
}
