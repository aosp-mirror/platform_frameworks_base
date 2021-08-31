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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A set of helper methods to retrieve result values from {@link CompletableFuture}.
 */
public final class CompletableFutureUtil {
    /**
     * Not intended to be instantiated.
     */
    private CompletableFutureUtil() {
    }

    @AnyThread
    @Nullable
    private static <T> T getValueOrRethrowErrorInternal(@NonNull CompletableFuture<T> future) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (ExecutionException e) {
                    final Throwable cause = e.getCause();
                    throw new RuntimeException(cause.getMessage(), cause.getCause());
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @AnyThread
    @Nullable
    private static <T> T getValueOrNullInternal(@NonNull CompletableFuture<T> future,
            @Nullable String tag, @Nullable String methodName,
            @DurationMillisLong long timeoutMillis, @Nullable CancellationGroup cancellationGroup) {
        // We intentionally do not use CompletableFuture.anyOf() to avoid additional object
        // allocations.
        final boolean needsToUnregister = cancellationGroup != null
                && cancellationGroup.tryRegisterFutureOrCancelImmediately(future);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (CompletionException e) {
                    if (e.getCause() instanceof CancellationException) {
                        logCancellationInternal(tag, methodName);
                        return null;
                    }
                    logErrorInternal(tag, methodName, e.getMessage());
                    return null;
                } catch (CancellationException e) {
                    logCancellationInternal(tag, methodName);
                    return null;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException e) {
                    logTimeoutInternal(tag, methodName, timeoutMillis);
                    return null;
                } catch (Throwable e) {
                    logErrorInternal(tag, methodName, e.getMessage());
                    return null;
                }
            }
        } finally {
            if (needsToUnregister) {
                cancellationGroup.unregisterFuture(future);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @AnyThread
    private static void logTimeoutInternal(@Nullable String tag, @Nullable String methodName,
            @DurationMillisLong long timeout) {
        if (tag == null || methodName == null) {
            return;
        }
        Log.w(tag, methodName + " didn't respond in " + timeout + " msec.");
    }

    @AnyThread
    private static void logErrorInternal(@Nullable String tag, @Nullable String methodName,
            @Nullable String errorString) {
        if (tag == null || methodName == null) {
            return;
        }
        Log.w(tag, methodName + " was failed with an exception=" + errorString);
    }

    @AnyThread
    private static void logCancellationInternal(@Nullable String tag, @Nullable String methodName) {
        if (tag == null || methodName == null) {
            return;
        }
        Log.w(tag, methodName + " was cancelled.");
    }

    /**
     * Return the result of the given {@link CompletableFuture<T>}.
     *
     * <p>This method may throw exception is the task is completed with an error.</p>
     *
     * @param future the object to extract the result from.
     * @param <T> type of the result.
     * @return the result.
     */
    @AnyThread
    @Nullable
    public static <T> T getResult(@NonNull CompletableFuture<T> future) {
        return getValueOrRethrowErrorInternal(future);
    }

    /**
     * Return the result of the given {@link CompletableFuture<Boolean>}.
     *
     * <p>This method may throw exception is the task is completed with an error.</p>
     *
     * @param future the object to extract the result from.
     * @return the result.
     */
    @AnyThread
    public static boolean getBooleanResult(@NonNull CompletableFuture<Boolean> future) {
        return getValueOrRethrowErrorInternal(future);
    }

    /**
     * Return the result of the given {@link CompletableFuture<Integer>}.
     *
     * <p>This method may throw exception is the task is completed with an error.</p>
     *
     * @param future the object to extract the result from.
     * @return the result.
     */
    @AnyThread
    public static int getIntegerResult(@NonNull CompletableFuture<Integer> future) {
        return getValueOrRethrowErrorInternal(future);
    }

    /**
     * Return the result of the given {@link CompletableFuture<Boolean>}.
     *
     * <p>This method is agnostic to {@link Thread#interrupt()}.</p>
     *
     * <p>CAVEAT: when {@code cancellationGroup} is specified and it is signalled, {@code future}
     * will be cancelled permanently.  You have to duplicate the {@link CompletableFuture} if you
     * want to avoid this side-effect.</p>
     *
     * @param future the object to extract the result from.
     * @param tag tag name for logging. Pass {@code null} to disable logging.
     * @param methodName method name for logging. Pass {@code null} to disable logging.
     * @param cancellationGroup an optional {@link CancellationGroup} to cancel {@code future}
     *                          object. Can be {@code null}.
     * @param timeoutMillis length of the timeout in millisecond.
     * @return the result if it is completed within the given timeout. {@code false} otherwise.
     */
    @AnyThread
    public static boolean getResultOrFalse(@NonNull CompletableFuture<Boolean> future,
            @Nullable String tag, @Nullable String methodName,
            @Nullable CancellationGroup cancellationGroup,
            @DurationMillisLong long timeoutMillis) {
        final Boolean obj = getValueOrNullInternal(future, tag, methodName, timeoutMillis,
                cancellationGroup);
        return obj != null ? obj : false;
    }

    /**
     * Return the result of the given {@link CompletableFuture<Integer>}.
     *
     * <p>This method is agnostic to {@link Thread#interrupt()}.</p>
     *
     * <p>CAVEAT: when {@code cancellationGroup} is specified and it is signalled, {@code future}
     * will be cancelled permanently.  You have to duplicate the {@link CompletableFuture} if you
     * want to avoid this side-effect.</p>
     *
     * @param future the object to extract the result from.
     * @param tag tag name for logging. Pass {@code null} to disable logging.
     * @param methodName method name for logging. Pass {@code null} to disable logging.
     * @param cancellationGroup an optional {@link CancellationGroup} to cancel {@code future}
     *                          object. Can be {@code null}.
     * @param timeoutMillis length of the timeout in millisecond.
     * @return the result if it is completed within the given timeout. {@code 0} otherwise.
     */
    @AnyThread
    public static int getResultOrZero(@NonNull CompletableFuture<Integer> future,
            @Nullable String tag, @Nullable String methodName,
            @Nullable CancellationGroup cancellationGroup, @DurationMillisLong long timeoutMillis) {
        final Integer obj = getValueOrNullInternal(future, tag, methodName, timeoutMillis,
                cancellationGroup);
        return obj != null ? obj : 0;
    }

    /**
     * Return the result of the given {@link CompletableFuture<T>}.
     *
     * <p>This method is agnostic to {@link Thread#interrupt()}.</p>
     *
     * <p>CAVEAT: when {@code cancellationGroup} is specified and it is signalled, {@code future}
     * will be cancelled permanently.  You have to duplicate the {@link CompletableFuture} if you
     * want to avoid this side-effect.</p>
     *
     * @param future the object to extract the result from.
     * @param tag tag name for logging. Pass {@code null} to disable logging.
     * @param methodName method name for logging. Pass {@code null} to disable logging.
     * @param cancellationGroup an optional {@link CancellationGroup} to cancel {@code future}
     *                          object. Can be {@code null}.
     * @param timeoutMillis length of the timeout in millisecond.
     * @param <T> Type of the result.
     * @return the result if it is completed within the given timeout. {@code null} otherwise.
     */
    @AnyThread
    @Nullable
    public static <T> T getResultOrNull(@NonNull CompletableFuture<T> future, @Nullable String tag,
            @Nullable String methodName, @Nullable CancellationGroup cancellationGroup,
            @DurationMillisLong long timeoutMillis) {
        return getValueOrNullInternal(future, tag, methodName, timeoutMillis, cancellationGroup);
    }
}
