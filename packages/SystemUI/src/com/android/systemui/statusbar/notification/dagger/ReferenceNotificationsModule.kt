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

package com.android.systemui.statusbar.notification.dagger

import com.android.systemui.statusbar.notification.promoted.PromotedNotificationsModule
import com.android.systemui.statusbar.notification.row.NotificationRowModule
import dagger.Module

/**
 * A module that includes the standard notifications classes that most SysUI variants need. Variants
 * are free to not include this module and instead write a custom notifications module.
 */
@Module(
    includes =
        [
            NotificationsModule::class,
            NotificationRowModule::class,
            PromotedNotificationsModule::class,
        ]
)
object ReferenceNotificationsModule
