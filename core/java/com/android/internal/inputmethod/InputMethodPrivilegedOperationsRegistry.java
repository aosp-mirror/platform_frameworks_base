/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * A weak-reference-based mapper from IME token to {@link InputMethodPrivilegedOperations} that is
 * used only to support deprecated IME APIs in {@link android.view.inputmethod.InputMethodManager}.
 *
 * <p>This class is designed to be used as a per-process global registry.</p>
 */
public final class InputMethodPrivilegedOperationsRegistry {
    private InputMethodPrivilegedOperationsRegistry() {
        // Not intended to be instantiated.
    }

    private static final Object sLock = new Object();

    @Nullable
    @GuardedBy("sLock")
    private static WeakHashMap<IBinder, WeakReference<InputMethodPrivilegedOperations>> sRegistry;

    @Nullable
    private static InputMethodPrivilegedOperations sNop;

    @NonNull
    @AnyThread
    private static InputMethodPrivilegedOperations getNopOps() {
        // Strict thread-safety is not necessary because temporarily creating multiple nop instance
        // is basically harmless
        if (sNop == null) {
            sNop = new InputMethodPrivilegedOperations();
        }
        return sNop;
    }

    /**
     * Put a new entry to the registry.
     *
     * <p>Note: {@link InputMethodPrivilegedOperationsRegistry} does not hold strong reference to
     * {@code token} and {@code ops}.  The caller must be responsible for holding strong references
     * to those objects, that is until {@link android.inputmethodservice.InputMethodService} is
     * destroyed.</p>
     *
     * @param token IME token
     * @param ops {@link InputMethodPrivilegedOperations} to be associated with the given IME token
     */
    @AnyThread
    public static void put(IBinder token, InputMethodPrivilegedOperations ops) {
        synchronized (sLock) {
            if (sRegistry == null) {
                sRegistry = new WeakHashMap<>();
            }
            sRegistry.put(token, new WeakReference<>(ops));
        }
    }

    /**
     * Get a {@link InputMethodPrivilegedOperations} from the given IME token.  If it is not
     * available, return a fake instance that does nothing except for showing warning messages.
     *
     * @param token IME token
     * @return real {@link InputMethodPrivilegedOperations} object if {@code token} is still valid.
     *         Otherwise a fake instance of {@link InputMethodPrivilegedOperations} hat does nothing
     *         except for showing warning messages
     */
    @NonNull
    @AnyThread
    public static InputMethodPrivilegedOperations get(IBinder token) {
        synchronized (sLock) {
            if (sRegistry == null) {
                return getNopOps();
            }
            final WeakReference<InputMethodPrivilegedOperations> wrapperRef = sRegistry.get(token);
            if (wrapperRef == null) {
                return getNopOps();
            }
            final InputMethodPrivilegedOperations wrapper = wrapperRef.get();
            if (wrapper == null) {
                return getNopOps();
            }
            return wrapper;
        }
    }

    /**
     * Explicitly removes the specified entry.
     *
     * <p>Note: Calling this method is optional. In general, {@link WeakHashMap} and
     * {@link WeakReference} guarantee that the entry will be removed after specified binder proxies
     * are garbage collected.</p>
     *
     * @param token IME token to be removed.
     */
    @AnyThread
    public static void remove(IBinder token) {
        synchronized (sLock) {
            if (sRegistry == null) {
                return;
            }
            sRegistry.remove(token);
            if (sRegistry.isEmpty()) {
                sRegistry = null;
            }
        }
    }
}
