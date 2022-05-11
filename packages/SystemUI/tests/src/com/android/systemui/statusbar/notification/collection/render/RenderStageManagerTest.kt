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

package com.android.systemui.statusbar.notification.collection.render

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderEntryListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderGroupListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderListListener
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@SmallTest
class RenderStageManagerTest : SysuiTestCase() {

    @Mock private lateinit var shadeListBuilder: ShadeListBuilder
    @Mock private lateinit var onAfterRenderListListener: OnAfterRenderListListener
    @Mock private lateinit var onAfterRenderGroupListener: OnAfterRenderGroupListener
    @Mock private lateinit var onAfterRenderEntryListener: OnAfterRenderEntryListener

    private lateinit var onRenderListListener: ShadeListBuilder.OnRenderListListener
    private lateinit var renderStageManager: RenderStageManager
    private val spyViewRenderer = spy(FakeNotifViewRenderer())

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        renderStageManager = RenderStageManager()
        renderStageManager.attach(shadeListBuilder)
        onRenderListListener = withArgCaptor {
            verify(shadeListBuilder).setOnRenderListListener(capture())
        }
    }

    private fun setUpRenderer() {
        renderStageManager.setViewRenderer(spyViewRenderer)
    }

    private fun setUpListeners() {
        renderStageManager.addOnAfterRenderListListener(onAfterRenderListListener)
        renderStageManager.addOnAfterRenderGroupListener(onAfterRenderGroupListener)
        renderStageManager.addOnAfterRenderEntryListener(onAfterRenderEntryListener)
    }

    @Test
    fun testNoCallbacksWithoutRenderer() {
        // GIVEN listeners but no renderer
        setUpListeners()

        // WHEN a shade list is built
        onRenderListListener.onRenderList(listWith2Groups8Entries())

        // VERIFY that no listeners are called
        verifyNoMoreInteractions(
            onAfterRenderListListener,
            onAfterRenderGroupListener,
            onAfterRenderEntryListener
        )
    }

    @Test
    fun testDoesNotQueryControllerIfNoListeners() {
        // GIVEN a renderer but no listeners
        setUpRenderer()

        // WHEN a shade list is built
        onRenderListListener.onRenderList(listWith2Groups8Entries())

        // VERIFY that the renderer is not queried for group or row controllers
        inOrder(spyViewRenderer).apply {
            verify(spyViewRenderer, times(1)).onRenderList(any())
            verify(spyViewRenderer, times(1)).getStackController()
            verify(spyViewRenderer, never()).getGroupController(any())
            verify(spyViewRenderer, never()).getRowController(any())
            verify(spyViewRenderer, times(1)).onDispatchComplete()
            verifyNoMoreInteractions(spyViewRenderer)
        }
    }

    @Test
    fun testDoesQueryControllerIfListeners() {
        // GIVEN a renderer and listeners
        setUpRenderer()
        setUpListeners()

        // WHEN a shade list is built
        onRenderListListener.onRenderList(listWith2Groups8Entries())

        // VERIFY that the renderer is queried once per group/entry
        inOrder(spyViewRenderer).apply {
            verify(spyViewRenderer, times(1)).onRenderList(any())
            verify(spyViewRenderer, times(1)).getStackController()
            verify(spyViewRenderer, times(2)).getGroupController(any())
            verify(spyViewRenderer, times(8)).getRowController(any())
            verify(spyViewRenderer, times(1)).onDispatchComplete()
            verifyNoMoreInteractions(spyViewRenderer)
        }
    }

    @Test
    fun testDoesNotQueryControllerTwice() {
        // GIVEN a renderer and multiple distinct listeners
        setUpRenderer()
        setUpListeners()
        renderStageManager.addOnAfterRenderListListener(mock())
        renderStageManager.addOnAfterRenderGroupListener(mock())
        renderStageManager.addOnAfterRenderEntryListener(mock())

        // WHEN a shade list is built
        onRenderListListener.onRenderList(listWith2Groups8Entries())

        // VERIFY that the renderer is queried once per group/entry
        inOrder(spyViewRenderer).apply {
            verify(spyViewRenderer, times(1)).onRenderList(any())
            verify(spyViewRenderer, times(1)).getStackController()
            verify(spyViewRenderer, times(2)).getGroupController(any())
            verify(spyViewRenderer, times(8)).getRowController(any())
            verify(spyViewRenderer, times(1)).onDispatchComplete()
            verifyNoMoreInteractions(spyViewRenderer)
        }
    }

    @Test
    fun testDoesCallListenerWithEachGroupAndEntry() {
        // GIVEN a renderer and multiple distinct listeners
        setUpRenderer()
        setUpListeners()

        // WHEN a shade list is built
        onRenderListListener.onRenderList(listWith2Groups8Entries())

        // VERIFY that the listeners are invoked once per group and once per entry
        verify(onAfterRenderListListener, times(1)).onAfterRenderList(any(), any())
        verify(onAfterRenderGroupListener, times(2)).onAfterRenderGroup(any(), any())
        verify(onAfterRenderEntryListener, times(8)).onAfterRenderEntry(any(), any())
        verifyNoMoreInteractions(
            onAfterRenderListListener,
            onAfterRenderGroupListener,
            onAfterRenderEntryListener
        )
    }

    @Test
    fun testDoesNotCallGroupAndEntryListenersIfTheListIsEmpty() {
        // GIVEN a renderer and multiple distinct listeners
        setUpRenderer()
        setUpListeners()

        // WHEN a shade list is built empty
        onRenderListListener.onRenderList(listOf())

        // VERIFY that the stack listener is invoked once but other listeners are not
        verify(onAfterRenderListListener, times(1)).onAfterRenderList(any(), any())
        verify(onAfterRenderGroupListener, never()).onAfterRenderGroup(any(), any())
        verify(onAfterRenderEntryListener, never()).onAfterRenderEntry(any(), any())
        verifyNoMoreInteractions(
            onAfterRenderListListener,
            onAfterRenderGroupListener,
            onAfterRenderEntryListener
        )
    }

    private fun listWith2Groups8Entries() = listOf(
        group(
            notif(1),
            notif(2),
            notif(3)
        ),
        notif(4),
        group(
            notif(5),
            notif(6),
            notif(7)
        ),
        notif(8)
    )

    private class FakeNotifViewRenderer : NotifViewRenderer {
        override fun onRenderList(notifList: List<ListEntry>) {}
        override fun getStackController(): NotifStackController = mock()
        override fun getGroupController(group: GroupEntry): NotifGroupController = mock()
        override fun getRowController(entry: NotificationEntry): NotifRowController = mock()
        override fun onDispatchComplete() {}
    }

    private fun notif(id: Int): NotificationEntry = NotificationEntryBuilder().setId(id).build()

    private fun group(summary: NotificationEntry, vararg children: NotificationEntry): GroupEntry =
        GroupEntryBuilder().setSummary(summary).setChildren(children.toList()).build()
}
