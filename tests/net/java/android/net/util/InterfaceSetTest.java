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

package android.net.util;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class InterfaceSetTest {
    @Test
    public void testNullNamesIgnored() {
        final InterfaceSet set = new InterfaceSet(null, "if1", null, "if2", null);
        assertEquals(2, set.ifnames.size());
        assertTrue(set.ifnames.contains("if1"));
        assertTrue(set.ifnames.contains("if2"));
    }

    @Test
    public void testToString() {
        final InterfaceSet set = new InterfaceSet("if1", "if2");
        final String setString = set.toString();
        assertTrue(setString.equals("[if1,if2]") || setString.equals("[if2,if1]"));
    }

    @Test
    public void testToString_Empty() {
        final InterfaceSet set = new InterfaceSet(null, null);
        assertEquals("[]", set.toString());
    }

    @Test
    public void testEquals() {
        assertEquals(new InterfaceSet(null, "if1", "if2"), new InterfaceSet("if2", "if1"));
        assertEquals(new InterfaceSet(null, null), new InterfaceSet());
        assertFalse(new InterfaceSet("if1", "if3").equals(new InterfaceSet("if1", "if2")));
        assertFalse(new InterfaceSet("if1", "if2").equals(new InterfaceSet("if1")));
        assertFalse(new InterfaceSet().equals(null));
    }
}
