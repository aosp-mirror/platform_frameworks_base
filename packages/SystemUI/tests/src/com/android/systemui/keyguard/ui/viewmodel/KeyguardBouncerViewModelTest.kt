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

package com.android.systemui.keyguard.ui.viewmodel

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerCallbackInteractor
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardBouncerViewModelTest : SysuiTestCase() {
    lateinit var underTest: KeyguardBouncerViewModel
    lateinit var bouncerInteractor: PrimaryBouncerInteractor
    @Mock lateinit var bouncerView: BouncerView
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var keyguardSecurityModel: KeyguardSecurityModel
    @Mock private lateinit var primaryBouncerCallbackInteractor: PrimaryBouncerCallbackInteractor
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var dismissCallbackRegistry: DismissCallbackRegistry
    @Mock private lateinit var keyguardBypassController: KeyguardBypassController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    private val mainHandler = FakeHandler(Looper.getMainLooper())
    val repository = FakeKeyguardBouncerRepository()

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
                keyguardBypassController,
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

    @Test
    fun shouldUpdateSideFps_show() = runTest {
        var count = 0
        val job = underTest.shouldUpdateSideFps.onEach { count++ }.launchIn(this)
        repository.setPrimaryShow(true)
        // Run the tasks that are pending at this point of virtual time.
        runCurrent()
        assertThat(count).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun shouldUpdateSideFps_hide() = runTest {
        repository.setPrimaryShow(true)
        var count = 0
        val job = underTest.shouldUpdateSideFps.onEach { count++ }.launchIn(this)
        repository.setPrimaryShow(false)
        // Run the tasks that are pending at this point of virtual time.
        runCurrent()
        assertThat(count).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun sideFpsShowing() = runTest {
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
}
