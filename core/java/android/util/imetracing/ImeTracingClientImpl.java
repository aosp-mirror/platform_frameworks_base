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

import android.annotation.NonNull;
import android.inputmethodservice.AbstractInputMethodService;
import android.os.RemoteException;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.InputMethodManager;

import java.io.PrintWriter;

/**
 * @hide
 */
class ImeTracingClientImpl extends ImeTracing {
    ImeTracingClientImpl() throws ServiceNotFoundException, RemoteException {
        sEnabled = mService.isImeTraceEnabled();
    }

    @Override
    public void addToBuffer(ProtoOutputStream proto, int source) {
    }

    @Override
    public void triggerClientDump(String where, @NonNull InputMethodManager immInstance,
            ProtoOutputStream icProto) {
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
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while sending ime-related client dump to server", e);
        } finally {
            mDumpInProgress = false;
        }
    }

    @Override
    public void triggerServiceDump(String where, @NonNull AbstractInputMethodService service,
            ProtoOutputStream icProto) {
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
            service.dumpProtoInternal(proto, icProto);
            sendToService(proto.getBytes(), IME_TRACING_FROM_IMS, where);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while sending ime-related service dump to server", e);
        } finally {
            mDumpInProgress = false;
        }
    }

    @Override
    public void triggerManagerServiceDump(String where) {
        // Intentionally left empty, this is implemented in ImeTracingServerImpl
    }

    @Override
    public void startTrace(PrintWriter pw) {
    }

    @Override
    public void stopTrace(PrintWriter pw) {
    }
}
