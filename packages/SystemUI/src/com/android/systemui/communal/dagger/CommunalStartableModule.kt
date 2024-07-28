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

package com.android.systemui.communal.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.communal.CommunalBackupRestoreStartable
import com.android.systemui.communal.CommunalDreamStartable
import com.android.systemui.communal.CommunalMetricsStartable
import com.android.systemui.communal.CommunalOngoingContentStartable
import com.android.systemui.communal.CommunalSceneStartable
import com.android.systemui.communal.log.CommunalLoggerStartable
import com.android.systemui.communal.widgets.CommunalAppWidgetHostStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface CommunalStartableModule {
    @Binds
    @IntoMap
    @ClassKey(CommunalLoggerStartable::class)
    fun bindCommunalLoggerStartable(impl: CommunalLoggerStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(CommunalSceneStartable::class)
    fun bindCommunalSceneStartable(impl: CommunalSceneStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(CommunalDreamStartable::class)
    fun bindCommunalDreamStartable(impl: CommunalDreamStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(CommunalAppWidgetHostStartable::class)
    fun bindCommunalAppWidgetHostStartable(impl: CommunalAppWidgetHostStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(CommunalBackupRestoreStartable::class)
    fun bindCommunalBackupRestoreStartable(impl: CommunalBackupRestoreStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(CommunalOngoingContentStartable::class)
    fun bindCommunalOngoingContentStartable(impl: CommunalOngoingContentStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(CommunalMetricsStartable::class)
    fun bindCommunalMetricsStartable(impl: CommunalMetricsStartable): CoreStartable
}
