/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.lifecycle

import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.Assert
import com.android.systemui.util.mockito.argumentCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(JUnit4::class)
@RunWithLooper
class RepeatWhenAttachedTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    @JvmField @Rule val instantTaskExecutor = InstantTaskExecutorRule()

    @Mock private lateinit var view: View
    @Mock private lateinit var viewTreeObserver: ViewTreeObserver

    private lateinit var block: Block
    private lateinit var attachListeners: MutableList<View.OnAttachStateChangeListener>

    @Before
    fun setUp() {
        Assert.setTestThread(Thread.currentThread())
        whenever(view.viewTreeObserver).thenReturn(viewTreeObserver)
        whenever(view.windowVisibility).thenReturn(View.GONE)
        whenever(view.hasWindowFocus()).thenReturn(false)
        attachListeners = mutableListOf()
        whenever(view.addOnAttachStateChangeListener(any())).then {
            attachListeners.add(it.arguments[0] as View.OnAttachStateChangeListener)
        }
        whenever(view.removeOnAttachStateChangeListener(any())).then {
            attachListeners.remove(it.arguments[0] as View.OnAttachStateChangeListener)
        }
        block = Block()
    }

    @Test(expected = IllegalStateException::class)
    fun `repeatWhenAttached - enforces main thread`() = runBlockingTest {
        Assert.setTestThread(null)

        repeatWhenAttached()
    }

    @Test(expected = IllegalStateException::class)
    fun `repeatWhenAttached - dispose enforces main thread`() = runBlockingTest {
        val disposableHandle = repeatWhenAttached()
        Assert.setTestThread(null)

        disposableHandle.dispose()
    }

    @Test
    fun `repeatWhenAttached - view starts detached - runs block when attached`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(false)
        repeatWhenAttached()
        assertThat(block.invocationCount).isEqualTo(0)

        whenever(view.isAttachedToWindow).thenReturn(true)
        attachListeners.last().onViewAttachedToWindow(view)

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `repeatWhenAttached - view already attached - immediately runs block`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)

        repeatWhenAttached()

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `repeatWhenAttached - starts visible without focus - STARTED`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        whenever(view.windowVisibility).thenReturn(View.VISIBLE)

        repeatWhenAttached()

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.STARTED)
    }

    @Test
    fun `repeatWhenAttached - starts with focus but invisible - CREATED`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        whenever(view.hasWindowFocus()).thenReturn(true)

        repeatWhenAttached()

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `repeatWhenAttached - starts visible and with focus - RESUMED`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        whenever(view.windowVisibility).thenReturn(View.VISIBLE)
        whenever(view.hasWindowFocus()).thenReturn(true)

        repeatWhenAttached()

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun `repeatWhenAttached - becomes visible without focus - STARTED`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        repeatWhenAttached()
        val listenerCaptor = argumentCaptor<ViewTreeObserver.OnWindowVisibilityChangeListener>()
        verify(viewTreeObserver).addOnWindowVisibilityChangeListener(listenerCaptor.capture())

        whenever(view.windowVisibility).thenReturn(View.VISIBLE)
        listenerCaptor.value.onWindowVisibilityChanged(View.VISIBLE)

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.STARTED)
    }

    @Test
    fun `repeatWhenAttached - gains focus but invisible - CREATED`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        repeatWhenAttached()
        val listenerCaptor = argumentCaptor<ViewTreeObserver.OnWindowFocusChangeListener>()
        verify(viewTreeObserver).addOnWindowFocusChangeListener(listenerCaptor.capture())

        whenever(view.hasWindowFocus()).thenReturn(true)
        listenerCaptor.value.onWindowFocusChanged(true)

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `repeatWhenAttached - becomes visible and gains focus - RESUMED`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        repeatWhenAttached()
        val visibleCaptor = argumentCaptor<ViewTreeObserver.OnWindowVisibilityChangeListener>()
        verify(viewTreeObserver).addOnWindowVisibilityChangeListener(visibleCaptor.capture())
        val focusCaptor = argumentCaptor<ViewTreeObserver.OnWindowFocusChangeListener>()
        verify(viewTreeObserver).addOnWindowFocusChangeListener(focusCaptor.capture())

        whenever(view.windowVisibility).thenReturn(View.VISIBLE)
        visibleCaptor.value.onWindowVisibilityChanged(View.VISIBLE)
        whenever(view.hasWindowFocus()).thenReturn(true)
        focusCaptor.value.onWindowFocusChanged(true)

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun `repeatWhenAttached - view gets detached - destroys the lifecycle`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        repeatWhenAttached()

        whenever(view.isAttachedToWindow).thenReturn(false)
        attachListeners.last().onViewDetachedFromWindow(view)

        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.DESTROYED)
    }

    @Test
    fun `repeatWhenAttached - view gets reattached - recreates a lifecycle`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        repeatWhenAttached()
        whenever(view.isAttachedToWindow).thenReturn(false)
        attachListeners.last().onViewDetachedFromWindow(view)

        whenever(view.isAttachedToWindow).thenReturn(true)
        attachListeners.last().onViewAttachedToWindow(view)

        assertThat(block.invocationCount).isEqualTo(2)
        assertThat(block.invocations[0].lifecycleState).isEqualTo(Lifecycle.State.DESTROYED)
        assertThat(block.invocations[1].lifecycleState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `repeatWhenAttached - dispose attached`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        val handle = repeatWhenAttached()

        handle.dispose()

        assertThat(attachListeners).isEmpty()
        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.DESTROYED)
    }

    @Test
    fun `repeatWhenAttached - dispose never attached`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(false)
        val handle = repeatWhenAttached()

        handle.dispose()

        assertThat(attachListeners).isEmpty()
        assertThat(block.invocationCount).isEqualTo(0)
    }

    @Test
    fun `repeatWhenAttached - dispose previously attached now detached`() = runBlockingTest {
        whenever(view.isAttachedToWindow).thenReturn(true)
        val handle = repeatWhenAttached()
        attachListeners.last().onViewDetachedFromWindow(view)

        handle.dispose()

        assertThat(attachListeners).isEmpty()
        assertThat(block.invocationCount).isEqualTo(1)
        assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.DESTROYED)
    }

    private fun CoroutineScope.repeatWhenAttached(): DisposableHandle {
        return view.repeatWhenAttached(
            coroutineContext = coroutineContext,
            block = block,
        )
    }

    private class Block : suspend LifecycleOwner.(View) -> Unit {
        data class Invocation(
            val lifecycleOwner: LifecycleOwner,
        ) {
            val lifecycleState: Lifecycle.State
                get() = lifecycleOwner.lifecycle.currentState
        }

        private val _invocations = mutableListOf<Invocation>()
        val invocations: List<Invocation> = _invocations
        val invocationCount: Int
            get() = _invocations.size
        val latestLifecycleState: Lifecycle.State
            get() = _invocations.last().lifecycleState

        override suspend fun invoke(lifecycleOwner: LifecycleOwner, view: View) {
            _invocations.add(Invocation(lifecycleOwner))
        }
    }
}
