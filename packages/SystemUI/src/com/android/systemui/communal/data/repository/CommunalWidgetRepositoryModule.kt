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

import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import dagger.Lazy
import dagger.Module
import dagger.Provides

@Module
interface CommunalWidgetRepositoryModule {
    companion object {
        @Provides
        fun provideCommunalWidgetRepository(
            localImpl: Lazy<CommunalWidgetRepositoryLocalImpl>,
            remoteImpl: Lazy<CommunalWidgetRepositoryRemoteImpl>,
            helper: GlanceableHubMultiUserHelper,
        ): CommunalWidgetRepository {
            // Provide an implementation based on the current user.
            return if (helper.glanceableHubHsumFlagEnabled && helper.isInHeadlessSystemUser())
                remoteImpl.get()
            else localImpl.get()
        }
    }
}
