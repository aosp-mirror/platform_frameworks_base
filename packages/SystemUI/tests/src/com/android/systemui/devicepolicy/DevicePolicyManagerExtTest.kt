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

package com.android.systemui.devicepolicy

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FACE
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class DevicePolicyManagerExtTest : SysuiTestCase() {

    @Mock lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var userTracker: UserTracker

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(userTracker.userId).thenReturn(CURRENT_USER_ID)
    }

    // region areKeyguardShortcutsDisabled
    @Test
    fun areKeyguardShortcutsDisabled_noDisabledKeyguardFeature_shouldReturnFalse() {
        whenever(devicePolicyManager.getKeyguardDisabledFeatures(eq(null), anyInt()))
            .thenReturn(KEYGUARD_DISABLE_FEATURES_NONE)

        assertThat(devicePolicyManager.areKeyguardShortcutsDisabled(userId = CURRENT_USER_ID))
            .isFalse()
    }

    @Test
    fun areKeyguardShortcutsDisabled_otherDisabledKeyguardFeatures_shouldReturnFalse() {
        whenever(devicePolicyManager.getKeyguardDisabledFeatures(eq(null), anyInt()))
            .thenReturn(KEYGUARD_DISABLE_SECURE_CAMERA or KEYGUARD_DISABLE_FACE)

        assertThat(devicePolicyManager.areKeyguardShortcutsDisabled(userId = CURRENT_USER_ID))
            .isFalse()
    }

    @Test
    fun areKeyguardShortcutsDisabled_disabledShortcutsKeyguardFeature_shouldReturnTrue() {
        whenever(devicePolicyManager.getKeyguardDisabledFeatures(eq(null), anyInt()))
            .thenReturn(KEYGUARD_DISABLE_SHORTCUTS_ALL)

        assertThat(devicePolicyManager.areKeyguardShortcutsDisabled(userId = CURRENT_USER_ID))
            .isTrue()
    }

    @Test
    fun areKeyguardShortcutsDisabled_disabledAllKeyguardFeatures_shouldReturnTrue() {
        whenever(devicePolicyManager.getKeyguardDisabledFeatures(eq(null), anyInt()))
            .thenReturn(KEYGUARD_DISABLE_FEATURES_ALL)

        assertThat(devicePolicyManager.areKeyguardShortcutsDisabled(userId = CURRENT_USER_ID))
            .isTrue()
    }
    // endregion

    private companion object {
        const val CURRENT_USER_ID = 123
    }
}
