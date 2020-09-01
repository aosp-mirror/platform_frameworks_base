/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.os;

import android.compat.annotation.UnsupportedAppUsage;
import android.util.Slog;

import com.android.internal.util.FastPrintWriter;

import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;

/**
 * Helper for implementing {@link Binder#onShellCommand Binder.onShellCommand}.
 * @hide
 */
public abstract class ShellCommand extends BasicShellCommandHandler {
    private ShellCallback mShellCallback;
    private ResultReceiver mResultReceiver;

    public int exec(Binder target, FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        mShellCallback = callback;
        mResultReceiver = resultReceiver;
        final int result = super.exec(target, in, out, err, args);

        if (mResultReceiver != null) {
            mResultReceiver.send(result, null);
        }

        return result;
    }

    /**
     * Adopt the ResultReceiver that was given to this shell command from it, taking
     * it over.  Primarily used to dispatch to another shell command.  Once called,
     * this shell command will no longer return its own result when done.
     */
    public ResultReceiver adoptResultReceiver() {
        ResultReceiver rr = mResultReceiver;
        mResultReceiver = null;
        return rr;
    }

    /**
     * Helper for just system services to ask the shell to open an output file.
     * @hide
     */
    public ParcelFileDescriptor openFileForSystem(String path, String mode) {
        if (DEBUG) Slog.d(TAG, "openFileForSystem: " + path + " mode=" + mode);
        try {
            ParcelFileDescriptor pfd = getShellCallback().openFile(path,
                    "u:r:system_server:s0", mode);
            if (pfd != null) {
                if (DEBUG) Slog.d(TAG, "Got file: " + pfd);
                return pfd;
            }
        } catch (RuntimeException e) {
            if (DEBUG) Slog.d(TAG, "Failure opening file: " + e.getMessage());
            getErrPrintWriter().println("Failure opening file: " + e.getMessage());
        }
        if (DEBUG) Slog.d(TAG, "Error: Unable to open file: " + path);
        getErrPrintWriter().println("Error: Unable to open file: " + path);

        String suggestedPath = "/data/local/tmp/";
        if (path == null || !path.startsWith(suggestedPath)) {
            getErrPrintWriter().println("Consider using a file under " + suggestedPath);
        }
        return null;
    }

    public int handleDefaultCommands(String cmd) {
        if ("dump".equals(cmd)) {
            String[] newArgs = new String[getAllArgs().length-1];
            System.arraycopy(getAllArgs(), 1, newArgs, 0, getAllArgs().length-1);
            getTarget().doDump(getOutFileDescriptor(), getOutPrintWriter(), newArgs);
            return 0;
        }
        return super.handleDefaultCommands(cmd);
    }

    @UnsupportedAppUsage
    public String peekNextArg() {
        return super.peekNextArg();
    }

    /**
     * Return the {@link ShellCallback} for communicating back with the calling shell.
     */
    public ShellCallback getShellCallback() {
        return mShellCallback;
    }
}
