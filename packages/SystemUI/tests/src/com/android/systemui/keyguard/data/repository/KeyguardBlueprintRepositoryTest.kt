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

package com.android.systemui.keyguard.data.repository

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint.Companion.DEFAULT
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class KeyguardBlueprintRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: KeyguardBlueprintRepository
    @Mock lateinit var configurationRepository: ConfigurationRepository
    @Mock lateinit var defaultLockscreenBlueprint: DefaultKeyguardBlueprint
    private val testScope = TestScope(StandardTestDispatcher())

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(defaultLockscreenBlueprint.id).thenReturn(DEFAULT)
        underTest =
            KeyguardBlueprintRepository(
                configurationRepository,
                setOf(defaultLockscreenBlueprint),
            )
    }

    @Test
    fun testApplyBlueprint_DefaultLayout() {
        testScope.runTest {
            val blueprint by collectLastValue(underTest.blueprint)
            underTest.applyBlueprint(defaultLockscreenBlueprint)
            assertThat(blueprint).isEqualTo(defaultLockscreenBlueprint)
        }
    }

    @Test
    fun testRefreshBlueprint() {
        testScope.runTest {
            val blueprint by collectLastValue(underTest.blueprint)
            underTest.refreshBlueprint()
            assertThat(blueprint).isEqualTo(defaultLockscreenBlueprint)
        }
    }

    @Test
    fun testTransitionToLayout_validId() {
        assertThat(underTest.applyBlueprint(DEFAULT)).isTrue()
    }
    @Test
    fun testTransitionToLayout_invalidId() {
        assertThat(underTest.applyBlueprint("abc")).isFalse()
    }
}
