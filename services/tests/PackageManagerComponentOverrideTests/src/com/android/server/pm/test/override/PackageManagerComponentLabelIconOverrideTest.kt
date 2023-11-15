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

package com.android.server.pm.test.override

import android.app.PropertyInvalidatedCache
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.UserHandle
import android.util.ArrayMap
import com.android.internal.pm.pkg.component.ParsedActivity
import com.android.server.pm.AppsFilterImpl
import com.android.server.pm.PackageManagerService
import com.android.server.pm.PackageManagerServiceInjector
import com.android.server.pm.PackageManagerServiceTestParams
import com.android.server.pm.PackageManagerTracedLock
import com.android.server.pm.PackageSetting
import com.android.server.pm.PendingPackageBroadcasts
import com.android.server.pm.Settings
import com.android.server.pm.SharedLibrariesImpl
import com.android.server.pm.UserManagerInternal
import com.android.server.pm.UserManagerService
import com.android.server.pm.parsing.pkg.AndroidPackageInternal
import com.android.server.pm.parsing.pkg.PackageImpl
import com.android.server.pm.parsing.pkg.ParsedPackage
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.resolution.ComponentResolver
import com.android.server.pm.snapshot.PackageDataSnapshot
import com.android.server.pm.test.override.PackageManagerComponentLabelIconOverrideTest.Companion.Params.AppType
import com.android.server.testutils.TestHandler
import com.android.server.testutils.mock
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.android.server.wm.ActivityTaskManagerInternal
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.intThat
import org.mockito.Mockito.same
import org.testng.Assert.assertThrows
import java.io.File
import java.util.UUID

@RunWith(Parameterized::class)
class PackageManagerComponentLabelIconOverrideTest {

    companion object {
        private const val VALID_PKG = "com.android.server.pm.test.override"
        private const val SHARED_PKG = "com.android.server.pm.test.override.shared"
        private const val INVALID_PKG = "com.android.server.pm.test.override.invalid"
        private const val NON_EXISTENT_PKG = "com.android.server.pm.test.override.nonexistent"

        private const val SEND_PENDING_BROADCAST = 1 // PackageManagerService.SEND_PENDING_BROADCAST

        private const val DEFAULT_LABEL = "DefaultLabel"
        private const val TEST_LABEL = "TestLabel"

        private const val DEFAULT_ICON = R.drawable.black16x16
        private const val TEST_ICON = R.drawable.white16x16

        private const val COMPONENT_CLASS_NAME = ".TestComponent"

        sealed class Result {
            // Component label/icon changed, message sent to send broadcast
            object Changed : Result()

            // Component label/icon changed, message was pending, not re-sent
            object ChangedWithoutNotify : Result()

            // Component label/icon did not changed, was already equivalent
            object NotChanged : Result()

            // Updating label/icon encountered a specific exception
            data class Exception(val type: Class<out java.lang.Exception>) : Result()
        }

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun parameters() = arrayOf(
                // Start with an array of the simplest known inputs and expected outputs
                Params(VALID_PKG, AppType.SYSTEM_APP, Result.Changed),
                Params(SHARED_PKG, AppType.SYSTEM_APP, Result.Changed),
                Params(INVALID_PKG, AppType.SYSTEM_APP, SecurityException::class.java),
                Params(NON_EXISTENT_PKG, AppType.SYSTEM_APP, SecurityException::class.java)
        )
                .flatMap { param ->
                    mutableListOf(param).apply {
                        if (param.result is Result.Changed) {
                            // For each param that would've succeeded, also verify that if a change
                            // happened, but a message was pending, another is not re-queued/reset
                            this += param.copy(result = Result.ChangedWithoutNotify)
                            // Also verify that when the component is already configured, no change
                            // is propagated
                            this += param.copy(result = Result.NotChanged)
                        }
                        // For all params, verify that an invalid component will cause an
                        // IllegalArgumentException, instead of result initially specified
                        this += param.copy(componentName = null,
                                result = Result.Exception(IllegalArgumentException::class.java))
                        // Also verify an updated system app variant, which should have the same
                        // result as a vanilla system app
                        this += param.copy(appType = AppType.UPDATED_SYSTEM_APP)
                        // Also verify a non-system app will cause a failure, since normal apps
                        // are not allowed to edit their label/icon
                        this += param.copy(appType = AppType.NORMAL_APP,
                                result = Result.Exception(SecurityException::class.java))
                    }
                }

        @BeforeClass
        @JvmStatic
        fun disablePropertyInvalidatedCache() {
            // Disable binder caches in this process.
            PropertyInvalidatedCache.disableForTestMode()
        }

        data class Params(
            val pkgName: String,
            private val appType: AppType,
            val result: Result,
            val componentName: ComponentName? = ComponentName(pkgName, COMPONENT_CLASS_NAME)
        ) {
            constructor(pkgName: String, appType: AppType, exception: Class<out Exception>) :
                    this(pkgName, appType, Result.Exception(exception))

            val expectedLabel = when (result) {
                Result.Changed, Result.ChangedWithoutNotify, Result.NotChanged -> TEST_LABEL
                is Result.Exception -> DEFAULT_LABEL
            }

            val expectedIcon = when (result) {
                Result.Changed, Result.ChangedWithoutNotify, Result.NotChanged -> TEST_ICON
                is Result.Exception -> DEFAULT_ICON
            }

            val isUpdatedSystemApp = appType == AppType.UPDATED_SYSTEM_APP
            val isSystem = appType == AppType.SYSTEM_APP || isUpdatedSystemApp

            override fun toString(): String {
                val resultString = when (result) {
                    Result.Changed -> "Changed"
                    Result.ChangedWithoutNotify -> "ChangedWithoutNotify"
                    Result.NotChanged -> "NotChanged"
                    is Result.Exception -> result.type.simpleName
                }

                // Nicer formatting for the test method suffix
                return "pkg=$pkgName, type=$appType, component=$componentName, result=$resultString"
            }

            enum class AppType { SYSTEM_APP, UPDATED_SYSTEM_APP, NORMAL_APP }
        }
    }

