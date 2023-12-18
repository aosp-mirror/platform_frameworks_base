/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.service.wearable;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.wearable.Flags;
import android.app.wearable.WearableSensingDataRequest;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Consumer;

/**
 * An interface to request wearable sensing data.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_DATA_REQUEST_OBSERVER_API)
@SystemApi
public interface WearableSensingDataRequester {

    /** An unknown status. */
    int STATUS_UNKNOWN = 0;

    /** The value of the status code that indicates success. */
    int STATUS_SUCCESS = 1;

    /**
     * The value of the status code that indicates the request is rejected because the data request
     * observer PendingIntent has been cancelled.
     */
    int STATUS_OBSERVER_CANCELLED = 2;

    /** @hide */
    @IntDef(
            prefix = {"STATUS_"},
            value = {STATUS_UNKNOWN, STATUS_SUCCESS, STATUS_OBSERVER_CANCELLED})
    @Retention(RetentionPolicy.SOURCE)
    @interface StatusCode {}

    /**
     * Sends a data request.
     *
     * @param dataRequest The data request to send.
     * @param statusConsumer A consumer that handles the status code for the data request.
     */
    void requestData(
            @NonNull WearableSensingDataRequest dataRequest,
            @NonNull @StatusCode Consumer<Integer> statusConsumer);
}
