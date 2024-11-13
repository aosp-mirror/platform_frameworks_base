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

package com.android.systemui.qs.tiles.impl.irecording

import android.content.res.mainResources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.qsEventLogger
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.recordissue.RecordIssueModule
import com.android.systemui.res.R
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class IssueRecordingMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val uiConfig =
        QSTileUIConfig.Resource(R.drawable.qs_record_issue_icon_off, R.string.qs_record_issue_label)
    private val config =
        QSTileConfig(
            TileSpec.create(RecordIssueModule.TILE_SPEC),
            uiConfig,
            kosmos.qsEventLogger.getNewInstanceId(),
            TileCategory.UTILITIES,
        )
    private val resources = kosmos.mainResources
    private val theme = resources.newTheme()

    @Test
    fun whenData_isRecording_useCorrectResources() {
        val underTest = IssueRecordingMapper(resources, theme)
        val tileState = underTest.map(config, IssueRecordingModel(true))
        Truth.assertThat(tileState.activationState).isEqualTo(QSTileState.ActivationState.ACTIVE)
    }

    @Test
    fun whenData_isNotRecording_useCorrectResources() {
        val underTest = IssueRecordingMapper(resources, theme)
        val tileState = underTest.map(config, IssueRecordingModel(false))
        Truth.assertThat(tileState.activationState).isEqualTo(QSTileState.ActivationState.INACTIVE)
    }
}
