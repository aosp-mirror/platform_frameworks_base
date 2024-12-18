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

package com.android.systemui.qs.tiles.impl.internet.domain

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.widget.Switch
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.internet.domain.model.InternetTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.pipeline.shared.ui.model.InternetTileIconModel
import javax.inject.Inject

/** Maps [InternetTileModel] to [QSTileState]. */
class InternetTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
    @ShadeDisplayAware private val context: Context,
    @Main private val handler: Handler,
) : QSTileDataToStateMapper<InternetTileModel> {

    override fun map(config: QSTileConfig, data: InternetTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.quick_settings_internet_label)
            expandedAccessibilityClass = Switch::class

            secondaryLabel =
                if (data.secondaryLabel != null) {
                    data.secondaryLabel.loadText(context)
                } else {
                    data.secondaryTitle
                }

            stateDescription = data.stateDescription.loadContentDescription(context)
            contentDescription = data.contentDescription.loadContentDescription(context)

            when (val dataIcon = data.icon) {
                is InternetTileIconModel.ResourceId -> {
                    iconRes = dataIcon.resId
                    icon =
                        Icon.Loaded(
                            resources.getDrawable(dataIcon.resId, theme),
                            contentDescription = null,
                        )
                }

                is InternetTileIconModel.Cellular -> {
                    val signalDrawable = SignalDrawable(context, handler)
                    signalDrawable.setLevel(dataIcon.level)
                    icon = Icon.Loaded(signalDrawable, contentDescription = null)
                }

                is InternetTileIconModel.Satellite -> {
                    iconRes = dataIcon.resourceIcon.res // level is inferred from res
                    icon =
                        Icon.Loaded(
                            resources.getDrawable(dataIcon.resourceIcon.res, theme),
                            contentDescription = null,
                        )
                }
            }

            sideViewIcon = QSTileState.SideViewIcon.Chevron

            activationState =
                if (data is InternetTileModel.Active) QSTileState.ActivationState.ACTIVE
                else QSTileState.ActivationState.INACTIVE

            supportedActions =
                setOf(
                    QSTileState.UserAction.CLICK,
                    QSTileState.UserAction.TOGGLE_CLICK,
                    QSTileState.UserAction.LONG_CLICK,
                )
        }
}
