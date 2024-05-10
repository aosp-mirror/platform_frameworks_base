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

package android.view.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;

final class IAccessibilityInputMethodSessionInvoker {
    private static final String TAG = "IAccessibilityInputMethodSessionInvoker";

    /**
     * The actual instance of the method to make calls on it.
     */
    @NonNull
    private final IAccessibilityInputMethodSession mSession;

    /**
     * An optional {@link Handler} to dispatch {@link IAccessibilityInputMethodSession} method
     * invocations to a background thread to emulate async (one-way) {@link Binder} call.
     *
     * {@code null} if {@code Binder.isProxy(mSession)} is {@code true}.
     */
    @Nullable
    private final Handler mCustomHandler;

    private static final Object sAsyncBinderEmulationHandlerLock = new Object();

    @GuardedBy("sAsyncBinderEmulationHandlerLock")
    @Nullable
    private static Handler sAsyncBinderEmulationHandler;

    private IAccessibilityInputMethodSessionInvoker(
            @NonNull IAccessibilityInputMethodSession session,
            @Nullable Handler customHandler) {
        mSession = session;
        mCustomHandler = customHandler;
    }

    /**
     * Create a {@link IAccessibilityInputMethodSessionInvoker} instance if applicable.
     *
     * @param session {@link IAccessibilityInputMethodSession} object to be wrapped.
     * @return an instance of {@link IAccessibilityInputMethodSessionInvoker} if
     *         {@code inputMethodSession} is not {@code null}. {@code null} otherwise.
     */
    @Nullable
    public static IAccessibilityInputMethodSessionInvoker createOrNull(
            @NonNull IAccessibilityInputMethodSession session) {
        final Handler customHandler;
        if (session != null && !Binder.isProxy(session)) {
            synchronized (sAsyncBinderEmulationHandlerLock) {
                if (sAsyncBinderEmulationHandler == null) {
                    final HandlerThread thread = new HandlerThread("IMM.IAIMS");
                    thread.start();
                    // Use an async handler instead of Handler#getThreadHandler().
                    sAsyncBinderEmulationHandler = Handler.createAsync(thread.getLooper());
                }
                customHandler = sAsyncBinderEmulationHandler;
            }
        } else {
            customHandler = null;
        }

        return session == null
                ? null : new IAccessibilityInputMethodSessionInvoker(session, customHandler);
    }

    @AnyThread
    void finishInput() {
        if (mCustomHandler == null) {
            finishInputInternal();
        } else {
            mCustomHandler.post(this::finishInputInternal);
        }
    }

    @AnyThread
    private void finishInputInternal() {
        try {
            mSession.finishInput();
        } catch (RemoteException e) {
            Log.w(TAG, "A11yIME died", e);
        }
    }

    @AnyThread
    void updateSelection(int oldSelStart, int oldSelEnd, int selStart, int selEnd,
            int candidatesStart, int candidatesEnd) {
        if (mCustomHandler == null) {
            updateSelectionInternal(
                    oldSelStart, oldSelEnd, selStart, selEnd, candidatesStart, candidatesEnd);
        } else {
            mCustomHandler.post(() -> updateSelectionInternal(
                    oldSelStart, oldSelEnd, selStart, selEnd, candidatesStart, candidatesEnd));
        }
    }

    @AnyThread
    private void updateSelectionInternal(int oldSelStart, int oldSelEnd, int selStart, int selEnd,
            int candidatesStart, int candidatesEnd) {
        try {
            mSession.updateSelection(
                    oldSelStart, oldSelEnd, selStart, selEnd, candidatesStart, candidatesEnd);
        } catch (RemoteException e) {
            Log.w(TAG, "A11yIME died", e);
        }
    }

    @AnyThread
    void invalidateInput(EditorInfo editorInfo, IRemoteAccessibilityInputConnection connection,
            int sessionId) {
        if (mCustomHandler == null) {
            invalidateInputInternal(editorInfo, connection, sessionId);
        } else {
            mCustomHandler.post(() -> invalidateInputInternal(editorInfo, connection, sessionId));
        }
    }

    @AnyThread
    private void invalidateInputInternal(EditorInfo editorInfo,
            IRemoteAccessibilityInputConnection connection, int sessionId) {
        try {
            mSession.invalidateInput(editorInfo, connection, sessionId);
        } catch (RemoteException e) {
            Log.w(TAG, "A11yIME died", e);
        }
    }
}
