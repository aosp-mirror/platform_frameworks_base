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

package com.android.systemui.shade

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import javax.inject.Inject

@SysUISingleton
class QuickSettingsControllerSceneImpl
@Inject
constructor(
    private val shadeInteractor: ShadeInteractor,
    private val qsSceneAdapter: QSSceneAdapter,
) : QuickSettingsController {

    override val expanded: Boolean
        get() = shadeInteractor.isQsExpanded.value

    override val isCustomizing: Boolean
        get() = qsSceneAdapter.isCustomizerShowing.value

    @Deprecated("specific to legacy touch handling")
    override fun shouldQuickSettingsIntercept(x: Float, y: Float, yDiff: Float): Boolean {
        throw UnsupportedOperationException()
    }

    override fun closeQsCustomizer() {
        qsSceneAdapter.requestCloseCustomizer()
    }

    @Deprecated("specific to legacy split shade")
    override fun closeQs() {
        // Do nothing
    }

    @Deprecated("specific to legacy DebugDrawable")
    override fun calculateNotificationsTopPadding(
        isShadeExpanding: Boolean,
        keyguardNotificationStaticPadding: Int,
        expandedFraction: Float
    ): Float {
        throw UnsupportedOperationException()
    }

    @Deprecated("specific to legacy DebugDrawable")
    override fun calculatePanelHeightExpanded(stackScrollerPadding: Int): Int {
        throw UnsupportedOperationException()
    }
}
