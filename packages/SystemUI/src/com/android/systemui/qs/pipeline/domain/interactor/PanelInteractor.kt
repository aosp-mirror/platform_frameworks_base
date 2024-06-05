/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs.pipeline.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.ShadeController
import javax.inject.Inject

/** Encapsulates business logic for interacting with the QS panel. */
interface PanelInteractor {

    /** Collapse the shade */
    fun collapsePanels()

    /** Collapse the shade forcefully, skipping some animations. */
    fun forceCollapsePanels()

    /** Open the Quick Settings panel */
    fun openPanels()
}

@SysUISingleton
class PanelInteractorImpl
@Inject
constructor(
    private val shadeController: ShadeController,
) : PanelInteractor {
    override fun collapsePanels() {
        shadeController.postAnimateCollapseShade()
    }

    override fun forceCollapsePanels() {
        shadeController.postAnimateForceCollapseShade()
    }

    override fun openPanels() {
        shadeController.postAnimateExpandQs()
    }
}
