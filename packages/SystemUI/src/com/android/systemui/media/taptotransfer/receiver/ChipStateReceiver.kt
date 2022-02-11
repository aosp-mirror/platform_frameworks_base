/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.media.taptotransfer.receiver

import android.content.Context
import android.graphics.drawable.Drawable
import com.android.systemui.media.taptotransfer.common.MediaTttChipState

/**
 * A class that stores all the information necessary to display the media tap-to-transfer chip on
 * the receiver device.
 *
 * @property appIconDrawable a drawable representing the icon of the app playing the media. If
 *     present, this will be used in [this.getAppIcon] instead of [appPackageName].
 * @property appName a name for the app playing the media. If present, this will be used in
 *     [this.getAppName] instead of [appPackageName].
 */
class ChipStateReceiver(
    appPackageName: String?,
    private val appIconDrawable: Drawable?,
    private val appName: CharSequence?
) : MediaTttChipState(appPackageName) {
    override fun getAppIcon(context: Context): Drawable? {
        if (appIconDrawable != null) {
            return appIconDrawable
        }
        return super.getAppIcon(context)
    }

    override fun getAppName(context: Context): String? {
        if (appName != null) {
            return appName.toString()
        }
        return super.getAppName(context)
    }
}
