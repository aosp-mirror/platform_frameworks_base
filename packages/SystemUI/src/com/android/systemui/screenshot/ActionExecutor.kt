/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.app.ActivityOptions
import android.app.BroadcastOptions
import android.app.ExitTransitionCoordinator
import android.app.ExitTransitionCoordinator.ExitTransitionCallbacks
import android.app.PendingIntent
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import android.util.Pair
import android.view.Window
import com.android.app.tracing.coroutines.launch
import com.android.internal.app.ChooserActivity
import com.android.systemui.dagger.qualifiers.Application
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope

class ActionExecutor
@AssistedInject
constructor(
    private val intentExecutor: ActionIntentExecutor,
    @Application private val applicationScope: CoroutineScope,
    @Assisted val window: Window,
    @Assisted val viewProxy: ScreenshotShelfViewProxy,
    @Assisted val finishDismiss: () -> Unit,
) {

    var isPendingSharedTransition = false
        private set

    fun startSharedTransition(intent: Intent, user: UserHandle, overrideTransition: Boolean) {
        isPendingSharedTransition = true
        viewProxy.fadeForSharedTransition()
        val windowTransition = createWindowTransition()
        applicationScope.launch("$TAG#launchIntentAsync") {
            intentExecutor.launchIntent(
                intent,
                user,
                overrideTransition,
                windowTransition.first,
                windowTransition.second
            )
        }
    }

    fun sendPendingIntent(pendingIntent: PendingIntent) {
        try {
            val options = BroadcastOptions.makeBasic()
            options.setInteractive(true)
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            pendingIntent.send(options.toBundle())
            viewProxy.requestDismissal(null)
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Intent cancelled", e)
        }
    }

    /**
     * Supplies the necessary bits for the shared element transition to share sheet. Note that once
     * called, the action intent to share must be sent immediately after.
     */
    private fun createWindowTransition(): Pair<ActivityOptions, ExitTransitionCoordinator> {
        val callbacks: ExitTransitionCallbacks =
            object : ExitTransitionCallbacks {
                override fun isReturnTransitionAllowed(): Boolean {
                    return false
                }

                override fun hideSharedElements() {
                    isPendingSharedTransition = false
                    finishDismiss.invoke()
                }

                override fun onFinish() {}
            }
        return ActivityOptions.startSharedElementAnimation(
            window,
            callbacks,
            null,
            Pair.create(
                viewProxy.screenshotPreview,
                ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            window: Window,
            viewProxy: ScreenshotShelfViewProxy,
            finishDismiss: (() -> Unit)
        ): ActionExecutor
    }

    companion object {
        private const val TAG = "ActionExecutor"
    }
}
