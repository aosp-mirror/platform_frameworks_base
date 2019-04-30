/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.util;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Utility classes to write tests for stable AIDL parceling/unparceling
 */
public final class ParcelableTestUtil {

    /**
     * Verifies that the number of nonstatic fields in a class equals a given count.
     *
     * <p>This assertion serves as a reminder to update test code around it if fields are added
     * after the test is written.
     * @param count Expected number of nonstatic fields in the class.
     * @param clazz Class to test.
     */
    public static <T> void assertFieldCountEquals(int count, Class<T> clazz) {
        assertEquals(count, Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers())).count());
    }
}
