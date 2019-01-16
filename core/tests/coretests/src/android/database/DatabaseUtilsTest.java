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

package android.database;

import static android.database.DatabaseUtils.bindSelection;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DatabaseUtilsTest {
    private static final Object[] ARGS = { "baz", 4, null };

    @Test
    public void testBindSelection_none() throws Exception {
        assertEquals(null,
                bindSelection(null, ARGS));
        assertEquals("",
                bindSelection("", ARGS));
        assertEquals("foo=bar",
                bindSelection("foo=bar", ARGS));
    }

    @Test
    public void testBindSelection_normal() throws Exception {
        assertEquals("foo='baz'",
                bindSelection("foo=?", ARGS));
        assertEquals("foo='baz' AND bar=4",
                bindSelection("foo=? AND bar=?", ARGS));
        assertEquals("foo='baz' AND bar=4 AND meow=NULL",
                bindSelection("foo=? AND bar=? AND meow=?", ARGS));
    }

    @Test
    public void testBindSelection_whitespace() throws Exception {
        assertEquals("BETWEEN 5 AND 10",
                bindSelection("BETWEEN? AND ?", 5, 10));
        assertEquals("IN 'foo'",
                bindSelection("IN?", "foo"));
    }

    @Test
    public void testBindSelection_indexed() throws Exception {
        assertEquals("foo=10 AND bar=11 AND meow=1",
                bindSelection("foo=?10 AND bar=? AND meow=?1",
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
    }
}
