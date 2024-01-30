/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.host.HostFlagsValueProvider
import com.android.internal.util.test.SystemPreparer
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(DeviceJUnit4ClassRunner::class)
@RequiresFlagsEnabled(android.security.Flags.FLAG_EXTEND_VB_CHAIN_TO_UPDATED_APK)
class TamperedUpdatedSystemPackageTest : BaseHostJUnit4Test() {

    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"
        private const val VERSION_ONE = "PackageManagerTestAppVersion1.apk"
        private const val VERSION_TWO_ALT_KEY = "PackageManagerTestAppVersion2AltKey.apk"
        private const val VERSION_TWO_ALT_KEY_IDSIG =
                "PackageManagerTestAppVersion2AltKey.apk.idsig"

        private const val ANOTHER_PKG_NAME = "com.android.server.pm.test.test_app2"
        private const val ANOTHER_PKG = "PackageManagerTestAppDifferentPkgName.apk"

        private const val STRICT_SIGNATURE_CONFIG_PATH =
                "/system/etc/sysconfig/preinstalled-packages-strict-signature.xml"
        private const val TIMESTAMP_REFERENCE_FILE_PATH = "/data/local/tmp/timestamp.ref"

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    private val tempFolder = TemporaryFolder()
    private val preparer: SystemPreparer = SystemPreparer(
        tempFolder,
            SystemPreparer.RebootStrategy.FULL,
        deviceRebootRule
    ) { this.device }
    private val productPath =
            HostUtils.makePathForApk("PackageManagerTestApp.apk", Partition.PRODUCT)
    private lateinit var originalConfigFile: File

    @Rule
    @JvmField
    val checkFlagsRule = HostFlagsValueProvider.createCheckFlagsRule({ getDevice() })

    @Rule
    @JvmField
    val rules = RuleChain.outerRule(tempFolder).around(preparer)!!

    @Before
    @After
    fun removeApk() {
        device.uninstallPackage(TEST_PKG_NAME)
        device.uninstallPackage(ANOTHER_PKG_NAME)
    }

    @Before
    fun backupAndModifySystemFiles() {
        // Backup
        device.pullFile(STRICT_SIGNATURE_CONFIG_PATH).also {
            assertNotNull(it)
            originalConfigFile = it
        }

        // Modify to allowlist the target package on device for testing the feature
        val xml = tempFolder.newFile().apply {
            val newConfigText = originalConfigFile
                    .readText()
                    .replace(
                        "</config>",
                            "<require-strict-signature package=\"${TEST_PKG_NAME}\"/>" +
                            "<require-strict-signature package=\"${ANOTHER_PKG_NAME}\"/>" +
                            "</config>"
                    )
            writeText(newConfigText)
        }
        device.remountSystemWritable()
        device.pushFile(xml, STRICT_SIGNATURE_CONFIG_PATH)
    }

    @After
    fun restoreSystemFiles() {
        device.remountSystemWritable()
        device.pushFile(originalConfigFile, STRICT_SIGNATURE_CONFIG_PATH)
        // Files pushed via a SystemPreparer are deleted automatically.
    }

    @Test
    fun detectApkAndXmlTamperingAtBoot() {
        // Set up the scenario where both APK and packages.xml are tampered by the attacker.
        // This is done by booting with the "bad" APK in a system partition, re-installing it to
        // /data. Then, replace the APK in the system partition with a "good" one.
        preparer.pushResourceFile(VERSION_TWO_ALT_KEY, productPath.toString())
                .reboot()

        // Install the "bad" APK to /data. This will also update package manager's XML records.
        val versionTwoFile = HostUtils.copyResourceToHostFile(
            VERSION_TWO_ALT_KEY,
                tempFolder.newFile()
        )
        assertThat(device.installPackage(versionTwoFile, true)).isNull()
        assertThat(device.executeShellCommand("pm path ${TEST_PKG_NAME}"))
                .doesNotContain(productPath.toString())

        // "Restore" the system partition is to a good state with correct APK.
        preparer.deleteFile(productPath.toString())
                .pushResourceFile(VERSION_ONE, productPath.toString())

        // Verify that upon the next boot, the system detect the problem and remove the problematic
        // APK in the /data.
        preparer.reboot()
        assertThat(device.executeShellCommand("pm path ${TEST_PKG_NAME}"))
                .contains(productPath.toString())
    }

    @Test
    fun detectApkTamperingAtBoot() {
        // Set up the scenario where APK is tampered but not the v4 signature. First, inject a
        // good APK as a system app.
        preparer.pushResourceFile(VERSION_TWO_ALT_KEY, productPath.toString())
                .reboot()

        // Re-install the target APK to /data, with the corresponding .idsig from build time.
        val versionTwoFile = HostUtils.copyResourceToHostFile(
            VERSION_TWO_ALT_KEY,
                tempFolder.newFile()
        )
        assertThat(device.installPackage(versionTwoFile, true)).isNull()
        val baseApkPath = getBaseApkPath(TEST_PKG_NAME)
        assertThat(baseApkPath).doesNotContain(productPath.toString())
        preparer.pushResourceFile(VERSION_TWO_ALT_KEY_IDSIG, baseApkPath.toString() + ".idsig")

        // Replace the APK in /data with a tampered version. Restore fs-verity and attributes.
        RandomAccessFile(versionTwoFile, "rw").use {
            // Skip the zip local file header to keep it valid. Tamper with the file name field and
            // beyond, just so that it won't simply fail.
            it.seek(30)
            it.writeBytes("tamper")
        }
        device.executeShellCommand("touch ${TIMESTAMP_REFERENCE_FILE_PATH} -r $baseApkPath")
        preparer.pushFile(versionTwoFile, baseApkPath)
        device.executeShellCommand(
            "cd ${baseApkPath.replace("base.apk", "")}" +
                "&& chown system:system base.apk " +
                "&& /data/local/tmp/fsverity_multilib enable base.apk" +
                "&& touch base.apk -r ${TIMESTAMP_REFERENCE_FILE_PATH}"
        )

        // Verify that upon the next boot, the system detect the problem and remove the problematic
        // APK in the /data.
        preparer.reboot()
        assertThat(device.executeShellCommand("pm path ${TEST_PKG_NAME}"))
                .contains(productPath.toString())
    }

    @Test
    fun allowlistedPackageIsNotASystemApp() {
        // If an allowlisted package isn't a system app, make sure install and boot still works
        // normally.
        assertThat(device.installJavaResourceApk(tempFolder, ANOTHER_PKG, /* reinstall */ false))
                .isNull()
        assertThat(getBaseApkPath(ANOTHER_PKG_NAME)).startsWith("/data/app/")

        preparer.reboot()
        assertThat(getBaseApkPath(ANOTHER_PKG_NAME)).startsWith("/data/app/")
    }

    private fun getBaseApkPath(pkgName: String): String {
        return device.executeShellCommand("pm path $pkgName")
                .lineSequence()
                .first()
                .replace("package:", "")
    }
}
