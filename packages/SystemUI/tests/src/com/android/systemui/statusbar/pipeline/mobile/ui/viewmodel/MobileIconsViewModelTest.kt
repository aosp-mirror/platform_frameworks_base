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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileIconsViewModelTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconsViewModel
    private val interactor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())

    @Mock private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var constants: ConnectivityConstants

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val subscriptionIdsFlow =
            interactor.filteredSubscriptions
                .map { subs -> subs.map { it.subscriptionId } }
                .stateIn(testScope.backgroundScope, SharingStarted.WhileSubscribed(), listOf())

        underTest =
            MobileIconsViewModel(
                subscriptionIdsFlow,
                interactor,
                logger,
                constants,
                testScope.backgroundScope,
                statusBarPipelineFlags,
            )

        interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)
    }

    @Test
    fun `caching - mobile icon view model is reused for same sub id`() =
        testScope.runTest {
            val model1 = underTest.viewModelForSub(1, StatusBarLocation.HOME)
            val model2 = underTest.viewModelForSub(1, StatusBarLocation.QS)

            assertThat(model1.commonImpl).isSameInstanceAs(model2.commonImpl)
        }

    @Test
    fun `caching - invalid view models are removed from cache when sub disappears`() =
        testScope.runTest {
            // Retrieve models to trigger caching
            val model1 = underTest.viewModelForSub(1, StatusBarLocation.HOME)
            val model2 = underTest.viewModelForSub(2, StatusBarLocation.QS)

            // Both impls are cached
            assertThat(underTest.mobileIconSubIdCache)
                .containsExactly(1, model1.commonImpl, 2, model2.commonImpl)

            // SUB_1 is removed from the list...
            interactor.filteredSubscriptions.value = listOf(SUB_2)

            // ... and dropped from the cache
            assertThat(underTest.mobileIconSubIdCache).containsExactly(2, model2.commonImpl)
        }

    companion object {
        private val SUB_1 = SubscriptionModel(subscriptionId = 1, isOpportunistic = false)
        private val SUB_2 = SubscriptionModel(subscriptionId = 2, isOpportunistic = false)
    }
}
