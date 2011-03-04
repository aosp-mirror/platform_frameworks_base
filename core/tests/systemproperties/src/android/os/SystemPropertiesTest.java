/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.os;

import junit.framework.TestCase;

import android.os.SystemProperties;
import android.test.suitebuilder.annotation.SmallTest;

public class SystemPropertiesTest extends TestCase {
    private static final String KEY = "sys.testkey";
    private static final String PERSIST_KEY = "persist.sys.testkey";

    @SmallTest
    public void testStressPersistPropertyConsistency() throws Exception {
        for (int i = 0; i < 100; ++i) {
            SystemProperties.set(PERSIST_KEY, Long.toString(i));
            long ret = SystemProperties.getLong(PERSIST_KEY, -1);
            assertEquals(i, ret);
        }
    }

    @SmallTest
    public void testStressMemoryPropertyConsistency() throws Exception {
        for (int i = 0; i < 100; ++i) {
            SystemProperties.set(KEY, Long.toString(i));
            long ret = SystemProperties.getLong(KEY, -1);
            assertEquals(i, ret);
        }
    }

    @SmallTest
    public void testProperties() throws Exception {
        String value;

        SystemProperties.set(KEY, "");
        value = SystemProperties.get(KEY, "default");
        assertEquals("default", value);

        SystemProperties.set(KEY, "SA");
        value = SystemProperties.get(KEY, "default");
        assertEquals("SA", value);

        value = SystemProperties.get(KEY);
        assertEquals("SA", value);

        SystemProperties.set(KEY, "");
        value = SystemProperties.get(KEY, "default");
        assertEquals("default", value);

        value = SystemProperties.get(KEY);
        assertEquals("", value);
    }
}
