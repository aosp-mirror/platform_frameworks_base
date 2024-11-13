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

package com.android.systemui.util

import junit.framework.Assert

/**
 * Assert that a list's values match the corresponding predicates.
 *
 * Useful to test animations or more complex objects where you only care about some of an object's
 * properties.
 */
fun <T> List<T>.assertValuesMatch(vararg matchers: (T) -> Boolean) {
    if (size != matchers.size) {
        Assert.fail("Expected size ${matchers.size}, but was size $size:\n$this")
    }

    for (i in indices) {
        if (!matchers[i].invoke(this[i])) {
            Assert.fail("Assertion failed. Element #$i did not match:\n${this[i]}")
        }
    }
}
