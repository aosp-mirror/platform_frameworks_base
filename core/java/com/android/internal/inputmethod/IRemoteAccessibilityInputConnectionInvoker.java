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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;

import com.android.internal.infra.AndroidFuture;

import java.util.Objects;

final class IRemoteAccessibilityInputConnectionInvoker {
    @NonNull
    private final IRemoteAccessibilityInputConnection mConnection;
    private final int mSessionId;

    private IRemoteAccessibilityInputConnectionInvoker(
            @NonNull IRemoteAccessibilityInputConnection inputContext, int sessionId) {
        mConnection = inputContext;
        mSessionId = sessionId;
    }

    /**
     * Creates a new instance of {@link IRemoteAccessibilityInputConnectionInvoker} for the given
     * {@link IRemoteAccessibilityInputConnection}.
     *
     * @param connection {@link IRemoteAccessibilityInputConnection} to be wrapped.
     * @return A new instance of {@link IRemoteAccessibilityInputConnectionInvoker}.
     */
    public static IRemoteAccessibilityInputConnectionInvoker create(
            @NonNull IRemoteAccessibilityInputConnection connection) {
        Objects.requireNonNull(connection);
        return new IRemoteAccessibilityInputConnectionInvoker(connection, 0);
    }

    /**
     * Creates a new instance of {@link IRemoteAccessibilityInputConnectionInvoker} with the given
     * {@code sessionId}.
     *
     * @param sessionId the new session ID to be used.
     * @return A new instance of {@link IRemoteAccessibilityInputConnectionInvoker}.
     */
    @NonNull
    public IRemoteAccessibilityInputConnectionInvoker cloneWithSessionId(int sessionId) {
        return new IRemoteAccessibilityInputConnectionInvoker(mConnection, sessionId);
    }

    /**
     * @param connection {@code IRemoteAccessibilityInputConnection} to be compared with
     * @return {@code true} if the underlying {@code IRemoteAccessibilityInputConnection} is the
     *         same. {@code false} if {@code connection} is {@code null}.
     */
    @AnyThread
    public boolean isSameConnection(@NonNull IRemoteAccessibilityInputConnection connection) {
        if (connection == null) {
            return false;
        }
        return mConnection.asBinder() == connection.asBinder();
    }

    @NonNull
    InputConnectionCommandHeader createHeader() {
        return new InputConnectionCommandHeader(mSessionId);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#commitText(InputConnectionCommandHeader,
     * int, CharSequence)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @param textAttribute The extra information about the text.
     */
    @AnyThread
    public void commitText(CharSequence text, int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        try {
            mConnection.commitText(createHeader(), text, newCursorPosition, textAttribute);
        } catch (RemoteException e) {
        }
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#setSelection(InputConnectionCommandHeader,
     * int, int)}.
     *
     * @param start {@code start} parameter to be passed.
     * @param end {@code start} parameter to be passed.
     */
    @AnyThread
    public void setSelection(int start, int end) {
        try {
            mConnection.setSelection(createHeader(), start, end);
        } catch (RemoteException e) {
        }
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#getSurroundingText(
     * InputConnectionCommandHeader, int, int, int, AndroidFuture)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link AndroidFuture< SurroundingText >} that can be used to retrieve the
     *         invocation result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<SurroundingText> getSurroundingText(int beforeLength, int afterLength,
            int flags) {
        final AndroidFuture<SurroundingText> future = new AndroidFuture<>();
        try {
            mConnection.getSurroundingText(createHeader(), beforeLength, afterLength, flags,
                    future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#deleteSurroundingText(
     * InputConnectionCommandHeader, int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     */
    @AnyThread
    public void deleteSurroundingText(int beforeLength, int afterLength) {
        try {
            mConnection.deleteSurroundingText(createHeader(), beforeLength, afterLength);
        } catch (RemoteException e) {
        }
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#sendKeyEvent(
     * InputConnectionCommandHeader, KeyEvent)}.
     *
     * @param event {@code event} parameter to be passed.
     */
    @AnyThread
    public void sendKeyEvent(KeyEvent event) {
        try {
            mConnection.sendKeyEvent(createHeader(), event);
        } catch (RemoteException e) {
        }
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#performEditorAction(
     * InputConnectionCommandHeader, int)}.
     *
     * @param actionCode {@code start} parameter to be passed.
     */
    @AnyThread
    public void performEditorAction(int actionCode) {
        try {
            mConnection.performEditorAction(createHeader(), actionCode);
        } catch (RemoteException e) {
        }
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#performContextMenuAction(
     * InputConnectionCommandHeader, int)}.
     *
     * @param id {@code id} parameter to be passed.
     */
    @AnyThread
    public void performContextMenuAction(int id) {
        try {
            mConnection.performContextMenuAction(createHeader(), id);
        } catch (RemoteException e) {
        }
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#getCursorCapsMode(
     * InputConnectionCommandHeader, int, AndroidFuture)}.
     *
     * @param reqModes {@code reqModes} parameter to be passed.
     * @return {@link AndroidFuture<Integer>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<Integer> getCursorCapsMode(int reqModes) {
        final AndroidFuture<Integer> future = new AndroidFuture<>();
        try {
            mConnection.getCursorCapsMode(createHeader(), reqModes, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#clearMetaKeyStates(
     * InputConnectionCommandHeader, int)}.
     *
     * @param states {@code states} parameter to be passed.
     */
    @AnyThread
    public void clearMetaKeyStates(int states) {
        try {
            mConnection.clearMetaKeyStates(createHeader(), states);
        } catch (RemoteException e) {
        }
    }
}
