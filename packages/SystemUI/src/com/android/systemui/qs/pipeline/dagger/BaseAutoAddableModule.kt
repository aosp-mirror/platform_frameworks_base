/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.dagger

import android.content.res.Resources
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.pipeline.domain.autoaddable.AutoAddableSetting
import com.android.systemui.qs.pipeline.domain.autoaddable.AutoAddableSettingList
import com.android.systemui.qs.pipeline.domain.autoaddable.CastAutoAddable
import com.android.systemui.qs.pipeline.domain.autoaddable.DataSaverAutoAddable
import com.android.systemui.qs.pipeline.domain.autoaddable.DeviceControlsAutoAddable
import com.android.systemui.qs.pipeline.domain.autoaddable.HotspotAutoAddable
import com.android.systemui.qs.pipeline.domain.autoaddable.NightDisplayAutoAddable
import com.android.systemui.qs.pipeline.domain.autoaddable.ReduceBrightColorsAutoAddable
import com.android.systemui.qs.pipeline.domain.autoaddable.WalletAutoAddable
import com.android.systemui.qs.pipeline.domain.autoaddable.WorkTileAutoAddable
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet

@Module
interface BaseAutoAddableModule {

    companion object {
        @Provides
        @ElementsIntoSet
        fun providesAutoAddableSetting(
            @Main resources: Resources,
            autoAddableSettingFactory: AutoAddableSetting.Factory
        ): Set<AutoAddable> {
            return AutoAddableSettingList.parseSettingsResource(
                    resources,
                    autoAddableSettingFactory
                )
                .toSet()
        }
    }

    @Binds @IntoSet fun bindCastAutoAddable(impl: CastAutoAddable): AutoAddable

    @Binds @IntoSet fun bindDataSaverAutoAddable(impl: DataSaverAutoAddable): AutoAddable

    @Binds @IntoSet fun bindDeviceControlsAutoAddable(impl: DeviceControlsAutoAddable): AutoAddable

    @Binds @IntoSet fun bindHotspotAutoAddable(impl: HotspotAutoAddable): AutoAddable

    @Binds @IntoSet fun bindNightDisplayAutoAddable(impl: NightDisplayAutoAddable): AutoAddable

    @Binds
    @IntoSet
    fun bindReduceBrightColorsAutoAddable(impl: ReduceBrightColorsAutoAddable): AutoAddable

    @Binds @IntoSet fun bindWalletAutoAddable(impl: WalletAutoAddable): AutoAddable

    @Binds @IntoSet fun bindWorkModeAutoAddable(impl: WorkTileAutoAddable): AutoAddable
}
