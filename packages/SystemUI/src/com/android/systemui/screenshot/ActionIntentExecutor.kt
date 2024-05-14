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

import android.app.ActivityOptions
import android.app.ExitTransitionCoordinator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process.myUserHandle
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import android.view.IRemoteAnimationFinishedCallback
import android.view.IRemoteAnimationRunner
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.WindowManager
import android.view.WindowManagerGlobal
import com.android.app.tracing.coroutines.launch
import com.android.internal.infra.ServiceConnector
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.screenshot.proxy.SystemUiProxy
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.statusbar.phone.CentralSurfaces
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

@SysUISingleton
class ActionIntentExecutor
@Inject
constructor(
    private val context: Context,
    private val activityManagerWrapper: ActivityManagerWrapper,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val systemUiProxy: SystemUiProxy,
    private val displayTracker: DisplayTracker,
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
        user: UserHandle,
        overrideTransition: Boolean,
        options: ActivityOptions?,
        transitionCoordinator: ExitTransitionCoordinator?,
    ) {
        applicationScope.launch("$TAG#launchIntentAsync") {
            launchIntent(intent, user, overrideTransition, options, transitionCoordinator)
        }
    }

    suspend fun launchIntent(
        intent: Intent,
        user: UserHandle,
        overrideTransition: Boolean,
        options: ActivityOptions?,
        transitionCoordinator: ExitTransitionCoordinator?,
    ) {
        if (Flags.fixScreenshotActionDismissSystemWindows()) {
            activityManagerWrapper.closeSystemWindows(
                CentralSurfaces.SYSTEM_DIALOG_REASON_SCREENSHOT
            )
        }
        systemUiProxy.dismissKeyguard()
        transitionCoordinator?.startExit()

        if (user == myUserHandle()) {
            withContext(mainDispatcher) { context.startActivity(intent, options?.toBundle()) }
        } else {
            launchCrossProfileIntent(user, intent, options?.toBundle())
        }

        if (overrideTransition) {
            val runner = RemoteAnimationAdapter(SCREENSHOT_REMOTE_RUNNER, 0, 0)
            try {
                checkNotNull(WindowManagerGlobal.getWindowManagerService())
                    .overridePendingAppTransitionRemote(runner, displayTracker.defaultDisplayId)
            } catch (e: Exception) {
                Log.e(TAG, "Error overriding screenshot app transition", e)
            }
        }
    }

    private fun getCrossProfileConnector(user: UserHandle): ServiceConnector<ICrossProfileService> =
        ServiceConnector.Impl<ICrossProfileService>(
            context,
            Intent(context, ScreenshotCrossProfileService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY or Context.BIND_NOT_VISIBLE,
            user.identifier,
            ICrossProfileService.Stub::asInterface,
        )

    private suspend fun launchCrossProfileIntent(
        user: UserHandle,
        intent: Intent,
        bundle: Bundle?
    ) {
        val connector = getCrossProfileConnector(user)
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

        override fun onAnimationCancelled() {}
    }
