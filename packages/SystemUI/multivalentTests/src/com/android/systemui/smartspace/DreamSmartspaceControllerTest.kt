/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.smartspace

import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dreams.smartspace.DreamSmartspaceController
import com.android.systemui.plugins.BcSmartspaceConfigPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.smartspace.dagger.SmartspaceViewComponent
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class DreamSmartspaceControllerTest : SysuiTestCase() {
    @Mock private lateinit var userTracker: UserTracker

    @Mock private lateinit var userContextPrimary: Context

    @Mock private lateinit var smartspaceManager: SmartspaceManager

    @Mock private lateinit var execution: Execution

    @Mock private lateinit var uiExecutor: Executor

    @Mock private lateinit var viewComponentFactory: SmartspaceViewComponent.Factory

    @Mock private lateinit var viewComponent: SmartspaceViewComponent

    @Mock private lateinit var weatherViewComponent: SmartspaceViewComponent

    private val weatherSmartspaceView: SmartspaceView by lazy { Mockito.spy(TestView(context)) }

    @Mock private lateinit var targetFilter: SmartspaceTargetFilter

    @Mock private lateinit var plugin: BcSmartspaceDataPlugin

    @Mock private lateinit var weatherPlugin: BcSmartspaceDataPlugin

    @Mock private lateinit var precondition: SmartspacePrecondition

    private val smartspaceView: SmartspaceView by lazy { Mockito.spy(TestView(context)) }

    @Mock private lateinit var listener: BcSmartspaceDataPlugin.SmartspaceTargetListener

    @Mock private lateinit var session: SmartspaceSession

    private lateinit var controller: DreamSmartspaceController

    // TODO(b/272811280): Remove usage of real view
    private val fakeParent by lazy { FrameLayout(context) }

    /**
     * A class which implements SmartspaceView and extends View. This is mocked to provide the right
     * object inheritance and interface implementation used in DreamSmartspaceController
     */
    private class TestView(context: Context?) : View(context), SmartspaceView {
        override fun registerDataProvider(plugin: BcSmartspaceDataPlugin?) {}

        override fun registerConfigProvider(plugin: BcSmartspaceConfigPlugin?) {}

        override fun setPrimaryTextColor(color: Int) {}

        override fun setUiSurface(uiSurface: String) {}

        override fun setBgHandler(bgHandler: Handler?) {}

        override fun setDozeAmount(amount: Float) {}

        override fun setIntentStarter(intentStarter: BcSmartspaceDataPlugin.IntentStarter?) {}

        override fun setFalsingManager(falsingManager: FalsingManager?) {}

        override fun setDnd(image: Drawable?, description: String?) {}

        override fun setNextAlarm(image: Drawable?, description: String?) {}

        override fun setMediaTarget(target: SmartspaceTarget?) {}

        override fun getSelectedPage(): Int {
            return 0
        }

        override fun getCurrentCardTopPadding(): Int {
            return 0
        }
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(viewComponentFactory.create(any(), eq(plugin), any(), eq(null)))
            .thenReturn(viewComponent)
        `when`(viewComponent.getView()).thenReturn(smartspaceView)
        `when`(viewComponentFactory.create(any(), eq(weatherPlugin), any(), any()))
            .thenReturn(weatherViewComponent)
        `when`(weatherViewComponent.getView()).thenReturn(weatherSmartspaceView)
        `when`(smartspaceManager.createSmartspaceSession(any())).thenReturn(session)

        `when`(userTracker.userContext).thenReturn(userContextPrimary)
        `when`(userContextPrimary.getSystemService(SmartspaceManager::class.java))
            .thenReturn(smartspaceManager)

        controller =
            DreamSmartspaceController(
                userTracker,
                execution,
                uiExecutor,
                viewComponentFactory,
                precondition,
                Optional.of(targetFilter),
                Optional.of(plugin),
                Optional.of(weatherPlugin),
            )
    }

    /** Ensures smartspace session begins on a listener only flow. */
    @Test
    fun testConnectOnListen() {
        `when`(precondition.conditionsMet()).thenReturn(true)
        controller.addListener(listener)

        verify(smartspaceManager).createSmartspaceSession(any())

        var targetListener =
            withArgCaptor<SmartspaceSession.OnTargetsAvailableListener> {
                verify(session).addOnTargetsAvailableListener(any(), capture())
            }

        `when`(targetFilter.filterSmartspaceTarget(any())).thenReturn(true)

        var target = Mockito.mock(SmartspaceTarget::class.java)
        targetListener.onTargetsAvailable(listOf(target))

        var targets =
            withArgCaptor<List<SmartspaceTarget>> { verify(plugin).onTargetsAvailable(capture()) }

        assertThat(targets.contains(target)).isTrue()

        controller.removeListener(listener)

        verify(session).close()
    }

    /** Ensures session begins when a view is attached. */
    @Test
    fun testConnectOnViewCreate() {
        `when`(precondition.conditionsMet()).thenReturn(true)
        controller.buildAndConnectView(Mockito.mock(ViewGroup::class.java))

        val stateChangeListener =
            withArgCaptor<View.OnAttachStateChangeListener> {
                verify(viewComponentFactory).create(any(), eq(plugin), capture(), eq(null))
            }

        val mockView = Mockito.mock(TestView::class.java)
        `when`(precondition.conditionsMet()).thenReturn(true)
        stateChangeListener.onViewAttachedToWindow(mockView)

        verify(smartspaceManager).createSmartspaceSession(any())
        verify(mockView).setDozeAmount(0f)

        stateChangeListener.onViewDetachedFromWindow(mockView)

        verify(session).close()
    }

    /** Ensures session is created when weather smartspace view is created and attached. */
    @Test
    fun testConnectOnWeatherViewCreate() {
        `when`(precondition.conditionsMet()).thenReturn(true)

        val customView = Mockito.mock(TestView::class.java)
        val weatherView = controller.buildAndConnectWeatherView(fakeParent, customView)
        val weatherSmartspaceView = weatherView as SmartspaceView
        fakeParent.addView(weatherView)

        // Then weather view is created with custom view and the default weatherPlugin.getView
        // should not be called
        verify(viewComponentFactory)
            .create(eq(fakeParent), eq(weatherPlugin), any(), eq(customView))
        verify(weatherPlugin, Mockito.never()).getView(fakeParent)

        // And then session is created
        controller.stateChangeListener.onViewAttachedToWindow(weatherView)
        verify(smartspaceManager).createSmartspaceSession(any())
        verify(weatherSmartspaceView).setPrimaryTextColor(anyInt())
        verify(weatherSmartspaceView).setDozeAmount(0f)
    }

    /** Ensures weather plugin registers target listener when it is added from the controller. */
    @Test
    fun testAddListenerInController_registersListenerForWeatherPlugin() {
        val customView = Mockito.mock(TestView::class.java)
        `when`(precondition.conditionsMet()).thenReturn(true)

        // Given a session is created
        val weatherView =
            checkNotNull(controller.buildAndConnectWeatherView(fakeParent, customView))
        controller.stateChangeListener.onViewAttachedToWindow(weatherView)
        verify(smartspaceManager).createSmartspaceSession(any())

        // When a listener is added
        controller.addListenerForWeatherPlugin(listener)

        // Then the listener is registered to the weather plugin only
        verify(weatherPlugin).registerListener(listener)
        verify(plugin, Mockito.never()).registerListener(any())
    }

    /**
     * Ensures session is closed and weather plugin unregisters the notifier when weather smartspace
     * view is detached.
     */
    @Test
    fun testDisconnect_emitsEmptyListAndRemovesNotifier() {
        `when`(precondition.conditionsMet()).thenReturn(true)

        // Given a session is created
        val customView = Mockito.mock(TestView::class.java)
        val weatherView =
            checkNotNull(controller.buildAndConnectWeatherView(fakeParent, customView))
        controller.stateChangeListener.onViewAttachedToWindow(weatherView)
        verify(smartspaceManager).createSmartspaceSession(any())

        // When view is detached
        controller.stateChangeListener.onViewDetachedFromWindow(weatherView)
        // Then the session is closed
        verify(session).close()

        // And the listener receives an empty list of targets and unregisters the notifier
        verify(weatherPlugin).onTargetsAvailable(emptyList())
        verify(weatherPlugin).registerSmartspaceEventNotifier(null)
    }
}
