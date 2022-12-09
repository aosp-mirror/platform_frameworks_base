/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * A base class that works with {@link BaseDataProducer} to add/remove a consumer that should
 * only be used once when {@link BaseDataProducer#notifyDataChanged} is called.
 * @param <T> The type of data this producer returns through {@link DataProducer#getData}.
 */
public class AcceptOnceConsumer<T> implements Consumer<T> {
    private final Consumer<T> mCallback;
    private final DataProducer<T> mProducer;

    public AcceptOnceConsumer(@NonNull DataProducer<T> producer, @NonNull Consumer<T> callback) {
        mProducer = producer;
        mCallback = callback;
    }

    @Override
    public void accept(@NonNull T t) {
        mCallback.accept(t);
        mProducer.removeDataChangedCallback(this);
    }
}
