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

import android.annotation.Nullable;
import android.inputmethodservice.AbstractInputMethodService;
import android.os.RemoteException;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceFileProto;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodManagerServiceTraceFileProto;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodServiceTraceFileProto;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.TraceBuffer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * @hide
 */
class ImeTracingServerImpl extends ImeTracing {
    private static final String TRACE_DIRNAME = "/data/misc/wmtrace/";
    private static final String TRACE_FILENAME_CLIENTS = "ime_trace_clients.pb";
    private static final String TRACE_FILENAME_IMS = "ime_trace_service.pb";
    private static final String TRACE_FILENAME_IMMS = "ime_trace_managerservice.pb";
    private static final int BUFFER_CAPACITY = 4096 * 1024;

    // Needed for winscope to auto-detect the dump type. Explained further in
    // core.proto.android.view.inputmethod.inputmethodeditortrace.proto.
    // This magic number corresponds to InputMethodClientsTraceFileProto.
    private static final long MAGIC_NUMBER_CLIENTS_VALUE =
            ((long) InputMethodClientsTraceFileProto.MAGIC_NUMBER_H << 32)
                | InputMethodClientsTraceFileProto.MAGIC_NUMBER_L;
    // This magic number corresponds to InputMethodServiceTraceFileProto.
    private static final long MAGIC_NUMBER_IMS_VALUE =
            ((long) InputMethodServiceTraceFileProto.MAGIC_NUMBER_H << 32)
                | InputMethodServiceTraceFileProto.MAGIC_NUMBER_L;
    // This magic number corresponds to InputMethodManagerServiceTraceFileProto.
    private static final long MAGIC_NUMBER_IMMS_VALUE =
            ((long) InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER_H << 32)
                | InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER_L;

    private final TraceBuffer mBufferClients;
    private final File mTraceFileClients;
    private final TraceBuffer mBufferIms;
    private final File mTraceFileIms;
    private final TraceBuffer mBufferImms;
    private final File mTraceFileImms;

    private final Object mEnabledLock = new Object();

    ImeTracingServerImpl() throws ServiceNotFoundException {
        mBufferClients = new TraceBuffer<>(BUFFER_CAPACITY);
        mTraceFileClients = new File(TRACE_DIRNAME + TRACE_FILENAME_CLIENTS);
        mBufferIms = new TraceBuffer<>(BUFFER_CAPACITY);
        mTraceFileIms = new File(TRACE_DIRNAME + TRACE_FILENAME_IMS);
        mBufferImms = new TraceBuffer<>(BUFFER_CAPACITY);
        mTraceFileImms = new File(TRACE_DIRNAME + TRACE_FILENAME_IMMS);
    }

    /**
     * The provided dump is added to the corresponding dump buffer:
     * {@link ImeTracingServerImpl#mBufferClients} or {@link ImeTracingServerImpl#mBufferIms}.
     *
     * @param proto dump to be added to the buffer
     */
    @Override
    public void addToBuffer(ProtoOutputStream proto, int source) {
        if (isAvailable() && isEnabled()) {
            switch (source) {
                case IME_TRACING_FROM_CLIENT:
                    mBufferClients.add(proto);
                    return;
                case IME_TRACING_FROM_IMS:
                    mBufferIms.add(proto);
                    return;
                case IME_TRACING_FROM_IMMS:
                    mBufferImms.add(proto);
                    return;
                default:
                    // Source not recognised.
                    Log.w(TAG, "Request to add to buffer, but source not recognised.");
            }
        }
    }

    @Override
    public void triggerClientDump(String where, InputMethodManager immInstance,
            ProtoOutputStream icProto) {
        // Intentionally left empty, this is implemented in ImeTracingClientImpl
    }

    @Override
    public void triggerServiceDump(String where, AbstractInputMethodService service,
            ProtoOutputStream icProto) {
        // Intentionally left empty, this is implemented in ImeTracingClientImpl
    }

