/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.ui.viewmodel

import com.android.systemui.contextualeducation.GestureType

sealed class ContextualEduContentViewModel(open val userId: Int)

data class ContextualEduNotificationViewModel(
    val title: String,
    val message: String,
    val gestureType: GestureType,
    override val userId: Int,
) : ContextualEduContentViewModel(userId)

data class ContextualEduToastViewModel(
    val message: String,
    val icon: Int,
    override val userId: Int,
) : ContextualEduContentViewModel(userId)
