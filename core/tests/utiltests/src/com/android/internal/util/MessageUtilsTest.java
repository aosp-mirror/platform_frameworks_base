/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.junit.Assert.*;

import com.android.internal.util.MessageUtils;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import android.util.SparseArray;

import org.junit.Test;
import org.junit.runner.RunWith;


class A {
    // Should not see these.
    private int mMember;
    public final int CMD_NOT_STATIC = 7;
    private static final String CMD_NOT_INT = "not an integer";
    public static int CMD_NOT_FINAL = 34;
    public static final int kWrongPrefix = 99;

    // Should see these.
    private static final int CMD_DO_SOMETHING = 12;
    public static final int EVENT_SOMETHING_HAPPENED = 45;
}

class B {
    public static final int CMD_FOO = 56;
    public static final int EVENT_BAR = 55;
    public static final int NOTIFICATION_BAZ = 12;
}

/**
 * Unit tests for {@link com.android.util.MessageUtils}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class MessageUtilsTest {

    private static final Class[] CLASSES = { A.class, B.class };

    private SparseArray<String> makeSparseArray(int[] keys, String[] values) throws Exception {
        assertEquals("Must specify same number of keys and values", keys.length, values.length);
        SparseArray<String> out = new SparseArray<>();
        for (int i = 0; i < keys.length; i++) {
            out.put(keys[i], values[i]);
        }
        return out;
    }

    private void assertSparseArrayEquals(
            SparseArray<String> a1, SparseArray<String> a2) throws Exception {
        String msg = String.format("%s != %s", a1.toString(), a2.toString());
        assertEquals(msg, a1.size(), a2.size());
        int size = a1.size();
        for (int i = 0; i < size; i++) {
            assertEquals(msg, a1.keyAt(i), a2.keyAt(i));
            assertEquals(msg, a1.valueAt(i), a2.valueAt(i));
        }
    }

    @Test
    public void basicOperation() throws Exception {
        SparseArray<String> expected = makeSparseArray(
            new int[]{12, 45, 55, 56},
            new String[]{"CMD_DO_SOMETHING", "EVENT_SOMETHING_HAPPENED", "EVENT_BAR", "CMD_FOO"});
        assertSparseArrayEquals(expected, MessageUtils.findMessageNames(CLASSES));
    }

    @Test
    public void withPrefixes() throws Exception {
        SparseArray<String> expected = makeSparseArray(
            new int[]{45, 55},
            new String[]{"EVENT_SOMETHING_HAPPENED", "EVENT_BAR"});
        assertSparseArrayEquals(expected, MessageUtils.findMessageNames(CLASSES,
                new String[]{"EVENT_"}));
    }

    @Test(expected=MessageUtils.DuplicateConstantError.class)
    public void duplicateConstants() {
        MessageUtils.findMessageNames(CLASSES, new String[]{"CMD_", "NOTIFICATION_"});
    }

}
