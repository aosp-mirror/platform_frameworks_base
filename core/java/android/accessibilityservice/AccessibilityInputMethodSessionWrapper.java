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

package android.accessibilityservice;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;

import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;

import java.util.concurrent.atomic.AtomicReference;

final class AccessibilityInputMethodSessionWrapper extends IAccessibilityInputMethodSession.Stub {
    private final Handler mHandler;

    @NonNull
    private final AtomicReference<AccessibilityInputMethodSession> mSessionRef;

    AccessibilityInputMethodSessionWrapper(
            @NonNull Looper looper, @NonNull AccessibilityInputMethodSession session) {
        mSessionRef = new AtomicReference<>(session);
        mHandler = Handler.createAsync(looper);
    }

    @AnyThread
    @Nullable
    AccessibilityInputMethodSession getSession() {
        return mSessionRef.get();
    }

    @Override
    public void updateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        if (mHandler.getLooper().isCurrentThread()) {
            doUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart,
                    candidatesEnd);
        } else {
            mHandler.post(() -> doUpdateSelection(oldSelStart, oldSelEnd, newSelStart,
                    newSelEnd, candidatesStart, candidatesEnd));
        }
    }

    private void doUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        final AccessibilityInputMethodSession session = mSessionRef.get();
        if (session != null) {
            session.updateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart,
                    candidatesEnd);
        }
    }

    @Override
    public void finishInput() {
        if (mHandler.getLooper().isCurrentThread()) {
            doFinishInput();
        } else {
            mHandler.post(this::doFinishInput);
        }
    }

    private void doFinishInput() {
        final AccessibilityInputMethodSession session = mSessionRef.get();
        if (session != null) {
            session.finishInput();
        }
    }

    @Override
    public void finishSession() {
        if (mHandler.getLooper().isCurrentThread()) {
            doFinishSession();
        } else {
            mHandler.post(this::doFinishSession);
        }
    }

    private void doFinishSession() {
        mSessionRef.set(null);
    }

    @Override
    public void invalidateInput(EditorInfo editorInfo,
            IRemoteAccessibilityInputConnection connection, int sessionId) {
        if (mHandler.getLooper().isCurrentThread()) {
            doInvalidateInput(editorInfo, connection, sessionId);
        } else {
            mHandler.post(() -> doInvalidateInput(editorInfo, connection, sessionId));
        }
    }

    private void doInvalidateInput(EditorInfo editorInfo,
            IRemoteAccessibilityInputConnection connection, int sessionId) {
        final AccessibilityInputMethodSession session = mSessionRef.get();
        if (session != null) {
            session.invalidateInput(editorInfo, connection, sessionId);
        }
    }
}
