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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.domain.interactor.UdfpsKeyguardInteractor
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/** Tests UdfpsFingerprintViewModel specific flows. */
@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class UdfpsFingerprintViewModelTest : SysuiTestCase() {
    private val defaultPadding = 12
    private lateinit var underTest: FingerprintViewModel

    private lateinit var testScope: TestScope
    private lateinit var configRepository: FakeConfigurationRepository
    private lateinit var bouncerRepository: KeyguardBouncerRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var fakeCommandQueue: FakeCommandQueue
    private lateinit var featureFlags: FakeFeatureFlags
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository
    private lateinit var shadeRepository: FakeShadeRepository

    @Mock private lateinit var burnInHelper: BurnInHelperWrapper
    @Mock private lateinit var dialogManager: SystemUIDialogManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        overrideResource(com.android.systemui.res.R.dimen.lock_icon_padding, defaultPadding)
        testScope = TestScope()
        configRepository = FakeConfigurationRepository()
        keyguardRepository = FakeKeyguardRepository()
        bouncerRepository = FakeKeyguardBouncerRepository()
        fakeCommandQueue = FakeCommandQueue()
        featureFlags = FakeFeatureFlags().apply { set(Flags.FACE_AUTH_REFACTOR, false) }
        bouncerRepository = FakeKeyguardBouncerRepository()
        transitionRepository = FakeKeyguardTransitionRepository()
        shadeRepository = FakeShadeRepository()
        val keyguardInteractor =
            KeyguardInteractorFactory.create(
                    repository = keyguardRepository,
                    featureFlags = featureFlags,
                )
                .keyguardInteractor

        val transitionInteractor =
            KeyguardTransitionInteractorFactory.create(
                    scope = testScope.backgroundScope,
                    repository = transitionRepository,
                    keyguardInteractor = keyguardInteractor,
                )
                .keyguardTransitionInteractor

        underTest =
            FingerprintViewModel(
                context,
                transitionInteractor,
                UdfpsKeyguardInteractor(
                    configRepository,
                    BurnInInteractor(
                        context,
                        burnInHelper,
                        testScope.backgroundScope,
                        configRepository,
                        keyguardInteractor,
                    ),
                    keyguardInteractor,
                    shadeRepository,
                    dialogManager,
                ),
                keyguardInteractor,
            )
    }

    @Test
    fun paddingUpdates_onScaleForResolutionChanges() =
        testScope.runTest {
            val padding by collectLastValue(underTest.padding)

            configRepository.setScaleForResolution(1f)
            runCurrent()
            assertThat(padding).isEqualTo(defaultPadding)

            configRepository.setScaleForResolution(2f)
            runCurrent()
            assertThat(padding).isEqualTo(defaultPadding * 2)

            configRepository.setScaleForResolution(.5f)
            runCurrent()
            assertThat(padding).isEqualTo((defaultPadding * .5).toInt())
        }
}
