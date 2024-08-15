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

enum class CreateCredentialEvent(private val id: Int) : UiEventLogger.UiEventEnum {

    @UiEvent(doc = "The create credential bottomsheet became visible on the screen.")
    CREDMAN_CREATE_CRED_BOTTOMSHEET(1318),

    @UiEvent(doc = "The provider activity is launched on the screen.")
    CREDMAN_CREATE_CRED_PROVIDER_ACTIVITY_READY_TO_LAUNCH(1319),

    @UiEvent(doc = "The provider activity is launched and we are waiting for its result. " +
            "Contents Hidden.")
    CREDMAN_CREATE_CRED_PROVIDER_ACTIVITY_PENDING(1320),

    @UiEvent(doc = "The provider activity is not active or ready launched on the screen.")
    CREDMAN_CREATE_CRED_PROVIDER_ACTIVITY_NOT_APPLICABLE(1321),

    @UiEvent(doc = "The passkey introduction card is visible on screen.")
    CREDMAN_CREATE_CRED_PASSKEY_INTRO(1322),

    @UiEvent(doc = "The provider selection card is visible on screen.")
    CREDMAN_CREATE_CRED_PROVIDER_SELECTION(1323),

    @UiEvent(doc = "The creation option selection card is visible on screen.")
    CREDMAN_CREATE_CRED_CREATION_OPTION_SELECTION(1324),

    @UiEvent(doc = "The more option selection card is visible on screen.")
    CREDMAN_CREATE_CRED_MORE_OPTIONS_SELECTION(1325),

    @UiEvent(doc = "The more options row intro card is visible on screen.")
    CREDMAN_CREATE_CRED_MORE_OPTIONS_ROW_INTRO(1326),

    @UiEvent(doc = "The external only selection card is visible on screen.")
    CREDMAN_CREATE_CRED_EXTERNAL_ONLY_SELECTION(1327),

    @UiEvent(doc = "The more about passkeys intro card is visible on screen.")
    CREDMAN_CREATE_CRED_MORE_ABOUT_PASSKEYS_INTRO(1328),

    @UiEvent(doc = "The single tap biometric flow is launched.")
    CREDMAN_CREATE_CRED_BIOMETRIC_FLOW_LAUNCHED(1800);

    override fun getId(): Int {
        return this.id
    }
}