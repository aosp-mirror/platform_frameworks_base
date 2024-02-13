/*
 * Copyright (C) 2023 The Android Open Source Project
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
package android.platform.test.ravenwood;

import java.io.File;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * Utilities for writing (bivalent) ravenwood tests.
 */
public class RavenwoodUtils {
    private RavenwoodUtils() {
    }

    /**
     * Load a JNI library respecting {@code java.library.path}
     * (which reflects {@code LD_LIBRARY_PATH}).
     *
     * <p>{@code libname} must be the library filename without:
     * - directory
     * - "lib" prefix
     * - and the ".so" extension
     *
     * <p>For example, in order to load "libmyjni.so", then pass "myjni".
     *
     * <p>This is basically the same thing as Java's {@link System#loadLibrary(String)},
     * but this API works slightly different on ART and on the desktop Java, namely
     * the desktop Java version uses a different entry point method name
     * {@code JNI_OnLoad_libname()} (note the included "libname")
     * while ART always seems to use {@code JNI_OnLoad()}.
     *
     * <p>This method provides the same behavior on both the device side and on Ravenwood --
     * it uses {@code JNI_OnLoad()} as the entry point name on both.
     */
    public static void loadJniLibrary(String libname) {
        if (RavenwoodRule.isOnRavenwood()) {
            loadLibraryOnRavenwood(libname);
        } else {
            // Just delegate to the loadLibrary().
            System.loadLibrary(libname);
        }
    }

    private static void loadLibraryOnRavenwood(String libname) {
        var path = System.getProperty("java.library.path");
        var filename = "lib" + libname + ".so";

        System.out.println("Looking for library " + libname + ".so in java.library.path:" + path);

        try {
            if (path == null) {
                throw new UnsatisfiedLinkError("Cannot load library " + libname + "."
                        + " Property java.library.path not set!");
            }
            for (var dir : path.split(":")) {
                var file = new File(dir + "/" + filename);
                if (file.exists()) {
                    System.load(file.getAbsolutePath());
                    return;
                }
            }
            throw new UnsatisfiedLinkError("Library " + libname + " not found in "
                    + "java.library.path: " + path);
        } catch (Throwable e) {
            dumpFiles(System.out);
            throw e;
        }
    }

    private static void dumpFiles(PrintStream out) {
        try {
            var path = System.getProperty("java.library.path");
            out.println("# java.library.path=" + path);

            for (var dir : path.split(":")) {
                listFiles(out, new File(dir), "");

                var gparent = new File((new File(dir)).getAbsolutePath() + "../../..")
                        .getCanonicalFile();
                if (gparent.getName().contains("testcases")) {
                    // Special case: if we found this directory, dump its contents too.
                    listFiles(out, gparent, "");
                }
            }

            var gparent = new File("../..").getCanonicalFile();
            out.println("# ../..=" + gparent);
            listFiles(out, gparent, "");
        } catch (Throwable th) {
            out.println("Error: " + th.toString());
            th.printStackTrace(out);
        }
    }

    private static void listFiles(PrintStream out, File dir, String prefix) {
        if (!dir.isDirectory()) {
            out.println(prefix + dir.getAbsolutePath() + " is not a directory!");
            return;
        }
        out.println(prefix + ":" + dir.getAbsolutePath() + "/");
        // First, list the files.
        for (var file : Arrays.stream(dir.listFiles()).sorted().toList()) {
            out.println(prefix + "  " + file.getName() + "" + (file.isDirectory() ? "/" : ""));
        }

        // Then recurse.
        if (dir.getAbsolutePath().startsWith("/usr") || dir.getAbsolutePath().startsWith("/lib")) {
            // There would be too many files, so don't recurse.
            return;
        }
        for (var file : Arrays.stream(dir.listFiles()).sorted().toList()) {
            if (file.isDirectory()) {
                listFiles(out, file, prefix + "  ");
            }
        }
    }
}
