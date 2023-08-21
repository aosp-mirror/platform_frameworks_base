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

package com.android.systemui.communal.ui.view.layout.sections

import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import com.android.systemui.R
import com.android.systemui.communal.ui.adapter.CommunalWidgetViewAdapter
import com.android.systemui.communal.ui.binder.CommunalWidgetViewBinder
import com.android.systemui.communal.ui.viewmodel.CommunalWidgetViewModel
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import dagger.Lazy
import javax.inject.Inject

class DefaultCommunalWidgetSection
@Inject
constructor(
    private val featureFlags: FeatureFlags,
    private val keyguardRootView: KeyguardRootView,
    private val communalWidgetViewModel: CommunalWidgetViewModel,
    private val communalWidgetViewAdapter: CommunalWidgetViewAdapter,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection {
    private val widgetAreaViewId = R.id.communal_widget_wrapper
    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!featureFlags.isEnabled(Flags.WIDGET_ON_KEYGUARD)) {
            return
        }

        CommunalWidgetViewBinder.bind(
            keyguardRootView,
            communalWidgetViewModel,
            communalWidgetViewAdapter,
            keyguardBlueprintInteractor.get(),
        )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(widgetAreaViewId, WRAP_CONTENT)
            constrainHeight(widgetAreaViewId, WRAP_CONTENT)
            connect(widgetAreaViewId, BOTTOM, PARENT_ID, BOTTOM)
            connect(widgetAreaViewId, END, PARENT_ID, END)
        }
    }
}
