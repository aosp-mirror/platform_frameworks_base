/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.smartspace.CommunalSmartspaceController
import com.android.systemui.plugins.BcSmartspaceConfigPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.mockito.any
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

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class CommunalSmartspaceControllerTest : SysuiTestCase() {
    @Mock private lateinit var smartspaceManager: SmartspaceManager

    @Mock private lateinit var execution: Execution

    @Mock private lateinit var uiExecutor: Executor

    @Mock private lateinit var targetFilter: SmartspaceTargetFilter

    @Mock private lateinit var plugin: BcSmartspaceDataPlugin

    @Mock private lateinit var precondition: SmartspacePrecondition

    @Mock private lateinit var listener: BcSmartspaceDataPlugin.SmartspaceTargetListener

    @Mock private lateinit var session: SmartspaceSession

    private lateinit var controller: CommunalSmartspaceController

    // TODO(b/272811280): Remove usage of real view
    private val fakeParent by lazy {
        FrameLayout(context)
    }

    /**
     * A class which implements SmartspaceView and extends View. This is mocked to provide the right
     * object inheritance and interface implementation used in CommunalSmartspaceController
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
        `when`(smartspaceManager.createSmartspaceSession(any())).thenReturn(session)

        controller =
            CommunalSmartspaceController(
                context,
                smartspaceManager,
                execution,
                uiExecutor,
                precondition,
                Optional.of(targetFilter),
                Optional.of(plugin)
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

    /**
     * Ensures session is closed and weather plugin unregisters the notifier when weather smartspace
     * view is detached.
     */
    @Test
    fun testDisconnect_emitsEmptyListAndRemovesNotifier() {
        `when`(precondition.conditionsMet()).thenReturn(true)
        controller.addListener(listener)

        verify(smartspaceManager).createSmartspaceSession(any())

        controller.removeListener(listener)

        verify(session).close()

        // And the listener receives an empty list of targets and unregisters the notifier
        verify(plugin).onTargetsAvailable(emptyList())
        verify(plugin).registerSmartspaceEventNotifier(null)
    }
}
