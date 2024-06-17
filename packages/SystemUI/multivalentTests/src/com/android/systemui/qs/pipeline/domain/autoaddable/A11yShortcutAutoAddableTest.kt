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

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.FakeAccessibilityQsShortcutsRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class A11yShortcutAutoAddableTest : SysuiTestCase() {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val a11yQsShortcutsRepository = FakeAccessibilityQsShortcutsRepository()
    private val underTest =
        A11yShortcutAutoAddable(a11yQsShortcutsRepository, testDispatcher, SPEC, TARGET_COMPONENT)

    @Test
    fun settingNotSet_noSignal() =
        testScope.runTest {
            val signal by collectLastValue(underTest.autoAddSignal(USER_ID))

            assertThat(signal).isNull() // null means no emitted value
        }

    @Test
    fun settingSetWithTarget_addSignal() =
        testScope.runTest {
            val signal by collectLastValue(underTest.autoAddSignal(USER_ID))
            assertThat(signal).isNull()

            a11yQsShortcutsRepository.setA11yQsShortcutTargets(
                USER_ID,
                setOf(TARGET_COMPONENT_FLATTEN)
            )

            assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
        }

    @Test
    fun settingSetWithoutTarget_removeSignal() =
        testScope.runTest {
            val signal by collectLastValue(flow = underTest.autoAddSignal(USER_ID))
            assertThat(signal).isNull()

            a11yQsShortcutsRepository.setA11yQsShortcutTargets(
                USER_ID,
                setOf(OTHER_COMPONENT_FLATTEN)
            )

            assertThat(signal).isEqualTo(AutoAddSignal.Remove(SPEC))
        }

    @Test
    fun settingSetWithMultipleComponents_containsTarget_addSignal() =
        testScope.runTest {
            val signal by collectLastValue(underTest.autoAddSignal(USER_ID))
            assertThat(signal).isNull()

            a11yQsShortcutsRepository.setA11yQsShortcutTargets(
                USER_ID,
                setOf(OTHER_COMPONENT_FLATTEN, TARGET_COMPONENT_FLATTEN)
            )

            assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
        }

    @Test
    fun settingSetWithMultipleComponents_doesNotContainTarget_removeSignal() =
        testScope.runTest {
            val signal by collectLastValue(underTest.autoAddSignal(USER_ID))
            assertThat(signal).isNull()

            a11yQsShortcutsRepository.setA11yQsShortcutTargets(
                USER_ID,
                setOf(OTHER_COMPONENT_FLATTEN, OTHER_COMPONENT_FLATTEN)
            )

            assertThat(signal).isEqualTo(AutoAddSignal.Remove(SPEC))
        }

    @Test
    fun multipleChangesWithTarget_onlyOneAddSignal() =
        testScope.runTest {
            val signals by collectValues(underTest.autoAddSignal(USER_ID))
            assertThat(signals).isEmpty()

            repeat(3) {
                a11yQsShortcutsRepository.setA11yQsShortcutTargets(
                    USER_ID,
                    setOf(TARGET_COMPONENT_FLATTEN)
                )
            }

            assertThat(signals.size).isEqualTo(1)
            assertThat(signals[0]).isEqualTo(AutoAddSignal.Add(SPEC))
        }

    @Test
    fun multipleChangesWithoutTarget_onlyOneRemoveSignal() =
        testScope.runTest {
            val signals by collectValues(underTest.autoAddSignal(USER_ID))
            assertThat(signals).isEmpty()

            repeat(3) {
                a11yQsShortcutsRepository.setA11yQsShortcutTargets(
                    USER_ID,
                    setOf("$OTHER_COMPONENT_FLATTEN$it")
                )
            }

            assertThat(signals.size).isEqualTo(1)
            assertThat(signals[0]).isEqualTo(AutoAddSignal.Remove(SPEC))
        }

    @Test
    fun settingSetWithTargetForUsers_onlySignalInThatUser() =
        testScope.runTest {
            val otherUserId = USER_ID + 1
            val signalTargetUser by collectLastValue(underTest.autoAddSignal(USER_ID))
            val signalOtherUser by collectLastValue(underTest.autoAddSignal(otherUserId))
            assertThat(signalTargetUser).isNull()
            assertThat(signalOtherUser).isNull()

            a11yQsShortcutsRepository.setA11yQsShortcutTargets(
                USER_ID,
                setOf(TARGET_COMPONENT_FLATTEN)
            )

            assertThat(signalTargetUser).isEqualTo(AutoAddSignal.Add(SPEC))
            assertThat(signalOtherUser).isNull()
        }

    @Test
    fun strategyAlways() {
        assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.Always)
    }

    companion object {
        private val SPEC = TileSpec.create("spec")
        private val TARGET_COMPONENT = ComponentName("FakePkgName", "FakeClassName")
        private val TARGET_COMPONENT_FLATTEN = TARGET_COMPONENT.flattenToString()
        private val OTHER_COMPONENT_FLATTEN =
            ComponentName("FakePkgName", "OtherClassName").flattenToString()
        private const val USER_ID = 0
    }
}
