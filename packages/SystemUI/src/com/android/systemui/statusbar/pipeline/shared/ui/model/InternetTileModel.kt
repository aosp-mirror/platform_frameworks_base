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

package com.android.systemui.statusbar.pipeline.shared.ui.model

import android.content.Context
import android.graphics.drawable.Drawable
import android.service.quicksettings.Tile
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tileimpl.QSTileImpl

/** Model describing the state that the QS Internet tile should be in. */
sealed interface InternetTileModel {
    val secondaryTitle: CharSequence?
    val secondaryLabel: Text?
    val iconId: Int?
    val icon: QSTile.Icon?
    val stateDescription: ContentDescription?
    val contentDescription: ContentDescription?

    fun applyTo(state: QSTile.BooleanState, context: Context) {
        if (secondaryLabel != null) {
            state.secondaryLabel = secondaryLabel.loadText(context)
        } else {
            state.secondaryLabel = secondaryTitle
        }

        state.stateDescription = stateDescription.loadContentDescription(context)
        state.contentDescription = contentDescription.loadContentDescription(context)

        // To support both SignalDrawable and other icons, give priority to icons over IDs
        if (icon != null) {
            state.icon = icon
        } else if (iconId != null) {
            state.icon = QSTileImpl.ResourceIcon.get(iconId!!)
        }

        state.state =
            if (this is Active) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
    }

    data class Active(
        override val secondaryTitle: CharSequence? = null,
        override val secondaryLabel: Text? = null,
        override val iconId: Int? = null,
        override val icon: QSTile.Icon? = null,
        override val stateDescription: ContentDescription? = null,
        override val contentDescription: ContentDescription? = null,
    ) : InternetTileModel

    data class Inactive(
        override val secondaryTitle: CharSequence? = null,
        override val secondaryLabel: Text? = null,
        override val iconId: Int? = null,
        override val icon: QSTile.Icon? = null,
        override val stateDescription: ContentDescription? = null,
        override val contentDescription: ContentDescription? = null,
    ) : InternetTileModel
}

/**
 * [QSTile.Icon]-compatible container class for us to marshal the compacted [SignalDrawable] state
 * across to the internet tile.
 */
data class SignalIcon(val state: Int) : QSTile.Icon() {

    override fun getDrawable(context: Context): Drawable {
        val d = SignalDrawable(context)
        d.setLevel(state)
        return d
    }

    override fun toString(): String {
        return String.format("SignalIcon[mState=0x%08x]", state)
    }
}
