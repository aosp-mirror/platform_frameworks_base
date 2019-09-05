/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.android.systemui.plugins.qs.QSTile

private const val TAG = "QSColorController"
private const val QS_COLOR_ICON = "qs_color_icon"
private const val QS_COLOR_ENABLED = "qs_color_enabled"
private const val QS_COLOR_OVERRIDDEN_TILES = "qs_color_overridden_tiles"
private val qsColorIconUri = Settings.System.getUriFor(QS_COLOR_ICON)
private val qsColorEnabledUri = Settings.System.getUriFor(QS_COLOR_ENABLED)
class QSColorController private constructor() {

    private var overrideColor = false
    private var colorIcon = false
    private lateinit var colorCache: SettingBackedMap

    companion object {
        val instance = QSColorController()
        internal fun overrideColor() = instance.overrideColor
        internal fun colorIcon() = instance.colorIcon

        @QSTile.ColorTile
        private fun getColorFromSetting(setting: String): Int {
            return when (setting.toLowerCase()) {
                "red" -> QSTile.COLOR_TILE_RED
                "blue" -> QSTile.COLOR_TILE_BLUE
                "green" -> QSTile.COLOR_TILE_GREEN
                "yellow" -> QSTile.COLOR_TILE_YELLOW
                else -> QSTile.COLOR_TILE_ACCENT
            }
        }
    }

    private fun getBooleanSetting(key: String, default: Boolean = false): Boolean =
            try {
                Settings.System.getInt(contentResolver, key) != 0
            } catch (_: Settings.SettingNotFoundException) {
                default
            }

    private lateinit var tileHost: QSHost
    private lateinit var contentResolver: ContentResolver

    fun initQSTileHost(host: QSHost) {
        tileHost = host
        contentResolver = tileHost.context.contentResolver
        colorCache = SettingBackedMap(contentResolver, mutableMapOf())
        colorIcon = getBooleanSetting(QS_COLOR_ICON)
        overrideColor = getBooleanSetting(QS_COLOR_ENABLED)
        readExistingSettings()
        contentResolver.registerContentObserver(qsColorEnabledUri, true, settingsListener)
        contentResolver.registerContentObserver(qsColorIconUri, false, settingsListener)
    }

    private fun readExistingSettings() {
        Settings.System.getString(contentResolver, QS_COLOR_OVERRIDDEN_TILES)?.split(",")
                ?.mapNotNull { spec ->
            Settings.System.getString(contentResolver, "$QS_COLOR_ENABLED/$spec")?.let {
                spec to it
            }
        }?.forEach {
            modifyTileColor(it.first, getColorFromSetting(it.second))
        }
    }

    private val settingsListener = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri) {
            super.onChange(selfChange, uri)
            when (uri) {
                qsColorIconUri -> colorIcon = getBooleanSetting(QS_COLOR_ICON)
                qsColorEnabledUri -> overrideColor = getBooleanSetting(QS_COLOR_ENABLED)
                else -> {
                    uri.path?.drop("/system/".length)?.let {
                        val color = getColorFromSetting(
                                Settings.System.getString(contentResolver, it) ?: "accent")
                        val tileSpec = uri.lastPathSegment ?: ""
                        modifyTileColor(tileSpec, color)
                    }
                }
            }
        }
    }

    private fun modifyTileColor(spec: String, @QSTile.ColorTile color: Int) {
        Log.w(TAG, "Setting color of tile $spec to $color")
        colorCache.put(spec, color)
        tileHost.tiles.firstOrNull { it.tileSpec == spec }?.setColor(color)
    }

    fun applyColorToTile(tile: QSTile) {
        colorCache.get(tile.tileSpec)?.let {
            modifyTileColor(tile.tileSpec, it)
        }
    }

    fun applyColorToAllTiles() = tileHost.tiles.forEach(::applyColorToTile)

    fun destroy() {
        contentResolver.unregisterContentObserver(settingsListener)
    }

    class SettingBackedMap(
        private val contentResolver: ContentResolver,
        private val map: MutableMap<String, Int>
    ) : MutableMap<String, @QSTile.ColorTile Int> by map {
        override fun put(key: String, @QSTile.ColorTile value: Int): Int? {
            return map.put(key, value).also {
                Settings.System.putString(contentResolver, QS_COLOR_OVERRIDDEN_TILES,
                        map.filterValues { it != QSTile.COLOR_TILE_ACCENT }
                                .keys
                                .joinToString(","))
            }
        }
    }
}
fun overrideColor() = QSColorController.overrideColor()
fun colorIcon() = QSColorController.colorIcon()