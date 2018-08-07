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

package com.android.server.coverage;

import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.ResultReceiver;

import org.jacoco.agent.rt.RT;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * A service that responds to `cmd coverage ...` and provides a mechanism for dumping code coverage
 * information from the system server process.
 * @hide
 */
public class CoverageService extends Binder {

    public static final String COVERAGE_SERVICE = "coverage";
    public static final boolean ENABLED;

    static {
        // This service should only be enabled if org.jacoco.agent.rt.RT was added to the build
        boolean shouldEnable = true;
        try {
            Class.forName("org.jacoco.agent.rt.RT");
        } catch (ClassNotFoundException e) {
            shouldEnable = false;
        }
        ENABLED = shouldEnable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new CoverageCommand().exec(this, in, out, err, args, callback, resultReceiver);
    }

    /**
     * A {@link ShellCommand} implementation for performing coverage shell commands.
     */
    private static class CoverageCommand extends ShellCommand {

        /**
         * {@inheritDoc}
         */
        @Override
        public int onCommand(String cmd) {
            if ("dump".equals(cmd)) {
                return onDump();
            } else if ("reset".equals(cmd)) {
                return onReset();
            } else {
                return handleDefaultCommands(cmd);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Coverage commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  dump [FILE]");
            pw.println("    Dump code coverage to FILE.");
            pw.println("  reset");
            pw.println("    Reset coverage information.");
        }

        /**
         * Perform the "dump" command to write the collected execution data to a file.
         *
         * @return The command result.
         */
        private int onDump() {
            // Figure out where to dump the coverage data
            String dest = getNextArg();
            if (dest == null) {
                dest = "/data/local/tmp/coverage.ec";
            } else {
                File f = new File(dest);
                if (f.isDirectory()) {
                    dest = new File(f, "coverage.ec").getAbsolutePath();
                }
            }

            // Try to open the destination file
            ParcelFileDescriptor fd = openFileForSystem(dest, "w");
            if (fd == null) {
                return -1;
            }

            // Write the execution data to the file
            try (BufferedOutputStream output = new BufferedOutputStream(
                    new ParcelFileDescriptor.AutoCloseOutputStream(fd))) {
                output.write(RT.getAgent().getExecutionData(false));
                output.flush();
                getOutPrintWriter().println(String.format("Dumped coverage data to %s", dest));
            } catch (IOException e) {
                getErrPrintWriter().println("Failed to dump coverage data: " + e.getMessage());
                return -1;
            }

            return 0;
        }

        /**
         * Perform the "reset" command to clear the collected execution data.
         *
         * @return The command result.
         */
        private int onReset() {
            RT.getAgent().reset();
            getOutPrintWriter().println("Reset coverage data");
            return 0;
        }
    }
}
