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

package com.android.systemui.shared.clocks

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.monet.Style as MonetStyle
import java.io.IOException

class AssetLoader
private constructor(
    private val pluginCtx: Context,
    private val sysuiCtx: Context,
    private val baseDir: String,
    var seedColor: Int?,
    var overrideChroma: Float?,
    val typefaceCache: TypefaceCache,
    messageBuffer: MessageBuffer,
) {
    val logger = Logger(messageBuffer, TAG)
    private val resources =
        listOf(
            Pair(pluginCtx.resources, pluginCtx.packageName),
            Pair(sysuiCtx.resources, sysuiCtx.packageName),
        )

    constructor(
        pluginCtx: Context,
        sysuiCtx: Context,
        baseDir: String,
        messageBuffer: MessageBuffer,
    ) : this(
        pluginCtx,
        sysuiCtx,
        baseDir,
        seedColor = null,
        overrideChroma = null,
        typefaceCache =
            TypefaceCache(messageBuffer) {
                // TODO(b/364680873): Move constant to config_clockFontFamily when shipping
                return@TypefaceCache Typeface.create("google-sans-flex-clock", Typeface.NORMAL)
            },
        messageBuffer = messageBuffer,
    )

    fun listAssets(path: String): List<String> {
        return pluginCtx.resources.assets.list("$baseDir$path")?.toList() ?: emptyList()
    }

    fun tryReadString(resStr: String): String? = tryRead(resStr, ::readString)

    fun readString(resStr: String): String {
        val resPair = resolveResourceId(resStr)
        if (resPair == null) {
            throw IOException("Failed to parse string: $resStr")
        }

        val (res, id) = resPair
        return res.getString(id)
    }

    fun readFontAsset(resStr: String): Typeface = typefaceCache.getTypeface(resStr)

    fun tryReadTextAsset(path: String?): String? = tryRead(path, ::readTextAsset)

    fun readTextAsset(path: String): String {
        return pluginCtx.resources.assets.open("$baseDir$path").use { stream ->
            val buffer = ByteArray(stream.available())
            stream.read(buffer)
            String(buffer)
        }
    }

    fun tryReadDrawableAsset(path: String?): Drawable? = tryRead(path, ::readDrawableAsset)

    fun readDrawableAsset(path: String): Drawable {
        var result: Drawable?

        if (path.startsWith("@")) {
            val pair = resolveResourceId(path)
            if (pair == null) {
                throw IOException("Failed to parse $path to an id")
            }
            val (res, id) = pair
            result = res.getDrawable(id)
        } else if (path.endsWith("xml")) {
            // TODO(b/248609434): Support xml files in assets
            throw IOException("Cannot load xml files from assets")
        } else {
            // Attempt to load as if it's a bitmap and directly loadable
            result =
                pluginCtx.resources.assets.open("$baseDir$path").use { stream ->
                    Drawable.createFromResourceStream(
                        pluginCtx.resources,
                        TypedValue(),
                        stream,
                        null,
                    )
                }
        }

        return result ?: throw IOException("Failed to load: $baseDir$path")
    }

    fun parseResourceId(resStr: String): Triple<String?, String, String> {
        if (!resStr.startsWith("@")) {
            throw IOException("Invalid resource id: $resStr; Must start with '@'")
        }

        // Parse out resource string
        val parts = resStr.drop(1).split('/', ':')
        return when (parts.size) {
            2 -> Triple(null, parts[0], parts[1])
            3 -> Triple(parts[0], parts[1], parts[2])
            else -> throw IOException("Failed to parse resource string: $resStr")
        }
    }

    fun resolveResourceId(resStr: String): Pair<Resources, Int>? {
        val (packageName, category, name) = parseResourceId(resStr)
        return resolveResourceId(packageName, category, name)
    }

    fun resolveResourceId(
        packageName: String?,
        category: String,
        name: String,
    ): Pair<Resources, Int>? {
        for ((res, ctxPkgName) in resources) {
            val result = res.getIdentifier(name, category, packageName ?: ctxPkgName)
            if (result != 0) {
                return Pair(res, result)
            }
        }
        return null
    }

    private fun <TArg : Any, TRes : Any> tryRead(arg: TArg?, fn: (TArg) -> TRes): TRes? {
        try {
            if (arg == null) {
                return null
            }
            return fn(arg)
        } catch (ex: IOException) {
            logger.w("Failed to read $arg", ex)
            return null
        }
    }

    fun assetExists(path: String): Boolean {
        try {
            if (path.startsWith("@")) {
                val pair = resolveResourceId(path)
                return pair != null
            } else {
                val stream = pluginCtx.resources.assets.open("$baseDir$path")
                if (stream == null) {
                    return false
                }

                stream.close()
                return true
            }
        } catch (ex: IOException) {
            return false
        }
    }

    fun copy(messageBuffer: MessageBuffer? = null): AssetLoader =
        AssetLoader(
            pluginCtx,
            sysuiCtx,
            baseDir,
            seedColor,
            overrideChroma,
            typefaceCache,
            messageBuffer ?: logger.buffer,
        )

    fun setSeedColor(seedColor: Int?, style: MonetStyle?) {
        this.seedColor = seedColor
    }

    fun getClockPaddingStart(): Int {
        val result = resolveResourceId(null, "dimen", "clock_padding_start")
        if (result != null) {
            val (res, id) = result
            return res.getDimensionPixelSize(id)
        }
        return -1
    }

    fun getStatusBarHeight(): Int {
        val display = pluginCtx.getDisplayNoVerify()
        if (display != null) {
            return SystemBarUtils.getStatusBarHeight(pluginCtx.resources, display.cutout)
        }

        logger.w("No display available; falling back to android.R.dimen.status_bar_height")
        val statusBarHeight = resolveResourceId("android", "dimen", "status_bar_height")
        if (statusBarHeight != null) {
            val (res, resId) = statusBarHeight
            return res.getDimensionPixelSize(resId)
        }

        throw Exception("Could not fetch StatusBarHeight")
    }

    fun getResourcesId(name: String): Int = getResource("id", name) { _, id -> id }

    fun getDimen(name: String): Int = getResource("dimen", name, Resources::getDimensionPixelSize)

    fun getString(name: String): String = getResource("string", name, Resources::getString)

    private fun <T> getResource(
        category: String,
        name: String,
        getter: (res: Resources, id: Int) -> T,
    ): T {
        val result = resolveResourceId(null, category, name)
        if (result != null) {
            val (res, id) = result
            if (id == -1) throw Exception("Cannot find id of $id from $TAG")
            return getter(res, id)
        }
        throw Exception("Cannot find id of $name from $TAG")
    }

    companion object {
        private val TAG = AssetLoader::class.simpleName!!
    }
}
