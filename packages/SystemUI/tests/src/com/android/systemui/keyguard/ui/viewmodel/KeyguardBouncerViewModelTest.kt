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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardBouncerViewModelTest : SysuiTestCase() {
    lateinit var underTest: KeyguardBouncerViewModel
    @Mock lateinit var bouncerView: BouncerView
    @Mock lateinit var bouncerInteractor: PrimaryBouncerInteractor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest = KeyguardBouncerViewModel(bouncerView, bouncerInteractor)
    }

    @Test
    fun setMessage() =
        runBlocking(Dispatchers.Main.immediate) {
            val flow = MutableStateFlow<BouncerShowMessageModel?>(null)
            var message: BouncerShowMessageModel? = null
            Mockito.`when`(bouncerInteractor.showMessage)
                .thenReturn(flow as Flow<BouncerShowMessageModel>)
            // Reinitialize the view model.
            underTest = KeyguardBouncerViewModel(bouncerView, bouncerInteractor)

            flow.value = BouncerShowMessageModel(message = "abc", colorStateList = null)

            val job = underTest.bouncerShowMessage.onEach { message = it }.launchIn(this)
            assertThat(message?.message).isEqualTo("abc")
            job.cancel()
        }
}
