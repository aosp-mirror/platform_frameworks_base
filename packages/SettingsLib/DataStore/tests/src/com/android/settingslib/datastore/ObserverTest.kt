/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.datastore

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class ObserverTest {
    private val observer1 = mock<Observer>()
    private val observer2 = mock<Observer>()
    private val originalObservable = mock<Observable>()

    private val executor1: Executor = MoreExecutors.directExecutor()
    private val executor2: Executor = MoreExecutors.newDirectExecutorService()
    private val observable = DataObservable(originalObservable)

    @Test
    fun addObserver_sameExecutor() {
        observable.addObserver(observer1, executor1)
        observable.addObserver(observer1, executor1)
    }

    @Test
    fun addObserver_differentExecutor() {
        observable.addObserver(observer1, executor1)
        assertThrows(IllegalStateException::class.java) {
            observable.addObserver(observer1, executor2)
        }
    }

    @Test
    fun addObserver_weaklyReferenced() {
        val counter = AtomicInteger()
        var observer: Observer? = Observer { _, _ -> counter.incrementAndGet() }
        observable.addObserver(observer!!, executor1)

        observable.notifyChange(DataChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)

        // trigger GC, the observer callback should not be invoked
        null.also { observer = it }
        System.gc()
        System.runFinalization()

        observable.notifyChange(DataChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)
    }

    @Test
    fun addObserver_notifyObservers_removeObserver() {
        observable.addObserver(observer1, executor1)
        observable.addObserver(observer2, executor2)

        observable.notifyChange(DataChangeReason.DELETE)

        verify(observer1).onChanged(originalObservable, DataChangeReason.DELETE)
        verify(observer2).onChanged(originalObservable, DataChangeReason.DELETE)

        reset(observer1, observer2)
        observable.removeObserver(observer2)

        observable.notifyChange(DataChangeReason.UPDATE)
        verify(observer1).onChanged(originalObservable, DataChangeReason.UPDATE)
        verify(observer2, never()).onChanged(any(), any())
    }

    @Test
    fun notifyChange_addObserverWithinCallback() {
        // ConcurrentModificationException is raised if it is not implemented correctly
        val observer = Observer { _, _ -> observable.addObserver(observer1, executor1) }
        observable.addObserver(observer, executor1)
        observable.notifyChange(DataChangeReason.UPDATE)
        observable.removeObserver(observer)
    }
}
