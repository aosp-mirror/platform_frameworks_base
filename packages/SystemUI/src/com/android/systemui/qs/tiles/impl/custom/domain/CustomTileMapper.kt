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

package com.android.systemui.qs.tiles.impl.custom.domain

import android.annotation.SuppressLint
import android.app.IUriGrantsManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.service.quicksettings.Tile
import android.widget.Button
import android.widget.Switch
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.custom.domain.entity.CustomTileDataModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import javax.inject.Inject

@SysUISingleton
class CustomTileMapper
@Inject
constructor(
    private val context: Context,
    private val uriGrantsManager: IUriGrantsManager,
) : QSTileDataToStateMapper<CustomTileDataModel> {

    override fun map(config: QSTileConfig, data: CustomTileDataModel): QSTileState {
        val userContext =
            try {
                context.createContextAsUser(UserHandle(data.user.identifier), 0)
            } catch (exception: IllegalStateException) {
                null
            }

        val iconResult =
            if (userContext != null) {
                getIconProvider(
                    userContext = userContext,
                    icon = data.tile.icon,
                    callingAppUid = data.callingAppUid,
                    packageName = data.componentName.packageName,
                    defaultIcon = data.defaultTileIcon,
                )
            } else {
                IconResult({ null }, true)
            }

        return QSTileState.build(iconResult.iconProvider, data.tile.label) {
            var tileState: Int = data.tile.state
            if (data.hasPendingBind) {
                tileState = Tile.STATE_UNAVAILABLE
            }

            icon = iconResult.iconProvider
            activationState =
                if (iconResult.failedToLoad) {
                    QSTileState.ActivationState.UNAVAILABLE
                } else {
                    QSTileState.ActivationState.valueOf(tileState)
                }

            if (!data.tile.subtitle.isNullOrEmpty()) {
                secondaryLabel = data.tile.subtitle
            }

            contentDescription = data.tile.contentDescription
            stateDescription = data.tile.stateDescription

            if (!data.isToggleable) {
                sideViewIcon = QSTileState.SideViewIcon.Chevron
            }

            supportedActions =
                if (tileState == Tile.STATE_UNAVAILABLE) {
                    setOf(QSTileState.UserAction.LONG_CLICK)
                } else {
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
                }
            expandedAccessibilityClass =
                if (data.isToggleable) {
                    Switch::class
                } else {
                    Button::class
                }
        }
    }

    @SuppressLint("MissingPermission") // android.permission.INTERACT_ACROSS_USERS_FULL
    private fun getIconProvider(
        userContext: Context,
        icon: android.graphics.drawable.Icon?,
        callingAppUid: Int,
        packageName: String,
        defaultIcon: android.graphics.drawable.Icon?,
    ): IconResult {
        var failedToLoad = false
        val drawable: Drawable? =
            try {
                icon?.loadDrawableCheckingUriGrant(
                    userContext,
                    uriGrantsManager,
                    callingAppUid,
                    packageName,
                )
            } catch (e: Exception) {
                failedToLoad = true
                null
            } ?: defaultIcon?.loadDrawable(userContext)
        return IconResult(
            {
                drawable?.constantState?.newDrawable()?.let {
                    Icon.Loaded(it, contentDescription = null)
                }
            },
            failedToLoad,
        )
    }

    class IconResult(
        val iconProvider: () -> Icon?,
        val failedToLoad: Boolean,
    )
}
