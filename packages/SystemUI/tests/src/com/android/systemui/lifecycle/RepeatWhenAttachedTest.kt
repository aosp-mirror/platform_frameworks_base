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

import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.Assert
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.argumentCaptor

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class RepeatWhenAttachedTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    @JvmField @Rule val instantTaskExecutor = InstantTaskExecutorRule()

    @Mock private lateinit var view: View
    @Mock private lateinit var viewTreeObserver: ViewTreeObserver

    private lateinit var block: Block
    private lateinit var attachListeners: MutableList<View.OnAttachStateChangeListener>
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)
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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test(expected = IllegalStateException::class)
    fun repeatWhenAttached_enforcesMainThread() =
        testScope.runTest {
            Assert.setTestThread(null)

            repeatWhenAttached()
        }

    @Test(expected = IllegalStateException::class)
    fun repeatWhenAttachedToWindow_enforcesMainThread() =
        testScope.runTest {
            Assert.setTestThread(null)

            view.repeatWhenAttachedToWindow {}
        }

    @Test(expected = IllegalStateException::class)
    fun repeatWhenAttached_disposeEnforcesMainThread() =
        testScope.runTest {
            val disposableHandle = repeatWhenAttached()
            Assert.setTestThread(null)

            disposableHandle.dispose()
        }

    @Test
    fun repeatWhenAttached_viewStartsDetached_runsBlockWhenAttached() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(false)
            repeatWhenAttached()
            assertThat(block.invocationCount).isEqualTo(0)

            whenever(view.isAttachedToWindow).thenReturn(true)
            attachListeners.last().onViewAttachedToWindow(view)

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.CREATED)
        }

    @Test
    fun repeatWhenAttachedToWindow_viewAlreadyAttached_immediatelyRunsBlock() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)

            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenAttachedToWindow { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)
        }

    @Test
    fun repeatWhenAttachedToWindow_viewStartsDetached_runsBlockWhenAttached() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(false)
            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenAttachedToWindow { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isNotEqualTo(true)

            whenever(view.isAttachedToWindow).thenReturn(true)
            attachListeners.last().onViewAttachedToWindow(view)
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)
        }

    @Test
    fun repeatWhenAttachedToWindow_viewGetsDetached_cancelsBlock() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenAttachedToWindow { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)

            whenever(view.isAttachedToWindow).thenReturn(false)
            attachListeners.last().onViewDetachedFromWindow(view)
            runCurrent()

            assertThat(innerJob?.isActive).isNotEqualTo(true)
        }

    @Test
    fun repeatWhenAttached_viewAlreadyAttached_immediatelyRunsBlock() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)

            repeatWhenAttached()

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.CREATED)
        }

    @Test
    fun repeatWhenAttached_startsVisibleWithoutFocus_STARTED() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.windowVisibility).thenReturn(View.VISIBLE)

            repeatWhenAttached()

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.STARTED)
        }

    @Test
    fun repeatWhenWindowIsVisible_startsAlreadyVisible_immediatelyRunsBlock() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.windowVisibility).thenReturn(View.VISIBLE)

            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenWindowIsVisible { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)
        }

    @Test
    fun repeatWhenWindowIsVisible_startsInvisible_runsBlockWhenVisible() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.windowVisibility).thenReturn(View.INVISIBLE)

            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenWindowIsVisible { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isNotEqualTo(true)

            whenever(view.windowVisibility).thenReturn(View.VISIBLE)
            argCaptor { verify(viewTreeObserver).addOnWindowVisibilityChangeListener(capture()) }
                .forEach { it.onWindowVisibilityChanged(View.VISIBLE) }
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)
        }

    @Test
    fun repeatWhenWindowIsVisible_becomesInvisible_cancelsBlock() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.windowVisibility).thenReturn(View.VISIBLE)

            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenWindowIsVisible { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)

            whenever(view.windowVisibility).thenReturn(View.INVISIBLE)
            argCaptor { verify(viewTreeObserver).addOnWindowVisibilityChangeListener(capture()) }
                .forEach { it.onWindowVisibilityChanged(View.INVISIBLE) }
            runCurrent()

            assertThat(innerJob?.isActive).isNotEqualTo(true)
        }

    @Test
    fun repeatWhenAttached_startsWithFocusButInvisible_CREATED() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.hasWindowFocus()).thenReturn(true)

            repeatWhenAttached()

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.CREATED)
        }

    @Test
    fun repeatWhenAttached_startsVisibleAndWithFocus_RESUMED() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.windowVisibility).thenReturn(View.VISIBLE)
            whenever(view.hasWindowFocus()).thenReturn(true)

            repeatWhenAttached()

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.RESUMED)
        }

    @Test
    fun repeatWhenWindowHasFocus_startsWithFocus_immediatelyRunsBlock() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.windowVisibility).thenReturn(View.VISIBLE)
            whenever(view.hasWindowFocus()).thenReturn(true)

            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenWindowHasFocus { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)
        }

    @Test
    fun repeatWhenWindowHasFocus_startsWithoutFocus_runsBlockWhenFocused() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.windowVisibility).thenReturn(View.VISIBLE)
            whenever(view.hasWindowFocus()).thenReturn(false)

            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenWindowHasFocus { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isNotEqualTo(true)

            whenever(view.hasWindowFocus()).thenReturn(true)

            argCaptor { verify(viewTreeObserver).addOnWindowFocusChangeListener(capture()) }
                .forEach { it.onWindowFocusChanged(true) }
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)
        }

    @Test
    fun repeatWhenWindowHasFocus_losesFocus_cancelsBlock() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            whenever(view.windowVisibility).thenReturn(View.VISIBLE)
            whenever(view.hasWindowFocus()).thenReturn(true)

            var innerJob: Job? = null
            backgroundScope.launch {
                view.repeatWhenWindowHasFocus { innerJob = launch { awaitCancellation() } }
            }
            runCurrent()

            assertThat(innerJob?.isActive).isEqualTo(true)

            whenever(view.hasWindowFocus()).thenReturn(false)
            argCaptor { verify(viewTreeObserver).addOnWindowFocusChangeListener(capture()) }
                .forEach { it.onWindowFocusChanged(false) }
            runCurrent()

            assertThat(innerJob?.isActive).isNotEqualTo(true)
        }

    @Test
    fun repeatWhenAttached_becomesVisibleWithoutFocus_STARTED() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            repeatWhenAttached()
            val listenerCaptor = argumentCaptor<ViewTreeObserver.OnWindowVisibilityChangeListener>()
            verify(viewTreeObserver).addOnWindowVisibilityChangeListener(listenerCaptor.capture())

            whenever(view.windowVisibility).thenReturn(View.VISIBLE)
            listenerCaptor.lastValue.onWindowVisibilityChanged(View.VISIBLE)

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.STARTED)
        }

    @Test
    fun repeatWhenAttached_gainsFocusButInvisible_CREATED() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            repeatWhenAttached()
            val listenerCaptor = argumentCaptor<ViewTreeObserver.OnWindowFocusChangeListener>()
            verify(viewTreeObserver).addOnWindowFocusChangeListener(listenerCaptor.capture())

            whenever(view.hasWindowFocus()).thenReturn(true)
            listenerCaptor.lastValue.onWindowFocusChanged(true)

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.CREATED)
        }

    @Test
    fun repeatWhenAttached_becomesVisibleAndGainsFocus_RESUMED() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            repeatWhenAttached()
            val visibleCaptor = argumentCaptor<ViewTreeObserver.OnWindowVisibilityChangeListener>()
            verify(viewTreeObserver).addOnWindowVisibilityChangeListener(visibleCaptor.capture())
            val focusCaptor = argumentCaptor<ViewTreeObserver.OnWindowFocusChangeListener>()
            verify(viewTreeObserver).addOnWindowFocusChangeListener(focusCaptor.capture())

            whenever(view.windowVisibility).thenReturn(View.VISIBLE)
            visibleCaptor.lastValue.onWindowVisibilityChanged(View.VISIBLE)
            whenever(view.hasWindowFocus()).thenReturn(true)
            focusCaptor.lastValue.onWindowFocusChanged(true)

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.RESUMED)
        }

    @Test
    fun repeatWhenAttached_viewGetsDetached_destroysTheLifecycle() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            repeatWhenAttached()

            whenever(view.isAttachedToWindow).thenReturn(false)
            attachListeners.last().onViewDetachedFromWindow(view)

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.DESTROYED)
        }

    @Test
    fun repeatWhenAttached_viewGetsReattached_recreatesAlifecycle() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            repeatWhenAttached()
            whenever(view.isAttachedToWindow).thenReturn(false)
            attachListeners.last().onViewDetachedFromWindow(view)

            whenever(view.isAttachedToWindow).thenReturn(true)
            attachListeners.last().onViewAttachedToWindow(view)

            runCurrent()
            assertThat(block.invocationCount).isEqualTo(2)
            assertThat(block.invocations[0].lifecycleState).isEqualTo(Lifecycle.State.DESTROYED)
            assertThat(block.invocations[1].lifecycleState).isEqualTo(Lifecycle.State.CREATED)
        }

    @Test
    fun repeatWhenAttached_disposeAttached() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            val handle = repeatWhenAttached()

            handle.dispose()

            runCurrent()
            assertThat(attachListeners).isEmpty()
            assertThat(block.invocationCount).isEqualTo(1)
            assertThat(block.latestLifecycleState).isEqualTo(Lifecycle.State.DESTROYED)
        }

    @Test
    fun repeatWhenAttached_disposeNeverAttached() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(false)
            val handle = repeatWhenAttached()

            handle.dispose()

            assertThat(attachListeners).isEmpty()
            assertThat(block.invocationCount).isEqualTo(0)
        }

    @Test
    fun repeatWhenAttached_disposePreviouslyAttachedNowDetached() =
        testScope.runTest {
            whenever(view.isAttachedToWindow).thenReturn(true)
            val handle = repeatWhenAttached()
            attachListeners.last().onViewDetachedFromWindow(view)

            handle.dispose()

            runCurrent()
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

private inline fun <reified T : Any> argCaptor(block: KArgumentCaptor<T>.() -> Unit) =
    argumentCaptor<T>().apply { block() }

private inline fun <reified T : Any> KArgumentCaptor<T>.forEach(block: (T) -> Unit): Unit =
    allValues.forEach(block)
