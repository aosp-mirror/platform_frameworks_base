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

import android.Manifest
import android.app.Instrumentation
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller.SessionParams
import android.os.Handler
import android.os.HandlerThread
import android.platform.test.annotations.Presubmit
import android.util.Log
import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import com.android.compatibility.common.util.ShellIdentityUtils
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.After
import org.testng.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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

        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"
        private const val INTENT_ACTION = "com.android.server.pm.test.test_app.action"
    }

    private val TAG = "PackageSessionTests"
    private val context: Context = InstrumentationRegistry.getContext()
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val installer = context.packageManager.packageInstaller
    private var callback: SessionStatusTrackerCallback? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val receiver = object : BroadcastReceiver() {
        private val results = ArrayBlockingQueue<Intent>(1)

        override fun onReceive(context: Context, intent: Intent) {
            // Added as a safety net. Have observed instances where the Queue isn't empty which
            // causes the test suite to crash.
            if (results.size != 0) {
                clear()
            }
            results.add(intent)
        }

        fun makeIntentSender(sessionId: Int) = PendingIntent.getBroadcast(context, sessionId,
                Intent(INTENT_ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT
                        or PendingIntent.FLAG_MUTABLE).intentSender

        fun getResult(unit: TimeUnit, timeout: Long) = results.poll(timeout, unit)

        fun clear() = results.clear()
    }

    class SessionStatusTrackerCallback : PackageInstaller.SessionCallback {
        private val TAG: String = "SessionStatusTrackerCallback"
        private val DEFAULT_TIMEOUT: Long = 30
        private var mSessionActiveLatch: CountDownLatch? = null
        private var mSessionInactiveLatch: CountDownLatch? = null
        private var mSessionFinishLatch: CountDownLatch? = null
        private val mSessionIds = mutableSetOf<Int>()

        constructor(sessionActiveCount: Int = 0, sessionInactiveCount: Int = 0) {
            this.mSessionActiveLatch = CountDownLatch(sessionActiveCount)
            this.mSessionInactiveLatch = CountDownLatch(sessionInactiveCount)
        }

        constructor(sessionFinishCount: Int = 0) {
            this.mSessionFinishLatch = CountDownLatch(sessionFinishCount)
        }

        override fun onCreated(sessionId: Int) {
            mSessionIds.add(sessionId)
        }

        override fun onBadgingChanged(sessionId: Int) {}

        override fun onActiveChanged(sessionId: Int, active: Boolean) {
            if (mSessionIds.contains(sessionId)) {
                if (active) {
                    mSessionActiveLatch?.countDown()
                } else {
                    mSessionInactiveLatch?.countDown()
                }
            } else {
                Log.d(TAG, "Did not find session ID $sessionId which was opened.")
            }
        }

        override fun onProgressChanged(sessionId: Int, progress: Float) {}

        override fun onFinished(sessionId: Int, success: Boolean) {
            if (!success and mSessionIds.contains(sessionId)) {
                mSessionFinishLatch?.countDown()
            }
        }

        fun awaitSessionActiveCallbacks() {
            mSessionActiveLatch?.await(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        }

        fun awaitSessionInactiveCallbacks() {
            mSessionInactiveLatch?.await(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        }

        fun awaitSessionFinishCallbacks() {
            mSessionFinishLatch?.await(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        }

        fun resetLatches() {
            mSessionActiveLatch = null
            mSessionInactiveLatch = null
            mSessionFinishLatch = null
        }
    }

    @Before
    fun registerReceiver() {
        receiver.clear()
        context.registerReceiver(receiver, IntentFilter(INTENT_ACTION))
    }

    @After
    fun unregisterReceiver() {
        context.unregisterReceiver(receiver)
    }

    @Before
    @After
    fun abandonAllSessions() {
        instrumentation.uiAutomation
                .executeShellCommand("pm uninstall $TEST_PKG_NAME")

        installer.mySessions.asSequence()
                .map { it.sessionId }
                .forEach {
                    try {
                        installer.abandonSession(it)
                    } catch (ignored: Exception) {
                        Log.e(TAG, "Abandon failed: ", ignored)
                        // Querying for sessions checks by calling package name, but abandoning
                        // checks by UID, which won't match if this test failed to clean up
                        // on a previous install + run + uninstall, so ignore these failures.
                    }
                }

        // Abandoning sessions created by the @LargeTest takes time. We must ensure that all
        // sessions are abandoned before proceeding with the next test. If all the sessions are not
        // abandoned before starting a new test, we may encounter an IllegalStateException
        callback?.apply {
            awaitSessionFinishCallbacks()
            resetLatches()
            installer.unregisterSessionCallback(this)
        }
    }

    @After
    fun revokeShellPermissionsAndCloseThread() {
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        handlerThread?.quitSafely()
    }

    @Test
    fun truncateAppLabel() {
        val longLabel = invalidAppLabel()
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setAppLabel(longLabel)
        }

        createAndAbandonSession(params) {
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

        createAndAbandonSession(params) {
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

        createAndAbandonSession(params) {
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

        createAndAbandonSession(params) {
            assertThat(installer.getSessionInfo(it)?.whitelistedRestrictedPermissions!!)
                    .containsExactlyElementsIn(RESTRICTED_PERMISSIONS)
        }
    }

    @Test
    fun fillPackageNameWithParsedValue() {
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        javaClass.classLoader.getResourceAsStream("PackageManagerTestAppVersion1.apk")!!
                .use { input ->
                    session.openWrite("base", 0, -1)
                            .use { output -> input.copyTo(output) }
                }

        // Test instrumentation doesn't have install permissions, so use shell
        ShellIdentityUtils.invokeWithShellPermissions {
            session.commit(receiver.makeIntentSender(sessionId))
        }
        session.close()

        // The actual contents aren't verified as part of this test. Only care about it finishing.
        val result = receiver.getResult(TimeUnit.SECONDS, 30)
        assertThat(result).isNotNull()

        val sessionInfo = installer.getSessionInfo(sessionId)
        assertThat(sessionInfo).isNotNull()
        assertThat(sessionInfo!!.getAppPackageName()).isEqualTo(TEST_PKG_NAME)
    }

    @LargeTest
    @Test
    fun allocateMaxSessionsWithPermission() {
        // Already invoking with shell permissions. Using the function to setup the handler
        setupHandlerAndPermissions(/* Need permissions? */false)

        callback = SessionStatusTrackerCallback(sessionFinishCount = 1024)

        installer.registerSessionCallback(callback!!, handler!!)

        ShellIdentityUtils.invokeWithShellPermissions {
            repeat(1024) { createDummySession() }
            assertThrows(IllegalStateException::class.java) { createDummySession() }
        }
    }

    @LargeTest
    @Test
    fun allocateMaxSessionsNoPermission() {
        setupHandlerAndPermissions(/* Need permissions? */false)

        callback = SessionStatusTrackerCallback(sessionFinishCount = 50)

        installer.registerSessionCallback(callback!!, handler!!)

        repeat(50) { createDummySession() }
        assertThrows(IllegalStateException::class.java) { createDummySession() }
    }

    @Test
    fun whenGrantedInstallPermission_sessionIsActive() {
        setupHandlerAndPermissions(true)

        val params = SessionParams(SessionParams.MODE_FULL_INSTALL)
        params.setRequireUserAction(SessionParams.USER_ACTION_REQUIRED)

        callback = SessionStatusTrackerCallback(sessionActiveCount = 2, sessionInactiveCount = 1)

        installer.registerSessionCallback(callback!!, handler!!)

        val sessionId = installer.createSession(params)
        // sessionActiveLatch counts down once when a session is opened
        val session = installer.openSession(sessionId)

        javaClass.classLoader.getResourceAsStream("PackageManagerTestAppVersion1.apk")!!
                .use { input ->
                    session.openWrite("base", 0, -1)
                            .use { output -> input.copyTo(output) }
                }

        session.commit(receiver.makeIntentSender(sessionId))
        session.close()

        // Wait till we get one session change callback to with status 'inactive'
        callback?.awaitSessionInactiveCallbacks()

        val installStatus = receiver.getResult(TimeUnit.SECONDS, 30)
                .getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        if (installStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            installer.setPermissionsResult(sessionId, true)
        } else {
            fail("Did not wait for user action")
        }

        // sessionActiveLatch counts down the second time when install permission is granted.
        // At this time, the latch opens.
        callback?.awaitSessionActiveCallbacks()
        assertThat(installer.getSessionInfo(sessionId)!!.isActive).isEqualTo(true)
    }

    @Test
    fun whenWaitingForUserAction_sessionIsInactive() {
        setupHandlerAndPermissions(true)

        val params = SessionParams(SessionParams.MODE_FULL_INSTALL)
        params.setRequireUserAction(SessionParams.USER_ACTION_REQUIRED)

        callback = SessionStatusTrackerCallback(sessionActiveCount = 1, sessionInactiveCount = 1)

        installer.registerSessionCallback(callback!!, handler!!)

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        // Wait till we get one session change callback to with status 'active'
        callback?.awaitSessionActiveCallbacks()

        javaClass.classLoader.getResourceAsStream("PackageManagerTestAppVersion1.apk")!!
                .use { input ->
                    session.openWrite("base", 0, -1)
                            .use { output -> input.copyTo(output) }
                }

        session.commit(receiver.makeIntentSender(sessionId))
        session.close()

        // Wait till we get one session change callback to with status 'inactive'
        callback?.awaitSessionInactiveCallbacks()

        val installStatus = receiver.getResult(TimeUnit.SECONDS, 30)
                .getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        assertThat(installStatus).isEqualTo(PackageInstaller.STATUS_PENDING_USER_ACTION)
        assertThat(installer.getSessionInfo(sessionId)!!.isActive).isEqualTo(false)
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

    private fun createAndAbandonSession(params: SessionParams, block: (Int) -> Unit = {}) {
        val sessionId = installer.createSession(params)
        try {
            block(sessionId)
        } finally {
            installer.abandonSession(sessionId)
        }
    }

    private fun setupHandlerAndPermissions(needPermissions: Boolean) {
        if (needPermissions) {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.REQUEST_INSTALL_PACKAGES,
                    Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION)
        }
        handlerThread = HandlerThread("PackageSessionTests")
        handlerThread?.start()
        handler = Handler(handlerThread?.looper)
    }
}
