/*
 * Copyright (c) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.statusbar

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.ExpandHelper
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.keyguard.domain.interactor.NaturalScrollingSettingObserver
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class DragDownHelperTest : SysuiTestCase() {

    private lateinit var dragDownHelper: DragDownHelper

    private val collapsedHeight = 300
    private val falsingManager: FalsingManager = mock()
    private val falsingCollector: FalsingCollector = mock()
    private val dragDownloadCallback: LockscreenShadeTransitionController = mock()
    private val expandableView: ExpandableView = mock()
    private val expandCallback: ExpandHelper.Callback = mock()
    private val naturalScrollingSettingObserver: NaturalScrollingSettingObserver = mock()

    @Before
    fun setUp() {
        whenever(expandableView.collapsedHeight).thenReturn(collapsedHeight)
        whenever(naturalScrollingSettingObserver.isNaturalScrollingEnabled).thenReturn(true)

        dragDownHelper = DragDownHelper(
                falsingManager,
                falsingCollector,
                dragDownloadCallback,
                naturalScrollingSettingObserver,
                mContext,
        ).also {
            it.expandCallback = expandCallback
        }
    }

    @Test
    fun cancelChildExpansion_updateHeight() {
        whenever(expandableView.actualHeight).thenReturn(500)

        dragDownHelper.cancelChildExpansion(expandableView, animationDuration = 0)

        verify(expandableView, atLeast(1)).actualHeight = collapsedHeight
    }

    @Test
    fun cancelChildExpansion_dontUpdateHeight() {
        whenever(expandableView.actualHeight).thenReturn(collapsedHeight)

        dragDownHelper.cancelChildExpansion(expandableView, animationDuration = 0)

        verify(expandableView, never()).actualHeight = anyInt()
    }
}
