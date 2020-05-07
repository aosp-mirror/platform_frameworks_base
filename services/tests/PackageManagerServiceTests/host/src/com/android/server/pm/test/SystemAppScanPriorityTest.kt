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
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Pushes APKs onto various system partitions to verify that multiple versions result in the
 * highest version being scanned. Also tries to upgrade/replace these APKs which should result
 * in a version upgrade on reboot.
 *
 * This will also verify that APKs under the same folder/file name across different partitions
 * are parsed as separate entities and don't get combined under the same cache entry.
 *
 * Known limitations:
 * - Does not verify that v1 isn't scanned. It's possible to introduce a bug that upgrades the
 *     system on every reboot from v1 -> v2, as this isn't easily visible after scan has finished.
 *     This would also have to successfully preserve the app data though, which seems unlikely.
 * - This takes a very long time to run. 105 seconds for the first test case, up to 60 seconds for
 *     each following case. It's theoretically possible to parallelize these tests so that each
 *     method is run by installing all the apps under different names, requiring only 3 reboots to
 *     fully verify, rather than 3 * numTestCases.
 */
@RunWith(DeviceJUnit4ClassRunner::class)
class SystemAppScanPriorityTest : BaseHostJUnit4Test() {

    companion object {
        @get:ClassRule
        var deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    private val tempFolder = TemporaryFolder()
    private val preparer: SystemPreparer = SystemPreparer(tempFolder,
            SystemPreparer.RebootStrategy.FULL, deviceRebootRule) { this.device }

    private var firstReboot = true

    @Rule
    @JvmField
    val rules = RuleChain.outerRule(tempFolder).around(preparer)!!

    @Before
    @After
    fun deleteFiles() {
        HostUtils.deleteAllTestPackages(device, preparer)
    }

    @Before
    fun resetFirstReboot() {
        firstReboot = true
    }

    @Test
    fun takeHigherPriority() {
        preparer.pushFile(VERSION_ONE, Partition.VENDOR)
                .pushFile(VERSION_TWO, Partition.PRODUCT)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.PRODUCT)
    }

