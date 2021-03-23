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

import android.annotation.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Implementation of {@link DataProducer} that delegates calls to {@link #getData()} to the list of
 * provided child producers.
 * <p>
 * The value returned is based on the precedence of the supplied children where the producer with
 * index 0 has a higher precedence than producers that come later in the list. When a producer with
 * a higher precedence has a non-empty value returned from {@link #getData()}, its value will be
 * returned from an instance of this class, ignoring all other producers with lower precedence.
 *
 * @param <T> The type of data this producer returns through {@link #getData()}.
 */
public final class PriorityDataProducer<T> extends BaseDataProducer<T> {
    private final List<DataProducer<T>> mChildProducers;

    public PriorityDataProducer(List<DataProducer<T>> childProducers) {
        mChildProducers = childProducers;
        for (DataProducer<T> childProducer : mChildProducers) {
            childProducer.addDataChangedCallback(this::notifyDataChanged);
        }
    }

    @Nullable
    @Override
    public Optional<T> getData() {
        for (DataProducer<T> childProducer : mChildProducers) {
            final Optional<T> data = childProducer.getData();
            if (data.isPresent()) {
                return data;
            }
        }
        return Optional.empty();
    }
}
