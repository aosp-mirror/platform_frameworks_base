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
package android.service.intelligence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.service.intelligence.SmartSuggestionsService.AutofillProxy;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

/**
 * Represents a request to augment-fill an activity.
 * @hide
 */
@SystemApi
public final class FillRequest {

    final AutofillProxy mProxy;

    /** @hide */
    FillRequest(@NonNull AutofillProxy proxy) {
        mProxy = proxy;
    }

    /**
     * Gets the session associated with this request.
     */
    @NonNull
    public InteractionSessionId getSessionId() {
        return mProxy.sessionId;
    }

    /**
     * Gets the id of the field that triggered the request.
     */
    @NonNull
    public AutofillId getFocusedId() {
        return mProxy.focusedId;
    }

    /**
     * Gets the current value of the field that triggered the request.
     */
    @NonNull
    public AutofillValue getFocusedAutofillValue() {
        return mProxy.focusedValue;
    }

    /**
     * Gets the Smart Suggestions object used to embed the autofill UI.
     *
     * @return object used to embed the autofill UI, or {@code null} if not supported.
     */
    @Nullable
    public PresentationParams getPresentationParams() {
        return mProxy.getSmartSuggestionParams();
    }

    @Override
    public String toString() {
        return "FillRequest[id=" + mProxy.focusedId + "]";
    }
}
