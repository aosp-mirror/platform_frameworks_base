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
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.UserHandle
import android.service.chooser.ChooserTarget
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.internal.R
import com.android.internal.app.ChooserListAdapter.LoadDirectShareIconTask
import com.android.internal.app.chooser.DisplayResolveInfo
import com.android.internal.app.chooser.SelectableTargetInfo
import com.android.internal.app.chooser.SelectableTargetInfo.SelectableTargetInfoCommunicator
import com.android.internal.app.chooser.TargetInfo
import com.android.server.testutils.any
import com.android.server.testutils.mock
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
class ChooserListAdapterTest {
    private val packageManager =
        mock<PackageManager> { whenever(resolveActivity(any(), anyInt())).thenReturn(mock()) }
    private val context = InstrumentationRegistry.getInstrumentation().getContext()
    private val resolverListController = mock<ResolverListController>()
    private val chooserListCommunicator =
        mock<ChooserListAdapter.ChooserListCommunicator> {
            whenever(maxRankedTargets).thenReturn(0)
        }
    private val selectableTargetInfoCommunicator =
        mock<SelectableTargetInfoCommunicator> { whenever(targetIntent).thenReturn(mock()) }
    private val chooserActivityLogger = mock<ChooserActivityLogger>()

    @Before
    fun setUp() {
        whenever(resolverListController.userHandle).thenReturn(UserHandle.CURRENT)
    }

    private fun createChooserListAdapter(
        taskProvider: (SelectableTargetInfo?) -> LoadDirectShareIconTask = createTaskProvider()
    ) =
        ChooserListAdapterOverride(
            context,
            emptyList(),
            emptyArray(),
            emptyList(),
            false,
            resolverListController,
            chooserListCommunicator,
            selectableTargetInfoCommunicator,
            packageManager,
            chooserActivityLogger,
            null,
            taskProvider,
        )

    @Test
    fun testDirectShareTargetLoadingIconIsStarted() {
        val view = createView()
        val viewHolder = ResolverListAdapter.ViewHolder(view)
        view.tag = viewHolder
        val targetInfo = createSelectableTargetInfo()
        val iconTask = mock<LoadDirectShareIconTask>()
        val testSubject = createChooserListAdapter { iconTask }
        testSubject.testViewBind(view, targetInfo, 0)

        verify(iconTask, times(1)).loadIcon()
    }

    @Test
    fun testOnlyOneTaskPerTarget() {
        val view = createView()
        val viewHolderOne = ResolverListAdapter.ViewHolder(view)
        view.tag = viewHolderOne
        val targetInfo = createSelectableTargetInfo()
        val iconTaskOne = mock<LoadDirectShareIconTask>()
        val testTaskProvider =
            mock<() -> LoadDirectShareIconTask> { whenever(invoke()).thenReturn(iconTaskOne) }
        val testSubject = createChooserListAdapter { testTaskProvider.invoke() }
        testSubject.testViewBind(view, targetInfo, 0)

        val viewHolderTwo = ResolverListAdapter.ViewHolder(view)
        view.tag = viewHolderTwo
        whenever(testTaskProvider()).thenReturn(mock())

        testSubject.testViewBind(view, targetInfo, 0)

        verify(iconTaskOne, times(1)).loadIcon()
        verify(testTaskProvider, times(1)).invoke()
    }

    @Test
    fun getServiceTargetCount_shouldNotShowServiceTargets_returnsZero() {
        whenever(chooserListCommunicator.shouldShowServiceTargets()).thenReturn(false)
        val adapter = createChooserListAdapter()
        whenever(chooserListCommunicator.maxRankedTargets).thenReturn(10)
        addServiceTargets(adapter, targetCount = 50)

        assertThat(adapter.serviceTargetCount).isEqualTo(0)
    }

    private fun createTaskProvider(): (SelectableTargetInfo?) -> LoadDirectShareIconTask {
        val iconTaskOne = mock<LoadDirectShareIconTask>()
        val testTaskProvider =
            mock<() -> LoadDirectShareIconTask> { whenever(invoke()).thenReturn(iconTaskOne) }
        return { testTaskProvider.invoke() }
    }

    private fun addServiceTargets(adapter: ChooserListAdapter, targetCount: Int) {
        val origTarget =
            DisplayResolveInfo(
                Intent(),
                createResolveInfo(),
                Intent(),
                ResolverListAdapter.ResolveInfoPresentationGetter(context, 200, createResolveInfo())
            )
        val targets = mutableListOf<ChooserTarget>()
        for (i in 1..targetCount) {
            val score = 1f
            val componentName = ComponentName("chooser.list.adapter", "Test$i")
            val extras = Bundle()
            val icon: Icon? = null
            targets += ChooserTarget("Title $i", icon, score, componentName, extras)
        }
        val directShareToShortcutInfos = mapOf<ChooserTarget, ShortcutInfo>()
        adapter.addServiceResults(
            origTarget,
            targets,
            ChooserActivity.TARGET_TYPE_DEFAULT,
            directShareToShortcutInfos
        )
    }

    private fun createResolveInfo(): ResolveInfo {
        val applicationInfo =
            ApplicationInfo().apply {
                packageName = "chooser.list.adapter"
                name = "ChooserListAdapterTestApplication"
            }
        val activityInfo =
            ActivityInfo().apply {
                packageName = applicationInfo.packageName
                name = "ChooserListAdapterTest"
            }
        activityInfo.applicationInfo = applicationInfo
        val resolveInfo = ResolveInfo()
        resolveInfo.activityInfo = activityInfo
        return resolveInfo
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
        ChooserTarget("Title", null, 1f, ComponentName("package", "package.Class"), Bundle())

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

private class ChooserListAdapterOverride(
    context: Context?,
    payloadIntents: List<Intent>?,
    initialIntents: Array<out Intent>?,
    rList: List<ResolveInfo>?,
    filterLastUsed: Boolean,
    resolverListController: ResolverListController?,
    chooserListCommunicator: ChooserListCommunicator?,
    selectableTargetInfoCommunicator: SelectableTargetInfoCommunicator?,
    packageManager: PackageManager?,
    chooserActivityLogger: ChooserActivityLogger?,
    initialIntentsUserHandle: UserHandle?,
    private val taskProvider: (SelectableTargetInfo?) -> LoadDirectShareIconTask
) :
    ChooserListAdapter(
        context,
        payloadIntents,
        initialIntents,
        rList,
        filterLastUsed,
        resolverListController,
        chooserListCommunicator,
        selectableTargetInfoCommunicator,
        packageManager,
        chooserActivityLogger,
        initialIntentsUserHandle,
    ) {
    override fun createLoadDirectShareIconTask(
        info: SelectableTargetInfo?
    ): LoadDirectShareIconTask = taskProvider.invoke(info)

    fun testViewBind(view: View?, info: TargetInfo?, position: Int) {
        onBindView(view, info, position)
    }
}
