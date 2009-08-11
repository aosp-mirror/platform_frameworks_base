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
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;

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

        System.out.println("Name"
                + ",Preloaded"
                + ",Median Load Time (us)"
                + ",Median Init Time (us)"
                + ",Process Names"
                + ",Load Count"
                + ",Init Count");
//                + ",Managed Heap (B)"
//                + ",Native Heap (B)"
//                + ",Managed Pages (kB)"
//                + ",Native Pages (kB)"
//                + ",Other Pages (kB)");

        MemoryUsage baseline = root.baseline;

        for (LoadedClass loadedClass : root.loadedClasses.values()) {
            if (!loadedClass.systemClass) {
                continue;
            }

            System.out.print(loadedClass.name);
            System.out.print(',');
            System.out.print(loadedClass.preloaded);
            System.out.print(',');
            System.out.print(loadedClass.medianLoadTimeMicros());
            System.out.print(',');
            System.out.print(loadedClass.medianInitTimeMicros());
            System.out.print(',');
            System.out.print('"');

            Set<String> procNames = new TreeSet<String>();
            for (Operation op : loadedClass.loads)
                procNames.add(op.process.name);
            for (Operation op : loadedClass.initializations)
                procNames.add(op.process.name);
            for (String name : procNames) {
                System.out.print(name + "\n");
            }
            
            System.out.print('"');
            System.out.print(',');
            System.out.print(loadedClass.loads.size());
            System.out.print(',');
            System.out.print(loadedClass.initializations.size());
/*
            if (loadedClass.memoryUsage.isAvailable()) {
                MemoryUsage subtracted
                        = loadedClass.memoryUsage.subtract(baseline);

                System.out.print(',');
                System.out.print(subtracted.javaHeapSize());
                System.out.print(',');
                System.out.print(subtracted.nativeHeapSize);
                System.out.print(',');
                System.out.print(subtracted.javaPagesInK());
                System.out.print(',');
                System.out.print(subtracted.nativePagesInK());
                System.out.print(',');
                System.out.print(subtracted.otherPagesInK());

            } else {
                System.out.print(",n/a,n/a,n/a,n/a,n/a");
            }
*/
            System.out.println();
        }
    }
}
