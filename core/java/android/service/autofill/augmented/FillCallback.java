/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.autofill.augmented;

import static android.service.autofill.augmented.AugmentedAutofillService.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.service.autofill.augmented.AugmentedAutofillService.AutofillProxy;
import android.util.Log;

import java.util.List;

/**
 * Callback used to indicate at {@link FillRequest} has been fulfilled.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class FillCallback {

    private static final String TAG = FillCallback.class.getSimpleName();

    private final AutofillProxy mProxy;

    FillCallback(@NonNull AutofillProxy proxy) {
        mProxy = proxy;
    }

    /**
     * Sets the response associated with the request.
     *
     * @param response response associated with the request, or {@code null} if the service
     *                 could not provide autofill for the request.
     */
    public void onSuccess(@Nullable FillResponse response) {
        if (sDebug) Log.d(TAG, "onSuccess(): " + response);

        if (response == null) {
            mProxy.logEvent(AutofillProxy.REPORT_EVENT_NO_RESPONSE);
            mProxy.reportResult(/* inlineSuggestionsData */ null, /* clientState */
                    null, /* showingFillWindow */ false);
            return;
        }

        final List<Dataset> inlineSuggestions = response.getInlineSuggestions();
        final Bundle clientState = response.getClientState();
        final FillWindow fillWindow = response.getFillWindow();
        boolean showingFillWindow = false;
        if (inlineSuggestions != null && !inlineSuggestions.isEmpty()) {
            mProxy.logEvent(AutofillProxy.REPORT_EVENT_INLINE_RESPONSE);
        } else if (fillWindow != null) {
            fillWindow.show();
            showingFillWindow = true;
        }
        // We need to report result regardless of whether inline suggestions are returned or not.
        mProxy.reportResult(inlineSuggestions, clientState, showingFillWindow);

        // TODO(b/123099468): must notify the server so it can update the session state to avoid
        // showing conflicting UIs (for example, if a new request is made to the main autofill
        // service and it now wants to show something).
    }
}
