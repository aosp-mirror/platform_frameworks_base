/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.credentials;

import android.app.Activity;
import android.content.Intent;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialResponse;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialResponse;
import android.credentials.selection.ProviderPendingIntentResponse;
import android.service.credentials.BeginGetCredentialResponse;
import android.service.credentials.CredentialProviderService;

/**
 * Helper class for setting up pending intent, and extracting objects from it.
 *
 * @hide
 */
public class PendingIntentResultHandler {
    /** Returns true if the result is successful and may contain result extras. */
    public static boolean isValidResponse(
            ProviderPendingIntentResponse pendingIntentResponse) {
        //TODO: Differentiate based on extra_error in the resultData
        return pendingIntentResponse.getResultCode() == Activity.RESULT_OK;
    }

    /** Returns true if the pending intent was cancelled by the user. */
    public static boolean isCancelledResponse(
            ProviderPendingIntentResponse pendingIntentResponse) {
        return pendingIntentResponse.getResultCode() == Activity.RESULT_CANCELED;
    }

    /** Extracts the {@link BeginGetCredentialResponse} object added to the result data. */
    public static BeginGetCredentialResponse extractResponseContent(Intent resultData) {
        if (resultData == null) {
            return null;
        }
        return resultData.getParcelableExtra(
                CredentialProviderService.EXTRA_BEGIN_GET_CREDENTIAL_RESPONSE,
                BeginGetCredentialResponse.class);
    }

    /** Extracts the {@link CreateCredentialResponse} object added to the result data. */
    public static CreateCredentialResponse extractCreateCredentialResponse(Intent resultData) {
        if (resultData == null) {
            return null;
        }
        return resultData.getParcelableExtra(
                CredentialProviderService.EXTRA_CREATE_CREDENTIAL_RESPONSE,
                CreateCredentialResponse.class);
    }

    /** Extracts the {@link GetCredentialResponse} object added to the result data. */
    public static GetCredentialResponse extractGetCredentialResponse(Intent resultData) {
        if (resultData == null) {
            return null;
        }
        return resultData.getParcelableExtra(
                CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE,
                GetCredentialResponse.class);
    }

    /** Extract the {@link CreateCredentialException} from the
     * given pending intent . */
    public static CreateCredentialException extractCreateCredentialException(
            Intent resultData) {
        if (resultData == null) {
            return null;
        }
        return resultData.getParcelableExtra(
                CredentialProviderService.EXTRA_CREATE_CREDENTIAL_EXCEPTION,
                CreateCredentialException.class);
    }

    /** Extract the {@link GetCredentialException} from the
     * given pending intent . */
    public static GetCredentialException extractGetCredentialException(
            Intent resultData) {
        if (resultData == null) {
            return null;
        }
        return resultData.getParcelableExtra(
                CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION,
                GetCredentialException.class);
    }
}
