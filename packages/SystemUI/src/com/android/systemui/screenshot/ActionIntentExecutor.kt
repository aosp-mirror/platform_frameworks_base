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

package com.android.systemui.screenshot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import android.view.Display
import android.view.IRemoteAnimationFinishedCallback
import android.view.IRemoteAnimationRunner
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.WindowManager
import android.view.WindowManagerGlobal
import com.android.internal.infra.ServiceConnector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class ActionIntentExecutor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val context: Context,
) {
    /**
     * Execute the given intent with startActivity while performing operations for screenshot action
     * launching.
     * - Dismiss the keyguard first
     * - If the userId is not the current user, proxy to a service running as that user to execute
     * - After startActivity, optionally override the pending app transition.
     */
    fun launchIntentAsync(
        intent: Intent,
        bundle: Bundle,
        userId: Int,
        overrideTransition: Boolean,
    ) {
        applicationScope.launch { launchIntent(intent, bundle, userId, overrideTransition) }
    }

    suspend fun launchIntent(
        intent: Intent,
        bundle: Bundle,
        userId: Int,
        overrideTransition: Boolean,
    ) {
        dismissKeyguard()

        if (userId == UserHandle.myUserId()) {
            withContext(mainDispatcher) { context.startActivity(intent, bundle) }
        } else {
            launchCrossProfileIntent(userId, intent, bundle)
        }

        if (overrideTransition) {
            val runner = RemoteAnimationAdapter(SCREENSHOT_REMOTE_RUNNER, 0, 0)
            try {
                WindowManagerGlobal.getWindowManagerService()
                    .overridePendingAppTransitionRemote(runner, Display.DEFAULT_DISPLAY)
            } catch (e: Exception) {
                Log.e(TAG, "Error overriding screenshot app transition", e)
            }
        }
    }

    private val proxyConnector: ServiceConnector<IScreenshotProxy> =
        ServiceConnector.Impl(
            context,
            Intent(context, ScreenshotProxyService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY or Context.BIND_NOT_VISIBLE,
            context.userId,
            IScreenshotProxy.Stub::asInterface,
        )

    private suspend fun dismissKeyguard() {
        val completion = CompletableDeferred<Unit>()
        val onDoneBinder =
            object : IOnDoneCallback.Stub() {
                override fun onDone(success: Boolean) {
                    completion.complete(Unit)
                }
            }
        proxyConnector.post { it.dismissKeyguard(onDoneBinder) }
        completion.await()
    }

    private fun getCrossProfileConnector(userId: Int): ServiceConnector<ICrossProfileService> =
        ServiceConnector.Impl<ICrossProfileService>(
            context,
            Intent(context, ScreenshotCrossProfileService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY or Context.BIND_NOT_VISIBLE,
            userId,
            ICrossProfileService.Stub::asInterface,
        )

    private suspend fun launchCrossProfileIntent(userId: Int, intent: Intent, bundle: Bundle) {
        val connector = getCrossProfileConnector(userId)
        val completion = CompletableDeferred<Unit>()
        connector.post {
            it.launchIntent(intent, bundle)
            completion.complete(Unit)
        }
        completion.await()
    }
}

private const val TAG: String = "ActionIntentExecutor"
private const val SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)"

/**
 * This is effectively a no-op, but we need something non-null to pass in, in order to successfully
 * override the pending activity entrance animation.
 */
private val SCREENSHOT_REMOTE_RUNNER: IRemoteAnimationRunner.Stub =
    object : IRemoteAnimationRunner.Stub() {
        override fun onAnimationStart(
            @WindowManager.TransitionOldType transit: Int,
            apps: Array<RemoteAnimationTarget>,
            wallpapers: Array<RemoteAnimationTarget>,
            nonApps: Array<RemoteAnimationTarget>,
            finishedCallback: IRemoteAnimationFinishedCallback,
        ) {
            try {
                finishedCallback.onAnimationFinished()
            } catch (e: RemoteException) {
                Log.e(TAG, "Error finishing screenshot remote animation", e)
            }
        }

        override fun onAnimationCancelled(isKeyguardOccluded: Boolean) {}
    }
