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

package com.android.systemui.common.shared.model

import android.annotation.StringRes
import android.content.Context

/**
 * Models a content description, that can either be already [loaded][ContentDescription.Loaded] or
 * be a [reference][ContentDescription.Resource] to a resource.
 */
sealed class ContentDescription {
    data class Loaded(
        val description: String?,
    ) : ContentDescription()

    data class Resource(
        @StringRes val res: Int,
    ) : ContentDescription()

    companion object {
        /**
         * Returns the loaded content description string, or null if we don't have one.
         *
         * Prefer [com.android.systemui.common.ui.binder.ContentDescriptionViewBinder.bind] over
         * this method. This should only be used for testing or concatenation purposes.
         */
        fun ContentDescription?.loadContentDescription(context: Context): String? {
            return when (this) {
                null -> null
                is Loaded -> this.description
                is Resource -> context.getString(this.res)
            }
        }
    }
}
