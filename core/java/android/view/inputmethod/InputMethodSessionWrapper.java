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
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.view.IInputMethodSession;

/**
 * This class wrap the {@link IInputMethodSession} object from {@link InputMethodManager}.
 * Using current {@link IInputMethodSession} object to communicate with
 * {@link android.inputmethodservice.InputMethodService}.
 */
final class InputMethodSessionWrapper {

    private static final String TAG = "InputMethodSessionWrapper";

    /**
     * The actual instance of the method to make calls on it.
     */
    @NonNull
    private final IInputMethodSession mSession;

    private InputMethodSessionWrapper(@NonNull IInputMethodSession inputMethodSession) {
        mSession = inputMethodSession;
    }

    /**
     * Create a {@link InputMethodSessionWrapper} instance if applicability.
     *
     * @param inputMethodSession {@link IInputMethodSession} object to be wrapped.
     * @return an instance of {@link InputMethodSessionWrapper} if {@code inputMethodSession} is not
     *         {@code null}. {@code null} otherwise.
     */
    @Nullable
    public static InputMethodSessionWrapper createOrNull(
            @NonNull IInputMethodSession inputMethodSession) {
        return inputMethodSession != null ? new InputMethodSessionWrapper(inputMethodSession)
                : null;
    }

    @AnyThread
    void finishInput() {
        try {
            mSession.finishInput();
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void updateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        try {
            mSession.updateCursorAnchorInfo(cursorAnchorInfo);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void displayCompletions(CompletionInfo[] completions) {
        try {
            mSession.displayCompletions(completions);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void updateExtractedText(int token, ExtractedText text) {
        try {
            mSession.updateExtractedText(token, text);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void appPrivateCommand(String action, Bundle data) {
        try {
            mSession.appPrivateCommand(action, data);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void notifyImeHidden() {
        try {
            mSession.notifyImeHidden();
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void viewClicked(boolean focusChanged) {
        try {
            mSession.viewClicked(focusChanged);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void updateCursor(Rect newCursor) {
        try {
            mSession.updateCursor(newCursor);
        } catch (RemoteException e) {
            Log.w(TAG, "IME died", e);
        }
    }

    @AnyThread
    void updateSelection(int oldSelStart, int oldSelEnd, int selStart, int selEnd,
            int candidatesStart, int candidatesEnd) {
        try {
            mSession.updateSelection(
                    oldSelStart, oldSelEnd, selStart, selEnd, candidatesStart, candidatesEnd);
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
