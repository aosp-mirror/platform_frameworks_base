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

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ConstraintChangesTest : SysuiTestCase() {

    @Test
    fun testSumWithoutNulls() {
        val mockQQS1: ConstraintChange = mock()
        val mockQS1: ConstraintChange = mock()
        val mockLS1: ConstraintChange = mock()
        val mockQQS2: ConstraintChange = mock()
        val mockQS2: ConstraintChange = mock()
        val mockLS2: ConstraintChange = mock()

        val changes1 = ConstraintsChanges(mockQQS1, mockQS1, mockLS1)
        val changes2 = ConstraintsChanges(mockQQS2, mockQS2, mockLS2)

        val sum = changes1 + changes2

        val constraintSet = ConstraintSet()
        sum.qqsConstraintsChanges?.invoke(constraintSet)
        sum.qsConstraintsChanges?.invoke(constraintSet)
        sum.largeScreenConstraintsChanges?.invoke(constraintSet)

        val inOrder = inOrder(mockQQS1, mockQS1, mockLS1, mockQQS2, mockQS2, mockLS2)

        inOrder.verify(mockQQS1).invoke(constraintSet)
        inOrder.verify(mockQQS2).invoke(constraintSet)
        inOrder.verify(mockQS1).invoke(constraintSet)
        inOrder.verify(mockQS2).invoke(constraintSet)
        inOrder.verify(mockLS1).invoke(constraintSet)
        inOrder.verify(mockLS2).invoke(constraintSet)
    }

    @Test
    fun testSumWithSomeNulls() {
        val mockQQS: ConstraintChange = mock()
        val mockQS: ConstraintChange = mock()

        val changes1 = ConstraintsChanges(mockQQS, null, null)
        val changes2 = ConstraintsChanges(null, mockQS, null)

        val sum = changes1 + changes2

        assertThat(sum.qqsConstraintsChanges).isSameInstanceAs(mockQQS)
        assertThat(sum.qsConstraintsChanges).isSameInstanceAs(mockQS)
        assertThat(sum.largeScreenConstraintsChanges).isNull()
    }
}
