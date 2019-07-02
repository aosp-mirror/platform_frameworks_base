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

package android.util;

import androidx.test.filters.LargeTest;

import junit.framework.TestCase;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@LargeTest
public class LocalLogTest extends TestCase {

    public void testA() {
        String[] lines = {
            "foo",
            "bar",
            "baz"
        };
        String[] want = lines;
        testcase(new LocalLog(10), lines, want);
    }

    public void testB() {
        String[] lines = {
            "foo",
            "bar",
            "baz"
        };
        String[] want = {};
        testcase(new LocalLog(0), lines, want);
    }

    public void testC() {
        String[] lines = {
            "dropped",
            "dropped",
            "dropped",
            "dropped",
            "dropped",
            "dropped",
            "foo",
            "bar",
            "baz",
        };
        String[] want = {
            "foo",
            "bar",
            "baz",
        };
        testcase(new LocalLog(3), lines, want);
    }

    void testcase(LocalLog logger, String[] input, String[] want) {
        for (String l : input) {
            logger.log(l);
        }
        verifyAllLines(want, dump(logger).split("\n"));
        verifyAllLines(reverse(want), reverseDump(logger).split("\n"));
    }

    void verifyAllLines(String[] wantLines, String[] gotLines) {
        for (int i = 0; i < wantLines.length; i++) {
            String want = wantLines[i];
            String got = gotLines[i];
            String msg = String.format("%s did not contain %s", quote(got), quote(want));
            assertTrue(msg, got.contains(want));
        }
    }

    static String dump(LocalLog logger) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        logger.dump(null, writer, new String[0]);
        return buffer.toString();
    }

    static String reverseDump(LocalLog logger) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        logger.reverseDump(null, writer, new String[0]);
        return buffer.toString();
    }

    static String quote(String s) {
        return '"' + s + '"';
    }

    static String[] reverse(String[] ary) {
        List<String> ls = Arrays.asList(ary);
        Collections.reverse(ls);
        return  ls.toArray(new String[ary.length]);
    }
}
