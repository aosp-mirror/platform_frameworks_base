/*
 * Copyright (C) 2009 The Android Open Source Project
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

import java.io.IOException;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Prints HTML containing removed and added files.
 */
public class PrintHtmlDiff {

    private static final String OLD_PRELOADED_CLASSES
            = "old-preloaded-classes";

    public static void main(String[] args) throws IOException,
            ClassNotFoundException {
        Root root = Root.fromFile(args[0]);

        BufferedReader oldClasses = new BufferedReader(
            new FileReader(OLD_PRELOADED_CLASSES));

        // Classes loaded implicitly by the zygote.
        Set<LoadedClass> zygote = new HashSet<LoadedClass>();
        for (Proc proc : root.processes.values()) {
            if (proc.name.equals("zygote")) {
                for (Operation op : proc.operations) {
                    zygote.add(op.loadedClass);
                }
                break;
            }
        }

        Set<LoadedClass> removed = new TreeSet<LoadedClass>();
        Set<LoadedClass> added = new TreeSet<LoadedClass>();

        for (LoadedClass loadedClass : root.loadedClasses.values()) {
            if (loadedClass.preloaded && !zygote.contains(loadedClass)) {
                added.add(loadedClass);
            }
        }

        String line;
        while ((line = oldClasses.readLine()) != null) {
            line = line.trim();
            LoadedClass clazz = root.loadedClasses.get(line);
            if (clazz != null) {
                added.remove(clazz);
                if (!clazz.preloaded) removed.add(clazz);
            }
        }

        PrintStream out = System.out;

        out.println("<html><body>");
        out.println("<style>");
        out.println("a, th, td, h2 { font-family: arial }");
        out.println("th, td { font-size: small }");
        out.println("</style>");
        out.println("<script src=\"sorttable.js\"></script>");
        out.println("<p><a href=\"#removed\">Removed</a>");
        out.println("<a name=\"added\"/><h2>Added</h2>");
        printTable(out, root.baseline, added);
        out.println("<a name=\"removed\"/><h2>Removed</h2>");
        printTable(out, root.baseline, removed);
        out.println("</body></html>");
    }

    static void printTable(PrintStream out, MemoryUsage baseline,
            Iterable<LoadedClass> classes) {
        out.println("<table border=\"1\" cellpadding=\"5\""
                + " class=\"sortable\">");

        out.println("<thead><tr>");
        out.println("<th>Name</th>");
        out.println("<th>Load Time (us)</th>");
        out.println("<th>Loaded By</th>");
        out.println("<th>Heap (B)</th>");
        out.println("<th>Pages</th>");
        out.println("</tr></thead>");

        for (LoadedClass clazz : classes) {
            out.println("<tr>");
            out.println("<td>" + clazz.name + "</td>");
            out.println("<td>" + clazz.medianTimeMicros() + "</td>");

            out.println("<td>");
            Set<String> procNames = new TreeSet<String>();
            for (Operation op : clazz.loads) procNames.add(op.process.name);
            for (Operation op : clazz.initializations) {
                procNames.add(op.process.name);
            }
            if (procNames.size() <= 3) {
                for (String name : procNames) {
                    out.print(name + "<br/>");
                }
            } else {
                Iterator<String> i = procNames.iterator();
                out.print(i.next() + "<br/>");
                out.print(i.next() + "<br/>");
                out.print("...and " + (procNames.size() - 2)
                        + " others.");
            }
            out.println("</td>");

            if (clazz.memoryUsage.isAvailable()) {
                MemoryUsage subtracted
                        = clazz.memoryUsage.subtract(baseline);

                out.println("<td>" + (subtracted.javaHeapSize()
                        + subtracted.nativeHeapSize) + "</td>");
                out.println("<td>" + subtracted.totalPages() + "</td>");
            } else {
                for (int i = 0; i < 2; i++) {
                    out.println("<td>n/a</td>");                    
                }
            }

            out.println("</tr>");
        }

        out.println("</table>");
    }
}
