/*
 * Copyright (C) 2021 The Android Open Source Project
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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(DeviceJUnit4ClassRunner::class)
class OverlayActorVisibilityTest : BaseHostJUnit4Test() {

    companion object {
        private const val ACTOR_PKG_NAME = "com.android.server.pm.test.overlay.actor"
        private const val ACTOR_APK = "PackageManagerTestOverlayActor.apk"
        private const val TARGET_APK = "PackageManagerTestOverlayTarget.apk"
        private const val OVERLAY_APK = "PackageManagerTestOverlay.apk"
        private const val TARGET_NO_OVERLAYABLE_APK =
            "PackageManagerTestOverlayTargetNoOverlayable.apk"

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val preparer: SystemPreparer = SystemPreparer(
        tempFolder,
        SystemPreparer.RebootStrategy.FULL,
        deviceRebootRule
    ) { this.device }

    private val namedActorFile = File(
        "/system/etc/sysconfig/com.android.server.pm.test.OverlayActorVisibilityTest.xml"
    )

    @Before
    @After
    fun uninstallPackages() {
        device.uninstallPackages(ACTOR_APK, TARGET_APK, OVERLAY_APK)
    }

    @Before
    fun pushSysConfigFile() {
        // In order for the test app to be the verification agent, it needs a permission file
        // which can be pushed onto the system and removed afterwards.
        // language=XML
        val file = tempFolder.newFile().apply {
            """
            <config>
                <named-actor
                    namespace="androidTest"
                    name="OverlayActorVisibilityTest"
                    package="$ACTOR_PKG_NAME"
                    />
            </config>
            """
                .trimIndent()
                .let { writeText(it) }
        }

        preparer.pushFile(file, namedActorFile.toString())
            .reboot()
    }

    @After
    fun deleteSysConfigFile() {
        preparer.deleteFile(namedActorFile.toString())
            .reboot()
    }

    @Test
    fun testVisibilityByOverlayable() {
        assertThat(device.installJavaResourceApk(tempFolder, ACTOR_APK, false)).isNull()
        assertThat(device.installJavaResourceApk(tempFolder, OVERLAY_APK, false)).isNull()
        assertThat(device.installJavaResourceApk(tempFolder, TARGET_NO_OVERLAYABLE_APK, false))
            .isNull()

        runDeviceTests(
            ACTOR_PKG_NAME, "$ACTOR_PKG_NAME.OverlayableVisibilityTest",
            "verifyNotVisible"
        )

        assertThat(device.installJavaResourceApk(tempFolder, TARGET_APK, true)).isNull()

        assertWithMessage(device.executeShellCommand("dumpsys package $OVERLAY_APK"))

        runDeviceTests(
            ACTOR_PKG_NAME, "$ACTOR_PKG_NAME.OverlayableVisibilityTest",
            "verifyVisible"
        )
    }
}
