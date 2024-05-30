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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.os.fakeExecutorHandler
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.testKosmos
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class KeyguardBlueprintViewModelTest : SysuiTestCase() {
    @Mock private lateinit var keyguardBlueprintInteractor: KeyguardBlueprintInteractor
    private lateinit var undertest: KeyguardBlueprintViewModel
    private val kosmos = testKosmos()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        undertest =
            KeyguardBlueprintViewModel(
                handler = kosmos.fakeExecutorHandler,
                keyguardBlueprintInteractor = keyguardBlueprintInteractor,
            )
    }

    @Test
    fun testBlueprintFlow() {
        verify(keyguardBlueprintInteractor).blueprint
    }
}
