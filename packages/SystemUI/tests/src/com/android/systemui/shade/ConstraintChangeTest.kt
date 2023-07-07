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
package com.android.systemui.shade

import android.testing.AndroidTestingRunner
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ConstraintChangeTest : SysuiTestCase() {

    @Test
    fun testSumNonNull() {
        val mock1: ConstraintChange = mock()
        val mock2: ConstraintChange = mock()

        val constraintSet = ConstraintSet()

        val sum = mock1 + mock2
        sum?.invoke(constraintSet)

        val inOrder = inOrder(mock1, mock2)
        inOrder.verify(mock1).invoke(constraintSet)
        inOrder.verify(mock2).invoke(constraintSet)
    }

    @Test
    fun testSumThisNull() {
        val mock: ConstraintChange = mock()
        val constraintSet = ConstraintSet()

        val sum = (null as? ConstraintChange?) + mock
        sum?.invoke(constraintSet)

        verify(mock).invoke(constraintSet)
    }

    @Test
    fun testSumThisNull_notWrapped() {
        val change: ConstraintChange = {}

        val sum = (null as? ConstraintChange?) + change
        assertThat(sum).isSameInstanceAs(change)
    }

    @Test
    fun testSumOtherNull() {
        val mock: ConstraintChange = mock()
        val constraintSet = ConstraintSet()

        val sum = mock + (null as? ConstraintChange?)
        sum?.invoke(constraintSet)

        verify(mock).invoke(constraintSet)
    }

    @Test
    fun testSumOtherNull_notWrapped() {
        val change: ConstraintChange = {}

        val sum = change + (null as? ConstraintChange?)
        assertThat(sum).isSameInstanceAs(change)
    }
}
