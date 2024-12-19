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

package com.android.systemui.communal.ui.viewmodel

import android.appwidget.AppWidgetHost.AppWidgetHostListener
import android.appwidget.AppWidgetHostView
import android.platform.test.flag.junit.FlagsParameterization
import android.util.SizeF
import android.widget.RemoteViews
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_SECONDARY_USER_WIDGET_HOST
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.shared.model.fakeGlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.AppWidgetHostListenerDelegate
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.GlanceableHubWidgetManager
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.backgroundCoroutineContext
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.testKosmos
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class CommunalAppWidgetViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    val kosmos = testKosmos()

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val Kosmos.listenerDelegateFactory by
        Kosmos.Fixture {
            AppWidgetHostListenerDelegate.Factory { tag, listener ->
                AppWidgetHostListenerDelegate(applicationCoroutineScope, tag, listener)
            }
        }

    private val Kosmos.appWidgetHost by
        Kosmos.Fixture {
            mock<CommunalAppWidgetHost> {
                on { setListener(any(), any()) } doAnswer
                    { invocation ->
                        val callback = invocation.arguments[1] as AppWidgetHostListener
                        callback.updateAppWidget(mock<RemoteViews>())
                    }
            }
        }

    private val Kosmos.glanceableHubWidgetManager by
        Kosmos.Fixture {
            mock<GlanceableHubWidgetManager> {
                on { setAppWidgetHostListener(any(), any()) } doAnswer
                    { invocation ->
                        val callback = invocation.arguments[1] as AppWidgetHostListener
                        callback.updateAppWidget(mock<RemoteViews>())
                    }
            }
        }

    private val Kosmos.underTest by
        Kosmos.Fixture {
            CommunalAppWidgetViewModel(
                    backgroundCoroutineContext,
                    { appWidgetHost },
                    listenerDelegateFactory,
                    { glanceableHubWidgetManager },
                    fakeGlanceableHubMultiUserHelper,
                )
                .apply { activateIn(testScope) }
        }

    @Test
    fun setListener() =
        kosmos.runTest {
            val listener = mock<AppWidgetHostListener>()

            underTest.setListener(123, listener)
            runAll()

            verify(listener).updateAppWidget(any())
        }

    @Test
    fun setListener_HSUM() =
        kosmos.runTest {
            fakeGlanceableHubMultiUserHelper.setIsInHeadlessSystemUser(true)
            val listener = mock<AppWidgetHostListener>()

            underTest.setListener(123, listener)
            runAll()

            verify(listener).updateAppWidget(any())
        }

    @Test
    fun updateSize() =
        kosmos.runTest {
            val view = mock<AppWidgetHostView>()
            val size = SizeF(/* width= */ 100f, /* height= */ 200f)

            underTest.updateSize(size, view)
            runAll()

            verify(view)
                .updateAppWidgetSize(
                    /* newOptions = */ any(),
                    /* minWidth = */ eq(100),
                    /* minHeight = */ eq(200),
                    /* maxWidth = */ eq(100),
                    /* maxHeight = */ eq(200),
                    /* ignorePadding = */ eq(true),
                )
        }

    private fun Kosmos.runAll() {
        runCurrent()
        fakeExecutor.runAllReady()
    }

    private companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_SECONDARY_USER_WIDGET_HOST)
        }
    }
}
