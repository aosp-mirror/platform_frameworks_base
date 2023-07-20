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
 *
 */

package com.android.systemui.keyguard.ui.preview

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.ArrayMap
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardQuickAffordancePreviewConstants
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking

@SysUISingleton
class KeyguardRemotePreviewManager
@Inject
constructor(
    private val previewRendererFactory: KeyguardPreviewRendererFactory,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundHandler: Handler,
) {
    private val activePreviews: ArrayMap<IBinder, PreviewLifecycleObserver> =
        ArrayMap<IBinder, PreviewLifecycleObserver>()

    fun preview(request: Bundle?): Bundle? {
        if (request == null) {
            return null
        }

        var observer: PreviewLifecycleObserver? = null
        return try {
            val renderer = previewRendererFactory.create(request)

            // Destroy any previous renderer associated with this token.
            activePreviews[renderer.hostToken]?.let { destroyObserver(it) }
            observer = PreviewLifecycleObserver(renderer, mainDispatcher, ::destroyObserver)
            activePreviews[renderer.hostToken] = observer
            renderer.render()
            renderer.hostToken?.linkToDeath(observer, 0)
            val result = Bundle()
            result.putParcelable(
                KEY_PREVIEW_SURFACE_PACKAGE,
                renderer.surfacePackage,
            )
            val messenger =
                Messenger(
                    Handler(
                        backgroundHandler.looper,
                        observer,
                    )
                )
            val msg = Message.obtain()
            msg.replyTo = messenger
            result.putParcelable(KEY_PREVIEW_CALLBACK, msg)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Unable to generate preview", e)
            observer?.let { destroyObserver(it) }
            null
        }
    }

    private fun destroyObserver(observer: PreviewLifecycleObserver) {
        observer.onDestroy()?.let { hostToken ->
            if (activePreviews[hostToken] === observer) {
                activePreviews.remove(hostToken)
            }
        }
    }

    private class PreviewLifecycleObserver(
        private val renderer: KeyguardPreviewRenderer,
        private val mainDispatcher: CoroutineDispatcher,
        private val requestDestruction: (PreviewLifecycleObserver) -> Unit,
    ) : Handler.Callback, IBinder.DeathRecipient {

        private var isDestroyed = false

        override fun handleMessage(message: Message): Boolean {
            when (message.what) {
                KeyguardQuickAffordancePreviewConstants.MESSAGE_ID_SLOT_SELECTED -> {
                    message.data
                        .getString(
                            KeyguardQuickAffordancePreviewConstants.KEY_SLOT_ID,
                        )
                        ?.let { slotId -> renderer.onSlotSelected(slotId = slotId) }
                }
                else -> requestDestruction(this)
            }

            return true
        }

        override fun binderDied() {
            requestDestruction(this)
        }

        fun onDestroy(): IBinder? {
            if (isDestroyed) {
                return null
            }

            isDestroyed = true
            val hostToken = renderer.hostToken
            hostToken?.unlinkToDeath(this, 0)
            try {
                runBlocking(mainDispatcher) { renderer.destroy() }
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while destroying observer", e)
            }
            return hostToken
        }
    }

    companion object {
        private const val TAG = "KeyguardRemotePreviewManager"
        @VisibleForTesting const val KEY_PREVIEW_SURFACE_PACKAGE = "surface_package"
        @VisibleForTesting const val KEY_PREVIEW_CALLBACK = "callback"
    }
}
