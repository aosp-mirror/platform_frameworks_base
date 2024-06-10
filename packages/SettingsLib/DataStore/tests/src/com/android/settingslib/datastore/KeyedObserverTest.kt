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
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class KeyedObserverTest {
    private val observer1 = mock<KeyedObserver<Any?>>()
    private val observer2 = mock<KeyedObserver<Any?>>()
    private val keyedObserver1 = mock<KeyedObserver<Any>>()
    private val keyedObserver2 = mock<KeyedObserver<Any>>()

    private val key1 = Object()
    private val key2 = Object()

    private val executor1: Executor = MoreExecutors.directExecutor()
    private val executor2: Executor = MoreExecutors.newDirectExecutorService()
    private val keyedObservable = KeyedDataObservable<Any>()

    @Test
    fun addObserver_sameExecutor() {
        keyedObservable.addObserver(observer1, executor1)
        keyedObservable.addObserver(observer1, executor1)
    }

    @Test
    fun addObserver_keyedObserver_sameExecutor() {
        keyedObservable.addObserver(key1, keyedObserver1, executor1)
        keyedObservable.addObserver(key1, keyedObserver1, executor1)
    }

    @Test
    fun addObserver_differentExecutor() {
        keyedObservable.addObserver(observer1, executor1)
        Assert.assertThrows(IllegalStateException::class.java) {
            keyedObservable.addObserver(observer1, executor2)
        }
    }

    @Test
    fun addObserver_keyedObserver_differentExecutor() {
        keyedObservable.addObserver(key1, keyedObserver1, executor1)
        Assert.assertThrows(IllegalStateException::class.java) {
            keyedObservable.addObserver(key1, keyedObserver1, executor2)
        }
    }

    @Test
    fun addObserver_weaklyReferenced() {
        val counter = AtomicInteger()
        var observer: KeyedObserver<Any?>? = KeyedObserver { _, _ -> counter.incrementAndGet() }
        keyedObservable.addObserver(observer!!, executor1)

        keyedObservable.notifyChange(DataChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)

        // trigger GC, the observer callback should not be invoked
        null.also { observer = it }
        System.gc()
        System.runFinalization()

        keyedObservable.notifyChange(DataChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)
    }

    @Test
    fun addObserver_keyedObserver_weaklyReferenced() {
        val counter = AtomicInteger()
        var keyObserver: KeyedObserver<Any>? = KeyedObserver { _, _ -> counter.incrementAndGet() }
        keyedObservable.addObserver(key1, keyObserver!!, executor1)

        keyedObservable.notifyChange(key1, DataChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)

        // trigger GC, the observer callback should not be invoked
        null.also { keyObserver = it }
        System.gc()
        System.runFinalization()

        keyedObservable.notifyChange(key1, DataChangeReason.UPDATE)
        assertThat(counter.get()).isEqualTo(1)
    }

    @Test
    fun addObserver_notifyObservers_removeObserver() {
        keyedObservable.addObserver(observer1, executor1)
        keyedObservable.addObserver(observer2, executor2)

        keyedObservable.notifyChange(DataChangeReason.UPDATE)
        verify(observer1).onKeyChanged(null, DataChangeReason.UPDATE)
        verify(observer2).onKeyChanged(null, DataChangeReason.UPDATE)

        reset(observer1, observer2)
        keyedObservable.removeObserver(observer2)

        keyedObservable.notifyChange(DataChangeReason.DELETE)
        verify(observer1).onKeyChanged(null, DataChangeReason.DELETE)
        verify(observer2, never()).onKeyChanged(null, DataChangeReason.DELETE)
    }

    @Test
    fun addObserver_keyedObserver_notifyObservers_removeObserver() {
        keyedObservable.addObserver(key1, keyedObserver1, executor1)
        keyedObservable.addObserver(key2, keyedObserver2, executor2)

        keyedObservable.notifyChange(key1, DataChangeReason.UPDATE)
        verify(keyedObserver1).onKeyChanged(key1, DataChangeReason.UPDATE)
        verify(keyedObserver2, never()).onKeyChanged(key2, DataChangeReason.UPDATE)

        reset(keyedObserver1, keyedObserver2)
        keyedObservable.removeObserver(key1, keyedObserver1)

        keyedObservable.notifyChange(key1, DataChangeReason.DELETE)
        verify(keyedObserver1, never()).onKeyChanged(key1, DataChangeReason.DELETE)
        verify(keyedObserver2, never()).onKeyChanged(key2, DataChangeReason.DELETE)
    }

    @Test
    fun notifyChange_addMoreTypeObservers_checkOnKeyChanged() {
        keyedObservable.addObserver(observer1, executor1)
        keyedObservable.addObserver(key1, keyedObserver1, executor1)
        keyedObservable.addObserver(key2, keyedObserver2, executor1)

        keyedObservable.notifyChange(DataChangeReason.UPDATE)
        verify(observer1).onKeyChanged(null, DataChangeReason.UPDATE)
        verify(keyedObserver1).onKeyChanged(key1, DataChangeReason.UPDATE)
        verify(keyedObserver2).onKeyChanged(key2, DataChangeReason.UPDATE)

        reset(observer1, keyedObserver1, keyedObserver2)
        keyedObservable.notifyChange(key1, DataChangeReason.UPDATE)

        verify(observer1).onKeyChanged(key1, DataChangeReason.UPDATE)
        verify(keyedObserver1).onKeyChanged(key1, DataChangeReason.UPDATE)
        verify(keyedObserver2, never()).onKeyChanged(key1, DataChangeReason.UPDATE)

        reset(observer1, keyedObserver1, keyedObserver2)
        keyedObservable.notifyChange(key2, DataChangeReason.UPDATE)

        verify(observer1).onKeyChanged(key2, DataChangeReason.UPDATE)
        verify(keyedObserver1, never()).onKeyChanged(key2, DataChangeReason.UPDATE)
        verify(keyedObserver2).onKeyChanged(key2, DataChangeReason.UPDATE)
    }

    @Test
    fun notifyChange_addObserverWithinCallback() {
        // ConcurrentModificationException is raised if it is not implemented correctly
        val observer: KeyedObserver<Any?> = KeyedObserver { _, _ ->
            keyedObservable.addObserver(observer1, executor1)
        }

        keyedObservable.addObserver(observer, executor1)

        keyedObservable.notifyChange(DataChangeReason.UPDATE)
        keyedObservable.removeObserver(observer)
    }

    @Test
    fun notifyChange_KeyedObserver_addObserverWithinCallback() {
        // ConcurrentModificationException is raised if it is not implemented correctly
        val keyObserver: KeyedObserver<Any?> = KeyedObserver { _, _ ->
            keyedObservable.addObserver(key1, keyedObserver1, executor1)
        }

        keyedObservable.addObserver(key1, keyObserver, executor1)

        keyedObservable.notifyChange(key1, DataChangeReason.UPDATE)
        keyedObservable.removeObserver(key1, keyObserver)
    }
}
