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

package android.media.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * An RAII-style object, used to establish a scope in which a single identity is part of the
 * context. This is used in order to avoid having to explicitly pass identity information through
 * deep call-stacks.
 * <p>
 * Intended usage:
 * <pre>
 * void caller() {
 *   Identity originator = ...;
 *   try (SafeCloseable ignored = IdentityContext.create(originator)) {
 *       // Within this scope the context is established.
 *       callee();
 *   }
 *   // Outside the scope the context is restored to its prior state.
 *
 * void callee() {
 *     // Here we can access the identity without having to explicitly take it as an argument.
 *     // This is true even if this were a deeply nested call.
 *     Identity originator = IdentityContext.getNonNull();
 *     ...
 * }
 * </pre>
 *
 * @hide
 */
public class IdentityContext implements SafeCloseable {
    private static ThreadLocal<Identity> sThreadLocalIdentity = new ThreadLocal<>();
    private @Nullable Identity mPrior = get();

    /**
     * Create a scoped identity context.
     *
     * @param identity The identity to establish with the scope.
     * @return A {@link SafeCloseable}, to be used in a try-with-resources block to establish a
     * scope.
     */
    public static @NonNull
    SafeCloseable create(@Nullable Identity identity) {
        return new IdentityContext(identity);
    }

    /**
     * Get the current identity context.
     *
     * @return The identity, or null if it has not been established.
     */
    public static @Nullable
    Identity get() {
        return sThreadLocalIdentity.get();
    }

    /**
     * Get the current identity context. Throws a {@link NullPointerException} if it has not been
     * established.
     *
     * @return The identity.
     */
    public static @NonNull
    Identity getNonNull() {
        Identity result = get();
        if (result == null) {
            throw new NullPointerException("Identity context is not set");
        }
        return result;
    }

    private IdentityContext(@Nullable Identity identity) {
        set(identity);
    }

    @Override
    public void close() {
        set(mPrior);
    }

    private static void set(@Nullable Identity identity) {
        sThreadLocalIdentity.set(identity);
    }
}
