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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin
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

@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessPolicyRepositoryImplTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val fakeUserRepository = kosmos.fakeUserRepository

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

    private companion object {
        val RESTRICTION = UserManager.DISALLOW_CONFIG_BRIGHTNESS
    }
}
