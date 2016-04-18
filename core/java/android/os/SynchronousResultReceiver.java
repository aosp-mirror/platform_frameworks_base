/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Extends ResultReceiver to allow the server end of the ResultReceiver to synchronously wait
 * on the response from the client. This enables an RPC like system but with the ability to
 * timeout and discard late results.
 *
 * NOTE: Can only be used for one response. Subsequent responses on the same instance are ignored.
 * {@hide}
 */
public class SynchronousResultReceiver extends ResultReceiver {
    public static class Result {
        public int resultCode;
        @Nullable public Bundle bundle;

        public Result(int resultCode, @Nullable Bundle bundle) {
            this.resultCode = resultCode;
            this.bundle = bundle;
        }
    }

    private final CompletableFuture<Result> mFuture = new CompletableFuture<>();

    public SynchronousResultReceiver() {
        super((Handler) null);
    }

    @Override
    final protected void onReceiveResult(int resultCode, Bundle resultData) {
        super.onReceiveResult(resultCode, resultData);
        mFuture.complete(new Result(resultCode, resultData));
    }

    /**
     * Blocks waiting for the result from the remote client.
     *
     * @return the Result
     * @throws TimeoutException if the timeout in milliseconds expired.
     */
    public @NonNull Result awaitResult(long timeoutMillis) throws TimeoutException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (timeoutMillis >= 0) {
            try {
                return mFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                // This will NEVER happen.
                throw new AssertionError("Error receiving response", e);
            } catch (InterruptedException e) {
                // The thread was interrupted, try and get the value again, this time
                // with the remaining time until the deadline.
                timeoutMillis -= deadline - System.currentTimeMillis();
            }
        }
        throw new TimeoutException();
    }

}
