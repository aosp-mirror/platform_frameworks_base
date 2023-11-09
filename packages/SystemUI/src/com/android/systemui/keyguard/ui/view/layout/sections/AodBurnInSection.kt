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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import androidx.constraintlayout.helper.widget.Layer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import javax.inject.Inject

/** Adds a layer to group elements for translation for burn-in preventation */
class AodBurnInSection
@Inject
constructor(
    private val context: Context,
) : KeyguardSection() {

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!KeyguardShadeMigrationNssl.isEnabled) {
            return
        }

        val statusView = constraintLayout.requireViewById<View>(R.id.keyguard_status_view)
        val nic = constraintLayout.requireViewById<View>(R.id.aod_notification_icon_container)
        val burnInLayer =
            Layer(context).apply {
                id = R.id.burn_in_layer
                addView(nic)
                addView(statusView)
            }
        constraintLayout.addView(burnInLayer)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!KeyguardShadeMigrationNssl.isEnabled) {
            return
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!KeyguardShadeMigrationNssl.isEnabled) {
            return
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(R.id.burn_in_layer)
    }
}