    @Override
    public void triggerManagerServiceDump(String where) {
        if (!isEnabled() || !isAvailable()) {
            return;
        }

        synchronized (mDumpInProgressLock) {
            if (mDumpInProgress) {
                return;
            }
            mDumpInProgress = true;
        }

        try {
            sendToService(null, IME_TRACING_FROM_IMMS, where);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while sending ime-related manager service dump to server", e);
        } finally {
            mDumpInProgress = false;
        }
    }

    private void writeTracesToFilesLocked() {
        try {
            ProtoOutputStream clientsProto = new ProtoOutputStream();
            clientsProto.write(InputMethodClientsTraceFileProto.MAGIC_NUMBER,
                    MAGIC_NUMBER_CLIENTS_VALUE);
            mBufferClients.writeTraceToFile(mTraceFileClients, clientsProto);

            ProtoOutputStream imsProto = new ProtoOutputStream();
            imsProto.write(InputMethodServiceTraceFileProto.MAGIC_NUMBER, MAGIC_NUMBER_IMS_VALUE);
            mBufferIms.writeTraceToFile(mTraceFileIms, imsProto);

            ProtoOutputStream immsProto = new ProtoOutputStream();
            immsProto.write(InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER,
                    MAGIC_NUMBER_IMMS_VALUE);
            mBufferImms.writeTraceToFile(mTraceFileImms, immsProto);

            resetBuffers();
        } catch (IOException e) {
            Log.e(TAG, "Unable to write buffer to file", e);
        }
    }

    @GuardedBy("mEnabledLock")
    @Override
    public void startTrace(@Nullable PrintWriter pw) {
        if (IS_USER) {
            Log.w(TAG, "Warn: Tracing is not supported on user builds.");
            return;
        }

        synchronized (mEnabledLock) {
            if (isAvailable() && isEnabled()) {
                Log.w(TAG, "Warn: Tracing is already started.");
                return;
            }

            logAndPrintln(pw, "Starting tracing in " + TRACE_DIRNAME + ": " + TRACE_FILENAME_CLIENTS
                    + ", " + TRACE_FILENAME_IMS + ", " + TRACE_FILENAME_IMMS);
            sEnabled = true;
            resetBuffers();
        }
    }

    @Override
    public void stopTrace(@Nullable PrintWriter pw) {
        if (IS_USER) {
            Log.w(TAG, "Warn: Tracing is not supported on user builds.");
            return;
        }

        synchronized (mEnabledLock) {
            if (!isAvailable() || !isEnabled()) {
                Log.w(TAG, "Warn: Tracing is not available or not started.");
                return;
            }

            logAndPrintln(pw, "Stopping tracing and writing traces in " + TRACE_DIRNAME + ": "
                    + TRACE_FILENAME_CLIENTS + ", " + TRACE_FILENAME_IMS + ", "
                    + TRACE_FILENAME_IMMS);
            sEnabled = false;
            writeTracesToFilesLocked();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void saveForBugreport(@Nullable PrintWriter pw) {
        if (IS_USER) {
            return;
        }
        synchronized (mEnabledLock) {
            if (!isAvailable() || !isEnabled()) {
                return;
            }
            // Temporarily stop accepting logs from trace event providers.  There is a small chance
            // that we may drop some trace events while writing the file, but we currently need to
            // live with that.  Note that addToBuffer() also has a bug that it doesn't do
            // read-acquire so flipping sEnabled here doesn't even guarantee that addToBuffer() will
            // temporarily stop accepting incoming events...
            // TODO(b/175761228): Implement atomic snapshot to avoid downtime.
            // TODO(b/175761228): Fix synchronization around sEnabled.
            sEnabled = false;
            logAndPrintln(pw, "Writing traces in " + TRACE_DIRNAME + ": "
                    + TRACE_FILENAME_CLIENTS + ", " + TRACE_FILENAME_IMS + ", "
                    + TRACE_FILENAME_IMMS);
            writeTracesToFilesLocked();
            sEnabled = true;
        }
    }

    private void resetBuffers() {
        mBufferClients.resetBuffer();
        mBufferIms.resetBuffer();
        mBufferImms.resetBuffer();
    }
}
