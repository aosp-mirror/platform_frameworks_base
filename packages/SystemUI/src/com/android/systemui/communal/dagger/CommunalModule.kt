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

package com.android.systemui.communal.dagger

import com.android.systemui.communal.data.db.CommunalDatabaseModule
import com.android.systemui.communal.data.repository.CommunalMediaRepositoryModule
import com.android.systemui.communal.data.repository.CommunalRepositoryModule
import com.android.systemui.communal.data.repository.CommunalTutorialRepositoryModule
import com.android.systemui.communal.data.repository.CommunalWidgetRepositoryModule
import dagger.Module

@Module(
    includes =
        [
            CommunalRepositoryModule::class,
            CommunalMediaRepositoryModule::class,
            CommunalTutorialRepositoryModule::class,
            CommunalWidgetRepositoryModule::class,
            CommunalDatabaseModule::class,
        ]
)
class CommunalModule
