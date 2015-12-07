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

import com.google.android.collect.Lists;
import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

public class IndentingPrintWriterBenchmark {

    private PrintWriter mDirect;
    private IndentingPrintWriter mIndenting;

    private Node mSimple;
    private Node mComplex;

    @BeforeExperiment
    protected void setUp() throws IOException {
        final FileOutputStream os = new FileOutputStream(new File("/dev/null"));
        mDirect = new PrintWriter(os);
        mIndenting = new IndentingPrintWriter(mDirect, "  ");

        final Node manyChildren = Node.build("ManyChildren", Node.build("1"), Node.build("2"),
                Node.build("3"), Node.build("4"), Node.build("5"), Node.build("6"), Node.build("7"),
                Node.build("8"), Node.build("9"), Node.build("10"));

        mSimple = Node.build("RED");
        mComplex = Node.build("PARENT", Node.build("RED"), Node.build("GREEN",
                Node.build("BLUE", manyChildren, manyChildren), manyChildren, manyChildren),
                manyChildren);
    }

    @AfterExperiment
    protected void tearDown() {
        mIndenting.close();
        mIndenting = null;
        mDirect = null;
    }

    public void timeSimpleDirect(int reps) {
        for (int i = 0; i < reps; i++) {
            mSimple.dumpDirect(mDirect, 0);
        }
    }

    public void timeSimpleIndenting(int reps) {
        for (int i = 0; i < reps; i++) {
            mSimple.dumpIndenting(mIndenting);
        }
    }

    public void timeComplexDirect(int reps) {
        for (int i = 0; i < reps; i++) {
            mComplex.dumpDirect(mDirect, 0);
        }
    }

    public void timeComplexIndenting(int reps) {
        for (int i = 0; i < reps; i++) {
            mComplex.dumpIndenting(mIndenting);
        }
    }

    public void timePairRaw(int reps) {
        final int value = 1024;
        for (int i = 0; i < reps; i++) {
            mDirect.print("key=");
            mDirect.print(value);
            mDirect.print(" ");
        }
    }

    public void timePairIndenting(int reps) {
        final int value = 1024;
        for (int i = 0; i < reps; i++) {
            mIndenting.printPair("key", value);
        }
    }

    private static class Node {
        public String name;
        public ArrayList<Node> children;

        private static String[] sIndents = new String[] { "", "  ", "    ", "      ", "        " };

        public static Node build(String name, Node... children) {
            Node node = new Node();
            node.name = name;
            if (children != null && children.length > 0) {
                node.children = Lists.newArrayList(children);
            }
            return node;
        }

        private void dumpSelf(PrintWriter pw) {
            pw.print("Node ");
            pw.print(name);
            pw.print(" first ");
            pw.print(512);
            pw.print(" second ");
            pw.print(1024);
            pw.print(" third ");
            pw.println(2048);
        }

        public void dumpDirect(PrintWriter pw, int depth) {
            pw.print(sIndents[depth]);
            dumpSelf(pw);

            if (children != null) {
                for (Node child : children) {
                    child.dumpDirect(pw, depth + 1);
                }
            }

            pw.println();
        }

        public void dumpIndenting(IndentingPrintWriter pw) {
            dumpSelf(pw);

            if (children != null) {
                pw.increaseIndent();
                for (Node child : children) {
                    child.dumpIndenting(pw);
                }
                pw.decreaseIndent();
            }

            pw.println();
        }
    }
}
