/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.InputBindResult;

/**
 * A stateless thin wrapper for {@link IInputMethodClient}.
 */
final class IInputMethodClientInvoker {
    private static final String TAG = InputMethodManagerService.TAG;
    private static final boolean DEBUG = InputMethodManagerService.DEBUG;

    @NonNull
    private final IInputMethodClient mTarget;
    private final boolean mIsProxy;

    @AnyThread
    @Nullable
    static IInputMethodClientInvoker create(@Nullable IInputMethodClient inputMethodClient) {
        if (inputMethodClient == null) {
            return null;
        }
        return new IInputMethodClientInvoker(inputMethodClient);
    }

    private IInputMethodClientInvoker(@NonNull IInputMethodClient target) {
        mTarget = target;
        mIsProxy = Binder.isProxy(mTarget);
    }

    /**
     * A simplified version of {@link android.os.Debug#getCaller()}.
     *
     * @return method name of the caller.
     */
    @AnyThread
    private static String getCallerMethodName() {
        final StackTraceElement[] callStack = Thread.currentThread().getStackTrace();
        if (callStack.length <= 4) {
            return "<bottom of call stack>";
        }
        return callStack[4].getMethodName();
    }

    @AnyThread
    private static void logRemoteException(@NonNull RemoteException e) {
        if (DEBUG || !(e instanceof DeadObjectException)) {
            Slog.w(TAG, "IPC failed at IInputMethodClientInvoker#" + getCallerMethodName(), e);
        }
    }

    @AnyThread
    void onBindMethod(@NonNull InputBindResult res) {
        try {
            mTarget.onBindMethod(res);
        } catch (RemoteException e) {
            logRemoteException(e);
        } finally {
            // Dispose the channel if the input method is not local to this process
            // because the remote proxy will get its own copy when unparceled.
            if (res.channel != null && mIsProxy) {
                res.channel.dispose();
            }
        }
    }

    @AnyThread
    void onBindAccessibilityService(@NonNull InputBindResult res, int id) {
        try {
            mTarget.onBindAccessibilityService(res, id);
        } catch (RemoteException e) {
            logRemoteException(e);
        } finally {
            // Dispose the channel if the accessibility service is not local to this process
            // because the remote proxy will get its own copy when unparceled.
            if (res.channel != null && mIsProxy) {
                res.channel.dispose();
            }
        }
    }

    @AnyThread
    void onUnbindMethod(int sequence, int unbindReason) {
        try {
            mTarget.onUnbindMethod(sequence, unbindReason);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void onUnbindAccessibilityService(int sequence, int id) {
        try {
            mTarget.onUnbindAccessibilityService(sequence, id);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void setActive(boolean active, boolean fullscreen, boolean reportToImeController) {
        try {
            mTarget.setActive(active, fullscreen, reportToImeController);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void scheduleStartInputIfNecessary(boolean fullscreen) {
        try {
            mTarget.scheduleStartInputIfNecessary(fullscreen);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void reportFullscreenMode(boolean fullscreen) {
        try {
            mTarget.reportFullscreenMode(fullscreen);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void updateVirtualDisplayToScreenMatrix(int bindSequence, float[] matrixValues) {
        try {
            mTarget.updateVirtualDisplayToScreenMatrix(bindSequence, matrixValues);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void setImeTraceEnabled(boolean enabled) {
        try {
            mTarget.setImeTraceEnabled(enabled);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void throwExceptionFromSystem(String message) {
        try {
            mTarget.throwExceptionFromSystem(message);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    IBinder asBinder() {
        return mTarget.asBinder();
    }
}
