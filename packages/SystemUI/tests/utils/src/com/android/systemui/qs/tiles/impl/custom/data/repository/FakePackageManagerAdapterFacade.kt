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

package com.android.systemui.qs.tiles.impl.custom.data.repository

import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Bundle
import com.android.systemui.qs.external.PackageManagerAdapter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

/**
 * Facade for [PackageManagerAdapter] to provide a fake-like behaviour. You can create this class
 * and then get [packageManagerAdapter] to use in your test code.
 *
 * This allows to mock [PackageManagerAdapter] to provide a custom behaviour for
 * [CustomTileRepository.isTileActive], [CustomTileRepository.isTileToggleable],
 * [com.android.systemui.qs.external.TileServiceManager.isToggleableTile] or
 * [com.android.systemui.qs.external.TileServiceManager.isActiveTile] when the real objects are
 * used.
 */
class FakePackageManagerAdapterFacade(
    val componentName: ComponentName,
    val packageManagerAdapter: PackageManagerAdapter = mock {},
) {

    private var isToggleable: Boolean = false
    private var isActive: Boolean = false

    init {
        whenever(packageManagerAdapter.getServiceInfo(eq(componentName), any())).thenAnswer {
            ServiceInfo().apply {
                metaData =
                    Bundle().apply {
                        putBoolean(
                            android.service.quicksettings.TileService.META_DATA_TOGGLEABLE_TILE,
                            isToggleable
                        )
                        putBoolean(
                            android.service.quicksettings.TileService.META_DATA_ACTIVE_TILE,
                            isActive
                        )
                    }
            }
        }
    }

    fun setIsActive(isActive: Boolean) {
        this.isActive = isActive
    }

    fun setIsToggleable(isToggleable: Boolean) {
        this.isToggleable = isToggleable
    }
}
