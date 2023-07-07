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
package com.android.systemui.clipboardoverlay

import android.content.ClipData
import android.content.ClipDescription.EXTRA_IS_SENSITIVE
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.view.textclassifier.TextLinks
import com.android.systemui.R
import java.io.IOException

data class ClipboardModel(
    val clipData: ClipData,
    val source: String,
    val type: Type,
    val text: CharSequence?,
    val textLinks: TextLinks?,
    val uri: Uri?,
    val isSensitive: Boolean,
    val isRemote: Boolean,
) {
    private var _bitmap: Bitmap? = null

    fun dataMatches(other: ClipboardModel?): Boolean {
        if (other == null) {
            return false
        }
        return source == other.source &&
            type == other.type &&
            text == other.text &&
            uri == other.uri &&
            isSensitive == other.isSensitive
    }

    fun loadThumbnail(context: Context): Bitmap? {
        if (_bitmap == null && type == Type.IMAGE && uri != null) {
            try {
                val size = context.resources.getDimensionPixelSize(R.dimen.overlay_x_scale)
                _bitmap = context.contentResolver.loadThumbnail(uri, Size(size, size * 4), null)
            } catch (e: IOException) {
                Log.e(TAG, "Thumbnail loading failed!", e)
            }
        }
        return _bitmap
    }

    internal companion object {
        private val TAG: String = "ClipboardModel"

        @JvmStatic
        fun fromClipData(
            context: Context,
            utils: ClipboardOverlayUtils,
            clipData: ClipData,
            source: String
        ): ClipboardModel {
            val sensitive = clipData.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) ?: false
            val item = clipData.getItemAt(0)!!
            val type = getType(context, item)
            val remote = utils.isRemoteCopy(context, clipData, source)
            return ClipboardModel(
                clipData,
                source,
                type,
                item.text,
                item.textLinks,
                item.uri,
                sensitive,
                remote
            )
        }

        private fun getType(context: Context, item: ClipData.Item): Type {
            return if (!TextUtils.isEmpty(item.text)) {
                Type.TEXT
            } else if (item.uri != null) {
                if (context.contentResolver.getType(item.uri)?.startsWith("image") == true) {
                    Type.IMAGE
                } else {
                    Type.URI
                }
            } else {
                Type.OTHER
            }
        }
    }

    enum class Type {
        TEXT,
        IMAGE,
        URI,
        OTHER
    }
}