    @Parameterized.Parameter(0)
    lateinit var params: Params

    private lateinit var mockPendingBroadcasts: PendingPackageBroadcasts
    private lateinit var mockPkg: AndroidPackageInternal
    private lateinit var mockPkgSetting: PackageSetting
    private lateinit var service: PackageManagerService

    private val testHandler = TestHandler(null)
    private val userId = UserHandle.getCallingUserId()
    private val userIdDifferent = userId + 1

    @Before
    fun setUpMocks() {
        makeTestData()

        mockPendingBroadcasts = PendingPackageBroadcasts()
        service = mockService()

        testHandler.clear()

        if (params.result is Result.ChangedWithoutNotify) {
            // Case where the handler already has a message and so another should not be sent.
            // This case will verify that only 1 message exists, which is the one added here.
            testHandler.sendEmptyMessage(SEND_PENDING_BROADCAST)
        }
    }

    @Test
    fun updateComponentLabelIcon() {
        fun runUpdate() {
            service.updateComponentLabelIcon(params.componentName, TEST_LABEL, TEST_ICON, userId)
        }

        when (val result = params.result) {
            Result.Changed, Result.ChangedWithoutNotify, Result.NotChanged -> {
                runUpdate()
                mockPkgSetting.getUserStateOrDefault(userId)
                    .getOverrideLabelIconForComponent(params.componentName!!)
                    .let {
                        assertThat(it?.first).isEqualTo(TEST_LABEL)
                        assertThat(it?.second).isEqualTo(TEST_ICON)
                    }
            }
            is Result.Exception -> {
                assertThrows(result.type) { runUpdate() }
            }
        }
    }

    @After
    fun verifyExpectedResult() {
        assertServiceInitialized() ?: return
        if (params.componentName != null && params.result !is Result.Exception) {
            // Suppress so that failures in @After don't override the actual test failure
            @Suppress("UNNECESSARY_SAFE_CALL")
            service?.let {
                val activityInfo = it.snapshotComputer()
                    .getActivityInfo(params.componentName, 0, userId)
                assertThat(activityInfo?.nonLocalizedLabel).isEqualTo(params.expectedLabel)
                assertThat(activityInfo?.icon).isEqualTo(params.expectedIcon)
            }
        }
    }

