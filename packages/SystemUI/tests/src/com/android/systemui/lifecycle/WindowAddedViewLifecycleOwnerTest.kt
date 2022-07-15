package com.android.systemui.lifecycle

import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class WindowAddedViewLifecycleOwnerTest : SysuiTestCase() {

    @Mock lateinit var view: View
    @Mock lateinit var viewTreeObserver: ViewTreeObserver

    private lateinit var underTest: WindowAddedViewLifecycleOwner

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(view.viewTreeObserver).thenReturn(viewTreeObserver)
        whenever(view.isAttachedToWindow).thenReturn(false)
        whenever(view.windowVisibility).thenReturn(View.INVISIBLE)
        whenever(view.hasWindowFocus()).thenReturn(false)

        underTest = WindowAddedViewLifecycleOwner(view) { LifecycleRegistry.createUnsafe(it) }
    }

    @Test
    fun `detached - invisible - does not have focus -- INITIALIZED`() {
        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.INITIALIZED)
    }

    @Test
    fun `detached - invisible - has focus -- INITIALIZED`() {
        whenever(view.hasWindowFocus()).thenReturn(true)
        val captor = argumentCaptor<ViewTreeObserver.OnWindowFocusChangeListener>()
        verify(viewTreeObserver).addOnWindowFocusChangeListener(capture(captor))
        captor.value.onWindowFocusChanged(true)

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.INITIALIZED)
    }

    @Test
    fun `detached - visible - does not have focus -- INITIALIZED`() {
        whenever(view.windowVisibility).thenReturn(View.VISIBLE)
        val captor = argumentCaptor<ViewTreeObserver.OnWindowVisibilityChangeListener>()
        verify(viewTreeObserver).addOnWindowVisibilityChangeListener(capture(captor))
        captor.value.onWindowVisibilityChanged(View.VISIBLE)

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.INITIALIZED)
    }

    @Test
    fun `detached - visible - has focus -- INITIALIZED`() {
        whenever(view.hasWindowFocus()).thenReturn(true)
        val focusCaptor = argumentCaptor<ViewTreeObserver.OnWindowFocusChangeListener>()
        verify(viewTreeObserver).addOnWindowFocusChangeListener(capture(focusCaptor))
        focusCaptor.value.onWindowFocusChanged(true)

        whenever(view.windowVisibility).thenReturn(View.VISIBLE)
        val visibilityCaptor = argumentCaptor<ViewTreeObserver.OnWindowVisibilityChangeListener>()
        verify(viewTreeObserver).addOnWindowVisibilityChangeListener(capture(visibilityCaptor))
        visibilityCaptor.value.onWindowVisibilityChanged(View.VISIBLE)

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.INITIALIZED)
    }

    @Test
    fun `attached - invisible - does not have focus -- CREATED`() {
        whenever(view.isAttachedToWindow).thenReturn(true)
        val captor = argumentCaptor<ViewTreeObserver.OnWindowAttachListener>()
        verify(viewTreeObserver).addOnWindowAttachListener(capture(captor))
        captor.value.onWindowAttached()

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `attached - invisible - has focus -- CREATED`() {
        whenever(view.isAttachedToWindow).thenReturn(true)
        val attachCaptor = argumentCaptor<ViewTreeObserver.OnWindowAttachListener>()
        verify(viewTreeObserver).addOnWindowAttachListener(capture(attachCaptor))
        attachCaptor.value.onWindowAttached()

        whenever(view.hasWindowFocus()).thenReturn(true)
        val focusCaptor = argumentCaptor<ViewTreeObserver.OnWindowFocusChangeListener>()
        verify(viewTreeObserver).addOnWindowFocusChangeListener(capture(focusCaptor))
        focusCaptor.value.onWindowFocusChanged(true)

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `attached - visible - does not have focus -- STARTED`() {
        whenever(view.isAttachedToWindow).thenReturn(true)
        val attachCaptor = argumentCaptor<ViewTreeObserver.OnWindowAttachListener>()
        verify(viewTreeObserver).addOnWindowAttachListener(capture(attachCaptor))
        attachCaptor.value.onWindowAttached()

        whenever(view.windowVisibility).thenReturn(View.VISIBLE)
        val visibilityCaptor = argumentCaptor<ViewTreeObserver.OnWindowVisibilityChangeListener>()
        verify(viewTreeObserver).addOnWindowVisibilityChangeListener(capture(visibilityCaptor))
        visibilityCaptor.value.onWindowVisibilityChanged(View.VISIBLE)

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
    }

    @Test
    fun `attached - visible - has focus -- RESUMED`() {
        whenever(view.isAttachedToWindow).thenReturn(true)
        val attachCaptor = argumentCaptor<ViewTreeObserver.OnWindowAttachListener>()
        verify(viewTreeObserver).addOnWindowAttachListener(capture(attachCaptor))
        attachCaptor.value.onWindowAttached()

        whenever(view.hasWindowFocus()).thenReturn(true)
        val focusCaptor = argumentCaptor<ViewTreeObserver.OnWindowFocusChangeListener>()
        verify(viewTreeObserver).addOnWindowFocusChangeListener(capture(focusCaptor))
        focusCaptor.value.onWindowFocusChanged(true)

        whenever(view.windowVisibility).thenReturn(View.VISIBLE)
        val visibilityCaptor = argumentCaptor<ViewTreeObserver.OnWindowVisibilityChangeListener>()
        verify(viewTreeObserver).addOnWindowVisibilityChangeListener(capture(visibilityCaptor))
        visibilityCaptor.value.onWindowVisibilityChanged(View.VISIBLE)

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun dispose() {
        underTest.dispose()

        verify(viewTreeObserver).removeOnWindowAttachListener(any())
        verify(viewTreeObserver).removeOnWindowVisibilityChangeListener(any())
        verify(viewTreeObserver).removeOnWindowFocusChangeListener(any())
    }
}
