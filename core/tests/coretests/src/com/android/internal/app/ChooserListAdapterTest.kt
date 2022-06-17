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

package com.android.internal.app

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.chooser.ChooserTarget
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.internal.R
import com.android.internal.app.chooser.SelectableTargetInfo
import com.android.internal.app.chooser.SelectableTargetInfo.SelectableTargetInfoCommunicator
import com.android.server.testutils.any
import com.android.server.testutils.mock
import com.android.server.testutils.whenever
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
class ChooserListAdapterTest {
    private val packageManager = mock<PackageManager> {
        whenever(resolveActivity(any(), anyInt())).thenReturn(mock())
    }
    private val context = InstrumentationRegistry.getInstrumentation().getContext()
    private val resolverListController = mock<ResolverListController>()
    private val chooserListCommunicator = mock<ChooserListAdapter.ChooserListCommunicator> {
        whenever(maxRankedTargets).thenReturn(0)
    }
    private val selectableTargetInfoCommunicator =
        mock<SelectableTargetInfoCommunicator> {
            whenever(targetIntent).thenReturn(mock())
        }
    private val chooserActivityLogger = mock<ChooserActivityLogger>()

    private val testSubject = ChooserListAdapter(
        context,
        emptyList(),
        emptyArray(),
        emptyList(),
        false,
        resolverListController,
        chooserListCommunicator,
        selectableTargetInfoCommunicator,
        packageManager,
        chooserActivityLogger
    )

    @Test
    fun testDirectShareTargetLoadingIconIsStarted() {
        val view = createView()
        val viewHolder = ResolverListAdapter.ViewHolder(view)
        view.tag = viewHolder
        val targetInfo = createSelectableTargetInfo()
        val iconTask = mock<ChooserListAdapter.LoadDirectShareIconTask> {
            doNothing().whenever(this).loadIcon()
        }
        testSubject.setTestLoadDirectShareTaskProvider(
            mock {
                whenever(get()).thenReturn(iconTask)
            }
        )
        testSubject.testViewBind(view, targetInfo, 0)

        verify(iconTask, times(1)).loadIcon()
        verify(iconTask, times(1)).setViewHolder(viewHolder)
    }

    @Test
    fun testOnlyOneTaskPerTarget() {
        val view = createView()
        val viewHolderOne = ResolverListAdapter.ViewHolder(view)
        view.tag = viewHolderOne
        val targetInfo = createSelectableTargetInfo()
        val iconTaskOne = mock<ChooserListAdapter.LoadDirectShareIconTask> {
            doNothing().whenever(this).loadIcon()
        }
        val testTaskProvider = mock<ChooserListAdapter.LoadDirectShareIconTaskProvider> {
            whenever(get()).thenReturn(iconTaskOne)
        }
        testSubject.setTestLoadDirectShareTaskProvider(
            testTaskProvider
        )
        testSubject.testViewBind(view, targetInfo, 0)

        val viewHolderTwo = ResolverListAdapter.ViewHolder(view)
        view.tag = viewHolderTwo
        whenever(testTaskProvider.get()).thenReturn(mock())

        testSubject.testViewBind(view, targetInfo, 0)

        verify(iconTaskOne, times(1)).loadIcon()
        verify(iconTaskOne, times(1)).setViewHolder(viewHolderOne)
        verify(iconTaskOne, times(1)).setViewHolder(viewHolderTwo)
        verify(testTaskProvider, times(1)).get()
    }

    private fun createSelectableTargetInfo(): SelectableTargetInfo =
        SelectableTargetInfo(
            context,
            null,
            createChooserTarget(),
            1f,
            selectableTargetInfoCommunicator,
            null
        )

    private fun createChooserTarget(): ChooserTarget =
        ChooserTarget(
            "Title",
            null,
            1f,
            ComponentName("package", "package.Class"),
            Bundle()
        )

    private fun createView(): View {
        val view = FrameLayout(context)
        TextView(context).apply {
            id = R.id.text1
            view.addView(this)
        }
        TextView(context).apply {
            id = R.id.text2
            view.addView(this)
        }
        ImageView(context).apply {
            id = R.id.icon
            view.addView(this)
        }
        return view
    }
}
