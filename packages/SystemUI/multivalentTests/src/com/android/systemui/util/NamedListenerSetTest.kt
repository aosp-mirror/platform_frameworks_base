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

package com.android.systemui.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NamedListenerSetTest : ListenerSetTest() {
    override fun makeRunnableListenerSet(): IListenerSet<Runnable> = NamedListenerSet()

    private val runnableSet = NamedListenerSet(NamedRunnable::name)

    class NamedRunnable(val name: String, private val block: () -> Unit = {}) : Runnable {
        override fun run() = block()
    }

    @Test
    fun addIfAbsent_addsMultipleWithSameName_onlyIfInstanceIsAbsent() {
        // setup & preconditions
        val runnable1 = NamedRunnable("A")
        val runnable2 = NamedRunnable("A")
        assertThat(runnableSet).isEmpty()

        // Test that an element can be added
        assertThat(runnableSet.addIfAbsent(runnable1)).isTrue()
        assertThat(runnableSet).containsExactly(runnable1)

        // Test that a second element can be added, even with the same name
        assertThat(runnableSet.addIfAbsent(runnable2)).isTrue()
        assertThat(runnableSet).containsExactly(runnable1, runnable2)

        // Test that re-adding the first element does nothing and returns false
        assertThat(runnableSet.addIfAbsent(runnable1)).isFalse()
        assertThat(runnableSet).containsExactly(runnable1, runnable2)
    }

    @Test
    fun forEachNamed_includesCorrectNames() {
        val runnable1 = NamedRunnable("A")
        val runnable2 = NamedRunnable("X")
        val runnable3 = NamedRunnable("X")
        assertThat(runnableSet).isEmpty()

        assertThat(runnableSet.addIfAbsent(runnable1)).isTrue()
        assertThat(runnableSet.toNamedPairs())
            .containsExactly(
                "A" to runnable1,
            )

        assertThat(runnableSet.addIfAbsent(runnable2)).isTrue()
        assertThat(runnableSet.toNamedPairs())
            .containsExactly(
                "A" to runnable1,
                "X" to runnable2,
            )

        assertThat(runnableSet.addIfAbsent(runnable3)).isTrue()
        assertThat(runnableSet.toNamedPairs())
            .containsExactly(
                "A" to runnable1,
                "X" to runnable2,
                "X" to runnable3,
            )

        assertThat(runnableSet.remove(runnable1)).isTrue()
        assertThat(runnableSet.toNamedPairs())
            .containsExactly(
                "X" to runnable2,
                "X" to runnable3,
            )

        assertThat(runnableSet.remove(runnable2)).isTrue()
        assertThat(runnableSet.toNamedPairs())
            .containsExactly(
                "X" to runnable3,
            )
    }

    /**
     * This private method uses [NamedListenerSet.forEachNamed] to produce a list of pairs in order
     * to validate that method.
     */
    private fun <T : Any> NamedListenerSet<T>.toNamedPairs() =
        sequence { forEachNamed { name, listener -> yield(name to listener) } }.toList()
}
