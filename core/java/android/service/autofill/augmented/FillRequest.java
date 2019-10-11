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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.service.autofill.augmented.AugmentedAutofillService.AutofillProxy;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

/**
 * Represents a request to augment-fill an activity.
 * @hide
 */
@SystemApi
// TODO(b/123100811): pass a requestId and/or sessionId?
@TestApi
public final class FillRequest {

    final AutofillProxy mProxy;

    /** @hide */
    FillRequest(@NonNull AutofillProxy proxy) {
        mProxy = proxy;
    }

    /**
     * Gets the task of the activity associated with this request.
     */
    public int getTaskId() {
        return mProxy.taskId;
    }

    /**
     * Gets the name of the activity associated with this request.
     */
    @NonNull
    public ComponentName getActivityComponent() {
        return mProxy.componentName;
    }

    /**
     * Gets the id of the field that triggered the request.
     */
    @NonNull
    public AutofillId getFocusedId() {
        return mProxy.getFocusedId();
    }

    /**
     * Gets the current value of the field that triggered the request.
     */
    @NonNull
    public AutofillValue getFocusedValue() {
        return mProxy.getFocusedValue();
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

    @NonNull
    @Override
    public String toString() {
        return "FillRequest[act=" + getActivityComponent().flattenToShortString()
                + ", id=" + mProxy.getFocusedId() + "]";
    }
}
