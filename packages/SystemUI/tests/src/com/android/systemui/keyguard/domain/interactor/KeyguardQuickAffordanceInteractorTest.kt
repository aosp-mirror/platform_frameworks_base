/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.domain.quickaffordance.FakeKeyguardQuickAffordanceRegistry
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardQuickAffordanceInteractorTest : SysuiTestCase() {

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var underTest: KeyguardQuickAffordanceInteractor

    private lateinit var repository: FakeKeyguardRepository
    private lateinit var homeControls: FakeKeyguardQuickAffordanceConfig
    private lateinit var quickAccessWallet: FakeKeyguardQuickAffordanceConfig
    private lateinit var qrCodeScanner: FakeKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        repository = FakeKeyguardRepository()
        repository.setKeyguardShowing(true)

        homeControls =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS)
        quickAccessWallet =
            FakeKeyguardQuickAffordanceConfig(
                BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET
            )
        qrCodeScanner =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.QR_CODE_SCANNER)

        underTest =
            KeyguardQuickAffordanceInteractor(
                keyguardInteractor = KeyguardInteractor(repository = repository),
                registry =
                    FakeKeyguardQuickAffordanceRegistry(
                        mapOf(
                            KeyguardQuickAffordancePosition.BOTTOM_START to
                                listOf(
                                    homeControls,
                                ),
                            KeyguardQuickAffordancePosition.BOTTOM_END to
                                listOf(
                                    quickAccessWallet,
                                    qrCodeScanner,
                                ),
                        ),
                    ),
                lockPatternUtils = lockPatternUtils,
                keyguardStateController = keyguardStateController,
                userTracker = userTracker,
                activityStarter = activityStarter,
            )
    }

    @Test
    fun `quickAffordance - bottom start affordance is visible`() = runBlockingTest {
        val configKey = BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS
        homeControls.setState(
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                icon = ICON,
                activationState = ActivationState.Active,
            )
        )

        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest
                .quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                .onEach { latest = it }
                .launchIn(this)
        // The interactor has an onStart { emit(Hidden) } to cover for upstream configs that don't
        // produce an initial value. We yield to give the coroutine time to emit the first real
        // value from our config.
        yield()

        assertThat(latest).isInstanceOf(KeyguardQuickAffordanceModel.Visible::class.java)
        val visibleModel = latest as KeyguardQuickAffordanceModel.Visible
        assertThat(visibleModel.configKey).isEqualTo(configKey)
        assertThat(visibleModel.icon).isEqualTo(ICON)
        assertThat(visibleModel.icon.contentDescription)
            .isEqualTo(ContentDescription.Resource(res = CONTENT_DESCRIPTION_RESOURCE_ID))
        assertThat(visibleModel.activationState).isEqualTo(ActivationState.Active)
        job.cancel()
    }

    @Test
    fun `quickAffordance - bottom end affordance is visible`() = runBlockingTest {
        val configKey = BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET
        quickAccessWallet.setState(
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                icon = ICON,
            )
        )

        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest
                .quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                .onEach { latest = it }
                .launchIn(this)
        // The interactor has an onStart { emit(Hidden) } to cover for upstream configs that don't
        // produce an initial value. We yield to give the coroutine time to emit the first real
        // value from our config.
        yield()

        assertThat(latest).isInstanceOf(KeyguardQuickAffordanceModel.Visible::class.java)
        val visibleModel = latest as KeyguardQuickAffordanceModel.Visible
        assertThat(visibleModel.configKey).isEqualTo(configKey)
        assertThat(visibleModel.icon).isEqualTo(ICON)
        assertThat(visibleModel.icon.contentDescription)
            .isEqualTo(ContentDescription.Resource(res = CONTENT_DESCRIPTION_RESOURCE_ID))
        assertThat(visibleModel.activationState).isEqualTo(ActivationState.NotSupported)
        job.cancel()
    }

    @Test
    fun `quickAffordance - bottom start affordance hidden while dozing`() = runBlockingTest {
        repository.setDozing(true)
        homeControls.setState(
            KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                icon = ICON,
            )
        )

        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest
                .quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                .onEach { latest = it }
                .launchIn(this)
        assertThat(latest).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        job.cancel()
    }

    @Test
    fun `quickAffordance - bottom start affordance hidden when lockscreen is not showing`() =
        runBlockingTest {
            repository.setKeyguardShowing(false)
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                )
            )

            var latest: KeyguardQuickAffordanceModel? = null
            val job =
                underTest
                    .quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                    .onEach { latest = it }
                    .launchIn(this)
            assertThat(latest).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
            job.cancel()
        }

    companion object {
        private val ICON: Icon = mock {
            whenever(this.contentDescription)
                .thenReturn(
                    ContentDescription.Resource(
                        res = CONTENT_DESCRIPTION_RESOURCE_ID,
                    )
                )
        }
        private const val CONTENT_DESCRIPTION_RESOURCE_ID = 1337
    }
}
