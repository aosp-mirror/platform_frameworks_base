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

@RunWith(DeviceJUnit4ClassRunner::class)
class FactoryPackageTest : BaseHostJUnit4Test() {

    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"

        private const val VERSION_ONE = "PackageManagerTestAppVersion1.apk"
        private const val VERSION_TWO = "PackageManagerTestAppVersion2.apk"
        private const val DEVICE_SIDE = "PackageManagerServiceDeviceSideTests.apk"

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    private val tempFolder = TemporaryFolder()
    private val preparer: SystemPreparer = SystemPreparer(tempFolder,
            SystemPreparer.RebootStrategy.FULL, deviceRebootRule) { this.device }

    @Rule
    @JvmField
    val rules = RuleChain.outerRule(tempFolder).around(preparer)!!
    private val filePath =
            HostUtils.makePathForApk("PackageManagerTestApp.apk", Partition.SYSTEM)

    @Before
    @After
    fun removeApk() {
        device.uninstallPackage(TEST_PKG_NAME)
        device.deleteFile(filePath.parent.toString())
        device.reboot()
    }

    @Test
    fun testGetInstalledPackagesFactoryOnlyFlag() {
        // First, push a system app to the device and then update it so there's a data variant
        preparer.pushResourceFile(VERSION_ONE, filePath.toString())
                .reboot()

        val versionTwoFile = HostUtils.copyResourceToHostFile(VERSION_TWO, tempFolder.newFile())

        assertThat(device.installPackage(versionTwoFile, true)).isNull()

        runDeviceTest("testGetInstalledPackagesWithFactoryOnly")
    }

    /**
     * Run a device side test from com.android.server.pm.test.deviceside.DeviceSide
     *
     * @param method the method to run
     */
    fun runDeviceTest(method: String) {
        val deviceSideFile = HostUtils.copyResourceToHostFile(DEVICE_SIDE, tempFolder.newFile())
        assertThat(device.installPackage(deviceSideFile, true)).isNull()
        runDeviceTests(device, "com.android.server.pm.test.deviceside",
                "com.android.server.pm.test.deviceside.DeviceSide", method)
    }
}
