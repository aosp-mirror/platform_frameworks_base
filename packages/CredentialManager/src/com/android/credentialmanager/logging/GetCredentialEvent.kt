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
import com.android.internal.logging.UiEventLogger.UiEventEnum.RESERVE_NEW_UI_EVENT_ID

enum class GetCredentialEvent(private val id: Int) : UiEventLogger.UiEventEnum {

    @UiEvent(doc = "The The snackbar only page when there's no account but only a remoteEntry " +
            "visible on the screen.")
    CREDMAN_GET_CRED_SCREEN_REMOTE_ONLY(1332),

    @UiEvent(doc = "The snackbar when there are only auth entries and all of them are empty.")
    CREDMAN_GET_CRED_SCREEN_UNLOCKED_AUTH_ENTRIES_ONLY(1333),

    @UiEvent(doc = "The primary credential selection page is displayed on screen.")
    CREDMAN_GET_CRED_SCREEN_PRIMARY_SELECTION(1334),

    @UiEvent(doc = "The secondary credential selection page, where all sign-in options are " +
            "listed is displayed on the screen.")
    CREDMAN_GET_CRED_SCREEN_ALL_SIGN_IN_OPTIONS(1335),

    @UiEvent(doc = "The provider activity is not active nor is any ready for launch on the screen.")
    CREDMAN_GET_CRED_PROVIDER_ACTIVITY_NOT_APPLICABLE(1336),

    @UiEvent(doc = "The provider activity is ready to be launched on the screen.")
    CREDMAN_GET_CRED_PROVIDER_ACTIVITY_READY_TO_LAUNCH(1337),

    @UiEvent(doc = "The provider activity is launched and we are waiting for its result. " +
            "Contents Hidden.")
    CREDMAN_GET_CRED_PROVIDER_ACTIVITY_PENDING(1338),

    @UiEvent(doc = "The remote credential snackbar screen is visible.")
    CREDMAN_GET_CRED_REMOTE_CRED_SNACKBAR_SCREEN(1339),

    @UiEvent(doc = "The empty auth snackbar screen is visible.")
    CREDMAN_GET_CRED_SCREEN_EMPTY_AUTH_SNACKBAR_SCREEN(1340),

    @UiEvent(doc = "The primary selection card is visible on screen.")
    CREDMAN_GET_CRED_PRIMARY_SELECTION_CARD(1341),

    @UiEvent(doc = "The all sign in option card is visible on screen.")
    CREDMAN_GET_CRED_ALL_SIGN_IN_OPTION_CARD(1342),

    @UiEvent(doc = "The single tap biometric flow is launched.")
    CREDMAN_GET_CRED_BIOMETRIC_FLOW_LAUNCHED(RESERVE_NEW_UI_EVENT_ID);

    override fun getId(): Int {
        return this.id
    }
}