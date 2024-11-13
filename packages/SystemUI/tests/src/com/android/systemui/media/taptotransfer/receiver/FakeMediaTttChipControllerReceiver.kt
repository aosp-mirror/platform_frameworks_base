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

package com.android.systemui.media.taptotransfer.receiver

import android.content.Context
import android.os.Handler
import android.os.PowerManager
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryViewUiEventLogger
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.wakelock.WakeLock

class FakeMediaTttChipControllerReceiver(
    commandQueue: CommandQueue,
    context: Context,
    logger: MediaTttReceiverLogger,
    viewCaptureAwareWindowManager: ViewCaptureAwareWindowManager,
    mainExecutor: DelayableExecutor,
    accessibilityManager: AccessibilityManager,
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    powerManager: PowerManager,
    mainHandler: Handler,
    mediaTttFlags: MediaTttFlags,
    uiEventLogger: MediaTttReceiverUiEventLogger,
    viewUtil: ViewUtil,
    wakeLockBuilder: WakeLock.Builder,
    systemClock: SystemClock,
    rippleController: MediaTttReceiverRippleController,
    temporaryViewUiEventLogger: TemporaryViewUiEventLogger,
) :
    MediaTttChipControllerReceiver(
        commandQueue,
        context,
        logger,
        viewCaptureAwareWindowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        dumpManager,
        powerManager,
        mainHandler,
        mediaTttFlags,
        uiEventLogger,
        viewUtil,
        wakeLockBuilder,
        systemClock,
        rippleController,
        temporaryViewUiEventLogger,
    ) {
    override fun animateViewOut(view: ViewGroup, removalReason: String?, onAnimationEnd: Runnable) {
        // Just bypass the animation in tests
        onAnimationEnd.run()
    }
}
