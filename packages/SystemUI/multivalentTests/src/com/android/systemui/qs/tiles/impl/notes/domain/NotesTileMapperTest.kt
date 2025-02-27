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

package com.android.systemui.qs.tiles.impl.notes.domain

import android.graphics.drawable.TestStubDrawable
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.notes.domain.model.NotesTileModel
import com.android.systemui.qs.tiles.impl.notes.qsNotesTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import kotlin.test.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotesTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val qsTileConfig = kosmos.qsNotesTileConfig

    private val mapper by lazy {
        NotesTileMapper(
            context.orCreateTestableResources
                .apply { addOverride(R.drawable.ic_qs_notes, TestStubDrawable()) }
                .resources,
            context.theme,
        )
    }

    @Test
    fun mappedStateMatchesModel() {
        val inputModel = NotesTileModel

        val outputState = mapper.map(qsTileConfig, inputModel)

        val expectedState = createNotesTileState()
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createNotesTileState(): QSTileState =
        QSTileState(
            Icon.Loaded(context.getDrawable(R.drawable.ic_qs_notes)!!, null),
            R.drawable.ic_qs_notes,
            context.getString(R.string.quick_settings_notes_label),
            QSTileState.ActivationState.INACTIVE,
            /* secondaryLabel= */ null,
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            context.getString(R.string.quick_settings_notes_label),
            /* stateDescription= */ null,
            QSTileState.SideViewIcon.Chevron,
            QSTileState.EnabledState.ENABLED,
            Button::class.qualifiedName,
        )
}
