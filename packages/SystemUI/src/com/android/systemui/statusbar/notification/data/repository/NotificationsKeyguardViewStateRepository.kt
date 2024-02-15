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
package com.android.systemui.statusbar.notification.data.repository

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/** View-states pertaining to notifications on the keyguard. */
@SysUISingleton
class NotificationsKeyguardViewStateRepository @Inject constructor() {
    /** Are notifications fully hidden from view? */
    val areNotificationsFullyHidden = MutableStateFlow(false)

    /** Is a pulse expansion occurring? */
    val isPulseExpanding = MutableStateFlow(false)
}
