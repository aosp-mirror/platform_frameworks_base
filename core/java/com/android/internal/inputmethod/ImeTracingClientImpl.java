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
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodManagerGlobal;

import java.io.PrintWriter;

/**
 * An implementation of {@link ImeTracing} for non system_server processes.
 */
class ImeTracingClientImpl extends ImeTracing {
    ImeTracingClientImpl() {
        sEnabled = InputMethodManagerGlobal.isImeTraceEnabled();
    }

    @Override
    public void addToBuffer(ProtoOutputStream proto, int source) {
    }

    @Override
    public void triggerClientDump(String where, @NonNull InputMethodManager immInstance,
            @Nullable byte[] icProto) {
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
            ProtoOutputStream proto = new ProtoOutputStream();
            immInstance.dumpDebug(proto, icProto);
            sendToService(proto.getBytes(), IME_TRACING_FROM_CLIENT, where);
        } finally {
            mDumpInProgress = false;
        }
    }

    @Override
    public void triggerServiceDump(String where, @NonNull ServiceDumper dumper,
            @Nullable byte[] icProto) {
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
            ProtoOutputStream proto = new ProtoOutputStream();
            dumper.dumpToProto(proto, icProto);
            sendToService(proto.getBytes(), IME_TRACING_FROM_IMS, where);
        } finally {
            mDumpInProgress = false;
        }
    }

    @Override
    public void triggerManagerServiceDump(String where, @NonNull ServiceDumper dumper) {
        // Intentionally left empty, this is implemented in ImeTracingServerImpl
    }

    @Override
    public void startTrace(PrintWriter pw) {
    }

    @Override
    public void stopTrace(PrintWriter pw) {
    }
}
