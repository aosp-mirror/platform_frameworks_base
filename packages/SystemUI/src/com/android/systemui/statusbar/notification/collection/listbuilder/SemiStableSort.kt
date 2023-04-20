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

import androidx.annotation.VisibleForTesting
import kotlin.math.sign

class SemiStableSort {
    val preallocatedWorkspace by lazy { ArrayList<Any>() }
    val preallocatedAdditions by lazy { ArrayList<Any>() }
    val preallocatedMapToIndex by lazy { HashMap<Any, Int>() }
    val preallocatedMapToIndexComparator: Comparator<Any> by lazy {
        Comparator.comparingInt { item -> preallocatedMapToIndex[item] ?: -1 }
    }

    /**
     * Sort the given [items] such that items which have a [stableOrder] will all be in that order,
     * items without a [stableOrder] will be sorted according to the comparator, and the two sets of
     * items will be combined to have the fewest elements out of order according to the [comparator]
     * . The result will be placed into the original [items] list.
     */
    fun <T : Any> sort(
        items: MutableList<T>,
        stableOrder: StableOrder<in T>,
        comparator: Comparator<in T>,
    ): Boolean =
        withWorkspace<T, Boolean> { workspace ->
            val ordered =
                sortTo(
                    items,
                    stableOrder,
                    comparator,
                    workspace,
                )
            items.clear()
            items.addAll(workspace)
            return ordered
        }

    /**
     * Sort the given [items] such that items which have a [stableOrder] will all be in that order,
     * items without a [stableOrder] will be sorted according to the comparator, and the two sets of
     * items will be combined to have the fewest elements out of order according to the [comparator]
     * . The result will be put into [output].
     */
    fun <T : Any> sortTo(
        items: Iterable<T>,
        stableOrder: StableOrder<in T>,
        comparator: Comparator<in T>,
        output: MutableList<T>,
    ): Boolean {
        if (DEBUG) println("\n> START from ${items.map { it to stableOrder.getRank(it) }}")
        // If array already has elements, use subList to ensure we only append
        val result = output.takeIf { it.isEmpty() } ?: output.subList(output.size, output.size)
        items.filterTo(result) { stableOrder.getRank(it) != null }
        result.sortBy { stableOrder.getRank(it)!! }
        val isOrdered = result.isSorted(comparator)
        withAdditions<T> { additions ->
            items.filterTo(additions) { stableOrder.getRank(it) == null }
            additions.sortWith(comparator)
            insertPreSortedElementsWithFewestMisOrderings(result, additions, comparator)
        }
        return isOrdered
    }

    /**
     * Rearrange the [sortedItems] to enforce that items are in the [stableOrder], and store the
     * result in [output]. Items with a [stableOrder] will be in that order, items without a
     * [stableOrder] will remain in same relative order as the input, and the two sets of items will
     * be combined to have the fewest elements moved from their locations in the original.
     */
    fun <T : Any> stabilizeTo(
        sortedItems: Iterable<T>,
        stableOrder: StableOrder<in T>,
        output: MutableList<T>,
    ): Boolean {
        // Append to the output array if present
        val result = output.takeIf { it.isEmpty() } ?: output.subList(output.size, output.size)
        sortedItems.filterTo(result) { stableOrder.getRank(it) != null }
        val stableRankComparator = compareBy<T> { stableOrder.getRank(it)!! }
        val isOrdered = result.isSorted(stableRankComparator)
        if (!isOrdered) {
            result.sortWith(stableRankComparator)
        }
        if (result.isEmpty()) {
            sortedItems.filterTo(result) { stableOrder.getRank(it) == null }
            return isOrdered
        }
        withAdditions<T> { additions ->
            sortedItems.filterTo(additions) { stableOrder.getRank(it) == null }
            if (additions.isNotEmpty()) {
                withIndexOfComparator(sortedItems) { comparator ->
                    insertPreSortedElementsWithFewestMisOrderings(result, additions, comparator)
                }
            }
        }
        return isOrdered
    }

    private inline fun <T : Any, R> withWorkspace(block: (ArrayList<T>) -> R): R {
        preallocatedWorkspace.clear()
        val result = block(preallocatedWorkspace as ArrayList<T>)
        preallocatedWorkspace.clear()
        return result
    }

    private inline fun <T : Any> withAdditions(block: (ArrayList<T>) -> Unit) {
        preallocatedAdditions.clear()
        block(preallocatedAdditions as ArrayList<T>)
        preallocatedAdditions.clear()
    }

    private inline fun <T : Any> withIndexOfComparator(
        sortedItems: Iterable<T>,
        block: (Comparator<in T>) -> Unit
    ) {
        preallocatedMapToIndex.clear()
        sortedItems.forEachIndexed { i, item -> preallocatedMapToIndex[item] = i }
        block(preallocatedMapToIndexComparator as Comparator<in T>)
        preallocatedMapToIndex.clear()
    }

    companion object {

        /**
         * This is the core of the algorithm.
         *
         * Insert [preSortedAdditions] (the elements to be inserted) into [existing] without
         * changing the relative order of any elements already in [existing], even though those
         * elements may be mis-ordered relative to the [comparator], such that the total number of
         * elements which are ordered incorrectly according to the [comparator] is fewest.
         */
        private fun <T> insertPreSortedElementsWithFewestMisOrderings(
            existing: MutableList<T>,
            preSortedAdditions: Iterable<T>,
            comparator: Comparator<in T>,
        ) {
            if (DEBUG) println("  To $existing insert $preSortedAdditions with fewest misordering")
            var iStart = 0
            preSortedAdditions.forEach { toAdd ->
                if (DEBUG) println("    need to add $toAdd to $existing, starting at $iStart")
                var cmpSum = 0
                var cmpSumMax = 0
                var iCmpSumMax = iStart
                if (DEBUG) print("      ")
                for (i in iCmpSumMax until existing.size) {
                    val cmp = comparator.compare(toAdd, existing[i]).sign
                    cmpSum += cmp
                    if (cmpSum > cmpSumMax) {
                        cmpSumMax = cmpSum
                        iCmpSumMax = i + 1
                    }
                    if (DEBUG) print("sum[$i]=$cmpSum, ")
                }
                if (DEBUG) println("inserting $toAdd at $iCmpSumMax")
                existing.add(iCmpSumMax, toAdd)
                iStart = iCmpSumMax + 1
            }
        }

        /** Determines if a list is correctly sorted according to the given comparator */
        @VisibleForTesting
        fun <T> List<T>.isSorted(comparator: Comparator<T>): Boolean {
            if (this.size <= 1) {
                return true
            }
            val iterator = this.iterator()
            var previous = iterator.next()
            var current: T?
            while (iterator.hasNext()) {
                current = iterator.next()
                if (comparator.compare(previous, current) > 0) {
                    return false
                }
                previous = current
            }
            return true
        }
    }

    fun interface StableOrder<T> {
        fun getRank(item: T): Int?
    }
}

val DEBUG = false
