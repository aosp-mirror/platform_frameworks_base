/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.util.function;

import java.util.function.Consumer;

/**
 * A 9-argument {@link Consumer}
 *
 * @hide
 */
public interface NonaConsumer<A, B, C, D, E, F, G, H, I> {
    void accept(A a, B b, C c, D d, E e, F f, G g, H h, I i);
}
