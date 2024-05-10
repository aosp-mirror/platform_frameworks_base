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

package com.android.systemui.bouncer.ui.viewmodel

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.shared.model.BouncerShowMessageModel
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor
import com.android.systemui.shared.Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardBouncerViewModelTest : SysuiTestCase() {

    @Mock lateinit var bouncerView: BouncerView
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var keyguardSecurityModel: KeyguardSecurityModel
    @Mock private lateinit var primaryBouncerCallbackInteractor: PrimaryBouncerCallbackInteractor
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var dismissCallbackRegistry: DismissCallbackRegistry
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var faceAuthInteractor: KeyguardFaceAuthInteractor

    lateinit var bouncerInteractor: PrimaryBouncerInteractor
    private val mainHandler = FakeHandler(Looper.getMainLooper())
    val repository = FakeKeyguardBouncerRepository()

    lateinit var underTest: KeyguardBouncerViewModel

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        bouncerInteractor =
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
        underTest = KeyguardBouncerViewModel(bouncerView, bouncerInteractor)
    }

    @Test
    fun setMessage() = runTest {
        var message: BouncerShowMessageModel? = null
        val job = underTest.bouncerShowMessage.onEach { message = it }.launchIn(this)

        repository.setShowMessage(BouncerShowMessageModel("abc", null))
        // Run the tasks that are pending at this point of virtual time.
        runCurrent()
        assertThat(message?.message).isEqualTo("abc")
        job.cancel()
    }

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    @Test
    fun shouldUpdateSideFps_show() = runTest {
        mSetFlagsRule.disableFlags(FLAG_SIDEFPS_CONTROLLER_REFACTOR)
        var count = 0
        val job = underTest.shouldUpdateSideFps.onEach { count++ }.launchIn(this)
        repository.setPrimaryShow(true)
        // Run the tasks that are pending at this point of virtual time.
        runCurrent()
        assertThat(count).isEqualTo(1)
        job.cancel()
    }

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    @Test
    fun shouldUpdateSideFps_hide() = runTest {
        mSetFlagsRule.disableFlags(FLAG_SIDEFPS_CONTROLLER_REFACTOR)
        repository.setPrimaryShow(true)
        var count = 0
        val job = underTest.shouldUpdateSideFps.onEach { count++ }.launchIn(this)
        repository.setPrimaryShow(false)
        // Run the tasks that are pending at this point of virtual time.
        runCurrent()
        assertThat(count).isEqualTo(1)
        job.cancel()
    }

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    @Test
    fun sideFpsShowing() = runTest {
        mSetFlagsRule.disableFlags(FLAG_SIDEFPS_CONTROLLER_REFACTOR)
        var sideFpsIsShowing = false
        val job = underTest.sideFpsShowing.onEach { sideFpsIsShowing = it }.launchIn(this)
        repository.setSideFpsShowing(true)
        // Run the tasks that are pending at this point of virtual time.
        runCurrent()
        assertThat(sideFpsIsShowing).isEqualTo(true)
        job.cancel()
    }

    @Test
    fun isShowing() = runTest {
        var isShowing: Boolean? = null
        val job = underTest.isShowing.onEach { isShowing = it }.launchIn(this)
        repository.setPrimaryShow(true)
        // Run the tasks that are pending at this point of virtual time.
        runCurrent()
        assertThat(isShowing).isEqualTo(true)
        job.cancel()
    }

    @Test
    fun isNotShowing() = runTest {
        var isShowing: Boolean? = null
        val job = underTest.isShowing.onEach { isShowing = it }.launchIn(this)
        repository.setPrimaryShow(false)
        // Run the tasks that are pending at this point of virtual time.
        runCurrent()
        assertThat(isShowing).isEqualTo(false)
        job.cancel()
    }

    @Test
    fun keyguardPosition_noValueSet_emptyByDefault() = runTest {
        val positionValues by collectValues(underTest.keyguardPosition)

        runCurrent()

        assertThat(positionValues).isEmpty()
    }

    @Test
    fun keyguardPosition_valueSet_returnsValue() = runTest {
        val position by collectLastValue(underTest.keyguardPosition)
        runCurrent()

        repository.setKeyguardPosition(123f)
        runCurrent()

        assertThat(position).isEqualTo(123f)
    }
}