    @Test
    fun takeLowerPriority() {
        preparer.pushFile(VERSION_TWO, Partition.VENDOR)
                .pushFile(VERSION_ONE, Partition.PRODUCT)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.VENDOR)
    }

    @Test
    fun upgradeToHigherOnLowerPriority() {
        preparer.pushFile(VERSION_ONE, Partition.VENDOR)
                .pushFile(VERSION_TWO, Partition.PRODUCT)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.PRODUCT)

        preparer.pushFile(VERSION_THREE, Partition.VENDOR)
                .rebootForTest()

        assertVersionAndPartition(3, Partition.VENDOR)
    }

    @Test
    fun upgradeToNewerOnHigherPriority() {
        preparer.pushFile(VERSION_ONE, Partition.VENDOR)
                .pushFile(VERSION_TWO, Partition.PRODUCT)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.PRODUCT)

        preparer.pushFile(VERSION_THREE, Partition.SYSTEM_EXT)
                .rebootForTest()

        assertVersionAndPartition(3, Partition.SYSTEM_EXT)
    }

    @Test
    fun replaceNewerOnLowerPriority() {
        preparer.pushFile(VERSION_TWO, Partition.VENDOR)
                .pushFile(VERSION_ONE, Partition.PRODUCT)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.VENDOR)

        preparer.pushFile(VERSION_THREE, Partition.VENDOR)
                .rebootForTest()

        assertVersionAndPartition(3, Partition.VENDOR)
    }

    @Test
    fun replaceNewerOnHigherPriority() {
        preparer.pushFile(VERSION_ONE, Partition.VENDOR)
                .pushFile(VERSION_TWO, Partition.PRODUCT)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.PRODUCT)

        preparer.pushFile(VERSION_THREE, Partition.PRODUCT)
                .rebootForTest()

        assertVersionAndPartition(3, Partition.PRODUCT)
    }

    @Test
    fun fallbackToLowerPriority() {
        preparer.pushFile(VERSION_TWO, Partition.VENDOR)
                .pushFile(VERSION_ONE, Partition.PRODUCT)
                .pushFile(VERSION_THREE, Partition.SYSTEM_EXT)
                .rebootForTest()

        assertVersionAndPartition(3, Partition.SYSTEM_EXT)

        preparer.deleteFile(VERSION_THREE, Partition.SYSTEM_EXT)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.VENDOR)
    }

    @Test
    fun fallbackToHigherPriority() {
        preparer.pushFile(VERSION_THREE, Partition.VENDOR)
                .pushFile(VERSION_ONE, Partition.PRODUCT)
                .pushFile(VERSION_TWO, Partition.SYSTEM_EXT)
                .rebootForTest()

        assertVersionAndPartition(3, Partition.VENDOR)

        preparer.deleteFile(VERSION_THREE, Partition.VENDOR)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.SYSTEM_EXT)
    }

    @Test
    fun removeBoth() {
        preparer.pushFile(VERSION_ONE, Partition.VENDOR)
                .pushFile(VERSION_TWO, Partition.PRODUCT)
                .rebootForTest()

        assertVersionAndPartition(2, Partition.PRODUCT)

        preparer.deleteFile(VERSION_ONE, Partition.VENDOR)
                .deleteFile(VERSION_TWO, Partition.PRODUCT)
                .rebootForTest()

        assertThat(device.getAppPackageInfo(TEST_PKG_NAME)).isNull()
    }

    private fun assertVersionAndPartition(versionCode: Int, partition: Partition) {
        assertThat(HostUtils.getVersionCode(device, TEST_PKG_NAME)).isEqualTo(versionCode)

        val privateFlags = HostUtils.getPrivateFlags(device, TEST_PKG_NAME)

        when (partition) {
            Partition.SYSTEM,
            Partition.SYSTEM_PRIVILEGED -> {
                assertThat(privateFlags).doesNotContain(Partition.VENDOR.toString())
                assertThat(privateFlags).doesNotContain(Partition.PRODUCT.toString())
                assertThat(privateFlags).doesNotContain(Partition.SYSTEM_EXT.toString())
            }
            Partition.VENDOR -> {
                assertThat(privateFlags).contains(Partition.VENDOR.toString())
                assertThat(privateFlags).doesNotContain(Partition.PRODUCT.toString())
                assertThat(privateFlags).doesNotContain(Partition.SYSTEM_EXT.toString())
            }
            Partition.PRODUCT -> {
                assertThat(privateFlags).doesNotContain(Partition.VENDOR.toString())
                assertThat(privateFlags).contains(Partition.PRODUCT.toString())
                assertThat(privateFlags).doesNotContain(Partition.SYSTEM_EXT.toString())
            }
            Partition.SYSTEM_EXT -> {
                assertThat(privateFlags).doesNotContain(Partition.VENDOR.toString())
                assertThat(privateFlags).doesNotContain(Partition.PRODUCT.toString())
                assertThat(privateFlags).contains(Partition.SYSTEM_EXT.toString())
            }
        }.run { /* exhaust */ }
    }

    // Following methods don't use HostUtils in order to test cache behavior when using the same
    // name across partitions. Writes all files under the version 1 name.
    private fun makeDevicePath(partition: Partition) =
            partition.baseAppFolder
                    .resolve(File(VERSION_ONE).nameWithoutExtension)
                    .resolve(VERSION_ONE)
                    .toString()

    private fun SystemPreparer.pushFile(file: String, partition: Partition) =
            pushResourceFile(file, makeDevicePath(partition))

    private fun SystemPreparer.deleteFile(file: String, partition: Partition) =
            deleteFile(makeDevicePath(partition))

    /**
     * Custom reboot used to write app data after the first reboot. This can then be verified
     * after each subsequent reboot to ensure no data is lost.
     */
    private fun SystemPreparer.rebootForTest() {
        if (firstReboot) {
            firstReboot = false
            preparer.reboot()

            val file = tempFolder.newFile()
            file.writeText("Test")
            pushFile(file, "${HostUtils.getDataDir(device, TEST_PKG_NAME)}/files/test.txt")
        } else {
            val versionBefore = HostUtils.getVersionCode(device, TEST_PKG_NAME)
            preparer.reboot()
            val versionAfter = HostUtils.getVersionCode(device, TEST_PKG_NAME)

            if (versionBefore != null && versionAfter != null) {
                val fileContents = device.pullFileContents(
                        "${HostUtils.getDataDir(device, TEST_PKG_NAME)}/files/test.txt")
                if (versionAfter < versionBefore) {
                    // A downgrade will wipe app data
                    assertThat(fileContents).isNull()
                } else {
                    // An upgrade or update will preserve app data
                    assertThat(fileContents).isEqualTo("Test")
                }
            }
        }
    }
}
