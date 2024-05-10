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

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import com.android.systemui.keyguard.domain.interactor.Offsets
import com.android.systemui.keyguard.domain.interactor.UdfpsKeyguardInteractor
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** View-model for UDFPS AOD view. */
@ExperimentalCoroutinesApi
class UdfpsAodViewModel
@Inject
constructor(
    val interactor: UdfpsKeyguardInteractor,
    val context: Context,
) {
    val alpha: Flow<Float> = interactor.dozeAmount
    val burnInOffsets: Flow<Offsets> = interactor.burnInOffsets
    val isVisible: Flow<Boolean> = alpha.map { it != 0f }

    // Padding between the fingerprint icon and its bounding box in pixels.
    val padding: Flow<Int> =
        interactor.scaleForResolution.map { scale ->
            (context.resources.getDimensionPixelSize(R.dimen.lock_icon_padding) * scale)
                .roundToInt()
        }
}
