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

package com.android.systemui.qs.tiles.base.interactor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class DisabledByPolicyInteractorTest : SysuiTestCase() {

    @Mock private lateinit var restrictedLockProxy: RestrictedLockProxy
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var context: Context

    @Captor private lateinit var intentCaptor: ArgumentCaptor<Intent>

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    lateinit var underTest: DisabledByPolicyInteractor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            DisabledByPolicyInteractorImpl(
                context,
                activityStarter,
                restrictedLockProxy,
                testDispatcher,
            )
    }

    @Test
    fun testEnabledWhenNoAdmin() =
        testScope.runTest {
            whenever(restrictedLockProxy.getEnforcedAdmin(anyInt(), anyString())).thenReturn(null)

            assertThat(underTest.isDisabled(TEST_USER, TEST_RESTRICTION))
                .isSameInstanceAs(DisabledByPolicyInteractor.PolicyResult.TileEnabled)
        }

    @Test
    fun testDisabledWhenAdminWithNoRestrictions() =
        testScope.runTest {
            val admin = EnforcedAdmin(TEST_COMPONENT_NAME, TEST_USER)
            whenever(restrictedLockProxy.getEnforcedAdmin(anyInt(), anyString())).thenReturn(admin)
            whenever(restrictedLockProxy.hasBaseUserRestriction(anyInt(), anyString()))
                .thenReturn(false)

            val result =
                underTest.isDisabled(TEST_USER, TEST_RESTRICTION)
                    as DisabledByPolicyInteractor.PolicyResult.TileDisabled
            assertThat(result.admin).isEqualTo(admin)
        }

    @Test
    fun testEnabledWhenAdminWithRestrictions() =
        testScope.runTest {
            whenever(restrictedLockProxy.getEnforcedAdmin(anyInt(), anyString())).thenReturn(ADMIN)
            whenever(restrictedLockProxy.hasBaseUserRestriction(anyInt(), anyString()))
                .thenReturn(true)

            assertThat(underTest.isDisabled(TEST_USER, TEST_RESTRICTION))
                .isSameInstanceAs(DisabledByPolicyInteractor.PolicyResult.TileEnabled)
        }

    @Test
    fun testHandleDisabledByPolicy() {
        val result =
            underTest.handlePolicyResult(
                DisabledByPolicyInteractor.PolicyResult.TileDisabled(ADMIN)
            )

        val expectedIntent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(context, ADMIN)
        assertThat(result).isTrue()
        verify(activityStarter).postStartActivityDismissingKeyguard(intentCaptor.capture(), any())
        assertThat(intentCaptor.value.filterEquals(expectedIntent)).isTrue()
    }

    @Test
    fun testHandleEnabled() {
        val result =
            underTest.handlePolicyResult(DisabledByPolicyInteractor.PolicyResult.TileEnabled)

        assertThat(result).isFalse()
        verify(activityStarter, never())
            .postStartActivityDismissingKeyguard(intentCaptor.capture(), any())
    }

    private companion object {

        const val TEST_RESTRICTION = "test_restriction"

        val TEST_COMPONENT_NAME = ComponentName("test.pkg", "test.cls")
        val TEST_USER = UserHandle(1)
        val ADMIN = EnforcedAdmin(TEST_COMPONENT_NAME, TEST_USER)
    }
}
