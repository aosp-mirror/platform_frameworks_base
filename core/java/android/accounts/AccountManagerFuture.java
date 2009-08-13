/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.accounts;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.io.IOException;

/**
 * An extension of {@link java.util.concurrent.Future} that provides wrappers for {@link #get()}
 * that handle the various
 * exceptions that  {@link #get()} may return and rethrows them as exceptions specific to
 * {@link android.accounts.AccountManager}.
 */
public interface AccountManagerFuture<V> extends Future<V> {
    /**
     * Wrapper for {@link java.util.concurrent.Future#get()}. If the get() throws
     * {@link InterruptedException} then the
     * {@link AccountManagerFuture} is canceled and
     * {@link android.accounts.OperationCanceledException} is thrown.
     * @return the {@link android.os.Bundle} that is returned by get()
     * @throws android.accounts.OperationCanceledException if get() throws the unchecked
     * CancellationException
     * or if the Future was interrupted.
     */
    V getResult() throws OperationCanceledException, IOException, AuthenticatorException;

    /**
     * Wrapper for {@link java.util.concurrent.Future#get()}. If the get() throws
     * {@link InterruptedException} then the
     * {@link AccountManagerFuture} is canceled and
     * {@link android.accounts.OperationCanceledException} is thrown.
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the {@link android.os.Bundle} that is returned by
     * {@link java.util.concurrent.Future#get()}
     * @throws android.accounts.OperationCanceledException if get() throws the unchecked
     * {@link java.util.concurrent.CancellationException} or if the {@link AccountManagerFuture}
     * was interrupted.
     */
    V getResult(long timeout, TimeUnit unit)
            throws OperationCanceledException, IOException, AuthenticatorException;

    @Deprecated
    V get() throws InterruptedException, ExecutionException;

    @Deprecated
    V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException;
}