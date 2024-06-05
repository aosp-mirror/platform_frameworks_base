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

package com.android.systemui.keyguard;

import androidx.annotation.NonNull;

import com.android.app.tracing.TraceUtils;

import kotlin.Unit;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Base class for lifecycles with observers.
 */
public class Lifecycle<T> {

    private final ArrayList<T> mObservers = new ArrayList<>();

    public void addObserver(@NonNull T observer) {
        mObservers.add(Objects.requireNonNull(observer));
    }

    public void removeObserver(T observer) {
        mObservers.remove(observer);
    }

    public void dispatch(Consumer<T> consumer) {
        for (int i = 0; i < mObservers.size(); i++) {
            final T observer = mObservers.get(i);
            TraceUtils.trace(() -> "dispatch#" + consumer.toString(), () -> {
                consumer.accept(observer);
                return Unit.INSTANCE;
            });
        }
    }

    /**
     * Will dispatch the consumer to the observer, along with a single argument of type<U>.
     */
    public <U> void dispatch(BiConsumer<T, U> biConsumer, U arg) {
        for (int i = 0; i < mObservers.size(); i++) {
            final T observer = mObservers.get(i);
            TraceUtils.trace(() -> "dispatch#" + biConsumer.toString(), () -> {
                biConsumer.accept(observer, arg);
                return Unit.INSTANCE;
            });
        }
    }
}
