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

package androidx.window.common.collections;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A class to contain utility methods for {@link List}.
 */
public final class ListUtil {

    private ListUtil() {}

    /**
     * Returns a new {@link List} that is created by applying the {@code transformer} to the
     * {@code source} list.
     */
    public static <T, U> List<U> map(List<T> source, Function<T, U> transformer) {
        final List<U> target = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            target.add(transformer.apply(source.get(i)));
        }
        return target;
    }
}
