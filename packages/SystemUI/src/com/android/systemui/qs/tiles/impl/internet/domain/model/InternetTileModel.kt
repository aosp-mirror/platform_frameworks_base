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

package com.android.systemui.qs.tiles.impl.internet.domain.model

import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text

/** Model describing the state that the QS Internet tile should be in. */
sealed interface InternetTileModel {
    val secondaryTitle: CharSequence?
    val secondaryLabel: Text?
    val iconId: Int?
    val icon: Icon?
    val stateDescription: ContentDescription?
    val contentDescription: ContentDescription?

    data class Active(
        override val secondaryTitle: CharSequence? = null,
        override val secondaryLabel: Text? = null,
        override val iconId: Int? = null,
        override val icon: Icon? = null,
        override val stateDescription: ContentDescription? = null,
        override val contentDescription: ContentDescription? = null,
    ) : InternetTileModel

    data class Inactive(
        override val secondaryTitle: CharSequence? = null,
        override val secondaryLabel: Text? = null,
        override val iconId: Int? = null,
        override val icon: Icon? = null,
        override val stateDescription: ContentDescription? = null,
        override val contentDescription: ContentDescription? = null,
    ) : InternetTileModel
}
