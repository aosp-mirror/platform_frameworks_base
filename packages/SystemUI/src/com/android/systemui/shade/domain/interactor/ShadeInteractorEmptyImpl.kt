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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Empty implementation of ShadeInteractor for System UI variants with no shade. */
@SysUISingleton
class ShadeInteractorEmptyImpl @Inject constructor() : ShadeInteractor {
    private val inactiveFlowBoolean = MutableStateFlow(false)
    private val inactiveFlowFloat = MutableStateFlow(0f)
    override val isShadeEnabled: StateFlow<Boolean> = inactiveFlowBoolean
    override val shadeExpansion: Flow<Float> = inactiveFlowFloat
    override val qsExpansion: StateFlow<Float> = inactiveFlowFloat
    override val isQsExpanded: StateFlow<Boolean> = inactiveFlowBoolean
    override val anyExpansion: StateFlow<Float> = inactiveFlowFloat
    override val isAnyFullyExpanded: Flow<Boolean> = inactiveFlowBoolean
    override val isAnyExpanded: StateFlow<Boolean> = inactiveFlowBoolean
    override val isUserInteractingWithShade: Flow<Boolean> = inactiveFlowBoolean
    override val isUserInteractingWithQs: Flow<Boolean> = inactiveFlowBoolean
    override val isUserInteracting: Flow<Boolean> = inactiveFlowBoolean
    override val isShadeTouchable: Flow<Boolean> = inactiveFlowBoolean
    override val isExpandToQsEnabled: Flow<Boolean> = inactiveFlowBoolean
}
