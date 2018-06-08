/**
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

package com.android.server.broadcastradio.hal2;

/**
 * A wrapper class for mutable objects to be used in non-mutable contexts
 * (i.e. final variables catched in lambda closures).
 *
 * @param <E> type of boxed value.
 */
final class Mutable<E> {
    /**
     * A mutable value.
     */
    public E value;

    /**
     * Initialize value with null pointer.
     */
    public Mutable() {
        value = null;
    }

    /**
     * Initialize value with specific value.
     *
     * @param value initial value.
     */
    public Mutable(E value) {
        this.value = value;
    }
}
