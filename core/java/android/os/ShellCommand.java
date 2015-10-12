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

import android.util.Slog;
import com.android.internal.util.FastPrintWriter;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;

/**
 * @hide
 */
public abstract class ShellCommand {
    static final String TAG = "ShellCommand";
    static final boolean DEBUG = false;

    private Binder mTarget;
    private FileDescriptor mIn;
    private FileDescriptor mOut;
    private FileDescriptor mErr;
    private String[] mArgs;
    private ResultReceiver mResultReceiver;

    private String mCmd;
    private int mArgPos;
    private String mCurArgData;

    private FastPrintWriter mOutPrintWriter;
    private FastPrintWriter mErrPrintWriter;

    public void exec(Binder target, FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) {
        mTarget = target;
        mIn = in;
        mOut = out;
        mErr = err;
        mArgs = args;
        mResultReceiver = resultReceiver;
        mCmd = args != null && args.length > 0 ? args[0] : null;
        mArgPos = 1;
        mCurArgData = null;
        mOutPrintWriter = null;
        mErrPrintWriter = null;

        if (DEBUG) Slog.d(TAG, "Starting command " + mCmd + " on " + mTarget);
        int res = -1;
        try {
            res = onCommand(mCmd);
            if (DEBUG) Slog.d(TAG, "Executed command " + mCmd + " on " + mTarget);
        } catch (SecurityException e) {
            PrintWriter eout = getErrPrintWriter();
            eout.println("Security exception: " + e.getMessage());
            eout.println();
            e.printStackTrace(eout);
        } catch (Throwable e) {
            // Unlike usual calls, in this case if an exception gets thrown
            // back to us we want to print it back in to the dump data, since
            // that is where the caller expects all interesting information to
            // go.
            PrintWriter eout = getErrPrintWriter();
            eout.println();
            eout.println("Exception occurred while dumping:");
            e.printStackTrace(eout);
        } finally {
            if (DEBUG) Slog.d(TAG, "Flushing output streams on " + mTarget);
            if (mOutPrintWriter != null) {
                mOutPrintWriter.flush();
            }
            if (mErrPrintWriter != null) {
                mErrPrintWriter.flush();
            }
            if (DEBUG) Slog.d(TAG, "Sending command result on " + mTarget);
            mResultReceiver.send(res, null);
        }
        if (DEBUG) Slog.d(TAG, "Finished command " + mCmd + " on " + mTarget);
    }

    public PrintWriter getOutPrintWriter() {
        if (mOutPrintWriter == null) {
            FileOutputStream fout = new FileOutputStream(mOut);
            mOutPrintWriter = new FastPrintWriter(fout);
        }
        return mOutPrintWriter;
    }

    public PrintWriter getErrPrintWriter() {
        if (mErr == null) {
            return getOutPrintWriter();
        }
        if (mErrPrintWriter == null) {
            FileOutputStream fout = new FileOutputStream(mErr);
            mErrPrintWriter = new FastPrintWriter(fout);
        }
        return mErrPrintWriter;
    }

    /**
     * Return the next option on the command line -- that is an argument that
     * starts with '-'.  If the next argument is not an option, null is returned.
     */
    public String getNextOption() {
        if (mCurArgData != null) {
            String prev = mArgs[mArgPos - 1];
            throw new IllegalArgumentException("No argument expected after \"" + prev + "\"");
        }
        if (mArgPos >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mArgPos];
        if (!arg.startsWith("-")) {
            return null;
        }
        mArgPos++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    /**
     * Return the next argument on the command line, whatever it is; if there are
     * no arguments left, return null.
     */
    public String getNextArg() {
        if (mCurArgData != null) {
            String arg = mCurArgData;
            mCurArgData = null;
            return arg;
        } else if (mArgPos < mArgs.length) {
            return mArgs[mArgPos++];
        } else {
            return null;
        }
    }

    /**
     * Return the next argument on the command line, whatever it is; if there are
     * no arguments left, throws an IllegalArgumentException to report this to the user.
     */
    public String getNextArgRequired() {
        String arg = getNextArg();
        if (arg == null) {
            String prev = mArgs[mArgPos - 1];
            throw new IllegalArgumentException("Argument expected after \"" + prev + "\"");
        }
        return arg;
    }

    public int handleDefaultCommands(String cmd) {
        if ("dump".equals(cmd)) {
            String[] newArgs = new String[mArgs.length-1];
            System.arraycopy(mArgs, 1, newArgs, 0, mArgs.length-1);
            mTarget.doDump(mOut, getOutPrintWriter(), newArgs);
            return 0;
        } else if (cmd == null || "help".equals(cmd) || "-h".equals(cmd)) {
            onHelp();
        } else {
            getOutPrintWriter().println("Unknown command: " + cmd);
        }
        return -1;
    }

    public abstract int onCommand(String cmd);

    public abstract void onHelp();
}
