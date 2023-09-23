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

package com.android.systemui.statusbar.pipeline.ethernet.domain

import com.android.settingslib.AccessibilityContentDescriptions
import com.android.systemui.res.R
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Currently we don't do much to interact with ethernet. We simply need a place to map between the
 * connectivity state of a default ethernet connection, and an icon representing that connection.
 */
@SysUISingleton
class EthernetInteractor
@Inject
constructor(
    connectivityRepository: ConnectivityRepository,
) {
    /** Icon representing the current connectivity status of the ethernet connection */
    val icon: Flow<Icon.Resource?> =
        connectivityRepository.defaultConnections.map {
            if (it.ethernet.isDefault) {
                if (it.isValidated) {
                    Icon.Resource(
                        R.drawable.stat_sys_ethernet_fully,
                        ContentDescription.Resource(
                            AccessibilityContentDescriptions.ETHERNET_CONNECTION_VALUES[1]
                        )
                    )
                } else {
                    Icon.Resource(
                        R.drawable.stat_sys_ethernet,
                        ContentDescription.Resource(
                            AccessibilityContentDescriptions.ETHERNET_CONNECTION_VALUES[0]
                        )
                    )
                }
            } else {
                null
            }
        }
}
