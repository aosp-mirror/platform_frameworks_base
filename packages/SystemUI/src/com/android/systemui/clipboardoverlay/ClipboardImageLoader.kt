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

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.Size
import com.android.systemui.res.R
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import java.io.IOException
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ClipboardImageLoader
@Inject
constructor(
    private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val mainScope: CoroutineScope
) {
    private val TAG: String = "ClipboardImageLoader"

    suspend fun load(uri: Uri, timeoutMs: Long = 300) =
        withTimeoutOrNull(timeoutMs) {
            withContext(bgDispatcher) {
                try {
                    val size = context.resources.getDimensionPixelSize(R.dimen.overlay_x_scale)
                    context.contentResolver.loadThumbnail(uri, Size(size, size * 4), null)
                } catch (e: IOException) {
                    Log.e(TAG, "Thumbnail loading failed!", e)
                    null
                }
            }
        }

    fun loadAsync(uri: Uri, callback: Consumer<Bitmap?>) {
        mainScope.launch { callback.accept(load(uri)) }
    }
}
