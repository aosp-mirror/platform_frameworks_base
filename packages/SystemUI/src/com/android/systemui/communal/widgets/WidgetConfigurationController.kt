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

package com.android.systemui.communal.widgets

import android.app.Activity
import android.app.ActivityOptions
import android.content.IntentSender
import android.os.OutcomeReceiver
import android.window.SplashScreen
import androidx.activity.ComponentActivity
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.nullableAtomicReference
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Handles starting widget configuration activities and receiving the response to determine if
 * configuration was successful.
 */
class WidgetConfigurationController
@AssistedInject
constructor(
    @Assisted private val activity: ComponentActivity,
    private val appWidgetHostLazy: Lazy<CommunalAppWidgetHost>,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper,
    private val glanceableHubWidgetManagerLazy: Lazy<GlanceableHubWidgetManager>,
    @Main private val mainExecutor: Executor,
) : WidgetConfigurator, OutcomeReceiver<IntentSender?, Throwable> {
    @AssistedFactory
    fun interface Factory {
        fun create(activity: ComponentActivity): WidgetConfigurationController
    }

    private val activityOptions: ActivityOptions
        get() =
            ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                splashScreenStyle = SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR
            }

    private var result: CompletableDeferred<Boolean>? by nullableAtomicReference()

    override suspend fun configureWidget(appWidgetId: Int): Boolean =
        withContext(bgDispatcher) {
            if (result != null) {
                throw IllegalStateException("There is already a pending configuration")
            }
            result = CompletableDeferred()

            try {
                if (
                    !glanceableHubMultiUserHelper.glanceableHubHsumFlagEnabled ||
                        !glanceableHubMultiUserHelper.isInHeadlessSystemUser()
                ) {
                    // Start configuration activity directly if we're running in a foreground user
                    with(appWidgetHostLazy.get()) {
                        startAppWidgetConfigureActivityForResult(
                            activity,
                            appWidgetId,
                            0,
                            REQUEST_CODE,
                            activityOptions.toBundle(),
                        )
                    }
                } else {
                    with(glanceableHubWidgetManagerLazy.get()) {
                        // Use service to get intent sender and start configuration activity
                        // locally if running in a headless system user
                        getIntentSenderForConfigureActivity(
                            appWidgetId,
                            outcomeReceiver = this@WidgetConfigurationController,
                            mainExecutor,
                        )
                    }
                }
            } catch (_: Exception) {
                setConfigurationResult(Activity.RESULT_CANCELED)
            }
            val value = result?.await() == true
            result = null
            return@withContext value
        }

    // Called when an intent sender is returned, and the configuration activity should be started.
    override fun onResult(intentSender: IntentSender?) {
        if (intentSender == null) {
            setConfigurationResult(Activity.RESULT_CANCELED)
            return
        }

        activity.startIntentSenderForResult(
            intentSender,
            REQUEST_CODE,
            /* fillInIntent = */ null,
            /* flagsMask = */ 0,
            /* flagsValues = */ 0,
            /* extraFlags = */ 0,
            activityOptions.toBundle(),
        )
    }

    // Called when there is an error getting the intent sender.
    override fun onError(e: Throwable) {
        setConfigurationResult(Activity.RESULT_CANCELED)
    }

    fun setConfigurationResult(resultCode: Int) {
        result?.complete(resultCode == Activity.RESULT_OK)
    }

    companion object {
        const val REQUEST_CODE = 100
    }
}
