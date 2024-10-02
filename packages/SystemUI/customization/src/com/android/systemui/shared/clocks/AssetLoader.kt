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
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.cam.Cam
import com.android.internal.graphics.cam.CamUtils
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.Style as MonetStyle
import com.android.systemui.monet.TonalPalette
import java.io.IOException
import kotlin.math.abs

class AssetLoader
private constructor(
    private val pluginCtx: Context,
    private val sysuiCtx: Context,
    private val baseDir: String,
    var colorScheme: ColorScheme?,
    var seedColor: Int?,
    var overrideChroma: Float?,
    val typefaceCache: TypefaceCache,
    val getThemeSeedColor: (Context) -> Int,
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
        getThemeSeedColor: ((Context) -> Int)? = null,
    ) : this(
        pluginCtx,
        sysuiCtx,
        baseDir,
        colorScheme = null,
        seedColor = null,
        overrideChroma = null,
        typefaceCache =
            TypefaceCache(messageBuffer) { Typeface.createFromAsset(pluginCtx.assets, it) },
        getThemeSeedColor = getThemeSeedColor ?: Companion::getThemeSeedColor,
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

    fun tryReadColor(resStr: String): Int? = tryRead(resStr, ::readColor)

    fun readColor(resStr: String): Int {
        if (resStr.startsWith("#")) {
            return Color.parseColor(resStr)
        }

        val schemeColor = tryParseColorFromScheme(resStr)
        if (schemeColor != null) {
            logColor("ColorScheme: $resStr", schemeColor)
            return checkChroma(schemeColor)
        }

        val result = resolveColorResourceId(resStr)
        if (result == null) {
            throw IOException("Failed to parse color: $resStr")
        }

        val (res, colorId, targetTone) = result
        val color = res.getColor(colorId)
        if (targetTone == null || TonalPalette.SHADE_KEYS.contains(targetTone.toInt())) {
            logColor("Resources: $resStr", color)
            return checkChroma(color)
        } else {
            val interpolatedColor =
                ColorStateList.valueOf(color)
                    .withLStar((1000f - targetTone) / 10f)
                    .getDefaultColor()
            logColor("Resources (interpolated tone): $resStr", interpolatedColor)
            return checkChroma(interpolatedColor)
        }
    }

    private fun checkChroma(color: Int): Int {
        return overrideChroma?.let {
            val cam = Cam.fromInt(color)
            val tone = CamUtils.lstarFromInt(color)
            val result = ColorUtils.CAMToColor(cam.hue, it, tone)
            logColor("Chroma override", result)
            result
        } ?: color
    }

    private fun tryParseColorFromScheme(resStr: String): Int? {
        val colorScheme = this.colorScheme
        if (colorScheme == null) {
            logger.w("No color scheme available")
            return null
        }

        val (packageName, category, name) = parseResourceId(resStr)
        if (packageName != "android" || category != "color") {
            logger.w("Failed to parse package from $resStr")
            return null
        }

        var parts = name.split('_')
        if (parts.size != 3) {
            logger.w("Failed to find palette and shade from $name")
            return null
        }
        val (_, paletteKey, shadeKeyStr) = parts

        val palette =
            when (paletteKey) {
                "accent1" -> colorScheme.accent1
                "accent2" -> colorScheme.accent2
                "accent3" -> colorScheme.accent3
                "neutral1" -> colorScheme.neutral1
                "neutral2" -> colorScheme.neutral2
                else -> return null
            }

        if (shadeKeyStr.contains("+") || shadeKeyStr.contains("-")) {
            val signIndex = shadeKeyStr.indexOfLast { it == '-' || it == '+' }
            // Use the tone of the seed color if it was set explicitly.
            var baseTone =
                if (seedColor != null) colorScheme.seedTone.toFloat()
                else shadeKeyStr.substring(0, signIndex).toFloatOrNull()
            val diff = shadeKeyStr.substring(signIndex).toFloatOrNull()

            if (baseTone == null) {
                logger.w("Failed to parse base tone from $shadeKeyStr")
                return null
            }

            if (diff == null) {
                logger.w("Failed to parse relative tone from $shadeKeyStr")
                return null
            }
            return palette.getAtTone(baseTone + diff)
        } else {
            val shadeKey = shadeKeyStr.toIntOrNull()
            if (shadeKey == null) {
                logger.w("Failed to parse tone from $shadeKeyStr")
                return null
            }
            return palette.allShadesMapped.get(shadeKey) ?: palette.getAtTone(shadeKey.toFloat())
        }
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

    fun resolveColorResourceId(resStr: String): Triple<Resources, Int, Float?>? {
        var (packageName, category, name) = parseResourceId(resStr)

        // Convert relative tonal specifiers to standard
        val relIndex = name.indexOfLast { it == '_' }
        val isToneRelative = name.contains("-") || name.contains("+")
        val targetTone =
            if (packageName != "android") {
                null
            } else if (isToneRelative) {
                val signIndex = name.indexOfLast { it == '-' || it == '+' }
                val baseTone = name.substring(relIndex + 1, signIndex).toFloatOrNull()
                var diff = name.substring(signIndex).toFloatOrNull()
                if (baseTone == null || diff == null) {
                    logger.w("Failed to parse relative tone from $name")
                    return null
                }
                baseTone + diff
            } else {
                val absTone = name.substring(relIndex + 1).toFloatOrNull()
                if (absTone == null) {
                    logger.w("Failed to parse absolute tone from $name")
                    return null
                }
                absTone
            }

        if (
            targetTone != null &&
                (isToneRelative || !TonalPalette.SHADE_KEYS.contains(targetTone.toInt()))
        ) {
            val closeTone = TonalPalette.SHADE_KEYS.minBy { abs(it - targetTone) }
            val prevName = name
            name = name.substring(0, relIndex + 1) + closeTone
            logger.i("Converted $prevName to $name")
        }

        val result = resolveResourceId(packageName, category, name)
        if (result == null) {
            return null
        }

        val (res, resId) = result
        return Triple(res, resId, targetTone)
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
                val colorPair = resolveColorResourceId(path)
                return pair != null || colorPair != null
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
            colorScheme,
            seedColor,
            overrideChroma,
            typefaceCache,
            getThemeSeedColor,
            messageBuffer ?: logger.buffer,
        )

    fun setSeedColor(seedColor: Int?, style: MonetStyle?) {
        this.seedColor = seedColor
        refreshColorPalette(style)
    }

    fun refreshColorPalette(style: MonetStyle?) {
        val seedColor =
            this.seedColor ?: getThemeSeedColor(sysuiCtx).also { logColor("Theme Seed Color", it) }
        this.colorScheme =
            ColorScheme(
                seedColor,
                false, // darkTheme is not used for palette generation
                style ?: MonetStyle.CLOCK,
            )

        // Enforce low chroma on output colors if low chroma theme is selected
        this.overrideChroma = run {
            val cam = colorScheme?.seed?.let { Cam.fromInt(it) }
            if (cam != null && cam.chroma < LOW_CHROMA_LIMIT) {
                return@run cam.chroma * LOW_CHROMA_SCALE
            }
            return@run null
        }
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

    private fun logColor(name: String, color: Int) {
        if (DEBUG_COLOR) {
            val cam = Cam.fromInt(color)
            val tone = CamUtils.lstarFromInt(color)
            logger.i("$name -> (hue: ${cam.hue}, chroma: ${cam.chroma}, tone: $tone)")
        }
    }

    companion object {
        private val DEBUG_COLOR = true
        private val LOW_CHROMA_LIMIT = 15
        private val LOW_CHROMA_SCALE = 1.5f
        private val TAG = AssetLoader::class.simpleName!!

        private fun getThemeSeedColor(ctx: Context): Int {
            return ctx.resources.getColor(android.R.color.system_palette_key_color_primary_light)
        }
    }
}
