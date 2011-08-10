/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.os;

import android.os.Process;
import android.util.Slog;

import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;

import dalvik.system.Zygote;

/**
 * Startup class for the wrapper process.
 * @hide
 */
public class WrapperInit {
    private final static String TAG = "AndroidRuntime";

    /**
     * Class not instantiable.
     */
    private WrapperInit() {
    }

    /**
     * The main function called when starting a runtime application through a
     * wrapper process instead of by forking Zygote.
     *
     * The first argument specifies the file descriptor for a pipe that should receive
     * the pid of this process, or 0 if none.  The remaining arguments are passed to
     * the runtime.
     *
     * @param args The command-line arguments.
     */
    public static void main(String[] args) {
        try {
            // Tell the Zygote what our actual PID is (since it only knows about the
            // wrapper that it directly forked).
            int fdNum = Integer.parseInt(args[0], 10);
            if (fdNum != 0) {
                try {
                    FileDescriptor fd = ZygoteInit.createFileDescriptor(fdNum);
                    DataOutputStream os = new DataOutputStream(new FileOutputStream(fd));
                    os.writeInt(Process.myPid());
                    os.close();
                    ZygoteInit.closeDescriptorQuietly(fd);
                } catch (IOException ex) {
                    Slog.d(TAG, "Could not write pid of wrapped process to Zygote pipe.", ex);
                }
            }

            // Mimic Zygote preloading.
            ZygoteInit.preload();

            // Launch the application.
            String[] runtimeArgs = new String[args.length - 1];
            System.arraycopy(args, 1, runtimeArgs, 0, runtimeArgs.length);
            RuntimeInit.wrapperInit(runtimeArgs);
        } catch (ZygoteInit.MethodAndArgsCaller caller) {
            caller.run();
        }
    }

    /**
     * Executes a runtime application with a wrapper command.
     * This method never returns.
     *
     * @param invokeWith The wrapper command.
     * @param niceName The nice name for the application, or null if none.
     * @param pipeFd The pipe to which the application's pid should be written, or null if none.
     * @param args Arguments for {@link RuntimeInit.main}.
     */
    public static void execApplication(String invokeWith, String niceName,
            FileDescriptor pipeFd, String[] args) {
        StringBuilder command = new StringBuilder(invokeWith);
        command.append(" /system/bin/app_process /system/bin --application");
        if (niceName != null) {
            command.append(" '--nice-name=").append(niceName).append("'");
        }
        command.append(" com.android.internal.os.WrapperInit ");
        command.append(pipeFd != null ? ZygoteInit.getFDFromFileDescriptor(pipeFd) : 0);
        Zygote.appendQuotedShellArgs(command, args);
        Zygote.execShell(command.toString());
    }

    /**
     * Executes a standalone application with a wrapper command.
     * This method never returns.
     *
     * @param invokeWith The wrapper command.
     * @param classPath The class path.
     * @param className The class name to invoke.
     * @param args Arguments for the main() method of the specified class.
     */
    public static void execStandalone(String invokeWith, String classPath, String className,
            String[] args) {
        StringBuilder command = new StringBuilder(invokeWith);
        command.append(" /system/bin/dalvikvm -classpath '").append(classPath);
        command.append("' ").append(className);
        Zygote.appendQuotedShellArgs(command, args);
        Zygote.execShell(command.toString());
    }
}
