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
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.InputBindResult;

/**
 * A stateless thin wrapper for {@link IInputMethodClient}.
 *
 * <p>This class also takes care of a special case when system_server is also an IME client. In this
 * scenario methods defined in {@link IInputMethodClient} are invoked as an direct (sync) invocation
 * despite the fact that all of them are marked {@code oneway}. This can easily cause dead lock
 * because {@link InputMethodManagerService} assumes that it's safe to make one-way IPCs while
 * holding the lock. This assumption becomes invalid when {@link IInputMethodClient} is not a
 * {@link android.os.BinderProxy}.</p>
 *
 * <p>To work around such a special scenario, this wrapper re-dispatch the method invocation into
 * the given {@link Handler} thread if {@link IInputMethodClient} is not a proxy object. Be careful
 * about its call ordering characteristics.</p>
 */
// TODO(b/322895594) Mark this class to be host side test compatible once enabling fw/services in
//     Ravenwood (mark this class with @RavenwoodKeepWholeClass and #create with @RavenwoodReplace,
//     so Ravenwood can properly swap create method during test execution).
final class IInputMethodClientInvoker {
    private static final String TAG = InputMethodManagerService.TAG;
    private static final boolean DEBUG = InputMethodManagerService.DEBUG;

    @NonNull
    private final IInputMethodClient mTarget;
    private final boolean mIsProxy;
    @Nullable
    private final Handler mHandler;

    @AnyThread
    @Nullable
    static IInputMethodClientInvoker create(@Nullable IInputMethodClient inputMethodClient,
            @NonNull Handler handler) {
        if (inputMethodClient == null) {
            return null;
        }
        final boolean isProxy = Binder.isProxy(inputMethodClient);
        return new IInputMethodClientInvoker(inputMethodClient, isProxy, isProxy ? null : handler);
    }

    @AnyThread
    @Nullable
    static IInputMethodClientInvoker create$ravenwood(
            @Nullable IInputMethodClient inputMethodClient, @NonNull Handler handler) {
        if (inputMethodClient == null) {
            return null;
        }
        return new IInputMethodClientInvoker(inputMethodClient, true, null);
    }

    private IInputMethodClientInvoker(@NonNull IInputMethodClient target,
            boolean isProxy, @Nullable Handler handler) {
        mTarget = target;
        mIsProxy = isProxy;
        mHandler = handler;
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
        if (mIsProxy) {
            onBindMethodInternal(res);
        } else {
            mHandler.post(() -> onBindMethodInternal(res));
        }
    }

    @AnyThread
    private void onBindMethodInternal(@NonNull InputBindResult res) {
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
    void onStartInputResult(@NonNull InputBindResult res, int startInputSeq) {
        if (mIsProxy) {
            onStartInputResultInternal(res, startInputSeq);
        } else {
            mHandler.post(() -> onStartInputResultInternal(res, startInputSeq));
        }
    }

    @AnyThread
    private void onStartInputResultInternal(@NonNull InputBindResult res, int startInputSeq) {
        try {
            mTarget.onStartInputResult(res, startInputSeq);
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
        if (mIsProxy) {
            onBindAccessibilityServiceInternal(res, id);
        } else {
            mHandler.post(() -> onBindAccessibilityServiceInternal(res, id));
        }
    }

    @AnyThread
    private void onBindAccessibilityServiceInternal(@NonNull InputBindResult res, int id) {
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
        if (mIsProxy) {
            onUnbindMethodInternal(sequence, unbindReason);
        } else {
            mHandler.post(() -> onUnbindMethodInternal(sequence, unbindReason));
        }
    }

    @AnyThread
    private void onUnbindMethodInternal(int sequence, int unbindReason) {
        try {
            mTarget.onUnbindMethod(sequence, unbindReason);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void onUnbindAccessibilityService(int sequence, int id) {
        if (mIsProxy) {
            onUnbindAccessibilityServiceInternal(sequence, id);
        } else {
            mHandler.post(() -> onUnbindAccessibilityServiceInternal(sequence, id));
        }
    }

    @AnyThread
    private void onUnbindAccessibilityServiceInternal(int sequence, int id) {
        try {
            mTarget.onUnbindAccessibilityService(sequence, id);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void setActive(boolean active, boolean fullscreen) {
        if (mIsProxy) {
            setActiveInternal(active, fullscreen);
        } else {
            mHandler.post(() -> setActiveInternal(active, fullscreen));
        }
    }

    @AnyThread
    private void setActiveInternal(boolean active, boolean fullscreen) {
        try {
            mTarget.setActive(active, fullscreen);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void setInteractive(boolean interactive, boolean fullscreen) {
        if (mIsProxy) {
            setInteractiveInternal(interactive, fullscreen);
        } else {
            mHandler.post(() -> setInteractiveInternal(interactive, fullscreen));
        }
    }

    @AnyThread
    private void setInteractiveInternal(boolean interactive, boolean fullscreen) {
        try {
            mTarget.setInteractive(interactive, fullscreen);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void setImeVisibility(boolean visible) {
        if (mIsProxy) {
            setImeVisibilityInternal(visible);
        } else {
            mHandler.post(() -> setImeVisibilityInternal(visible));
        }
    }

    @AnyThread
    private void setImeVisibilityInternal(boolean visible) {
        try {
            mTarget.setImeVisibility(visible);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void scheduleStartInputIfNecessary(boolean fullscreen) {
        if (mIsProxy) {
            scheduleStartInputIfNecessaryInternal(fullscreen);
        } else {
            mHandler.post(() -> scheduleStartInputIfNecessaryInternal(fullscreen));
        }
    }

    @AnyThread
    private void scheduleStartInputIfNecessaryInternal(boolean fullscreen) {
        try {
            mTarget.scheduleStartInputIfNecessary(fullscreen);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void reportFullscreenMode(boolean fullscreen) {
        if (mIsProxy) {
            reportFullscreenModeInternal(fullscreen);
        } else {
            mHandler.post(() -> reportFullscreenModeInternal(fullscreen));
        }
    }

    @AnyThread
    private void reportFullscreenModeInternal(boolean fullscreen) {
        try {
            mTarget.reportFullscreenMode(fullscreen);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void setImeTraceEnabled(boolean enabled) {
        if (mIsProxy) {
            setImeTraceEnabledInternal(enabled);
        } else {
            mHandler.post(() -> setImeTraceEnabledInternal(enabled));
        }
    }

    @AnyThread
    private void setImeTraceEnabledInternal(boolean enabled) {
        try {
            mTarget.setImeTraceEnabled(enabled);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void throwExceptionFromSystem(String message) {
        if (mIsProxy) {
            throwExceptionFromSystemInternal(message);
        } else {
            mHandler.post(() -> throwExceptionFromSystemInternal(message));
        }
    }

    @AnyThread
    private void throwExceptionFromSystemInternal(String message) {
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
