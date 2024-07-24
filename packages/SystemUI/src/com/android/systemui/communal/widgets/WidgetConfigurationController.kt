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
import android.content.ActivityNotFoundException
import android.window.SplashScreen
import androidx.activity.ComponentActivity
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.nullableAtomicReference
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
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
    private val appWidgetHost: CommunalAppWidgetHost,
    @Background private val bgDispatcher: CoroutineDispatcher
) : WidgetConfigurator {
    @AssistedFactory
    fun interface Factory {
        fun create(activity: ComponentActivity): WidgetConfigurationController
    }

    private var result: CompletableDeferred<Boolean>? by nullableAtomicReference()

    override suspend fun configureWidget(appWidgetId: Int): Boolean =
        withContext(bgDispatcher) {
            if (result != null) {
                throw IllegalStateException("There is already a pending configuration")
            }
            result = CompletableDeferred()
            val options =
                ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    splashScreenStyle = SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR
                }

            try {
                appWidgetHost.startAppWidgetConfigureActivityForResult(
                    activity,
                    appWidgetId,
                    0,
                    REQUEST_CODE,
                    options.toBundle()
                )
            } catch (e: ActivityNotFoundException) {
                setConfigurationResult(Activity.RESULT_CANCELED)
            }
            val value = result?.await() ?: false
            result = null
            return@withContext value
        }

    fun setConfigurationResult(resultCode: Int) {
        result?.complete(resultCode == Activity.RESULT_OK)
    }

    companion object {
        const val REQUEST_CODE = 100
    }
}
