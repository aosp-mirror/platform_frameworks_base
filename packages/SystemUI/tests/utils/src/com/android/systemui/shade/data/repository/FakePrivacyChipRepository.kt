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
 *
 */

package com.android.systemui.shade.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.privacy.PrivacyItem
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/** Fake implementation of [PrivacyChipRepository] */
@SysUISingleton
class FakePrivacyChipRepository @Inject constructor() : PrivacyChipRepository {
    private val _isSafetyCenterEnabled = MutableStateFlow(false)
    override val isSafetyCenterEnabled = _isSafetyCenterEnabled

    private val _privacyItems: MutableStateFlow<List<PrivacyItem>> = MutableStateFlow(emptyList())
    override val privacyItems = _privacyItems

    private val _isMicCameraIndicationEnabled = MutableStateFlow(false)
    override val isMicCameraIndicationEnabled = _isMicCameraIndicationEnabled

    private val _isLocationIndicationEnabled = MutableStateFlow(false)
    override val isLocationIndicationEnabled = _isLocationIndicationEnabled

    fun setIsSafetyCenterEnabled(value: Boolean) {
        _isSafetyCenterEnabled.value = value
    }

    fun setPrivacyItems(value: List<PrivacyItem>) {
        _privacyItems.value = value
    }

    fun setIsMicCameraIndicationEnabled(value: Boolean) {
        _isMicCameraIndicationEnabled.value = value
    }

    fun setIsLocationIndicationEnabled(value: Boolean) {
        _isLocationIndicationEnabled.value = value
    }
}
