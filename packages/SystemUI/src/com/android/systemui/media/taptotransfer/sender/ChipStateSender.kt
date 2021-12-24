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

package com.android.systemui.media.taptotransfer.sender

import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import com.android.systemui.R
import com.android.systemui.media.taptotransfer.common.MediaTttChipState
import java.util.concurrent.Future

/**
 * A class that stores all the information necessary to display the media tap-to-transfer chip on
 * the sender device.
 *
 * This is a sealed class where each subclass represents a specific chip state. Each subclass can
 * contain additional information that is necessary for only that state.
 *
 * @property chipText a string resource for the text that the chip should display.
 * @property otherDeviceName the name of the other device involved in the transfer.
 */
sealed class ChipStateSender(
    appIconDrawable: Drawable,
    @StringRes internal val chipText: Int,
    internal val otherDeviceName: String,
) : MediaTttChipState(appIconDrawable)

/**
 * A state representing that the two devices are close but not close enough to initiate a transfer.
 * The chip will instruct the user to move closer in order to initiate the transfer.
 */
class MoveCloserToTransfer(
    appIconDrawable: Drawable,
    otherDeviceName: String,
) : ChipStateSender(appIconDrawable, R.string.media_move_closer_to_transfer, otherDeviceName)

/**
 * A state representing that a transfer has been initiated (but not completed).
 *
 * @property future a future that will be resolved when the transfer has either succeeded or failed.
 *   If the transfer succeeded, the future can optionally return an undo runnable (see
 *   [TransferSucceeded.undoRunnable]). [MediaTttChipControllerSender] is responsible for transitioning
 *   the chip to the [TransferSucceeded] state if the future resolves successfully.
 */
class TransferInitiated(
    appIconDrawable: Drawable,
    otherDeviceName: String,
    val future: Future<Runnable?>
) : ChipStateSender(appIconDrawable, R.string.media_transfer_playing, otherDeviceName)

/**
 * A state representing that a transfer has been successfully completed.
 *
 * @property undoRunnable if present, the runnable that should be run to undo the transfer. We will
 *   show an Undo button on the chip if this runnable is present.
 */
class TransferSucceeded(
    appIconDrawable: Drawable,
    otherDeviceName: String,
    val undoRunnable: Runnable? = null
) : ChipStateSender(appIconDrawable, R.string.media_transfer_playing, otherDeviceName)