    @After
    fun verifyDifferentUserUnchanged() {
        assertServiceInitialized() ?: return
        when (params.result) {
            Result.Changed, Result.ChangedWithoutNotify -> {
                // Suppress so that failures in @After don't override the actual test failure
                @Suppress("UNNECESSARY_SAFE_CALL")
                service?.let {
                    val activityInfo = it.snapshotComputer()
                        ?.getActivityInfo(params.componentName, 0, userIdDifferent)
                    assertThat(activityInfo?.nonLocalizedLabel).isEqualTo(DEFAULT_LABEL)
                    assertThat(activityInfo?.icon).isEqualTo(DEFAULT_ICON)
                }
            }
            Result.NotChanged, is Result.Exception -> {}
        }.run { /*exhaust*/ }
    }

    @After
    fun verifyHandlerHasMessage() {
        assertServiceInitialized() ?: return
        when (params.result) {
            is Result.Changed, is Result.ChangedWithoutNotify -> {
                assertThat(testHandler.pendingMessages).hasSize(1)
                assertThat(testHandler.pendingMessages.first().message.what)
                        .isEqualTo(SEND_PENDING_BROADCAST)
            }
            is Result.NotChanged, is Result.Exception -> {
                assertThat(testHandler.pendingMessages).hasSize(0)
            }
        }.run { /*exhaust*/ }
    }

    @After
    fun verifyPendingBroadcast() {
        assertServiceInitialized() ?: return
        when (params.result) {
            is Result.Changed, Result.ChangedWithoutNotify -> {
                assertThat(mockPendingBroadcasts.copiedMap()?.get(userId)?.get(params.pkgName)
                    ?: emptyList<String>())
                        .containsExactly(params.componentName!!.className)
                        .inOrder()
            }
            is Result.NotChanged, is Result.Exception -> {
                assertThat(mockPendingBroadcasts.copiedMap()?.get(userId)?.get(params.pkgName))
                    .isNull()
            }
        }.run { /*exhaust*/ }
    }

    private fun makePkg(pkgName: String, block: ParsedPackage.() -> Unit = {}) =
            PackageImpl.forTesting(pkgName)
                    .setEnabled(true)
                    .let { it.hideAsParsed() as ParsedPackage }
                    .setSystem(params.isSystem)
                    .apply(block)
                    .hideAsFinal()

    private fun makePkgSetting(pkgName: String, pkg: AndroidPackageInternal) =
        PackageSetting(pkgName, null, File("/test"), 0, 0,
                UUID.fromString("3f9d52b7-d7b4-406a-a1da-d9f19984c72c"))
        .apply {
            if (params.isSystem) {
                this.flags = this.flags or ApplicationInfo.FLAG_SYSTEM
            }
            this.pkgState.isUpdatedSystemApp = params.isUpdatedSystemApp
            setPkg(pkg)
        }

    private fun makeTestData() {
        mockPkg = makePkg(params.pkgName)
        mockPkgSetting = makePkgSetting(params.pkgName, mockPkg)

        if (params.result is Result.NotChanged) {
            // If verifying no-op behavior, set the current setting to the test values
            mockPkgSetting.overrideNonLocalizedLabelAndIcon(params.componentName!!, TEST_LABEL,
                    TEST_ICON, userId)
        }
    }

