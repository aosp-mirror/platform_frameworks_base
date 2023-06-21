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

package com.android.internal.accessibility;

import com.android.internal.os.RoSystemProperties;

import java.lang.reflect.Field;

/**
 * Test utility methods.
 */
public class TestUtils {

    /**
     * Sets the {@code enabled} of the given OneHandedMode flags to simulate device behavior.
     */
    public static void setOneHandedModeEnabled(Object obj, boolean enabled) {
        try {
            final Field field = RoSystemProperties.class.getDeclaredField(
                    "SUPPORT_ONE_HANDED_MODE");
            field.setAccessible(true);
            field.setBoolean(obj, enabled);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
