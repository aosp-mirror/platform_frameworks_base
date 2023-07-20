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

package com.android.systemui.statusbar.pipeline.mobile.ui

import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger.Companion.getIdForLogging
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.KeyguardMobileIconViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.QsMobileIconViewModel
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
class MobileViewLoggerTest : SysuiTestCase() {
    private val buffer = LogBufferFactory(DumpManager(), mock()).create("buffer", 10)
    private val stringWriter = StringWriter()
    private val printWriter = PrintWriter(stringWriter)

    private val underTest = MobileViewLogger(buffer, mock())

    @Mock private lateinit var flags: StatusBarPipelineFlags
    @Mock private lateinit var commonViewModel: MobileIconViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun collectionStarted_dumpHasInfo() {
        val view = TextView(context)
        val viewModel = QsMobileIconViewModel(commonViewModel, flags)

        underTest.logCollectionStarted(view, viewModel)

        val dumpString = getDumpString()
        assertThat(dumpString).contains("${view.getIdForLogging()}, isCollecting=true")
    }

    @Test
    fun collectionStarted_multipleViews_dumpHasInfo() {
        val view = TextView(context)
        val view2 = TextView(context)
        val viewModel = QsMobileIconViewModel(commonViewModel, flags)
        val viewModel2 = KeyguardMobileIconViewModel(commonViewModel, flags)

        underTest.logCollectionStarted(view, viewModel)
        underTest.logCollectionStarted(view2, viewModel2)

        val dumpString = getDumpString()
        assertThat(dumpString).contains("${view.getIdForLogging()}, isCollecting=true")
        assertThat(dumpString).contains("${view2.getIdForLogging()}, isCollecting=true")
    }

    @Test
    fun collectionStopped_dumpHasInfo() {
        val view = TextView(context)
        val view2 = TextView(context)
        val viewModel = QsMobileIconViewModel(commonViewModel, flags)
        val viewModel2 = KeyguardMobileIconViewModel(commonViewModel, flags)

        underTest.logCollectionStarted(view, viewModel)
        underTest.logCollectionStarted(view2, viewModel2)
        underTest.logCollectionStopped(view, viewModel)

        val dumpString = getDumpString()
        assertThat(dumpString).contains("${view.getIdForLogging()}, isCollecting=false")
        assertThat(dumpString).contains("${view2.getIdForLogging()}, isCollecting=true")
    }

    private fun getDumpString(): String {
        underTest.dump(printWriter, args = arrayOf())
        return stringWriter.toString()
    }
}
