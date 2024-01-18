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
 *
 */

package com.android.systemui.communal.data.repository

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Resources
import com.android.systemui.communal.shared.CommunalWidgetHost
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.util.Optional
import javax.inject.Named

@Module
interface CommunalWidgetRepositoryModule {
    companion object {
        private const val APP_WIDGET_HOST_ID = 116
        const val DEFAULT_WIDGETS = "default_widgets"

        @SysUISingleton
        @Provides
        fun provideAppWidgetManager(@Application context: Context): Optional<AppWidgetManager> {
            return Optional.ofNullable(AppWidgetManager.getInstance(context))
        }

        @SysUISingleton
        @Provides
        fun provideCommunalAppWidgetHost(@Application context: Context): CommunalAppWidgetHost {
            return CommunalAppWidgetHost(context, APP_WIDGET_HOST_ID)
        }

        @SysUISingleton
        @Provides
        fun provideCommunalWidgetHost(
            appWidgetManager: Optional<AppWidgetManager>,
            appWidgetHost: CommunalAppWidgetHost,
            @CommunalLog logBuffer: LogBuffer,
        ): CommunalWidgetHost {
            return CommunalWidgetHost(appWidgetManager, appWidgetHost, logBuffer)
        }

        @Provides
        @Named(DEFAULT_WIDGETS)
        fun provideDefaultWidgets(@Main resources: Resources): Array<String> {
            return resources.getStringArray(R.array.config_communalWidgetAllowlist)
        }
    }

    @Binds
    fun communalWidgetRepository(impl: CommunalWidgetRepositoryImpl): CommunalWidgetRepository
}
