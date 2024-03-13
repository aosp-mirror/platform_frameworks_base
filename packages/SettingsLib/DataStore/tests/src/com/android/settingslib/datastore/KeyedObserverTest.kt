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

import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert
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
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyedObserverTest {
    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var observer1: KeyedObserver<Any?>

    @Mock
    private lateinit var observer2: KeyedObserver<Any?>

    @Mock
    private lateinit var keyedObserver1: KeyedObserver<Any>

    @Mock
    private lateinit var keyedObserver2: KeyedObserver<Any>

    @Mock
    private lateinit var key1: Any

    @Mock
    private lateinit var key2: Any

    @Mock
    private lateinit var executor: Executor

    private val keyedObservable = KeyedDataObservable<Any>()

    @Test
    fun addObserver_sameExecutor() {
        keyedObservable.addObserver(observer1, executor)
        keyedObservable.addObserver(observer1, executor)
    }

    @Test
    fun addObserver_keyedObserver_sameExecutor() {
        keyedObservable.addObserver(key1, keyedObserver1, executor)
        keyedObservable.addObserver(key1, keyedObserver1, executor)
    }

    @Test
    fun addObserver_differentExecutor() {
        keyedObservable.addObserver(observer1, executor)
        Assert.assertThrows(IllegalStateException::class.java) {
            keyedObservable.addObserver(observer1, directExecutor())
        }
    }

    @Test
    fun addObserver_keyedObserver_differentExecutor() {
        keyedObservable.addObserver(key1, keyedObserver1, executor)
        Assert.assertThrows(IllegalStateException::class.java) {
            keyedObservable.addObserver(key1, keyedObserver1, directExecutor())
        }
    }

    @Test
    fun addObserver_weaklyReferenced() {
        val counter = AtomicInteger()
        var observer: KeyedObserver<Any?>? = KeyedObserver { _, _ -> counter.incrementAndGet() }
        keyedObservable.addObserver(observer!!, directExecutor())

        keyedObservable.notifyChange(ChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)

        // trigger GC, the observer callback should not be invoked
        null.also { observer = it }
        System.gc()
        System.runFinalization()

        keyedObservable.notifyChange(ChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)
    }

    @Test
    fun addObserver_keyedObserver_weaklyReferenced() {
        val counter = AtomicInteger()
        var keyObserver: KeyedObserver<Any>? = KeyedObserver { _, _ -> counter.incrementAndGet() }
        keyedObservable.addObserver(key1, keyObserver!!, directExecutor())

        keyedObservable.notifyChange(key1, ChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)

        // trigger GC, the observer callback should not be invoked
        null.also { keyObserver = it }
        System.gc()
        System.runFinalization()

        keyedObservable.notifyChange(key1, ChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)
    }

    @Test
    fun addObserver_notifyObservers_removeObserver() {
        keyedObservable.addObserver(observer1, directExecutor())
        keyedObservable.addObserver(observer2, executor)

        keyedObservable.notifyChange(ChangeReason.UPDATE)
        verify(observer1).onKeyChanged(null, ChangeReason.UPDATE)
        verify(observer2, never()).onKeyChanged(any(), any())
        verify(executor).execute(any())

        reset(observer1, executor)
        keyedObservable.removeObserver(observer2)

        keyedObservable.notifyChange(ChangeReason.DELETE)
        verify(observer1).onKeyChanged(null, ChangeReason.DELETE)
        verify(executor, never()).execute(any())
    }

    @Test
    fun addObserver_keyedObserver_notifyObservers_removeObserver() {
        keyedObservable.addObserver(key1, keyedObserver1, directExecutor())
        keyedObservable.addObserver(key2, keyedObserver2, executor)

        keyedObservable.notifyChange(key1, ChangeReason.UPDATE)
        verify(keyedObserver1).onKeyChanged(key1, ChangeReason.UPDATE)
        verify(keyedObserver2, never()).onKeyChanged(any(), any())
        verify(executor, never()).execute(any())

        reset(keyedObserver1, executor)
        keyedObservable.removeObserver(key2, keyedObserver2)

        keyedObservable.notifyChange(key1, ChangeReason.DELETE)
        verify(keyedObserver1).onKeyChanged(key1, ChangeReason.DELETE)
        verify(executor, never()).execute(any())
    }

    @Test
    fun notifyChange_addMoreTypeObservers_checkOnKeyChanged() {
        keyedObservable.addObserver(observer1, directExecutor())
        keyedObservable.addObserver(key1, keyedObserver1, directExecutor())
        keyedObservable.addObserver(key2, keyedObserver2, directExecutor())

        keyedObservable.notifyChange(ChangeReason.UPDATE)
        verify(observer1).onKeyChanged(null, ChangeReason.UPDATE)
        verify(keyedObserver1).onKeyChanged(key1, ChangeReason.UPDATE)
        verify(keyedObserver2).onKeyChanged(key2, ChangeReason.UPDATE)

        reset(observer1, keyedObserver1, keyedObserver2)
        keyedObservable.notifyChange(key1, ChangeReason.UPDATE)

        verify(observer1).onKeyChanged(key1, ChangeReason.UPDATE)
        verify(keyedObserver1).onKeyChanged(key1, ChangeReason.UPDATE)
        verify(keyedObserver2, never()).onKeyChanged(key1, ChangeReason.UPDATE)

        reset(observer1, keyedObserver1, keyedObserver2)
        keyedObservable.notifyChange(key2, ChangeReason.UPDATE)

        verify(observer1).onKeyChanged(key2, ChangeReason.UPDATE)
        verify(keyedObserver1, never()).onKeyChanged(key2, ChangeReason.UPDATE)
        verify(keyedObserver2).onKeyChanged(key2, ChangeReason.UPDATE)
    }

    @Test
    fun notifyChange_addObserverWithinCallback() {
        // ConcurrentModificationException is raised if it is not implemented correctly
        val observer: KeyedObserver<Any?> = KeyedObserver { _, _ ->
            keyedObservable.addObserver(observer1, executor)
        }

        keyedObservable.addObserver(observer, directExecutor())

        keyedObservable.notifyChange(ChangeReason.UPDATE)
        keyedObservable.removeObserver(observer)
    }

    @Test
    fun notifyChange_KeyedObserver_addObserverWithinCallback() {
        // ConcurrentModificationException is raised if it is not implemented correctly
        val keyObserver: KeyedObserver<Any?> = KeyedObserver { _, _ ->
            keyedObservable.addObserver(key1, keyedObserver1, executor)
        }

        keyedObservable.addObserver(key1, keyObserver, directExecutor())

        keyedObservable.notifyChange(key1, ChangeReason.UPDATE)
        keyedObservable.removeObserver(key1, keyObserver)
    }
}