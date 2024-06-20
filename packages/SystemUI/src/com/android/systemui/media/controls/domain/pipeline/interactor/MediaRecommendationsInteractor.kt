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

package com.android.systemui.media.controls.domain.pipeline.interactor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.Expandable
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.MediaDataProcessor
import com.android.systemui.media.controls.shared.model.MediaRecModel
import com.android.systemui.media.controls.shared.model.MediaRecommendationsModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.plugins.ActivityStarter
import java.net.URISyntaxException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business logic for media recommendation */
@SysUISingleton
class MediaRecommendationsInteractor
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    private val repository: MediaFilterRepository,
    private val mediaDataProcessor: MediaDataProcessor,
    private val broadcastSender: BroadcastSender,
    private val activityStarter: ActivityStarter,
) {

    val recommendations: Flow<MediaRecommendationsModel> =
        repository.smartspaceMediaData.map { toRecommendationsModel(it) }.distinctUntilChanged()

    /** Indicates whether the recommendations card is active. */
    val isActive: StateFlow<Boolean> =
        repository.smartspaceMediaData
            .map { it.isActive }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), false)

    val onAnyMediaConfigurationChange: Flow<Unit> = repository.onAnyMediaConfigurationChange

    fun removeMediaRecommendations(key: String, dismissIntent: Intent?, delayMs: Long) {
        mediaDataProcessor.dismissSmartspaceRecommendation(key, delayMs)
        if (dismissIntent == null) {
            Log.w(TAG, "Cannot create dismiss action click action: extras missing dismiss_intent.")
            return
        }

        val className = dismissIntent.component?.className
        if (className == EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME) {
            // Dismiss the card Smartspace data through Smartspace trampoline activity.
            applicationContext.startActivity(dismissIntent)
        } else {
            broadcastSender.sendBroadcast(dismissIntent)
        }
    }

    fun startSettings() {
        activityStarter.startActivity(SETTINGS_INTENT, /* dismissShade= */ true)
    }

    fun startClickIntent(expandable: Expandable, intent: Intent) {
        if (shouldActivityOpenInForeground(intent)) {
            // Request to unlock the device if the activity needs to be opened in foreground.
            activityStarter.postStartActivityDismissingKeyguard(
                intent,
                0 /* delay */,
                expandable.activityTransitionController(
                    InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER
                )
            )
        } else {
            // Otherwise, open the activity in background directly.
            applicationContext.startActivity(intent)
        }
    }

    /** Returns if the action will open the activity in foreground. */
    private fun shouldActivityOpenInForeground(intent: Intent): Boolean {
        val intentString = intent.extras?.getString(EXTRAS_SMARTSPACE_INTENT) ?: return false
        try {
            val wrapperIntent = Intent.parseUri(intentString, Intent.URI_INTENT_SCHEME)
            return wrapperIntent.getBooleanExtra(KEY_SMARTSPACE_OPEN_IN_FOREGROUND, false)
        } catch (e: URISyntaxException) {
            Log.wtf(TAG, "Failed to create intent from URI: $intentString")
            e.printStackTrace()
        }
        return false
    }

    private fun toRecommendationsModel(data: SmartspaceMediaData): MediaRecommendationsModel {
        val mediaRecs = ArrayList<MediaRecModel>()
        data.recommendations.forEach {
            with(it) { mediaRecs.add(MediaRecModel(intent, title, subtitle, icon, extras)) }
        }
        return with(data) {
            MediaRecommendationsModel(
                key = targetId,
                uid = getUid(applicationContext),
                packageName = packageName,
                instanceId = instanceId,
                appName = getAppName(applicationContext),
                dismissIntent = dismissIntent,
                areRecommendationsValid = isValid(),
                mediaRecs = mediaRecs,
            )
        }
    }

    fun switchToMediaControl(packageName: String) {
        repository.setMediaFromRecPackageName(packageName)
    }

    companion object {

        private const val TAG = "MediaRecommendationsInteractor"

        // TODO (b/237284176) : move AGSA reference out.
        private const val EXTRAS_SMARTSPACE_INTENT =
            "com.google.android.apps.gsa.smartspace.extra.SMARTSPACE_INTENT"
        @VisibleForTesting
        const val EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME =
            "com.google.android.apps.gsa.staticplugins.opa.smartspace." +
                "ExportedSmartspaceTrampolineActivity"

        private const val KEY_SMARTSPACE_OPEN_IN_FOREGROUND = "KEY_OPEN_IN_FOREGROUND"

        private val SETTINGS_INTENT = Intent(Settings.ACTION_MEDIA_CONTROLS_SETTINGS)
    }
}
