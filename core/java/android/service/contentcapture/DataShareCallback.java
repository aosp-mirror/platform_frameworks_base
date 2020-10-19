/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.contentcapture;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemApi;

import java.util.concurrent.Executor;

/**
 * Callback for the Content Capture Service to accept or reject the data share request from a client
 * app.
 *
 * If the request is rejected, client app would receive a signal and the data share session wouldn't
 * be started.
 *
 * @hide
 **/
@SystemApi
public interface DataShareCallback {

    /** Accept the data share.
     *
     * @param executor executor to be used for running the adapter in.
     * @param adapter adapter to be used for the share operation
     */
    void onAccept(@NonNull @CallbackExecutor Executor executor,
            @NonNull DataShareReadAdapter adapter);

    /** Reject the data share. */
    void onReject();
}
