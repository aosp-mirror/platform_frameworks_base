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

import java.io.Serializable;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Memory usage information.
 */
class MemoryUsage implements Serializable {

    private static final long serialVersionUID = 0;

    static final MemoryUsage NOT_AVAILABLE = new MemoryUsage();
    
    static int errorCount = 0;

    // These values are in 1kB increments (not 4kB like you'd expect).
    final int nativeSharedPages;
    final int javaSharedPages;
    final int otherSharedPages;
    final int nativePrivatePages;
    final int javaPrivatePages;
    final int otherPrivatePages;

    final int allocCount;
    final int allocSize;
    final int freedCount;
    final int freedSize;
    final long nativeHeapSize;

    public MemoryUsage(String line) {
        String[] parsed = line.split(",");

        nativeSharedPages = Integer.parseInt(parsed[1]);
        javaSharedPages = Integer.parseInt(parsed[2]);
        otherSharedPages = Integer.parseInt(parsed[3]);
        nativePrivatePages = Integer.parseInt(parsed[4]);
        javaPrivatePages = Integer.parseInt(parsed[5]);
        otherPrivatePages = Integer.parseInt(parsed[6]);
        allocCount = Integer.parseInt(parsed[7]);
        allocSize = Integer.parseInt(parsed[8]);
        freedCount = Integer.parseInt(parsed[9]);
        freedSize = Integer.parseInt(parsed[10]);
        nativeHeapSize = Long.parseLong(parsed[11]);
    }

    MemoryUsage() {
        nativeSharedPages = -1;
        javaSharedPages = -1;
        otherSharedPages = -1;
        nativePrivatePages = -1;
        javaPrivatePages = -1;
        otherPrivatePages = -1;

        allocCount = -1;
        allocSize = -1;
        freedCount = -1;
        freedSize = -1;
        nativeHeapSize = -1;
    }

    MemoryUsage(int nativeSharedPages,
            int javaSharedPages,
            int otherSharedPages,
            int nativePrivatePages,
            int javaPrivatePages,
            int otherPrivatePages,
            int allocCount,
            int allocSize,
            int freedCount,
            int freedSize,
            long nativeHeapSize) {
        this.nativeSharedPages = nativeSharedPages;
        this.javaSharedPages = javaSharedPages;
        this.otherSharedPages = otherSharedPages;
        this.nativePrivatePages = nativePrivatePages;
        this.javaPrivatePages = javaPrivatePages;
        this.otherPrivatePages = otherPrivatePages;
        this.allocCount = allocCount;
        this.allocSize = allocSize;
        this.freedCount = freedCount;
        this.freedSize = freedSize;
        this.nativeHeapSize = nativeHeapSize;
    }

    MemoryUsage subtract(MemoryUsage baseline) {
        return new MemoryUsage(
                nativeSharedPages - baseline.nativeSharedPages,
                javaSharedPages - baseline.javaSharedPages,
                otherSharedPages - baseline.otherSharedPages,
                nativePrivatePages - baseline.nativePrivatePages,
                javaPrivatePages - baseline.javaPrivatePages,
                otherPrivatePages - baseline.otherPrivatePages,
                allocCount - baseline.allocCount,
                allocSize - baseline.allocSize,
                freedCount - baseline.freedCount,
                freedSize - baseline.freedSize,
                nativeHeapSize - baseline.nativeHeapSize);
    }

    int javaHeapSize() {
        return allocSize - freedSize;
    }

    int totalHeap() {
        return javaHeapSize() + (int) nativeHeapSize;
    }

    int javaPagesInK() {
        return javaSharedPages + javaPrivatePages;
    }

    int nativePagesInK() {
        return nativeSharedPages + nativePrivatePages;
    }
    int otherPagesInK() {
        return otherSharedPages + otherPrivatePages;
    }

    int totalPages() {
        return javaSharedPages + javaPrivatePages + nativeSharedPages +
                nativePrivatePages + otherSharedPages + otherPrivatePages;
    }

    /**
     * Was this information available?
     */
    boolean isAvailable() {
        return nativeSharedPages != -1;
    }

    /**
     * Measures baseline memory usage.
     */
    static MemoryUsage baseline() {
        return forClass(null);
    }

    private static final String CLASS_PATH = "-Xbootclasspath"
            + ":/system/framework/core.jar"
            + ":/system/framework/ext.jar"
            + ":/system/framework/framework.jar"
            + ":/system/framework/framework-tests.jar"
            + ":/system/framework/services.jar"
            + ":/system/framework/loadclass.jar";

    private static final String[] GET_DIRTY_PAGES = {
        "adb", "shell", "dalvikvm", CLASS_PATH, "LoadClass" };

    /**
     * Measures memory usage for the given class.
     */
    static MemoryUsage forClass(String className) {
        MeasureWithTimeout measurer = new MeasureWithTimeout(className);

        new Thread(measurer).start();

        synchronized (measurer) {
            if (measurer.memoryUsage == null) {
                // Wait up to 10s.
                try {
                    measurer.wait(30000);
                } catch (InterruptedException e) {
                    System.err.println("Interrupted waiting for measurement.");
                    e.printStackTrace();
                    return NOT_AVAILABLE;
                }

                // If it's still null.
                if (measurer.memoryUsage == null) {
                    System.err.println("Timed out while measuring "
                            + className + ".");
                    return NOT_AVAILABLE;
                }
            }

            System.err.println("Got memory usage for " + className + ".");
            return measurer.memoryUsage;
        }
    }

    static class MeasureWithTimeout implements Runnable {

        final String className;
        MemoryUsage memoryUsage = null;

        MeasureWithTimeout(String className) {
            this.className = className;
        }

        public void run() {
            MemoryUsage measured = measure();

            synchronized (this) {
                memoryUsage = measured;
                notifyAll();
            }
        }

        private MemoryUsage measure() {
            String[] commands = GET_DIRTY_PAGES;
            if (className != null) {
                List<String> commandList = new ArrayList<String>(
                        GET_DIRTY_PAGES.length + 1);
                commandList.addAll(Arrays.asList(commands));
                commandList.add(className);
                commands = commandList.toArray(new String[commandList.size()]);
            }

            try {
                final Process process = Runtime.getRuntime().exec(commands);

                final InputStream err = process.getErrorStream();

                // Send error output to stderr.
                Thread errThread = new Thread() {
                    @Override
                    public void run() {
                        copy(err, System.err);
                    }
                };
                errThread.setDaemon(true);
                errThread.start();

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line = in.readLine();
                if (line == null || !line.startsWith("DECAFBAD,")) {
                    System.err.println("Got bad response for " + className
                            + ": " + line + "; command was " + Arrays.toString(commands));
                    errorCount += 1;
                    return NOT_AVAILABLE;
                }

                in.close();
                err.close();
                process.destroy();                

                return new MemoryUsage(line);
            } catch (IOException e) {
                System.err.println("Error getting stats for "
                        + className + ".");                
                e.printStackTrace();
                return NOT_AVAILABLE;
            }
        }

    }

    /**
     * Copies from one stream to another.
     */
    private static void copy(InputStream in, OutputStream out) {
        byte[] buffer = new byte[1024];
        int read;
        try {
            while ((read = in.read(buffer)) > -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Measures memory usage information and stores it in the model. */
    public static void main(String[] args) throws IOException,
            ClassNotFoundException {
        Root root = Root.fromFile(args[0]);
        root.baseline = baseline();
        for (LoadedClass loadedClass : root.loadedClasses.values()) {
            if (loadedClass.systemClass) {
                loadedClass.measureMemoryUsage();
            }
        }
        root.toFile(args[0]);
    }
}
