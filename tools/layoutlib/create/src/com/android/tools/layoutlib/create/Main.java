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

package com.android.tools.layoutlib.create;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Entry point for the layoutlib_create tool.
 * <p/>
 * The tool does not currently rely on any external configuration file.
 * Instead the configuration is mostly done via the {@link CreateInfo} class.
 * <p/>
 * For a complete description of the tool and its implementation, please refer to
 * the "README.txt" file at the root of this project.
 * <p/>
 * For a quick test, invoke this as follows:
 * <pre>
 * $ make layoutlib
 * </pre>
 * which does:
 * <pre>
 * $ make layoutlib_create &lt;bunch of framework jars&gt;
 * $ java -jar out/host/linux-x86/framework/layoutlib_create.jar \
 *        out/host/common/obj/JAVA_LIBRARIES/temp_layoutlib_intermediates/javalib.jar \
 *        out/target/common/obj/JAVA_LIBRARIES/core_intermediates/classes.jar \
 *        out/target/common/obj/JAVA_LIBRARIES/framework_intermediates/classes.jar
 * </pre>
 */
public class Main {

    public static class Options {
        public boolean generatePublicAccess = true;
        public boolean listAllDeps = false;
        public boolean listOnlyMissingDeps = false;
    }

    public static final Options sOptions = new Options();

    public static void main(String[] args) {

        Log log = new Log();

        ArrayList<String> osJarPath = new ArrayList<String>();
        String[] osDestJar = { null };

        if (!processArgs(log, args, osJarPath, osDestJar)) {
            log.error("Usage: layoutlib_create [-v] [-p] output.jar input.jar ...");
            log.error("Usage: layoutlib_create [-v] [--list-deps|--missing-deps] input.jar ...");
            System.exit(1);
        }

        if (sOptions.listAllDeps || sOptions.listOnlyMissingDeps) {
            System.exit(listDeps(osJarPath, log));

        } else {
            System.exit(createLayoutLib(osDestJar[0], osJarPath, log));
        }


        System.exit(1);
    }

    private static int createLayoutLib(String osDestJar, ArrayList<String> osJarPath, Log log) {
        log.info("Output: %1$s", osDestJar);
        for (String path : osJarPath) {
            log.info("Input :      %1$s", path);
        }

        try {
            AsmGenerator agen = new AsmGenerator(log, osDestJar, new CreateInfo());

            AsmAnalyzer aa = new AsmAnalyzer(log, osJarPath, agen,
                    new String[] {                          // derived from
                        "android.view.View",
                        "android.app.Fragment"
                    },
                    new String[] {                          // include classes
                        "android.*", // for android.R
                        "android.util.*",
                        "com.android.internal.util.*",
                        "android.view.*",
                        "android.widget.*",
                        "com.android.internal.widget.*",
                        "android.text.**",
                        "android.graphics.*",
                        "android.graphics.drawable.*",
                        "android.content.*",
                        "android.content.res.*",
                        "org.apache.harmony.xml.*",
                        "com.android.internal.R**",
                        "android.pim.*", // for datepicker
                        "android.os.*",  // for android.os.Handler
                        "android.database.ContentObserver", // for Digital clock
                        });
            aa.analyze();
            agen.generate();

            // Throw an error if any class failed to get renamed by the generator
            //
            // IMPORTANT: if you're building the platform and you get this error message,
            // it means the renameClasses[] array in AsmGenerator needs to be updated: some
            // class should have been renamed but it was not found in the input JAR files.
            Set<String> notRenamed = agen.getClassesNotRenamed();
            if (notRenamed.size() > 0) {
                // (80-column guide below for error formatting)
                // 01234567890123456789012345678901234567890123456789012345678901234567890123456789
                log.error(
                  "ERROR when running layoutlib_create: the following classes are referenced\n" +
                  "by tools/layoutlib/create but were not actually found in the input JAR files.\n" +
                  "This may be due to some platform classes having been renamed.");
                for (String fqcn : notRenamed) {
                    log.error("- Class not found: %s", fqcn.replace('/', '.'));
                }
                for (String path : osJarPath) {
                    log.info("- Input JAR : %1$s", path);
                }
                return 1;
            }

            return 0;
        } catch (IOException e) {
            log.exception(e, "Failed to load jar");
        } catch (LogAbortException e) {
            e.error(log);
        }

        return 1;
    }

    private static int listDeps(ArrayList<String> osJarPath, Log log) {
        DependencyFinder df = new DependencyFinder(log);
        try {
            List<Map<String, Set<String>>> result = df.findDeps(osJarPath);
            if (sOptions.listAllDeps) {
                df.printAllDeps(result);
            } else if (sOptions.listOnlyMissingDeps) {
                df.printMissingDeps(result);
            }
        } catch (IOException e) {
            log.exception(e, "Failed to load jar");
        }

        return 0;
    }

    /**
     * Returns true if args where properly parsed.
     * Returns false if program should exit with command-line usage.
     * <p/>
     * Note: the String[0] is an output parameter wrapped in an array, since there is no
     * "out" parameter support.
     */
    private static boolean processArgs(Log log, String[] args,
            ArrayList<String> osJarPath, String[] osDestJar) {
        boolean needs_dest = true;
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            if (s.equals("-v")) {
                log.setVerbose(true);
            } else if (s.equals("-p")) {
                sOptions.generatePublicAccess = false;
            } else if (s.equals("--list-deps")) {
                sOptions.listAllDeps = true;
                needs_dest = false;
            } else if (s.equals("--missing-deps")) {
                sOptions.listOnlyMissingDeps = true;
                needs_dest = false;
            } else if (!s.startsWith("-")) {
                if (needs_dest && osDestJar[0] == null) {
                    osDestJar[0] = s;
                } else {
                    osJarPath.add(s);
                }
            } else {
                log.error("Unknow argument: %s", s);
                return false;
            }
        }

        if (osJarPath.isEmpty()) {
            log.error("Missing parameter: path to input jar");
            return false;
        }
        if (needs_dest && osDestJar[0] == null) {
            log.error("Missing parameter: path to output jar");
            return false;
        }

        sOptions.generatePublicAccess = false;

        return true;
    }
}
