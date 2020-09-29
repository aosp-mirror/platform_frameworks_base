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

package android.util.imetracing;

import static android.os.Build.IS_USER;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodEditorTraceFileProto.MAGIC_NUMBER;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodEditorTraceFileProto.MAGIC_NUMBER_H;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodEditorTraceFileProto.MAGIC_NUMBER_L;

import android.os.RemoteException;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.ShellCommand;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.TraceBuffer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * @hide
 */
class ImeTracingServerImpl extends ImeTracing {
    private static final String TRACE_FILENAME = "/data/misc/wmtrace/ime_trace.pb";
    private static final int BUFFER_CAPACITY = 4096 * 1024;

    // Needed for winscope to auto-detect the dump type. Explained further in
    // core.proto.android.view.inputmethod.inputmethodeditortrace.proto
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private final TraceBuffer mBuffer;
    private final File mTraceFile;
    private final Object mEnabledLock = new Object();

    ImeTracingServerImpl() throws ServiceNotFoundException {
        mBuffer = new TraceBuffer<>(BUFFER_CAPACITY);
        mTraceFile = new File(TRACE_FILENAME);
    }

    /**
     * The provided dump is added to the current dump buffer {@link ImeTracingServerImpl#mBuffer}.
     *
     * @param proto dump to be added to the buffer
     */
    @Override
    public void addToBuffer(ProtoOutputStream proto) {
        if (isAvailable() && isEnabled()) {
            mBuffer.add(proto);
        }
    }

    /**
     * Responds to a shell command of the format "adb shell cmd input_method ime tracing <command>"
     *
     * @param shell The shell command to process
     * @return {@code 0} if the command was valid and successfully processed, {@code -1} otherwise
     */
    @Override
    public int onShellCommand(ShellCommand shell) {
        PrintWriter pw = shell.getOutPrintWriter();
        String cmd = shell.getNextArgRequired();
        switch (cmd) {
            case "start":
                startTrace(pw);
                return 0;
            case "stop":
                stopTrace(pw);
                return 0;
            default:
                pw.println("Unknown command: " + cmd);
                pw.println("Input method trace options:");
                pw.println("  start: Start tracing");
                pw.println("  stop: Stop tracing");
                return -1;
        }
    }

    @Override
    public void triggerDump() {
        if (isAvailable() && isEnabled()) {
            try {
                mService.startProtoDump(null);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while triggering proto dump", e);
            }
        }
    }

    private void writeTraceToFileLocked() {
        try {
            ProtoOutputStream proto = new ProtoOutputStream();
            proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
            mBuffer.writeTraceToFile(mTraceFile, proto);
        } catch (IOException e) {
            Log.e(TAG, "Unable to write buffer to file", e);
        }
    }

    @GuardedBy("mEnabledLock")
    private void startTrace(PrintWriter pw) {
        if (IS_USER) {
            Log.w(TAG, "Warn: Tracing is not supported on user builds.");
            return;
        }

        synchronized (mEnabledLock) {
            if (isAvailable() && isEnabled()) {
                Log.w(TAG, "Warn: Tracing is already started.");
                return;
            }

            pw.println("Starting tracing to " + mTraceFile + ".");
            sEnabled = true;
            mBuffer.resetBuffer();
        }
    }

    @GuardedBy("mEnabledLock")
    private void stopTrace(PrintWriter pw) {
        if (IS_USER) {
            Log.w(TAG, "Warn: Tracing is not supported on user builds.");
            return;
        }

        synchronized (mEnabledLock) {
            if (!isAvailable() || !isEnabled()) {
                Log.w(TAG, "Warn: Tracing is not available or not started.");
                return;
            }

            pw.println("Stopping tracing and writing traces to " + mTraceFile + ".");
            sEnabled = false;
            writeTraceToFileLocked();
            mBuffer.resetBuffer();
        }
    }
}
