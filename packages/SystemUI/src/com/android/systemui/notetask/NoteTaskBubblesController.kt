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

package com.android.systemui.notetask

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.UserHandle
import com.android.internal.infra.ServiceConnector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.DebugLogger.debugLog
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A utility class to help interact with [Bubbles] as system user. The SysUI instance running as
 * system user is the only instance that has the instance of [Bubbles] that manages the notes app
 * bubble for all users.
 *
 * <p>Note: This class is made overridable so that a fake can be created for as mocking suspending
 * functions is not supported by the Android tree's version of mockito.
 */
@SysUISingleton
open class NoteTaskBubblesController
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher
) {

    private val serviceConnector: ServiceConnector<INoteTaskBubblesService> =
        ServiceConnector.Impl(
            context,
            Intent(context, NoteTaskBubblesService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY or Context.BIND_NOT_VISIBLE,
            UserHandle.USER_SYSTEM,
            INoteTaskBubblesService.Stub::asInterface
        )

    /** Returns whether notes app bubble is supported. */
    open suspend fun areBubblesAvailable(): Boolean =
        withContext(bgDispatcher) {
            suspendCoroutine { continuation ->
                serviceConnector
                    .postForResult { it.areBubblesAvailable() }
                    .whenComplete { available, error ->
                        if (error != null) {
                            debugLog(error = error) { "Failed to query Bubbles as system user." }
                        }
                        continuation.resume(available ?: false)
                    }
            }
        }

    /** Calls the [Bubbles.showOrHideAppBubble] API as [UserHandle.USER_SYSTEM]. */
    open suspend fun showOrHideAppBubble(
        intent: Intent,
        userHandle: UserHandle,
        icon: Icon
    ) {
        withContext(bgDispatcher) {
            serviceConnector
                .post { it.showOrHideAppBubble(intent, userHandle, icon) }
                .whenComplete { _, error ->
                    if (error != null) {
                        debugLog(error = error) {
                            "Failed to show notes app bubble for intent $intent, " +
                                "user $userHandle, and icon $icon."
                        }
                    } else {
                        debugLog {
                            "Call to show notes app bubble for intent $intent, " +
                                "user $userHandle, and icon $icon successful."
                        }
                    }
                }
        }
    }

    /**
     * A helper service to call [Bubbles] APIs that should always be called from the system user
     * instance of SysUI.
     *
     * <p>Note: This service always runs in the SysUI process running on the system user
     * irrespective of which user started the service. This is required so that the correct instance
     * of {@link Bubbles} is injected. This is set via attribute {@code android:singleUser=”true”}
     * in AndroidManifest.
     */
    class NoteTaskBubblesService
    @Inject
    constructor(private val mOptionalBubbles: Optional<Bubbles>) : Service() {

        override fun onBind(intent: Intent): IBinder {
            return object : INoteTaskBubblesService.Stub() {
                override fun areBubblesAvailable() = mOptionalBubbles.isPresent

                override fun showOrHideAppBubble(
                    intent: Intent,
                    userHandle: UserHandle,
                    icon: Icon
                ) {
                    mOptionalBubbles.ifPresentOrElse(
                        { bubbles -> bubbles.showOrHideAppBubble(intent, userHandle, icon) },
                        {
                            debugLog {
                                "Failed to show or hide bubble for intent $intent," +
                                    "user $user, and icon $icon as bubble is empty."
                            }
                        }
                    )
                }
            }
        }
    }
}
