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

package com.android.credentialmanager.model.get

import android.graphics.drawable.Drawable

data class ProviderInfo(
    /**
     * Unique id (component name) of this provider.
     * Not for display purpose - [displayName] should be used for ui rendering.
     */
    val id: String,
    val icon: Drawable,
    val displayName: String,
    val credentialEntryList: List<CredentialEntryInfo>,
    val authenticationEntryList: List<AuthenticationEntryInfo>,
    val remoteEntry: RemoteEntryInfo?,
    val actionEntryList: List<ActionEntryInfo>,
)