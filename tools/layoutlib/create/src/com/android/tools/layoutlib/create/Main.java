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
import java.util.Set;



public class Main {

    public static void main(String[] args) {

        Log log = new Log();

        ArrayList<String> osJarPath = new ArrayList<String>();
        String[] osDestJar = { null };

        if (!processArgs(log, args, osJarPath, osDestJar)) {
            log.error("Usage: layoutlib_create [-v] output.jar input.jar ...");
            System.exit(1);
        }

        log.info("Output: %1$s", osDestJar[0]);
        for (String path : osJarPath) {
            log.info("Input :      %1$s", path);
        }
        
        try {
            AsmGenerator agen = new AsmGenerator(log, osDestJar[0],
                    new Class<?>[] {  // classes to inject in the final JAR
                        OverrideMethod.class,
                        MethodListener.class,
                        MethodAdapter.class
                    },
                    new String[] {  // methods to force override
                        "android.view.View#isInEditMode",
                        "android.content.res.Resources$Theme#obtainStyledAttributes",
                    },
                    new String[] {  // classes to rename (so that we can replace them in layoutlib)
                        // original-platform-class-name ======> renamed-class-name
                        "android.graphics.Matrix",              "android.graphics._Original_Matrix",
                        "android.graphics.Paint",               "android.graphics._Original_Paint",
                        "android.graphics.Typeface",            "android.graphics._Original_Typeface",
                        "android.graphics.Bitmap",              "android.graphics._Original_Bitmap",
                        "android.graphics.Path",                "android.graphics._Original_Path",
                        "android.graphics.PorterDuffXfermode",  "android.graphics._Original_PorterDuffXfermode",
                        "android.graphics.Shader",              "android.graphics._Original_Shader",
                        "android.graphics.LinearGradient",      "android.graphics._Original_LinearGradient",
                        "android.graphics.BitmapShader",        "android.graphics._Original_BitmapShader",
                        "android.graphics.ComposeShader",       "android.graphics._Original_ComposeShader",
                        "android.graphics.RadialGradient",      "android.graphics._Original_RadialGradient",
                        "android.graphics.SweepGradient",       "android.graphics._Original_SweepGradient",
                        "android.util.FloatMath",               "android.util._Original_FloatMath",
                        "android.view.SurfaceView",             "android.view._Original_SurfaceView",
                    },
                    new String[] { // methods deleted from their return type.
                        "android.graphics.Paint", // class to delete method from
                        "android.graphics.Paint$Align", // list of type identifying methods to delete
                        "android.graphics.Paint$Style",
                        "android.graphics.Paint$Join",
                        "android.graphics.Paint$Cap",
                        "android.graphics.Paint$FontMetrics",
                        "android.graphics.Paint$FontMetricsInt",
                        null }
            );

            AsmAnalyzer aa = new AsmAnalyzer(log, osJarPath, agen,
                    new String[] { "android.view.View" },   // derived from
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
                System.exit(1);
            }
            
            System.exit(0);
        } catch (IOException e) {
            log.exception(e, "Failed to load jar");
        } catch (LogAbortException e) {
            e.error(log);
        }

        System.exit(1);
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
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            if (s.equals("-v")) {
                log.setVerbose(true);
            } else if (!s.startsWith("-")) {
                if (osDestJar[0] == null) {
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
        if (osDestJar[0] == null) {
            log.error("Missing parameter: path to output jar");
            return false;
        }

        return true;
    }

}
