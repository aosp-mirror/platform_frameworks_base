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

import android.content.res.ColorStateList

/**
 * Represents the message displayed on the bouncer. It has two parts, primary and a secondary
 * message
 */
data class BouncerMessageModel(
    val message: Message? = null,
    val secondaryMessage: Message? = null,
)

/**
 * Representation of a single message on the bouncer. It can be either a string or a string resource
 * ID
 */
data class Message(
    val message: String? = null,
    val messageResId: Int? = null,
    val colorState: ColorStateList? = null,
    /** Any plural formatter arguments that can used to format the [messageResId] */
    var formatterArgs: Map<String, Any>? = null,
    /** Specifies whether this text should be animated when it is shown. */
    var animate: Boolean = true,
)
