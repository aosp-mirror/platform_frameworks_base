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
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon

/** Utility methods for media tap-to-transfer. */
class MediaTttUtils {
    companion object {
        const val WINDOW_TITLE_SENDER = "Media Transfer Chip View (Sender)"
        const val WINDOW_TITLE_RECEIVER = "Media Transfer Chip View (Receiver)"

        const val WAKE_REASON_SENDER = "MEDIA_TRANSFER_ACTIVATED_SENDER"
        const val WAKE_REASON_RECEIVER = "MEDIA_TRANSFER_ACTIVATED_RECEIVER"

        /**
         * Returns the information needed to display the icon in [Icon] form.
         *
         * See [getIconInfoFromPackageName].
         */
        fun getIconFromPackageName(
            context: Context,
            appPackageName: String?,
            logger: MediaTttLogger,
        ): Icon {
            val iconInfo = getIconInfoFromPackageName(context, appPackageName, logger)
            return Icon.Loaded(
                iconInfo.drawable,
                ContentDescription.Loaded(iconInfo.contentDescription)
            )
        }

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
