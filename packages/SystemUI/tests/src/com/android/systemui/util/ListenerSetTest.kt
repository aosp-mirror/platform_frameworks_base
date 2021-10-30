/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.test.suitebuilder.annotation.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ListenerSetTest : SysuiTestCase() {

    var runnableSet: ListenerSet<Runnable> = ListenerSet()

    @Before
    fun setup() {
        runnableSet = ListenerSet()
    }

    @Test
    fun addIfAbsent_doesNotDoubleAdd() {
        // setup & preconditions
        val runnable1 = Runnable { }
        val runnable2 = Runnable { }
        assertThat(runnableSet.toList()).isEmpty()

        // Test that an element can be added
        assertThat(runnableSet.addIfAbsent(runnable1)).isTrue()
        assertThat(runnableSet.toList()).containsExactly(runnable1)

        // Test that a second element can be added
        assertThat(runnableSet.addIfAbsent(runnable2)).isTrue()
        assertThat(runnableSet.toList()).containsExactly(runnable1, runnable2)

        // Test that re-adding the first element does nothing and returns false
        assertThat(runnableSet.addIfAbsent(runnable1)).isFalse()
        assertThat(runnableSet.toList()).containsExactly(runnable1, runnable2)
    }

    @Test
    fun remove_removesListener() {
        // setup and preconditions
        val runnable1 = Runnable { }
        val runnable2 = Runnable { }
        assertThat(runnableSet.toList()).isEmpty()
        runnableSet.addIfAbsent(runnable1)
        runnableSet.addIfAbsent(runnable2)
        assertThat(runnableSet.toList()).containsExactly(runnable1, runnable2)

        // Test that removing the first runnable only removes that one runnable
        assertThat(runnableSet.remove(runnable1)).isTrue()
        assertThat(runnableSet.toList()).containsExactly(runnable2)

        // Test that removing a non-present runnable does not error
        assertThat(runnableSet.remove(runnable1)).isFalse()
        assertThat(runnableSet.toList()).containsExactly(runnable2)

        // Test that removing the other runnable succeeds
        assertThat(runnableSet.remove(runnable2)).isTrue()
        assertThat(runnableSet.toList()).isEmpty()
    }

    @Test
    fun remove_isReentrantSafe() {
        // Setup and preconditions
        val runnablesCalled = mutableListOf<Int>()
        // runnable1 is configured to remove itself
        val runnable1 = object : Runnable {
            override fun run() {
                runnableSet.remove(this)
                runnablesCalled.add(1)
            }
        }
        val runnable2 = Runnable {
            runnablesCalled.add(2)
        }
        assertThat(runnableSet.toList()).isEmpty()
        runnableSet.addIfAbsent(runnable1)
        runnableSet.addIfAbsent(runnable2)
        assertThat(runnableSet.toList()).containsExactly(runnable1, runnable2)

        // Test that both runnables are called and 1 was removed
        for (runnable in runnableSet) {
            runnable.run()
        }
        assertThat(runnablesCalled).containsExactly(1, 2)
        assertThat(runnableSet.toList()).containsExactly(runnable2)
    }

    @Test
    fun addIfAbsent_isReentrantSafe() {
        // Setup and preconditions
        val runnablesCalled = mutableListOf<Int>()
        val runnable99 = Runnable {
            runnablesCalled.add(99)
        }
        // runnable1 is configured to add runnable99
        val runnable1 = Runnable {
            runnableSet.addIfAbsent(runnable99)
            runnablesCalled.add(1)
        }
        val runnable2 = Runnable {
            runnablesCalled.add(2)
        }
        assertThat(runnableSet.toList()).isEmpty()
        runnableSet.addIfAbsent(runnable1)
        runnableSet.addIfAbsent(runnable2)
        assertThat(runnableSet.toList()).containsExactly(runnable1, runnable2)

        // Test that both original runnables are called and 99 was added but not called
        for (runnable in runnableSet) {
            runnable.run()
        }
        assertThat(runnablesCalled).containsExactly(1, 2)
        assertThat(runnableSet.toList()).containsExactly(runnable1, runnable2, runnable99)
    }
}