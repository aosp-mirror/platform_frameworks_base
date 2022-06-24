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
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dreams.smartspace.DreamSmartspaceController
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.FalsingManager
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
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.Spy

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class DreamSmartspaceControllerTest : SysuiTestCase() {
    @Mock
    private lateinit var smartspaceManager: SmartspaceManager

    @Mock
    private lateinit var execution: Execution

    @Mock
    private lateinit var uiExecutor: Executor

    @Mock
    private lateinit var viewComponentFactory: SmartspaceViewComponent.Factory

    @Mock
    private lateinit var viewComponent: SmartspaceViewComponent

    @Mock
    private lateinit var targetFilter: SmartspaceTargetFilter

    @Mock
    private lateinit var plugin: BcSmartspaceDataPlugin

    @Mock
    private lateinit var precondition: SmartspacePrecondition

    @Spy
    private var smartspaceView: SmartspaceView = TestView(context)

    @Mock
    private lateinit var listener: BcSmartspaceDataPlugin.SmartspaceTargetListener

    @Mock
    private lateinit var session: SmartspaceSession

    private lateinit var controller: DreamSmartspaceController

    /**
     * A class which implements SmartspaceView and extends View. This is mocked to provide the right
     * object inheritance and interface implementation used in DreamSmartspaceController
     */
    private class TestView(context: Context?) : View(context), SmartspaceView {
        override fun registerDataProvider(plugin: BcSmartspaceDataPlugin?) {}

        override fun setPrimaryTextColor(color: Int) {}

        override fun setIsDreaming(isDreaming: Boolean) {}

        override fun setDozeAmount(amount: Float) {}

        override fun setIntentStarter(intentStarter: BcSmartspaceDataPlugin.IntentStarter?) {}

        override fun setFalsingManager(falsingManager: FalsingManager?) {}

        override fun setDnd(image: Drawable?, description: String?) {}

        override fun setNextAlarm(image: Drawable?, description: String?) {}

        override fun setMediaTarget(target: SmartspaceTarget?) {}

        override fun getSelectedPage(): Int { return 0; }

        override fun getCurrentCardTopPadding(): Int { return 0; }
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(viewComponentFactory.create(any(), eq(plugin), any()))
                .thenReturn(viewComponent)
        `when`(viewComponent.getView()).thenReturn(smartspaceView)
        `when`(smartspaceManager.createSmartspaceSession(any())).thenReturn(session)

        controller = DreamSmartspaceController(context, smartspaceManager, execution, uiExecutor,
                viewComponentFactory, precondition, Optional.of(targetFilter), Optional.of(plugin))
    }

    /**
     * Ensures smartspace session begins on a listener only flow.
     */
    @Test
    fun testConnectOnListen() {
        `when`(precondition.conditionsMet()).thenReturn(true)
        controller.addListener(listener)

        verify(smartspaceManager).createSmartspaceSession(any())

        var targetListener = withArgCaptor<SmartspaceSession.OnTargetsAvailableListener> {
            verify(session).addOnTargetsAvailableListener(any(), capture())
        }

        `when`(targetFilter.filterSmartspaceTarget(any())).thenReturn(true)

        var target = Mockito.mock(SmartspaceTarget::class.java)
        targetListener.onTargetsAvailable(listOf(target))

        var targets = withArgCaptor<List<SmartspaceTarget>> {
            verify(plugin).onTargetsAvailable(capture())
        }

        assertThat(targets.contains(target)).isTrue()

        controller.removeListener(listener)

        verify(session).close()
    }

    /**
     * Ensures session begins when a view is attached.
     */
    @Test
    fun testConnectOnViewCreate() {
        `when`(precondition.conditionsMet()).thenReturn(true)
        controller.buildAndConnectView(Mockito.mock(ViewGroup::class.java))

        var stateChangeListener = withArgCaptor<View.OnAttachStateChangeListener> {
            verify(viewComponentFactory).create(any(), eq(plugin), capture())
        }

        var mockView = Mockito.mock(TestView::class.java)
        `when`(precondition.conditionsMet()).thenReturn(true)
        stateChangeListener.onViewAttachedToWindow(mockView)

        verify(smartspaceManager).createSmartspaceSession(any())
        verify(mockView).setDozeAmount(0f)

        stateChangeListener.onViewDetachedFromWindow(mockView)

        verify(session).close()
    }
}
