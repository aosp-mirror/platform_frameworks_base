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
import android.os.Binder;

/**
 * An RAII-style object, used to establish a scope in which the binder calling identity is cleared.
 *
 * <p>
 * Intended usage:
 * <pre>
 * void caller() {
 *   try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
 *       // Within this scope the binder calling identity is cleared.
 *       ...
 *   }
 *   // Outside the scope the calling identity is restored to its prior state.
 * </pre>
 *
 * @hide
 */
public class ClearCallingIdentityContext implements SafeCloseable {
    private final long mRestoreKey;

    /**
     * Creates a new instance.
     * @return A {@link SafeCloseable}, intended to be used in a try-with-resource block.
     */
    public static @NonNull
    SafeCloseable create() {
        return new ClearCallingIdentityContext();
    }

    @SuppressWarnings("AndroidFrameworkBinderIdentity")
    private ClearCallingIdentityContext() {
        mRestoreKey = Binder.clearCallingIdentity();
    }

    @Override
    @SuppressWarnings("AndroidFrameworkBinderIdentity")
    public void close() {
        Binder.restoreCallingIdentity(mRestoreKey);
    }
}
