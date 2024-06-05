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

package com.android.systemui.communal.ui.widgets

import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.CommunalAppWidgetHostView
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class CommunalAppWidgetHostTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var testableLooper: TestableLooper
    private lateinit var underTest: CommunalAppWidgetHost

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        underTest =
            CommunalAppWidgetHost(
                context = context,
                backgroundScope = kosmos.applicationCoroutineScope,
                hostId = 116,
                interactionHandler = mock(),
                looper = testableLooper.looper,
                logBuffer = logcatLogBuffer("CommunalAppWidgetHostTest"),
            )
    }

    @Test
    fun createViewForCommunal_returnCommunalAppWidgetView() {
        val appWidgetId = 789
        val view =
            underTest.createViewForCommunal(
                context = context,
                appWidgetId = appWidgetId,
                appWidget = null
            )
        testableLooper.processAllMessages()

        assertThat(view).isInstanceOf(CommunalAppWidgetHostView::class.java)
        assertThat(view).isNotNull()
        assertThat(view.appWidgetId).isEqualTo(appWidgetId)
    }

    @Test
    fun appWidgetIdToRemove_emit() =
        testScope.runTest {
            val appWidgetIdToRemove by collectLastValue(underTest.appWidgetIdToRemove)

            // Nothing should be emitted yet
            assertThat(appWidgetIdToRemove).isNull()

            underTest.onAppWidgetRemoved(appWidgetId = 1)
            runCurrent()

            assertThat(appWidgetIdToRemove).isEqualTo(1)

            underTest.onAppWidgetRemoved(appWidgetId = 2)
            runCurrent()

            assertThat(appWidgetIdToRemove).isEqualTo(2)
        }

    @Test
    fun observer_onHostStartListeningTriggeredWhileObserverActive() =
        testScope.runTest {
            // Observer added
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            // Verify callback triggered
            verify(observer, never()).onHostStartListening()
            underTest.startListening()
            runCurrent()
            verify(observer).onHostStartListening()

            clearInvocations(observer)

            // Observer removed
            underTest.removeObserver(observer)
            runCurrent()

            // Verify callback not triggered
            underTest.startListening()
            runCurrent()
            verify(observer, never()).onHostStartListening()
        }

    @Test
    fun observer_onHostStopListeningTriggeredWhileObserverActive() =
        testScope.runTest {
            // Observer added
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            // Verify callback triggered
            verify(observer, never()).onHostStopListening()
            underTest.stopListening()
            runCurrent()
            verify(observer).onHostStopListening()

            clearInvocations(observer)

            // Observer removed
            underTest.removeObserver(observer)
            runCurrent()

            // Verify callback not triggered
            underTest.stopListening()
            runCurrent()
            verify(observer, never()).onHostStopListening()
        }

    @Test
    fun observer_onAllocateAppWidgetIdTriggeredWhileObserverActive() =
        testScope.runTest {
            // Observer added
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            // Verify callback triggered
            verify(observer, never()).onAllocateAppWidgetId(any())
            val id = underTest.allocateAppWidgetId()
            runCurrent()
            verify(observer).onAllocateAppWidgetId(eq(id))

            clearInvocations(observer)

            // Observer removed
            underTest.removeObserver(observer)
            runCurrent()

            // Verify callback not triggered
            underTest.allocateAppWidgetId()
            runCurrent()
            verify(observer, never()).onAllocateAppWidgetId(any())
        }

    @Test
    fun observer_onDeleteAppWidgetIdTriggeredWhileObserverActive() =
        testScope.runTest {
            // Observer added
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            // Verify callback triggered
            verify(observer, never()).onDeleteAppWidgetId(any())
            underTest.deleteAppWidgetId(1)
            runCurrent()
            verify(observer).onDeleteAppWidgetId(eq(1))

            clearInvocations(observer)

            // Observer removed
            underTest.removeObserver(observer)
            runCurrent()

            // Verify callback not triggered
            underTest.deleteAppWidgetId(2)
            runCurrent()
            verify(observer, never()).onDeleteAppWidgetId(any())
        }

    @Test
    fun observer_multipleObservers() =
        testScope.runTest {
            // Set up two observers
            val observer1 = mock<CommunalAppWidgetHost.Observer>()
            val observer2 = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer1)
            underTest.addObserver(observer2)
            runCurrent()

            // Verify both observers triggered
            verify(observer1, never()).onHostStartListening()
            verify(observer2, never()).onHostStartListening()
            underTest.startListening()
            runCurrent()
            verify(observer1).onHostStartListening()
            verify(observer2).onHostStartListening()

            // Observer 1 removed
            underTest.removeObserver(observer1)
            runCurrent()

            // Verify only observer 2 is triggered
            underTest.stopListening()
            runCurrent()
            verify(observer2).onHostStopListening()
            verify(observer1, never()).onHostStopListening()
        }
}
