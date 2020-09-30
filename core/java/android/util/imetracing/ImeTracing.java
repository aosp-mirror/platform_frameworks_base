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

import android.app.ActivityThread;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.ShellCommand;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.view.IInputMethodManager;

/**
 *
 * An abstract class that declares the methods for ime trace related operations - enable trace,
 * schedule trace and add new trace to buffer. Both the client and server side classes can use
 * it by getting an implementation through {@link ImeTracing#getInstance()}.
 *
 * @hide
 */
public abstract class ImeTracing {

    static final String TAG = "imeTracing";
    public static final String PROTO_ARG = "--proto-com-android-imetracing";

    private static ImeTracing sInstance;
    static boolean sEnabled = false;
    IInputMethodManager mService;

    ImeTracing() throws ServiceNotFoundException {
        mService = IInputMethodManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.INPUT_METHOD_SERVICE));
    }

    /**
     * Returns an instance of {@link ImeTracingServerImpl} when called from a server side class
     * and an instance of {@link ImeTracingClientImpl} when called from a client side class.
     * Useful to schedule a dump for next frame or save a dump when certain methods are called.
     *
     * @return Instance of one of the children classes of {@link ImeTracing}
     */
    public static ImeTracing getInstance() {
        if (sInstance == null) {
            try {
                sInstance = isSystemProcess()
                        ? new ImeTracingServerImpl() : new ImeTracingClientImpl();
            } catch (RemoteException | ServiceNotFoundException e) {
                Log.e(TAG, "Exception while creating ImeTracing instance", e);
            }
        }
        return sInstance;
    }

    /**
     * Sends request to start proto dump to {@link ImeTracingServerImpl} when called from a
     * server process and to {@link ImeTracingClientImpl} when called from a client process.
     */
    public abstract void triggerDump();

    /**
     * @param proto dump to be added to the buffer
     */
    public abstract void addToBuffer(ProtoOutputStream proto);

    /**
     * @param shell The shell command to process
     * @return {@code 0} if the command was successfully processed, {@code -1} otherwise
     */
    public abstract int onShellCommand(ShellCommand shell);

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
        return mService != null;
    }

    private static boolean isSystemProcess() {
        return ActivityThread.isSystem();
    }
}
