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

import java.util.concurrent.TimeUnit;
import java.io.IOException;

/**
 * A <tt>AccountManagerFuture</tt> represents the result of an asynchronous
 * {@link AccountManager} call.  Methods are provided to check if the computation is
 * complete, to wait for its completion, and to retrieve the result of
 * the computation.  The result can only be retrieved using method
 * <tt>get</tt> when the computation has completed, blocking if
 * necessary until it is ready.  Cancellation is performed by the
 * <tt>cancel</tt> method.  Additional methods are provided to
 * determine if the task completed normally or was cancelled. Once a
 * computation has completed, the computation cannot be cancelled.
 * If you would like to use a <tt>Future</tt> for the sake
 * of cancellability but not provide a usable result, you can
 * declare types of the form <tt>Future&lt;?&gt;</tt> and
 * return <tt>null</tt> as a result of the underlying task.
 */
public interface AccountManagerFuture<V> {
    /**
     * Attempts to cancel execution of this task.  This attempt will
     * fail if the task has already completed, has already been cancelled,
     * or could not be cancelled for some other reason. If successful,
     * and this task has not started when <tt>cancel</tt> is called,
     * this task should never run.  If the task has already started,
     * then the <tt>mayInterruptIfRunning</tt> parameter determines
     * whether the thread executing this task should be interrupted in
     * an attempt to stop the task.
     *
     * <p>After this method returns, subsequent calls to {@link #isDone} will
     * always return <tt>true</tt>.  Subsequent calls to {@link #isCancelled}
     * will always return <tt>true</tt> if this method returned <tt>true</tt>.
     *
     * @param mayInterruptIfRunning <tt>true</tt> if the thread executing this
     * task should be interrupted; otherwise, in-progress tasks are allowed
     * to complete
     * @return <tt>false</tt> if the task could not be cancelled,
     * typically because it has already completed normally;
     * <tt>true</tt> otherwise
     */
    boolean cancel(boolean mayInterruptIfRunning);

    /**
     * Returns <tt>true</tt> if this task was cancelled before it completed
     * normally.
     *
     * @return <tt>true</tt> if this task was cancelled before it completed
     */
    boolean isCancelled();

    /**
     * Returns <tt>true</tt> if this task completed.
     *
     * Completion may be due to normal termination, an exception, or
     * cancellation -- in all of these cases, this method will return
     * <tt>true</tt>.
     *
     * @return <tt>true</tt> if this task completed
     */
    boolean isDone();

    /**
     * Accessor for the future result the {@link AccountManagerFuture} represents. This
     * call will block until the result is available. In order to check if the result is
     * available without blocking, one may call {@link #isDone()} and  {@link #isCancelled()}.
     * If the request that generated this result fails or is canceled then an exception
     * will be thrown rather than the call returning normally.
     * @return the actual result
     * @throws android.accounts.OperationCanceledException if the request was canceled for any
     * reason (including if it is forbidden
     * by policy to modify an account (of that type))
     * @throws android.accounts.AuthenticatorException if there was an error communicating with
     * the authenticator or if the authenticator returned an invalid response
     * @throws java.io.IOException if the authenticator returned an error response that indicates
     * that it encountered an IOException while communicating with the authentication server
     */
    V getResult() throws OperationCanceledException, IOException, AuthenticatorException;

    /**
     * Accessor for the future result the {@link AccountManagerFuture} represents. This
     * call will block until the result is available. In order to check if the result is
     * available without blocking, one may call {@link #isDone()} and  {@link #isCancelled()}.
     * If the request that generated this result fails or is canceled then an exception
     * will be thrown rather than the call returning normally. If a timeout is specified then
     * the request will automatically be canceled if it does not complete in that amount of time.
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument. This must not be null.
     * @return the actual result
     * @throws android.accounts.OperationCanceledException if the request was canceled for any
     * reason
     * @throws android.accounts.AuthenticatorException if there was an error communicating with
     * the authenticator or if the authenticator returned an invalid response
     * @throws java.io.IOException if the authenticator returned an error response that indicates
     * that it encountered an IOException while communicating with the authentication server
     */
    V getResult(long timeout, TimeUnit unit)
            throws OperationCanceledException, IOException, AuthenticatorException;
}