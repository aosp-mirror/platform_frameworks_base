/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util;

import java.util.List;
import java.util.function.Consumer;

public class Utils {

    /**
     * Allows lambda iteration over a list. It is done in reverse order so it is safe
     * to add or remove items during the iteration.
     */
    public static <T> void safeForeach(List<T> list, Consumer<T> c) {
        for (int i = list.size() - 1; i >= 0; i--) {
            c.accept(list.get(i));
        }
    }
}
