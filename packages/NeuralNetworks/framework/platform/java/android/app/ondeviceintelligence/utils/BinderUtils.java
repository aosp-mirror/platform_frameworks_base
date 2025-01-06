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

package android.app.ondeviceintelligence.utils;

import android.annotation.NonNull;
import android.os.Binder;

import java.util.function.Supplier;

/**
 * Collection of utilities for {@link Binder} and related classes.
 * @hide
 */
public class BinderUtils {
    /**
     * Convenience method for running the provided action enclosed in
     * {@link Binder#clearCallingIdentity}/{@link Binder#restoreCallingIdentity}
     *
     * Any exception thrown by the given action will be caught and rethrown after the call to
     * {@link Binder#restoreCallingIdentity}
     *
     * Note that this is copied from Binder#withCleanCallingIdentity with minor changes
     * since it is not public.
     *
     * @hide
     */
    public static final <T extends Exception> void withCleanCallingIdentity(
            @NonNull ThrowingRunnable<T> action) throws T {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            action.run();
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Like a Runnable, but declared to throw an exception.
     *
     * @param <T> The exception class which is declared to be thrown.
     */
    @FunctionalInterface
    public interface ThrowingRunnable<T extends Exception> {
        /** @see java.lang.Runnable */
        void run() throws T;
    }

    /**
     * Convenience method for running the provided action enclosed in
     * {@link Binder#clearCallingIdentity}/{@link Binder#restoreCallingIdentity} returning the
     * result.
     *
     * <p>Any exception thrown by the given action will be caught and rethrown after
     * the call to {@link Binder#restoreCallingIdentity}.
     *
     * Note that this is copied from Binder#withCleanCallingIdentity with minor changes
     * since it is not public.
     *
     * @hide
     */
    public static final <T, E extends Exception> T withCleanCallingIdentity(
            @NonNull ThrowingSupplier<T, E> action) throws E {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            return action.get();
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * An equivalent of {@link Supplier}
     *
     * @param <T> The class which is declared to be returned.
     * @param <E> The exception class which is declared to be thrown.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        /** @see java.util.function.Supplier */
        T get() throws E;
    }
}