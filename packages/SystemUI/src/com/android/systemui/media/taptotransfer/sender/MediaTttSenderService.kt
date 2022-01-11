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

package com.android.systemui.media.taptotransfer.sender

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import android.os.IBinder
import com.android.systemui.R
import com.android.systemui.shared.mediattt.DeviceInfo
import com.android.systemui.shared.mediattt.IDeviceSenderCallback
import javax.inject.Inject

/**
 * Service that allows external handlers to trigger the media chip on the sender device.
 */
class MediaTttSenderService @Inject constructor(
    context: Context,
    val controller: MediaTttChipControllerSender
) : Service() {

    // TODO(b/203800643): Add logging when callbacks trigger.
    private val binder: IBinder = object : IDeviceSenderCallback.Stub() {
        override fun closeToReceiverToStartCast(
            mediaInfo: MediaRoute2Info, otherDeviceInfo: DeviceInfo
        ) {
            this@MediaTttSenderService.closeToReceiverToStartCast(mediaInfo, otherDeviceInfo)
        }
    }

    // TODO(b/203800643): Use the app icon from the media info instead of a fake one.
    private val fakeAppIconDrawable =
        Icon.createWithResource(context, R.drawable.ic_avatar_user).loadDrawable(context).also {
            it.setTint(Color.YELLOW)
        }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun closeToReceiverToStartCast(
        mediaInfo: MediaRoute2Info, otherDeviceInfo: DeviceInfo
    ) {
        val chipState = MoveCloserToStartCast(
            appIconDrawable = fakeAppIconDrawable,
            appIconContentDescription = mediaInfo.name.toString(),
            otherDeviceName = otherDeviceInfo.name
        )
        controller.displayChip(chipState)
    }
}
