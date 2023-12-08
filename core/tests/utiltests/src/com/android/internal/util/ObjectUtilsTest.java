/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.internal.util;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ObjectUtilsTest {
    @Test
    public void testCompare() {
        assertEquals(0, ObjectUtils.compare(null, null));
        assertEquals(1, ObjectUtils.compare("a", null));
        assertEquals(-1, ObjectUtils.compare(null, "a"));

        assertEquals(0, ObjectUtils.compare("a", "a"));

        assertEquals(-1, ObjectUtils.compare("a", "b"));
        assertEquals(1, ObjectUtils.compare("b", "a"));
    }
}
