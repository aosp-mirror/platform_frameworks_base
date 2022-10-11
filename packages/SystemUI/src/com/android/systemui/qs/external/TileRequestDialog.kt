/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.external

import android.content.Context
import android.graphics.drawable.Icon
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.tileimpl.QSIconViewImpl
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon
import com.android.systemui.qs.tileimpl.QSTileViewImpl
import com.android.systemui.statusbar.phone.SystemUIDialog

/**
 * Dialog to present to the user to ask for authorization to add a [TileService].
 */
class TileRequestDialog(
    context: Context
) : SystemUIDialog(context) {

    companion object {
        internal val CONTENT_ID = R.id.content
    }

    /**
     * Set the data of the tile to add, to show the user.
     */
    fun setTileData(tileData: TileData) {
        val ll = (LayoutInflater
                        .from(context)
                        .inflate(R.layout.tile_service_request_dialog, null)
                        as ViewGroup).apply {
                    requireViewById<TextView>(R.id.text).apply {
                        text = context
                                .getString(R.string.qs_tile_request_dialog_text, tileData.appName)
                    }
                    addView(
                            createTileView(tileData),
                            context.resources.getDimensionPixelSize(
                                    R.dimen.qs_tile_service_request_tile_width),
                            context.resources.getDimensionPixelSize(R.dimen.qs_quick_tile_size)
                    )
                    isSelected = true
        }
        val spacing = 0
        setView(ll, spacing, spacing, spacing, spacing / 2)
    }

    private fun createTileView(tileData: TileData): QSTileView {
        val tile = QSTileViewImpl(context, QSIconViewImpl(context), true)
        val state = QSTile.BooleanState().apply {
            label = tileData.label
            handlesLongClick = false
            icon = tileData.icon?.loadDrawable(context)?.let {
                QSTileImpl.DrawableIcon(it)
            } ?: ResourceIcon.get(R.drawable.android)
            contentDescription = label
        }
        tile.onStateChanged(state)
        tile.post {
            tile.stateDescription = ""
            tile.isClickable = false
            tile.isSelected = true
        }
        return tile
    }

    /**
     * Data bundle of information to show the user.
     *
     * @property appName Name of the app requesting their [TileService] to be added.
     * @property label Label of the tile.
     * @property icon Icon for the tile.
     */
    data class TileData(
        val appName: CharSequence,
        val label: CharSequence,
        val icon: Icon?
    )
}
