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
package com.android.credentialmanager.logging

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

enum class LifecycleEvent(private val id: Int) : UiEventLogger.UiEventEnum {

    @UiEvent(doc = "A new activity is initialized for processing the request.")
    CREDMAN_ACTIVITY_INIT(1343),

    @UiEvent(doc = "An existing activity received a new request to process.")
    CREDMAN_ACTIVITY_NEW_REQUEST(1344),

    @UiEvent(doc = "The UI closed due to illegal internal state.")
    CREDMAN_ACTIVITY_INTERNAL_ERROR(1345);

    override fun getId(): Int {
        return this.id
    }
}