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

package com.android.systemui.clipboardoverlay

import android.app.ActivityOptions
import android.app.ExitTransitionCoordinator
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import android.util.Log
import android.util.Pair
import android.view.IRemoteAnimationFinishedCallback
import android.view.IRemoteAnimationRunner
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.View
import android.view.Window
import android.view.WindowManagerGlobal
import com.android.internal.app.ChooserActivity
import com.android.systemui.settings.DisplayTracker
import javax.inject.Inject

class ClipboardTransitionExecutor
@Inject
constructor(val context: Context, val displayTracker: DisplayTracker) {
    fun startSharedTransition(window: Window, view: View, intent: Intent, onReady: Runnable) {
        val transition: Pair<ActivityOptions, ExitTransitionCoordinator> =
            ActivityOptions.startSharedElementAnimation(
                window,
                object : ExitTransitionCoordinator.ExitTransitionCallbacks {
                    override fun isReturnTransitionAllowed(): Boolean {
                        return false
                    }

                    override fun hideSharedElements() {
                        onReady.run()
                    }

                    override fun onFinish() {}
                },
                null,
                Pair.create(view, ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME)
            )
        transition.second.startExit()
        context.startActivity(intent, transition.first.toBundle())
        val runner = RemoteAnimationAdapter(NULL_ACTIVITY_TRANSITION, 0, 0)
        try {
            checkNotNull(WindowManagerGlobal.getWindowManagerService())
                .overridePendingAppTransitionRemote(runner, displayTracker.defaultDisplayId)
        } catch (e: Exception) {
            Log.e(TAG, "Error overriding clipboard app transition", e)
        }
    }

    private val TAG: String = "ClipboardTransitionExec"

    /**
     * This is effectively a no-op, but we need something non-null to pass in, in order to
     * successfully override the pending activity entrance animation.
     */
    private val NULL_ACTIVITY_TRANSITION: IRemoteAnimationRunner.Stub =
        object : IRemoteAnimationRunner.Stub() {
            override fun onAnimationStart(
                transit: Int,
                apps: Array<RemoteAnimationTarget>,
                wallpapers: Array<RemoteAnimationTarget>,
                nonApps: Array<RemoteAnimationTarget>,
                finishedCallback: IRemoteAnimationFinishedCallback
            ) {
                try {
                    finishedCallback.onAnimationFinished()
                } catch (e: RemoteException) {
                    Log.e(TAG, "Error finishing screenshot remote animation", e)
                }
            }

            override fun onAnimationCancelled() {}
        }
}
