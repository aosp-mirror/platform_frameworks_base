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

package com.android.systemui.statusbar.pipeline.wifi.data.repository

import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositoryImpl.Companion.ACTIVITY_DEFAULT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Fake implementation of [WifiRepository] exposing set methods for all the flows. */
class FakeWifiRepository : WifiRepository {
    private val _wifiModel: MutableStateFlow<WifiModel?> = MutableStateFlow(null)
    override val wifiModel: Flow<WifiModel?> = _wifiModel

    private val _wifiActivity = MutableStateFlow(ACTIVITY_DEFAULT)
    override val wifiActivity: Flow<WifiActivityModel> = _wifiActivity

    fun setWifiModel(wifiModel: WifiModel?) {
        _wifiModel.value = wifiModel
    }

    fun setWifiActivity(activity: WifiActivityModel) {
        _wifiActivity.value = activity
    }
}
