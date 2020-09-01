/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.commands.incident;

import android.util.Log;

import com.android.commands.incident.sections.PersistLogSection;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

/**
 * Helper command runner for incidentd to run customized command to gather data for a non-standard
 * section.
 */
public class IncidentHelper {
    private static final String TAG = "IncidentHelper";
    private static boolean sLog = false;
    private final List<String> mArgs;
    private ListIterator<String> mArgsIterator;

    private IncidentHelper(String[] args) {
        mArgs = Collections.unmodifiableList(Arrays.asList(args));
        mArgsIterator = mArgs.listIterator();
    }

    private static void showUsage(PrintStream out) {
        out.println("This command is not designed to be run manually.");
        out.println("Usage:");
        out.println("  run [sectionName]");
    }

    private void run(String[] args) throws ExecutionException {
        Section section = null;
        List<String> sectionArgs = new ArrayList<>();
        while (mArgsIterator.hasNext()) {
            String arg = mArgsIterator.next();
            if ("-l".equals(arg)) {
                sLog = true;
                Log.i(TAG, "Args: [" + String.join(",", args) + "]");
            } else if ("run".equals(arg)) {
                section = getSection(nextArgRequired());
                mArgsIterator.forEachRemaining(sectionArgs::add);
                break;
            } else {
                log(Log.WARN, TAG, "Error: Unknown argument: " + arg);
                return;
            }
        }
        section.run(System.in, System.out, sectionArgs);
    }

    private static Section getSection(String name) throws IllegalArgumentException {
        if ("persisted_logs".equals(name)) {
            return new PersistLogSection();
        }
        throw new IllegalArgumentException("Section not found: " + name);
    }

    private String nextArgRequired() {
        if (!mArgsIterator.hasNext()) {
            throw new IllegalArgumentException(
                    "Arg required after \"" + mArgs.get(mArgsIterator.previousIndex()) + "\"");
        }
        return mArgsIterator.next();
    }

    /**
     * Print the given message to stderr, also log it if asked to (set by -l cmd arg).
     */
    public static void log(int priority, String tag, String msg) {
        System.err.println(tag + ": " + msg);
        if (sLog) {
            Log.println(priority, tag, msg);
        }
    }

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            showUsage(System.err);
            System.exit(0);
        }
        IncidentHelper incidentHelper = new IncidentHelper(args);
        try {
            incidentHelper.run(args);
        } catch (IllegalArgumentException e) {
            showUsage(System.err);
            System.err.println();
            e.printStackTrace(System.err);
            if (sLog) {
                Log.e(TAG, "Error: ", e);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            if (sLog) {
                Log.e(TAG, "Error: ", e);
            }
            System.exit(1);
        }
    }
}
