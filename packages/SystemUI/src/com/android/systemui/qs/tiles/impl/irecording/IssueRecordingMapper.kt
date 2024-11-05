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

import android.content.res.Resources
import android.content.res.Resources.Theme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

class IssueRecordingMapper
@Inject
constructor(@Main private val resources: Resources, private val theme: Theme) :
    QSTileDataToStateMapper<IssueRecordingModel> {
    override fun map(config: QSTileConfig, data: IssueRecordingModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            icon =
                if (data.isRecording) {
                    activationState = QSTileState.ActivationState.ACTIVE
                    secondaryLabel = resources.getString(R.string.qs_record_issue_stop)
                    Icon.Loaded(
                        resources.getDrawable(R.drawable.qs_record_issue_icon_on, theme),
                        null,
                    )
                } else {
                    activationState = QSTileState.ActivationState.INACTIVE
                    secondaryLabel = resources.getString(R.string.qs_record_issue_start)
                    Icon.Loaded(
                        resources.getDrawable(R.drawable.qs_record_issue_icon_off, theme),
                        null,
                    )
                }
            supportedActions = setOf(QSTileState.UserAction.CLICK)
            contentDescription = "$label, $secondaryLabel"
        }
}
