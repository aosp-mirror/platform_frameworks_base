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

import android.cts.host.utils.DeviceJUnit4ClassRunnerWithParameters
import android.cts.host.utils.DeviceJUnit4Parameterized
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.regex.Pattern

/**
 * Verifies PackageManagerService behavior when an app is moved to an adoptable storage device.
 *
 * Also has the effect of verifying system behavior when the PackageSetting for a package has no
 * corresponding AndroidPackage which can be parsed from the APK on disk. This is done by removing
 * the storage device and causing a reboot, at which point PMS will read PackageSettings from disk
 * and fail to find the package path.
 */
@RunWith(DeviceJUnit4Parameterized::class)
@Parameterized.UseParametersRunnerFactory(
        DeviceJUnit4ClassRunnerWithParameters.RunnerFactory::class)
class SdCardEjectionTests : BaseHostJUnit4Test() {

    companion object {
        private const val VERSION_DECLARES = "PackageManagerTestAppDeclaresStaticLibrary.apk"
        private const val VERSION_DECLARES_PKG_NAME =
                "com.android.server.pm.test.test_app_declares_static_library"
        private const val VERSION_USES = "PackageManagerTestAppUsesStaticLibrary.apk"
        private const val VERSION_USES_PKG_NAME =
                "com.android.server.pm.test.test_app_uses_static_library"

        // TODO(chiuwinson): Use the HostUtils constants when merged
        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"
        private const val VERSION_ONE = "PackageManagerTestAppVersion1.apk"

        @Parameterized.Parameters(name = "reboot={0}")
        @JvmStatic
        fun parameters() = arrayOf(false, true)

        data class Volume(
            val diskId: String,
            val fsUuid: String
        )
    }

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Parameterized.Parameter(0)
    @JvmField
    var reboot: Boolean = false

    @Before
    @After
    fun removePackagesAndDeleteVirtualDisk() {
        device.uninstallPackages(VERSION_ONE, VERSION_USES_PKG_NAME, VERSION_DECLARES_PKG_NAME)
        removeVirtualDisk()
        device.reboot()
    }

    @Test
    fun launchActivity() {
        val hostApkFile = HostUtils.copyResourceToHostFile(VERSION_ONE, tempFolder.newFile())
        assertThat(device.installPackage(hostApkFile, true)).isNull()

        val errorRegex = Pattern.compile("error", Pattern.CASE_INSENSITIVE)
        fun assertStartResponse(launched: Boolean) {
            val response = device.executeShellCommand("am start -n $TEST_PKG_NAME/.TestActivity")
            if (launched) {
                assertThat(response).doesNotContainMatch(errorRegex)
            } else {
                assertThat(response).containsMatch(errorRegex)
            }
        }

        assertStartResponse(launched = true)

        val volume = initializeVirtualDisk()

        movePackage(TEST_PKG_NAME, volume)
        assertStartResponse(launched = true)

        unmount(volume, TEST_PKG_NAME)
        assertStartResponse(launched = false)

        remount(volume, hostApkFile, TEST_PKG_NAME)
        assertStartResponse(launched = true)
    }

    @Test
    fun uninstallStaticLibraryInUse() {
        assertThat(device.installJavaResourceApk(tempFolder, VERSION_DECLARES)).isNull()

        val usesApkFile = HostUtils.copyResourceToHostFile(VERSION_USES, tempFolder.newFile())
        assertThat(device.installPackage(usesApkFile, true)).isNull()

        fun assertUninstallFails() = assertThat(device.uninstallPackage(VERSION_DECLARES_PKG_NAME))
                .isEqualTo("DELETE_FAILED_USED_SHARED_LIBRARY")

        assertUninstallFails()

        val volume = initializeVirtualDisk()

        movePackage(VERSION_USES_PKG_NAME, volume)
        assertUninstallFails()

        unmount(volume, VERSION_USES_PKG_NAME)
        assertUninstallFails()

        remount(volume, usesApkFile, VERSION_USES_PKG_NAME)
        assertUninstallFails()

        // Check that install in the correct order (uses first) passes
        assertThat(device.uninstallPackage(VERSION_USES_PKG_NAME)).isNull()
        assertThat(device.uninstallPackage(VERSION_DECLARES_PKG_NAME)).isNull()
    }

