/*
 * Copyright 2024 The Android Open Source Project
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

import android.annotation.NonNull;
import android.util.Log;

import java.util.List;
import java.util.Objects;

/**
 * Helper class to generate {@link IContextHubTransactionCallback}.
 *
 * @hide
 */
public class ContextHubTransactionHelper {
    private static final String TAG = "ContextHubTransactionHelper";

    /**
     * Helper to generate a stub for a query nanoapp transaction callback.
     *
     * @param transaction the transaction to unblock when complete
     * @return the callback
     * @hide
     */
    public static IContextHubTransactionCallback createNanoAppQueryCallback(
            @NonNull() ContextHubTransaction<List<NanoAppState>> transaction) {
        Objects.requireNonNull(transaction, "transaction cannot be null");
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoappList) {
                transaction.setResponse(new ContextHubTransaction.Response<List<NanoAppState>>(
                        result, nanoappList));
            }

            @Override
            public void onTransactionComplete(int result) {
                Log.e(TAG, "Received a non-query callback on a query request");
                transaction.setResponse(new ContextHubTransaction.Response<List<NanoAppState>>(
                        ContextHubTransaction.RESULT_FAILED_SERVICE_INTERNAL_FAILURE, null));
            }
        };
    }

    /**
     * Helper to generate a stub for a non-query transaction callback.
     *
     * @param transaction the transaction to unblock when complete
     * @return the callback
     * @hide
     */
    public static IContextHubTransactionCallback createTransactionCallback(
            @NonNull() ContextHubTransaction<Void> transaction) {
        Objects.requireNonNull(transaction, "transaction cannot be null");
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoappList) {
                Log.e(TAG, "Received a query callback on a non-query request");
                transaction.setResponse(new ContextHubTransaction.Response<Void>(
                        ContextHubTransaction.RESULT_FAILED_SERVICE_INTERNAL_FAILURE, null));
            }

            @Override
            public void onTransactionComplete(int result) {
                transaction.setResponse(new ContextHubTransaction.Response<Void>(result, null));
            }
        };
    }

}
