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

package com.android.systemui.keyguard.data.repository

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePosition
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardQuickAffordanceRepositoryImplTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardQuickAffordanceRepository

    private lateinit var homeControls: FakeKeyguardQuickAffordanceConfig
    private lateinit var quickAccessWallet: FakeKeyguardQuickAffordanceConfig
    private lateinit var qrCodeScanner: FakeKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        homeControls = object : FakeKeyguardQuickAffordanceConfig() {}
        quickAccessWallet = object : FakeKeyguardQuickAffordanceConfig() {}
        qrCodeScanner = object : FakeKeyguardQuickAffordanceConfig() {}

        underTest =
            KeyguardQuickAffordanceRepositoryImpl(
                configs =
                    FakeKeyguardQuickAffordanceConfigs(
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
            )
    }

    @Test
    fun `bottom start affordance - none`() = runBlockingTest {
        // TODO(b/239834928): once coroutines.test is updated, switch to the approach described in
        // https://developer.android.com/kotlin/flow/test#continuous-collection
        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest
                .affordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                .onEach { latest = it }
                .launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        job.cancel()
    }

    @Test
    fun `bottom start affordance - home controls`() = runBlockingTest {
        // TODO(b/239834928): once coroutines.test is updated, switch to the approach described in
        // https://developer.android.com/kotlin/flow/test#continuous-collection
        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest
                .affordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                .onEach { latest = it }
                .launchIn(this)

        val state =
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = mock(),
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
            )
        homeControls.setState(state)

        assertThat(latest).isEqualTo(state.toModel(homeControls::class))
        job.cancel()
    }

    @Test
    fun `bottom end affordance - none`() = runBlockingTest {
        // TODO(b/239834928): once coroutines.test is updated, switch to the approach described in
        // https://developer.android.com/kotlin/flow/test#continuous-collection
        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest
                .affordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                .onEach { latest = it }
                .launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        job.cancel()
    }

    @Test
    fun `bottom end affordance - quick access wallet`() = runBlockingTest {
        // TODO(b/239834928): once coroutines.test is updated, switch to the approach described in
        // https://developer.android.com/kotlin/flow/test#continuous-collection
        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest
                .affordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                .onEach { latest = it }
                .launchIn(this)

        val quickAccessWalletState =
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = mock(),
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
            )
        quickAccessWallet.setState(quickAccessWalletState)
        val qrCodeScannerState =
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = mock(),
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
            )
        qrCodeScanner.setState(qrCodeScannerState)

        assertThat(latest).isEqualTo(quickAccessWalletState.toModel(quickAccessWallet::class))
        job.cancel()
    }

    @Test
    fun `bottom end affordance - qr code scanner`() = runBlockingTest {
        // TODO(b/239834928): once coroutines.test is updated, switch to the approach described in
        // https://developer.android.com/kotlin/flow/test#continuous-collection
        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest
                .affordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                .onEach { latest = it }
                .launchIn(this)

        val state =
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = mock(),
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
            )
        qrCodeScanner.setState(state)

        assertThat(latest).isEqualTo(state.toModel(qrCodeScanner::class))
        job.cancel()
    }

    private fun KeyguardQuickAffordanceConfig.State?.toModel(
        configKey: KClass<out KeyguardQuickAffordanceConfig>,
    ): KeyguardQuickAffordanceModel? {
        return when (this) {
            is KeyguardQuickAffordanceConfig.State.Visible ->
                KeyguardQuickAffordanceModel.Visible(
                    configKey = configKey,
                    icon = icon,
                    contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
                )
            is KeyguardQuickAffordanceConfig.State.Hidden -> KeyguardQuickAffordanceModel.Hidden
            null -> null
        }
    }

    companion object {
        private const val CONTENT_DESCRIPTION_RESOURCE_ID = 1337
    }
}
