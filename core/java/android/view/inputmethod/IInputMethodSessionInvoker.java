/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IRemoteInputConnection;

/**
 * This class wrap the {@link IInputMethodSession} object from {@link InputMethodManager}.
 * Using current {@link IInputMethodSession} object to communicate with
 * {@link android.inputmethodservice.InputMethodService}.
 */
final class IInputMethodSessionInvoker {

    private static final String TAG = "InputMethodSessionWrapper";

    /**
     * The actual instance of the method to make calls on it.
     */
    @NonNull
    private final IInputMethodSession mSession;

    /**
     * An optional {@link Handler} to dispatch {@link IInputMethodSession} method invocations to
     * a background thread to emulate async (one-way) {@link Binder} call.
     *
     * {@code null} if {@code Binder.isProxy(mSession)} is {@code true}.
     */
    @Nullable
    private final Handler mCustomHandler;

    private static final Object sAsyncBinderEmulationHandlerLock = new Object();

    @GuardedBy("sAsyncBinderEmulationHandlerLock")
    @Nullable
    private static Handler sAsyncBinderEmulationHandler;

    private IInputMethodSessionInvoker(@NonNull IInputMethodSession inputMethodSession,
            @Nullable Handler customHandler) {
        mSession = inputMethodSession;
        mCustomHandler = customHandler;
    }

    /**
     * Create a {@link IInputMethodSessionInvoker} instance if applicability.
     *
     * @param inputMethodSession {@link IInputMethodSession} object to be wrapped.
     * @return an instance of {@link IInputMethodSessionInvoker} if {@code inputMethodSession} is
     *         not {@code null}. {@code null} otherwise.
     */
    @Nullable
    public static IInputMethodSessionInvoker createOrNull(
            @NonNull IInputMethodSession inputMethodSession) {

        final Handler customHandler;
        if (inputMethodSession != null && !Binder.isProxy(inputMethodSession)) {
            synchronized (sAsyncBinderEmulationHandlerLock) {
                if (sAsyncBinderEmulationHandler == null) {
                    final HandlerThread thread = new HandlerThread("IMM.binder-emu");
                    thread.start();
                    // Use an async handler instead of Handler#getThreadHandler().
                    sAsyncBinderEmulationHandler = Handler.createAsync(thread.getLooper());
                }
                customHandler = sAsyncBinderEmulationHandler;
            }
        } else {
            customHandler = null;
        }

        return inputMethodSession != null
                ? new IInputMethodSessionInvoker(inputMethodSession, customHandler) : null;
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
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void updateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        if (mCustomHandler == null) {
            updateCursorAnchorInfoInternal(cursorAnchorInfo);
        } else {
            mCustomHandler.post(() -> updateCursorAnchorInfoInternal(cursorAnchorInfo));
        }
    }

    @AnyThread
    private void updateCursorAnchorInfoInternal(CursorAnchorInfo cursorAnchorInfo) {
        try {
            mSession.updateCursorAnchorInfo(cursorAnchorInfo);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void displayCompletions(CompletionInfo[] completions) {
        if (mCustomHandler == null) {
            displayCompletionsInternal(completions);
        } else {
            mCustomHandler.post(() -> displayCompletionsInternal(completions));
        }
    }

    @AnyThread
    void displayCompletionsInternal(CompletionInfo[] completions) {
        try {
            mSession.displayCompletions(completions);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void updateExtractedText(int token, ExtractedText text) {
        if (mCustomHandler == null) {
            updateExtractedTextInternal(token, text);
        } else {
            mCustomHandler.post(() -> updateExtractedTextInternal(token, text));
        }
    }

    @AnyThread
    private void updateExtractedTextInternal(int token, ExtractedText text) {
        try {
            mSession.updateExtractedText(token, text);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void appPrivateCommand(String action, Bundle data) {
        if (mCustomHandler == null) {
            appPrivateCommandInternal(action, data);
        } else {
            mCustomHandler.post(() -> appPrivateCommandInternal(action, data));
        }
    }

    @AnyThread
    private void appPrivateCommandInternal(String action, Bundle data) {
        try {
            mSession.appPrivateCommand(action, data);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void viewClicked(boolean focusChanged) {
        if (mCustomHandler == null) {
            viewClickedInternal(focusChanged);
        } else {
            mCustomHandler.post(() -> viewClickedInternal(focusChanged));
        }
    }

    @AnyThread
    private void viewClickedInternal(boolean focusChanged) {
        try {
            mSession.viewClicked(focusChanged);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void updateCursor(Rect newCursor) {
        if (mCustomHandler == null) {
            updateCursorInternal(newCursor);
        } else {
            mCustomHandler.post(() -> updateCursorInternal(newCursor));
        }
    }

    @AnyThread
    private void updateCursorInternal(Rect newCursor) {
        try {
            mSession.updateCursor(newCursor);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
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
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void invalidateInput(EditorInfo editorInfo, IRemoteInputConnection inputConnection,
            int sessionId) {
        if (mCustomHandler == null) {
            invalidateInputInternal(editorInfo, inputConnection, sessionId);
        } else {
            mCustomHandler.post(() -> invalidateInputInternal(editorInfo, inputConnection,
                    sessionId));
        }
    }

    @AnyThread
    private void invalidateInputInternal(EditorInfo editorInfo,
            IRemoteInputConnection inputConnection, int sessionId) {
        try {
            mSession.invalidateInput(editorInfo, inputConnection, sessionId);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    /**
     * @return {@link IInputMethodSession#toString()} as a debug string.
     */
    @AnyThread
    @NonNull
    @Override
    public String toString() {
        return mSession.toString();
    }
}
