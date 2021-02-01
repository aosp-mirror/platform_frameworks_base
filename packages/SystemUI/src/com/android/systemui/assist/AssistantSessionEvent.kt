/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.assist

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

enum class AssistantSessionEvent(private val id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "Unknown assistant session event")
    ASSISTANT_SESSION_UNKNOWN(0),

    @UiEvent(doc = "Assistant session dismissed due to timeout")
    ASSISTANT_SESSION_TIMEOUT_DISMISS(524),

    @UiEvent(doc = "User began a gesture for invoking the Assistant")
    ASSISTANT_SESSION_INVOCATION_START(525),

    @UiEvent(doc =
        "User stopped a gesture for invoking the Assistant before the gesture was completed")
    ASSISTANT_SESSION_INVOCATION_CANCELLED(526),

    @UiEvent(doc = "User manually dismissed the Assistant session")
    ASSISTANT_SESSION_USER_DISMISS(527),

    @UiEvent(doc = "The Assistant session has changed modes")
    ASSISTANT_SESSION_UPDATE(528),

    @UiEvent(doc = "The Assistant session completed")
    ASSISTANT_SESSION_CLOSE(529);

    override fun getId(): Int {
        return id
    }
}