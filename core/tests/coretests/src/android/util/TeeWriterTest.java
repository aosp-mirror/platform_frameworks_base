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

package android.util;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;

@RunWith(AndroidJUnit4.class)
public class TeeWriterTest {
    @Test
    public void testEmpty() throws Exception {
        // Verify we don't crash when writing nowhere
        final TeeWriter writer = new TeeWriter();
        writer.write("EXAMPLE!");
    }

    @Test
    public void testSimple() throws Exception {
        final ByteArrayOutputStream first = new ByteArrayOutputStream();
        final ByteArrayOutputStream second = new ByteArrayOutputStream();
        final ByteArrayOutputStream third = new ByteArrayOutputStream();

        final TeeWriter writer = new TeeWriter(
                new OutputStreamWriter(first),
                new OutputStreamWriter(second),
                new OutputStreamWriter(third));

        writer.write("EXAMPLE!");
        writer.flush();
        assertEquals("EXAMPLE!", first.toString());
        assertEquals("EXAMPLE!", second.toString());
        assertEquals("EXAMPLE!", third.toString());
    }
}
