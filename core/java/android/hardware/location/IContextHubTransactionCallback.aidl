/*
 * Copyright 2017 The Android Open Source Project
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

package android.hardware.location;

import android.hardware.location.NanoAppState;

/**
 * An interface used by the Context Hub Service to invoke callbacks notifying the complete of a
 * transaction. The callbacks are unique for each type of transaction, and the service is
 * responsible for invoking the correct callback.
 *
 * @hide
 */
oneway interface IContextHubTransactionCallback {

    // Callback to be invoked when a query request completes
    void onQueryResponse(int result, in List<NanoAppState> nanoappList);

    // Callback to be invoked when a non-query request completes
    void onTransactionComplete(int result);
}
