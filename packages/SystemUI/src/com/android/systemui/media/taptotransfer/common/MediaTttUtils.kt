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
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import com.android.systemui.res.R
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.TintedIcon
import com.android.systemui.temporarydisplay.chipbar.ChipbarInfo.Companion.DEFAULT_ICON_TINT

/** Utility methods for media tap-to-transfer. */
class MediaTttUtils {
    companion object {
        const val WINDOW_TITLE_SENDER = "Media Transfer Chip View (Sender)"
        const val WINDOW_TITLE_RECEIVER = "Media Transfer Chip View (Receiver)"

        const val WAKE_REASON_SENDER = "MEDIA_TRANSFER_ACTIVATED_SENDER"
        const val WAKE_REASON_RECEIVER = "MEDIA_TRANSFER_ACTIVATED_RECEIVER"

        /**
         * Returns the information needed to display the icon.
         *
         * The information will either contain app name and icon of the app playing media, or a
         * default name and icon if we can't find the app name/icon.
         *
         * @param appPackageName the package name of the app playing the media.
         * @param onPackageNotFoundException a function run if a
         *   [PackageManager.NameNotFoundException] occurs.
         * @param isReceiver indicates whether the icon is displayed in a receiver view.
         */
        fun getIconInfoFromPackageName(
            context: Context,
            appPackageName: String?,
            isReceiver: Boolean,
            onPackageNotFoundException: () -> Unit,
        ): IconInfo {
            if (appPackageName != null) {
                val packageManager = context.packageManager
                try {
                    val appName =
                        packageManager
                            .getApplicationInfo(
                                appPackageName,
                                PackageManager.ApplicationInfoFlags.of(0),
                            )
                            .loadLabel(packageManager)
                            .toString()
                    val contentDescription =
                        if (isReceiver) {
                            ContentDescription.Loaded(
                                context.getString(
                                    R.string
                                        .media_transfer_receiver_content_description_with_app_name,
                                    appName
                                )
                            )
                        } else {
                            ContentDescription.Loaded(appName)
                        }
                    return IconInfo(
                        contentDescription,
                        MediaTttIcon.Loaded(packageManager.getApplicationIcon(appPackageName)),
                        tint = null,
                        isAppIcon = true
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    onPackageNotFoundException.invoke()
                }
            }
            return IconInfo(
                if (isReceiver) {
                    ContentDescription.Resource(
                        R.string.media_transfer_receiver_content_description_unknown_app
                    )
                } else {
                    ContentDescription.Resource(
                        R.string.media_output_dialog_unknown_launch_app_name
                    )
                },
                MediaTttIcon.Resource(R.drawable.ic_cast),
                tint = DEFAULT_ICON_TINT,
                isAppIcon = false
            )
        }
    }
}

/** Stores all the information for an icon shown with media TTT. */
data class IconInfo(
    val contentDescription: ContentDescription,
    val icon: MediaTttIcon,
    @AttrRes val tint: Int?,
    /**
     * True if [drawable] is the app's icon, and false if [drawable] is some generic default icon.
     */
    val isAppIcon: Boolean
) {
    /** Converts this into a [TintedIcon]. */
    fun toTintedIcon(): TintedIcon {
        val iconOutput =
            when (icon) {
                is MediaTttIcon.Loaded -> Icon.Loaded(icon.drawable, contentDescription)
                is MediaTttIcon.Resource -> Icon.Resource(icon.res, contentDescription)
            }
        return TintedIcon(iconOutput, tint)
    }
}

/**
 * Mimics [com.android.systemui.common.shared.model.Icon] but without the content description, since
 * the content description may need to be overridden.
 */
sealed interface MediaTttIcon {
    data class Loaded(val drawable: Drawable) : MediaTttIcon
    data class Resource(@DrawableRes val res: Int) : MediaTttIcon
}
