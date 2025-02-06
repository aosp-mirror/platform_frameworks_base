/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row.shared

import android.annotation.IdRes
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.drawable.Icon
import android.net.Uri

/**
 * This is a representation of an [Icon] that supports the [equals] and [hashCode] semantics
 * required for use as a map key, or for change detection.
 */
sealed class IconData {
    abstract fun toIcon(): Icon

    class BitmapIcon(val sourceBitmap: Bitmap, val isAdaptive: Boolean, val tint: IconTint?) :
        IconData() {
        override fun toIcon(): Icon =
            if (isAdaptive) {
                    Icon.createWithAdaptiveBitmap(sourceBitmap)
                } else {
                    Icon.createWithBitmap(sourceBitmap)
                }
                .withTint(tint)

        override fun equals(other: Any?): Boolean =
            when (other) {
                null -> false
                (other === this) -> true
                !is BitmapIcon -> false
                else ->
                    other.isAdaptive == isAdaptive &&
                        other.tint == tint &&
                        other.sourceBitmap.sameAs(sourceBitmap)
            }

        override fun hashCode(): Int {
            var result = sourceBitmap.width
            result = 31 * result + sourceBitmap.height
            result = 31 * result + isAdaptive.hashCode()
            result = 31 * result + (tint?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "BitmapIcon(sourceBitmap=$sourceBitmap, isAdaptive=$isAdaptive, tint=$tint)"
    }

    data class ResourceIcon(val packageName: String, @IdRes val resId: Int, val tint: IconTint?) :
        IconData() {
        @SuppressLint("ResourceType")
        override fun toIcon(): Icon = Icon.createWithResource(packageName, resId).withTint(tint)
    }

    class DataIcon(val data: ByteArray, val tint: IconTint?) : IconData() {
        override fun toIcon(): Icon = Icon.createWithData(data, 0, data.size).withTint(tint)

        override fun equals(other: Any?): Boolean =
            when (other) {
                null -> false
                (other === this) -> true
                !is DataIcon -> false
                else -> other.data.contentEquals(data) && other.tint == tint
            }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + (tint?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "DataIcon(data.size=${data.size}, data.hashCode=${data.contentHashCode()}, tint=$tint)"
    }

    data class UriIcon(val uri: Uri, val isAdaptive: Boolean, val tint: IconTint?) : IconData() {

        override fun toIcon(): Icon =
            if (isAdaptive) {
                    Icon.createWithAdaptiveBitmapContentUri(uri)
                } else {
                    Icon.createWithContentUri(uri)
                }
                .withTint(tint)
    }

    companion object {
        fun fromIcon(icon: Icon): IconData? {
            val tint = icon.tintList?.let { tintList -> IconTint(tintList, icon.tintBlendMode) }
            return when (icon.type) {
                Icon.TYPE_BITMAP ->
                    icon.bitmap?.let { bitmap -> BitmapIcon(bitmap, isAdaptive = false, tint) }
                Icon.TYPE_ADAPTIVE_BITMAP ->
                    icon.bitmap?.let { bitmap -> BitmapIcon(bitmap, isAdaptive = true, tint) }
                Icon.TYPE_URI -> UriIcon(icon.uri, isAdaptive = false, tint)
                Icon.TYPE_URI_ADAPTIVE_BITMAP -> UriIcon(icon.uri, isAdaptive = true, tint)
                Icon.TYPE_RESOURCE -> ResourceIcon(icon.resPackage, icon.resId, tint)
                Icon.TYPE_DATA -> icon.safeData?.let { data -> DataIcon(data, tint) }
                else -> null
            }
        }

        private val Icon.safeData: ByteArray?
            get() {
                val dataBytes = dataBytes
                val dataLength = dataLength
                val dataOffset = dataOffset
                if (dataOffset == 0 && dataLength == dataBytes.size) {
                    return dataBytes
                }
                if (dataLength < dataBytes.size - dataOffset) {
                    return null
                }
                return dataBytes.copyOfRange(dataOffset, dataOffset + dataLength)
            }

        private fun Icon.withTint(tint: IconTint?): Icon {
            if (tint != null) {
                tintList = tint.tintList
                tintBlendMode = tint.blendMode
            }
            return this
        }
    }
}

data class IconTint(val tintList: ColorStateList, val blendMode: BlendMode)
