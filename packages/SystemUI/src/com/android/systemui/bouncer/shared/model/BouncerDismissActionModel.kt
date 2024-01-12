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

package com.android.systemui.bouncer.shared.model

import com.android.systemui.plugins.ActivityStarter

/** Represents the action that needs to be performed after bouncer is dismissed. */
data class BouncerDismissActionModel(
    /** If the bouncer is unlocked, [onDismissAction] will be run. */
    val onDismissAction: ActivityStarter.OnDismissAction?,
    /** If the bouncer is exited before unlocking, [onCancel] will be invoked. */
    val onCancel: Runnable?
)
