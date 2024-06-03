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
 *
 */

package com.android.systemui.communal.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Resources
import android.os.Looper
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.res.R
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import dagger.Module
import dagger.Provides
import java.util.Optional
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope

@Module
interface CommunalWidgetModule {
    companion object {
        const val APP_WIDGET_HOST_ID = 116
        const val DEFAULT_WIDGETS = "default_widgets"

        @SysUISingleton
        @Provides
        fun provideAppWidgetManager(@Application context: Context): Optional<AppWidgetManager> {
            return Optional.ofNullable(AppWidgetManager.getInstance(context))
        }

        @SysUISingleton
        @Provides
        fun provideCommunalAppWidgetHost(
            @Application context: Context,
            @Background backgroundScope: CoroutineScope,
            interactionHandler: WidgetInteractionHandler,
            @Main looper: Looper,
            @CommunalLog logBuffer: LogBuffer,
        ): CommunalAppWidgetHost {
            return CommunalAppWidgetHost(
                context,
                backgroundScope,
                APP_WIDGET_HOST_ID,
                interactionHandler,
                looper,
                logBuffer,
            )
        }

        @SysUISingleton
        @Provides
        fun provideCommunalWidgetHost(
            @Application applicationScope: CoroutineScope,
            appWidgetManager: Optional<AppWidgetManager>,
            appWidgetHost: CommunalAppWidgetHost,
            selectedUserInteractor: SelectedUserInteractor,
            @CommunalLog logBuffer: LogBuffer,
        ): CommunalWidgetHost {
            return CommunalWidgetHost(
                applicationScope,
                appWidgetManager,
                appWidgetHost,
                selectedUserInteractor,
                logBuffer,
            )
        }

        @Provides
        @Named(DEFAULT_WIDGETS)
        fun provideDefaultWidgets(@Main resources: Resources): Array<String> {
            return resources.getStringArray(R.array.config_communalWidgetAllowlist)
        }
    }
}
