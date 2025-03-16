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

package com.android.systemui.scene.ui.viewmodel

import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.viewmodel.dualShadeActions
import com.android.systemui.shade.ui.viewmodel.singleShadeActions
import com.android.systemui.shade.ui.viewmodel.splitShadeActions
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class GoneUserActionsViewModel
@AssistedInject
constructor(private val shadeInteractor: ShadeInteractor) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        shadeInteractor.shadeMode.collect { shadeMode ->
            setActions(
                buildList {
                        addAll(
                            when (shadeMode) {
                                ShadeMode.Single ->
                                    singleShadeActions(requireTwoPointersForTopEdgeForQs = true)
                                ShadeMode.Split -> splitShadeActions()
                                ShadeMode.Dual -> dualShadeActions()
                            }
                        )
                    }
                    .associate { it }
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): GoneUserActionsViewModel
    }
}
