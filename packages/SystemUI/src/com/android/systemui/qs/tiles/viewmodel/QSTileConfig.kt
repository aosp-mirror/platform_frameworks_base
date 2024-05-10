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

package com.android.systemui.qs.tiles.viewmodel

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.android.internal.logging.InstanceId
import com.android.systemui.qs.pipeline.shared.TileSpec

data class QSTileConfig(
    val tileSpec: TileSpec,
    val uiConfig: QSTileUIConfig,
    val instanceId: InstanceId,
    val metricsSpec: String = tileSpec.spec,
    val policy: QSTilePolicy = QSTilePolicy.NoRestrictions,
)

/**
 * Static tile icon and label to be used when the fully operational tile isn't needed (ex. in edit
 * mode). Icon and label are resources to better support config/locale changes.
 */
sealed interface QSTileUIConfig {

    val iconRes: Int
        @DrawableRes get
    val labelRes: Int
        @StringRes get

    /**
     * Represents the absence of static UI state. This should be avoided by platform tiles in favour
     * of [Resource]. Returns [Resources.ID_NULL] for each field.
     */
    data object Empty : QSTileUIConfig {
        override val iconRes: Int
            get() = Resources.ID_NULL
        override val labelRes: Int
            get() = Resources.ID_NULL
    }

    /** Config containing actual icon and label resources. */
    data class Resource(
        @DrawableRes override val iconRes: Int,
        @StringRes override val labelRes: Int,
    ) : QSTileUIConfig
}

/** Represents policy restrictions that may be imposed on the tile. */
sealed interface QSTilePolicy {
    /** Tile has no policy restrictions */
    data object NoRestrictions : QSTilePolicy

    /**
     * Tile might be disabled by policy. [userRestriction] is usually a constant from
     * [android.os.UserManager] like [android.os.UserManager.DISALLOW_AIRPLANE_MODE].
     * [com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractor] is commonly used
     * to resolve this and show user a message when needed.
     */
    data class Restricted(val userRestriction: String) : QSTilePolicy
}
