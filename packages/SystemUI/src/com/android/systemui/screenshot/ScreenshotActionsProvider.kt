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

package com.android.systemui.screenshot

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import androidx.appcompat.content.res.AppCompatResources
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Provides actions for screenshots. This class can be overridden by a vendor-specific SysUI
 * implementation.
 */
interface ScreenshotActionsProvider {
    data class ScreenshotAction(
        val icon: Drawable? = null,
        val text: String? = null,
        val description: String,
        val overrideTransition: Boolean = false,
        val retrieveIntent: (Uri) -> Intent
    )

    interface ScreenshotActionsCallback {
        fun setPreviewAction(overrideTransition: Boolean = false, retrieveIntent: (Uri) -> Intent)
        fun addAction(action: ScreenshotAction) = addActions(listOf(action))
        fun addActions(actions: List<ScreenshotAction>)
    }

    interface Factory {
        fun create(
            context: Context,
            user: UserHandle?,
            callback: ScreenshotActionsCallback
        ): ScreenshotActionsProvider
    }
}

class DefaultScreenshotActionsProvider(
    private val context: Context,
    private val user: UserHandle?,
    private val callback: ScreenshotActionsProvider.ScreenshotActionsCallback
) : ScreenshotActionsProvider {
    init {
        callback.setPreviewAction(true) { ActionIntentCreator.createEdit(it, context) }
        val editAction =
            ScreenshotActionsProvider.ScreenshotAction(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_edit),
                context.resources.getString(R.string.screenshot_edit_label),
                context.resources.getString(R.string.screenshot_edit_description),
                true
            ) { uri ->
                ActionIntentCreator.createEdit(uri, context)
            }
        val shareAction =
            ScreenshotActionsProvider.ScreenshotAction(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_share),
                context.resources.getString(R.string.screenshot_share_label),
                context.resources.getString(R.string.screenshot_share_description),
                false
            ) { uri ->
                ActionIntentCreator.createShare(uri)
            }
        callback.addActions(listOf(editAction, shareAction))
    }

    class Factory @Inject constructor() : ScreenshotActionsProvider.Factory {
        override fun create(
            context: Context,
            user: UserHandle?,
            callback: ScreenshotActionsProvider.ScreenshotActionsCallback
        ): ScreenshotActionsProvider {
            return DefaultScreenshotActionsProvider(context, user, callback)
        }
    }
}
