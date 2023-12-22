/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */
package com.android.systemui.common.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.annotation.LayoutRes
import com.android.settingslib.Utils
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onDensityOrFontScaleChanged
import com.android.systemui.statusbar.policy.onThemeChanged
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Configuration-aware-state-tracking utilities. */
class ConfigurationState
@Inject
constructor(
    private val configurationController: ConfigurationController,
    @Application private val context: Context,
    private val layoutInflater: LayoutInflater,
) {
    /**
     * Returns a [Flow] that emits a dimension pixel size that is kept in sync with the device
     * configuration.
     *
     * @see android.content.res.Resources.getDimensionPixelSize
     */
    fun getDimensionPixelSize(@DimenRes id: Int): Flow<Int> {
        return configurationController.onDensityOrFontScaleChanged.emitOnStart().map {
            context.resources.getDimensionPixelSize(id)
        }
    }

    /**
     * Returns a [Flow] that emits a color that is kept in sync with the device theme.
     *
     * @see Utils.getColorAttrDefaultColor
     */
    fun getColorAttr(@AttrRes id: Int, @ColorInt defaultValue: Int): Flow<Int> {
        return configurationController.onThemeChanged.emitOnStart().map {
            Utils.getColorAttrDefaultColor(context, id, defaultValue)
        }
    }

    /**
     * Returns a [Flow] that emits a [View] that is re-inflated as necessary to remain in sync with
     * the device configuration.
     *
     * @see LayoutInflater.inflate
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : View> inflateLayout(
        @LayoutRes id: Int,
        root: ViewGroup?,
        attachToRoot: Boolean,
    ): Flow<T> {
        // TODO(b/305930747): This may lead to duplicate invocations if both flows emit, find a
        //  solution to only emit one event.
        return merge(
                configurationController.onThemeChanged,
                configurationController.onDensityOrFontScaleChanged,
            )
            .emitOnStart()
            .map { layoutInflater.inflate(id, root, attachToRoot) as T }
    }
}
