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
import com.android.app.tracing.coroutines.runBlocking
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_HIDE_SMART_SPACE
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_INITIALLY_SELECTED_SLOT_ID
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_QUICK_AFFORDANCE_ID
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_SLOT_ID
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_DEFAULT_PREVIEW
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_HIDE_SMART_SPACE
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_PREVIEW_QUICK_AFFORDANCE_SELECTED
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_SLOT_SELECTED
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_START_CUSTOMIZING_QUICK_AFFORDANCES
import com.android.systemui.util.kotlin.logD
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class KeyguardRemotePreviewManager
@Inject
constructor(
    private val previewRendererFactory: KeyguardPreviewRendererFactory,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundHandler: Handler,
) {
    private val activePreviews: ArrayMap<Pair<IBinder?, Int>, PreviewLifecycleObserver> =
        ArrayMap<Pair<IBinder?, Int>, PreviewLifecycleObserver>()

    fun preview(request: Bundle?): Bundle? {
        if (request == null) {
            return null
        }

        var observer: PreviewLifecycleObserver? = null
        return try {
            val renderer =
                runBlocking("$TAG#previewRendererFactory.create", mainDispatcher) {
                    previewRendererFactory.create(request)
                }

            observer =
                PreviewLifecycleObserver(
                    applicationScope,
                    mainDispatcher,
                    renderer,
                    ::destroyObserver,
                )

            logD(TAG) { "Created observer $observer" }

            // Destroy any previous renderer associated with this token.
            activePreviews[renderer.id]?.let { destroyObserver(it) }
            activePreviews[renderer.id] = observer
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
            // NOTE: The process on the other side can retain messenger indefinitely.
            // (e.g. GC might not trigger and cleanup the reference)
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
        observer.onDestroy()?.let { identifier ->
            if (activePreviews[identifier] === observer) {
                activePreviews.remove(identifier)
            }
        }
    }

    companion object {
        internal const val TAG = "KeyguardRemotePreviewManager"
        @VisibleForTesting const val KEY_PREVIEW_SURFACE_PACKAGE = "surface_package"
        @VisibleForTesting const val KEY_PREVIEW_CALLBACK = "callback"
    }
}

/**
 * Handles messages from the other process and handles cleanup.
 *
 * NOTE: The other process might hold on to reference of this class indefinitely. It's entirely
 * possible that GC won't trigger and we'll leak this for all times even if [onDestroy] was called.
 * This helps make sure no non-Singleton objects are retained beyond destruction to prevent leaks.
 */
@VisibleForTesting(VisibleForTesting.PRIVATE)
class PreviewLifecycleObserver(
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    renderer: KeyguardPreviewRenderer,
    onDestroy: (PreviewLifecycleObserver) -> Unit,
) : Handler.Callback, IBinder.DeathRecipient {

    private var isDestroyedOrDestroying = false
    // These two are null after destruction
    @VisibleForTesting var renderer: KeyguardPreviewRenderer?
    @VisibleForTesting var onDestroy: ((PreviewLifecycleObserver) -> Unit)?

    init {
        this.renderer = renderer
        this.onDestroy = onDestroy
    }

    override fun handleMessage(message: Message): Boolean {
        if (isDestroyedOrDestroying) {
            return true
        }

        if (renderer == null || onDestroy == null) {
            Log.wtf(TAG, "Renderer/onDestroy should not be null.")
            return true
        }

        when (message.what) {
            MESSAGE_ID_START_CUSTOMIZING_QUICK_AFFORDANCES -> {
                checkNotNull(renderer)
                    .onStartCustomizingQuickAffordances(
                        initiallySelectedSlotId =
                            message.data.getString(KEY_INITIALLY_SELECTED_SLOT_ID)
                                ?: SLOT_ID_BOTTOM_START
                    )
            }
            MESSAGE_ID_SLOT_SELECTED -> {
                message.data.getString(KEY_SLOT_ID)?.let { slotId ->
                    checkNotNull(renderer).onSlotSelected(slotId = slotId)
                }
            }
            MESSAGE_ID_PREVIEW_QUICK_AFFORDANCE_SELECTED -> {
                val slotId = message.data.getString(KEY_SLOT_ID)
                val quickAffordanceId = message.data.getString(KEY_QUICK_AFFORDANCE_ID)
                if (slotId != null && quickAffordanceId != null) {
                    checkNotNull(renderer)
                        .onPreviewQuickAffordanceSelected(
                            slotId = slotId,
                            quickAffordanceId = quickAffordanceId,
                        )
                }
            }
            MESSAGE_ID_DEFAULT_PREVIEW -> {
                checkNotNull(renderer).onDefaultPreview()
            }
            MESSAGE_ID_HIDE_SMART_SPACE -> {
                checkNotNull(renderer).hideSmartspace(message.data.getBoolean(KEY_HIDE_SMART_SPACE))
            }
            else -> checkNotNull(onDestroy).invoke(this)
        }

        return true
    }

    override fun binderDied() {
        onDestroy?.invoke(this)
    }

    fun onDestroy(): Pair<IBinder?, Int>? {
        if (isDestroyedOrDestroying) {
            return null
        }

        logD(TAG) { "Destroying $this" }

        isDestroyedOrDestroying = true
        return renderer?.let { rendererToDestroy ->
            this.renderer = null
            this.onDestroy = null
            val hostToken = rendererToDestroy.hostToken
            hostToken?.unlinkToDeath(this, 0)
            scope.launch(mainDispatcher) { rendererToDestroy.destroy() }
            rendererToDestroy.id
        }
    }

    companion object {
        private const val TAG = "KeyguardRemotePreviewManager"
    }
}
