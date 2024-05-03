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

package com.android.internal.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityThread;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodManagerGlobal;

import java.io.PrintWriter;

/**
 *
 * An abstract class that declares the methods for ime trace related operations - enable trace,
 * schedule trace and add new trace to buffer. Both the client and server side classes can use
 * it by getting an implementation through {@link ImeTracing#getInstance()}.
 */
public abstract class ImeTracing {

    static final String TAG = "imeTracing";
    public static final String PROTO_ARG = "--proto-com-android-imetracing";

    /* Constants describing the component type that triggered a dump. */
    public static final int IME_TRACING_FROM_CLIENT = 0;
    public static final int IME_TRACING_FROM_IMS = 1;
    public static final int IME_TRACING_FROM_IMMS = 2;

    private static ImeTracing sInstance;
    static boolean sEnabled = false;

    private final boolean mIsAvailable = InputMethodManagerGlobal.isImeTraceAvailable();

    protected boolean mDumpInProgress;
    protected final Object mDumpInProgressLock = new Object();

    /**
     * Returns an instance of {@link ImeTracingServerImpl} when called from a server side class
     * and an instance of {@link ImeTracingClientImpl} when called from a client side class.
     * Useful to schedule a dump for next frame or save a dump when certain methods are called.
     *
     * @return Instance of one of the children classes of {@link ImeTracing}
     */
    public static ImeTracing getInstance() {
        if (sInstance == null) {
            if (isSystemProcess()) {
                sInstance = new ImeTracingServerImpl();
            } else {
                sInstance = new ImeTracingClientImpl();
            }
        }
        return sInstance;
    }

    /**
     * Transmits the information from client or InputMethodService side to the server, in order to
     * be stored persistently to the current IME tracing dump.
     *
     * @param protoDump client or service side information to be stored by the server
     * @param source where the information is coming from, refer to {@see #IME_TRACING_FROM_CLIENT}
     * and {@see #IME_TRACING_FROM_IMS}
     * @param where
     */
    public void sendToService(byte[] protoDump, int source, String where) {
        InputMethodManagerGlobal.startProtoDump(protoDump, source, where,
                e -> Log.e(TAG, "Exception while sending ime-related dump to server", e));
    }

    /**
     * Start IME trace.
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_UI_TRACING)
    public final void startImeTrace() {
        InputMethodManagerGlobal.startImeTrace(e -> Log.e(TAG, "Could not start ime trace.", e));
    }

    /**
     * Stop IME trace.
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_UI_TRACING)
    public final void stopImeTrace() {
        InputMethodManagerGlobal.stopImeTrace(e -> Log.e(TAG, "Could not stop ime trace.", e));
    }

    /**
     * @param proto dump to be added to the buffer
     */
    public abstract void addToBuffer(ProtoOutputStream proto, int source);

    /**
     * Starts a proto dump of the client side information.
     *
     * @param where Place where the trace was triggered.
     * @param immInstance The {@link InputMethodManager} instance to dump.
     * @param icProto {@link android.view.inputmethod.InputConnection} call data in proto format.
     */
    public abstract void triggerClientDump(String where, InputMethodManager immInstance,
            @Nullable byte[] icProto);

    /**
     * A delegate for {@link #triggerServiceDump(String, ServiceDumper, byte[])}.
     */
    @FunctionalInterface
    public interface ServiceDumper {
        /**
         * Dumps internal data into {@link ProtoOutputStream}.
         *
         * @param proto {@link ProtoOutputStream} to be dumped into.
         * @param icProto {@link android.view.inputmethod.InputConnection} call data in proto
         *                format.
         */
        void dumpToProto(ProtoOutputStream proto, @Nullable byte[] icProto);
    }

    /**
     * Starts a proto dump of the currently connected InputMethodService information.
     *
     * @param where Place where the trace was triggered.
     * @param dumper {@link ServiceDumper} to be used to dump
     *               {@link android.inputmethodservice.InputMethodService}.
     * @param icProto {@link android.view.inputmethod.InputConnection} call data in proto format.
     */
    public abstract void triggerServiceDump(String where, ServiceDumper dumper,
            @Nullable byte[] icProto);

    /**
     * Starts a proto dump of the InputMethodManagerService information.
     *
     * @param where Place where the trace was triggered.
     */
    public abstract void triggerManagerServiceDump(String where, @NonNull ServiceDumper dumper);

    /**
     * Being called while taking a bugreport so that tracing files can be included in the bugreport
     * when the IME tracing is running.  Does nothing otherwise.
     *
     * @param pw Print writer
     */
    public void saveForBugreport(@Nullable PrintWriter pw) {
        // does nothing by default.
    }

    /**
     * Sets whether ime tracing is enabled.
     *
     * @param enabled Tells whether ime tracing should be enabled or disabled.
     */
    public void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * @return {@code true} if dumping is enabled, {@code false} otherwise.
     */
    public boolean isEnabled() {
        return sEnabled;
    }

    /**
     * @return {@code true} if tracing is available, {@code false} otherwise.
     */
    public boolean isAvailable() {
        return mIsAvailable;
    }

    /**
     * Starts a new IME trace if one is not already started.
     *
     * @param pw Print writer
     */
    public abstract void startTrace(@Nullable PrintWriter pw);

    /**
     * Stops the IME trace if one was previously started and writes the current buffers to disk.
     *
     * @param pw Print writer
     */
    public abstract void stopTrace(@Nullable PrintWriter pw);

    private static boolean isSystemProcess() {
        return ActivityThread.isSystem();
    }

    protected void logAndPrintln(@Nullable PrintWriter pw, String msg) {
        Log.i(TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }

}
