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

package com.android.systemui.communal.data.db

import android.content.Context
import androidx.room.Room
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import dagger.Module
import dagger.Provides

@Module
interface CommunalDatabaseModule {
    companion object {
        @SysUISingleton
        @Provides
        fun provideCommunalDatabase(
            @Application context: Context,
            defaultWidgetPopulation: DefaultWidgetPopulation,
        ): CommunalDatabase {
            return Room.databaseBuilder(
                    context,
                    CommunalDatabase::class.java,
                    context.resources.getString(R.string.config_communalDatabase)
                )
                .fallbackToDestructiveMigration()
                .addCallback(defaultWidgetPopulation)
                .build()
        }

        @SysUISingleton
        @Provides
        fun provideCommunalWidgetDao(database: CommunalDatabase): CommunalWidgetDao =
            database.communalWidgetDao()
    }
}
