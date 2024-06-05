/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.ui.binder

import android.text.TextUtils
import android.util.PluralsMessageFormatter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.keyguard.BouncerKeyguardMessageArea
import com.android.keyguard.KeyguardMessageArea
import com.android.keyguard.KeyguardMessageAreaController
import com.android.systemui.Flags
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor
import com.android.systemui.bouncer.shared.model.Message
import com.android.systemui.bouncer.ui.BouncerMessageView
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.BouncerLogger
import kotlinx.coroutines.launch

object BouncerMessageViewBinder {
    @JvmStatic
    fun bind(
        view: BouncerMessageView,
        interactor: BouncerMessageInteractor,
        factory: KeyguardMessageAreaController.Factory,
        bouncerLogger: BouncerLogger,
    ) {
        view.repeatWhenAttached {
            if (!Flags.revampedBouncerMessages()) {
                view.primaryMessageView?.disable()
                view.secondaryMessageView?.disable()
                return@repeatWhenAttached
            }
            view.init(factory)
            view.primaryMessage?.setIsVisible(true)
            view.secondaryMessage?.setIsVisible(true)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bouncerLogger.startBouncerMessageInteractor()
                launch {
                    interactor.bouncerMessage.collect {
                        bouncerLogger.bouncerMessageUpdated(it)
                        updateView(
                            view.primaryMessage,
                            view.primaryMessageView,
                            message = it?.message,
                            allowTruncation = true,
                        )
                        updateView(
                            view.secondaryMessage,
                            view.secondaryMessageView,
                            message = it?.secondaryMessage,
                            allowTruncation = false,
                        )
                        view.requestLayout()
                    }
                }
            }
        }
    }

    private fun updateView(
        controller: KeyguardMessageAreaController<KeyguardMessageArea>?,
        view: BouncerKeyguardMessageArea?,
        message: Message?,
        allowTruncation: Boolean = false,
    ) {
        if (view == null || controller == null) return
        if (message?.message != null || message?.messageResId != null) {
            controller.setIsVisible(true)
            var newMessage = message.message ?: ""
            if (message.messageResId != null && message.messageResId != 0) {
                newMessage = view.resources.getString(message.messageResId)
                if (message.formatterArgs != null) {
                    newMessage =
                        PluralsMessageFormatter.format(
                            view.resources,
                            message.formatterArgs,
                            message.messageResId
                        )
                }
            }
            controller.setMessage(newMessage, message.animate)
        } else {
            controller.setIsVisible(false)
            controller.setMessage(0)
        }
        message?.colorState?.let { controller.setNextMessageColor(it) }
        view.ellipsize =
            if (allowTruncation) TextUtils.TruncateAt.END else TextUtils.TruncateAt.MARQUEE
    }
}
