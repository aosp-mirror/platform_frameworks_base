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
package com.android.systemui.smartspace.dagger

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.UI_SURFACE_DREAM
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.smartspace.dagger.SmartspaceViewComponent.SmartspaceViewModule.PLUGIN
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import javax.inject.Named

@Subcomponent(modules = [SmartspaceViewComponent.SmartspaceViewModule::class])
interface SmartspaceViewComponent {
    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance parent: ViewGroup,
            @BindsInstance @Named(PLUGIN) plugin: BcSmartspaceDataPlugin,
            @BindsInstance onAttachListener: View.OnAttachStateChangeListener,
            @BindsInstance viewWithCustomLayout: View? = null
        ): SmartspaceViewComponent
    }

    fun getView(): BcSmartspaceDataPlugin.SmartspaceView

    @Module
    object SmartspaceViewModule {
        const val PLUGIN = "plugin"

        @Provides
        fun providesSmartspaceView(
            activityStarter: ActivityStarter,
            falsingManager: FalsingManager,
            parent: ViewGroup,
            @Named(PLUGIN) plugin: BcSmartspaceDataPlugin,
            viewWithCustomLayout: View?,
            onAttachListener: View.OnAttachStateChangeListener
        ):
                BcSmartspaceDataPlugin.SmartspaceView {
            val ssView = viewWithCustomLayout
                    as? BcSmartspaceDataPlugin.SmartspaceView
                    ?: plugin.getView(parent)
            // Currently, this is only used to provide SmartspaceView on Dream surface.
            ssView.setUiSurface(UI_SURFACE_DREAM)
            ssView.registerDataProvider(plugin)

            ssView.setIntentStarter(object : BcSmartspaceDataPlugin.IntentStarter {
                override fun startIntent(view: View, intent: Intent, showOnLockscreen: Boolean) {
                    activityStarter.startActivity(
                            intent,
                            true, /* dismissShade */
                            null, /* launch animator */
                            showOnLockscreen
                    )
                }

                override fun startPendingIntent(
                        view: View,
                        pi: PendingIntent,
                        showOnLockscreen: Boolean
                ) {
                    if (showOnLockscreen) {
                        val options = ActivityOptions.makeBasic()
                                .setPendingIntentBackgroundActivityStartMode(
                                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                                .toBundle()
                        pi.send(options)
                    } else {
                        activityStarter.startPendingIntentDismissingKeyguard(pi)
                    }
                }
            })
            (ssView as View).addOnAttachStateChangeListener(onAttachListener)
            ssView.setFalsingManager(falsingManager)
            return ssView
        }
    }
}
