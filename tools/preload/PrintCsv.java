/*
 * Copyright (C) 2008 The Android Open Source Project
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
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.io.BufferedInputStream;
import java.io.Writer;
import java.io.PrintStream;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.Iterator;

/**
 * Prints raw information in CSV format.
 */
public class PrintCsv {

    public static void main(String[] args)
            throws IOException, ClassNotFoundException {
        if (args.length != 1) {
            System.err.println("Usage: PrintCsv [compiled log file]");
            System.exit(0);
        }

        Root root = Root.fromFile(args[0]);

        printHeaders(System.out);

        MemoryUsage baseline = MemoryUsage.baseline();

        for (LoadedClass loadedClass : root.loadedClasses.values()) {
            if (!loadedClass.systemClass) {
                continue;
            }

            printRow(System.out, baseline, loadedClass);
        }
    }

    static void printHeaders(PrintStream out) {
        out.println("Name"
                + ",Preloaded"
                + ",Median Load Time (us)"
                + ",Median Init Time (us)"
                + ",Process Names"
                + ",Load Count"
                + ",Init Count"
                + ",Managed Heap (B)"
                + ",Native Heap (B)"
                + ",Managed Pages (kB)"
                + ",Native Pages (kB)"
                + ",Other Pages (kB)");
    }

    static void printRow(PrintStream out, MemoryUsage baseline,
            LoadedClass loadedClass) {
        out.print(loadedClass.name);
        out.print(',');
        out.print(loadedClass.preloaded);
        out.print(',');
        out.print(loadedClass.medianLoadTimeMicros());
        out.print(',');
        out.print(loadedClass.medianInitTimeMicros());
        out.print(',');
        out.print('"');

        Set<String> procNames = new TreeSet<String>();
        for (Operation op : loadedClass.loads)
            procNames.add(op.process.name);
        for (Operation op : loadedClass.initializations)
            procNames.add(op.process.name);

        if (procNames.size() <= 3) {
            for (String name : procNames) {
                out.print(name + "\n");
            }
        } else {
            Iterator<String> i = procNames.iterator();
            out.print(i.next() + "\n");
            out.print(i.next() + "\n");
            out.print("...and " + (procNames.size() - 2)
                    + " others.");
        }

        out.print('"');
        out.print(',');
        out.print(loadedClass.loads.size());
        out.print(',');
        out.print(loadedClass.initializations.size());

        if (loadedClass.memoryUsage.isAvailable()) {
            MemoryUsage subtracted
                    = loadedClass.memoryUsage.subtract(baseline);

            out.print(',');
            out.print(subtracted.javaHeapSize());
            out.print(',');
            out.print(subtracted.nativeHeapSize);
            out.print(',');
            out.print(subtracted.javaPagesInK());
            out.print(',');
            out.print(subtracted.nativePagesInK());
            out.print(',');
            out.print(subtracted.otherPagesInK());

        } else {
            out.print(",n/a,n/a,n/a,n/a,n/a");
        }

        out.println();
    }
}
