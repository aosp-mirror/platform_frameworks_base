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
package androidx.window.extensions.util;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.core.util.function.Consumer;

/**
 * A utility class that will not report a value if it is the same as the last reported value.
 * @param <T> generic values to be reported.
 */
public class DeduplicateConsumer<T> implements Consumer<T> {

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    @Nullable
    private T mLastReportedValue = null;
    @NonNull
    private final Consumer<T> mConsumer;

    public DeduplicateConsumer(@NonNull Consumer<T> consumer) {
        mConsumer = consumer;
    }

    /**
     * Returns {@code true} if the given consumer matches this object or the wrapped
     * {@link Consumer}, {@code false} otherwise
     */
    public boolean matchesConsumer(@NonNull Consumer<T> consumer) {
        return consumer == this || mConsumer.equals(consumer);
    }

    /**
     * Accepts a new value and relays it if it is different from
     * the last reported value.
     * @param value to report if different.
     */
    @Override
    public void accept(@NonNull T value) {
        synchronized (mLock) {
            if (mLastReportedValue != null && mLastReportedValue.equals(value)) {
                return;
            }
            mLastReportedValue = value;
        }
        mConsumer.accept(value);
    }
}
