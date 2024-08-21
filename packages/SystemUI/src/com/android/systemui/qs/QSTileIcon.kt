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

package com.android.systemui.qs

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tileimpl.QSTileImpl

/**
 * Creates a [QSTile.Icon] from an [Icon].
 * * [Icon.Loaded] -> [QSTileImpl.DrawableIcon]
 * * [Icon.Resource] -> [QSTileImpl.ResourceIcon]
 */
fun Icon.asQSTileIcon(): QSTile.Icon {
    return when (this) {
        is Icon.Loaded -> {
            QSTileImpl.DrawableIcon(this.drawable)
        }
        is Icon.Resource -> {
            QSTileImpl.ResourceIcon.get(this.res)
        }
    }
}
