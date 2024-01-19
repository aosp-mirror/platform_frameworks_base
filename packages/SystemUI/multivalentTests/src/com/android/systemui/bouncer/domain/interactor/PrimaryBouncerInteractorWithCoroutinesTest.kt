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
 * limitations under the License
 */

package com.android.systemui.bouncer.domain.interactor

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class PrimaryBouncerInteractorWithCoroutinesTest : SysuiTestCase() {
    private lateinit var repository: FakeKeyguardBouncerRepository
    @Mock private lateinit var bouncerView: BouncerView
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var keyguardSecurityModel: KeyguardSecurityModel
    @Mock private lateinit var primaryBouncerCallbackInteractor: PrimaryBouncerCallbackInteractor
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var dismissCallbackRegistry: DismissCallbackRegistry
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var faceAuthInteractor: DeviceEntryFaceAuthInteractor
    private val mainHandler by lazy {
        FakeHandler(Looper.getMainLooper())
    }
    private lateinit var underTest: PrimaryBouncerInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        repository = FakeKeyguardBouncerRepository()
        underTest =
            PrimaryBouncerInteractor(
                repository,
                bouncerView,
                mainHandler,
                keyguardStateController,
                keyguardSecurityModel,
                primaryBouncerCallbackInteractor,
                falsingCollector,
                dismissCallbackRegistry,
                context,
                keyguardUpdateMonitor,
                Mockito.mock(TrustRepository::class.java),
                TestScope().backgroundScope,
                mSelectedUserInteractor,
                faceAuthInteractor,
            )
    }

    @Test
    fun notInteractableWhenExpansionIsBelow90Percent() = runTest {
        val isInteractable = collectLastValue(underTest.isInteractable)

        repository.setPrimaryShow(true)
        repository.setPanelExpansion(0.15f)

        assertThat(isInteractable()).isFalse()
    }

    @Test
    fun notInteractableWhenExpansionAbove90PercentButNotVisible() = runTest {
        val isInteractable = collectLastValue(underTest.isInteractable)

        repository.setPrimaryShow(false)
        repository.setPanelExpansion(0.05f)

        assertThat(isInteractable()).isFalse()
    }

    @Test
    fun isInteractableWhenExpansionAbove90PercentAndVisible() = runTest {
        var isInteractable = collectLastValue(underTest.isInteractable)

        repository.setPrimaryShow(true)
        repository.setPanelExpansion(0.09f)

        assertThat(isInteractable()).isTrue()

        repository.setPanelExpansion(0.12f)
        assertThat(isInteractable()).isFalse()

        repository.setPanelExpansion(0f)
        assertThat(isInteractable()).isTrue()
    }
}
