/*
 * Copyright (C) 2013 The Android Open Source Project
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

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

/**
 * Tests for {@link IndentingPrintWriter}.
 */
public class IndentingPrintWriterTest extends TestCase {

    private ByteArrayOutputStream mStream;
    private PrintWriter mWriter;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mStream = new ByteArrayOutputStream();
        mWriter = new PrintWriter(mStream);
    }

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

    public void testAdjustIndentAfterNewline() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ");

        pw.println("Hello");
        pw.increaseIndent();
        pw.println("World");

        pw.flush();
        assertEquals("Hello\n  World\n", mStream.toString());
    }

    public void testWrapping() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "", 10);

        pw.print("dog ");
        pw.print("cat ");
        pw.print("cow ");
        pw.print("meow ");

        pw.flush();
        assertEquals("dog cat \ncow meow ", mStream.toString());
    }

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

    public void testWrappingEmbeddedNewlines() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ", 10);

        pw.increaseIndent();
        pw.print("Lorem ipsum \ndolor sit \namet, consectetur \nadipiscing elit.");

        pw.flush();
        assertEquals("  Lorem ip\n  sum \n  dolor si\n  t \n  amet, co\n"
                + "  nsectetu\n  r \n  adipisci\n  ng elit.\n", mStream.toString());
    }

    public void testWrappingSingleGiant() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ", 10);

        pw.increaseIndent();
        pw.print("Lorem ipsum dolor sit amet, consectetur adipiscing elit.");

        pw.flush();
        assertEquals("  Lorem ip\n  sum dolo\n  r sit am\n  et, cons\n"
                + "  ectetur \n  adipisci\n  ng elit.\n", mStream.toString());
    }

    public void testWrappingPrefixedGiant() throws Exception {
        final IndentingPrintWriter pw = new IndentingPrintWriter(mWriter, "  ", 10);

        pw.increaseIndent();
        pw.print("foo");
        pw.print("Lorem ipsum dolor sit amet, consectetur adipiscing elit.");

        pw.flush();
        assertEquals("  foo\n  Lorem ip\n  sum dolo\n  r sit am\n  et, cons\n"
                + "  ectetur \n  adipisci\n  ng elit.\n", mStream.toString());
    }
}
