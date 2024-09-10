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
package com.android.systemui.statusbar.notification.collection.listbuilder

import android.testing.TestableLooper.RunWithLooper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class SemiStableSortTest : SysuiTestCase() {

    var shuffleInput: Boolean = false
    var testStabilizeTo: Boolean = false
    var sorter: SemiStableSort? = null

    @Before
    fun setUp() {
        shuffleInput = false
        sorter = null
    }

    private fun stringStabilizeTo(
        stableOrder: String,
        activeOrder: String,
    ): Pair<String, Boolean> {
        val actives = activeOrder.toMutableList()
        val result = mutableListOf<Char>()
        return (sorter ?: SemiStableSort())
            .stabilizeTo(
                actives,
                { ch -> stableOrder.indexOf(ch).takeIf { it >= 0 } },
                result,
            )
            .let { ordered -> result.joinToString("") to ordered }
    }

    private fun stringSort(
        stableOrder: String,
        activeOrder: String,
    ): Pair<String, Boolean> {
        val actives = activeOrder.toMutableList()
        if (shuffleInput) {
            actives.shuffle()
        }
        return (sorter ?: SemiStableSort())
            .sort(
                actives,
                { ch -> stableOrder.indexOf(ch).takeIf { it >= 0 } },
                compareBy { activeOrder.indexOf(it) },
            )
            .let { ordered -> actives.joinToString("") to ordered }
    }

    private fun testCase(
        stableOrder: String,
        activeOrder: String,
        expected: String,
        expectOrdered: Boolean,
    ) {
        val (mergeResult, ordered) =
            if (testStabilizeTo) stringStabilizeTo(stableOrder, activeOrder)
            else stringSort(stableOrder, activeOrder)
        val resultPass = expected == mergeResult
        val orderedPass = ordered == expectOrdered
        val pass = resultPass && orderedPass
        val resultSuffix =
            if (resultPass) "result=$expected" else "expected=$expected got=$mergeResult"
        val orderedSuffix =
            if (orderedPass) "ordered=$ordered" else "expected ordered to be $expectOrdered"
        val readableResult = "stable=$stableOrder active=$activeOrder $resultSuffix $orderedSuffix"
        Log.d("SemiStableSortTest", "${if (pass) "PASS" else "FAIL"}: $readableResult")
        if (!pass) {
            throw AssertionError("Test case failed: $readableResult")
        }
    }

    private fun runAllTestCases() {
        // No input or output
        testCase("", "", "", true)
        // Remove everything
        testCase("ABCDEFG", "", "", true)
        // Literally no changes
        testCase("ABCDEFG", "ABCDEFG", "ABCDEFG", true)

        // No stable order
        testCase("", "ABCDEFG", "ABCDEFG", true)

        // F moved after A, and...
        testCase("ABCDEFG", "AFBCDEG", "ABCDEFG", false) // No other changes
        testCase("ABCDEFG", "AXFBCDEG", "AXBCDEFG", false) // Insert X before F
        testCase("ABCDEFG", "AFXBCDEG", "AXBCDEFG", false) // Insert X after F
        testCase("ABCDEFG", "AFBCDEXG", "ABCDEFXG", false) // Insert X where F was

        // B moved after F, and...
        testCase("ABCDEFG", "ACDEFBG", "ABCDEFG", false) // No other changes
        testCase("ABCDEFG", "ACDEFXBG", "ABCDEFXG", false) // Insert X before B
        testCase("ABCDEFG", "ACDEFBXG", "ABCDEFXG", false) // Insert X after B
        testCase("ABCDEFG", "AXCDEFBG", "AXBCDEFG", false) // Insert X where B was

        // Swap F and B, and...
        testCase("ABCDEFG", "AFCDEBG", "ABCDEFG", false) // No other changes
        testCase("ABCDEFG", "AXFCDEBG", "AXBCDEFG", false) // Insert X before F
        testCase("ABCDEFG", "AFXCDEBG", "AXBCDEFG", false) // Insert X after F
        testCase("ABCDEFG", "AFCXDEBG", "AXBCDEFG", false) // Insert X between CD (Alt: ABCXDEFG)
        testCase("ABCDEFG", "AFCDXEBG", "ABCDXEFG", false) // Insert X between DE (Alt: ABCDEFXG)
        testCase("ABCDEFG", "AFCDEXBG", "ABCDEFXG", false) // Insert X before B
        testCase("ABCDEFG", "AFCDEBXG", "ABCDEFXG", false) // Insert X after B

        // Remove a bunch of entries at once
        testCase("ABCDEFGHIJKL", "ACEGHI", "ACEGHI", true)

        // Remove a bunch of entries and scramble
        testCase("ABCDEFGHIJKL", "GCEHAI", "ACEGHI", false)

        // Add a bunch of entries at once
        testCase("ABCDEFG", "AVBWCXDYZEFG", "AVBWCXDYZEFG", true)

        // Add a bunch of entries and reverse originals
        // NOTE: Some of these don't have obviously correct answers
        testCase("ABCDEFG", "GFEBCDAVWXYZ", "ABCDEFGVWXYZ", false) // appended
        testCase("ABCDEFG", "VWXYZGFEBCDA", "VWXYZABCDEFG", false) // prepended
        testCase("ABCDEFG", "GFEBVWXYZCDA", "ABCDEFGVWXYZ", false) // closer to back: append
        testCase("ABCDEFG", "GFEVWXYZBCDA", "VWXYZABCDEFG", false) // closer to front: prepend
        testCase("ABCDEFG", "GFEVWBXYZCDA", "VWABCDEFGXYZ", false) // split new entries

        // Swap 2 pairs ("*BC*NO*"->"*NO*CB*"), remove EG, add UVWXYZ throughout
        testCase("ABCDEFGHIJKLMNOP", "AUNOVDFHWXIJKLMYCBZP", "AUVBCDFHWXIJKLMNOYZP", false)
    }

    @Test
    fun testSort() {
        testStabilizeTo = false
        shuffleInput = false
        sorter = null
        runAllTestCases()
    }

    @Test
    fun testSortWithSingleInstance() {
        testStabilizeTo = false
        shuffleInput = false
        sorter = SemiStableSort()
        runAllTestCases()
    }

    @Test
    fun testSortWithShuffledInput() {
        testStabilizeTo = false
        shuffleInput = true
        sorter = null
        runAllTestCases()
    }

    @Test
    fun testStabilizeTo() {
        testStabilizeTo = true
        sorter = null
        runAllTestCases()
    }

    @Test
    fun testStabilizeToWithSingleInstance() {
        testStabilizeTo = true
        sorter = SemiStableSort()
        runAllTestCases()
    }

    @Test
    fun testIsSorted() {
        val intCmp = Comparator<Int> { x, y -> Integer.compare(x, y) }
        SemiStableSort.apply {
            assertTrue(emptyList<Int>().isSorted(intCmp))
            assertTrue(listOf(1).isSorted(intCmp))
            assertTrue(listOf(1, 2).isSorted(intCmp))
            assertTrue(listOf(1, 2, 3).isSorted(intCmp))
            assertTrue(listOf(1, 2, 3, 4).isSorted(intCmp))
            assertTrue(listOf(1, 2, 3, 4, 5).isSorted(intCmp))
            assertTrue(listOf(1, 1, 1, 1, 1).isSorted(intCmp))
            assertTrue(listOf(1, 1, 2, 2, 3, 3).isSorted(intCmp))
            assertFalse(listOf(2, 1).isSorted(intCmp))
            assertFalse(listOf(2, 1, 2).isSorted(intCmp))
            assertFalse(listOf(1, 2, 1).isSorted(intCmp))
            assertFalse(listOf(1, 2, 3, 2, 5).isSorted(intCmp))
            assertFalse(listOf(5, 2, 3, 4, 5).isSorted(intCmp))
            assertFalse(listOf(1, 2, 3, 4, 1).isSorted(intCmp))
        }
    }
}
