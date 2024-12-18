/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.ktx

import android.content.Intent
import android.credentials.selection.CancelSelectionRequest
import android.credentials.selection.Constants
import android.credentials.selection.CreateCredentialProviderData
import android.credentials.selection.GetCredentialProviderData
import android.credentials.selection.ProviderData
import android.credentials.selection.RequestInfo
import android.os.ResultReceiver

val Intent.cancelUiRequest: CancelSelectionRequest?
    get() = this.extras?.getParcelable(
        CancelSelectionRequest.EXTRA_CANCEL_UI_REQUEST,
        CancelSelectionRequest::class.java
    )

val Intent.requestInfo: RequestInfo?
    get() = this.extras?.getParcelable(
        RequestInfo.EXTRA_REQUEST_INFO,
        RequestInfo::class.java
    )

val Intent.getCredentialProviderDataList: List<GetCredentialProviderData>
    get() = this.extras?.getParcelableArrayList(
        ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
        GetCredentialProviderData::class.java
    ) ?.filterIsInstance<GetCredentialProviderData>() ?: emptyList()

val Intent.createCredentialProviderDataList: List<CreateCredentialProviderData>
    get() = this.extras?.getParcelableArrayList(
        ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
        CreateCredentialProviderData::class.java
    ) ?.filterIsInstance<CreateCredentialProviderData>() ?: emptyList()

val Intent.resultReceiver: ResultReceiver?
    get() = this.getParcelableExtra(
        Constants.EXTRA_RESULT_RECEIVER,
        ResultReceiver::class.java
    )
