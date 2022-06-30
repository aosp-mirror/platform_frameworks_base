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

package com.android.systemui.privacy

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.content.pm.UserInfo
import android.os.Process.SYSTEM_UID
import android.os.UserHandle
import android.permission.PermissionGroupUsage
import android.permission.PermissionManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpsController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class PrivacyDialogControllerTest : SysuiTestCase() {

    companion object {
        private const val USER_ID = 0
        private const val ENT_USER_ID = 10

        private const val TEST_PACKAGE_NAME = "test package name"
        private const val TEST_ATTRIBUTION_TAG = "test attribution tag"
        private const val TEST_PROXY_LABEL = "test proxy label"

        private const val PERM_CAMERA = android.Manifest.permission_group.CAMERA
        private const val PERM_MICROPHONE = android.Manifest.permission_group.MICROPHONE
        private const val PERM_LOCATION = android.Manifest.permission_group.LOCATION
    }

    @Mock
    private lateinit var dialog: PrivacyDialog
    @Mock
    private lateinit var permissionManager: PermissionManager
    @Mock
    private lateinit var packageManager: PackageManager
    @Mock
    private lateinit var privacyItemController: PrivacyItemController
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var privacyLogger: PrivacyLogger
    @Mock
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var appOpsController: AppOpsController
    @Captor
    private lateinit var dialogDismissedCaptor: ArgumentCaptor<PrivacyDialog.OnDialogDismissed>
    @Captor
    private lateinit var activityStartedCaptor: ArgumentCaptor<ActivityStarter.Callback>
    @Captor
    private lateinit var intentCaptor: ArgumentCaptor<Intent>
    @Mock
    private lateinit var uiEventLogger: UiEventLogger

    private val backgroundExecutor = FakeExecutor(FakeSystemClock())
    private val uiExecutor = FakeExecutor(FakeSystemClock())
    private lateinit var controller: PrivacyDialogController
    private var nextUid: Int = 0

    private val dialogProvider = object : PrivacyDialogController.DialogProvider {
        var list: List<PrivacyDialog.PrivacyElement>? = null
        var starter: ((String, Int, CharSequence?, Intent?) -> Unit)? = null

        override fun makeDialog(
            context: Context,
            list: List<PrivacyDialog.PrivacyElement>,
            starter: (String, Int, CharSequence?, Intent?) -> Unit
        ): PrivacyDialog {
            this.list = list
            this.starter = starter
            return dialog
        }
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        nextUid = 0
        setUpDefaultMockResponses()

        controller = PrivacyDialogController(
                permissionManager,
                packageManager,
                privacyItemController,
                userTracker,
                activityStarter,
                backgroundExecutor,
                uiExecutor,
                privacyLogger,
                keyguardStateController,
                appOpsController,
                uiEventLogger,
                dialogProvider
        )
    }

    @After
    fun tearDown() {
        FakeExecutor.exhaustExecutors(uiExecutor, backgroundExecutor)
        dialogProvider.list = null
        dialogProvider.starter = null
    }

    @Test
    fun testMicMutedParameter() {
        `when`(appOpsController.isMicMuted).thenReturn(true)
        controller.showDialog(context)
        backgroundExecutor.runAllReady()

        verify(permissionManager).getIndicatorAppOpUsageData(true)
    }

    @Test
    fun testPermissionManagerOnlyCalledInBackgroundThread() {
        controller.showDialog(context)
        verify(permissionManager, never()).getIndicatorAppOpUsageData(anyBoolean())
        backgroundExecutor.runAllReady()
        verify(permissionManager).getIndicatorAppOpUsageData(anyBoolean())
    }

    @Test
    fun testPackageManagerOnlyCalledInBackgroundThread() {
        val usage = createMockPermGroupUsage()
        `when`(usage.isPhoneCall).thenReturn(false)
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))

        controller.showDialog(context)
        verify(packageManager, never()).getApplicationInfoAsUser(anyString(), anyInt(), anyInt())
        backgroundExecutor.runAllReady()
        verify(packageManager, atLeastOnce())
                .getApplicationInfoAsUser(anyString(), anyInt(), anyInt())
    }

    @Test
    fun testShowDialogShowsDialog() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))

        controller.showDialog(context)
        exhaustExecutors()

        verify(dialog).show()
    }

    @Test
    fun testDontShowEmptyDialog() {
        controller.showDialog(context)
        exhaustExecutors()

        verify(dialog, never()).show()
    }

    @Test
    fun testHideDialogDismissesDialogIfShown() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)
        exhaustExecutors()

        controller.dismissDialog()
        verify(dialog).dismiss()
    }

    @Test
    fun testHideDialogNoopIfNotShown() {
        controller.dismissDialog()
        verify(dialog, never()).dismiss()
    }

    @Test
    fun testHideDialogNoopAfterDismissed() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)
        exhaustExecutors()

        verify(dialog).addOnDismissListener(capture(dialogDismissedCaptor))

        dialogDismissedCaptor.value.onDialogDismissed()
        controller.dismissDialog()
        verify(dialog, never()).dismiss()
    }

    @Test
    fun testShowForAllUsers() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)

        exhaustExecutors()
        verify(dialog).setShowForAllUsers(true)
    }

    @Test
    fun testSingleElementInList() {
        val usage = createMockPermGroupUsage(
                packageName = TEST_PACKAGE_NAME,
                uid = generateUidForUser(USER_ID),
                permissionGroupName = PERM_CAMERA,
                lastAccessTimeMillis = 5L,
                isActive = true,
                isPhoneCall = false,
                attributionTag = null,
                proxyLabel = TEST_PROXY_LABEL
        )
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))

        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.list?.let { list ->
            assertThat(list.get(0).type).isEqualTo(PrivacyType.TYPE_CAMERA)
            assertThat(list.get(0).packageName).isEqualTo(TEST_PACKAGE_NAME)
            assertThat(list.get(0).userId).isEqualTo(USER_ID)
            assertThat(list.get(0).applicationName).isEqualTo(TEST_PACKAGE_NAME)
            assertThat(list.get(0).attributionTag).isNull()
            assertThat(list.get(0).attributionLabel).isNull()
            assertThat(list.get(0).proxyLabel).isEqualTo(TEST_PROXY_LABEL)
            assertThat(list.get(0).lastActiveTimestamp).isEqualTo(5L)
            assertThat(list.get(0).active).isTrue()
            assertThat(list.get(0).phoneCall).isFalse()
            assertThat(list.get(0).enterprise).isFalse()
            assertThat(list.get(0).permGroupName).isEqualTo(PERM_CAMERA)
            assertThat(isIntentEqual(list.get(0).navigationIntent!!,
                    controller.getDefaultManageAppPermissionsIntent(TEST_PACKAGE_NAME, USER_ID)))
                    .isTrue()
        }
    }

    private fun isIntentEqual(actual: Intent, expected: Intent): Boolean {
        return actual.action == expected.action &&
                actual.getStringExtra(Intent.EXTRA_PACKAGE_NAME) ==
                expected.getStringExtra(Intent.EXTRA_PACKAGE_NAME) &&
                actual.getParcelableExtra(Intent.EXTRA_USER) as? UserHandle ==
                expected.getParcelableExtra(Intent.EXTRA_USER) as? UserHandle
    }

    @Test
    fun testTwoElementsDifferentType_sorted() {
        val usage_camera = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_camera",
                permissionGroupName = PERM_CAMERA
        )
        val usage_microphone = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_microphone",
                permissionGroupName = PERM_MICROPHONE
        )
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(
                listOf(usage_microphone, usage_camera)
        )

        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.list?.let { list ->
            assertThat(list).hasSize(2)
            assertThat(list.get(0).type.compareTo(list.get(1).type)).isLessThan(0)
        }
    }

    @Test
    fun testTwoElementsSameType_oneActive() {
        val usage_active = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_active",
                isActive = true
        )
        val usage_recent = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_recent",
                isActive = false
        )
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(
                listOf(usage_recent, usage_active)
        )

        controller.showDialog(context)
        exhaustExecutors()

        assertThat(dialogProvider.list).hasSize(1)
        assertThat(dialogProvider.list?.get(0)?.active).isTrue()
    }

    @Test
    fun testTwoElementsSameType_twoActive() {
        val usage_active = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_active",
                isActive = true,
                lastAccessTimeMillis = 0L
        )
        val usage_active_moreRecent = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_active_recent",
                isActive = true,
                lastAccessTimeMillis = 1L
        )
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(
                listOf(usage_active, usage_active_moreRecent)
        )
        controller.showDialog(context)
        exhaustExecutors()
        assertThat(dialogProvider.list).hasSize(2)
        assertThat(dialogProvider.list?.get(0)?.lastActiveTimestamp).isEqualTo(1L)
        assertThat(dialogProvider.list?.get(1)?.lastActiveTimestamp).isEqualTo(0L)
    }

    @Test
    fun testManyElementsSameType_bothRecent() {
        val usage_recent = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_recent",
                isActive = false,
                lastAccessTimeMillis = 0L
        )
        val usage_moreRecent = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_moreRecent",
                isActive = false,
                lastAccessTimeMillis = 1L
        )
        val usage_mostRecent = createMockPermGroupUsage(
                packageName = "${TEST_PACKAGE_NAME}_mostRecent",
                isActive = false,
                lastAccessTimeMillis = 2L
        )
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(
                listOf(usage_recent, usage_mostRecent, usage_moreRecent)
        )

        controller.showDialog(context)
        exhaustExecutors()

        assertThat(dialogProvider.list).hasSize(1)
        assertThat(dialogProvider.list?.get(0)?.lastActiveTimestamp).isEqualTo(2L)
    }

    @Test
    fun testMicAndCameraDisabled() {
        val usage_camera = createMockPermGroupUsage(
                permissionGroupName = PERM_CAMERA
        )
        val usage_microphone = createMockPermGroupUsage(
                permissionGroupName = PERM_MICROPHONE
        )
        val usage_location = createMockPermGroupUsage(
                permissionGroupName = PERM_LOCATION
        )

        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(
                listOf(usage_camera, usage_location, usage_microphone)
        )
        `when`(privacyItemController.micCameraAvailable).thenReturn(false)

        controller.showDialog(context)
        exhaustExecutors()

        assertThat(dialogProvider.list).hasSize(1)
        assertThat(dialogProvider.list?.get(0)?.type).isEqualTo(PrivacyType.TYPE_LOCATION)
    }

    @Test
    fun testLocationDisabled() {
        val usage_camera = createMockPermGroupUsage(
                permissionGroupName = PERM_CAMERA
        )
        val usage_microphone = createMockPermGroupUsage(
                permissionGroupName = PERM_MICROPHONE
        )
        val usage_location = createMockPermGroupUsage(
                permissionGroupName = PERM_LOCATION
        )

        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(
                listOf(usage_camera, usage_location, usage_microphone)
        )
        `when`(privacyItemController.locationAvailable).thenReturn(false)

        controller.showDialog(context)
        exhaustExecutors()

        assertThat(dialogProvider.list).hasSize(2)
        dialogProvider.list?.forEach {
            assertThat(it.type).isNotEqualTo(PrivacyType.TYPE_LOCATION)
        }
    }

    @Test
    fun testAllIndicatorsAvailable() {
        val usage_camera = createMockPermGroupUsage(
                permissionGroupName = PERM_CAMERA
        )
        val usage_microphone = createMockPermGroupUsage(
                permissionGroupName = PERM_MICROPHONE
        )
        val usage_location = createMockPermGroupUsage(
                permissionGroupName = PERM_LOCATION
        )

        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(
                listOf(usage_camera, usage_location, usage_microphone)
        )
        `when`(privacyItemController.micCameraAvailable).thenReturn(true)
        `when`(privacyItemController.locationAvailable).thenReturn(true)

        controller.showDialog(context)
        exhaustExecutors()

        assertThat(dialogProvider.list).hasSize(3)
    }

    @Test
    fun testNoIndicatorsAvailable() {
        val usage_camera = createMockPermGroupUsage(
                permissionGroupName = PERM_CAMERA
        )
        val usage_microphone = createMockPermGroupUsage(
                permissionGroupName = PERM_MICROPHONE
        )
        val usage_location = createMockPermGroupUsage(
                permissionGroupName = PERM_LOCATION
        )

        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(
                listOf(usage_camera, usage_location, usage_microphone)
        )
        `when`(privacyItemController.micCameraAvailable).thenReturn(false)
        `when`(privacyItemController.locationAvailable).thenReturn(false)

        controller.showDialog(context)
        exhaustExecutors()

        verify(dialog, never()).show()
    }

    @Test
    fun testEnterpriseUser() {
        val usage_enterprise = createMockPermGroupUsage(
                uid = generateUidForUser(ENT_USER_ID)
        )
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean()))
                .thenReturn(listOf(usage_enterprise))

        controller.showDialog(context)
        exhaustExecutors()

        assertThat(dialogProvider.list?.single()?.enterprise).isTrue()
    }

    @Test
    fun testNotCurrentUser() {
        val usage_other = createMockPermGroupUsage(
                uid = generateUidForUser(ENT_USER_ID + 1)
        )
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean()))
                .thenReturn(listOf(usage_other))

        controller.showDialog(context)
        exhaustExecutors()

        verify(dialog, never()).show()
    }

    @Test
    fun testStartActivityCorrectIntent() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.starter?.invoke(TEST_PACKAGE_NAME, USER_ID, null, null)
        verify(activityStarter)
                .startActivity(capture(intentCaptor), eq(true), any<ActivityStarter.Callback>())

        assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_MANAGE_APP_PERMISSIONS)
        assertThat(intentCaptor.value.getStringExtra(Intent.EXTRA_PACKAGE_NAME))
                .isEqualTo(TEST_PACKAGE_NAME)
        assertThat(intentCaptor.value.getParcelableExtra(Intent.EXTRA_USER) as? UserHandle)
                .isEqualTo(UserHandle.of(USER_ID))
    }

    @Test
    fun testStartActivityCorrectIntent_enterpriseUser() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.starter?.invoke(TEST_PACKAGE_NAME, ENT_USER_ID, null, null)
        verify(activityStarter)
                .startActivity(capture(intentCaptor), eq(true), any<ActivityStarter.Callback>())

        assertThat(intentCaptor.value.getParcelableExtra(Intent.EXTRA_USER) as? UserHandle)
                .isEqualTo(UserHandle.of(ENT_USER_ID))
    }

    @Test
    fun testStartActivitySuccess() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.starter?.invoke(TEST_PACKAGE_NAME, USER_ID, null, null)
        verify(activityStarter).startActivity(any(), eq(true), capture(activityStartedCaptor))

        activityStartedCaptor.value.onActivityStarted(ActivityManager.START_DELIVERED_TO_TOP)

        verify(dialog).dismiss()
    }

    @Test
    fun testStartActivityFailure() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.starter?.invoke(TEST_PACKAGE_NAME, USER_ID, null, null)
        verify(activityStarter).startActivity(any(), eq(true), capture(activityStartedCaptor))

        activityStartedCaptor.value.onActivityStarted(ActivityManager.START_ABORTED)

        verify(dialog, never()).dismiss()
    }

    @Test
    fun testCallOnSecondaryUser() {
        // Calls happen in
        val usage = createMockPermGroupUsage(uid = SYSTEM_UID, isPhoneCall = true)
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        `when`(userTracker.userProfiles).thenReturn(listOf(
                UserInfo(ENT_USER_ID, "", 0)
        ))

        controller.showDialog(context)
        exhaustExecutors()

        verify(dialog).show()
    }

    @Test
    fun testStartActivityLogs() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.starter?.invoke(TEST_PACKAGE_NAME, USER_ID, null, null)
        verify(uiEventLogger).log(PrivacyDialogEvent.PRIVACY_DIALOG_ITEM_CLICKED_TO_APP_SETTINGS,
                USER_ID, TEST_PACKAGE_NAME)
    }

    @Test
    fun testDismissedDialogLogs() {
        val usage = createMockPermGroupUsage()
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        controller.showDialog(context)
        exhaustExecutors()

        verify(dialog).addOnDismissListener(capture(dialogDismissedCaptor))

        dialogDismissedCaptor.value.onDialogDismissed()

        controller.dismissDialog()

        verify(uiEventLogger, times(1)).log(PrivacyDialogEvent.PRIVACY_DIALOG_DISMISSED)
    }

    @Test
    fun testInvalidAttributionTag() {
        val usage = createMockPermGroupUsage(
                packageName = TEST_PACKAGE_NAME,
                uid = generateUidForUser(USER_ID),
                permissionGroupName = PERM_CAMERA,
                lastAccessTimeMillis = 5L,
                isActive = true,
                isPhoneCall = false,
                attributionTag = "INVALID_ATTRIBUTION_TAG",
                proxyLabel = TEST_PROXY_LABEL
        )
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))

        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.list?.let { list ->
            assertThat(list.get(0).type).isEqualTo(PrivacyType.TYPE_CAMERA)
            assertThat(list.get(0).packageName).isEqualTo(TEST_PACKAGE_NAME)
            assertThat(list.get(0).userId).isEqualTo(USER_ID)
            assertThat(list.get(0).applicationName).isEqualTo(TEST_PACKAGE_NAME)
            assertThat(list.get(0).attributionTag).isEqualTo("INVALID_ATTRIBUTION_TAG")
            assertThat(list.get(0).attributionLabel).isNull()
            assertThat(list.get(0).proxyLabel).isEqualTo(TEST_PROXY_LABEL)
            assertThat(list.get(0).lastActiveTimestamp).isEqualTo(5L)
            assertThat(list.get(0).active).isTrue()
            assertThat(list.get(0).phoneCall).isFalse()
            assertThat(list.get(0).enterprise).isFalse()
            assertThat(list.get(0).permGroupName).isEqualTo(PERM_CAMERA)
            assertThat(isIntentEqual(list.get(0).navigationIntent!!,
                    controller.getDefaultManageAppPermissionsIntent(TEST_PACKAGE_NAME, USER_ID)))
                    .isTrue()
        }
    }

    @Test
    fun testCorrectIntentSubAttribution() {
        val usage = createMockPermGroupUsage(
                attributionTag = TEST_ATTRIBUTION_TAG,
                attributionLabel = "TEST_LABEL"
        )

        val activityInfo = createMockActivityInfo()
        val resolveInfo = createMockResolveInfo(activityInfo)
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        `when`(packageManager.resolveActivity(any(), any<ResolveInfoFlags>()))
                .thenAnswer { resolveInfo }
        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.list?.let { list ->
            val navigationIntent = list.get(0).navigationIntent!!
            assertThat(navigationIntent.action).isEqualTo(Intent.ACTION_MANAGE_PERMISSION_USAGE)
            assertThat(navigationIntent.getStringExtra(Intent.EXTRA_PERMISSION_GROUP_NAME))
                    .isEqualTo(PERM_CAMERA)
            assertThat(navigationIntent.getStringArrayExtra(Intent.EXTRA_ATTRIBUTION_TAGS))
                    .isEqualTo(arrayOf(TEST_ATTRIBUTION_TAG.toString()))
            assertThat(navigationIntent.getBooleanExtra(Intent.EXTRA_SHOWING_ATTRIBUTION, false))
                    .isTrue()
        }
    }

    @Test
    fun testDefaultIntentOnMissingAttributionLabel() {
        val usage = createMockPermGroupUsage(
                attributionTag = TEST_ATTRIBUTION_TAG
        )

        val activityInfo = createMockActivityInfo()
        val resolveInfo = createMockResolveInfo(activityInfo)
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        `when`(packageManager.resolveActivity(any(), any<ResolveInfoFlags>()))
                .thenAnswer { resolveInfo }
        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.list?.let { list ->
            assertThat(isIntentEqual(list.get(0).navigationIntent!!,
                    controller.getDefaultManageAppPermissionsIntent(TEST_PACKAGE_NAME, USER_ID)))
                    .isTrue()
        }
    }

    @Test
    fun testDefaultIntentOnIncorrectPermission() {
        val usage = createMockPermGroupUsage(
                attributionTag = TEST_ATTRIBUTION_TAG
        )

        val activityInfo = createMockActivityInfo(
                permission = "INCORRECT_PERMISSION"
        )
        val resolveInfo = createMockResolveInfo(activityInfo)
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(listOf(usage))
        `when`(packageManager.resolveActivity(any(), any<ResolveInfoFlags>()))
                .thenAnswer { resolveInfo }
        controller.showDialog(context)
        exhaustExecutors()

        dialogProvider.list?.let { list ->
            assertThat(isIntentEqual(list.get(0).navigationIntent!!,
                    controller.getDefaultManageAppPermissionsIntent(TEST_PACKAGE_NAME, USER_ID)))
                    .isTrue()
        }
    }

    private fun exhaustExecutors() {
        FakeExecutor.exhaustExecutors(backgroundExecutor, uiExecutor)
    }

    private fun setUpDefaultMockResponses() {
        `when`(permissionManager.getIndicatorAppOpUsageData(anyBoolean())).thenReturn(emptyList())
        `when`(appOpsController.isMicMuted).thenReturn(false)

        `when`(packageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenAnswer { FakeApplicationInfo(it.getArgument(0)) }

        `when`(privacyItemController.locationAvailable).thenReturn(true)
        `when`(privacyItemController.micCameraAvailable).thenReturn(true)

        `when`(userTracker.userProfiles).thenReturn(listOf(
                UserInfo(USER_ID, "", 0),
                UserInfo(ENT_USER_ID, "", UserInfo.FLAG_MANAGED_PROFILE)
        ))

        `when`(keyguardStateController.isUnlocked).thenReturn(true)
    }

    private class FakeApplicationInfo(val label: CharSequence) : ApplicationInfo() {
        override fun loadLabel(pm: PackageManager): CharSequence {
            return label
        }
    }

    private fun generateUidForUser(user: Int): Int {
        return user * UserHandle.PER_USER_RANGE + nextUid++
    }

    private fun createMockResolveInfo(
        activityInfo: ActivityInfo? = null
    ): ResolveInfo {
        val resolveInfo = mock(ResolveInfo::class.java)
        resolveInfo.activityInfo = activityInfo
        return resolveInfo
    }

    private fun createMockActivityInfo(
        permission: String = android.Manifest.permission.START_VIEW_PERMISSION_USAGE,
        className: String = "TEST_CLASS_NAME"
    ): ActivityInfo {
        val activityInfo = mock(ActivityInfo::class.java)
        activityInfo.permission = permission
        activityInfo.name = className
        return activityInfo
    }

    private fun createMockPermGroupUsage(
        packageName: String = TEST_PACKAGE_NAME,
        uid: Int = generateUidForUser(USER_ID),
        permissionGroupName: String = PERM_CAMERA,
        lastAccessTimeMillis: Long = 0L,
        isActive: Boolean = false,
        isPhoneCall: Boolean = false,
        attributionTag: CharSequence? = null,
        attributionLabel: CharSequence? = null,
        proxyLabel: CharSequence? = null
    ): PermissionGroupUsage {
        val usage = mock(PermissionGroupUsage::class.java)
        `when`(usage.packageName).thenReturn(packageName)
        `when`(usage.uid).thenReturn(uid)
        `when`(usage.permissionGroupName).thenReturn(permissionGroupName)
        `when`(usage.lastAccessTimeMillis).thenReturn(lastAccessTimeMillis)
        `when`(usage.isActive).thenReturn(isActive)
        `when`(usage.isPhoneCall).thenReturn(isPhoneCall)
        `when`(usage.attributionTag).thenReturn(attributionTag)
        `when`(usage.attributionLabel).thenReturn(attributionLabel)
        `when`(usage.proxyLabel).thenReturn(proxyLabel)
        return usage
    }
}