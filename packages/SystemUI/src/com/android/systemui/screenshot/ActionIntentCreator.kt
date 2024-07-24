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

package com.android.systemui.screenshot

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.systemui.res.R

object ActionIntentCreator {
    /** @return a chooser intent to share the given URI. */
    fun createShare(uri: Uri): Intent = createShare(uri, subject = null, text = null)

    /** @return a chooser intent to share the given URI with the optional provided subject. */
    fun createShareWithSubject(uri: Uri, subject: String): Intent =
        createShare(uri, subject = subject)

    /** @return a chooser intent to share the given URI with the optional provided extra text. */
    fun createShareWithText(uri: Uri, extraText: String): Intent =
        createShare(uri, text = extraText)

    private fun createShare(rawUri: Uri, subject: String? = null, text: String? = null): Intent {
        val uri = uriWithoutUserId(rawUri)

        // Create a share intent, this will always go through the chooser activity first
        // which should not trigger auto-enter PiP
        val sharingIntent =
            Intent(Intent.ACTION_SEND).apply {
                setDataAndType(uri, "image/png")
                putExtra(Intent.EXTRA_STREAM, uri)

                // Include URI in ClipData also, so that grantPermission picks it up.
                // We don't use setData here because some apps interpret this as "to:".
                clipData =
                    ClipData(
                        ClipDescription("content", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)),
                        ClipData.Item(uri)
                    )

                subject?.let { putExtra(Intent.EXTRA_SUBJECT, subject) }
                text?.let { putExtra(Intent.EXTRA_TEXT, text) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

        return Intent.createChooser(sharingIntent, null)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * @return an ACTION_EDIT intent for the given URI, directed to config_screenshotEditor if
     *   available.
     */
    fun createEdit(rawUri: Uri, context: Context): Intent {
        val uri = uriWithoutUserId(rawUri)
        val editIntent = Intent(Intent.ACTION_EDIT)

        val editor = context.getString(R.string.config_screenshotEditor)
        if (editor.isNotEmpty()) {
            editIntent.component = ComponentName.unflattenFromString(editor)
        }

        return editIntent
            .setDataAndType(uri, "image/png")
            .putExtra(EXTRA_EDIT_SOURCE, EDIT_SOURCE_SCREENSHOT)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private const val EXTRA_EDIT_SOURCE = "edit_source"
    private const val EDIT_SOURCE_SCREENSHOT = "screenshot"
}

/**
 * URIs here are passed only via Intent which are sent to the target user via Intent. Because of
 * this, the userId component can be removed to prevent compatibility issues when an app attempts
 * valid a URI containing a userId within the authority.
 */
private fun uriWithoutUserId(uri: Uri): Uri {
    return ContentProvider.getUriWithoutUserId(uri)
}
