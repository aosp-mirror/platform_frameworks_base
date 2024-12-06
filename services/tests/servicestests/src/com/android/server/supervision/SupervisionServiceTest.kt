/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.supervision

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManagerInternal
import android.app.supervision.flags.Flags
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.Handler
import android.os.PersistableBundle
import android.os.UserHandle
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.internal.R
import com.android.server.LocalServices
import com.android.server.SystemService.TargetUser
import com.android.server.pm.UserManagerInternal
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.whenever

/**
 * Unit tests for [SupervisionService].
 *
 * Run with `atest SupervisionServiceTest`.
 */
@RunWith(AndroidJUnit4::class)
class SupervisionServiceTest {
    @get:Rule val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule val mocks: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var mockDpmInternal: DevicePolicyManagerInternal
    @Mock private lateinit var mockPackageManager: PackageManager
    @Mock private lateinit var mockUserManagerInternal: UserManagerInternal

    private lateinit var context: Context
    private lateinit var lifecycle: SupervisionService.Lifecycle
    private lateinit var service: SupervisionService

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        context = SupervisionContextWrapper(context, mockPackageManager)

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal::class.java)
        LocalServices.addService(DevicePolicyManagerInternal::class.java, mockDpmInternal)

        LocalServices.removeServiceForTest(UserManagerInternal::class.java)
        LocalServices.addService(UserManagerInternal::class.java, mockUserManagerInternal)

        service = SupervisionService(context)
        lifecycle = SupervisionService.Lifecycle(context, service)
        lifecycle.registerProfileOwnerListener()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYNC_WITH_DPM)
    fun onUserStarting_supervisionAppIsProfileOwner_enablesSupervision() {
        whenever(mockDpmInternal.getProfileOwnerAsUser(USER_ID))
            .thenReturn(ComponentName(systemSupervisionPackage, "MainActivity"))

        simulateUserStarting(USER_ID)

        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isTrue()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYNC_WITH_DPM)
    fun onUserStarting_userPreCreated_doesNotEnableSupervision() {
        whenever(mockDpmInternal.getProfileOwnerAsUser(USER_ID))
            .thenReturn(ComponentName(systemSupervisionPackage, "MainActivity"))

        simulateUserStarting(USER_ID, preCreated = true)

        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYNC_WITH_DPM)
    fun onUserStarting_supervisionAppIsNotProfileOwner_doesNotEnableSupervision() {
        whenever(mockDpmInternal.getProfileOwnerAsUser(USER_ID))
            .thenReturn(ComponentName("other.package", "MainActivity"))

        simulateUserStarting(USER_ID)

        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYNC_WITH_DPM)
    fun profileOwnerChanged_supervisionAppIsProfileOwner_enablesSupervision() {
        whenever(mockDpmInternal.getProfileOwnerAsUser(USER_ID))
            .thenReturn(ComponentName(systemSupervisionPackage, "MainActivity"))

        broadcastProfileOwnerChanged(USER_ID)

        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isTrue()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYNC_WITH_DPM)
    fun profileOwnerChanged_supervisionAppIsNotProfileOwner_doesNotEnableSupervision() {
        whenever(mockDpmInternal.getProfileOwnerAsUser(USER_ID))
            .thenReturn(ComponentName("other.package", "MainActivity"))

        broadcastProfileOwnerChanged(USER_ID)

        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    fun isActiveSupervisionApp_supervisionUid_supervisionEnabled_returnsTrue() {
        whenever(mockPackageManager.getPackagesForUid(APP_UID))
            .thenReturn(arrayOf(systemSupervisionPackage))
        service.setSupervisionEnabledForUser(USER_ID, true)

        assertThat(service.mInternal.isActiveSupervisionApp(APP_UID)).isTrue()
    }

    @Test
    fun isActiveSupervisionApp_supervisionUid_supervisionNotEnabled_returnsFalse() {
        whenever(mockPackageManager.getPackagesForUid(APP_UID))
            .thenReturn(arrayOf(systemSupervisionPackage))
        service.setSupervisionEnabledForUser(USER_ID, false)

        assertThat(service.mInternal.isActiveSupervisionApp(APP_UID)).isFalse()
    }

    @Test
    fun isActiveSupervisionApp_notSupervisionUid_returnsFalse() {
        whenever(mockPackageManager.getPackagesForUid(APP_UID)).thenReturn(arrayOf())

        assertThat(service.mInternal.isActiveSupervisionApp(APP_UID)).isFalse()
    }

    @Test
    fun setSupervisionEnabledForUser() {
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()

        service.setSupervisionEnabledForUser(USER_ID, true)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isTrue()

        service.setSupervisionEnabledForUser(USER_ID, false)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    fun supervisionEnabledForUser_internal() {
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()

        service.mInternal.setSupervisionEnabledForUser(USER_ID, true)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isTrue()

        service.mInternal.setSupervisionEnabledForUser(USER_ID, false)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    fun setSupervisionLockscreenEnabledForUser() {
        var userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isFalse()
        assertThat(userData.supervisionLockScreenOptions).isNull()

        service.mInternal.setSupervisionLockscreenEnabledForUser(USER_ID, true, PersistableBundle())
        userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isTrue()
        assertThat(userData.supervisionLockScreenOptions).isNotNull()

        service.mInternal.setSupervisionLockscreenEnabledForUser(USER_ID, false, null)
        userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isFalse()
        assertThat(userData.supervisionLockScreenOptions).isNull()
    }

    private val systemSupervisionPackage: String
        get() = context.getResources().getString(R.string.config_systemSupervision)

    private fun simulateUserStarting(userId: Int, preCreated: Boolean = false) {
        val userInfo = UserInfo(userId, /* name= */ "tempUser", /* flags= */ 0)
        userInfo.preCreated = preCreated
        lifecycle.onUserStarting(TargetUser(userInfo))
    }

    private fun broadcastProfileOwnerChanged(userId: Int) {
        val intent = Intent(DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED)
        context.sendBroadcastAsUser(intent, UserHandle.of(userId))
    }

    private companion object {
        const val USER_ID = 100
        val APP_UID = USER_ID * UserHandle.PER_USER_RANGE
    }
}

/**
 * A context wrapper that allows broadcast intents to immediately invoke the receivers without
 * performing checks on the sending user.
 */
private class SupervisionContextWrapper(val context: Context, val pkgManager: PackageManager) :
    ContextWrapper(context) {
    val interceptors = mutableListOf<Pair<BroadcastReceiver, IntentFilter>>()

    override fun getPackageManager() = pkgManager

    override fun registerReceiverForAllUsers(
        receiver: BroadcastReceiver?,
        filter: IntentFilter,
        broadcastPermission: String?,
        scheduler: Handler?,
    ): Intent? {
        if (receiver != null) {
            interceptors.add(Pair(receiver, filter))
        }
        return null
    }

    override fun sendBroadcastAsUser(intent: Intent, user: UserHandle) {
        val pendingResult =
            BroadcastReceiver.PendingResult(
                Activity.RESULT_OK,
                /* resultData= */ "",
                /* resultExtras= */ null,
                /* type= */ 0,
                /* ordered= */ true,
                /* sticky= */ false,
                /* token= */ null,
                user.identifier,
                /* flags= */ 0,
            )
        for ((receiver, filter) in interceptors) {
            if (filter.match(contentResolver, intent, false, "") > 0) {
                receiver.setPendingResult(pendingResult)
                receiver.onReceive(context, intent)
            }
        }
    }
}
