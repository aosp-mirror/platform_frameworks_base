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

package com.android.systemui.media.taptotransfer.common

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.android.internal.widget.CachingIconView
import com.android.settingslib.Utils
import com.android.systemui.R

/** Utility methods for media tap-to-transfer. */
class MediaTttUtils {
    companion object {
        // Used in CTS tests UpdateMediaTapToTransferSenderDisplayTest and
        // UpdateMediaTapToTransferReceiverDisplayTest
        const val WINDOW_TITLE = "Media Transfer Chip View"
        const val WAKE_REASON = "MEDIA_TRANSFER_ACTIVATED"

        /**
         * Returns the information needed to display the icon.
         *
         * The information will either contain app name and icon of the app playing media, or a
         * default name and icon if we can't find the app name/icon.
         *
         * @param appPackageName the package name of the app playing the media.
         * @param logger the logger to use for any errors.
         */
        fun getIconInfoFromPackageName(
            context: Context,
            appPackageName: String?,
            logger: MediaTttLogger
        ): IconInfo {
            if (appPackageName != null) {
                try {
                    val contentDescription =
                        context.packageManager
                            .getApplicationInfo(
                                appPackageName,
                                PackageManager.ApplicationInfoFlags.of(0)
                            )
                            .loadLabel(context.packageManager)
                            .toString()
                    return IconInfo(
                        contentDescription,
                        drawable = context.packageManager.getApplicationIcon(appPackageName),
                        isAppIcon = true
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    logger.logPackageNotFound(appPackageName)
                }
            }
            return IconInfo(
                contentDescription =
                    context.getString(R.string.media_output_dialog_unknown_launch_app_name),
                drawable =
                    context.resources.getDrawable(R.drawable.ic_cast).apply {
                        this.setTint(
                            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary)
                        )
                    },
                isAppIcon = false
            )
        }

        /**
         * Sets an icon to be displayed by the given view.
         *
         * @param iconSize the size in pixels that the icon should be. If null, the size of
         * [appIconView] will not be adjusted.
         */
        fun setIcon(
            appIconView: CachingIconView,
            icon: Drawable,
            iconContentDescription: CharSequence,
            iconSize: Int? = null,
        ) {
            iconSize?.let { size ->
                val lp = appIconView.layoutParams
                lp.width = size
                lp.height = size
                appIconView.layoutParams = lp
            }

            appIconView.contentDescription = iconContentDescription
            appIconView.setImageDrawable(icon)
        }
    }
}

data class IconInfo(
    val contentDescription: String,
    val drawable: Drawable,
    /**
     * True if [drawable] is the app's icon, and false if [drawable] is some generic default icon.
     */
    val isAppIcon: Boolean
)
