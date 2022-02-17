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
package com.android.server.timezonedetector;

import android.annotation.NonNull;
import android.util.ArraySet;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A helper class that turns a set of objects into ordinal values, i.e. each object is offered
 * up via {@link #ordinal(Object)} or similar method, and a number will be returned. If the
 * object has been seen before by the instance then the same number will be returned. Intended
 * for situations where it is useful to know if values from some finite set are the same or
 * different, but the value is either large or may reveal PII. This class relies on {@link
 * Object#equals(Object)} and {@link Object#hashCode()}.
 */
class OrdinalGenerator<T> {
    private final ArraySet<T> mKnownIds = new ArraySet<>();

    @NonNull private final Function<T, T> mCanonicalizationFunction;

    OrdinalGenerator(@NonNull Function<T, T> canonicalizationFunction) {
        mCanonicalizationFunction = Objects.requireNonNull(canonicalizationFunction);
    }

    int ordinal(T object) {
        T canonical = mCanonicalizationFunction.apply(object);

        int ordinal = mKnownIds.indexOf(canonical);
        if (ordinal < 0) {
            ordinal = mKnownIds.size();
            mKnownIds.add(canonical);
        }
        return ordinal;
    }

    int[] ordinals(List<T> objects) {
        int[] ordinals = new int[objects.size()];
        for (int i = 0; i < ordinals.length; i++) {
            ordinals[i] = ordinal(objects.get(i));
        }
        return ordinals;
    }
}
