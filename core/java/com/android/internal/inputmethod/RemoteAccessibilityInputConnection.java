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
import android.annotation.DurationMillisLong;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.KeyEvent;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.view.IInputMethod;

import java.util.concurrent.CompletableFuture;

/**
 * A wrapper object for A11y IME.
 *
 * <p>This needs to be public to be referenced from {@link android.app.UiAutomation}.</p>
 */
public final class RemoteAccessibilityInputConnection {
    private static final String TAG = "RemoteA11yInputConnection";

    @DurationMillisLong
    private static final int MAX_WAIT_TIME_MILLIS = 2000;

    @NonNull
    IRemoteAccessibilityInputConnectionInvoker mInvoker;

    /**
     * Signaled when the system decided to take away IME focus from the target app.
     *
     * <p>This is expected to be signaled immediately when the IME process receives
     * {@link IInputMethod#unbindInput()}.</p>
     */
    @NonNull
    private final CancellationGroup mCancellationGroup;

    public RemoteAccessibilityInputConnection(
            @NonNull IRemoteAccessibilityInputConnection connection,
            @NonNull CancellationGroup cancellationGroup) {
        mInvoker = IRemoteAccessibilityInputConnectionInvoker.create(connection);
        mCancellationGroup = cancellationGroup;
    }

    public RemoteAccessibilityInputConnection(@NonNull RemoteAccessibilityInputConnection original,
            int sessionId) {
        mInvoker = original.mInvoker.cloneWithSessionId(sessionId);
        mCancellationGroup = original.mCancellationGroup;
    }

    /**
     * Test if this object holds the given {@link IRemoteAccessibilityInputConnection} or not.
     *
     * @param connection {@link IRemoteAccessibilityInputConnection} to be tested.
     * @return {@code true} if this object holds the same object.
     */
    @AnyThread
    public boolean isSameConnection(@NonNull IRemoteAccessibilityInputConnection connection) {
        return mInvoker.isSameConnection(connection);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#commitText(InputConnectionCommandHeader,
     * CharSequence, int, TextAttribute)}.
     *
     * @param text The {@code "text"} parameter to be passed.
     * @param newCursorPosition The {@code "newCursorPosition"} parameter to be passed.
     * @param textAttribute The {@code "textAttribute"} parameter to be passed.
     */
    @AnyThread
    public void commitText(@NonNull CharSequence text, int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        mInvoker.commitText(text, newCursorPosition, textAttribute);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#setSelection(InputConnectionCommandHeader,
     * int, int)}.
     *
     * @param start The {@code "start"} parameter to be passed.
     * @param end The {@code "end"} parameter to be passed.
     */
    @AnyThread
    public void setSelection(int start, int end) {
        mInvoker.setSelection(start, end);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#getSurroundingText(
     * InputConnectionCommandHeader, int, int, int, AndroidFuture)}.
     *
     * @param beforeLength The {@code "beforeLength"} parameter to be passed.
     * @param afterLength The {@code "afterLength"} parameter to be passed.
     * @param flags The {@code "flags"} parameter to be passed.
     * @return The {@link SurroundingText} object returned from the target application.
     */
    @AnyThread
    public SurroundingText getSurroundingText(
            @IntRange(from = 0) int beforeLength, @IntRange(from = 0) int afterLength, int flags) {
        if (beforeLength < 0) {
            throw new IllegalArgumentException("beforeLength cannot be negative but was "
                + beforeLength);
        }
        if (afterLength < 0) {
            throw new IllegalArgumentException("afterLength cannot be negative but was "
                    + afterLength);
        }
        if (mCancellationGroup.isCanceled()) {
            return null;
        }

        final CompletableFuture<SurroundingText> value = mInvoker.getSurroundingText(beforeLength,
                afterLength, flags);
        return CompletableFutureUtil.getResultOrNull(
                value, TAG, "getSurroundingText()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#deleteSurroundingText(
     * InputConnectionCommandHeader, int, int)}.
     *
     * @param beforeLength The {@code "beforeLength"} parameter to be passed.
     * @param afterLength The {@code "afterLength"} parameter to be passed.
     */
    @AnyThread
    public void deleteSurroundingText(int beforeLength, int afterLength) {
        mInvoker.deleteSurroundingText(beforeLength, afterLength);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#sendKeyEvent(InputConnectionCommandHeader,
     * KeyEvent)}.
     *
     * @param event The {@code "event"} parameter to be passed.
     */
    @AnyThread
    public void sendKeyEvent(KeyEvent event) {
        mInvoker.sendKeyEvent(event);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#performEditorAction(
     * InputConnectionCommandHeader, int)}.
     *
     * @param actionCode The {@code "actionCode"} parameter to be passed.
     */
    @AnyThread
    public void performEditorAction(int actionCode) {
        mInvoker.performEditorAction(actionCode);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#performContextMenuAction(
     * InputConnectionCommandHeader, int)}.
     *
     * @param id The {@code "id"} parameter to be passed.
     */
    @AnyThread
    public void performContextMenuAction(int id) {
        mInvoker.performContextMenuAction(id);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#getCursorCapsMode(
     * InputConnectionCommandHeader, int, AndroidFuture)}.
     *
     * @param reqModes The {@code "reqModes"} parameter to be passed.
     * @return integer result returned from the target application.
     */
    @AnyThread
    public int getCursorCapsMode(int reqModes) {
        if (mCancellationGroup.isCanceled()) {
            return 0;
        }

        final CompletableFuture<Integer> value = mInvoker.getCursorCapsMode(reqModes);

        return CompletableFutureUtil.getResultOrZero(
                value, TAG, "getCursorCapsMode()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);
    }

    /**
     * Invokes {@link IRemoteAccessibilityInputConnection#clearMetaKeyStates(
     * InputConnectionCommandHeader, int)}.
     *
     * @param states The {@code "states"} parameter to be passed.
     */
    @AnyThread
    public void clearMetaKeyStates(int states) {
        mInvoker.clearMetaKeyStates(states);
    }
}
