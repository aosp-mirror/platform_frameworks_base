/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

/**
 * Tests for {@link IndentingPrintWriter}.
 */
@RunWith(AndroidJUnit4.class)
public class IndentingPrintWriterTest {

    private ByteArrayOutputStream mStream;
    private PrintWriter mWriter;

    @Before
    public void setUp() throws Exception {
        mStream = new ByteArrayOutputStream();
        mWriter = new PrintWriter(mStream);
    }

    @Test
    public void testMultipleIndents() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ");

        pw.print("Hello");
        pw.increaseIndent();
        pw.println();
        pw.print("World");
        pw.increaseIndent();
        pw.println();
        pw.print("And");
        pw.decreaseIndent();
        pw.println();
        pw.print("Goodbye");
        pw.decreaseIndent();
        pw.println();
        pw.print("World");
        pw.println();

        pw.flush();
        assertEquals("Hello\n  World\n    And\n  Goodbye\nWorld\n", mStream.toString());
    }

    @Test
    public void testAdjustIndentAfterNewline() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ");

        pw.println("Hello");
        pw.increaseIndent();
        pw.println("World");

        pw.flush();
        assertEquals("Hello\n  World\n", mStream.toString());
    }

    @Test
    public void testWrapping() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "", 10);

        pw.print("dog ");
        pw.print("cat ");
        pw.print("cow ");
        pw.print("meow ");

        pw.flush();
        assertEquals("dog cat \ncow meow ", mStream.toString());
    }

    @Test
    public void testWrappingIndented() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "    ", 10);

        pw.increaseIndent();
        pw.print("dog ");
        pw.print("meow ");
        pw.print("a ");
        pw.print("b ");
        pw.print("cow ");

        pw.flush();
        assertEquals("    dog \n    meow \n    a b \n    cow ", mStream.toString());
    }

    @Test
    public void testWrappingEmbeddedNewlines() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ", 10);

        pw.increaseIndent();
        pw.print("Lorem ipsum \ndolor sit \namet, consectetur \nadipiscing elit.");

        pw.flush();
        assertEquals("  Lorem ip\n  sum \n  dolor si\n  t \n  amet, co\n"
                + "  nsectetu\n  r \n  adipisci\n  ng elit.\n", mStream.toString());
    }

    @Test
    public void testWrappingSingleGiant() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ", 10);

        pw.increaseIndent();
        pw.print("Lorem ipsum dolor sit amet, consectetur adipiscing elit.");

        pw.flush();
        assertEquals("  Lorem ip\n  sum dolo\n  r sit am\n  et, cons\n"
                + "  ectetur \n  adipisci\n  ng elit.\n", mStream.toString());
    }

    @Test
    public void testWrappingPrefixedGiant() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ", 10);

        pw.increaseIndent();
        pw.print("foo");
        pw.print("Lorem ipsum dolor sit amet, consectetur adipiscing elit.");

        pw.flush();
        assertEquals("  foo\n  Lorem ip\n  sum dolo\n  r sit am\n  et, cons\n"
                + "  ectetur \n  adipisci\n  ng elit.\n", mStream.toString());
    }

    @Test
    public void testArrayValue() {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ");
        pw.increaseIndent();
        pw.print("strings", new String[]{"a", "b", "c"});
        pw.print("ints", new int[]{1, 2});
        pw.flush();

        assertEquals("  strings=[a, b, c] ints=[1, 2] ", mStream.toString());
    }
}
