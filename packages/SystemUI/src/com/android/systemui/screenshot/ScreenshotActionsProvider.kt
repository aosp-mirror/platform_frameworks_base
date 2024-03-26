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
 * Provides static actions for screenshots. This class can be overridden by a vendor-specific SysUI
 * implementation.
 */
interface ScreenshotActionsProvider {
    data class ScreenshotAction(
        val icon: Drawable?,
        val text: String?,
        val overrideTransition: Boolean,
        val retrieveIntent: (Uri) -> Intent
    )

    fun getPreviewAction(context: Context, uri: Uri, user: UserHandle): Intent
    fun getActions(context: Context, user: UserHandle): List<ScreenshotAction>
}

class DefaultScreenshotActionsProvider @Inject constructor() : ScreenshotActionsProvider {
    override fun getPreviewAction(context: Context, uri: Uri, user: UserHandle): Intent {
        return ActionIntentCreator.createEdit(uri, context)
    }

    override fun getActions(
        context: Context,
        user: UserHandle
    ): List<ScreenshotActionsProvider.ScreenshotAction> {
        val editAction =
            ScreenshotActionsProvider.ScreenshotAction(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_edit),
                context.resources.getString(R.string.screenshot_edit_label),
                true
            ) { uri ->
                ActionIntentCreator.createEdit(uri, context)
            }
        val shareAction =
            ScreenshotActionsProvider.ScreenshotAction(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_share),
                context.resources.getString(R.string.screenshot_share_label),
                false
            ) { uri ->
                ActionIntentCreator.createShare(uri)
            }
        return listOf(editAction, shareAction)
    }
}
