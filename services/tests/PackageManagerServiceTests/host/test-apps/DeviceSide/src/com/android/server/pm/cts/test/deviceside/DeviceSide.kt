package com.android.server.pm.test.deviceside

import android.content.pm.PackageManager.MATCH_FACTORY_ONLY
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSide {
    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"
    }

    @Test
    fun testGetInstalledPackagesWithFactoryOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        val ctx = instrumentation.context

        uiAutomation.adoptShellPermissionIdentity()
        try {
            val packages1 = ctx.packageManager.getInstalledPackages(0)
                    .filter { it.packageName == TEST_PKG_NAME }
            val packages2 = ctx.packageManager.getInstalledPackages(MATCH_FACTORY_ONLY)
                    .filter { it.packageName == TEST_PKG_NAME }

            Truth.assertWithMessage("Incorrect number of packages found")
                    .that(packages1.size).isEqualTo(1)
            Truth.assertWithMessage("Incorrect number of packages found")
                    .that(packages2.size).isEqualTo(1)

            Truth.assertWithMessage("Incorrect version code for updated package")
                    .that(packages1[0].longVersionCode).isEqualTo(2)
            Truth.assertWithMessage("Incorrect version code for factory package")
                    .that(packages2[0].longVersionCode).isEqualTo(1)
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }
}
