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

package com.android.systemui.shade.domain.interactor

import android.provider.Settings
import androidx.annotation.FloatRange
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.scene.domain.SceneFrameworkTableLog
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Defines interface for classes that can provide state and business logic related to the mode of
 * the shade.
 */
interface ShadeModeInteractor {

    /**
     * The version of the shade layout to use.
     *
     * Note: Most likely, you want to read [isShadeLayoutWide] instead of this.
     */
    val shadeMode: StateFlow<ShadeMode>

    /**
     * Whether the shade layout should be wide (true) or narrow (false).
     *
     * In a wide layout, notifications and quick settings each take up only half the screen width
     * (whether they are shown at the same time or not). In a narrow layout, they can each be as
     * wide as the entire screen.
     */
    val isShadeLayoutWide: StateFlow<Boolean>

    /** Convenience shortcut for querying whether the current [shadeMode] is [ShadeMode.Dual]. */
    val isDualShade: Boolean
        get() = shadeMode.value is ShadeMode.Dual

    /** Convenience shortcut for querying whether the current [shadeMode] is [ShadeMode.Split]. */
    val isSplitShade: Boolean
        get() = shadeMode.value is ShadeMode.Split

    /**
     * The fraction between [0..1] (i.e., percentage) of screen width to consider the threshold
     * between "top-left" and "top-right" for the purposes of dual-shade invocation.
     *
     * When the dual-shade is not wide, this always returns 0.5 (the top edge is evenly split). On
     * wide layouts however, a larger fraction is returned because only the area of the system
     * status icons is considered top-right.
     *
     * Note that this fraction only determines the split between the absolute left and right
     * directions. In RTL layouts, the "top-start" edge will resolve to "top-right", and "top-end"
     * will resolve to "top-left".
     */
    @FloatRange(from = 0.0, to = 1.0) fun getTopEdgeSplitFraction(): Float
}

class ShadeModeInteractorImpl
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val repository: ShadeRepository,
    secureSettingsRepository: SecureSettingsRepository,
    @SceneFrameworkTableLog private val tableLogBuffer: TableLogBuffer,
) : ShadeModeInteractor {

    private val isDualShadeEnabled: Flow<Boolean> =
        secureSettingsRepository.boolSetting(
            Settings.Secure.DUAL_SHADE,
            defaultValue = DUAL_SHADE_ENABLED_DEFAULT,
        )

    override val isShadeLayoutWide: StateFlow<Boolean> = repository.isShadeLayoutWide

    private val shadeModeInitialValue: ShadeMode
        get() =
            determineShadeMode(
                isDualShadeEnabled = DUAL_SHADE_ENABLED_DEFAULT,
                isShadeLayoutWide = repository.isShadeLayoutWide.value,
            )

    override val shadeMode: StateFlow<ShadeMode> =
        combine(isDualShadeEnabled, repository.isShadeLayoutWide, ::determineShadeMode)
            .logDiffsForTable(tableLogBuffer = tableLogBuffer, initialValue = shadeModeInitialValue)
            .stateIn(applicationScope, SharingStarted.Eagerly, initialValue = shadeModeInitialValue)

    @FloatRange(from = 0.0, to = 1.0) override fun getTopEdgeSplitFraction(): Float = 0.5f

    private fun determineShadeMode(
        isDualShadeEnabled: Boolean,
        isShadeLayoutWide: Boolean,
    ): ShadeMode {
        return when {
            isDualShadeEnabled ||
                // TODO(b/388793191): This ensures that the dual_shade aconfig flag can also enable
                //  Dual Shade, to avoid breaking unit tests. Remove this once all references to the
                //  flag are removed.
                DualShade.isEnabled -> ShadeMode.Dual
            isShadeLayoutWide -> ShadeMode.Split
            else -> ShadeMode.Single
        }
    }

    companion object {
        /* Whether the Dual Shade setting is enabled by default. */
        private const val DUAL_SHADE_ENABLED_DEFAULT = false
    }
}

class ShadeModeInteractorEmptyImpl @Inject constructor() : ShadeModeInteractor {

    override val shadeMode: StateFlow<ShadeMode> = MutableStateFlow(ShadeMode.Single)

    override val isShadeLayoutWide: StateFlow<Boolean> = MutableStateFlow(false)

    override fun getTopEdgeSplitFraction(): Float = 0.5f
}
