/*
 * Copyright (C) 2010 The Android Open Source Project
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

package libcore.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

public final class Objects {
    private Objects() {}

    /**
     * Returns a string reporting the value of each declared field, via reflection.
     * Static and transient fields are automatically skipped. Produces output like
     * "SimpleClassName[integer=1234,string="hello",character='c',intArray=[1,2,3]]".
     */
    public static String toString(Object o) {
        Class<?> c = o.getClass();
        StringBuilder sb = new StringBuilder();
        sb.append(c.getSimpleName()).append('[');
        int i = 0;
        for (Field f : c.getDeclaredFields()) {
            if ((f.getModifiers() & (Modifier.STATIC | Modifier.TRANSIENT)) != 0) {
                continue;
            }
            f.setAccessible(true);
            try {
                Object value = f.get(o);

                if (i++ > 0) {
                    sb.append(',');
                }

                sb.append(f.getName());
                sb.append('=');

                if (value.getClass().isArray()) {
                    if (value.getClass() == boolean[].class) {
                        sb.append(Arrays.toString((boolean[]) value));
                    } else if (value.getClass() == byte[].class) {
                        sb.append(Arrays.toString((byte[]) value));
                    } else if (value.getClass() == char[].class) {
                        sb.append(Arrays.toString((char[]) value));
                    } else if (value.getClass() == double[].class) {
                        sb.append(Arrays.toString((double[]) value));
                    } else if (value.getClass() == float[].class) {
                        sb.append(Arrays.toString((float[]) value));
                    } else if (value.getClass() == int[].class) {
                        sb.append(Arrays.toString((int[]) value));
                    } else if (value.getClass() == long[].class) {
                        sb.append(Arrays.toString((long[]) value));
                    } else if (value.getClass() == short[].class) {
                        sb.append(Arrays.toString((short[]) value));
                    } else {
                        sb.append(Arrays.toString((Object[]) value));
                    }
                } else if (value.getClass() == Character.class) {
                    sb.append('\'').append(value).append('\'');
                } else if (value.getClass() == String.class) {
                    sb.append('"').append(value).append('"');
                } else {
                    sb.append(value);
                }
            } catch (IllegalAccessException unexpected) {
                throw new AssertionError(unexpected);
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
