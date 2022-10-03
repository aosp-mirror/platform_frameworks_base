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
 */

package com.android.systemui.keyguard.domain.usecase

import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.domain.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.domain.quickaffordance.FakeKeyguardQuickAffordanceRegistry
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.mock
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

        homeControls = object : FakeKeyguardQuickAffordanceConfig() {}
        quickAccessWallet = object : FakeKeyguardQuickAffordanceConfig() {}
        qrCodeScanner = object : FakeKeyguardQuickAffordanceConfig() {}

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
        val configKey = homeControls::class
        homeControls.setState(
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = ICON,
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
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
        assertThat(visibleModel.contentDescriptionResourceId)
            .isEqualTo(CONTENT_DESCRIPTION_RESOURCE_ID)
        job.cancel()
    }

    @Test
    fun `quickAffordance - bottom end affordance is visible`() = runBlockingTest {
        val configKey = quickAccessWallet::class
        quickAccessWallet.setState(
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = ICON,
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
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
        assertThat(visibleModel.contentDescriptionResourceId)
            .isEqualTo(CONTENT_DESCRIPTION_RESOURCE_ID)
        job.cancel()
    }

    @Test
    fun `quickAffordance - bottom start affordance hidden while dozing`() = runBlockingTest {
        repository.setDozing(true)
        homeControls.setState(
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = ICON,
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
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
                KeyguardQuickAffordanceConfig.State.Visible(
                    icon = ICON,
                    contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
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
        private val ICON: ContainedDrawable = mock()
        private const val CONTENT_DESCRIPTION_RESOURCE_ID = 1337
    }
}