    private fun initializeVirtualDisk(): Volume {
        // Rather than making any assumption about what disks/volumes exist on the device,
        // save the existing disks/volumes to compare and see when a new one pops up, assuming
        // it was created as the result of the calls in this test.
        val existingDisks = device.executeShellCommand("sm list-disks adoptable").lines()
        val existingVolumes = device.executeShellCommand("sm list-volumes private").lines()
        device.executeShellCommand("sm set-virtual-disk true")

        val diskId = retryUntilNonNull {
            device.executeShellCommand("sm list-disks adoptable")
                    .lines()
                    .filterNot(existingDisks::contains)
                    .filterNot(String::isEmpty)
                    .firstOrNull()
        }

        device.executeShellCommand("sm partition $diskId private")

        return retrieveNewVolume(existingVolumes)
    }

    private fun retrieveNewVolume(existingVolumes: List<String>): Volume {
        val newVolume = retryUntilNonNull {
            device.executeShellCommand("sm list-volumes private")
                    .lines()
                    .toMutableList()
                    .apply { removeAll(existingVolumes) }
                    .firstOrNull()
                    ?.takeIf { it.isNotEmpty() }
        }

        val sections = newVolume.split(" ")
        return Volume(diskId = sections.first(), fsUuid = sections.last()).also {
            assertThat(it.diskId).isNotEmpty()
            assertThat(it.fsUuid).isNotEmpty()
        }
    }

    private fun removeVirtualDisk() {
        device.executeShellCommand("sm set-virtual-disk false")
        retryUntilSuccess {
            !device.executeShellCommand("sm list-volumes").contains("ejecting")
        }
    }

    private fun movePackage(pkgName: String, volume: Volume) {
        // TODO(b/167241596): oat dir must exist for a move install
        val codePath = HostUtils.getCodePaths(device, pkgName).first()
        device.executeShellCommand("mkdir $codePath/oat")
        assertThat(device.executeShellCommand(
                "pm move-package $pkgName ${volume.fsUuid}").trim())
                .isEqualTo("Success")
    }

    private fun unmount(volume: Volume, pkgName: String) {
        assertThat(device.executeShellCommand("sm unmount ${volume.diskId}")).isEmpty()
        if (reboot) {
            // The system automatically mounts the virtual disk on startup, which would mean the
            // app files are available to the system. To prevent this, disable the disk entirely.
            // TODO: There must be a better way to prevent it from auto-mounting.
            removeVirtualDisk()
            device.reboot()
        } else {
            // Because PackageManager unmount scan is asynchronous, need to retry until the package
            // has been unloaded. This only has to be done in the non-reboot case. Reboot will
            // clear the data structure by its nature.
            retryUntilSuccess {
                // The compiler section will print the state of the physical APK
                HostUtils.packageSection(device, pkgName, sectionName = "Compiler stats")
                        .any { it.contains("Unable to find package: $pkgName") }
            }
        }
    }

    private fun remount(volume: Volume, hostApkFile: File, pkgName: String) {
        if (reboot) {
            // Because the disk was destroyed when unmounting, it now has to be rebuilt manually.
            // This enables a new virtual disk, unmounts it, mutates its UUID to match the previous
            // partition's, remounts it, and pushes the base.apk back onto the device. This
            // simulates the same disk being re-inserted. This is very hacky.
            val newVolume = initializeVirtualDisk()
            val mountPoint = device.executeShellCommand("mount")
                    .lineSequence()
                    .first { it.contains(newVolume.fsUuid) }
                    .takeWhile { !it.isWhitespace() }

            device.executeShellCommand("sm unmount ${newVolume.diskId}")

            // Save without renamed UUID to compare and see when the renamed pops up
            val existingVolumes = device.executeShellCommand("sm list-volumes private").lines()

            device.executeShellCommand("make_f2fs -U ${volume.fsUuid} $mountPoint")
            device.executeShellCommand("sm mount ${newVolume.diskId}")

            val reparsedVolume = retrieveNewVolume(existingVolumes)
            assertThat(reparsedVolume.fsUuid).isEqualTo(volume.fsUuid)

            val codePath = HostUtils.getCodePaths(device, pkgName).first()
            device.pushFile(hostApkFile, "$codePath/base.apk")

            // Unmount so following remount will re-kick package scan
            device.executeShellCommand("sm unmount ${newVolume.diskId}")
        }

        device.executeShellCommand("sm mount ${volume.diskId}")

        // Because PackageManager remount scan is asynchronous, need to retry until the package
        // has been loaded and added to the internal structures. Otherwise resolution will fail.
        retryUntilSuccess {
            // The compiler section will print the state of the physical APK
            HostUtils.packageSection(device, pkgName, sectionName = "Compiler stats")
                    .none { it.contains("Unable to find package: $pkgName") }
        }
    }
}
