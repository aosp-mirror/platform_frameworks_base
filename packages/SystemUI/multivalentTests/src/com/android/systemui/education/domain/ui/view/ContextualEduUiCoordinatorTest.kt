/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.domain.ui.view

import android.content.applicationContext
import android.widget.Toast
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.education.domain.interactor.KeyboardTouchpadEduInteractor
import com.android.systemui.education.domain.interactor.contextualEducationInteractor
import com.android.systemui.education.domain.interactor.keyboardTouchpadEduInteractor
import com.android.systemui.education.ui.view.ContextualEduUiCoordinator
import com.android.systemui.education.ui.viewmodel.ContextualEduViewModel
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class ContextualEduUiCoordinatorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val interactor = kosmos.contextualEducationInteractor
    private lateinit var underTest: ContextualEduUiCoordinator
    @Mock private lateinit var toast: Toast

    @get:Rule val mockitoRule = MockitoJUnit.rule()

    @Before
    fun setUp() {
        val viewModel =
            ContextualEduViewModel(
                kosmos.applicationContext.resources,
                kosmos.keyboardTouchpadEduInteractor
            )
        underTest =
            ContextualEduUiCoordinator(kosmos.applicationCoroutineScope, viewModel) { _ -> toast }
        underTest.start()
        kosmos.keyboardTouchpadEduInteractor.start()
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun showToastOnNewEdu() =
        testScope.runTest {
            triggerEducation(BACK)
            runCurrent()
            verify(toast).show()
        }

    private suspend fun triggerEducation(gestureType: GestureType) {
        for (i in 1..KeyboardTouchpadEduInteractor.MAX_SIGNAL_COUNT) {
            interactor.incrementSignalCount(gestureType)
        }
    }
}
