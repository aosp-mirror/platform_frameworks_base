/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.service.autofill;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/**
 * Handles the response to
 * {@link AutofillService#onSavedDatasetsInfoRequest(SavedDatasetsInfoCallback)}.
 * <p>
 * Use {@link #onSuccess(Set)} to return the computed info about the datasets the user saved to this
 * service. If there was an error querying the info, or if the service is unable to do so at this
 * time (for example, if the user isn't logged in), call {@link #onError(int)}.
 * <p>
 * This callback can be used only once.
 */
public interface SavedDatasetsInfoCallback {

    /** @hide */
    @IntDef(prefix = {"ERROR_"}, value = {
            ERROR_OTHER,
            ERROR_UNSUPPORTED,
            ERROR_NEEDS_USER_ACTION
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Error {
    }

    /**
     * The result could not be computed for any other reason.
     */
    int ERROR_OTHER = 0;
    /**
     * The service does not support this request.
     */
    int ERROR_UNSUPPORTED = 1;
    /**
     * The result cannot be computed until the user takes some action, such as setting up their
     * account.
     */
    int ERROR_NEEDS_USER_ACTION = 2;

    /**
     * Successfully respond to the request with the info on each type of saved datasets.
     */
    void onSuccess(@NonNull Set<SavedDatasetsInfo> results);

    /**
     * Respond to the request with an error. System settings may display a suitable notice to the
     * user.
     */
    void onError(@Error int error);
}
