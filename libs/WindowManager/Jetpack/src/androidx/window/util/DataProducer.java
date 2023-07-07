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

package androidx.window.util;

import android.annotation.NonNull;

import java.util.function.Consumer;

/**
 * Produces data through {@link DataProducer#getData} and provides a mechanism for receiving
 * a callback when the data managed by the produces has changed.
 *
 * @param <T> The type of data this producer returns through {@link DataProducer#getData}.
 */
public interface DataProducer<T> {
    /**
     * Emits the first available data at that point in time.
     * @param dataConsumer a {@link Consumer} that will receive one value.
     */
    void getData(@NonNull Consumer<T> dataConsumer);

    /**
     * Adds a callback to be notified when the data returned
     * from {@link DataProducer#getData} has changed.
     */
    void addDataChangedCallback(@NonNull Consumer<T> callback);

    /** Removes a callback previously added with {@link #addDataChangedCallback(Consumer)}. */
    void removeDataChangedCallback(@NonNull Consumer<T> callback);
}
