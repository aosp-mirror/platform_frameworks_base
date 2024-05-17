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
 * limitations under the License.
 */

package com.android.systemui.brightness.data.repository

import android.content.applicationContext
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.platform.test.flag.junit.FlagsParameterization.allCombinationsOf
import androidx.test.filters.SmallTest
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin
import com.android.systemui.Flags.FLAG_ENFORCE_BRIGHTNESS_BASE_USER_RESTRICTION
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.data.repository.userRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.PolicyRestriction
import com.android.systemui.utils.UserRestrictionChecker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class BrightnessPolicyRepositoryImplTest(flags: FlagsParameterization) : SysuiTestCase() {

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = testKosmos()

    private val mockUserRestrictionChecker: UserRestrictionChecker = mock {
        whenever(checkIfRestrictionEnforced(any(), anyString(), anyInt())).thenReturn(null)
        whenever(hasBaseUserRestriction(any(), anyString(), anyInt())).thenReturn(false)
    }

    private val underTest =
        with(kosmos) {
            BrightnessPolicyRepositoryImpl(
                userRepository,
                mockUserRestrictionChecker,
                applicationContext,
                testDispatcher,
            )
        }

    @Test
    fun noRestrictionByDefaultForAllUsers() =
        with(kosmos) {
            testScope.runTest {
                val restrictions by collectLastValue(underTest.restrictionPolicy)

                assertThat(restrictions).isEqualTo(PolicyRestriction.NoRestriction)

                fakeUserRepository.asMainUser()

                assertThat(restrictions).isEqualTo(PolicyRestriction.NoRestriction)
            }
        }

    @Test
    fun restrictDefaultUser() =
        with(kosmos) {
            testScope.runTest {
                val enforcedAdmin: EnforcedAdmin =
                    EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(RESTRICTION)

                whenever(
                        mockUserRestrictionChecker.checkIfRestrictionEnforced(
                            any(),
                            eq(RESTRICTION),
                            eq(userRepository.getSelectedUserInfo().id)
                        )
                    )
                    .thenReturn(enforcedAdmin)

                val restrictions by collectLastValue(underTest.restrictionPolicy)

                assertThat(restrictions).isEqualTo(PolicyRestriction.Restricted(enforcedAdmin))

                fakeUserRepository.asMainUser()

                assertThat(restrictions).isEqualTo(PolicyRestriction.NoRestriction)
            }
        }

    @Test
    fun restrictMainUser() =
        with(kosmos) {
            testScope.runTest {
                val enforcedAdmin: EnforcedAdmin =
                    EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(RESTRICTION)

                whenever(
                        mockUserRestrictionChecker.checkIfRestrictionEnforced(
                            any(),
                            eq(RESTRICTION),
                            eq(userRepository.mainUserId)
                        )
                    )
                    .thenReturn(enforcedAdmin)

                val restrictions by collectLastValue(underTest.restrictionPolicy)

                assertThat(restrictions).isEqualTo(PolicyRestriction.NoRestriction)

                fakeUserRepository.asMainUser()

                assertThat(restrictions).isEqualTo(PolicyRestriction.Restricted(enforcedAdmin))
            }
        }

    @Test
    @DisableFlags(FLAG_ENFORCE_BRIGHTNESS_BASE_USER_RESTRICTION)
    fun brightnessBaseUserRestriction_flagOff_noRestriction() =
        with(kosmos) {
            testScope.runTest {
                whenever(
                        mockUserRestrictionChecker.hasBaseUserRestriction(
                            any(),
                            eq(RESTRICTION),
                            eq(userRepository.getSelectedUserInfo().id)
                        )
                    )
                    .thenReturn(true)

                val restrictions by collectLastValue(underTest.restrictionPolicy)

                assertThat(restrictions).isEqualTo(PolicyRestriction.NoRestriction)
            }
        }

    @Test
    fun bothRestrictions_returnsSetEnforcedAdminFromCheck() =
        with(kosmos) {
            testScope.runTest {
                val enforcedAdmin: EnforcedAdmin =
                    EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(RESTRICTION)

                whenever(
                        mockUserRestrictionChecker.checkIfRestrictionEnforced(
                            any(),
                            eq(RESTRICTION),
                            eq(userRepository.getSelectedUserInfo().id)
                        )
                    )
                    .thenReturn(enforcedAdmin)

                whenever(
                        mockUserRestrictionChecker.hasBaseUserRestriction(
                            any(),
                            eq(RESTRICTION),
                            eq(userRepository.getSelectedUserInfo().id)
                        )
                    )
                    .thenReturn(true)

                val restrictions by collectLastValue(underTest.restrictionPolicy)

                assertThat(restrictions).isEqualTo(PolicyRestriction.Restricted(enforcedAdmin))
            }
        }

    @Test
    @EnableFlags(FLAG_ENFORCE_BRIGHTNESS_BASE_USER_RESTRICTION)
    fun brightnessBaseUserRestriction_flagOn_emptyRestriction() =
        with(kosmos) {
            testScope.runTest {
                whenever(
                        mockUserRestrictionChecker.hasBaseUserRestriction(
                            any(),
                            eq(RESTRICTION),
                            eq(userRepository.getSelectedUserInfo().id)
                        )
                    )
                    .thenReturn(true)

                val restrictions by collectLastValue(underTest.restrictionPolicy)

                assertThat(restrictions).isEqualTo(PolicyRestriction.Restricted(EnforcedAdmin()))
            }
        }

    companion object {
        private const val RESTRICTION = UserManager.DISALLOW_CONFIG_BRIGHTNESS
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return allCombinationsOf(FLAG_ENFORCE_BRIGHTNESS_BASE_USER_RESTRICTION)
        }
    }
}
