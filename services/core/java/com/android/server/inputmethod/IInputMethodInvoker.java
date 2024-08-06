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
import android.os.ResultReceiver;
import android.util.Slog;
import android.view.InputChannel;
import android.view.MotionEvent;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodSubtype;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.inputmethod.IConnectionlessHandwritingCallback;
import com.android.internal.inputmethod.IInlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IInputMethodSessionCallback;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;

import java.util.List;

/**
 * A wrapper class to invoke IPCs defined in {@link IInputMethod}.
 */
final class IInputMethodInvoker {
    private static final String TAG = InputMethodManagerService.TAG;
    private static final boolean DEBUG = InputMethodManagerService.DEBUG;

    @AnyThread
    @Nullable
    static IInputMethodInvoker create(@Nullable IInputMethod inputMethod) {
        if (inputMethod == null) {
            return null;
        }
        if (!Binder.isProxy(inputMethod)) {
            // IInputMethodInvoker must be used only within the system_server and InputMethodService
            // must not be running in the system_server.  Therefore, "inputMethod" must be a Proxy.
            throw new UnsupportedOperationException(inputMethod + " must have been a BinderProxy.");
        }
        return new IInputMethodInvoker(inputMethod);
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
            Slog.w(TAG, "IPC failed at IInputMethodInvoker#" + getCallerMethodName(), e);
        }
    }

    @AnyThread
    static int getBinderIdentityHashCode(@Nullable IInputMethodInvoker obj) {
        if (obj == null) {
            return 0;
        }

        return System.identityHashCode(obj.mTarget);
    }

    @NonNull
    private final IInputMethod mTarget;

    private IInputMethodInvoker(@NonNull IInputMethod target) {
        mTarget = target;
    }

    @AnyThread
    @NonNull
    IBinder asBinder() {
        return mTarget.asBinder();
    }

    @AnyThread
    void initializeInternal(@NonNull IBinder token,
            @NonNull IInputMethodPrivilegedOperations privilegedOperations,
            @InputMethodNavButtonFlags int navigationBarFlags) {
        final IInputMethod.InitParams params = new IInputMethod.InitParams();
        params.token = token;
        params.privilegedOperations = privilegedOperations;
        params.navigationBarFlags = navigationBarFlags;
        try {
            mTarget.initializeInternal(params);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void onCreateInlineSuggestionsRequest(InlineSuggestionsRequestInfo requestInfo,
            IInlineSuggestionsRequestCallback cb) {
        try {
            mTarget.onCreateInlineSuggestionsRequest(requestInfo, cb);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void bindInput(InputBinding binding) {
        try {
            mTarget.bindInput(binding);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void unbindInput() {
        try {
            mTarget.unbindInput();
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void startInput(IBinder startInputToken, IRemoteInputConnection remoteInputConnection,
            EditorInfo editorInfo, boolean restarting,
            @InputMethodNavButtonFlags int navButtonFlags,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        final IInputMethod.StartInputParams params = new IInputMethod.StartInputParams();
        params.startInputToken = startInputToken;
        params.remoteInputConnection = remoteInputConnection;
        params.editorInfo = editorInfo;
        params.restarting = restarting;
        params.navigationBarFlags = navButtonFlags;
        params.imeDispatcher = imeDispatcher;
        try {
            mTarget.startInput(params);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void onNavButtonFlagsChanged(@InputMethodNavButtonFlags int navButtonFlags) {
        try {
            mTarget.onNavButtonFlagsChanged(navButtonFlags);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void createSession(InputChannel channel, IInputMethodSessionCallback callback) {
        try {
            mTarget.createSession(channel, callback);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void setSessionEnabled(IInputMethodSession session, boolean enabled) {
        try {
            mTarget.setSessionEnabled(session, enabled);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    // TODO(b/192412909): Convert this back to void method
    @AnyThread
    boolean showSoftInput(IBinder showInputToken, @NonNull ImeTracker.Token statsToken,
            @InputMethod.ShowFlags int flags, ResultReceiver resultReceiver) {
        try {
            mTarget.showSoftInput(showInputToken, statsToken, flags, resultReceiver);
        } catch (RemoteException e) {
            logRemoteException(e);
            return false;
        }
        return true;
    }

    // TODO(b/192412909): Convert this back to void method
    @AnyThread
    boolean hideSoftInput(IBinder hideInputToken, @NonNull ImeTracker.Token statsToken,
            int flags, ResultReceiver resultReceiver) {
        try {
            mTarget.hideSoftInput(hideInputToken, statsToken, flags, resultReceiver);
        } catch (RemoteException e) {
            logRemoteException(e);
            return false;
        }
        return true;
    }

    @AnyThread
    void updateEditorToolType(@MotionEvent.ToolType int toolType) {
        try {
            mTarget.updateEditorToolType(toolType);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void changeInputMethodSubtype(InputMethodSubtype subtype) {
        try {
            mTarget.changeInputMethodSubtype(subtype);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void canStartStylusHandwriting(int requestId,
            IConnectionlessHandwritingCallback connectionlessCallback,
            CursorAnchorInfo cursorAnchorInfo, boolean isConnectionlessForDelegation) {
        try {
            mTarget.canStartStylusHandwriting(requestId, connectionlessCallback, cursorAnchorInfo,
                    isConnectionlessForDelegation);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    boolean startStylusHandwriting(int requestId, InputChannel channel, List<MotionEvent> events) {
        try {
            mTarget.startStylusHandwriting(requestId, channel, events);
        } catch (RemoteException e) {
            logRemoteException(e);
            return false;
        }
        return true;
    }

    @AnyThread
    void commitHandwritingDelegationTextIfAvailable() {
        try {
            mTarget.commitHandwritingDelegationTextIfAvailable();
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void discardHandwritingDelegationText() {
        try {
            mTarget.discardHandwritingDelegationText();
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void initInkWindow() {
        try {
            mTarget.initInkWindow();
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void finishStylusHandwriting() {
        try {
            mTarget.finishStylusHandwriting();
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void removeStylusHandwritingWindow() {
        try {
            mTarget.removeStylusHandwritingWindow();
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }

    @AnyThread
    void setStylusWindowIdleTimeoutForTest(long timeout) {
        try {
            mTarget.setStylusWindowIdleTimeoutForTest(timeout);
        } catch (RemoteException e) {
            logRemoteException(e);
        }
    }
}
