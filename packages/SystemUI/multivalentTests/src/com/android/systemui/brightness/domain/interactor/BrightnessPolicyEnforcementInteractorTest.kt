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

package com.android.systemui.brightness.domain.interactor

import android.content.ComponentName
import android.content.Intent
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin
import com.android.systemui.SysuiTestCase
import com.android.systemui.brightness.data.repository.BrightnessPolicyRepository
import com.android.systemui.brightness.data.repository.brightnessPolicyRepository
import com.android.systemui.brightness.data.repository.fakeBrightnessPolicyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.utils.PolicyRestriction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessPolicyEnforcementInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val mockActivityStarter = kosmos.activityStarter

    private val underTest =
        with(kosmos) {
            BrightnessPolicyEnforcementInteractor(
                brightnessPolicyRepository,
                activityStarter,
            )
        }

    @Test
    fun restriction() =
        with(kosmos) {
            testScope.runTest {
                fakeBrightnessPolicyRepository.setCurrentUserUnrestricted()

                val restriction by collectLastValue(underTest.brightnessPolicyRestriction)

                assertThat(restriction).isEqualTo(PolicyRestriction.NoRestriction)

                fakeBrightnessPolicyRepository.setCurrentUserRestricted()

                assertThat(restriction)
                    .isEqualTo(
                        PolicyRestriction.Restricted(
                            EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(
                                BrightnessPolicyRepository.RESTRICTION
                            )
                        )
                    )

                fakeBrightnessPolicyRepository.setBaseUserRestriction()

                assertThat(restriction).isEqualTo(PolicyRestriction.Restricted(EnforcedAdmin()))
            }
        }

    @Test
    fun startRestrictionDialog() =
        with(kosmos) {
            testScope.runTest {
                val enforcedAdmin =
                    EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(
                            BrightnessPolicyRepository.RESTRICTION
                        )
                        .apply {
                            component = TEST_COMPONENT
                            user = UserHandle.of(TEST_USER)
                        }

                underTest.startAdminSupportDetailsDialog(
                    PolicyRestriction.Restricted(enforcedAdmin)
                )

                val intentCaptor = argumentCaptor<Intent>()

                verify(mockActivityStarter)
                    .postStartActivityDismissingKeyguard(
                        capture(intentCaptor),
                        eq(0),
                    )

                val expectedIntent =
                    RestrictedLockUtils.getShowAdminSupportDetailsIntent(enforcedAdmin)

                with(intentCaptor.value) {
                    assertThat(action).isEqualTo(expectedIntent.action)
                    assertThat(extras!!.kindofEquals(expectedIntent.extras)).isTrue()
                }
            }
        }

    private companion object {
        val TEST_COMPONENT = ComponentName("pkg", ".cls")
        val TEST_USER = 10
    }
}
