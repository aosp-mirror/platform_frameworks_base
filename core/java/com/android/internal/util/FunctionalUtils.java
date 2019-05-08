/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.util;

import android.os.RemoteException;
import android.util.ExceptionUtils;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Utilities specific to functional programming
 */
public class FunctionalUtils {
    private FunctionalUtils() {}

    /**
     * Converts a lambda expression that throws a checked exception(s) into a regular
     * {@link Consumer} by propagating any checked exceptions as {@link RuntimeException}
     */
    public static <T> Consumer<T> uncheckExceptions(ThrowingConsumer<T> action) {
        return action;
    }

    /**
     * @see #uncheckExceptions(ThrowingConsumer)
     */
    public static <I, O> Function<I, O> uncheckExceptions(ThrowingFunction<I, O> action) {
        return action;
    }

    /**
     * @see #uncheckExceptions(ThrowingConsumer)
     */
    public static Runnable uncheckExceptions(ThrowingRunnable action) {
        return action;
    }

    /**
     * @see #uncheckExceptions(ThrowingConsumer)
     */
    public static <T> Supplier<T> uncheckExceptions(ThrowingSupplier<T> action) {
        return action;
    }

    /**
     * Wraps a given {@code action} into one that ignores any {@link RemoteException}s
     */
    public static <T> Consumer<T> ignoreRemoteException(RemoteExceptionIgnoringConsumer<T> action) {
        return action;
    }

    /**
     * Wraps the given {@link ThrowingRunnable} into one that handles any exceptions using the
     * provided {@code handler}
     */
    public static Runnable handleExceptions(ThrowingRunnable r, Consumer<Throwable> handler) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                handler.accept(t);
            }
        };
    }

    /**
     * An equivalent of {@link Runnable} that allows throwing checked exceptions
     *
     * This can be used to specify a lambda argument without forcing all the checked exceptions
     * to be handled within it
     */
    @FunctionalInterface
    @SuppressWarnings("FunctionalInterfaceMethodChanged")
    public interface ThrowingRunnable extends Runnable {
        void runOrThrow() throws Exception;

        @Override
        default void run() {
            try {
                runOrThrow();
            } catch (Exception ex) {
                throw ExceptionUtils.propagate(ex);
            }
        }
    }

    /**
     * An equivalent of {@link Supplier} that allows throwing checked exceptions
     *
     * This can be used to specify a lambda argument without forcing all the checked exceptions
     * to be handled within it
     */
    @FunctionalInterface
    @SuppressWarnings("FunctionalInterfaceMethodChanged")
    public interface ThrowingSupplier<T> extends Supplier<T> {
        T getOrThrow() throws Exception;

        @Override
        default T get() {
            try {
                return getOrThrow();
            } catch (Exception ex) {
                throw ExceptionUtils.propagate(ex);
            }
        }
    }
    /**
     * A {@link Consumer} that allows throwing checked exceptions from its single abstract method.
     *
     * Can be used together with {@link #uncheckExceptions} to effectively turn a lambda expression
     * that throws a checked exception into a regular {@link Consumer}
     */
    @FunctionalInterface
    @SuppressWarnings("FunctionalInterfaceMethodChanged")
    public interface ThrowingConsumer<T> extends Consumer<T> {
        void acceptOrThrow(T t) throws Exception;

        @Override
        default void accept(T t) {
            try {
                acceptOrThrow(t);
            } catch (Exception ex) {
                throw ExceptionUtils.propagate(ex);
            }
        }
    }

    /**
     * A {@link Consumer} that automatically ignores any {@link RemoteException}s.
     *
     * Used by {@link #ignoreRemoteException}
     */
    @FunctionalInterface
    @SuppressWarnings("FunctionalInterfaceMethodChanged")
    public interface RemoteExceptionIgnoringConsumer<T> extends Consumer<T> {
        void acceptOrThrow(T t) throws RemoteException;

        @Override
        default void accept(T t) {
            try {
                acceptOrThrow(t);
            } catch (RemoteException ex) {
                // ignore
            }
        }
    }

    /**
     * A {@link Function} that allows throwing checked exceptions from its single abstract method.
     *
     * Can be used together with {@link #uncheckExceptions} to effectively turn a lambda expression
     * that throws a checked exception into a regular {@link Function}
     *
     * @param <T> see {@link Function}
     * @param <R> see {@link Function}
     */
    @FunctionalInterface
    @SuppressWarnings("FunctionalInterfaceMethodChanged")
    public interface ThrowingFunction<T, R> extends Function<T, R> {
        /** @see ThrowingFunction */
        R applyOrThrow(T t) throws Exception;

        @Override
        default R apply(T t) {
            try {
                return applyOrThrow(t);
            } catch (Exception ex) {
                throw ExceptionUtils.propagate(ex);
            }
        }
    }
}
