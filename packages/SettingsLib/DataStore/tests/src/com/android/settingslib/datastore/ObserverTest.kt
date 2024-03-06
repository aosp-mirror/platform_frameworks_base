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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class ObserverTest {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var observer1: Observer

    @Mock private lateinit var observer2: Observer

    @Mock private lateinit var executor: Executor

    private val observable = DataObservable()

    @Test
    fun addObserver_sameExecutor() {
        observable.addObserver(observer1, executor)
        observable.addObserver(observer1, executor)
    }

    @Test
    fun addObserver_differentExecutor() {
        observable.addObserver(observer1, executor)
        assertThrows(IllegalStateException::class.java) {
            observable.addObserver(observer1, MoreExecutors.directExecutor())
        }
    }

    @Test
    fun addObserver_weaklyReferenced() {
        val counter = AtomicInteger()
        var observer: Observer? = Observer { counter.incrementAndGet() }
        observable.addObserver(observer!!, MoreExecutors.directExecutor())

        observable.notifyChange(ChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)

        // trigger GC, the observer callback should not be invoked
        @Suppress("unused")
        observer = null
        System.gc()
        System.runFinalization()

        observable.notifyChange(ChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)
    }

    @Test
    fun addObserver_notifyObservers_removeObserver() {
        observable.addObserver(observer1, MoreExecutors.directExecutor())
        observable.addObserver(observer2, executor)

        observable.notifyChange(ChangeReason.DELETE)

        verify(observer1).onChanged(ChangeReason.DELETE)
        verify(observer2, never()).onChanged(any())
        verify(executor).execute(any())

        reset(observer1, executor)
        observable.removeObserver(observer2)

        observable.notifyChange(ChangeReason.UPDATE)
        verify(observer1).onChanged(ChangeReason.UPDATE)
        verify(executor, never()).execute(any())
    }

    @Test
    fun notifyChange_addObserverWithinCallback() {
        // ConcurrentModificationException is raised if it is not implemented correctly
        observable.addObserver(
            { observable.addObserver(observer1, executor) },
            MoreExecutors.directExecutor()
        )
        observable.notifyChange(ChangeReason.UPDATE)
    }
}
