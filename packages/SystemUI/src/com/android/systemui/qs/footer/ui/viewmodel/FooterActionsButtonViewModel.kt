/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs.footer.ui.viewmodel

import android.annotation.AttrRes
import android.annotation.ColorInt
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon

/**
 * A ViewModel for a simple footer actions button. This is used for the user switcher, settings and
 * power buttons.
 */
data class FooterActionsButtonViewModel(
    val id: Int,
    val icon: Icon,
    @ColorInt val iconTint: Int?,
    @AttrRes val backgroundColor: Int,
    val onClick: (Expandable) -> Unit,
)