    private fun mockService(): PackageManagerService {
        val mockedPkgs = mapOf(
                // Must use the test app's UID so that PMS can match them when querying, since
                // the static Binder.getCallingUid can't mocked as it's marked final
                VALID_PKG to makePkg(VALID_PKG) { uid = Binder.getCallingUid() },
                SHARED_PKG to makePkg(SHARED_PKG) { uid = Binder.getCallingUid() },
                INVALID_PKG to makePkg(INVALID_PKG) { uid = Binder.getCallingUid() + 1 }
        )
        val mockedPkgSettings = mutableMapOf(
                VALID_PKG to makePkgSetting(VALID_PKG, mockedPkgs[VALID_PKG]!!),
                SHARED_PKG to makePkgSetting(SHARED_PKG, mockedPkgs[SHARED_PKG]!!),
                INVALID_PKG to makePkgSetting(INVALID_PKG, mockedPkgs[INVALID_PKG]!!)
        )

        var mockActivity: ParsedActivity? = null
        if (mockedPkgSettings.containsKey(params.pkgName)) {
            // Add pkgSetting under test so its attributes override the defaults added above
            mockedPkgSettings.put(params.pkgName, mockPkgSetting)

            mockActivity = mock<ParsedActivity> {
                whenever(this.packageName) { params.pkgName }
                whenever(this.nonLocalizedLabel) { DEFAULT_LABEL }
                whenever(this.icon) { DEFAULT_ICON }
                whenever(this.componentName) { params.componentName }
                whenever(this.name) { params.componentName?.className }
                whenever(this.isEnabled) { true }
                whenever(this.isDirectBootAware) { params.isSystem }
            }
        }

        val mockSettings = Settings(mockedPkgSettings)
        val mockComponentResolver: ComponentResolver = mockThrowOnUnmocked {
            params.componentName?.let {
                doReturn(mockActivity != null).`when`(this).componentExists(same(it))
                doReturn(mockActivity).`when`(this).getActivity(same(it))
            }
            whenever(this.snapshot()) { this@mockThrowOnUnmocked }
            whenever(registerObserver(any())).thenCallRealMethod()
        }
        val mockUserManagerService: UserManagerService = mockThrowOnUnmocked {
            val matcher: (Int) -> Boolean = { it == userId || it == userIdDifferent }
            whenever(this.exists(intThat(matcher))) { true }
        }
        val mockUserManagerInternal: UserManagerInternal = mockThrowOnUnmocked {
            val matcher: (Int) -> Boolean = { it == userId || it == userIdDifferent }
            whenever(this.isUserUnlockingOrUnlocked(intThat(matcher))) { true }
        }
        val mockActivityTaskManager: ActivityTaskManagerInternal = mockThrowOnUnmocked {
            whenever(this.isCallerRecents(anyInt())) { false }
        }
        val mockAppsFilter: AppsFilterImpl = mockThrowOnUnmocked {
            whenever(this.shouldFilterApplication(any<PackageDataSnapshot>(), anyInt(),
                    any<PackageSetting>(), any<PackageSetting>(), anyInt())) { false }
            whenever(this.snapshot()) { this@mockThrowOnUnmocked }
            whenever(registerObserver(any())).thenCallRealMethod()
        }
        val mockContext: Context = mockThrowOnUnmocked {
            whenever(this.getString(
                    com.android.internal.R.string.config_overrideComponentUiPackage)) { VALID_PKG }
            whenever(this.checkCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)) {
                PackageManager.PERMISSION_GRANTED
            }
        }
        val mockSharedLibrariesImpl: SharedLibrariesImpl = mock {
            whenever(this.snapshot()) { this@mock }
        }
        val mockInjector: PackageManagerServiceInjector = mock {
            whenever(this.lock) { PackageManagerTracedLock() }
            whenever(this.componentResolver) { mockComponentResolver }
            whenever(this.userManagerService) { mockUserManagerService }
            whenever(this.userManagerInternal) { mockUserManagerInternal }
            whenever(this.settings) { mockSettings }
            whenever(this.getLocalService(ActivityTaskManagerInternal::class.java)) {
                mockActivityTaskManager
            }
            whenever(this.appsFilter) { mockAppsFilter }
            whenever(this.context) { mockContext }
            whenever(this.handler) { testHandler }
            whenever(this.sharedLibrariesImpl) { mockSharedLibrariesImpl }
        }
        val testParams = PackageManagerServiceTestParams().apply {
            this.pendingPackageBroadcasts = mockPendingBroadcasts
            this.resolveComponentName = ComponentName("android", ".Test")
            this.packages = ArrayMap<String, AndroidPackage>().apply { putAll(mockedPkgs) }
            this.instantAppRegistry = mock()
        }

        return PackageManagerService(mockInjector, testParams)
    }

    // If service isn't initialized, then test setup failed and @Afters should be skipped
    private fun assertServiceInitialized() = Unit.takeIf { ::service.isInitialized }
}
