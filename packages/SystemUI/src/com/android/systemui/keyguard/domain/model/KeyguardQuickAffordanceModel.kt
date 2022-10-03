/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.model

import androidx.annotation.StringRes
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig
import kotlin.reflect.KClass

/**
 * Models a "quick affordance" in the keyguard bottom area (for example, a button on the
 * lock-screen).
 */
sealed class KeyguardQuickAffordanceModel {
    /** No affordance should show up. */
    object Hidden : KeyguardQuickAffordanceModel()

    /** A affordance is visible. */
    data class Visible(
        /** Identifier for the affordance this is modeling. */
        val configKey: KClass<out KeyguardQuickAffordanceConfig>,
        /** An icon for the affordance. */
        val icon: ContainedDrawable,
        /**
         * Resource ID for a string to use for the accessibility content description text of the
         * affordance.
         */
        @StringRes val contentDescriptionResourceId: Int,
    ) : KeyguardQuickAffordanceModel()
}
