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

package com.android.server.pm.test.intent.verify

import com.android.internal.util.test.SystemPreparer
import com.android.server.pm.test.Partition
import com.android.server.pm.test.deleteApkFolders
import com.android.server.pm.test.installJavaResourceApk
import com.android.server.pm.test.pushApk
import com.android.server.pm.test.uninstallPackages
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
import java.util.concurrent.TimeUnit

@RunWith(DeviceJUnit4ClassRunner::class)
class IntentFilterVerificationTest : BaseHostJUnit4Test() {

    companion object {
        private const val VERIFIER = "PackageManagerTestIntentVerifier.apk"
        private const val VERIFIER_PKG_NAME = "com.android.server.pm.test.intent.verifier"
        private const val TARGET_PKG_PREFIX = "$VERIFIER_PKG_NAME.target"
        private const val TARGET_APK_PREFIX = "PackageManagerTestIntentVerifierTarget"
        private const val TARGET_ONE = "${TARGET_APK_PREFIX}1.apk"
        private const val TARGET_ONE_PKG_NAME = "$TARGET_PKG_PREFIX.one"
        private const val TARGET_TWO = "${TARGET_APK_PREFIX}2.apk"
        private const val TARGET_TWO_PKG_NAME = "$TARGET_PKG_PREFIX.two"
        private const val TARGET_THREE = "${TARGET_APK_PREFIX}3.apk"
        private const val TARGET_THREE_PKG_NAME = "$TARGET_PKG_PREFIX.three"
        private const val TARGET_FOUR_BASE = "${TARGET_APK_PREFIX}4Base.apk"
        private const val TARGET_FOUR_PKG_NAME = "$TARGET_PKG_PREFIX.four"
        private const val TARGET_FOUR_NO_AUTO_VERIFY = "${TARGET_APK_PREFIX}4NoAutoVerify.apk"
        private const val TARGET_FOUR_WILDCARD = "${TARGET_APK_PREFIX}4Wildcard.apk"
        private const val TARGET_FOUR_WILDCARD_NO_AUTO_VERIFY =
                "${TARGET_APK_PREFIX}4WildcardNoAutoVerify.apk"

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    private val tempFolder = TemporaryFolder()
    private val preparer: SystemPreparer = SystemPreparer(tempFolder,
            SystemPreparer.RebootStrategy.FULL, deviceRebootRule) { this.device }

    @Rule
    @JvmField
    val rules = RuleChain.outerRule(tempFolder).around(preparer)!!

    private val permissionsFile = File("/system/etc/permissions" +
            "/privapp-PackageManagerIntentFilterVerificationTest-permissions.xml")

    @Before
    fun cleanupAndPushPermissionsFile() {
        // In order for the test app to be the verification agent, it needs a permission file
        // which can be pushed onto the system and removed afterwards.
        val file = tempFolder.newFile().apply {
            """
                <permissions>
                    <privapp-permissions package="$VERIFIER_PKG_NAME">
                        <permission name="android.permission.INTENT_FILTER_VERIFICATION_AGENT"/>
                    </privapp-permissions>
                </permissions>
            """
                    .trimIndent()
                    .let { writeText(it) }
        }
        device.uninstallPackages(TARGET_ONE_PKG_NAME, TARGET_TWO_PKG_NAME, TARGET_THREE_PKG_NAME,
                TARGET_FOUR_PKG_NAME)
        preparer.pushApk(VERIFIER, Partition.SYSTEM_PRIVILEGED)
                .pushFile(file, permissionsFile.toString())
                .reboot()
        runTest("clearResponse")
    }

    @After
    fun cleanupAndDeletePermissionsFile() {
        device.uninstallPackages(TARGET_ONE_PKG_NAME, TARGET_TWO_PKG_NAME, TARGET_THREE_PKG_NAME,
                TARGET_FOUR_PKG_NAME)
        preparer.deleteApkFolders(Partition.SYSTEM_PRIVILEGED, VERIFIER)
                .deleteFile(permissionsFile.toString())
        device.reboot()
    }

    @Test
    fun verifyOne() {
        installPackage(TARGET_ONE)

        assertReceivedRequests(true, VerifyRequest(
                scheme = "https",
                hosts = listOf(
                        "https_only.pm.server.android.com",
                        "other_activity.pm.server.android.com",
                        "http_only.pm.server.android.com",
                        "verify.pm.server.android.com",
                        "https_plus_non_web_scheme.pm.server.android.com",
                        "multiple.pm.server.android.com",
                        // TODO(b/159952358): the following domain should not be
                        //  verified, this is because the verifier tries to verify all web domains,
                        //  even in intent filters not marked for auto verify
                        "no_verify.pm.server.android.com"
                ),
                packageName = TARGET_ONE_PKG_NAME
        ))

        runTest(StartActivityParams(
                uri = "https://https_only.pm.server.android.com",
                expected = "$TARGET_ONE_PKG_NAME.TargetActivity"
        ))
    }

    @Test
    fun nonWebScheme() {
        installPackage(TARGET_TWO)
        assertReceivedRequests(null)
    }

    @Test
    fun verifyHttpNonSecureOnly() {
        installPackage(TARGET_THREE)
        assertReceivedRequests(true, VerifyRequest(
                scheme = "https",
                hosts = listOf(
                        "multiple.pm.server.android.com"
                ),
                packageName = TARGET_THREE_PKG_NAME
        ))

        runTest(StartActivityParams(
                uri = "http://multiple.pm.server.android.com",
                expected = "$TARGET_THREE_PKG_NAME.TargetActivity"
        ))
    }

    @Test
    fun multipleResults() {
        installPackage(TARGET_ONE)
        installPackage(TARGET_THREE)
        assertReceivedRequests(true, VerifyRequest(
                scheme = "https",
                hosts = listOf(
                        "https_only.pm.server.android.com",
                        "other_activity.pm.server.android.com",
                        "http_only.pm.server.android.com",
                        "verify.pm.server.android.com",
                        "https_plus_non_web_scheme.pm.server.android.com",
                        "multiple.pm.server.android.com",
                        // TODO(b/159952358): the following domain should not be
                        //  verified, this is because the verifier tries to verify all web domains,
                        //  even in intent filters not marked for auto verify
                        "no_verify.pm.server.android.com"
                ),
                packageName = TARGET_ONE_PKG_NAME
        ), VerifyRequest(
                scheme = "https",
                hosts = listOf(
                        "multiple.pm.server.android.com"
                ),
                packageName = TARGET_THREE_PKG_NAME
        ))

        // Target3 declares http non-s, so it should be included in the set here
        runTest(StartActivityParams(
                uri = "http://multiple.pm.server.android.com",
                expected = listOf(
                        "$TARGET_ONE_PKG_NAME.TargetActivity2",
                        "$TARGET_THREE_PKG_NAME.TargetActivity"
                )
        ))

        // But it excludes https, so it shouldn't resolve here
        runTest(StartActivityParams(
                uri = "https://multiple.pm.server.android.com",
                expected = "$TARGET_ONE_PKG_NAME.TargetActivity2"
        ))

        // Remove Target3 and return to single verified Target1 app for http non-s
        device.uninstallPackage(TARGET_THREE_PKG_NAME)
        runTest(StartActivityParams(
                uri = "http://multiple.pm.server.android.com",
                expected = "$TARGET_ONE_PKG_NAME.TargetActivity2"
        ))
    }

    @Test
    fun demoteAlways() {
        installPackage(TARGET_FOUR_BASE)
        assertReceivedRequests(false, VerifyRequest(
                scheme = "https",
                host = "failing.pm.server.android.com",
                packageName = TARGET_FOUR_PKG_NAME
        ))

        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity",
                withBrowsers = true
        ))
        runTest(SetActivityAsAlwaysParams(
                uri = "https://failing.pm.server.android.com",
                packageName = TARGET_FOUR_PKG_NAME,
                activityName = "$TARGET_FOUR_PKG_NAME.TargetActivity"
        ))
        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity"
        ))

        // Re-installing with same host/verify set will maintain always setting
        installPackage(TARGET_FOUR_BASE)
        assertReceivedRequests(null)
        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity"
        ))

        // Installing with new wildcard host will downgrade out of always, re-including browsers
        installPackage(TARGET_FOUR_WILDCARD)

        // TODO(b/159952358): The first request without the wildcard should not be sent. This is
        //  caused by the request being queued even if it should be dropped from the previous
        //  install case since the host set didn't change.
        assertReceivedRequests(false, VerifyRequest(
                scheme = "https",
                hosts = listOf("failing.pm.server.android.com"),
                packageName = TARGET_FOUR_PKG_NAME
        ), VerifyRequest(
                scheme = "https",
                hosts = listOf("failing.pm.server.android.com", "wildcard.tld"),
                packageName = TARGET_FOUR_PKG_NAME
        ))
        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity",
                withBrowsers = true
        ))
    }

    @Test
    fun unverifiedReinstallResendRequest() {
        installPackage(TARGET_FOUR_BASE)
        assertReceivedRequests(false, VerifyRequest(
                scheme = "https",
                host = "failing.pm.server.android.com",
                packageName = TARGET_FOUR_PKG_NAME
        ))

        installPackage(TARGET_FOUR_BASE)

        assertReceivedRequests(false, VerifyRequest(
                scheme = "https",
                host = "failing.pm.server.android.com",
                packageName = TARGET_FOUR_PKG_NAME
        ))
    }

    @Test
    fun unverifiedUpdateRemovingDomainNoRequestDemoteAlways() {
        installPackage(TARGET_FOUR_WILDCARD)
        assertReceivedRequests(false, VerifyRequest(
                scheme = "https",
                hosts = listOf("failing.pm.server.android.com", "wildcard.tld"),
                packageName = TARGET_FOUR_PKG_NAME
        ))

        runTest(SetActivityAsAlwaysParams(
                uri = "https://failing.pm.server.android.com",
                packageName = TARGET_FOUR_PKG_NAME,
                activityName = "$TARGET_FOUR_PKG_NAME.TargetActivity"
        ))

        // Re-installing with a smaller host/verify set will not request re-verification
        installPackage(TARGET_FOUR_BASE)
        assertReceivedRequests(null)
        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity"
        ))

        // Re-installing with a (now) larger host/verify set will re-request and demote
        installPackage(TARGET_FOUR_WILDCARD)
        // TODO(b/159952358): The first request should not be sent. This is caused by the request
        //  being queued even if it should be dropped from the previous install case.
        assertReceivedRequests(false, VerifyRequest(
                scheme = "https",
                host = "failing.pm.server.android.com",
                packageName = TARGET_FOUR_PKG_NAME
        ), VerifyRequest(
                scheme = "https",
                hosts = listOf("failing.pm.server.android.com", "wildcard.tld"),
                packageName = TARGET_FOUR_PKG_NAME
        ))

        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity",
                withBrowsers = true
        ))
    }

    // TODO(b/159952358): I would expect this to demote
    // TODO(b/32810168)
    @Test
    fun verifiedUpdateRemovingAutoVerifyMaintainsAlways() {
        installPackage(TARGET_FOUR_BASE)
        assertReceivedRequests(true, VerifyRequest(
                scheme = "https",
                host = "failing.pm.server.android.com",
                packageName = TARGET_FOUR_PKG_NAME
        ))

        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity"
        ))

        installPackage(TARGET_FOUR_NO_AUTO_VERIFY)
        assertReceivedRequests(null)

        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity"
        ))
    }

    @Test
    fun verifiedUpdateRemovingAutoVerifyAddingDomainDemotesAlways() {
        installPackage(TARGET_FOUR_BASE)

        assertReceivedRequests(true, VerifyRequest(
                scheme = "https",
                host = "failing.pm.server.android.com",
                packageName = TARGET_FOUR_PKG_NAME
        ))

        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity"
        ))

        installPackage(TARGET_FOUR_WILDCARD_NO_AUTO_VERIFY)
        assertReceivedRequests(null)

        runTest(StartActivityParams(
                uri = "https://failing.pm.server.android.com",
                expected = "$TARGET_FOUR_PKG_NAME.TargetActivity",
                withBrowsers = true
        ))
    }

    private fun installPackage(javaResourceName: String) {
        // Need to pass --user as verification is not currently run for all user installs
        assertThat(device.installJavaResourceApk(tempFolder, javaResourceName,
                extraArgs = arrayOf("--user", device.currentUser.toString()))).isNull()
    }

    private fun assertReceivedRequests(success: Boolean?, vararg expected: VerifyRequest?) {
        // TODO(b/159952358): This can probably be less than 10
        // Because tests have to assert that multiple broadcasts aren't received, there's no real
        // better way to await for a value than sleeping for a long enough time.
        TimeUnit.SECONDS.sleep(10)

        val params = mutableMapOf<String, String>()
        if (expected.any { it != null }) {
            params["expected"] = expected.filterNotNull()
                    .joinToString(separator = "") { it.serializeToString() }
        }
        runTest("compareLastReceived", params)

        if (success != null) {
            if (success) {
                runTest("verifyPreviousReceivedSuccess")
            } else {
                runTest("verifyPreviousReceivedFailure")
            }
            runTest("clearResponse")
        }
    }

    private fun runTest(params: IntentVerifyTestParams) =
            runTest(params.methodName, params.toArgsMap())

    private fun runTest(testName: String, args: Map<String, String> = emptyMap()) {
        val escapedArgs = args.mapValues {
            // Need to escape strings so that args are passed properly through the shell command
            "\"${it.value.trim('"')}\""
        }
        runDeviceTests(device, null, VERIFIER_PKG_NAME, "$VERIFIER_PKG_NAME.VerifyReceiverTest",
                testName, null, 10 * 60 * 1000L, 10 * 60 * 1000L, 0L, true, false, escapedArgs)
    }
}
